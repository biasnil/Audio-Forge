import AppIntents

/// The widget's buttons. They're audio playback intents, so iOS runs them inside the app
/// (starting it in the background if needed), where they control the real player.
/// The widget is built with these too (WIDGET_EXTENSION is set there) but never runs them.
nonisolated enum PlaybackCommand: Sendable {
    case playPause, next, previous
}

nonisolated struct PlayPauseIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play or Pause"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await runPlaybackCommand(.playPause)
        return .result()
    }
}

nonisolated struct NextTrackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Next Song"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await runPlaybackCommand(.next)
        return .result()
    }
}

nonisolated struct PreviousTrackIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Previous Song"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await runPlaybackCommand(.previous)
        return .result()
    }
}

nonisolated private func runPlaybackCommand(_ command: PlaybackCommand) async {
#if !WIDGET_EXTENSION
    await WidgetBridge.perform(command)
#endif
}
