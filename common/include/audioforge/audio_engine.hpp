#pragma once

#include <QString>
#include "miniaudio.h"

namespace audioforge {

// Thin wrapper over miniaudio. Uses two sound "slots" instead of one so a
// crossfade can have the outgoing and incoming track playing at once --
// everything else (Play/Pause/Stop/seek/volume) only ever touches whichever
// slot is currently "active". Has no Qt-widget dependency (QString/float
// only), so it's reusable by any UI layer, not just the Qt one.
class AudioEngine
{
public:
    AudioEngine() = default;
    ~AudioEngine();

    AudioEngine(const AudioEngine&) = delete;
    AudioEngine& operator=(const AudioEngine&) = delete;

    // Returns false if miniaudio's engine itself failed to initialize
    // (e.g. no audio device available) -- caller should report this via
    // ErrorReporter rather than let playback silently do nothing.
    bool init();

    // Hard cut: stops/unloads whatever was in the active slot (if anything)
    // and loads+starts this file. Returns false if the file couldn't be
    // opened (bad path, unsupported/corrupt file, etc.).
    bool loadAndPlay(const QString& path);

    void play();
    void pause();
    void stop(); // also aborts any in-progress crossfade and seeks to 0

    bool isLoaded() const;
    bool isAtEnd() const;
    bool isPlaying() const; // false if loaded but paused, or nothing loaded
    float cursorSeconds() const;
    float lengthSeconds() const;

    void setVolume(float linear); // 0..1 = normal range; can go above 1 for "boost"
    void seekToSeconds(float seconds);

    // --- Crossfade -------------------------------------------------------
    // The caller (PlaybackQueue-driven code) is responsible for deciding
    // WHEN to start a crossfade and what the two volumes should be each
    // tick (so ReplayGain / user-volume math stays outside this class);
    // AudioEngine just manages the two sound objects and the handoff.

    bool isCrossfading() const { return m_crossfading; }

    // Starts preloading/playing `nextPath` silently in the inactive slot.
    // Returns false if that file couldn't be opened (crossfade doesn't start).
    bool startCrossfadeTo(const QString& nextPath);

    // Called every tick while crossfading, with the two volumes the caller
    // has already computed for "how far through the fade we are".
    void updateCrossfadeVolumes(float activeVolume, float nextVolume);

    // Promotes the preloaded (inactive) slot to active, discarding the old
    // active sound. Call this once the fade has fully completed.
    void finalizeCrossfade();

    // Cancels an in-progress crossfade (discards the preloaded slot) without
    // touching the currently-playing active sound. Safe to call even when
    // not crossfading (no-op).
    void abortCrossfade();

    // --- Equalizer ---------------------------------------------------------
    // A 10-band graphic EQ (peaking filters, ~31Hz-16kHz octave spacing)
    // sitting between whichever sound is playing and the engine's output.
    // Both crossfade slots route through the SAME chain when enabled (their
    // outputs mix into the chain's first band, same as they already mix at
    // the engine's endpoint by default), so a crossfade doesn't lose EQ
    // partway through.
    //
    // Requires miniaudio's node-graph peaking-filter nodes (ma_peak_node);
    // isEqualizerAvailable() is false if that init failed for any reason
    // (e.g. an older vendored miniaudio.h without them) -- the UI should
    // disable the equalizer controls in that case rather than call the
    // rest of this API.
    static constexpr int kEqBandCount = 10;

    bool isEqualizerAvailable() const { return m_eqBandsInitialized; }
    bool isEqualizerEnabled() const { return m_eqEnabled; }
    void setEqualizerEnabled(bool enabled);

    static float equalizerBandFrequency(int bandIndex);
    float equalizerBandGain(int bandIndex) const;
    void setEqualizerBandGain(int bandIndex, float gainDb); // applied live, no reload needed

private:
    ma_sound& ActiveSound() { return m_soundSlots[m_activeSlot]; }
    const ma_sound& ActiveSound() const { return m_soundSlots[m_activeSlot]; }
    int InactiveSlot() const { return 1 - m_activeSlot; }

    void initEqualizerChain(); // builds the 10-node peak-filter chain; sets m_eqBandsInitialized
    void routeSoundThroughEqualizer(ma_sound& sound); // attaches to the EQ chain or straight to the endpoint, per m_eqEnabled

    ma_engine m_engine{};
    ma_sound m_soundSlots[2]{};
    bool m_slotLoaded[2] = {false, false};
    int m_activeSlot = 0;
    bool m_crossfading = false;
    bool m_engineInitialized = false;

    ma_peak_node m_eqBands[kEqBandCount]{};
    bool m_eqBandsInitialized = false;
    bool m_eqEnabled = false;
    float m_eqBandGainsDb[kEqBandCount] = {0.0f};
};

} // namespace audioforge