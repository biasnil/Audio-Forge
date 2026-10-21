#pragma once

#include <QWidget>
#include "audioforge/audio_engine.hpp"
#include "audioforge/playback_queue.hpp"
#include "audioforge/music_library.hpp"
#include "audioforge/wallpaper_library.hpp"
#include "audioforge/lyrics_provider.hpp"
#include "audioforge/discord_presence.hpp"
#include "audioforge/song_data.hpp"
#include "audioforge/smart_playlists.hpp"
#include "audioforge/theme.hpp"
#include "audioforge/visualizer_media.hpp"
#include <QFont>
#include <QPointer>
#include <functional>

class QTabWidget;
class QStackedWidget;
class QLineEdit;
class QTableWidget;
class QListWidget;
class QLabel;
class QSlider;
class QPushButton;
class QCheckBox;
class QComboBox;
class QTimer;
class QNetworkAccessManager;
class QNetworkReply;
class QCloseEvent;
class QEvent;
class QResizeEvent;
class QScrollArea;
class QAction;
class QDragEnterEvent;
class QDropEvent;
class QSystemTrayIcon;
class QAbstractButton;
class QProgressBar;
class QThread;
class QFrame;
class QVBoxLayout;
class QListWidgetItem;
class QMenu;

namespace audioforge {

class ManualTagDialog;
class VideoBackgroundWidget;
class UpNextPanel;
class UpdateChecker;
class VisualizerWidget;
class VisualizerStyleGrid;
class CardListWidget;
class WindowsMediaControls;

class PlayerWindow : public QWidget
{
    Q_OBJECT

public:
    explicit PlayerWindow(QWidget* parent = nullptr);
    ~PlayerWindow() override;

protected:
    void closeEvent(QCloseEvent* event) override;
    bool nativeEvent(const QByteArray& eventType, void* message, qintptr* result) override; // the taskbar buttons
    void dragEnterEvent(QDragEnterEvent* event) override;
    void dropEvent(QDropEvent* event) override; // music files play, folders join the library
    void changeEvent(QEvent* event) override; // pauses/resumes the video wallpaper on minimize/restore
    void resizeEvent(QResizeEvent* event) override; // re-applies title truncation for the new available width
    bool eventFilter(QObject* watched, QEvent* event) override; // hover-underline + click-to-seek on synced lyrics lines

private:
    // --- Tab / page builders ---------------------------------------------
    QWidget* buildAlbumsTab();
    QWidget* buildArtistsTab();
    QWidget* buildTracksTab();
    QWidget* buildFoldersTab();
    QWidget* buildPlaylistsTab();
    QWidget* buildSettingsTab();
    QWidget* buildWallpapersTab();
    QWidget* buildEqualizerTab();      // wraps buildEqualizerSection() in a QScrollArea, as its own top-level tab
    QWidget* buildEqualizerSection();
    void onEqEnabledToggled(bool checked);
    void onEqBandChanged(int bandIndex, int sliderValue);
    void onEqPostGainChanged(int sliderValue);
    void applyEqPreset(int index);
    void resetEqualizer();
    QWidget* buildSidebar();
    void setSidebarCompact(bool compact); // icon-only rail for small windows
    QWidget* buildPlayerBar();
    QWidget* buildNowPlayingPage();
    static QLabel* sectionHeader(const QString& text);
    void toggleFullScreen();

    // --- Theme / icons -----------------------------------------------------
    void applyTheme();          // palette + app stylesheet + every painted icon, for m_darkMode
    void refreshIcons();        // repaints icons in the current theme's colors
    void updatePlayPauseIcons(); // swaps play <-> pause glyphs when the engine state changes
    void updateVolumeIcons();   // speaker vs. muted glyph
    void showCoverPlaceholders(); // music-note glyph in both cover slots when a track has no art
    void onTabChanged(int index); // keeps the sidebar selection + page title in sync with m_tabs

    // --- Folders / Tracks / Albums / Artists ------------------------------
    void addFolder();
    void removeFolder(const QString& dir);
    void refreshLibrary(); // starts a background scan (see startLibraryScan)
    // Scans the library folders on a worker thread (tags of new/changed files
    // only), with a progress bar; the UI stays usable meanwhile. A request
    // while one is running queues one more scan afterwards.
    void startLibraryScan();
    void onLibraryScanProgress(int done, int total);
    void onLibraryScanFinished(const QVector<TrackInfo>& tracks);
    void refreshFoldersList();
    void refreshTracksTable();
    void fitTracksColumnsOnce(); // first run: the columns share the list's width

    void refreshAlbumsAndArtists();
    void applySearchFilter(const QString& text);
    void openGroupTracks(const QString& title, const QStringList& paths);

    // --- MusicBrainz tagger -------------------------------------------
    // These act on `path`, or (empty) the Tracks list's selected row.
    void openManualTagDialog(QString path = QString());
    void openChangeCoverDialog(QString path = QString());
    QString selectedTrackPath() const;
    void saveBeforeExit();          // queue, settings, play counts... once, from closeEvent or aboutToQuit
    void setupWindowsIntegration(); // media controls, taskbar buttons, tray icon
    void showFromTray();
    void notifySongStarted(const TrackInfo& info, const QByteArray& coverArt);
    void deletePlaylistAt(int index);                                      // asks first
    void addToPlaylistMenu(QMenu& menu, const QVector<TrackInfo>& tracks); // "Add to Playlist" > ... / New Playlist...
    void showNowPlayingMenu(const QPoint& globalPos);                      // right-click on Now Playing
    // `targetDialog` non-null = "Fill from internet" inside that manual tag
    // dialog: the chosen result goes into its fields, never straight to disk.
    void lookupSelectedTrackOnMusicBrainz(ManualTagDialog* targetDialog = nullptr, QString path = QString());
    void handleMusicBrainzReply(QNetworkReply* reply, const QString& path,
                                bool forManualDialog, QPointer<ManualTagDialog> targetDialog);

    // Runs `write` (a tag/cover write to `path`) with the file released by
    // the audio engine if it's the playing (or crossfading-in) track, then
    // reloads it at the same position -- rewriting a file miniaudio is
    // still streaming from can corrupt playback, or fail outright on Windows.
    bool writeTrackFile(const QString& path, const std::function<bool()>& write);
    void afterTrackFileWritten(const QString& path); // refreshes library views + Now Playing

    // --- Playlists ---------------------------------------------------------
    void openCreatePlaylistDialog();
    void refreshPlaylistsList();
    void openPlaylistTracks(int playlistIndex);
    // New Playlist / New Smart Playlist / Import .m3u, below `anchor` (or at `at`, in its coordinates).
    void showNewPlaylistMenu(QWidget* anchor, QPoint at = QPoint(-1, -1));
    QPixmap playlistArt(const PlaylistData& playlist, int size); // 2x2 cover mosaic, or generated art
    void activatePlaylistItem(QListWidgetItem* item); // a row of either playlist list: open it

    // --- Smart playlists (built-in ones first, then the user's) -------------
    const SmartPlaylist* smartPlaylist(const QString& id) const;
    QVector<TrackInfo> smartPlaylistTracks(const SmartPlaylist& playlist) const;
    void openSmartPlaylist(const QString& id);
    void editSmartPlaylist(const QString& id); // empty id = a new one
    void deleteSmartPlaylist(const QString& id);

    // --- .m3u ------------------------------------------------------------------
    void importM3u();
    void exportM3u(const QString& name, const QVector<TrackInfo>& tracks);

    // --- Lyrics timing offset (per song, ±0.5 s steps) -------------------------
    void setLyricsOffset(int offsetMs);
    void updateLyricsOffsetUi();
    void updateLyricsPadding();
    void updateAmbientBackground(const QByteArray& coverArt, const QString& seed); // Now Playing's backdrop // the padding rows: half the lyrics list's height

    // --- Visualizer (in place of the Now Playing cover) ----------------------
    void setVisualizerOn(bool on);
    void updateVisualizerForTrack(const TrackInfo& info, const QByteArray& coverArt);
    void updateVisualizerActive(); // runs only while it's on screen
    void openVisualizerMediaDialog();

    // --- Genre detection ----------------------------------------------------------
    void startGenreDetection();
    void updateGenreProgressUi();

    // --- Podcasts (folders marked Podcast; kept apart from the music) -----------
    QWidget* buildPodcastsTab();
    void setFolderIsPodcast(const QString& dir, bool podcast);
    void refreshPodcasts();               // the shows list, and the open show's episodes
    void openPodcastShow(const QString& name);
    void showPodcastEpisodeMenu(const QPoint& pos);
    bool isPodcast(const QString& path) const { return m_library.isPodcast(path); }

    // --- A-B repeat (Now Playing) ---------------------------------------------------
    void cycleAbRepeat();   // off -> A set -> looping A to B -> off
    void clearAbRepeat();
    void updateAbButton();

    // --- Queue / transport ---------------------------------------------
    // `context` names where playback started ("Tracks", an album, a
    // playlist...) -- shown as "Next From <context>" in Up Next.
    void setQueueAndPlay(const QVector<TrackInfo>& queue, int startIndex, const QString& context = QString());

    // --- Up Next (Play Next / Add to Queue) --------------------------------
    void playNextTracks(const QVector<TrackInfo>& tracks);
    void addTracksToQueue(const QVector<TrackInfo>& tracks);
    void settleHandOff(); // before what plays next changes: finish an audible crossfade, drop an unheard one
    void setUpNextVisible(bool visible);
    void refreshUpNext(); // no-op while the panel is hidden
    void saveQueueAsPlaylist();
    void showToast(const QString& message);
    void recordHistory(const TrackInfo& track); // each track that starts playing, for Up Next's History
    void saveQueueState();    // queue.json next to AudioForge.ini, on exit
    void restoreQueueState(); // ...and back on launch, paused where it was
    void playTracksTableRow(int row); // the visible rows as the queue, starting at `row`
    QVector<TrackInfo> selectedTracks() const; // Tracks table selection, in on-screen order
    void showTrackContextMenu(const QPoint& pos);
    // Right-click menu for anything that stands for a list of tracks (album,
    // artist, playlist); `extra` adds a playlist's own actions at the end.
    void showTracksMenu(const QPoint& globalPos, const QStringList& paths, const QString& name,
                        const std::function<void(QMenu&)>& extra = {});
    void enableTracksMenu(QListWidget* list, bool playlistRows); // hooks showTracksMenu up to a list's right-click
    void next(bool fromAutoAdvance);
    void previous();
    void cycleRepeatMode();
    void cycleShuffleMode();
    void updateShuffleRepeatLabels(); // keeps the mini bar AND Now Playing page shuffle/repeat buttons in sync
    void toggleReplayGain();

    // --- Lyrics (cache, then LRCLIB, then Musixmatch, then local .lrc/.txt, then nothing) ---
    void fetchLyricsFor(const TrackInfo& info);
    void fetchFromLrclib(const TrackInfo& info, const QString& path);
    void fetchFromMusixmatch(const TrackInfo& info, const QString& path);
    void applyFallbackOrClearLyrics(const QString& path);
    void setLyricsContent(const QString& rawText, bool synced); // populates m_lyricsList, and m_syncedLyrics if synced
    void setLyricsPlaceholder(const QString& text); // "Loading lyrics..." / "No lyrics found."
    void updateSyncedLyricsHighlight(float cursorSeconds); // called every tick while m_syncedLyrics isn't empty
    void resetTitleMarquee(const QString& title); // called once per track load -- decides if scrolling is needed at all
    void applyElidedTitleText(); // sets m_bigTitleLabel to the truncated "..." form, sized to the current viewport
    void applyElidedMiniTitle(); // same idea for the mini player bar's m_titleLabel -- static, no marquee

    // --- Live video wallpaper ---------------------------------------------
    void updateWallpaperForTrack(const TrackInfo& info); // called from the end of updateNowPlayingUi(), same hook fetchLyricsFor() uses
    void refreshGlobalWallpaperLabel();
    void chooseGlobalWallpaper();
    void clearGlobalWallpaper();
    void refreshWallpaperEntriesList();
    void addWallpaperEntry();
    void editWallpaperEntryTracks();
    void removeSelectedWallpaperEntry();

    // --- Playback ----------------------------------------------------------
    void setupShortcuts();
    void togglePlayPause();
    void seekBySeconds(float deltaSeconds);
    void seekTo(float seconds); // every seek goes through here -- cancels an in-progress crossfade first
    void updateDiscordPresence(); // re-sends title + elapsed time; no-op unless enabled and playing
    void adjustVolume(int delta);
    void toggleMute();
    void setControlsEnabled(bool enabled);
    void openFile();
    // Hard cut via AudioEngine + UI update. autoplay = false loads it paused;
    // startAt >= 0 starts there (instead of a long track's resume point / Smart
    // Transitions' trimmed start).
    void loadTrack(const TrackInfo& info, bool autoplay = true, float startAt = -1.0f);
    void updateNowPlayingUi(const TrackInfo& info);
    void play();
    void pause();
    void stop();
    void applyVolume();
    void toggleTheme();
    void seekToSliderValue();

    // --- Crossfade / hand-off ----------------------------------------------
    // Ported from the iOS PlayerManager: near the end of a track the next one
    // is lined up -- a crossfade (optionally beat-matched), or with crossfade
    // off (or same-album tracks under Smart Transitions) a gapless hand-off
    // scheduled on the exact sample the current one ends.
    void abortCrossfade(); // clears both AudioEngine's and PlaybackQueue's crossfade-in-progress state
    void prepareHandOff(float cursor, float end);
    void startHandOff(bool gapless, float fadeLength);
    void updateCrossfadeProgress(float cursor, float end);
    float baseVolumeFor(float replayGainDb) const;
    void finalizeCrossfade();
    const SongAnalysis* analysisOf(const TrackInfo& track) const; // null unless analysis has unlocked Smart Options
    float trackBegin(const TrackInfo& track) const; // Smart Transitions: where the audio starts (silence skipped)
    float trackEnd(const TrackInfo& track, float length) const; // ...and where it ends

    // --- Settings: analysis, smart options, theme ---------------------------
    void updateLibraryStatsUi();  // stat rows + the Analyze row
    void updateAnalysisProgressUi(); // just the Analyze row ("N of M songs analyzed" / progress)
    void updateSmartOptionsUi();  // locks/unlocks the Smart Options switches
    void startAnalysis();
    void onAnalysisFinished(bool completed);
    void updateSmartOrder();      // Harmonic Shuffle on/off -> the queue's smart-shuffle order
    void openDuplicates();
    void setThemeMode(int mode);  // 0 = System, 1 = Light, 2 = Dark

    // --- Settings: language, About ------------------------------------------
    void onLanguageChosen(int index); // saves it, then offers to restart (the language is set at startup)
    void restartApp();                // closes (saving everything, like quitting) and starts again
    void updateVersionUi();           // About > Version: the update check's state
    void showUpdateNotice(const QString& version, const QString& url); // the automatic check found one
    void reportProblem();             // an email with the app and Windows versions filled in
    void openTextPage(const QString& title, const QString& markdown);
    QString acknowledgementsMarkdown() const;

    // --- Sleep timer / long tracks -------------------------------------------
    void openSleepTimerMenu();
    void setSleepTimer(int minutes); // 0 = off, -1 = end of the current track
    void updateSleepTimer(float cursor, float end); // per tick: fires the timer, fades the volume out
    void rememberLongTrackPosition(); // saves (or clears) where a 10 min+ track was left

    // --- Loved / play counts -------------------------------------------------
    void countPlayIfDue(float cursor, float length); // once half the song (at most 4 min) has been heard
    void toggleLoved();      // Now Playing's heart
    void updateLoveButton();

    // --- Main update tick ----------------------------------------------
    void updatePlayback();

    // --- Settings persistence (%APPDATA%\AudioForge\AudioForge.ini) -----
    void loadSettings();
    void saveSettings();

private:
    AudioEngine m_engine;
    PlaybackQueue m_queue;
    MusicLibrary m_library;
    WallpaperLibrary m_wallpaperLibrary;
    DiscordPresence m_discordPresence;

    bool m_seeking = false;
    bool m_wasAtEnd = false;
    bool m_darkMode = true;
    bool m_replayGainEnabled = true;
    bool m_videoWallpaperEnabled = true; // persisted via Settings tab's m_videoWallpaperCheckbox
    float m_currentReplayGainDb = 0.0f;

    bool m_crossfadeEnabled = false;
    int m_crossfadeSeconds = 5;
    bool m_gaplessHandOff = false;  // the in-progress "crossfade" is a gapless hand-off (no fade)
    float m_fadeLength = 5.0f;      // this crossfade's length (Smart Transitions can stretch it to the outro)
    bool m_handOffBlocked = false;  // the next track failed to preload -- just advance at the end

    // Settings mirrored from the iOS app.
    int m_themeMode = 2;            // 0 = System, 1 = Light, 2 = Dark
    bool m_analysisCompleted = false; // an analysis run finished once -- unlocks Smart Options
    bool m_smartTransitions = false;
    bool m_beatMatchedCrossfade = false;
    bool m_harmonicShuffle = false;
    bool m_sleepFadeOut = true;
    bool m_rememberLongTracks = true;

    int m_sleepMinutes = 0;          // 0 = off, -1 = end of track, else minutes
    qint64 m_sleepDeadlineMs = 0;    // when a minutes timer fires (ms since epoch)
    float m_sleepVolumeFactor = 1.0f; // the sleep fade-out, folded into baseVolumeFor()
    int m_tickCount = 0;

    SongDataStore* m_songData = nullptr;
    LibraryAnalyzer* m_analyzer = nullptr;
    GenreAnalyzer* m_genreAnalyzer = nullptr;
    QPushButton* m_genreButton = nullptr;      // Settings > Library > Detect Genres
    QPushButton* m_genreStopButton = nullptr;
    QProgressBar* m_genreProgress = nullptr;
    QLabel* m_genreStatusLabel = nullptr;

    QVector<SmartPlaylist> m_smartPlaylists; // the user's own (the built-in ones aren't stored)

    int m_lyricsOffsetMs = 0;              // the playing song's, + shows the lines later
    QWidget* m_lyricsOffsetRow = nullptr;  // the lyrics timing button, shown for synced lyrics only
    QPushButton* m_lyricsTimingButton = nullptr; // "Lyrics timing: +0.5 s" -> Earlier / Later / Reset
    bool m_lyricsCentered = false; // synced lyrics or the "No lyrics" note: the line in the middle
    int m_lyricsLead = 0; // padding rows before the first lyric line (synced lyrics: 1, so any line can sit in the middle)

    QStackedWidget* m_podcastsStack = nullptr;  // the shows (0) or one show's episodes (1)
    QListWidget* m_podcastShowsList = nullptr;
    QLabel* m_podcastsEmptyLabel = nullptr;
    QListWidget* m_podcastEpisodesList = nullptr;
    QLabel* m_podcastShowTitle = nullptr;
    QPushButton* m_podcastBackButton = nullptr;
    QString m_openPodcastShow;                  // the show whose episodes are shown, if any

    enum class AbState { Off, ASet, Looping };
    AbState m_abState = AbState::Off;
    float m_abA = 0.0f; // seconds
    float m_abB = 0.0f;
    QPushButton* m_abButton = nullptr;

    VisualizerWidget* m_visualizer = nullptr;
    QStackedWidget* m_coverStack = nullptr;   // the cover (0) or the visualizer (1)
    QPushButton* m_visualizerButton = nullptr;
    bool m_visualizerOn = false;
    int m_visualizerStyle = 0;
    VisualizerStyleGrid* m_visualizerStyleGrid = nullptr;
    VisualizerMediaLibrary m_visualizerMedia;

    QThread* m_scanThread = nullptr;
    std::shared_ptr<std::atomic<bool>> m_scanCancel;
    bool m_scanPending = false;
    QWidget* m_scanBar = nullptr;       // "Loading library... N of M" under the title bar
    QLabel* m_scanLabel = nullptr;
    QProgressBar* m_scanProgress = nullptr;
    QPushButton* m_refreshLibraryButton = nullptr;

    QString m_queueContext;            // "Next From ..." in Up Next
    QVector<TrackInfo> m_playHistory;  // played tracks, oldest first, current last (capped)
    UpNextPanel* m_upNextPanel = nullptr;
    QPushButton* m_queueButton = nullptr;    // player bar: shows/hides Up Next
    QPushButton* m_bigQueueButton = nullptr; // Now Playing: back to the library with Up Next open
    QLabel* m_toast = nullptr;               // "X plays next." confirmation
    QTimer* m_toastTimer = nullptr;
    int m_videoWallpaperOpacityPercent = 100; // 0-100; 100 = fully visible (current behavior), lower fades the video
    int m_volumeBeforeMute = 100;

    QString m_musixmatchApiKey;
    QLineEdit* m_musixmatchApiKeyEdit = nullptr;
    bool m_discordPresenceEnabled = false;
    QString m_discordClientId;
    QAbstractButton* m_discordPresenceCheckbox = nullptr;
    QLineEdit* m_discordClientIdEdit = nullptr;
    QListWidget* m_lyricsList = nullptr;
    QFont m_lyricsBaseFont;
    QVector<SyncedLyricLine> m_syncedLyrics; // empty unless the current lyrics are time-synced
    int m_lastLyricsLineIndex = -1; // avoids re-styling every item on every 200ms tick when nothing changed
    int m_hoveredLyricsLineIndex = -1; // which synced line the mouse is over right now (-1 = none); only ever set for lines that have a timestamp

    QNetworkAccessManager* m_network = nullptr;
    bool m_musicBrainzLookupInFlight = false; // one lookup at a time (MusicBrainz rate-limits to ~1 req/s anyway)

    QTabWidget* m_tabs = nullptr; // tab bar hidden -- the sidebar's nav buttons switch pages instead
    QVector<QPushButton*> m_navButtons; // indexed by tab index, same as m_tabs
    QFrame* m_sidebar = nullptr;
    QVBoxLayout* m_sidebarLayout = nullptr;
    int m_sidebarSpacerIndex = -1;
    QLabel* m_brandLabel = nullptr;
    QWidget* m_playlistsSection = nullptr;
    bool m_sidebarCompact = false;
    QLabel* m_pageTitleLabel = nullptr;
    QListWidget* m_sidebarPlaylistsList = nullptr;
    QPushButton* m_newPlaylistButton = nullptr;
    QAction* m_searchIconAction = nullptr;
    QStackedWidget* m_stack = nullptr;
    VideoBackgroundWidget* m_wallpaperWidget = nullptr;
    QWidget* m_nowPlayingPage = nullptr;
    QLabel* m_ambientBackground = nullptr; // Now Playing's backdrop: the cover blurred and darkened, or generated art
    QLineEdit* m_searchBar = nullptr;
    QTableWidget* m_tracksTable = nullptr;
    QPushButton* m_musicBrainzButton = nullptr;
    QPushButton* m_changeCoverButton = nullptr;
    QListWidget* m_albumsList = nullptr;
    QListWidget* m_artistsList = nullptr;
    QListWidget* m_foldersList = nullptr;
    CardListWidget* m_playlistsList = nullptr;      // Playlists tab: the user's playlists (cards)
    CardListWidget* m_smartPlaylistsList = nullptr; // ...and the smart ones
    QHash<QString, QPixmap> m_playlistArtCache;
    WindowsMediaControls* m_windowsMedia = nullptr;
    QSystemTrayIcon* m_tray = nullptr;
    QAction* m_trayPlayAction = nullptr;
    QAbstractButton* m_trayKeepSwitch = nullptr;
    QAbstractButton* m_notifySwitch = nullptr;
    bool m_keepPlayingInTray = false; // closing the window hides it to the tray
    bool m_songNotifications = false;
    bool m_trayHintShown = false;
    bool m_quitting = false;      // the tray's Quit: really close
    bool m_savedForExit = false;
    bool m_startupSettled = false; // no notification for the song restored at start
    QString m_lastNotifiedPath;
    bool m_tracksColumnsFitted = false; // the Tracks list has widths (the user's, or fitted once)  // cover mosaics, by the paths they're made from
    QLabel* m_globalWallpaperLabel = nullptr;
    QListWidget* m_wallpaperEntriesList = nullptr;
    QVector<QAbstractButton*> m_tabVisibilityCheckboxes; // Settings > Visible Tabs, indexed by tab index (0-4)

    QVector<QLabel*> m_statValueLabels; // Songs, Albums, Artists, Genres
    QPushButton* m_analyzeButton = nullptr;
    QLabel* m_analyzeStatusLabel = nullptr;
    QProgressBar* m_analyzeProgress = nullptr;
    QPushButton* m_analyzeStopButton = nullptr;
    QWidget* m_smartLockRow = nullptr;
    QAbstractButton* m_smartTransitionsSwitch = nullptr;
    QAbstractButton* m_beatMatchSwitch = nullptr;
    QAbstractButton* m_harmonicShuffleSwitch = nullptr;
    QVector<QPair<QLabel*, theme::Icon>> m_settingsIcons; // row icons, repainted on theme change
    QComboBox* m_themeCombo = nullptr;
    QComboBox* m_languageCombo = nullptr; // item data = the language code
    UpdateChecker* m_updateChecker = nullptr;
    QPushButton* m_versionButton = nullptr;      // About > Version: click to check for updates
    QLabel* m_versionStatusLabel = nullptr;      // "Checking..." / "You have the latest version." / ...
    QPushButton* m_downloadUpdateButton = nullptr; // shown while a newer version is available
    QAbstractButton* m_autoUpdateSwitch = nullptr;
    QAbstractButton* m_replayGainSwitch = nullptr;
    QAbstractButton* m_sleepFadeSwitch = nullptr;
    QAbstractButton* m_resumeLongSwitch = nullptr;
    QPushButton* m_sleepButton = nullptr; // Now Playing: sleep timer menu
    QPushButton* m_loveButton = nullptr;  // Now Playing: Loved heart
    QString m_countedPath;                // the playing track, once its play has been counted

    QAbstractButton* m_crossfadeCheckbox = nullptr;
    QLabel* m_crossfadeLabel = nullptr;
    QSlider* m_crossfadeSlider = nullptr;
    QAbstractButton* m_videoWallpaperCheckbox = nullptr;
    QSlider* m_videoWallpaperOpacitySlider = nullptr;

    QCheckBox* m_eqEnabledCheckbox = nullptr;
    QComboBox* m_eqPresetCombo = nullptr;
    QVector<QSlider*> m_eqBandSliders;   // vertical, one per AudioEngine::kEqBandCount, range -150..150 (tenths of a dB)
    QVector<QLabel*> m_eqBandValueLabels;
    QSlider* m_eqPostGainSlider = nullptr; // same -150..150 tenths-of-a-dB range
    QLabel* m_eqPostGainValueLabel = nullptr;
    float m_eqPostGainDb = 0.0f;

    QLabel* m_coverArtLabel = nullptr;
    QLabel* m_titleLabel = nullptr;
    QLabel* m_formatLabel = nullptr;
    QSlider* m_seekSlider = nullptr;
    QLabel* m_timeLabel = nullptr;     // elapsed, left of the seek bar
    QLabel* m_durationLabel = nullptr; // total, right of the seek bar

    QLabel* m_bigCoverArtLabel = nullptr;
    bool m_coverPlaceholderShown = true; // so a theme switch knows whether to repaint the placeholder
    QLabel* m_bigTitleLabel = nullptr;
    QScrollArea* m_bigTitleScrollArea = nullptr; // clips m_bigTitleLabel; the marquee below scrolls it when the title doesn't fit
    QTimer* m_titleMarqueeTimer = nullptr;
    QString m_titleMarqueeFullText; // untruncated title -- label alternates between this and an elided version
    QString m_miniTitleFullText; // untruncated title for the mini player bar's m_titleLabel
    bool m_titleMarqueeNeeded = false; // false means the title already fits -- stays static, never scrolls
    int m_titleMarqueePhase = 0; // 0 = paused showing elided text, 1 = scrolling to reveal, 2 = paused showing full text
    int m_titleMarqueeTicksRemaining = 0; // countdown used by phases 0 and 2
    QLabel* m_bigSubtitleLabel = nullptr;
    QLabel* m_bigFormatLabel = nullptr;
    QSlider* m_bigSeekSlider = nullptr;
    QLabel* m_bigTimeLabel = nullptr;
    QLabel* m_bigDurationLabel = nullptr;
    QPushButton* m_shuffleButton = nullptr;
    QPushButton* m_bigShuffleButton = nullptr; // Now Playing page copy of the shuffle button
    QPushButton* m_bigRepeatButton = nullptr;  // Now Playing page copy of the repeat button
    QPushButton* m_bigPrevButton = nullptr;
    QPushButton* m_bigPlayButton = nullptr;
    QPushButton* m_bigNextButton = nullptr;
    QPushButton* m_backButton = nullptr;
    QPushButton* m_fullscreenButton = nullptr;
    QPushButton* m_volumeButton = nullptr;    // speaker icon -- click to mute/unmute
    QPushButton* m_bigVolumeButton = nullptr;
    QPushButton* m_prevButton = nullptr;
    QPushButton* m_playButton = nullptr; // play/pause toggle -- icon follows the engine state
    int m_shownPlayingState = -1;        // what the play buttons currently show (-1 = not set yet)
    QPushButton* m_stopButton = nullptr;
    QPushButton* m_nextButton = nullptr;
    QPushButton* m_repeatButton = nullptr;
    QSlider* m_bigVolumeSlider = nullptr;
    QPushButton* m_openButton = nullptr;
    QPushButton* m_replayGainButton = nullptr;
    QPushButton* m_themeButton = nullptr;
    QSlider* m_volumeSlider = nullptr;
    QTimer* m_updateTimer = nullptr;
};

} // namespace audioforge