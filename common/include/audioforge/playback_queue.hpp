#pragma once

#include <QVector>
#include "audioforge/track_info.hpp"

#include <functional>
#include <optional>

namespace audioforge {

enum class ShuffleMode { Off, Random, Smart };
enum class RepeatMode { Off, All, One };

// Manages "what plays next" -- the queue itself, shuffle/repeat state, and
// play history (so Previous undoes shuffle jumps correctly, not just
// "index minus one"). Doesn't touch any audio or UI; PlayerWindow reads
// currentTrack()/pendingNextTrack() and drives AudioEngine accordingly.
//
// Two parts, like the iOS app's Up Next:
//   - the list playback was started from (an album, playlist, the Tracks
//     tab...), which shuffle/repeat apply to, and
//   - "Playing Next": tracks queued by hand (Play Next / Add to Queue).
//     They always play before the rest of the list, in their own order,
//     each once; shuffle and repeat don't touch them.
//
// Two-step "next" API (peekNext / commitPendingNext) exists because
// crossfade needs to know which track is coming up *before* actually
// switching to it (to preload it), while a plain Next button press wants
// both steps done immediately -- moveNext() does both in one call.
class PlaybackQueue
{
public:
    // A hand-queued track. `id` tells two queued copies of the same file apart.
    struct QueuedTrack
    {
        quint64 id = 0;
        TrackInfo track;
    };

    // Replaces the list. Hand-queued tracks stay queued.
    void setQueue(const QVector<TrackInfo>& queue, int startIndex);

    // Puts back a queue saved at the last exit: the list and where it was,
    // a hand-queued track that was playing (if any), Playing Next, and the
    // shuffle/repeat modes. `list` must not be empty.
    void restore(const QVector<TrackInfo>& list, int index, const std::optional<TrackInfo>& currentManual,
                 const QVector<TrackInfo>& playingNext, ShuffleMode shuffle, RepeatMode repeat);

    bool isEmpty() const { return m_queue.isEmpty() && !m_currentManual; }
    int size() const { return m_queue.size(); }
    const TrackInfo& currentTrack() const { return m_currentManual ? *m_currentManual : m_queue[m_queueIndex]; }
    int currentIndex() const { return m_queueIndex; }
    bool isCurrentFromManualQueue() const { return m_currentManual.has_value(); }
    const QVector<TrackInfo>& items() const { return m_queue; }

    ShuffleMode shuffleMode() const { return m_shuffleMode; }
    RepeatMode repeatMode() const { return m_repeatMode; }
    void cycleShuffleMode();
    void cycleRepeatMode();

    // Resolves (and caches) what plays next -- the first hand-queued track
    // if there is one, otherwise per the list's shuffle/repeat mode --
    // WITHOUT committing to it yet. For Smart mode this does consume/advance
    // the shuffle order (that consumption is real/final), so calling it
    // twice in a row without committing returns the same cached result
    // rather than picking again. Returns false if there's nothing to advance
    // to (end of queue, repeat off -- in the shuffle modes, "end" means one
    // full queue's worth of tracks).
    bool peekNext();
    bool hasPendingNext() const { return m_pendingManualId != 0 || m_pendingNextIndex >= 0; }
    const TrackInfo& pendingNextTrack() const;

    // Moves currentTrack() to the previously-peeked pending track (pushes
    // history, clears the pending slot). Only valid if hasPendingNext().
    void commitPendingNext();

    // Discards a peeked-but-not-committed pending track without moving
    // anywhere (used when a crossfade gets aborted).
    void cancelPendingNext();

    // Convenience: peekNext() + commitPendingNext() in one call. Returns
    // false (queue unchanged) if there was nothing to advance to.
    bool moveNext();

    // Moves to the previous track in play history, or one index back (only
    // in ShuffleMode::Off) if history is exhausted. From a hand-queued
    // track, goes back to the list's track that was playing before it.
    // Returns false if there's nowhere earlier to go.
    bool movePrevious();

    // Builds the Smart-shuffle order (element 0 must be `start`). Empty
    // function = plain random order. Set for Harmonic Shuffle, which puts
    // songs in compatible keys and tempos next to each other.
    using SmartOrderFn = std::function<QVector<int>(const QVector<TrackInfo>& items, int start)>;
    void setSmartOrder(SmartOrderFn fn) { m_smartOrder = std::move(fn); }

    // Rebuilds the Smart-shuffle order from the current track (after the
    // smart order function changed). No-op outside Smart mode.
    void reshuffle();

    // --- Up Next ---------------------------------------------------------
    // Callers settle any hand-off in progress first: these drop the pending pick.

    const QVector<QueuedTrack>& manualQueue() const { return m_manual; }
    void playNext(const QVector<TrackInfo>& tracks);   // to the front of Playing Next
    void addToQueue(const QVector<TrackInfo>& tracks); // to the end of Playing Next
    void removeFromManualQueue(int position);
    void moveInManualQueue(int from, int to);
    void clearManualQueue();
    // Plays Playing Next's track at `position` now; the ones before it are dropped.
    void jumpToManual(int position);

    // List indices that will play after the current track, in order (up to
    // `limit`). nullopt in Random shuffle, where each pick is made only
    // when it's needed.
    std::optional<QVector<int>> upcoming(int limit) const;
    void jumpTo(int index);   // plays list item `index` now
    void removeAt(int index); // drops a list item that isn't the current one

private:
    void resetSmartShuffleFrom(int currentIndex);
    static QVector<int> RandomPermutation(int count);

    QVector<TrackInfo> m_queue;
    int m_queueIndex = -1;
    int m_pendingNextIndex = -1;
    QVector<int> m_history;

    QVector<QueuedTrack> m_manual;
    std::optional<TrackInfo> m_currentManual; // set while a hand-queued track is the current one
    quint64 m_pendingManualId = 0;            // the pending pick is this hand-queued track (0 = none)
    quint64 m_nextManualId = 1;

    ShuffleMode m_shuffleMode = ShuffleMode::Off;
    QVector<int> m_shuffleOrder; // used only in Smart mode
    int m_shufflePos = -1;
    RepeatMode m_repeatMode = RepeatMode::Off;
    SmartOrderFn m_smartOrder;
};

} // namespace audioforge
