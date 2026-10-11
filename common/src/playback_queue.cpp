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
    if (m_smartOrder)
    {
        QVector<int> order = m_smartOrder(m_queue, currentIndex);
        if (order.size() == m_queue.size() && !order.isEmpty() && order.first() == currentIndex)
        {
            m_shuffleOrder = order;
            m_shufflePos = 0;
            return;
        }
    }

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
    m_pendingManualId = 0;
    m_currentManual.reset();
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
    if (hasPendingNext())
    {
        return true; // already resolved for this transition
    }
    if (!m_manual.isEmpty())
    {
        m_pendingManualId = m_manual.first().id; // hand-queued tracks always go first
        return true;
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
            if (m_smartOrder)
            {
                // A fresh lap continuing from the current track, which plays last this lap.
                QVector<int> built = m_smartOrder(m_queue, justPlayed);
                built.removeFirst();
                built << justPlayed;
                m_shuffleOrder = built;
            }
            else
            {
                m_shuffleOrder = RandomPermutation(m_queue.size());
                if (m_shuffleOrder.size() > 1 && m_shuffleOrder.first() == justPlayed)
                {
                    m_shuffleOrder.swapItemsAt(0, 1); // avoid an immediate repeat across the lap boundary
                }
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

const TrackInfo& PlaybackQueue::pendingNextTrack() const
{
    if (m_pendingManualId != 0)
    {
        for (const QueuedTrack& q : m_manual)
        {
            if (q.id == m_pendingManualId)
            {
                return q.track;
            }
        }
    }
    return m_queue[m_pendingNextIndex];
}

void PlaybackQueue::cancelPendingNext()
{
    m_pendingNextIndex = -1;
    m_pendingManualId = 0;
}

void PlaybackQueue::commitPendingNext()
{
    if (m_pendingManualId != 0)
    {
        for (int i = 0; i < m_manual.size(); ++i)
        {
            if (m_manual[i].id == m_pendingManualId)
            {
                m_currentManual = m_manual[i].track;
                m_manual.removeAt(i);
                break;
            }
        }
        m_pendingManualId = 0;
        return;
    }
    if (m_pendingNextIndex < 0)
    {
        return;
    }
    m_currentManual.reset(); // back to the list
    m_queueIndex = m_pendingNextIndex;
    m_pendingNextIndex = -1;
    m_history << m_queueIndex;
}

bool PlaybackQueue::moveNext()
{
    if (!peekNext())
    {
        cancelPendingNext();
        return false;
    }
    commitPendingNext();
    return true;
}

void PlaybackQueue::reshuffle()
{
    if (m_shuffleMode != ShuffleMode::Smart || m_queue.isEmpty())
    {
        return;
    }
    m_pendingNextIndex = -1;
    resetSmartShuffleFrom(m_queueIndex);
}

bool PlaybackQueue::movePrevious()
{
    cancelPendingNext();
    if (m_currentManual)
    {
        // From a hand-queued track, back to the list's track that was playing before it.
        m_currentManual.reset();
        return !m_queue.isEmpty();
    }
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

void PlaybackQueue::restore(const QVector<TrackInfo>& list, int index, const std::optional<TrackInfo>& currentManual,
                            const QVector<TrackInfo>& playingNext, ShuffleMode shuffle, RepeatMode repeat)
{
    if (list.isEmpty())
    {
        return;
    }
    m_shuffleMode = shuffle;
    m_repeatMode = repeat;
    m_shuffleOrder.clear();
    m_shufflePos = -1;
    setQueue(list, index); // also builds the smart-shuffle order when shuffle is Smart
    m_currentManual = currentManual;
    m_manual.clear();
    addToQueue(playingNext);
}

// --- Up Next ------------------------------------------------------------------

void PlaybackQueue::playNext(const QVector<TrackInfo>& tracks)
{
    cancelPendingNext();
    QVector<QueuedTrack> queued;
    for (const TrackInfo& t : tracks)
    {
        queued << QueuedTrack{m_nextManualId++, t};
    }
    m_manual = queued + m_manual;
}

void PlaybackQueue::addToQueue(const QVector<TrackInfo>& tracks)
{
    cancelPendingNext();
    for (const TrackInfo& t : tracks)
    {
        m_manual << QueuedTrack{m_nextManualId++, t};
    }
}

void PlaybackQueue::removeFromManualQueue(int position)
{
    if (position >= 0 && position < m_manual.size())
    {
        cancelPendingNext();
        m_manual.removeAt(position);
    }
}

void PlaybackQueue::moveInManualQueue(int from, int to)
{
    if (from < 0 || from >= m_manual.size() || to < 0 || to >= m_manual.size() || from == to)
    {
        return;
    }
    cancelPendingNext();
    m_manual.move(from, to);
}

void PlaybackQueue::clearManualQueue()
{
    cancelPendingNext();
    m_manual.clear();
}

void PlaybackQueue::jumpToManual(int position)
{
    if (position < 0 || position >= m_manual.size())
    {
        return;
    }
    cancelPendingNext();
    m_currentManual = m_manual[position].track;
    m_manual.remove(0, position + 1);
}

std::optional<QVector<int>> PlaybackQueue::upcoming(int limit) const
{
    QVector<int> result;
    switch (m_shuffleMode)
    {
        case ShuffleMode::Random:
            return std::nullopt;
        case ShuffleMode::Smart:
        {
            // A pick already made for a hand-off has been taken off the order
            // (peekNext consumed it) but hasn't played yet -- it still comes next.
            int from = m_shufflePos + 1;
            if (m_pendingNextIndex >= 0 && m_shufflePos >= 0)
            {
                from = m_shufflePos;
            }
            for (int i = qMax(from, 0); i < m_shuffleOrder.size() && result.size() < limit; ++i)
            {
                result << m_shuffleOrder[i];
            }
            return result;
        }
        case ShuffleMode::Off:
        {
            if (m_queue.isEmpty())
            {
                return result;
            }
            int index = m_queueIndex;
            while (result.size() < limit)
            {
                ++index;
                if (index >= m_queue.size())
                {
                    if (m_repeatMode != RepeatMode::All)
                    {
                        break;
                    }
                    index = 0;
                }
                if (index == m_queueIndex)
                {
                    break;
                }
                result << index;
            }
            return result;
        }
    }
    return result;
}

void PlaybackQueue::jumpTo(int index)
{
    if (index < 0 || index >= m_queue.size())
    {
        return;
    }
    cancelPendingNext();
    m_currentManual.reset();
    if (m_shuffleMode == ShuffleMode::Smart)
    {
        const int position = m_shuffleOrder.indexOf(index);
        if (position > m_shufflePos)
        {
            m_shufflePos = position;
        }
        else
        {
            resetSmartShuffleFrom(index);
        }
    }
    m_queueIndex = index;
    m_history << index;
}

void PlaybackQueue::removeAt(int index)
{
    if (index < 0 || index >= m_queue.size() || index == m_queueIndex)
    {
        return;
    }
    cancelPendingNext();
    m_queue.removeAt(index);
    if (m_queueIndex > index)
    {
        --m_queueIndex;
    }

    QVector<int> history;
    for (int h : m_history)
    {
        if (h != index)
        {
            history << (h > index ? h - 1 : h);
        }
    }
    m_history = history;

    const int position = m_shuffleOrder.indexOf(index);
    if (position >= 0)
    {
        m_shuffleOrder.removeAt(position);
        if (position <= m_shufflePos)
        {
            --m_shufflePos;
        }
    }
    for (int& i : m_shuffleOrder)
    {
        if (i > index)
        {
            --i;
        }
    }
}

} // namespace audioforge