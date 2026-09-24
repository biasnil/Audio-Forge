#pragma once

#include <QWidget>
#include "audioforge/audio_engine.hpp"
#include "audioforge/playback_queue.hpp"
#include "audioforge/music_library.hpp"
#include "audioforge/wallpaper_library.hpp"
#include "audioforge/lyrics_provider.hpp"
#include "audioforge/discord_presence.hpp"
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

namespace audioforge {

class ManualTagDialog;
class VideoBackgroundWidget;

class PlayerWindow : public QWidget
{
    Q_OBJECT

public:
    explicit PlayerWindow(QWidget* parent = nullptr);
    ~PlayerWindow() override;

protected:
    void closeEvent(QCloseEvent* event) override;
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
    void positionEqValueLabel(QSlider* slider, QLabel* label); // floats a band's dB label right above its slider handle
    void onEqEnabledToggled(bool checked);
    void onEqBandChanged(int bandIndex, int sliderValue);
    void onEqPostGainChanged(int sliderValue);
    void applyEqPreset(int index);
    void resetEqualizer();
    QWidget* buildPlayerBar();
    QWidget* buildNowPlayingPage();
    static QLabel* sectionHeader(const QString& text);
    void toggleFullScreen();

    // --- Folders / Tracks / Albums / Artists ------------------------------
    void addFolder();
    void removeFolder(const QString& dir);
    void refreshLibrary();
    void refreshFoldersList();
    void refreshTracksTable();
    void refreshAlbumsAndArtists();
    void updateStats();
    void applySearchFilter(const QString& text);
    void openGroupTracks(const QString& labelWithCount, const QStringList& paths);

    // --- MusicBrainz tagger -------------------------------------------
    void openTagMusicMenu(); // "Tag Music" button -- offers MusicBrainz vs Manual tagging
    void openManualTagDialog();
    void openChangeCoverDialog();
    // `targetDialog` non-null = "Fill from internet" inside that manual tag
    // dialog: the chosen result goes into its fields, never straight to disk.
    void lookupSelectedTrackOnMusicBrainz(ManualTagDialog* targetDialog = nullptr);
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

    // --- Queue / transport ---------------------------------------------
    void setQueueAndPlay(const QVector<TrackInfo>& queue, int startIndex);
    void next(bool fromAutoAdvance);
    void previous();
    void cycleRepeatMode();
    void cycleShuffleMode();
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
    void loadTrack(const TrackInfo& info); // hard cut via AudioEngine + UI update
    void updateNowPlayingUi(const TrackInfo& info);
    void play();
    void pause();
    void stop();
    void applyVolume();
    void toggleTheme();
    void seekToSliderValue();

    // --- Crossfade ---------------------------------------------------------
    void abortCrossfade(); // clears both AudioEngine's and PlaybackQueue's crossfade-in-progress state
    void tryStartCrossfade();
    void updateCrossfadeProgress(float cursor, float length);
    float baseVolumeFor(float replayGainDb) const;
    void finalizeCrossfade();

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
    int m_videoWallpaperOpacityPercent = 100; // 0-100; 100 = fully visible (current behavior), lower fades the video
    int m_volumeBeforeMute = 100;

    QString m_musixmatchApiKey;
    QLineEdit* m_musixmatchApiKeyEdit = nullptr;
    bool m_discordPresenceEnabled = false;
    QString m_discordClientId;
    QCheckBox* m_discordPresenceCheckbox = nullptr;
    QLineEdit* m_discordClientIdEdit = nullptr;
    QListWidget* m_lyricsList = nullptr;
    QFont m_lyricsBaseFont;
    QVector<SyncedLyricLine> m_syncedLyrics; // empty unless the current lyrics are time-synced
    int m_lastLyricsLineIndex = -1; // avoids re-styling every item on every 200ms tick when nothing changed
    int m_hoveredLyricsLineIndex = -1; // which synced line the mouse is over right now (-1 = none); only ever set for lines that have a timestamp

    QNetworkAccessManager* m_network = nullptr;
    bool m_musicBrainzLookupInFlight = false; // one lookup at a time (MusicBrainz rate-limits to ~1 req/s anyway)

    QTabWidget* m_tabs = nullptr;
    QStackedWidget* m_stack = nullptr;
    VideoBackgroundWidget* m_wallpaperWidget = nullptr;
    QWidget* m_nowPlayingPage = nullptr;
    QWidget* m_infoBox = nullptr; // bounded panel (title through lyrics) that gets the per-track cover-art tint now, not the whole page
    QLineEdit* m_searchBar = nullptr;
    QTableWidget* m_tracksTable = nullptr;
    QPushButton* m_musicBrainzButton = nullptr;
    QPushButton* m_changeCoverButton = nullptr;
    QListWidget* m_albumsList = nullptr;
    QListWidget* m_artistsList = nullptr;
    QListWidget* m_foldersList = nullptr;
    QListWidget* m_playlistsList = nullptr;
    QLabel* m_globalWallpaperLabel = nullptr;
    QListWidget* m_wallpaperEntriesList = nullptr;
    QVector<QCheckBox*> m_tabVisibilityCheckboxes; // Settings > Visible Tabs, indexed by tab index (0-4)

    QLabel* m_statsLabel = nullptr;
    QCheckBox* m_crossfadeCheckbox = nullptr;
    QLabel* m_crossfadeLabel = nullptr;
    QSlider* m_crossfadeSlider = nullptr;
    QCheckBox* m_videoWallpaperCheckbox = nullptr;
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
    QLabel* m_timeLabel = nullptr;

    QLabel* m_bigCoverArtLabel = nullptr;
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
    QPushButton* m_shuffleButton = nullptr;
    QPushButton* m_prevButton = nullptr;
    QPushButton* m_playButton = nullptr;
    QPushButton* m_pauseButton = nullptr;
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