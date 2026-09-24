package com.biasnil.audioforge.playback

import kotlin.random.Random

enum class ShuffleMode { Off, Random, Smart }

enum class RepeatMode { Off, All, One }

/**
 * "What plays next": the queue, shuffle/repeat state, and play history (so
 * Previous undoes shuffle jumps instead of just going index - 1). A port of
 * the desktop PlaybackQueue, including its fixes. Plain Kotlin, no Android
 * types -- PlaybackManager drives the actual player from it.
 *
 * Two-step "next" ([peekNext] + [commitPendingNext]) exists because a
 * crossfade (stage 3) needs to know the next track before switching to it;
 * [moveNext] does both at once.
 */
class PlaybackQueue<T>(private val random: Random = Random.Default) {

    private var queue: List<T> = emptyList()
    private var pendingNextIndex = -1
    private val history = ArrayList<Int>()
    private var shuffleOrder: List<Int> = emptyList() // Smart mode only
    private var shufflePos = -1

    var currentIndex = -1
        private set
    var shuffleMode = ShuffleMode.Off
        private set
    var repeatMode = RepeatMode.Off
        private set

    val isEmpty: Boolean get() = queue.isEmpty()
    val items: List<T> get() = queue
    val currentItem: T? get() = queue.getOrNull(currentIndex)
    val hasPendingNext: Boolean get() = pendingNextIndex >= 0
    val pendingNextItem: T? get() = queue.getOrNull(pendingNextIndex)

    fun setQueue(items: List<T>, startIndex: Int) {
        if (items.isEmpty()) return
        queue = items.toList()
        currentIndex = startIndex.coerceIn(0, queue.lastIndex)
        pendingNextIndex = -1
        history.clear()
        history += currentIndex
        if (shuffleMode == ShuffleMode.Smart) {
            resetSmartShuffleFrom(currentIndex)
        }
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
    }

    fun cycleShuffleMode() {
        shuffleMode = when (shuffleMode) {
            ShuffleMode.Off -> ShuffleMode.Random
            ShuffleMode.Random -> {
                if (queue.isNotEmpty()) resetSmartShuffleFrom(currentIndex)
                ShuffleMode.Smart
            }
            ShuffleMode.Smart -> ShuffleMode.Off
        }
    }

    /**
     * Resolves (and caches) which index plays next, without moving there.
     * Calling it again before a commit returns the same pick. Returns false
     * if there's nothing to advance to -- the end of the queue with Repeat
     * off (in the shuffle modes, after one queue's worth of tracks).
     */
    fun peekNext(): Boolean {
        if (pendingNextIndex >= 0) return true
        if (queue.isEmpty()) return false

        when (shuffleMode) {
            ShuffleMode.Random -> {
                // Picks with replacement, so there's no natural end: without
                // Repeat All, stop once a full queue's worth has played.
                if (repeatMode != RepeatMode.All && history.size >= queue.size) return false
                pendingNextIndex = if (queue.size > 1) {
                    var index: Int
                    do {
                        index = random.nextInt(queue.size)
                    } while (index == currentIndex)
                    index
                } else {
                    currentIndex
                }
            }
            ShuffleMode.Smart -> {
                if (shuffleOrder.isEmpty()) resetSmartShuffleFrom(currentIndex)
                if (shufflePos + 1 >= shuffleOrder.size) {
                    // Every track has played once this lap.
                    if (repeatMode != RepeatMode.All) return false
                    val order = queue.indices.shuffled(random).toMutableList()
                    if (order.size > 1 && order[0] == currentIndex) {
                        // avoid an immediate repeat across the lap boundary
                        order[0] = order[1]
                        order[1] = currentIndex
                    }
                    shuffleOrder = order
                    shufflePos = 0
                } else {
                    shufflePos++
                }
                pendingNextIndex = shuffleOrder[shufflePos]
            }
            ShuffleMode.Off -> {
                var index = currentIndex + 1
                if (index >= queue.size) {
                    index = if (repeatMode == RepeatMode.All) 0 else -1
                }
                pendingNextIndex = index
            }
        }
        return pendingNextIndex >= 0
    }

    fun commitPendingNext() {
        if (pendingNextIndex < 0) return
        currentIndex = pendingNextIndex
        pendingNextIndex = -1
        history += currentIndex
    }

    fun cancelPendingNext() {
        pendingNextIndex = -1
    }

    fun moveNext(): Boolean {
        if (!peekNext()) {
            pendingNextIndex = -1
            return false
        }
        commitPendingNext()
        return true
    }

    /**
     * Back through play history, or (shuffle off only) one index back once
     * history runs out. Returns false if there's nowhere earlier to go.
     */
    fun movePrevious(): Boolean {
        if (queue.isEmpty()) return false
        pendingNextIndex = -1

        if (history.size > 1) {
            history.removeAt(history.lastIndex) // drop the current track
            currentIndex = history.last()
            return true
        }
        if (shuffleMode == ShuffleMode.Off && currentIndex > 0) {
            currentIndex--
            // Keep history in step with where we actually are, so a later
            // Next + Previous doesn't pop back to a stale entry.
            history.clear()
            history += currentIndex
            return true
        }
        return false
    }

    /** Smart shuffle: a fresh random order of every OTHER track, with the current one pinned first ("already played"). */
    private fun resetSmartShuffleFrom(current: Int) {
        val rest = queue.indices.filter { it != current }.shuffled(random)
        shuffleOrder = listOf(current) + rest
        shufflePos = 0
    }
}
