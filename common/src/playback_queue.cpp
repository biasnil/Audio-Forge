#include "audioforge/playback_queue.hpp"

#include <QRandomGenerator>

namespace audioforge {

QVector<int> PlaybackQueue::RandomPermutation(int count)
{
    QVector<int> order;
    order.reserve(count);
    for (int i = 0; i < count; ++i)
    {
        order << i;
    }
    for (int i = order.size() - 1; i > 0; --i)
    {
        int j = static_cast<int>(QRandomGenerator::global()->bounded(i + 1));
        order.swapItemsAt(i, j);
    }
    return order;
}

// Smart shuffle: builds a fresh random order of every OTHER track, with the
// currently-playing one pinned at position 0 (already "used up") so moving
// on picks a genuinely new track, not a repeat.
void PlaybackQueue::resetSmartShuffleFrom(int currentIndex)
{
    QVector<int> rest;
    for (int i = 0; i < m_queue.size(); ++i)
    {
        if (i != currentIndex)
        {
            rest << i;
        }
    }
    for (int i = rest.size() - 1; i > 0; --i)
    {
        int j = static_cast<int>(QRandomGenerator::global()->bounded(i + 1));
        rest.swapItemsAt(i, j);
    }
    m_shuffleOrder = rest;
    m_shuffleOrder.prepend(currentIndex);
    m_shufflePos = 0;
}

void PlaybackQueue::setQueue(const QVector<TrackInfo>& queue, int startIndex)
{
    if (queue.isEmpty())
    {
        return;
    }

    m_queue = queue;
    m_queueIndex = qBound(0, startIndex, m_queue.size() - 1);
    m_pendingNextIndex = -1;
    m_history.clear();
    m_history << m_queueIndex;

    if (m_shuffleMode == ShuffleMode::Smart)
    {
        resetSmartShuffleFrom(m_queueIndex);
    }
}

void PlaybackQueue::cycleRepeatMode()
{
    switch (m_repeatMode)
    {
        case RepeatMode::Off: m_repeatMode = RepeatMode::All; break;
        case RepeatMode::All: m_repeatMode = RepeatMode::One; break;
        case RepeatMode::One: m_repeatMode = RepeatMode::Off; break;
    }
}

void PlaybackQueue::cycleShuffleMode()
{
    switch (m_shuffleMode)
    {
        case ShuffleMode::Off:
            m_shuffleMode = ShuffleMode::Random;
            break;
        case ShuffleMode::Random:
            m_shuffleMode = ShuffleMode::Smart;
            if (!m_queue.isEmpty())
            {
                resetSmartShuffleFrom(m_queueIndex);
            }
            break;
        case ShuffleMode::Smart:
            m_shuffleMode = ShuffleMode::Off;
            break;
    }
}

bool PlaybackQueue::peekNext()
{
    if (m_pendingNextIndex >= 0)
    {
        return true; // already resolved for this transition
    }
    if (m_queue.isEmpty())
    {
        return false;
    }

    if (m_shuffleMode == ShuffleMode::Random)
    {
        // Random picks with replacement, so there's no natural "end" --
        // without Repeat All, stop once a full queue's worth of tracks has
        // played (m_history holds one entry per track played since
        // setQueue(), minus any undone by Previous).
        if (m_repeatMode != RepeatMode::All && m_history.size() >= m_queue.size())
        {
            return false;
        }
        if (m_queue.size() > 1)
        {
            int newIndex;
            do
            {
                newIndex = static_cast<int>(QRandomGenerator::global()->bounded(m_queue.size()));
            } while (newIndex == m_queueIndex);
            m_pendingNextIndex = newIndex;
        }
        else
        {
            m_pendingNextIndex = m_queueIndex;
        }
    }
    else if (m_shuffleMode == ShuffleMode::Smart)
    {
        if (m_shuffleOrder.isEmpty())
        {
            resetSmartShuffleFrom(m_queueIndex);
        }
        if (m_shufflePos + 1 >= m_shuffleOrder.size())
        {
            // Every track has played once this lap -- only start another
            // lap with Repeat All, same as sequential mode's wrap-around.
            if (m_repeatMode != RepeatMode::All)
            {
                return false;
            }
            int justPlayed = m_queueIndex;
            m_shuffleOrder = RandomPermutation(m_queue.size());
            if (m_shuffleOrder.size() > 1 && m_shuffleOrder.first() == justPlayed)
            {
                m_shuffleOrder.swapItemsAt(0, 1); // avoid an immediate repeat across the lap boundary
            }
            m_shufflePos = 0;
        }
        else
        {
            m_shufflePos++;
        }
        m_pendingNextIndex = m_shuffleOrder[m_shufflePos];
    }
    else // Off -- plain sequential
    {
        int idx = m_queueIndex + 1;
        if (idx >= m_queue.size())
        {
            idx = (m_repeatMode == RepeatMode::All) ? 0 : -1;
        }
        m_pendingNextIndex = idx;
    }

    return m_pendingNextIndex >= 0;
}

void PlaybackQueue::commitPendingNext()
{
    if (m_pendingNextIndex < 0)
    {
        return;
    }
    m_queueIndex = m_pendingNextIndex;
    m_pendingNextIndex = -1;
    m_history << m_queueIndex;
}

bool PlaybackQueue::moveNext()
{
    if (!peekNext())
    {
        m_pendingNextIndex = -1;
        return false;
    }
    commitPendingNext();
    return true;
}

bool PlaybackQueue::movePrevious()
{
    if (m_queue.isEmpty())
    {
        return false;
    }

    if (m_history.size() > 1)
    {
        m_history.removeLast(); // drop the current track
        m_queueIndex = m_history.last();
        return true;
    }
    if (m_shuffleMode == ShuffleMode::Off && m_queueIndex > 0)
    {
        m_queueIndex--;
        // Keep history in step with where we actually are -- otherwise a
        // later Next + Previous would pop back to the stale entry (e.g.
        // 5 -> Prev 4 -> Prev 3 -> Next 4 -> Prev would land on 5, not 3).
        m_history.clear();
        m_history << m_queueIndex;
        return true;
    }
    return false; // nothing earlier to go back to
}

} // namespace audioforge
