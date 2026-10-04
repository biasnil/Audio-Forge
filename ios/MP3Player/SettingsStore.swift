import Foundation
import Combine
import Security

nonisolated enum Appearance: String, Codable, CaseIterable, Identifiable, Sendable {
    case system = "System", light = "Light", dark = "Dark"
    var id: String { rawValue }
}

/// The tabs that can be hidden in Settings (Songs and Settings always stay).
nonisolated enum AppTab: String, Codable, CaseIterable, Identifiable, Sendable {
    case songs = "Songs", albums = "Albums", artists = "Artists", folders = "Folders"
    case playlists = "Playlists", equalizer = "Equalizer", wallpapers = "Wallpapers", settings = "Settings"

    var id: String { rawValue }
    var hideable: Bool { self != .songs && self != .settings }

    var systemImage: String {
        switch self {
        case .songs: "music.note"
        case .albums: "square.stack"
        case .artists: "music.mic"
        case .folders: "folder"
        case .playlists: "music.note.list"
        case .equalizer: "slider.vertical.3"
        case .wallpapers: "play.rectangle"
        case .settings: "gearshape"
        }
    }
}

/// One video assigned to a set of songs.
nonisolated struct WallpaperEntry: Identifiable, Codable, Hashable, Sendable {
    var id = UUID()
    /// File name inside Application Support/Wallpapers.
    var videoFile: String
    var songKeys: [String] = []
}

/// A folder outside the app (iCloud Drive, On My iPhone, ...) that's read in place.
nonisolated struct LinkedFolder: Identifiable, Codable, Hashable, Sendable {
    var id = UUID()
    var name: String
    var bookmark: Data
}

/// Everything the app remembers between launches (the desktop's AudioForge.ini).
nonisolated struct AppSettings: Codable, Equatable, Sendable {
    static let maxVolumePercent = 200
    static let crossfadeRange = 2...15

    var appearance: Appearance = .system
    var hiddenTabs: Set<AppTab> = []
    /// 0-200: above 100 boosts.
    var volumePercent = 100
    var replayGainEnabled = true
    var crossfadeEnabled = false
    var crossfadeSeconds = 5
    var eqEnabled = false
    var eqGainsDb: [Float] = Array(repeating: 0, count: EqualizerConfig.bandCount)
    var eqPostGainDb: Float = 0
    var videoWallpaperEnabled = true
    /// 0-100; 100 = fully visible.
    var videoWallpaperOpacityPercent = 100
    /// Used when the playing song has no wallpaper of its own; "" = none.
    var globalWallpaperFile = ""
    var wallpapers: [WallpaperEntry] = []
    var linkedFolders: [LinkedFolder] = []
    /// Folder keys (Song.folderKey) whose songs are hidden, subfolders included.
    var hiddenFolders: Set<String> = []

    init() {}

    // Every key is optional, so older or newer settings files still load.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let d = AppSettings()
        appearance = (try? c.decodeIfPresent(Appearance.self, forKey: .appearance)) ?? d.appearance
        hiddenTabs = (try? c.decodeIfPresent(Set<AppTab>.self, forKey: .hiddenTabs)) ?? d.hiddenTabs
        volumePercent = min(max((try? c.decodeIfPresent(Int.self, forKey: .volumePercent)) ?? d.volumePercent, 0),
                            Self.maxVolumePercent)
        replayGainEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .replayGainEnabled)) ?? d.replayGainEnabled
        crossfadeEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .crossfadeEnabled)) ?? d.crossfadeEnabled
        let seconds = (try? c.decodeIfPresent(Int.self, forKey: .crossfadeSeconds)) ?? d.crossfadeSeconds
        crossfadeSeconds = min(max(seconds, Self.crossfadeRange.lowerBound), Self.crossfadeRange.upperBound)
        eqEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .eqEnabled)) ?? d.eqEnabled
        let gains = (try? c.decodeIfPresent([Float].self, forKey: .eqGainsDb)) ?? d.eqGainsDb
        eqGainsDb = (0..<EqualizerConfig.bandCount).map { gains.indices.contains($0) ? gains[$0] : 0 }
        eqPostGainDb = (try? c.decodeIfPresent(Float.self, forKey: .eqPostGainDb)) ?? d.eqPostGainDb
        videoWallpaperEnabled = (try? c.decodeIfPresent(Bool.self, forKey: .videoWallpaperEnabled))
            ?? d.videoWallpaperEnabled
        videoWallpaperOpacityPercent = (try? c.decodeIfPresent(Int.self, forKey: .videoWallpaperOpacityPercent))
            ?? d.videoWallpaperOpacityPercent
        globalWallpaperFile = (try? c.decodeIfPresent(String.self, forKey: .globalWallpaperFile))
            ?? d.globalWallpaperFile
        wallpapers = (try? c.decodeIfPresent([WallpaperEntry].self, forKey: .wallpapers)) ?? d.wallpapers
        linkedFolders = (try? c.decodeIfPresent([LinkedFolder].self, forKey: .linkedFolders)) ?? d.linkedFolders
        hiddenFolders = (try? c.decodeIfPresent(Set<String>.self, forKey: .hiddenFolders)) ?? d.hiddenFolders
    }
}

/// Settings, kept in memory and saved to Application Support/settings.json on every change.
@MainActor
final class SettingsStore: ObservableObject {
    @Published private(set) var settings: AppSettings

    private let fileURL: URL

    init() {
        let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        fileURL = folder.appendingPathComponent("settings.json")
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode(AppSettings.self, from: data) {
            settings = decoded
        } else {
            settings = AppSettings()
        }
    }

    func update(_ transform: (inout AppSettings) -> Void) {
        var copy = settings
        transform(&copy)
        guard copy != settings else { return }
        settings = copy
        scheduleSave()
    }

    private var saveTask: Task<Void, Never>?

    /// Saves half a second after the last change, so dragging a slider doesn't write the
    /// file dozens of times a second (the latest value is what gets written).
    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            self?.saveNow()
        }
    }

    /// Writes immediately (also used when the app goes to the background).
    func saveNow() {
        saveTask?.cancel()
        saveTask = nil
        // Small file, written at most twice a second: fine on the main thread, and in order.
        do {
            let data = try JSONEncoder().encode(settings)
            try data.write(to: fileURL, options: .atomic)
        } catch {
            print("Could not save settings: \(error)")
        }
    }
}

/// Small Keychain wrapper for secrets (the Musixmatch API key).
nonisolated enum Keychain {
    private static let service = "AudioForge"

    static func read(_ account: String) -> String {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return "" }
        return String(data: data, encoding: .utf8) ?? ""
    }

    static func write(_ value: String, for account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        guard !value.isEmpty else { return }
        var item = query
        item[kSecValueData as String] = Data(value.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(item as CFDictionary, nil)
    }
}
