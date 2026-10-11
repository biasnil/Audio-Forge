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

    /// Builds the smart-shuffle order (first element = `start`). nil = plain random order.
    /// Used for harmonic shuffle (compatible keys and tempos next to each other).
    var smartOrder: (@Sendable (_ items: [Item], _ start: Int) -> [Int])?

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
                var order: [Int]
                if let smartOrder {
                    // A fresh lap that continues from the current song; it plays last this lap.
                    let built = smartOrder(items, currentIndex)
                    order = Array(built.dropFirst()) + [currentIndex]
                } else {
                    order = Array(items.indices).shuffled()
                    if order.count > 1 && order[0] == currentIndex {
                        order.swapAt(0, 1)       // no immediate repeat across the lap boundary
                    }
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

    /// Smart shuffle: a fresh order of every OTHER song, with the current one first ("already played").
    private mutating func resetSmartShuffle(from current: Int) {
        if let smartOrder {
            let order = smartOrder(items, current)
            if order.count == items.count, order.first == current {
                shuffleOrder = order
                shufflePos = 0
                return
            }
        }
        let rest = items.indices.filter { $0 != current }.shuffled()
        shuffleOrder = [current] + rest
        shufflePos = 0
    }

    /// Rebuilds the smart-shuffle order from the current song (after `smartOrder` changed).
    mutating func reshuffle() {
        guard shuffleMode == .smart, !items.isEmpty else { return }
        pendingNextIndex = -1
        resetSmartShuffle(from: currentIndex)
    }

    // MARK: - Up Next

    /// The indices that will play next, in order (up to `limit`). nil in Random mode,
    /// where each next song is only picked when it's needed.
    func upcoming(limit: Int) -> [Int]? {
        switch shuffleMode {
        case .random:
            return nil
        case .smart:
            guard shufflePos >= 0 else { return [] }
            return Array(shuffleOrder.dropFirst(shufflePos + 1).prefix(limit))
        case .off:
            var result: [Int] = []
            var index = currentIndex
            while result.count < limit {
                index += 1
                if index >= items.count {
                    guard repeatMode == .all else { break }
                    index = 0
                }
                if index == currentIndex { break }
                result.append(index)
            }
            return result
        }
    }

    /// Makes `index` the current item (tapping a song in Up Next).
    mutating func jump(to index: Int) {
        guard items.indices.contains(index) else { return }
        pendingNextIndex = -1
        if shuffleMode == .smart {
            if let position = shuffleOrder.firstIndex(of: index), position > shufflePos {
                shufflePos = position
            } else {
                resetSmartShuffle(from: index)
            }
        }
        currentIndex = index
        history.append(index)
    }

    /// Removes an item that isn't the current one (swiping it away in Up Next).
    mutating func remove(at index: Int) {
        guard items.indices.contains(index), index != currentIndex else { return }
        items.remove(at: index)
        if currentIndex > index { currentIndex -= 1 }
        history = history.filter { $0 != index }.map { $0 > index ? $0 - 1 : $0 }
        if let position = shuffleOrder.firstIndex(of: index) {
            shuffleOrder.remove(at: position)
            if position <= shufflePos { shufflePos -= 1 }
        }
        shuffleOrder = shuffleOrder.map { $0 > index ? $0 - 1 : $0 }
        pendingNextIndex = -1
    }
}
