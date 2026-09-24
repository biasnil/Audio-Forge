#pragma once

#include <QVector>
#include "audioforge/track_info.hpp"

namespace audioforge {

enum class ShuffleMode { Off, Random, Smart };
enum class RepeatMode { Off, All, One };

// Manages "what plays next" -- the queue itself, shuffle/repeat state, and
// play history (so Previous undoes shuffle jumps correctly, not just
// "index minus one"). Doesn't touch any audio or UI; PlayerWindow reads
// currentTrack()/pendingNextTrack() and drives AudioEngine accordingly.
//
// Two-step "next" API (peekNext / commitPendingNext) exists because
// crossfade needs to know which track is coming up *before* actually
// switching to it (to preload it), while a plain Next button press wants
// both steps done immediately -- moveNext() does both in one call.
class PlaybackQueue
{
public:
    void setQueue(const QVector<TrackInfo>& queue, int startIndex);

    bool isEmpty() const { return m_queue.isEmpty(); }
    const TrackInfo& currentTrack() const { return m_queue[m_queueIndex]; }
    int currentIndex() const { return m_queueIndex; }

    ShuffleMode shuffleMode() const { return m_shuffleMode; }
    RepeatMode repeatMode() const { return m_repeatMode; }
    void cycleShuffleMode();
    void cycleRepeatMode();

    // Resolves (and caches) which index should play next, per the current
    // shuffle/repeat mode, WITHOUT committing to it yet. For Smart mode
    // this does consume/advance the shuffle order (that consumption is
    // real/final), so calling it twice in a row without committing returns
    // the same cached result rather than picking again. Returns false if
    // there's nothing to advance to (end of queue, repeat off -- in the
    // shuffle modes, "end" means one full queue's worth of tracks).
    bool peekNext();
    bool hasPendingNext() const { return m_pendingNextIndex >= 0; }
    const TrackInfo& pendingNextTrack() const { return m_queue[m_pendingNextIndex]; }

    // Moves currentTrack() to the previously-peeked pending track (pushes
    // history, clears the pending slot). Only valid if hasPendingNext().
    void commitPendingNext();

    // Discards a peeked-but-not-committed pending track without moving
    // anywhere (used when a crossfade gets aborted).
    void cancelPendingNext() { m_pendingNextIndex = -1; }

    // Convenience: peekNext() + commitPendingNext() in one call. Returns
    // false (queue unchanged) if there was nothing to advance to.
    bool moveNext();

    // Moves to the previous track in play history, or one index back (only
    // in ShuffleMode::Off) if history is exhausted. Returns false if
    // there's nowhere earlier to go.
    bool movePrevious();

private:
    void resetSmartShuffleFrom(int currentIndex);
    static QVector<int> RandomPermutation(int count);

    QVector<TrackInfo> m_queue;
    int m_queueIndex = -1;
    int m_pendingNextIndex = -1;
    QVector<int> m_history;

    ShuffleMode m_shuffleMode = ShuffleMode::Off;
    QVector<int> m_shuffleOrder; // used only in Smart mode
    int m_shufflePos = -1;
    RepeatMode m_repeatMode = RepeatMode::Off;
};

} // namespace audioforge
