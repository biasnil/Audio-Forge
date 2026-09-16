#pragma once

#include <QWidget>
#include "audioforge/audio_engine.hpp"
#include "audioforge/playback_queue.hpp"
#include "audioforge/music_library.hpp"
#include "audioforge/lyrics_provider.hpp"
#include <QFont>

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

namespace audioforge {

class ManualTagDialog;

class PlayerWindow : public QWidget
{
    Q_OBJECT

public:
    explicit PlayerWindow(QWidget* parent = nullptr);
    ~PlayerWindow() override;

protected:
    void closeEvent(QCloseEvent* event) override;

private:
    // --- Tab / page builders ---------------------------------------------
    QWidget* buildAlbumsTab();
    QWidget* buildArtistsTab();
    QWidget* buildTracksTab();
    QWidget* buildFoldersTab();
    QWidget* buildPlaylistsTab();
    QWidget* buildSettingsTab();
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
    void lookupSelectedTrackOnMusicBrainz();
    void handleMusicBrainzReply(QNetworkReply* reply);

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

    // --- Playback ----------------------------------------------------------
    void setupShortcuts();
    void togglePlayPause();
    void seekBySeconds(float deltaSeconds);
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

    bool m_seeking = false;
    bool m_wasAtEnd = false;
    bool m_darkMode = true;
    bool m_replayGainEnabled = true;
    float m_currentReplayGainDb = 0.0f;

    bool m_crossfadeEnabled = false;
    int m_crossfadeSeconds = 5;
    int m_volumeBeforeMute = 100;

    QString m_musixmatchApiKey;
    QLineEdit* m_musixmatchApiKeyEdit = nullptr;
    QListWidget* m_lyricsList = nullptr;
    QFont m_lyricsBaseFont;
    QVector<SyncedLyricLine> m_syncedLyrics; // empty unless the current lyrics are time-synced
    int m_lastLyricsLineIndex = -1; // avoids re-styling every item on every 200ms tick when nothing changed

    QNetworkAccessManager* m_network = nullptr;
    QString m_pendingLookupPath;

    // Non-null only while a manual-tag dialog's "Fill from internet" lookup
    // is in flight -- tells handleMusicBrainzReply() to feed its result into
    // this dialog instead of writing tags straight to disk. Points at a
    // stack-local ManualTagDialog owned by openManualTagDialog(), so it's
    // only ever valid while that dialog's exec() is still running.
    ManualTagDialog* m_activeManualTagDialog = nullptr;

    QTabWidget* m_tabs = nullptr;
    QStackedWidget* m_stack = nullptr;
    QWidget* m_nowPlayingPage = nullptr;
    QLineEdit* m_searchBar = nullptr;
    QTableWidget* m_tracksTable = nullptr;
    QPushButton* m_musicBrainzButton = nullptr;
    QPushButton* m_changeCoverButton = nullptr;
    QListWidget* m_albumsList = nullptr;
    QListWidget* m_artistsList = nullptr;
    QListWidget* m_foldersList = nullptr;
    QListWidget* m_playlistsList = nullptr;

    QLabel* m_statsLabel = nullptr;
    QCheckBox* m_crossfadeCheckbox = nullptr;
    QLabel* m_crossfadeLabel = nullptr;
    QSlider* m_crossfadeSlider = nullptr;

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
    QPushButton* m_openButton = nullptr;
    QPushButton* m_replayGainButton = nullptr;
    QPushButton* m_themeButton = nullptr;
    QSlider* m_volumeSlider = nullptr;
    QTimer* m_updateTimer = nullptr;
};

} // namespace audioforge