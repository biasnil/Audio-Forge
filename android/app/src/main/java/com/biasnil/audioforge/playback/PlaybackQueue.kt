package com.biasnil.audioforge.playback

import kotlin.random.Random

enum class ShuffleMode { Off, Random, Smart }

enum class RepeatMode { Off, All, One }

/**
 * One place in the queue. [id] tells two copies of the same song apart and
 * stays the same when entries are moved, so history and the shuffle order
 * survive edits to the queue.
 */
data class QueueEntry<T>(val id: Long, val item: T)

/** The queue as saved between launches: positions in [items] instead of entry ids. */
data class SavedQueue<T>(
    val items: List<T>,
    val currentIndex: Int,
    val history: List<Int>,
    val shuffleOrder: List<Int>,
    val shufflePos: Int,
    val userQueued: List<Int>,
    val shuffleMode: ShuffleMode,
    val repeatMode: RepeatMode,
    /** What playback was started from (shown as "Next From ..."); not used by the queue itself. */
    val context: String? = null,
)

/**
 * "What plays next": the queue, shuffle/repeat state, and play history (so
 * Previous undoes shuffle jumps instead of just going index - 1). A port of
 * the desktop PlaybackQueue, including its fixes, plus Play next / Add to
 * queue and the Up next screen's edits. Plain Kotlin, no Android types --
 * PlaybackManager drives the actual player from it.
 *
 * Two-step "next" ([peekNext] + [commitPendingNext]) exists because a
 * crossfade needs to know the next track before switching to it;
 * [moveNext] does both at once. Peeking never changes the queue, so
 * cancelling a pick (a manual skip mid-crossfade) doesn't skip anything.
 *
 * What's "upcoming" depends on the shuffle mode: with shuffle off it's the
 * entries after the current one; in Smart mode, the rest of this lap's
 * shuffle order; in Random mode only the songs added with Play next / Add to
 * queue (after those, picks are random). Every edit goes through
 * [setUpcoming], which rewrites that sequence.
 */
class PlaybackQueue<T>(private val random: Random = Random.Default) {

    private var queue: MutableList<QueueEntry<T>> = ArrayList()
    private var nextId = 0L
    private var current: QueueEntry<T>? = null
    private val history = ArrayList<Long>()
    private var shuffleOrder: MutableList<Long> = ArrayList() // Smart mode only
    private var shufflePos = -1
    /** Songs added with Play next / Add to queue that haven't played yet, in play order. */
    private val userQueued = ArrayList<Long>()

    // The resolved next pick (see peekNext), plus in Smart mode where the
    // shuffle order stands once it's committed.
    private var pending: QueueEntry<T>? = null
    private var pendingShufflePos = -1
    private var pendingShuffleOrder: MutableList<Long>? = null

    var shuffleMode = ShuffleMode.Off
        private set
    var repeatMode = RepeatMode.Off
        private set

    /**
     * Builds the Smart-shuffle order: indices into the items, [start] first.
     * null = plain random order. Used for harmonic shuffle (compatible keys
     * and tempos next to each other). Call [reshuffle] after changing it.
     */
    var smartOrder: ((items: List<T>, start: Int) -> List<Int>)? = null

    /** Bumped whenever what's queued changes (not on a mere peek), so the UI knows to re-read it. */
    var version = 0L
        private set

    val isEmpty: Boolean get() = queue.isEmpty()
    val currentItem: T? get() = current?.item
    val currentEntry: QueueEntry<T>? get() = current
    val hasPendingNext: Boolean get() = pending != null
    val pendingNextItem: T? get() = pending?.item
    val pendingNextEntry: QueueEntry<T>? get() = pending

    /** Every entry, in queue order (shuffle aside). */
    val items: List<T> get() = queue.map { it.item }

    private val currentIndex: Int
        get() = current?.let { c -> queue.indexOfFirst { it.id == c.id } } ?: -1

    fun setQueue(items: List<T>, startIndex: Int) {
        if (items.isEmpty()) return
        queue = items.mapTo(ArrayList(), ::newEntry)
        val start = queue[startIndex.coerceIn(0, queue.lastIndex)]
        current = start
        clearPending()
        history.clear()
        history += start.id
        userQueued.clear()
        shuffleOrder = ArrayList()
        shufflePos = -1
        if (shuffleMode == ShuffleMode.Smart) resetSmartShuffle(emptyList())
        changed()
    }

    fun cycleRepeatMode() {
        repeatMode = when (repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        clearPending() // whether there's a next track can depend on it
        changed()
    }

    /** Songs added with Play next / Add to queue stay first in line in the new mode. */
    fun cycleShuffleMode() {
        val waiting = upcomingIds().filter { it in userQueued }
        clearPending()
        shuffleMode = when (shuffleMode) {
            ShuffleMode.Off -> ShuffleMode.Random
            ShuffleMode.Random -> ShuffleMode.Smart
            ShuffleMode.Smart -> ShuffleMode.Off
        }
        when (shuffleMode) {
            ShuffleMode.Smart -> if (queue.isNotEmpty()) resetSmartShuffle(waiting)
            ShuffleMode.Off -> moveAfterCurrent(waiting)
            ShuffleMode.Random -> Unit
        }
        userQueued.clear()
        userQueued += waiting
        changed()
    }

    // --- Moving through the queue -------------------------------------------

    /**
     * Resolves (and caches) which entry plays next, without moving there.
     * Calling it again before a commit returns the same pick. Returns false
     * if there's nothing to advance to -- the end of the queue with Repeat
     * off (in the shuffle modes, after one queue's worth of tracks).
     */
    fun peekNext(): Boolean {
        if (pending != null) return true
        val playing = current ?: return false

        when (shuffleMode) {
            ShuffleMode.Random -> {
                val queued = userQueued.firstOrNull()?.let(::entryFor)
                if (queued != null) {
                    pending = queued
                } else {
                    // Picks with replacement, so there's no natural end: without
                    // Repeat All, stop once a full queue's worth has played.
                    if (repeatMode != RepeatMode.All && history.size >= queue.size) return false
                    pending = if (queue.size > 1) {
                        var pick: QueueEntry<T>
                        do {
                            pick = queue[random.nextInt(queue.size)]
                        } while (pick.id == playing.id)
                        pick
                    } else {
                        playing
                    }
                }
            }
            ShuffleMode.Smart -> {
                if (shuffleOrder.isEmpty()) resetSmartShuffle(emptyList())
                val order: List<Long>
                if (shufflePos + 1 >= shuffleOrder.size) {
                    // Every track has played once this lap.
                    if (repeatMode != RepeatMode.All) return false
                    val build = smartOrder
                    val lap = if (build != null) {
                        // A fresh lap that continues from the current song; it plays last this lap.
                        val built = build(queue.map { it.item }, currentIndex).map { queue[it].id }
                        (built.drop(1) + playing.id).toMutableList()
                    } else {
                        queue.map { it.id }.shuffled(random).toMutableList().also { order ->
                            if (order.size > 1 && order[0] == playing.id) {
                                // avoid an immediate repeat across the lap boundary
                                order[0] = order[1]
                                order[1] = playing.id
                            }
                        }
                    }
                    pendingShuffleOrder = lap
                    pendingShufflePos = 0
                    order = lap
                } else {
                    pendingShufflePos = shufflePos + 1
                    order = shuffleOrder
                }
                pending = entryFor(order[pendingShufflePos])
            }
            ShuffleMode.Off -> {
                var index = currentIndex + 1
                if (index >= queue.size) {
                    if (repeatMode != RepeatMode.All) return false
                    index = 0
                }
                pending = queue[index]
            }
        }
        return pending != null
    }

    fun commitPendingNext() {
        val next = pending ?: return
        if (shuffleMode == ShuffleMode.Smart) {
            pendingShuffleOrder?.let { shuffleOrder = it }
            shufflePos = pendingShufflePos
        }
        current = next
        history += next.id
        userQueued.remove(next.id)
        clearPending()
        changed()
    }

    fun cancelPendingNext() = clearPending()

    fun moveNext(): Boolean {
        if (!peekNext()) {
            clearPending()
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
        clearPending()

        if (history.size > 1) {
            history.removeAt(history.lastIndex) // drop the current track
            current = entryFor(history.last()) ?: return false
            changed()
            return true
        }
        val index = currentIndex
        if (shuffleMode == ShuffleMode.Off && index > 0) {
            val previous = queue[index - 1]
            current = previous
            // Keep history in step with where we actually are, so a later
            // Next + Previous doesn't pop back to a stale entry.
            history.clear()
            history += previous.id
            changed()
            return true
        }
        return false
    }

    /**
     * Makes entry [id] the current one (a tap in Up next). Jumping ahead
     * skips the songs in between, like playing on from there; jumping back
     * to a played song leaves what's upcoming alone.
     */
    fun jumpTo(id: Long): Boolean {
        if (id == current?.id) return false
        val target = entryFor(id) ?: return false
        val upcoming = upcomingIds()
        val position = upcoming.indexOf(id)
        clearPending()
        if (position >= 0) {
            val passed = upcoming.subList(0, position + 1).toHashSet()
            if (shuffleMode == ShuffleMode.Smart) shufflePos += position + 1
            userQueued.removeAll(passed)
        }
        current = target
        history += id
        changed()
        return true
    }

    // --- Editing what's upcoming -----------------------------------------------

    /** Straight after the current song (before anything added earlier). */
    fun playNext(items: List<T>) {
        if (items.isEmpty() || current == null) return
        val added = items.map(::newEntry)
        val upcoming = upcomingIds() // before touching userQueued: in Random mode it *is* the upcoming list
        userQueued.addAll(0, added.map { it.id })
        setUpcoming(added.map { it.id } + upcoming, added)
    }

    /** After the current song and anything else added with Play next / Add to queue. */
    fun addToQueue(items: List<T>) {
        if (items.isEmpty() || current == null) return
        val added = items.map(::newEntry)
        val upcoming = upcomingIds() // before touching userQueued, as in playNext
        val insertAt = upcoming.indexOfLast { it in userQueued } + 1
        userQueued += added.map { it.id }
        setUpcoming(upcoming.take(insertAt) + added.map { it.id } + upcoming.drop(insertAt), added)
    }

    /** Moves upcoming entry [id] to position [toIndex] among the upcoming ones. */
    fun moveUpcoming(id: Long, toIndex: Int) {
        val upcoming = upcomingIds().toMutableList()
        if (!upcoming.remove(id)) return
        upcoming.add(toIndex.coerceIn(0, upcoming.size), id)
        setUpcoming(upcoming, emptyList())
    }

    fun removeUpcoming(id: Long) {
        val upcoming = upcomingIds()
        if (id !in upcoming) return
        setUpcoming(upcoming - id, emptyList())
    }

    /**
     * Removes everything waiting to play; the current song carries on. In
     * Random mode that's every song not played yet, so the random picks
     * stop too.
     */
    fun clearUpcoming() {
        val playing = current ?: return
        if (shuffleMode == ShuffleMode.Random) {
            val keep = history.toHashSet() + playing.id
            removeEntries(queue.filter { it.id !in keep }.map { it.id }.toHashSet())
            userQueued.clear()
            clearPending()
            changed()
        } else {
            setUpcoming(emptyList(), emptyList())
        }
    }

    /** Removes the songs added with Play next / Add to queue that haven't played yet (iOS "Clear Playing Next"). */
    fun clearQueued() {
        if (current == null) return
        setUpcoming(upcomingIds().drop(queuedCount()), emptyList())
    }

    /** What's waiting to play, in order (in Random mode, only the songs added to play next). */
    fun upcoming(): List<QueueEntry<T>> {
        val byId = queue.associateBy { it.id }
        return upcomingIds().mapNotNull { byId[it] }
    }

    /** Played songs, oldest first, not counting the current one. */
    fun played(): List<QueueEntry<T>> {
        val byId = queue.associateBy { it.id }
        return history.dropLast(1).mapNotNull { byId[it] }
    }

    /**
     * How many of [upcoming], from the front, were added with Play next /
     * Add to queue. They're always together at the front: added songs go
     * there, and Up next only moves a song within its own section.
     */
    fun queuedCount(): Int {
        val queued = userQueued.toHashSet()
        return upcomingIds().takeWhile { it in queued }.size
    }

    /** Whether more songs follow [upcoming]: random picks, or Repeat All starting over. */
    fun continuesAfterUpcoming(): Boolean =
        current != null && (repeatMode == RepeatMode.All ||
            (shuffleMode == ShuffleMode.Random && history.size < queue.size))

    /** Replaces queued items in place (same positions, e.g. after a tag edit). */
    fun updateItems(transform: (T) -> T) {
        queue = queue.mapTo(ArrayList()) { it.copy(item = transform(it.item)) }
        current = current?.let { entryFor(it.id) }
        pending = pending?.let { entryFor(it.id) }
        changed()
    }

    // --- Saving between launches -------------------------------------------------

    fun save(): SavedQueue<T>? {
        val playing = current ?: return null
        val indexOf = HashMap<Long, Int>(queue.size * 2)
        queue.forEachIndexed { index, entry -> indexOf[entry.id] = index }
        return SavedQueue(
            items = queue.map { it.item },
            currentIndex = indexOf.getValue(playing.id),
            history = history.mapNotNull { indexOf[it] },
            shuffleOrder = shuffleOrder.mapNotNull { indexOf[it] },
            shufflePos = shufflePos,
            userQueued = userQueued.mapNotNull { indexOf[it] },
            shuffleMode = shuffleMode,
            repeatMode = repeatMode,
        )
    }

    fun restore(saved: SavedQueue<T>) {
        if (saved.items.isEmpty()) return
        queue = saved.items.mapTo(ArrayList(), ::newEntry)
        fun idAt(index: Int): Long? = queue.getOrNull(index)?.id
        val playing = queue[saved.currentIndex.coerceIn(0, queue.lastIndex)]
        current = playing
        clearPending()
        shuffleMode = saved.shuffleMode
        repeatMode = saved.repeatMode
        history.clear()
        history += saved.history.mapNotNull(::idAt)
        if (history.lastOrNull() != playing.id) history += playing.id
        userQueued.clear()
        userQueued += saved.userQueued.mapNotNull(::idAt).distinct()
        shuffleOrder = saved.shuffleOrder.mapNotNullTo(ArrayList(), ::idAt)
        shufflePos = saved.shufflePos
        val orderIsWhole = shuffleOrder.size == queue.size && shuffleOrder.toHashSet().size == queue.size
        if (shuffleMode == ShuffleMode.Smart && (!orderIsWhole || shufflePos !in shuffleOrder.indices)) {
            resetSmartShuffle(userQueued.toList())
        }
        changed()
    }

    // --- Internals -------------------------------------------------------------

    private fun upcomingIds(): List<Long> {
        if (current == null) return emptyList()
        return when (shuffleMode) {
            ShuffleMode.Off -> queue.subList(currentIndex + 1, queue.size).map { it.id }
            ShuffleMode.Smart ->
                if (shufflePos < 0) emptyList() else shuffleOrder.subList(shufflePos + 1, shuffleOrder.size).toList()
            ShuffleMode.Random -> userQueued.toList()
        }
    }

    /**
     * Makes [newOrder] the upcoming sequence. Entries upcoming before but
     * missing from [newOrder] leave the queue; [added] are new entries
     * appearing in it.
     */
    private fun setUpcoming(newOrder: List<Long>, added: List<QueueEntry<T>>) {
        if (current == null) return
        val keep = newOrder.toHashSet()
        val removed = upcomingIds().filterTo(HashSet()) { it !in keep }
        when (shuffleMode) {
            ShuffleMode.Off -> {
                val byId = (queue + added).associateBy { it.id }
                val before = queue.subList(0, currentIndex + 1).toList()
                queue = (before + newOrder.mapNotNull { byId[it] }).toMutableList()
                history.removeAll(removed)
            }
            ShuffleMode.Smart -> {
                if (shuffleOrder.isEmpty()) resetSmartShuffle(emptyList())
                queue.addAll(added) // where in the list doesn't matter in shuffle
                removeEntries(removed)
                shuffleOrder = (shuffleOrder.subList(0, shufflePos + 1) + newOrder).toMutableList()
            }
            ShuffleMode.Random -> {
                queue.addAll(added)
                removeEntries(removed)
            }
        }
        // Keep the added-songs list in the new play order (Random mode: it *is* the order).
        val wasQueued = userQueued.toHashSet()
        userQueued.clear()
        userQueued += if (shuffleMode == ShuffleMode.Random) newOrder else newOrder.filter { it in wasQueued }
        clearPending()
        changed()
    }

    /** Drops entries from the queue, history and the shuffle order (keeping the shuffle position on the same entry). */
    private fun removeEntries(ids: Set<Long>) {
        if (ids.isEmpty()) return
        queue.removeAll { it.id in ids }
        history.removeAll(ids)
        userQueued.removeAll(ids)
        val removedBeforePos = shuffleOrder.take(shufflePos + 1).count { it in ids }
        shuffleOrder.removeAll(ids)
        shufflePos -= removedBeforePos
    }

    /** Smart shuffle: a fresh random order with the current song first ("already played"), then [first], then the rest. */
    private fun resetSmartShuffle(first: List<Long>) {
        val playing = current ?: return
        val firstSet = first.toHashSet()
        val build = smartOrder
        val rest = if (build != null) {
            build(queue.map { it.item }, currentIndex).map { queue[it].id }.filter { it != playing.id && it !in firstSet }
        } else {
            queue.map { it.id }.filter { it != playing.id && it !in firstSet }.shuffled(random)
        }
        shuffleOrder = (listOf(playing.id) + first + rest).toMutableList()
        shufflePos = 0
    }

    /** Rebuilds the Smart-shuffle order from the current song (after [smartOrder] changed). Queued songs stay first. */
    fun reshuffle() {
        if (shuffleMode != ShuffleMode.Smart || queue.isEmpty()) return
        val waiting = upcomingIds().filter { it in userQueued }
        clearPending()
        resetSmartShuffle(waiting)
        changed()
    }

    /** Shuffle off: physically moves [ids] to just after the current entry, in that order. */
    private fun moveAfterCurrent(ids: List<Long>) {
        if (ids.isEmpty() || current == null) return
        val moving = ids.mapNotNull(::entryFor)
        val set = ids.toHashSet()
        queue.removeAll { it.id in set }
        queue.addAll(currentIndex + 1, moving)
    }

    private fun entryFor(id: Long): QueueEntry<T>? = queue.firstOrNull { it.id == id }

    private fun newEntry(item: T) = QueueEntry(nextId++, item)

    private fun clearPending() {
        pending = null
        pendingShufflePos = -1
        pendingShuffleOrder = null
    }

    private fun changed() {
        version++
    }
}

/**
 * Converts a saved queue's items, dropping those [transform] returns null
 * for (songs no longer in the library) and fixing up positions. If the
 * current song is gone, the next remaining one becomes current. Null if
 * nothing is left.
 */
fun <A, B> SavedQueue<A>.mapItemsNotNull(transform: (A) -> B?): SavedQueue<B>? {
    val newIndex = IntArray(items.size) { -1 }
    val kept = ArrayList<B>(items.size)
    items.forEachIndexed { index, item ->
        transform(item)?.let {
            newIndex[index] = kept.size
            kept += it
        }
    }
    if (kept.isEmpty()) return null
    fun remap(indices: List<Int>) = indices.mapNotNull { newIndex.getOrNull(it)?.takeIf { i -> i >= 0 } }
    val current = (currentIndex until items.size).firstNotNullOfOrNull { newIndex.getOrNull(it)?.takeIf { i -> i >= 0 } }
        ?: kept.lastIndex
    // Shuffle position: count what survived up to it.
    val survivingBeforePos = shuffleOrder.take(shufflePos + 1).count { newIndex.getOrNull(it)?.let { i -> i >= 0 } == true }
    return SavedQueue(
        items = kept,
        currentIndex = current,
        history = remap(history),
        shuffleOrder = remap(shuffleOrder),
        shufflePos = survivingBeforePos - 1,
        userQueued = remap(userQueued),
        shuffleMode = shuffleMode,
        repeatMode = repeatMode,
        context = context,
    )
}
