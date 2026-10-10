import CarPlay
import Combine

/// The CarPlay screens: a small menu (Now Playing, Lyrics), the system Now Playing screen with a
/// Lyrics button, and a lyrics list. Synced lyrics follow the song (the line being sung is marked,
/// tap a line to jump to it); plain lyrics show from the top.
///
/// CarPlay has no lyrics view of its own, so the list is built from list items, which the car
/// limits in number and cuts to one line each. Needs the CarPlay audio entitlement and a CarPlay
/// scene in Info.plist (see README).
@objc(CarPlaySceneDelegate)
final class CarPlaySceneDelegate: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interfaceController: CPInterfaceController?
    private let lyricsTemplate = CPListTemplate(title: "Lyrics", sections: [])
    private var cancellables = Set<AnyCancellable>()
    /// The synced line marked as playing, so the list is only rebuilt when it changes.
    private var shownLine: Int?

    private var player: PlayerManager { AppObjects.player }

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didConnect interfaceController: CPInterfaceController) {
        self.interfaceController = interfaceController
        player.beginLyricsDemand()          // ready before the Lyrics screen is opened

        if let icon = UIImage(systemName: "quote.bubble") {
            let lyricsButton = CPNowPlayingImageButton(image: icon) { [weak self] _ in
                MainActor.assumeIsolated { self?.showLyrics() }
            }
            CPNowPlayingTemplate.shared.updateNowPlayingButtons([lyricsButton])
        }
        interfaceController.setRootTemplate(makeMenu(), animated: false, completion: nil)
        observePlayer()

        // Started from the car with the phone locked: load the library and the last song,
        // as the phone screen does on launch.
        let library = AppObjects.library
        Task {
            await library.reloadIfStale()
            AppObjects.player.restoreIfNeeded(from: library.songs)
        }
    }

    func templateApplicationScene(_ templateApplicationScene: CPTemplateApplicationScene,
                                  didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        self.interfaceController = nil
        cancellables.removeAll()
        player.endLyricsDemand()
    }

    // MARK: - Screens

    private func makeMenu() -> CPListTemplate {
        let nowPlaying = CPListItem(text: "Now Playing", detailText: nil, image: UIImage(systemName: "play.circle"))
        nowPlaying.handler = { [weak self] _, completion in
            MainActor.assumeIsolated { self?.show(CPNowPlayingTemplate.shared) }
            completion()
        }
        let lyrics = CPListItem(text: "Lyrics", detailText: nil, image: UIImage(systemName: "quote.bubble"))
        lyrics.handler = { [weak self] _, completion in
            MainActor.assumeIsolated { self?.showLyrics() }
            completion()
        }
        return CPListTemplate(title: "AudioForge", sections: [CPListSection(items: [nowPlaying, lyrics])])
    }

    private func showLyrics() {
        render(player.lyrics, at: player.currentTime)
        show(lyricsTemplate)
    }

    /// Pushes `template`, or goes back to it if it's already open.
    private func show(_ template: CPTemplate) {
        guard let controller = interfaceController else { return }
        if controller.templates.contains(where: { $0 === template }) {
            controller.pop(to: template, animated: true, completion: nil)
        } else {
            controller.pushTemplate(template, animated: true, completion: nil)
        }
    }

    // MARK: - Lyrics list

    private func observePlayer() {
        // @Published sends the new value before the property changes: use the value it sends.
        player.$lyrics
            .sink { [weak self] lyrics in
                guard let self else { return }
                self.render(lyrics, at: self.player.currentTime)
            }
            .store(in: &cancellables)
        player.clock.$time
            .sink { [weak self] time in
                guard let self, case .synced(let lines) = self.player.lyrics,
                      lines.currentIndex(at: time) != self.shownLine else { return }
                self.render(self.player.lyrics, at: time)
            }
            .store(in: &cancellables)
    }

    /// Fills the list: synced lyrics from the line before the one being sung, plain lyrics
    /// from the top (two lines per row, as many rows as the car allows).
    private func render(_ lyrics: LyricsContent, at time: TimeInterval) {
        let maxItems = max(CPListTemplate.maximumItemCount, 1)
        var items: [CPListItem] = []
        shownLine = nil

        switch lyrics {
        case .synced(let lines):
            let current = lines.currentIndex(at: time)
            shownLine = current
            let start = max((current ?? 0) - 1, 0)
            for index in lines.indices.dropFirst(start).prefix(maxItems) {
                let line = lines[index]
                let item = CPListItem(text: line.text.isEmpty ? "♪" : line.text, detailText: nil)
                item.isPlaying = index == current
                item.handler = { [weak self] _, completion in
                    MainActor.assumeIsolated { self?.player.seek(to: line.time) }
                    completion()
                }
                items.append(item)
            }
        case .plain(let lines):
            for pair in stride(from: 0, to: lines.count, by: 2).prefix(maxItems) {
                items.append(CPListItem(text: lines[pair],
                                        detailText: pair + 1 < lines.count ? lines[pair + 1] : nil))
            }
        default:
            break
        }

        lyricsTemplate.emptyViewTitleVariants = [emptyTitle(for: lyrics)]
        lyricsTemplate.updateSections(items.isEmpty ? [] : [CPListSection(items: items)])
    }

    private func emptyTitle(for lyrics: LyricsContent) -> String {
        if player.currentSong == nil { return "Nothing Playing" }
        switch lyrics {
        case .loading: return "Looking for Lyrics…"
        default: return "No Lyrics Found"
        }
    }
}
