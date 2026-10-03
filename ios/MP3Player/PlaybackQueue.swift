import Foundation

nonisolated enum ShuffleMode: String, Codable, Sendable {
    case off, random, smart
}

nonisolated enum RepeatMode: String, Codable, Sendable {
    case off, all, one

    var iconName: String { self == .one ? "repeat.1" : "repeat" }
}

/// "What plays next": the queue, shuffle/repeat state, and play history (so
/// Previous undoes shuffle jumps instead of just going index - 1). A port of
/// the desktop/Android PlaybackQueue.
///
/// Two-step "next" (`peekNext` + `commitPendingNext`) exists because a
/// crossfade needs to know the next song before switching to it;
/// `moveNext` does both at once.
nonisolated struct PlaybackQueue<Item> {
    private(set) var items: [Item] = []
    private(set) var currentIndex = -1
    private(set) var shuffleMode: ShuffleMode = .off
    private(set) var repeatMode: RepeatMode = .off

    private var pendingNextIndex = -1
    private var history: [Int] = []
    private var shuffleOrder: [Int] = []      // smart mode only
    private var shufflePos = -1

    var isEmpty: Bool { items.isEmpty }
    var currentItem: Item? { items.indices.contains(currentIndex) ? items[currentIndex] : nil }
    var hasPendingNext: Bool { pendingNextIndex >= 0 }
    var pendingNextItem: Item? { items.indices.contains(pendingNextIndex) ? items[pendingNextIndex] : nil }

    mutating func setQueue(_ newItems: [Item], startIndex: Int) {
        guard !newItems.isEmpty else { return }
        items = newItems
        currentIndex = min(max(startIndex, 0), items.count - 1)
        pendingNextIndex = -1
        history = [currentIndex]
        if shuffleMode == .smart { resetSmartShuffle(from: currentIndex) }
    }

    mutating func setShuffleMode(_ mode: ShuffleMode) {
        guard mode != shuffleMode else { return }
        shuffleMode = mode
        pendingNextIndex = -1
        if mode == .smart && !items.isEmpty { resetSmartShuffle(from: currentIndex) }
    }

    mutating func setRepeatMode(_ mode: RepeatMode) {
        repeatMode = mode
        pendingNextIndex = -1
    }

    mutating func cycleRepeatMode() {
        switch repeatMode {
        case .off: setRepeatMode(.all)
        case .all: setRepeatMode(.one)
        case .one: setRepeatMode(.off)
        }
    }

    mutating func cycleShuffleMode() {
        switch shuffleMode {
        case .off: setShuffleMode(.random)
        case .random: setShuffleMode(.smart)
        case .smart: setShuffleMode(.off)
        }
    }

    /// Resolves (and caches) which index plays next, without moving there.
    /// Returns false if there's nothing to advance to -- the end of the queue
    /// with Repeat off (in the shuffle modes, after one queue's worth of songs).
    mutating func peekNext() -> Bool {
        if pendingNextIndex >= 0 { return true }
        if items.isEmpty { return false }

        switch shuffleMode {
        case .random:
            // Picks with replacement, so there's no natural end: without
            // Repeat All, stop once a full queue's worth has played.
            if repeatMode != .all && history.count >= items.count { return false }
            if items.count > 1 {
                var index: Int
                repeat { index = Int.random(in: items.indices) } while index == currentIndex
                pendingNextIndex = index
            } else {
                pendingNextIndex = currentIndex
            }
        case .smart:
            if shuffleOrder.isEmpty { resetSmartShuffle(from: currentIndex) }
            if shufflePos + 1 >= shuffleOrder.count {
                // Every song has played once this lap.
                if repeatMode != .all { return false }
                var order = Array(items.indices).shuffled()
                if order.count > 1 && order[0] == currentIndex {
                    order.swapAt(0, 1)           // no immediate repeat across the lap boundary
                }
                shuffleOrder = order
                shufflePos = 0
            } else {
                shufflePos += 1
            }
            pendingNextIndex = shuffleOrder[shufflePos]
        case .off:
            var index = currentIndex + 1
            if index >= items.count {
                index = repeatMode == .all ? 0 : -1
            }
            pendingNextIndex = index
        }
        return pendingNextIndex >= 0
    }

    mutating func commitPendingNext() {
        guard pendingNextIndex >= 0 else { return }
        currentIndex = pendingNextIndex
        pendingNextIndex = -1
        history.append(currentIndex)
    }

    mutating func cancelPendingNext() {
        pendingNextIndex = -1
    }

    mutating func moveNext() -> Bool {
        guard peekNext() else {
            pendingNextIndex = -1
            return false
        }
        commitPendingNext()
        return true
    }

    /// Back through play history, or (shuffle off only) one index back once
    /// history runs out. Returns false if there's nowhere earlier to go.
    mutating func movePrevious() -> Bool {
        guard !items.isEmpty else { return false }
        pendingNextIndex = -1

        if history.count > 1 {
            history.removeLast()                 // drop the current song
            currentIndex = history[history.count - 1]
            return true
        }
        if shuffleMode == .off && currentIndex > 0 {
            currentIndex -= 1
            // Keep history in step with where we actually are.
            history = [currentIndex]
            return true
        }
        return false
    }

    /// Replaces queued items in place (same positions, e.g. after a tag edit).
    mutating func updateItems(_ transform: (Item) -> Item) {
        items = items.map(transform)
    }

    /// Smart shuffle: a fresh random order of every OTHER song, with the current one first ("already played").
    private mutating func resetSmartShuffle(from current: Int) {
        let rest = items.indices.filter { $0 != current }.shuffled()
        shuffleOrder = [current] + rest
        shufflePos = 0
    }
}
