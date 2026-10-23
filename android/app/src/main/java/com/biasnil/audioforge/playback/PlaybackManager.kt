package com.biasnil.audioforge.playback

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.biasnil.audioforge.audio.AudioEffectsProcessor
import com.biasnil.audioforge.audio.ChannelSettings
import com.biasnil.audioforge.audio.CorrectionSettings
import com.biasnil.audioforge.audio.KaraokeSettings
import com.biasnil.audioforge.audio.pitchFactor
import com.biasnil.audioforge.audio.ToneParams
import com.biasnil.audioforge.audio.ToneSettings
import com.biasnil.audioforge.audio.VisualizerTap
import com.biasnil.audioforge.audio.EqualizerSettings
import com.biasnil.audioforge.audio.SpatialParams
import com.biasnil.audioforge.audio.SpatialSettings
import com.biasnil.audioforge.audio.dbToLinear
import com.biasnil.audioforge.audio.parseReplayGainDb
import com.biasnil.audioforge.data.AppSettings
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.AnalysisStore
import com.biasnil.audioforge.data.ListenTracker
import com.biasnil.audioforge.data.PlayEntry
import com.biasnil.audioforge.data.PlayKind
import com.biasnil.audioforge.data.PlayLogStore
import com.biasnil.audioforge.data.RadioStation
import com.biasnil.audioforge.data.toTrack
import com.biasnil.audioforge.data.SongAnalysis
import com.biasnil.audioforge.data.SongDataStore
import com.biasnil.audioforge.data.SurroundPreset
import com.biasnil.audioforge.data.harmonicOrder
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

sealed interface SleepTimer {
    data object Off : SleepTimer
    data class Minutes(val minutes: Int) : SleepTimer
    /** Stop when the current song ends. */
    data object EndOfTrack : SleepTimer
}

/** Off -> A marked -> looping A..B -> Off. */
sealed interface AbRepeat {
    data object Off : AbRepeat
    data class ASet(val aMs: Long) : AbRepeat
    data class Looping(val aMs: Long, val bMs: Long) : AbRepeat
}

/** A song that couldn't be played, for the message the UI shows. */
sealed interface PlaybackError {
    /** The song's title (or file name). */
    val name: String

    /** It was skipped and the next song is playing. */
    data class Skipped(override val name: String) : PlaybackError

    /** Playback stopped on it. */
    data class Failed(override val name: String) : PlaybackError
}

/** What the UI shows about playback. The position isn't in here -- it's polled, see [PlaybackManager.positionMs]. */
data class PlaybackState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffleMode: ShuffleMode = ShuffleMode.Off,
    val repeatMode: RepeatMode = RepeatMode.Off,
    /** 0-200; above 100 boosts. */
    val volumePercent: Int = 100,
    /** Playback speed (pitch is kept). */
    val rate: Float = 1f,
    val sleepTimer: SleepTimer = SleepTimer.Off,
    /** System.currentTimeMillis() when a Minutes timer runs out. */
    val sleepEndsAtMs: Long? = null,
    val abRepeat: AbRepeat = AbRepeat.Off,
    /** Radio: what the station says is playing (its ICY StreamTitle), if it says. */
    val liveTitle: String? = null,
) {
    /** 10 minutes or more (audiobooks, mixes): ±15 s buttons, and the position is remembered. */
    val isLongTrack: Boolean get() = durationMs >= PlaybackManager.LONG_TRACK_MS
}

/** The queue as the Up next screen shows it. */
data class QueueSnapshot(
    /** Oldest first, not counting [current]. */
    val played: List<QueueEntry<Track>> = emptyList(),
    val current: QueueEntry<Track>? = null,
    /** In play order. In Random shuffle, only the songs added with Play next / Add to queue. */
    val upcoming: List<QueueEntry<Track>> = emptyList(),
    /** More songs follow [upcoming]: random picks, or Repeat All starting over. */
    val continuesAfter: Boolean = false,
    /** How many of [upcoming], from the start, were added with Play next / Add to queue ("Playing Next"). */
    val queuedCount: Int = 0,
    /** Where playback was started from ("Songs", an album's name...), for "Next From ...". */
    val contextName: String? = null,
)

/**
 * The desktop's AudioEngine + queue handling. Two ExoPlayers stand in for
 * the desktop's two sound slots: one plays the current track, the other
 * fades the next one in during a crossfade and then becomes the current
 * one. Each player runs an [AudioEffectsProcessor] (EQ + volume/ReplayGain
 * gain); player.volume is only used for the fade itself.
 *
 * Lives for the whole app process; [PlaybackService] exposes the active
 * player to the system as a media session. Main thread only.
 */
@OptIn(UnstableApi::class)
class PlaybackManager(
    context: Context,
    private val store: AppStore,
    private val queueStore: QueueStore,
    private val songData: SongDataStore,
    /** The listening log behind Settings -> Listening stats. */
    private val playLog: PlayLogStore,
    private val analysisStore: AnalysisStore,
    private val scope: CoroutineScope,
    /** Whether a song (by URI) is a podcast episode: those always resume and get marked played. */
    private val isPodcast: (String) -> Boolean = { false },
) {
    private val appContext: Context = context.applicationContext
    private val equalizer = EqualizerSettings()
    private val spatial = SpatialSettings()
    private val correction = CorrectionSettings()
    private val tone = ToneSettings()
    private val karaoke = KaraokeSettings()
    private val channelSettings = ChannelSettings()
    /** Karaoke key change, as the players' pitch factor (1 = the song's own key). */
    private var pitch = 1f
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    /** The phone's media volume (0..1) the loudness was last set for. */
    private var loudnessVolume = -1f
    /** Changes when Android's own spatial audio is switched on or off. */
    private var audioAttributes = audioAttributesFor(store.settings.value)

    /** One player + its effects chain + what it's playing. */
    private inner class Slot {
        val effects = AudioEffectsProcessor(equalizer, spatial, correction, tone, karaoke, channelSettings)
        val player: ExoPlayer = buildPlayer(appContext, effects, audioAttributes)
        var track: Track? = null
        var replayGainDb = 0f
        var analysis: SongAnalysis? = null
        /** Smart transitions: where the audio really starts and ends (silence skipped); else 0 / MAX. */
        var beginMs = 0L
        var endMs = Long.MAX_VALUE
        /** Beat-matched crossfade: this slot's tempo adjustment, eased back to 1 afterwards. */
        var rateFactor = 1f

        init {
            player.addListener(SlotListener(this))
        }
    }

    private val slots = arrayOf(Slot(), Slot())
    private var activeIndex = 0
    private val active: Slot get() = slots[activeIndex]
    private val incoming: Slot get() = slots[1 - activeIndex]
    private var crossfading = false
    /** This crossfade's length, in real time. */
    private var fadeLengthMs = 0f
    /** Gapless: the next song is queued in the active player, which moves to it on the exact sample. */
    private var gaplessQueued = false
    /** A beat-matched crossfade waiting to start the next song on a beat. */
    private var beatStartJob: Job? = null

    private val queue = PlaybackQueue<Track>()
    private var volumePercent = store.settings.value.volumePercent
    private var contextName: String? = null

    private var rate = 1f
    private var sleepTimer: SleepTimer = SleepTimer.Off
    private var sleepEndsAtMs: Long? = null
    /** The sleep timer's fade-out, applied on top of the volume. */
    private var sleepVolume = 1f
    private var abRepeat: AbRepeat = AbRepeat.Off
    /** The track whose play has been counted (once per time it's played). */
    private var countedUri: String? = null
    /** How long the current song has really been heard, for the listening log. */
    private val listening = ListenTracker()
    private var lastListenTickMs = 0L
    private var lastListenCheckpointMs = 0L
    /** The settings last applied (declared before init, which applies the first ones). */
    private var appliedSettings: AppSettings? = null
    /** Songs that failed in a row (skipping stops after a run of them, so a dead queue can't spin). */
    private var consecutiveFailures = 0
    /** The lyric line shown on the lock screen / Android Auto in place of the artist (null = the artist). */
    private var nowPlayingLine: String? = null
    /** Radio: the station's ICY title (see [PlaybackState.liveTitle]). */
    private var liveTitle: String? = null

    /** Spectrum of what's being heard, for the Now Playing visualizer. */
    val visualizer = VisualizerTap()

    private val _state = MutableStateFlow(PlaybackState(volumePercent = volumePercent))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _queue = MutableStateFlow(QueueSnapshot())
    val queueState: StateFlow<QueueSnapshot> = _queue.asStateFlow()
    private var publishedQueueVersion = queue.version
    private var queueSaveJob: Job? = null
    private var lastPositionSaveMs = 0L

    private val _errors = MutableSharedFlow<PlaybackError>(extraBufferCapacity = 4)
    val errors: SharedFlow<PlaybackError> = _errors.asSharedFlow()

    /**
     * What the media session (notification, lock screen, headphone buttons)
     * controls: the active player, re-wrapped whenever a crossfade swaps them.
     */
    private val _sessionPlayer = MutableStateFlow<Player>(QueueForwardingPlayer(active.player, this))
    val sessionPlayer: StateFlow<Player> = _sessionPlayer.asStateFlow()

    init {
        active.player.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
        active.effects.visualizer = visualizer

        scope.launch {
            store.settings.collect { settings -> applyAudioSettings(settings) }
        }
        // The desktop's 200 ms update tick, at 100 ms for smoother fades.
        scope.launch {
            while (isActive) {
                tick()
                delay(100)
            }
        }
    }

    // --- Transport ---------------------------------------------------------

    /**
     * Replaces the queue and starts [startIndex] -- the desktop's
     * setQueueAndPlay(). [from] names the list for Up next ("Next From ...").
     */
    fun playQueue(tracks: List<Track>, startIndex: Int, from: String? = null) {
        if (tracks.isEmpty()) return
        abortHandOff()
        contextName = from
        queue.setQueue(tracks, startIndex)
        queue.currentItem?.let(::load)
    }

    /** Plays an internet radio station: it replaces the queue. */
    fun playStation(station: RadioStation) = playQueue(listOf(station.toTrack()), 0)

    /** The list in a random order (the iOS album page's Shuffle button). */
    fun playShuffled(tracks: List<Track>, from: String? = null) = playQueue(tracks.shuffled(), 0, from)

    fun togglePlayPause() {
        if (active.player.isPlaying) pause() else play()
    }

    fun play() {
        if (queue.isEmpty) return
        val player = active.player
        when (player.playbackState) {
            Player.STATE_ENDED -> player.seekTo(0) // end of the queue: play the last track again
            Player.STATE_IDLE -> player.prepare() // after an error
        }
        if (active.track?.isRadio == true) player.seekToDefaultPosition()
        player.play() // a fading-in track follows along, see SlotListener.onPlayWhenReadyChanged
    }

    fun pause() = active.player.pause()

    /** A manual skip always hard-cuts, discarding any fade in progress (same as the desktop). */
    fun next() {
        abortHandOff()
        queue.cancelPendingNext()
        advance(fromAutoAdvance = false)
    }

    /** More than 3 s in: back to the start of this song (like iOS); otherwise the previous song. */
    fun previous() {
        abortHandOff()
        queue.cancelPendingNext()
        if (active.track != null && active.player.currentPosition > active.beginMs + RESTART_THRESHOLD_MS) {
            seekTo(active.beginMs)
        } else if (queue.movePrevious()) {
            queue.currentItem?.let(::load)
        } else {
            seekTo(active.beginMs)
        }
    }

    /**
     * A seek during a crossfade drops the fade instead of moving only the
     * outgoing track. The queue keeps its pending pick, so if the new
     * position is still inside the crossfade window the fade restarts into
     * the same track, from its beginning.
     */
    fun seekTo(positionMs: Long) {
        if (active.track?.isRadio == true) return // live: nothing to seek in
        abortHandOff()
        val target = positionMs.coerceAtLeast(0)
        val ab = abRepeat
        if (ab is AbRepeat.Looping && (target < ab.aMs || target > ab.bMs)) {
            abRepeat = AbRepeat.Off
            publish()
        }
        active.player.seekTo(target)
    }

    /** ±15 s on long tracks. */
    fun skipBy(deltaMs: Long) {
        val duration = active.player.knownDurationMs ?: Long.MAX_VALUE
        seekTo((active.player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    /** Playback speed; the pitch stays the same. */
    fun setRate(newRate: Float) {
        rate = newRate.coerceIn(0.25f, 4f)
        slots.forEach(::applyRate)
        publish()
    }

    fun setSleepTimer(timer: SleepTimer) {
        sleepTimer = timer
        sleepEndsAtMs = (timer as? SleepTimer.Minutes)?.let { System.currentTimeMillis() + it.minutes * 60_000L }
        setSleepVolume(1f)
        if (timer == SleepTimer.EndOfTrack && crossfading) {
            // Stop after the song that's playing: don't hand over to the next one.
            abortHandOff()
            queue.cancelPendingNext()
        }
        publish()
    }

    /** Measure the spectrum only while the visualizer is on screen (it costs a little CPU per sample). */
    private var visualizerUsers = 0

    /**
     * The spectrum is only measured while something shows it: Now Playing's
     * visualizer and the Settings previews each acquire it while on screen
     * and release it after, so one closing doesn't switch it off for the other.
     */
    fun acquireVisualizer() {
        visualizerUsers++
        visualizer.enabled = true
    }

    fun releaseVisualizer() {
        visualizerUsers = (visualizerUsers - 1).coerceAtLeast(0)
        visualizer.enabled = visualizerUsers > 0
    }

    /**
     * Lyrics on the lock screen / Android Auto: shows [line] in place of the
     * artist (null = the artist again). Called when the line being sung changes.
     */
    fun setNowPlayingLine(line: String?) {
        if (line == nowPlayingLine) return
        nowPlayingLine = line
        (_sessionPlayer.value as? QueueForwardingPlayer)?.lyricLine = line
    }

    /** Off -> mark A (here) -> mark B (here; loops A..B) -> off. */
    fun cycleAbRepeat() {
        if (active.track == null) return
        val now = active.player.currentPosition
        abRepeat = when (val ab = abRepeat) {
            AbRepeat.Off -> AbRepeat.ASet(now)
            is AbRepeat.ASet -> if (now > ab.aMs + 1_000) AbRepeat.Looping(ab.aMs, now) else AbRepeat.Off
            is AbRepeat.Looping -> AbRepeat.Off
        }
        if (abRepeat is AbRepeat.Looping && crossfading) {
            abortHandOff()
            queue.cancelPendingNext()
        }
        publish()
    }

    fun positionMs(): Long = active.player.currentPosition

    fun cycleShuffleMode() = editQueue { queue.cycleShuffleMode() }

    fun cycleRepeatMode() = editQueue { queue.cycleRepeatMode() }

    // --- Queue editing (Play next / Add to queue / Up next) -----------------

    /** Returns false if nothing was playing, in which case [tracks] start playing instead. */
    fun playNext(tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        if (queue.isEmpty) {
            playQueue(tracks, 0)
            return false
        }
        editQueue { queue.playNext(tracks) }
        return true
    }

    /** Returns false if nothing was playing, in which case [tracks] start playing instead. */
    fun addToQueue(tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        if (queue.isEmpty) {
            playQueue(tracks, 0)
            return false
        }
        editQueue { queue.addToQueue(tracks) }
        return true
    }

    fun moveUpcoming(entryId: Long, toIndex: Int) = editQueue { queue.moveUpcoming(entryId, toIndex) }

    fun removeUpcoming(entryId: Long) = editQueue { queue.removeUpcoming(entryId) }

    fun clearUpcoming() = editQueue { queue.clearUpcoming() }

    /** Just the "Playing Next" songs. */
    fun clearQueued() = editQueue { queue.clearQueued() }

    /** Plays queue entry [entryId] now (a tap in Up next). */
    fun jumpTo(entryId: Long) {
        abortHandOff()
        if (queue.jumpTo(entryId)) queue.currentItem?.let(::load)
    }

    /** Every song in the queue, in queue order (for Save as playlist). */
    fun queuedTracks(): List<Track> = queue.items

    /**
     * Brings back last session's queue, paused where it was. Does nothing if
     * something has started playing meanwhile. Songs no longer in the
     * library (looked up with [trackFor]) are dropped.
     */
    fun restoreQueue(saved: SavedQueue<String>, position: SavedPosition?, trackFor: (String) -> Track?) {
        if (!queue.isEmpty) return
        val resolved = saved.mapItemsNotNull(trackFor) ?: return
        contextName = saved.context
        queue.restore(resolved)
        val track = queue.currentItem ?: return
        val startMs = position?.takeIf { it.uri == track.uri }?.positionMs ?: 0L
        load(track, startMs = startMs, play = false)
    }

    /**
     * Runs a queue edit. The queue drops its next pick on any edit; if a
     * crossfade is already fading into that song and it's still next
     * afterwards, the fade carries on, otherwise it's dropped (and starts
     * again into the new next song on the next tick).
     */
    private fun editQueue(edit: () -> Unit) {
        val fadingInto = if (crossfading || gaplessQueued) queue.pendingNextEntry?.id else null
        edit()
        if (fadingInto != null) {
            val stillNext = queue.peekNext() && queue.pendingNextEntry?.id == fadingInto &&
                (queue.repeatMode != RepeatMode.One || crossfading) // a queued gapless song would still play
            if (!stillNext) {
                abortHandOff()
                queue.cancelPendingNext()
            }
        }
        publish()
    }

    /** 0-200. Applies immediately; call [saveVolume] when the user lets go of the slider. */
    fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, AppSettings.MAX_VOLUME_PERCENT)
        slots.forEach(::applyGain)
        publish()
    }

    fun saveVolume() {
        val percent = volumePercent
        store.update { it.copy(volumePercent = percent) }
    }

    // --- Tag edits ------------------------------------------------------------

    /** Where playback was when a file was taken away for a tag edit. */
    class FileEditHold internal constructor(val positionMs: Long, val playWhenReady: Boolean)

    /**
     * Stops reading [uri] while its file is rewritten (like the desktop's
     * writeTrackFile()): returns a hold if it was the playing track, to pass
     * to [resumeAfterFileEdit]; a crossfade into it is just dropped.
     */
    fun releaseFileForEdit(uri: String): FileEditHold? {
        if (crossfading && incoming.track?.uri == uri) abortHandOff() // the queue keeps its pick
        if (active.track?.uri != uri) return null
        abortHandOff()
        val hold = FileEditHold(active.player.currentPosition, active.player.playWhenReady)
        active.player.stop()
        active.player.clearMediaItems()
        return hold
    }

    /** Puts the edited track's new tags into the queue and, if it was playing, reloads it where it was. */
    fun resumeAfterFileEdit(updated: Track, hold: FileEditHold?) {
        queue.updateItems { if (it.uri == updated.uri) updated else it }
        if (hold != null) {
            val slot = active
            slot.track = updated
            slot.player.setMediaItem(updated.toMediaItem(), hold.positionMs)
            slot.player.prepare()
            slot.player.playWhenReady = hold.playWhenReady
        }
        publish()
    }

    // --- Internals ---------------------------------------------------------

    private fun advance(fromAutoAdvance: Boolean) {
        if (queue.isEmpty) return
        if (fromAutoAdvance && sleepTimer == SleepTimer.EndOfTrack) {
            // Sleep timer "end of current song": stop here, rewound.
            setSleepTimer(SleepTimer.Off)
            active.player.pause()
            active.player.seekTo(0)
            return
        }
        if (fromAutoAdvance && queue.repeatMode == RepeatMode.One) {
            countedUri = null // each repeat counts as a play
            startListening()
            active.player.seekTo(active.beginMs)
            active.player.play()
            return
        }
        if (queue.moveNext()) {
            queue.currentItem?.let(::load)
        }
        // else: end of the queue -- stays stopped on the last track
    }

    /** Hard cut to [track] on the active player. */
    private fun load(track: Track) = load(track, startMs = 0L, play = true)

    private fun load(track: Track, startMs: Long, play: Boolean) {
        rememberLongTrackPosition() // of the song being left
        val slot = active
        prepareSlot(slot, track)
        songStarted()
        slot.player.volume = 1f
        val start = if (startMs == 0L) resumePositionFor(track) ?: slot.beginMs else startMs
        slot.player.setMediaItem(track.toMediaItem(), start)
        slot.player.prepare()
        if (play) slot.player.play()
        publish()
    }

    /** Sets [slot] up for [track]: its analysis, silence trim (smart transitions), normal speed, gain. */
    private fun prepareSlot(slot: Slot, track: Track) {
        slot.track = track
        slot.replayGainDb = 0f // until this file's tags are read, see onTracksChanged
        slot.analysis = analysisStore.analysisFor(track)
        slot.rateFactor = 1f
        applyRate(slot)
        applyTrim(slot)
        applyGain(slot)
    }

    /** Smart transitions: skip the silence at the start and end of the song. */
    private fun applyTrim(slot: Slot) {
        slot.beginMs = 0L
        slot.endMs = Long.MAX_VALUE
        val analysis = slot.analysis ?: return
        if (!store.settings.value.smartEnabled) return
        if (analysis.audioStartMs > 300) slot.beginMs = (analysis.audioStartMs - 50).coerceAtLeast(0)
        if (analysis.audioEndMs < analysis.durationMs - 300) slot.endMs = analysis.audioEndMs + 100
        if (slot.endMs <= slot.beginMs + 1_000) {
            slot.beginMs = 0L
            slot.endMs = Long.MAX_VALUE
        }
    }

    private fun applyRate(slot: Slot) {
        slot.player.playbackParameters = PlaybackParameters(rate * slot.rateFactor, pitch)
    }

    private fun effectiveRate(slot: Slot): Float = rate * slot.rateFactor

    /** Bookkeeping when a new song becomes the current one. */
    private fun songStarted() {
        countedUri = null
        abRepeat = AbRepeat.Off
        setLiveTitle(null)
        startListening()
    }

    private fun setLiveTitle(title: String?) {
        if (title == liveTitle) return
        liveTitle = title
        (_sessionPlayer.value as? QueueForwardingPlayer)?.liveTitle = title
        publish()
    }

    /** Logs the song before (if it counted as a play) and starts timing the current one. */
    private fun startListening() {
        val track = active.track ?: return
        val entry = PlayEntry(
            uri = track.uri,
            title = track.title,
            artist = track.artist,
            album = track.album,
            startedAtMs = System.currentTimeMillis(),
            listenedMs = 0,
            kind = when {
                track.isRadio -> PlayKind.Radio
                isPodcast(track.uri) -> PlayKind.Podcast
                else -> PlayKind.Music
            },
        )
        val finished = listening.start(entry, track.durationMs)
        if (finished != null) playLog.append(finished) else playLog.checkpoint(null)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        applyTone(store.settings.value)
        sleepEndsAtMs?.let { end ->
            if (now >= end) {
                pause()
                setSleepTimer(SleepTimer.Off)
            }
        }

        val player = active.player
        // Listening time, in real time (100 ms ticks; a stalled tick adds at most a second).
        if (player.isPlaying) listening.listened(now - lastListenTickMs)
        lastListenTickMs = now
        val track = active.track ?: return
        if (now - lastListenCheckpointMs >= LISTEN_CHECKPOINT_MS) {
            lastListenCheckpointMs = now
            listening.counted?.let(playLog::checkpoint)
        }
        // Radio is live: no position to save, no play count, no crossfade or end. Only the sleep timer's fade.
        if (track.isRadio) {
            updateSleepFade(player.currentPosition, Long.MAX_VALUE)
            return
        }
        val length = player.knownDurationMs ?: return
        val cursor = player.currentPosition
        listening.setLength(length)

        // While playing, remember the position every few seconds in case the app is killed.
        if (player.isPlaying && now - lastPositionSaveMs >= POSITION_SAVE_INTERVAL_MS) {
            lastPositionSaveMs = now
            queueStore.savePosition(track.uri, cursor)
            rememberLongTrackPosition()
        }
        if (player.isPlaying) {
            val ab = abRepeat
            if (ab is AbRepeat.Looping && cursor >= ab.bMs) player.seekTo(ab.aMs)
            countPlayIfDue(track, cursor, length)
            if (length > 0 && cursor >= length - PODCAST_PLAYED_WITHIN_MS && isPodcast(track.uri)) {
                songData.setPlayed(track.uri, true)
            }
        }
        updateSleepFade(cursor, length)

        if (crossfading) {
            updateCrossfade(cursor, length)
            return
        }
        // Glide back to normal speed after a beat-matched crossfade.
        if (active.rateFactor != 1f) {
            val difference = 1f - active.rateFactor
            active.rateFactor = if (abs(difference) < 0.002f) 1f else active.rateFactor + difference * 0.06f
            applyRate(active)
        }
        if (!player.isPlaying) return
        // Smart transitions: the trimmed end (silence after the music) counts as the end.
        if (active.endMs < length - 300 && cursor >= active.endMs) {
            if (gaplessQueued) player.seekToNextMediaItem() else advance(fromAutoAdvance = true)
            return
        }
        if (sleepTimer != SleepTimer.EndOfTrack && abRepeat == AbRepeat.Off && queue.repeatMode != RepeatMode.One) {
            prepareHandOff(cursor, length)
        }
    }

    /**
     * Lines up the next song before this one ends: a crossfade, or a
     * gapless hand-off (the next song queued in this player, which starts it
     * on the exact sample this one ends) -- the iOS app's prepareHandOff().
     */
    private fun prepareHandOff(cursor: Long, length: Long) {
        if (gaplessQueued) return
        val settings = store.settings.value
        val end = minOf(active.endMs, length)
        val remaining = (end - cursor) / effectiveRate(active)
        val fade = settings.crossfadeSeconds * 1000f
        // Don't decide anything until the end is near.
        if (remaining > maxOf(fade * 2.5f, 12_000f)) return
        if (!queue.peekNext()) return
        val next = queue.pendingNextItem ?: return
        val current = active.track ?: return

        var useCrossfade = settings.crossfadeEnabled && length > fade * 2
        // Smart: consecutive songs of the same album stay gapless (live albums, DJ mixes). The
        // artist must match too: untagged songs get their folder's name as album ("Music").
        if (useCrossfade && settings.smartEnabled && current.album.isNotBlank() &&
            current.album.trim().equals(next.album.trim(), ignoreCase = true) &&
            current.artist.trim().equals(next.artist.trim(), ignoreCase = true)
        ) {
            useCrossfade = false
        }
        if (useCrossfade) {
            var fadeLength = fade
            // Smart: a long fade-out at the end becomes the crossfade.
            if (settings.smartEnabled) {
                active.analysis?.let { analysis ->
                    val outro = end - analysis.outroStartMs
                    if (outro > fade) fadeLength = minOf(outro.toFloat(), fade * 2, 12_000f)
                }
            }
            if (remaining <= fadeLength) startCrossfade(next, fadeLength)
        } else if (remaining <= GAPLESS_QUEUE_AHEAD_MS) {
            // Queued well ahead, so the player has the next file open in time.
            active.player.addMediaItem(next.toMediaItem())
            gaplessQueued = true
            Log.i(TAG, "Gapless: queued ${next.fileName}")
        }
    }

    /** The gapless hand-off happened: the active player moved on to the queued song. */
    private fun completeGapless() {
        gaplessQueued = false
        val slot = active
        // The song that just ended played to its end: nothing to resume.
        slot.track?.let { finished ->
            if (finished.durationMs >= LONG_TRACK_MS || isPodcast(finished.uri)) songData.setResumePosition(finished.uri, null)
        }
        queue.commitPendingNext()
        val track = queue.currentItem ?: return
        prepareSlot(slot, track)
        songStarted()
        val index = slot.player.currentMediaItemIndex
        if (index > 0) slot.player.removeMediaItems(0, index) // the finished song
        if (slot.beginMs > 0) slot.player.seekTo(slot.beginMs)
        publish()
    }

    /** Takes a queued gapless song back out of the active player. */
    private fun cancelGapless() {
        if (!gaplessQueued) return
        gaplessQueued = false
        val player = active.player
        val after = player.currentMediaItemIndex + 1
        if (after < player.mediaItemCount) player.removeMediaItems(after, player.mediaItemCount)
    }

    /** Counts a play once half the song (or 4 minutes) has been heard. */
    private fun countPlayIfDue(track: Track, cursor: Long, length: Long) {
        if (countedUri == track.uri) return
        val needed = (length / 2).coerceIn(1_000L, 240_000L)
        if (cursor >= needed) {
            countedUri = track.uri
            songData.recordPlay(track.uri)
        }
    }

    /** Fades out over the last 30 s of a sleep timer (last 10 s of the song for "end of song"). */
    private fun updateSleepFade(cursor: Long, length: Long) {
        var volume = 1f
        if (store.settings.value.sleepFadeOut && active.player.isPlaying) {
            val end = sleepEndsAtMs
            if (end != null) {
                volume = ((end - System.currentTimeMillis()) / 30_000f).coerceIn(0f, 1f)
            } else if (sleepTimer == SleepTimer.EndOfTrack) {
                volume = ((length - cursor) / rate / 10_000f).coerceIn(0f, 1f)
            }
        }
        setSleepVolume(volume)
    }

    private fun setSleepVolume(volume: Float) {
        if (abs(volume - sleepVolume) < 0.005f && (volume != 1f || sleepVolume == 1f)) return
        sleepVolume = volume
        slots.forEach(::applyGain)
    }

    /** Where a long track (or any podcast episode) was left, if it should continue from there. */
    private fun resumePositionFor(track: Track): Long? {
        if (!remembersPosition(track.uri, track.durationMs)) return null
        val saved = songData.statsFor(track.uri).resumePositionMs ?: return null
        return saved.takeIf { it > 15_000 && it < track.durationMs - 30_000 }
    }

    /** Podcasts always continue where they were left; other songs if they're long and the setting is on. */
    private fun remembersPosition(uri: String, durationMs: Long): Boolean =
        isPodcast(uri) || (store.settings.value.rememberLongTrackPosition && durationMs >= LONG_TRACK_MS)

    /** Saves (or clears, near the start or end) the position of the long track or episode that's playing. */
    private fun rememberLongTrackPosition() {
        val track = active.track ?: return
        val duration = active.player.knownDurationMs ?: track.durationMs
        if (!remembersPosition(track.uri, duration)) return
        val position = active.player.currentPosition
        val keep = position > 15_000 && position < duration - 30_000
        songData.setResumePosition(track.uri, if (keep) position else null)
    }

    private fun startCrossfade(next: Track, fadeLength: Float) {
        val slot = incoming
        prepareSlot(slot, next)
        slot.player.volume = 0f
        var startMs = slot.beginMs
        var waitMs = 0L
        if (store.settings.value.beatMatchEnabled) {
            beatMatch(slot)?.let { match ->
                slot.rateFactor = match.rateFactor
                applyRate(slot)
                startMs = match.startMs
                waitMs = match.waitMs
            }
        }
        Log.i(TAG, "Crossfade ${fadeLength.toInt()} ms into ${next.fileName}, start $startMs ms, " +
            "beat wait $waitMs ms, tempo x${slot.rateFactor}")
        slot.player.setMediaItem(next.toMediaItem(), startMs)
        slot.player.prepare()
        fadeLengthMs = maxOf(fadeLength, 1_000f)
        crossfading = true
        if (waitMs <= 0) {
            slot.player.play()
        } else {
            // Start on the outgoing song's beat: once the player is ready, wait out the rest.
            val target = SystemClock.elapsedRealtime() + waitMs
            beatStartJob = scope.launch {
                while (slot.player.playbackState != Player.STATE_READY && SystemClock.elapsedRealtime() < target) delay(5)
                delay((target - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                if (crossfading && slot === incoming && active.player.playWhenReady) slot.player.play()
            }
        }
    }

    private class BeatMatch(val rateFactor: Float, val startMs: Long, val waitMs: Long)

    /**
     * Beat-matched crossfade: the next song's tempo is adjusted to this one's
     * (if they're within 8%, counting half/double time) and it starts on its
     * first beat on one of this song's beats. null when either song's beat
     * isn't reliable. ExoPlayer can't start on an exact sample the way iOS
     * schedules it, so the start is timed from here (within a few ms).
     */
    private fun beatMatch(slot: Slot): BeatMatch? {
        val outgoing = active.analysis ?: return null
        val incomingAnalysis = slot.analysis ?: return null
        if (!outgoing.isBeatReliable || !incomingAnalysis.isBeatReliable) return null
        val outBpm = outgoing.bpm ?: return null
        val inBpm = incomingAnalysis.bpm ?: return null
        val outPeriod = outgoing.beatPeriodMs ?: return null
        val inPeriod = incomingAnalysis.beatPeriodMs ?: return null
        val ratio = listOf(outBpm / inBpm, outBpm * 2 / inBpm, outBpm / (inBpm * 2)).minBy { abs(it - 1) }
        if (abs(ratio - 1) > 0.08) return null

        // This song's next beat, far enough away for the next song's player to get ready.
        val now = active.player.currentPosition.toDouble()
        var beatTime = outgoing.firstBeatMs + ceil((now - outgoing.firstBeatMs) / outPeriod) * outPeriod
        while (beatTime - now < BEAT_START_LEAD_MS) beatTime += outPeriod
        val waitMs = ((beatTime - now) / effectiveRate(active)).toLong()
        // The next song's first beat at or after where its audio starts.
        val skipped = maxOf(0.0, ceil((slot.beginMs - incomingAnalysis.firstBeatMs) / inPeriod))
        val startMs = maxOf(0.0, incomingAnalysis.firstBeatMs + skipped * inPeriod).toLong()
        return BeatMatch(ratio.toFloat(), startMs, waitMs)
    }

    private fun updateCrossfade(cursor: Long, length: Long) {
        val remaining = (minOf(active.endMs, length) - cursor) / effectiveRate(active)
        val progress = 1f - (remaining / fadeLengthMs).coerceIn(0f, 1f)
        active.player.volume = 1f - progress
        // The incoming song may not have started yet (beat-matched start on a beat).
        incoming.player.volume = progress
        if (remaining <= 50 || active.player.playbackState == Player.STATE_ENDED) {
            finalizeCrossfade()
        }
    }

    /** The faded-in player becomes the active one (the desktop's finalizeCrossfade()). */
    private fun finalizeCrossfade() {
        if (!crossfading) return
        crossfading = false
        beatStartJob?.cancel()
        beatStartJob = null
        // Still waiting for its beat (the outgoing song ran out first): start it now.
        if (!incoming.player.isPlaying && active.player.playWhenReady) incoming.player.play()
        rememberLongTrackPosition()
        val old = active
        activeIndex = 1 - activeIndex
        songStarted()

        // Hand audio focus over: only the active player holds it, or the two would take it from each other.
        active.player.setAudioAttributes(audioAttributes, true)
        active.player.volume = 1f
        old.player.setAudioAttributes(audioAttributes, false)
        old.player.stop()
        old.player.clearMediaItems()
        old.track = null
        old.analysis = null
        old.rateFactor = 1f
        applyRate(old)

        // The visualizer follows the player that's heard.
        old.effects.visualizer = null
        active.effects.visualizer = visualizer

        queue.commitPendingNext()
        _sessionPlayer.value = QueueForwardingPlayer(active.player, this).also {
            it.lyricLine = nowPlayingLine
            it.liveTitle = liveTitle
        }
        publish()
    }

    /**
     * Drops a hand-off in progress (a fade, or a queued gapless song),
     * leaving the current track playing at full volume. Doesn't touch the queue.
     */
    private fun abortHandOff() {
        cancelGapless()
        if (!crossfading) return
        crossfading = false
        beatStartJob?.cancel()
        beatStartJob = null
        incoming.player.stop()
        incoming.player.clearMediaItems()
        incoming.track = null
        incoming.analysis = null
        active.player.volume = 1f
    }

    private fun applyAudioSettings(settings: AppSettings) {
        val old = appliedSettings
        appliedSettings = settings
        correction.update(settings.headphoneCorrection)
        karaoke.update(settings.karaokeVocalRemovalPercent / 100f)
        channelSettings.update(settings.monoAudio, settings.balancePercent / 100f)
        slots.forEach { it.player.skipSilenceEnabled = settings.skipSilence }
        val newPitch = pitchFactor(settings.karaokeKeySemitones)
        if (newPitch != pitch) {
            pitch = newPitch
            slots.forEach(::applyRate)
        }
        loudnessVolume = -1f // the tone is set again below
        applyTone(settings)
        slots.forEach { it.effects.limiterEnabled = settings.limiterEnabled }
        equalizer.update(settings.eqEnabled, settings.eqBandGainsDb)
        spatial.update(
            SpatialParams(
                surround = settings.surroundPreset,
                surroundAmount = settings.surroundAmountPercent / 100f,
                width = settings.stereoWidthPercent / 100f,
                reverb = settings.reverbPreset,
                reverbMix = settings.reverbMixPercent / 100f,
            )
        )
        val attributes = audioAttributesFor(settings)
        if (attributes != audioAttributes) {
            audioAttributes = attributes
            // Only the active player holds audio focus (see the crossfade hand-off).
            slots.forEach { it.player.setAudioAttributes(attributes, it === active) }
        }
        slots.forEach(::applyGain)
        if (!settings.crossfadeEnabled && crossfading) abortHandOff()
        if (old != null && (old.smartEnabled != settings.smartEnabled)) {
            // Trim points only change for songs loaded from now on; the current one keeps its own.
            if (!crossfading) {
                abortHandOff()
                queue.cancelPendingNext()
            }
        }
        if (old == null || old.harmonicEnabled != settings.harmonicEnabled) {
            queue.smartOrder = if (settings.harmonicEnabled) {
                { items, start -> harmonicOrder(items, start, analysisStore::analysisFor) }
            } else null
            if (old != null) editQueue { queue.reshuffle() }
        }
    }

    /** The desktop's baseVolumeFor(): volume x ReplayGain (if on) x EQ post-gain (if the EQ is on). */
    /**
     * Bass boost, plus loudness for the phone's media volume -- read on
     * every tick, since the volume buttons don't go through the app.
     */
    private fun applyTone(settings: AppSettings) {
        val volume = if (settings.loudnessEnabled) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        } else 1f
        if (volume == loudnessVolume && tone.params.bassBoostDb == settings.bassBoostDb) return
        loudnessVolume = volume
        tone.update(
            ToneParams(
                bassBoostDb = settings.bassBoostDb,
                loudness = if (settings.loudnessEnabled) ToneParams.loudnessAt(volume) else 0f,
            )
        )
    }

    private fun applyGain(slot: Slot) {
        val settings = store.settings.value
        var gain = volumePercent / 100f
        if (settings.replayGainEnabled) gain *= dbToLinear(slot.replayGainDb)
        if (settings.eqEnabled) gain *= dbToLinear(settings.eqPostGainDb)
        slot.effects.gain = gain * sleepVolume
    }

    private fun publish() {
        val player = active.player
        _state.value = PlaybackState(
            current = queue.currentItem,
            isPlaying = player.isPlaying,
            durationMs = player.knownDurationMs ?: queue.currentItem?.durationMs ?: 0L,
            shuffleMode = queue.shuffleMode,
            repeatMode = queue.repeatMode,
            volumePercent = volumePercent,
            rate = rate,
            sleepTimer = sleepTimer,
            sleepEndsAtMs = sleepEndsAtMs,
            abRepeat = abRepeat,
            liveTitle = liveTitle,
        )
        if (queue.version != publishedQueueVersion) {
            publishedQueueVersion = queue.version
            _queue.value = QueueSnapshot(
                played = queue.played(),
                current = queue.currentEntry,
                upcoming = queue.upcoming(),
                continuesAfter = queue.continuesAfterUpcoming(),
                queuedCount = queue.queuedCount(),
                contextName = contextName,
            )
            scheduleQueueSave()
        }
    }

    /** Saves the queue a moment after it last changed, so a burst of edits is one write. */
    private fun scheduleQueueSave() {
        queueSaveJob?.cancel()
        queueSaveJob = scope.launch {
            delay(QUEUE_SAVE_DELAY_MS)
            queueStore.save(queue.save()?.let { saved -> saved.mapItemsNotNull { it.uri }?.copy(context = contextName) })
        }
    }

    private inner class SlotListener(private val slot: Slot) : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (slot !== active) return
            if (isPlaying) consecutiveFailures = 0
            // Paused (or stopped): where to resume next launch.
            if (!isPlaying) {
                slot.track?.let { queueStore.savePosition(it.uri, slot.player.currentPosition) }
                rememberLongTrackPosition()
            }
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (slot !== active) return
            if (playbackState == Player.STATE_ENDED) {
                // Crossfade-off path, or a fade that couldn't start (end of queue).
                if (crossfading) finalizeCrossfade() else advance(fromAutoAdvance = true)
            }
            publish()
        }

        /**
         * Pause/play from anywhere (the app, the notification, a phone call
         * taking audio focus, headphones unplugged) lands on the active
         * player; the track fading in follows it, so it never keeps playing
         * on its own.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (slot === active && crossfading) {
                incoming.player.playWhenReady = playWhenReady
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The active player reached the queued gapless song (on its own, or skipped there at a trimmed end).
            if (slot !== active || !gaplessQueued) return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) return
            if (mediaItem?.mediaId == queue.pendingNextItem?.uri) completeGapless() else cancelGapless()
        }

        /** Radio stations send what's playing as ICY metadata ("Artist - Title"). */
        override fun onMetadata(metadata: Metadata) {
            if (slot !== active || slot.track?.isRadio != true) return
            for (i in 0 until metadata.length()) {
                val icy = metadata[i] as? IcyInfo ?: continue
                setLiveTitle(icy.title?.trim()?.takeIf { it.isNotEmpty() })
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            slot.replayGainDb = readReplayGainDb(tracks) ?: 0f
            applyGain(slot)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (slot === active && slot.track?.isRadio == true && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                slot.player.seekToDefaultPosition()
                slot.player.prepare()
                return
            }
            if (slot === active) {
                val track = slot.track
                Log.w(TAG, "Couldn't play ${track?.fileName}", error)
                abortHandOff()
                // Like the iOS app: skip it with a short message instead of stopping the queue
                // (up to a run of failures, so a queue of missing files doesn't spin).
                consecutiveFailures++
                val wasPlaying = slot.player.playWhenReady
                val skipped = consecutiveFailures <= MAX_SKIPPED_IN_A_ROW && wasPlaying && queue.moveNext()
                val name = track?.title?.ifEmpty { track.fileName }.orEmpty()
                _errors.tryEmit(if (skipped) PlaybackError.Skipped(name) else PlaybackError.Failed(name))
                if (skipped) queue.currentItem?.let(::load) else publish()
            } else {
                // The next track failed to load for a crossfade: drop the fade;
                // it gets another go (and reports the error) on the normal advance.
                Log.w(TAG, "Crossfade preload failed for ${slot.track?.fileName}", error)
                abortHandOff()
                queue.cancelPendingNext()
            }
        }
    }

    companion object {
        /** Tracks this long get ±15 s buttons and remember where you stopped. */
        const val LONG_TRACK_MS = 10 * 60_000L
        /** A podcast episode counts as played once this close to its end (outros, ads). */
        const val PODCAST_PLAYED_WITHIN_MS = 30_000L
        private const val TAG = "PlaybackManager"
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        /** How often the play in progress is saved (in case the app is killed). */
        private const val LISTEN_CHECKPOINT_MS = 10_000L
        private const val QUEUE_SAVE_DELAY_MS = 1_000L
        private const val RESTART_THRESHOLD_MS = 3_000L
        /** How early the next song is queued for a gapless hand-off. */
        private const val GAPLESS_QUEUE_AHEAD_MS = 10_000f
        /** A beat-matched start is at least this far ahead, so the next song's player is ready. */
        private const val BEAT_START_LEAD_MS = 600.0
        private const val MAX_SKIPPED_IN_A_ROW = 10
    }
}

/**
 * Music playback, for audio focus and routing. Android's own spatial audio
 * may process it unless it's switched off -- or our 3D Surround is on, so
 * the two never stack.
 */
private fun audioAttributesFor(settings: AppSettings): AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
    .setSpatializationBehavior(
        if (settings.androidSpatialAudio && settings.surroundPreset == SurroundPreset.Off) {
            C.SPATIALIZATION_BEHAVIOR_AUTO
        } else {
            C.SPATIALIZATION_BEHAVIOR_NEVER
        }
    )
    .build()

/** The length of what [Player] is playing, or null while it isn't known yet. */
private val Player.knownDurationMs: Long?
    get() = duration.takeIf { it != C.TIME_UNSET && it > 0 }

/** An ExoPlayer whose audio goes through [effects] before reaching the speaker. */
@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, effects: AudioEffectsProcessor, audioAttributes: AudioAttributes): ExoPlayer {
    val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
            DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(effects))
                .build()
    }
    return ExoPlayer.Builder(context, renderersFactory)
        .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false) // turned on for whichever player is active
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .setWakeMode(C.WAKE_MODE_LOCAL) // keep playing with the screen off
        .build()
}

/**
 * The track's ReplayGain from its tags, as ExoPlayer read them: ID3 TXXX
 * (MP3), Vorbis comments (FLAC, OGG, Opus) or iTunes freeform atoms (M4A).
 */
@OptIn(UnstableApi::class)
private fun readReplayGainDb(tracks: Tracks): Float? {
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            val metadata: Metadata = group.getTrackFormat(i).metadata ?: continue
            for (j in 0 until metadata.length()) {
                val value = when (val entry = metadata.get(j)) {
                    is TextInformationFrame ->
                        if (entry.id == "TXXX" && entry.description.equals(REPLAYGAIN_TRACK_GAIN, ignoreCase = true)) {
                            entry.values.firstOrNull()
                        } else null
                    is VorbisComment ->
                        if (entry.key.equals(REPLAYGAIN_TRACK_GAIN, ignoreCase = true)) entry.value else null
                    is InternalFrame ->
                        if (entry.description.equals(REPLAYGAIN_TRACK_GAIN, ignoreCase = true)) entry.text else null
                    else -> null
                }
                parseReplayGainDb(value)?.let { return it }
            }
        }
    }
    return null
}

private const val REPLAYGAIN_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"

/**
 * Title/artist/album from the library; ExoPlayer adds the file's embedded
 * artwork itself, which the notification and lock screen pick up.
 */
private fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(uri)
        .setUri(uri)
        .apply { if (station?.hls == true) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifEmpty { null })
                .setAlbumTitle(album.ifEmpty { null })
                .build()
        )
        .build()
