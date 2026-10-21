#include "audioforge/player_window.hpp"
#include "audioforge/track_metadata.hpp"
#include "audioforge/warnings.hpp"
#include "audioforge/widgets/card_list_widget.hpp"
#include "audioforge/windows_integration.hpp"
#include "audioforge/widgets/clickable_widget.hpp"
#include "audioforge/widgets/click_seek_slider.hpp"
#include "audioforge/widgets/single_page_stacked_widget.hpp"
#include "audioforge/widgets/video_background_widget.hpp"
#include "audioforge/dialogs/track_list_dialog.hpp"
#include "audioforge/dialogs/create_playlist_dialog.hpp"
#include "audioforge/dialogs/playlist_edit_dialog.hpp"
#include "audioforge/dialogs/musicbrainz_result_dialog.hpp"
#include "audioforge/dialogs/manual_tag_dialog.hpp"
#include "audioforge/dialogs/wallpaper_track_picker_dialog.hpp"
#include "audioforge/dialogs/duplicates_dialog.hpp"
#include "audioforge/dialogs/text_page_dialog.hpp"
#include "audioforge/dialogs/smart_playlist_dialog.hpp"
#include "audioforge/dialogs/visualizer_media_dialog.hpp"
#include "audioforge/widgets/visualizer_style_grid.hpp"
#include "audioforge/widgets/visualizer_widget.hpp"
#include "audioforge/m3u.hpp"
#include "audioforge/podcasts.hpp"
#include "audioforge/update_checker.hpp"
#include "audioforge/widgets/toggle_switch.hpp"
#include "audioforge/widgets/up_next_panel.hpp"
#include "audioforge/track_metadata_tags.hpp"
#include "audioforge/lyrics_provider.hpp"
#include "audioforge/cover_art_writer.hpp"
#include "audioforge/discord_presence.hpp"
#include "audioforge/secret_store.hpp"
#include "audioforge/theme.hpp"
#include "audioforge/app_files.hpp"
#include "audioforge/app_info.hpp"
#include "audioforge/translations.hpp"

#include <QAction>
#include <QApplication>
#include <QButtonGroup>
#include <QFrame>
#include <QVBoxLayout>
#include <QMenu>
#include <QShortcut>
#include <QFile>
#include <QScrollArea>
#include <QScrollBar>
#include <QFontMetrics>
#include <QComboBox>
#include <QStyleOptionSlider>
#include <QHBoxLayout>
#include <QPushButton>
#include <QSlider>
#include <QLabel>
#include <QCheckBox>
#include <QFileDialog>
#include <QFileInfo>
#include <QDir>
#include <QTimer>
#include <QPalette>
#include <QTabBar>
#include <QTabWidget>
#include <QListWidget>
#include <QTableWidget>
#include <QHeaderView>
#include <QLineEdit>
#include <QRandomGenerator>
#include <QPixmap>
#include <QImage>
#include <QNetworkAccessManager>
#include <QNetworkRequest>
#include <QNetworkReply>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonArray>
#include <QUrl>
#include <QUrlQuery>
#include <QStackedWidget>
#include <QStackedLayout>
#include <QSettings>
#include <QStandardPaths>
#include <QCloseEvent>
#include <QEvent>
#include <QResizeEvent>
#include <QMouseEvent>
#include <QDateTime>
#include <QInputDialog>
#include <QThread>
#include <QScreen>
#include <QElapsedTimer>
#include <QDragEnterEvent>
#include <QDropEvent>
#include <QMimeData>
#include <QSystemTrayIcon>
#include <QLinearGradient>
#include <QGraphicsOpacityEffect>
#include <QGraphicsDropShadowEffect>
#include <QPainter>
#include <QProgressBar>
#include <QStyleHints>
#include <QClipboard>
#include <QDesktopServices>
#include <QMessageBox>
#include <QProcess>
#include <QSysInfo>
#include <QLocale>
#include <QRegularExpression>
#include <QUuid>

#include <cmath>

namespace audioforge {

// Palettes and the app stylesheet live in theme.cpp. (Qt's "Fusion" style,
// set in main.cpp, is the one that respects a custom QPalette everywhere.)

// Round, borderless, icon-only button -- the transport controls, back,
// full screen, etc. Icons themselves are set by refreshIcons().
static QPushButton* MakeIconButton(int size, int iconSize, const QString& toolTip)
{
    auto* button = new QPushButton();
    button->setObjectName("IconButton");
    button->setFixedSize(size, size);
    button->setIconSize(QSize(iconSize, iconSize));
    button->setToolTip(toolTip);
    button->setCursor(Qt::PointingHandCursor);
    button->setFocusPolicy(Qt::NoFocus); // Space belongs to the play/pause shortcut, not whichever button was clicked last
    return button;
}


// Small icon for a right-click menu entry, in the theme's text color.
static QIcon MenuIcon(theme::Icon kind, bool dark)
{
    return theme::icon(kind, theme::tokens(dark).text, QColor(), QColor(), 16);
}

// The tracks that live under one of 'folders' (subfolders included).
static QVector<TrackInfo> TracksInFolders(const QVector<TrackInfo>& tracks, const QStringList& folders)
{
    QStringList prefixes;
    for (const QString& dir : folders)
    {
        QString prefix = QDir(dir).absolutePath();
        if (!prefix.endsWith('/'))
        {
            prefix += '/'; // "C:/Music" mustn't match "C:/Music2"
        }
        prefixes << prefix;
    }
    QVector<TrackInfo> result;
    result.reserve(tracks.size());
    for (const TrackInfo& t : tracks)
    {
        for (const QString& prefix : prefixes)
        {
            if (t.path.startsWith(prefix, Qt::CaseInsensitive)) // Windows paths are case-insensitive
            {
                result << t;
                break;
            }
        }
    }
    return result;
}

// "Resume Long Tracks" applies to tracks at least this long (audiobooks, mixes).
static constexpr float kLongTrackSeconds = 600.0f;

// Album / artist rows: the plain name (the row's text adds a translated "(N tracks)").
static constexpr int kGroupNameRole = Qt::UserRole + 1;
// Playlist rows: a smart playlist's id (empty for a regular one, whose index is Qt::UserRole).
static constexpr int kSmartIdRole = Qt::UserRole + 2;
static constexpr int kNewCardRole = Qt::UserRole + 3; // the Playlists tab's "+ New" cards

// Scales `imageBytes` to fill `targetSize` exactly, cropping the overflow
// off whichever dimension ends up larger -- rather than stretching the
// image non-uniformly to force it into a differently-proportioned box
// (which is what QLabel::setScaledContents alone does, and why a 1280x760
// cover looked squished in a square thumbnail). This is the same
// fill-and-crop behavior most players use for album art. Returns a null
// QPixmap if the bytes don't decode or targetSize is empty.
static QPixmap ScaledCoverArt(const QByteArray& imageBytes, const QSize& targetSize)
{
    QPixmap pix;
    if (imageBytes.isEmpty() || targetSize.isEmpty() || !pix.loadFromData(imageBytes))
    {
        return QPixmap();
    }

    QPixmap scaled = pix.scaled(targetSize, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);

    // `scaled` now covers targetSize in both dimensions (aspect ratio
    // preserved) but may overhang on one axis -- crop a centered
    // targetSize-sized region out of it.
    int x = (scaled.width() - targetSize.width()) / 2;
    int y = (scaled.height() - targetSize.height()) / 2;
    return scaled.copy(x, y, targetSize.width(), targetSize.height());
}

PlayerWindow::PlayerWindow(QWidget* parent) : QWidget(parent)
{
    setWindowTitle("AudioForge");
    // 1200x780, or 90% of a smaller screen (taskbar excluded).
    QSize initialSize(1200, 780);
    if (QScreen* screen = QGuiApplication::primaryScreen())
    {
        const QRect available = screen->availableGeometry();
        initialSize = initialSize.boundedTo(QSize(available.width() * 9 / 10, available.height() * 9 / 10));
    }
    resize(initialSize);

    if (!m_engine.init())
    {
        ErrorReporter::warn(this, tr("Audio engine failed to start"),
            tr("The audio engine could not be initialized (no audio device found?). Playback controls won't work."));
    }

    m_network = new QNetworkAccessManager(this);
    m_updateChecker = new UpdateChecker(m_network, this); // before the Settings tab, which shows its state

    m_stack = new SinglePageStackedWidget();
    auto* outerLayout = new QVBoxLayout(this);
    outerLayout->setContentsMargins(0, 0, 0, 0);
    outerLayout->addWidget(m_stack);

    // Live video wallpaper -- the actual
    // VideoBackgroundWidget is created and layered in buildNowPlayingPage()
    // instead of here, since it's scoped to just that page, not the whole
    // window. Library data still needs loading up front either way.
    m_wallpaperLibrary.load();

    // Key/BPM analysis results + long-track resume positions, and the
    // background analyzer that fills them in. Deleted in ~PlayerWindow, in
    // that order (the analyzer writes into the store).
    m_songData = new SongDataStore();
    m_songData->load();
    m_analyzer = new LibraryAnalyzer(m_songData);
    connect(m_analyzer, &LibraryAnalyzer::progressChanged, this, &PlayerWindow::updateAnalysisProgressUi);
    connect(m_analyzer, &LibraryAnalyzer::finished, this, &PlayerWindow::onAnalysisFinished);
    m_genreAnalyzer = new GenreAnalyzer(m_songData);
    connect(m_genreAnalyzer, &GenreAnalyzer::progressChanged, this, &PlayerWindow::updateGenreProgressUi);
    connect(m_genreAnalyzer, &GenreAnalyzer::finished, this, [this]() {
        updateGenreProgressUi();
        // Newly detected genres replace empty and "Music"-like ones in the list.
        refreshTracksTable();
        updateLibraryStatsUi();
    });

    m_visualizerMedia.load();
    m_smartPlaylists = LoadSmartPlaylists();

    // Theme "System" follows Windows' light/dark setting, live.
    connect(QGuiApplication::styleHints(), &QStyleHints::colorSchemeChanged, this, [this]() {
        if (m_themeMode == 0)
        {
            applyTheme();
        }
    });

    // --- Library page: sidebar | (title + search, tabs, mini player bar) ---
    auto* libraryPage = new QWidget();
    auto* libraryLayout = new QHBoxLayout(libraryPage);
    libraryLayout->setContentsMargins(0, 0, 0, 0);
    libraryLayout->setSpacing(0);

    auto* mainArea = new QWidget();
    auto* rootLayout = new QVBoxLayout(mainArea);
    rootLayout->setContentsMargins(28, 20, 24, 16);
    rootLayout->setSpacing(14);

    auto* topBar = new QHBoxLayout();
    topBar->setSpacing(10);
    m_pageTitleLabel = new QLabel();
    m_pageTitleLabel->setObjectName("PageTitle");
    topBar->addWidget(m_pageTitleLabel);
    topBar->addStretch();

    m_searchBar = new QLineEdit();
    m_searchBar->setObjectName("SearchBar");
    m_searchBar->setPlaceholderText(tr("Search songs, artists, albums..."));
    m_searchBar->setClearButtonEnabled(true);
    m_searchBar->setFixedWidth(320);
    m_searchIconAction = m_searchBar->addAction(QIcon(), QLineEdit::LeadingPosition);
    connect(m_searchBar, &QLineEdit::textChanged, this, &PlayerWindow::applySearchFilter);
    topBar->addWidget(m_searchBar);

    m_themeButton = MakeIconButton(36, 18, tr("Toggle light / dark theme"));
    connect(m_themeButton, &QPushButton::clicked, this, &PlayerWindow::toggleTheme);
    topBar->addWidget(m_themeButton);
    rootLayout->addLayout(topBar);

    // Library scan progress -- only shown while a scan is running.
    m_scanBar = new QWidget();
    auto* scanLayout = new QHBoxLayout(m_scanBar);
    scanLayout->setContentsMargins(4, 0, 4, 0);
    scanLayout->setSpacing(12);
    m_scanLabel = new QLabel(tr("Loading library\u2026"));
    m_scanLabel->setObjectName("MutedLabel");
    scanLayout->addWidget(m_scanLabel);
    m_scanProgress = new QProgressBar();
    m_scanProgress->setTextVisible(false);
    scanLayout->addWidget(m_scanProgress, 1);
    m_scanBar->hide();
    rootLayout->addWidget(m_scanBar);

    m_tabs = new QTabWidget();
    m_tabs->tabBar()->hide(); // navigation lives in the sidebar
    m_tabs->setDocumentMode(true);

    // Up Next sits beside the page (not over the player bar), hidden until
    // the queue button opens it.
    auto* contentRow = new QHBoxLayout();
    contentRow->setSpacing(16);
    contentRow->addWidget(m_tabs, 1);
    m_upNextPanel = new UpNextPanel();
    m_upNextPanel->hide();
    contentRow->addWidget(m_upNextPanel);
    rootLayout->addLayout(contentRow, 1);

    connect(m_upNextPanel, &UpNextPanel::closeRequested, this, [this]() { setUpNextVisible(false); });
    connect(m_upNextPanel, &UpNextPanel::jumpToPlayingNext, this, [this](int position) {
        rememberLongTrackPosition();
        abortCrossfade();
        m_queue.jumpToManual(position);
        loadTrack(m_queue.currentTrack());
    });
    connect(m_upNextPanel, &UpNextPanel::jumpToListItem, this, [this](int index) {
        rememberLongTrackPosition();
        abortCrossfade();
        m_queue.jumpTo(index);
        loadTrack(m_queue.currentTrack());
    });
    connect(m_upNextPanel, &UpNextPanel::removePlayingNext, this, [this](int position) {
        settleHandOff();
        m_queue.removeFromManualQueue(position);
        refreshUpNext();
    });
    connect(m_upNextPanel, &UpNextPanel::movePlayingNext, this, [this](int from, int to) {
        settleHandOff();
        m_queue.moveInManualQueue(from, to);
        refreshUpNext();
    });
    connect(m_upNextPanel, &UpNextPanel::removeListItem, this, [this](int index) {
        settleHandOff();
        m_queue.removeAt(index);
        refreshUpNext();
    });
    connect(m_upNextPanel, &UpNextPanel::clearPlayingNextRequested, this, [this]() {
        settleHandOff();
        m_queue.clearManualQueue();
        refreshUpNext();
    });
    connect(m_upNextPanel, &UpNextPanel::saveAsPlaylistRequested, this, &PlayerWindow::saveQueueAsPlaylist);
    // History rows are positions in the shown History list (the last 20 played, minus the current track).
    auto historyTrack = [this](int position) -> std::optional<TrackInfo> {
        const int pastCount = m_playHistory.size() - (!m_queue.isEmpty() && m_engine.isLoaded() ? 1 : 0); // same as refreshUpNext
        const int index = qMax(0, pastCount - 20) + position;
        if (index < 0 || index >= pastCount)
        {
            return std::nullopt;
        }
        return m_playHistory[index];
    };
    connect(m_upNextPanel, &UpNextPanel::playHistoryNow, this, [this, historyTrack](int position) {
        if (const std::optional<TrackInfo> track = historyTrack(position))
        {
            // In front of everything queued, then skip to it: the list carries on afterwards.
            settleHandOff();
            m_queue.playNext({*track});
            next(false);
        }
    });
    connect(m_upNextPanel, &UpNextPanel::playHistoryNext, this, [this, historyTrack](int position) {
        if (const std::optional<TrackInfo> track = historyTrack(position))
        {
            playNextTracks({*track});
        }
    });
    connect(m_upNextPanel, &UpNextPanel::addHistoryToQueue, this, [this, historyTrack](int position) {
        if (const std::optional<TrackInfo> track = historyTrack(position))
        {
            addTracksToQueue({*track});
        }
    });

    m_toast = new QLabel(this);
    m_toast->setObjectName("Toast");
    m_toast->hide();
    m_toastTimer = new QTimer(this);
    m_toastTimer->setSingleShot(true);
    connect(m_toastTimer, &QTimer::timeout, m_toast, &QWidget::hide);

    m_tabs->addTab(buildAlbumsTab(), tr("Albums"));     // index 0
    m_tabs->addTab(buildTracksTab(), tr("Tracks"));      // index 1
    m_tabs->addTab(buildArtistsTab(), tr("Artists"));    // index 2
    m_tabs->addTab(buildFoldersTab(), tr("Folders"));    // index 3
    m_tabs->addTab(buildPlaylistsTab(), tr("Playlists"));// index 4
    m_tabs->addTab(buildEqualizerTab(), tr("Equalizer")); // index 5 (always visible, not in the visibility config)
    m_tabs->addTab(buildWallpapersTab(), tr("Wallpapers")); // index 6 (always visible, not in the visibility config)
    m_tabs->addTab(buildSettingsTab(), tr("Settings"));  // index 7 (always visible, not in the visibility config)
    m_tabs->addTab(buildPodcastsTab(), tr("Podcasts"));  // index 8 (shown with the library tabs)
    rootLayout->addWidget(buildPlayerBar());

    libraryLayout->addWidget(buildSidebar());
    libraryLayout->addWidget(mainArea, 1);

    connect(m_tabs, &QTabWidget::currentChanged, this, &PlayerWindow::onTabChanged);
    connect(m_tabs, &QTabWidget::currentChanged, this, &PlayerWindow::fitTracksColumnsOnce); // if Tracks wasn't showing at start
    m_tabs->setCurrentIndex(1); // Tracks
    onTabChanged(m_tabs->currentIndex()); // setCurrentIndex(1) may not emit if 1 was already current

    m_stack->addWidget(libraryPage);          // index 0
    m_stack->addWidget(buildNowPlayingPage());// index 1
    m_stack->setCurrentIndex(0);
    // The video wallpaper only decodes while it's actually on screen (Now
    // Playing) -- hidden, it was costing CPU the whole time on PCs without
    // hardware video conversion.
    m_wallpaperWidget->setPaused(true);
    connect(m_stack, &QStackedWidget::currentChanged, this, [this](int index) {
        m_wallpaperWidget->setPaused(index != 1 || isMinimized());
        updateVisualizerActive();
    });

    // Qt apps are event-loop driven, not a manual render loop -- a timer is
    // the normal way to poll playback position, detect end-of-track /
    // crossfade windows, and refresh the UI periodically.
    m_updateTimer = new QTimer(this);
    connect(m_updateTimer, &QTimer::timeout, this, &PlayerWindow::updatePlayback);
    m_updateTimer->start(200);

    setupShortcuts();
    loadSettings();
    restoreQueueState(); // needs the library (loadSettings) to look tracks up
    startLibraryScan();  // checks the cached library against the disk, in the background
    QTimer::singleShot(0, this, &PlayerWindow::fitTracksColumnsOnce); // once the window is on screen

    setupWindowsIntegration();
    QTimer::singleShot(3000, this, [this]() { m_startupSettled = true; }); // the restored song isn't "new"
    setAcceptDrops(true); // music files to play, folders to add (dropEvent)
    connect(qApp, &QCoreApplication::aboutToQuit, this, &PlayerWindow::saveBeforeExit); // e.g. Windows shutting down while in the tray

    // Development aid: AUDIOFORGE_SNAPSHOT="4:playlists.png;nowplaying:np.png" saves
    // pictures of those pages (a tab index, or "nowplaying") a few seconds after
    // start, then quits. Unset, nothing happens.
    const QString snapshots = qEnvironmentVariable("AUDIOFORGE_SNAPSHOT");
    if (!snapshots.isEmpty())
    {
        QTimer::singleShot(4000, this, [this, snapshots]() {
            for (const QString& entry : snapshots.split(';', Qt::SkipEmptyParts))
            {
                const QString page = entry.section(':', 0, 0);
                const QString file = entry.section(':', 1);
                if (page == QLatin1String("nowplaying"))
                {
                    m_stack->setCurrentIndex(1);
                }
                else
                {
                    m_stack->setCurrentIndex(0);
                    m_tabs->setCurrentIndex(page.toInt());
                }
                QElapsedTimer settle;
                settle.start();
                while (settle.elapsed() < 1500)
                {
                    QCoreApplication::processEvents(QEventLoop::AllEvents, 50);
                }
                grab().save(file);
            }
            QCoreApplication::exit(0);
        });
    }
}

PlayerWindow::~PlayerWindow()
{
    if (m_scanThread)
    {
        m_scanCancel->store(true); // it stops after the file it's reading
        m_scanThread->wait();
    }
    // Analyzers first: cancelling them writes into the store. Disconnected so
    // their "finished" signals can't reach this half-destroyed window.
    m_analyzer->disconnect();
    delete m_analyzer;
    m_genreAnalyzer->disconnect();
    delete m_genreAnalyzer;
    delete m_songData; // saves anything pending
    // AudioEngine's own destructor tears down miniaudio
}

void PlayerWindow::closeEvent(QCloseEvent* event)
{
    // "Keep playing in the tray": closing only hides the window. Quit is in the tray icon's menu.
    if (m_keepPlayingInTray && !m_quitting && m_tray && m_tray->isVisible())
    {
        hide();
        event->ignore();
        if (!m_trayHintShown)
        {
            m_trayHintShown = true;
            m_tray->showMessage(QStringLiteral("AudioForge"),
                                tr("AudioForge keeps playing here. Right-click the icon to quit."),
                                windowIcon(), 5000);
        }
        return;
    }
    saveBeforeExit();
    QWidget::closeEvent(event);
}

void PlayerWindow::saveBeforeExit()
{
    if (m_savedForExit)
    {
        return; // closeEvent and aboutToQuit can both come
    }
    m_savedForExit = true;
    rememberLongTrackPosition();
    m_songData->saveNow();
    saveQueueState();
    MusicLibrary::SaveCache(m_library.allTracks()); // keeps tag edits made since the last scan (podcasts too)
    saveSettings();
    m_wallpaperLibrary.save();
}

void PlayerWindow::setupWindowsIntegration()
{
    // The Windows media controls (overlay, lock screen, background media keys) and the taskbar buttons.
    m_windowsMedia = new WindowsMediaControls(this);
    connect(m_windowsMedia, &WindowsMediaControls::playPauseRequested, this, &PlayerWindow::togglePlayPause);
    connect(m_windowsMedia, &WindowsMediaControls::playRequested, this, [this]() {
        if (!m_engine.isPlaying())
        {
            togglePlayPause();
        }
    });
    connect(m_windowsMedia, &WindowsMediaControls::pauseRequested, this, [this]() {
        if (m_engine.isPlaying())
        {
            togglePlayPause();
        }
    });
    connect(m_windowsMedia, &WindowsMediaControls::nextRequested, this, [this]() { next(false); });
    connect(m_windowsMedia, &WindowsMediaControls::previousRequested, this, &PlayerWindow::previous);
    if (!m_queue.isEmpty() && m_engine.isLoaded()) // the song restored at start
    {
        const TrackInfo& info = m_queue.currentTrack();
        m_windowsMedia->setTrack(info.title.isEmpty() ? QFileInfo(info.path).completeBaseName() : info.title,
                                 info.artist, info.album, info.coverArt.isEmpty() ? ReadCoverArt(info.path) : info.coverArt);
        m_windowsMedia->setPlaybackState(true, m_engine.isPlaying());
    }

    // The tray icon: Play/Pause, Next, Previous, Show, Quit; a click brings the window back.
    if (!QSystemTrayIcon::isSystemTrayAvailable())
    {
        return;
    }
    m_tray = new QSystemTrayIcon(windowIcon(), this);
    m_tray->setToolTip(QStringLiteral("AudioForge"));
    auto* menu = new QMenu(this);
    m_trayPlayAction = menu->addAction(tr("Play"), this, &PlayerWindow::togglePlayPause);
    menu->addAction(tr("Next"), this, [this]() { next(false); });
    menu->addAction(tr("Previous"), this, &PlayerWindow::previous);
    menu->addSeparator();
    menu->addAction(tr("Show AudioForge"), this, &PlayerWindow::showFromTray);
    menu->addAction(tr("Quit"), this, [this]() {
        m_quitting = true;
        close(); // saves
        QCoreApplication::quit();
    });
    m_tray->setContextMenu(menu);
    connect(m_tray, &QSystemTrayIcon::activated, this, [this](QSystemTrayIcon::ActivationReason reason) {
        if (reason == QSystemTrayIcon::Trigger || reason == QSystemTrayIcon::DoubleClick)
        {
            showFromTray();
        }
    });
    connect(m_tray, &QSystemTrayIcon::messageClicked, this, &PlayerWindow::showFromTray);
    m_tray->show();
}

void PlayerWindow::showFromTray()
{
    if (isMinimized())
    {
        showNormal();
    }
    else
    {
        show();
    }
    raise();
    activateWindow();
}

void PlayerWindow::notifySongStarted(const TrackInfo& info, const QByteArray& coverArt)
{
    // Only when AudioForge isn't what you're looking at, and not for the song restored at start.
    if (!m_songNotifications || !m_tray || !m_startupSettled || (isVisible() && isActiveWindow() && !isMinimized()))
    {
        return;
    }
    QPixmap cover;
    if (coverArt.isEmpty() || !cover.loadFromData(coverArt))
    {
        cover = theme::generatedArt(info.title, 64, 8);
    }
    QString details = info.artist;
    if (!info.album.isEmpty())
    {
        details += (details.isEmpty() ? QString() : QStringLiteral(" • ")) + info.album;
    }
    m_tray->showMessage(info.title.isEmpty() ? QFileInfo(info.path).completeBaseName() : info.title, details,
                        QIcon(cover), 5000);
}

bool PlayerWindow::nativeEvent(const QByteArray& eventType, void* message, qintptr* result)
{
    if (m_windowsMedia && eventType == "windows_generic_MSG" && m_windowsMedia->handleNativeEvent(message, result))
    {
        return true;
    }
    return QWidget::nativeEvent(eventType, message, result);
}

namespace {

// What a drop can hold: folders to add, and the files AudioForge plays.
bool IsPlayableFile(const QString& path)
{
    static const QStringList kExtensions = {"mp3", "flac", "wav", "m4a", "m4b", "aac"};
    return kExtensions.contains(QFileInfo(path).suffix().toLower());
}

} // namespace

void PlayerWindow::dragEnterEvent(QDragEnterEvent* event)
{
    for (const QUrl& url : event->mimeData()->urls())
    {
        const QString path = url.toLocalFile();
        if (!path.isEmpty() && (QFileInfo(path).isDir() || IsPlayableFile(path)))
        {
            event->acceptProposedAction();
            return;
        }
    }
}

void PlayerWindow::dropEvent(QDropEvent* event)
{
    QVector<TrackInfo> tracks;
    QStringList addedFolders;
    for (const QUrl& url : event->mimeData()->urls())
    {
        const QString path = url.toLocalFile();
        if (path.isEmpty())
        {
            continue;
        }
        if (QFileInfo(path).isDir())
        {
            if (m_library.addFolder(path))
            {
                addedFolders << QDir::toNativeSeparators(path);
            }
        }
        else if (IsPlayableFile(path))
        {
            tracks << m_library.findTrackInfo(path);
        }
    }
    event->acceptProposedAction();
    if (!addedFolders.isEmpty())
    {
        refreshFoldersList();
        startLibraryScan(); // reads the new folders' songs in the background
    }
    if (!tracks.isEmpty())
    {
        setQueueAndPlay(tracks, 0, tr("Dropped files"));
    }
    if (!addedFolders.isEmpty())
    {
        showToast(addedFolders.size() == 1 ? tr("Added %1 to the library.").arg(addedFolders.first())
                                           : tr("Added %1 folders to the library.").arg(addedFolders.size()));
    }
}

void PlayerWindow::changeEvent(QEvent* event)
{
    if (event->type() == QEvent::WindowStateChange)
    {
        m_wallpaperWidget->setPaused(isMinimized() || m_stack->currentIndex() != 1);
        updateVisualizerActive();
    }
    QWidget::changeEvent(event);
}

void PlayerWindow::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    if (m_sidebar)
    {
        setSidebarCompact(width() < 1100 || height() < 640);
    }
    // Only the mini player bar's title tracks the window's actual width --
    // the big Now Playing title sits in a fixed-size box regardless of
    // window size, so it has nothing to re-measure here.
    if (!m_miniTitleFullText.isEmpty())
    {
        applyElidedMiniTitle();
    }
}

// --- Tab builders ----------------------------------------------------

QWidget* PlayerWindow::buildAlbumsTab()
{
    auto* w = new QWidget();
    auto* l = new QVBoxLayout(w);
    m_albumsList = new QListWidget();
    connect(m_albumsList, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem* item) {
        openGroupTracks(item->data(kGroupNameRole).toString(), item->data(Qt::UserRole).toStringList());
    });
    enableTracksMenu(m_albumsList, /*playlistRows=*/false);

    l->addWidget(m_albumsList);
    return w;
}

QWidget* PlayerWindow::buildArtistsTab()
{
    auto* w = new QWidget();
    auto* l = new QVBoxLayout(w);
    m_artistsList = new QListWidget();
    connect(m_artistsList, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem* item) {
        openGroupTracks(item->data(kGroupNameRole).toString(), item->data(Qt::UserRole).toStringList());
    });
    enableTracksMenu(m_artistsList, /*playlistRows=*/false);

    l->addWidget(m_artistsList);
    return w;
}

QWidget* PlayerWindow::buildTracksTab()
{
    auto* w = new QWidget();
    auto* l = new QVBoxLayout(w);

    m_tracksTable = new QTableWidget(0, 5);
    m_tracksTable->setHorizontalHeaderLabels({tr("Title"), tr("Artist"), tr("Album"), tr("Genre"), tr("Year")});
    {
        // Every column can be resized (drag its edge; double-click the edge to
        // fit it) and moved (drag its name); both are remembered (saveSettings).
        QHeaderView* header = m_tracksTable->horizontalHeader();
        header->setSectionResizeMode(QHeaderView::Interactive);
        header->setSectionsMovable(true);
        header->setStretchLastSection(false);
        header->setMinimumSectionSize(48);
        header->setTextElideMode(Qt::ElideRight);
        const int widths[] = {440, 220, 220, 160, 64}; // Title, Artist, Album, Genre, Year
        for (int i = 0; i < 5; ++i)
        {
            header->resizeSection(i, widths[i]);
        }
    }
    m_tracksTable->setEditTriggers(QAbstractItemView::NoEditTriggers);
    m_tracksTable->setSelectionBehavior(QAbstractItemView::SelectRows);
    m_tracksTable->setSelectionMode(QAbstractItemView::ExtendedSelection); // Ctrl/Shift-click several to queue them
    m_tracksTable->setSortingEnabled(true);
    // Row separators come from the stylesheet's item border. Qt's own grid
    // adds a 1px line *between* rows that the row-number header doesn't
    // have, so the two sets of lines drifted apart into a visible step.
    m_tracksTable->setShowGrid(false);
    m_tracksTable->verticalHeader()->setDefaultAlignment(Qt::AlignCenter);

    connect(m_tracksTable, &QTableWidget::cellDoubleClicked, this, [this](int row, int) { playTracksTableRow(row); });
    m_tracksTable->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_tracksTable, &QTableWidget::customContextMenuRequested, this, &PlayerWindow::showTrackContextMenu);
    l->addWidget(m_tracksTable);

    auto* buttonRow = new QHBoxLayout();
    m_musicBrainzButton = new QPushButton(tr("Edit Tags..."));
    m_musicBrainzButton->setEnabled(false);
    connect(m_musicBrainzButton, &QPushButton::clicked, this, [this]() { openManualTagDialog(); });
    buttonRow->addWidget(m_musicBrainzButton);

    m_changeCoverButton = new QPushButton(tr("Change Cover..."));
    m_changeCoverButton->setEnabled(false);
    connect(m_changeCoverButton, &QPushButton::clicked, this, [this]() { openChangeCoverDialog(); });
    buttonRow->addWidget(m_changeCoverButton);

    connect(m_tracksTable, &QTableWidget::itemSelectionChanged, this, [this]() {
        bool hasSelection = !m_tracksTable->selectedItems().isEmpty();
        m_musicBrainzButton->setEnabled(hasSelection);
        m_changeCoverButton->setEnabled(hasSelection);
    });
    buttonRow->addStretch();
    l->addLayout(buttonRow);

    return w;
}

QWidget* PlayerWindow::buildFoldersTab()
{
    auto* w = new QWidget();
    auto* l = new QVBoxLayout(w);

    m_foldersList = new QListWidget();
    m_foldersList->setObjectName("FoldersList");
    m_foldersList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_foldersList->setResizeMode(QListView::Adjust); // rows follow the list's width
    l->addWidget(m_foldersList);

    auto* podcastNote = new QLabel(tr(
        "Tick Podcast for a folder of podcasts: its episodes only show in the Podcasts tab (never in Tracks, "
        "Albums, playlists or shuffle), continue where you left them, and .m4a files in it are found too."));
    podcastNote->setObjectName("MutedLabel");
    podcastNote->setWordWrap(true);
    l->addWidget(podcastNote);

    auto* addFolderButton = new QPushButton(tr("Add Folder..."));
    connect(addFolderButton, &QPushButton::clicked, this, &PlayerWindow::addFolder);

    auto* row = new QHBoxLayout();
    row->addStretch();
    row->addWidget(addFolderButton);
    row->addStretch();
    l->addLayout(row);

    return w;
}

QWidget* PlayerWindow::buildPlaylistsTab()
{
    // Two sections of cards on one scrolling page: the user's own playlists
    // (cover mosaics, plus a "New" card), then the smart ones.
    auto* scroll = new QScrollArea();
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    auto* page = new QWidget();
    auto* l = new QVBoxLayout(page);
    l->setContentsMargins(0, 0, 8, 16);

    auto heading = [](const QString& text) {
        auto* label = new QLabel(text);
        label->setObjectName("SectionHeading");
        return label;
    };

    l->addWidget(heading(tr("Your Playlists")));
    m_playlistsList = new CardListWidget(QSize(150, 150), QSize(176, 214));
    l->addWidget(m_playlistsList);
    l->addSpacing(12);
    l->addWidget(heading(tr("Smart Playlists")));
    m_smartPlaylistsList = new CardListWidget(QSize(72, 72), QSize(140, 134));
    l->addWidget(m_smartPlaylistsList);
    l->addStretch();

    for (QListWidget* list : {m_playlistsList, m_smartPlaylistsList})
    {
        connect(list, &QListWidget::itemDoubleClicked, this, &PlayerWindow::activatePlaylistItem);
        // The "New" cards open their menu on a single click.
        connect(list, &QListWidget::itemClicked, this, [this, list](QListWidgetItem* item) {
            if (!item->data(kNewCardRole).toBool())
            {
                return;
            }
            list->clearSelection();
            if (list == m_smartPlaylistsList)
            {
                editSmartPlaylist(QString());
            }
            else
            {
                showNewPlaylistMenu(list->viewport(), list->visualItemRect(item).bottomLeft());
            }
        });
        enableTracksMenu(list, /*playlistRows=*/true);
    }

    scroll->setWidget(page);
    return scroll;
}

// --- Podcasts ----------------------------------------------------------------------

QWidget* PlayerWindow::buildPodcastsTab()
{
    m_podcastsStack = new QStackedWidget();

    // Page 0: the shows.
    auto* showsPage = new QWidget();
    auto* showsLayout = new QVBoxLayout(showsPage);
    m_podcastsEmptyLabel = new QLabel(tr("No podcasts yet. In the Folders tab, tick Podcast for a folder of podcasts."));
    m_podcastsEmptyLabel->setObjectName("MutedLabel");
    m_podcastsEmptyLabel->setWordWrap(true);
    m_podcastsEmptyLabel->setAlignment(Qt::AlignCenter);
    showsLayout->addWidget(m_podcastsEmptyLabel, 1);
    m_podcastShowsList = new QListWidget();
    m_podcastShowsList->setIconSize(QSize(44, 44));
    m_podcastShowsList->setSpacing(2);
    connect(m_podcastShowsList, &QListWidget::itemActivated, this, [this](QListWidgetItem* item) {
        openPodcastShow(item->data(Qt::UserRole).toString());
    });
    showsLayout->addWidget(m_podcastShowsList, 1);
    m_podcastsStack->addWidget(showsPage);

    // Page 1: one show's episodes, newest first.
    auto* episodesPage = new QWidget();
    auto* episodesLayout = new QVBoxLayout(episodesPage);
    auto* header = new QHBoxLayout();
    m_podcastBackButton = MakeIconButton(32, 18, tr("All podcasts"));
    connect(m_podcastBackButton, &QPushButton::clicked, this, [this]() {
        m_openPodcastShow.clear();
        m_podcastsStack->setCurrentIndex(0);
    });
    header->addWidget(m_podcastBackButton);
    m_podcastShowTitle = new QLabel();
    m_podcastShowTitle->setObjectName("SettingsSectionTitle");
    header->addWidget(m_podcastShowTitle, 1);
    episodesLayout->addLayout(header);
    m_podcastEpisodesList = new QListWidget();
    m_podcastEpisodesList->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_podcastEpisodesList, &QListWidget::customContextMenuRequested, this, &PlayerWindow::showPodcastEpisodeMenu);
    connect(m_podcastEpisodesList, &QListWidget::itemActivated, this, [this](QListWidgetItem* item) {
        for (const PodcastShow& show : PodcastShows(m_library))
        {
            if (show.name == m_openPodcastShow)
            {
                // From this episode on; each one continues where it was left.
                setQueueAndPlay(show.episodes, m_podcastEpisodesList->row(item), show.name);
                return;
            }
        }
    });
    episodesLayout->addWidget(m_podcastEpisodesList, 1);
    auto* hint = new QLabel(tr("Double-click an episode to play it; right-click to mark it played or unplayed."));
    hint->setObjectName("MutedLabel");
    episodesLayout->addWidget(hint);
    m_podcastsStack->addWidget(episodesPage);
    return m_podcastsStack;
}

void PlayerWindow::refreshPodcasts()
{
    if (!m_podcastShowsList)
    {
        return;
    }
    const QVector<PodcastShow> shows = PodcastShows(m_library);
    const QHash<QString, SongStats>& stats = m_songData->allStats();

    m_podcastShowsList->clear();
    const QPixmap placeholder = theme::icon(theme::Icon::Podcast, theme::tokens(m_darkMode).muted, QColor(), QColor(), 44).pixmap(44, 44);
    for (const PodcastShow& show : shows)
    {
        int unplayed = 0;
        for (const TrackInfo& e : show.episodes)
        {
            unplayed += stats.value(e.path).played ? 0 : 1;
        }
        const QPixmap cover = ScaledCoverArt(ReadCoverArt(show.episodes.first().path), QSize(88, 88));
        auto* item = new QListWidgetItem(QIcon(cover.isNull() ? placeholder : theme::roundedPixmap(cover, 8)),
                                         show.name + '\n' + tr("%1 episodes · %2 unplayed").arg(show.episodes.size()).arg(unplayed));
        item->setData(Qt::UserRole, show.name);
        m_podcastShowsList->addItem(item);
    }
    m_podcastsEmptyLabel->setVisible(shows.isEmpty());
    m_podcastShowsList->setVisible(!shows.isEmpty());

    // The open show's episodes (it may have gone: folder unmarked, or emptied).
    const PodcastShow* open = nullptr;
    for (const PodcastShow& show : shows)
    {
        if (show.name == m_openPodcastShow)
        {
            open = &show;
        }
    }
    if (!open)
    {
        m_openPodcastShow.clear();
        m_podcastsStack->setCurrentIndex(0);
        return;
    }
    const int row = m_podcastEpisodesList->currentRow();
    m_podcastEpisodesList->clear();
    const QString playing = !m_queue.isEmpty() && m_engine.isLoaded() ? m_queue.currentTrack().path : QString();
    for (const TrackInfo& e : open->episodes)
    {
        const SongStats s = stats.value(e.path);
        const double resume = m_songData->resumePosition(e.path);
        QString status;
        if (s.played)
        {
            status = QStringLiteral("✓ ") + tr("Played");
        }
        else if (resume > 0.0 && e.durationSeconds > resume)
        {
            status = tr("%1 left").arg(FormatDuration(static_cast<int>(e.durationSeconds - resume)));
        }
        else
        {
            status = FormatDuration(static_cast<int>(e.durationSeconds + 0.5));
        }
        const QString date = e.addedMs > 0 ? QLocale().toString(QDateTime::fromMSecsSinceEpoch(e.addedMs).date(), QLocale::ShortFormat)
                                           : QString();
        auto* item = new QListWidgetItem((e.title.isEmpty() ? QFileInfo(e.path).completeBaseName() : e.title) + '\n'
                                         + (date.isEmpty() ? status : date + QStringLiteral("  ·  ") + status));
        item->setData(Qt::UserRole, e.path);
        item->setToolTip(QDir::toNativeSeparators(e.path));
        if (e.path == playing)
        {
            QFont font = item->font();
            font.setBold(true);
            item->setFont(font);
        }
        if (s.played)
        {
            item->setForeground(theme::tokens(m_darkMode).muted);
        }
        m_podcastEpisodesList->addItem(item);
    }
    m_podcastEpisodesList->setCurrentRow(qMin(row, m_podcastEpisodesList->count() - 1));
}

void PlayerWindow::openPodcastShow(const QString& name)
{
    m_openPodcastShow = name;
    m_podcastShowTitle->setText(name);
    m_podcastEpisodesList->clear();
    refreshPodcasts();
    if (!m_openPodcastShow.isEmpty())
    {
        m_podcastsStack->setCurrentIndex(1);
    }
}

void PlayerWindow::showPodcastEpisodeMenu(const QPoint& pos)
{
    QListWidgetItem* item = m_podcastEpisodesList->itemAt(pos);
    if (!item)
    {
        return;
    }
    const QString path = item->data(Qt::UserRole).toString();
    const bool played = m_songData->statsFor(path).played;
    const TrackInfo episode = m_library.findTrackInfo(path);
    QMenu menu(this);
    menu.addAction(played ? tr("Mark as Unplayed") : tr("Mark as Played"), this, [this, path, played]() {
        m_songData->setPlayed(path, !played);
        refreshPodcasts();
    });
    menu.addSeparator();
    menu.addAction(MenuIcon(theme::Icon::Next, m_darkMode), tr("Play Next"), this, [this, episode]() { playNextTracks({episode}); });
    menu.addAction(MenuIcon(theme::Icon::Queue, m_darkMode), tr("Add to Queue"), this, [this, episode]() { addTracksToQueue({episode}); });
    menu.addAction(tr("Show in Folder"), this, [path]() { ShowInFileManager(path); });
    menu.exec(m_podcastEpisodesList->viewport()->mapToGlobal(pos));
}

// --- A-B repeat ---------------------------------------------------------------------

void PlayerWindow::cycleAbRepeat()
{
    if (!m_engine.isLoaded())
    {
        clearAbRepeat();
        return;
    }
    const float now = m_engine.cursorSeconds();
    switch (m_abState)
    {
        case AbState::Off:
            m_abState = AbState::ASet;
            m_abA = now;
            break;
        case AbState::ASet:
            if (now > m_abA + 1.0f)
            {
                m_abState = AbState::Looping;
                m_abB = now;
                if (m_engine.isCrossfading())
                {
                    abortCrossfade(); // the loop replaces the hand-off to the next song
                    applyVolume();
                }
            }
            else
            {
                m_abState = AbState::Off; // B at (or before) A: cancel
            }
            break;
        case AbState::Looping:
            m_abState = AbState::Off;
            break;
    }
    updateAbButton();
}

void PlayerWindow::clearAbRepeat()
{
    m_abState = AbState::Off;
    updateAbButton();
}

void PlayerWindow::updateAbButton()
{
    if (!m_abButton)
    {
        return;
    }
    m_abButton->setChecked(m_abState == AbState::Looping);
    m_abButton->setText(m_abState == AbState::ASet ? QStringLiteral("A–") : QStringLiteral("A–B"));
    // A set, waiting for B: the accent colour, not yet filled.
    m_abButton->setStyleSheet(m_abState == AbState::ASet
        ? QString("QPushButton { color: %1; }").arg(theme::tokens(m_darkMode).accent.name())
        : QString());
    switch (m_abState)
    {
        case AbState::Off:
            m_abButton->setToolTip(tr("A–B repeat: click to set the start (A)"));
            break;
        case AbState::ASet:
            m_abButton->setToolTip(tr("A–B repeat: click again to set the end (B)"));
            break;
        case AbState::Looping:
            m_abButton->setToolTip(tr("Repeating %1 to %2. Click to stop.")
                                       .arg(FormatDuration(static_cast<int>(m_abA)), FormatDuration(static_cast<int>(m_abB))));
            break;
    }
}

namespace {

// Settings is laid out like the iOS app: titled sections of rounded cards,
// one setting per row, with an explanatory footer under the card.
struct SettingsCard
{
    QFrame* frame = nullptr;
    QVBoxLayout* rows = nullptr;
};

SettingsCard AddSettingsSection(QVBoxLayout* page, const QString& title)
{
    auto* heading = new QLabel(title);
    heading->setObjectName("SettingsSectionTitle");
    page->addWidget(heading);

    SettingsCard card;
    card.frame = new QFrame();
    card.frame->setObjectName("SettingsCard");
    card.rows = new QVBoxLayout(card.frame);
    card.rows->setContentsMargins(0, 4, 0, 4);
    card.rows->setSpacing(0);
    page->addWidget(card.frame);
    return card;
}

void AddSettingsFooter(QVBoxLayout* page, const QString& text)
{
    auto* footer = new QLabel(text);
    footer->setObjectName("SettingsFooter");
    footer->setWordWrap(true);
    page->addWidget(footer);
}

// Adds `row` to the card, with a hairline divider above it unless it's the first.
void AddSettingsRow(const SettingsCard& card, QWidget* row)
{
    if (card.rows->count() > 0)
    {
        auto* divider = new QFrame();
        divider->setObjectName("SettingsDivider");
        auto* dividerWrap = new QWidget();
        auto* dividerLayout = new QHBoxLayout(dividerWrap);
        dividerLayout->setContentsMargins(16, 0, 16, 0);
        dividerLayout->addWidget(divider);
        // Hidden together with the row it introduces (see SetRowVisible).
        row->setProperty("divider", QVariant::fromValue(static_cast<QObject*>(dividerWrap)));
        card.rows->addWidget(dividerWrap);
    }
    card.rows->addWidget(row);
}

void SetRowVisible(QWidget* row, bool visible)
{
    row->setVisible(visible);
    if (auto* divider = qobject_cast<QWidget*>(row->property("divider").value<QObject*>()))
    {
        divider->setVisible(visible);
    }
}

// "Label .......... [trailing]" -- optional leading icon label.
QWidget* MakeSettingsRow(const QString& text, QWidget* trailing, QLabel** iconOut = nullptr, QLabel** textOut = nullptr)
{
    auto* row = new QWidget();
    row->setMinimumHeight(46);
    auto* layout = new QHBoxLayout(row);
    layout->setContentsMargins(16, 8, 16, 8);
    layout->setSpacing(12);
    if (iconOut)
    {
        *iconOut = new QLabel();
        (*iconOut)->setFixedSize(20, 20);
        layout->addWidget(*iconOut);
    }
    auto* label = new QLabel(text);
    label->setWordWrap(true);
    layout->addWidget(label, 1);
    if (textOut)
    {
        *textOut = label;
    }
    if (trailing)
    {
        layout->addWidget(trailing, 0, Qt::AlignVCenter);
    }
    return row;
}

QLabel* MakeValueLabel()
{
    auto* label = new QLabel("0");
    label->setObjectName("SettingsValue");
    return label;
}

} // namespace

QWidget* PlayerWindow::buildSettingsTab()
{
    auto* scroll = new QScrollArea();
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);

    auto* outer = new QWidget();
    auto* outerLayout = new QHBoxLayout(outer);
    outerLayout->setContentsMargins(0, 0, 12, 24);
    auto* column = new QWidget();
    column->setMaximumWidth(760);
    auto* l = new QVBoxLayout(column);
    l->setContentsMargins(0, 0, 0, 0);
    l->setSpacing(0);
    outerLayout->addWidget(column, 1);
    outerLayout->addStretch(0);
    scroll->setWidget(outer);

    auto iconRow = [this](const SettingsCard& card, const QString& text, theme::Icon icon, QWidget* trailing) {
        QLabel* iconLabel = nullptr;
        QWidget* row = MakeSettingsRow(text, trailing, &iconLabel);
        m_settingsIcons << qMakePair(iconLabel, icon);
        AddSettingsRow(card, row);
        return row;
    };

    // A whole row, chevron included, that opens a dialog, a page or a link.
    // (A QLabel, not a button: a button turns the "&" of "Terms & Conditions" into a shortcut.)
    auto chevronRow = [this](const SettingsCard& card, const QString& text, const std::function<void()>& onClick) {
        auto* chevron = new QLabel();
        m_settingsIcons << qMakePair(chevron, theme::Icon::Chevron);
        auto* row = new ClickableWidget();
        row->setMinimumHeight(46);
        connect(row, &ClickableWidget::clicked, this, onClick);
        auto* rowLayout = new QHBoxLayout(row);
        rowLayout->setContentsMargins(16, 8, 16, 8);
        rowLayout->addWidget(new QLabel(text), 1);
        rowLayout->addWidget(chevron);
        AddSettingsRow(card, row);
    };

    // --- Library ---------------------------------------------------------
    SettingsCard library = AddSettingsSection(l, tr("Library"));
    for (const QString& name : {tr("Songs"), tr("Albums"), tr("Artists"), tr("Genres")})
    {
        QLabel* value = MakeValueLabel();
        m_statValueLabels << value;
        AddSettingsRow(library, MakeSettingsRow(name, value));
    }

    {
        m_refreshLibraryButton = new QPushButton(tr("Refresh Library"));
        m_refreshLibraryButton->setObjectName("LinkButton");
        m_refreshLibraryButton->setCursor(Qt::PointingHandCursor);
        connect(m_refreshLibraryButton, &QPushButton::clicked, this, &PlayerWindow::refreshLibrary);
        auto* row = new QWidget();
        row->setMinimumHeight(46);
        auto* rowLayout = new QHBoxLayout(row);
        rowLayout->setContentsMargins(16, 8, 16, 8);
        rowLayout->addWidget(m_refreshLibraryButton);
        rowLayout->addStretch();
        AddSettingsRow(library, row);
    }

    {
        // Analyze: a link-style button with "N of M analyzed" under it, or,
        // while running, a progress bar and Stop.
        auto* row = new QWidget();
        auto* rowLayout = new QVBoxLayout(row);
        rowLayout->setContentsMargins(16, 10, 16, 10);
        rowLayout->setSpacing(4);
        auto* top = new QHBoxLayout();
        m_analyzeButton = new QPushButton(tr("Analyze Your Songs' Key and BPM"));
        m_analyzeButton->setObjectName("LinkButton");
        m_analyzeButton->setCursor(Qt::PointingHandCursor);
        connect(m_analyzeButton, &QPushButton::clicked, this, &PlayerWindow::startAnalysis);
        top->addWidget(m_analyzeButton);
        top->addStretch();
        m_analyzeStopButton = new QPushButton(tr("Stop"));
        m_analyzeStopButton->setObjectName("LinkButton");
        m_analyzeStopButton->setCursor(Qt::PointingHandCursor);
        connect(m_analyzeStopButton, &QPushButton::clicked, this, [this]() { m_analyzer->cancel(); });
        top->addWidget(m_analyzeStopButton);
        rowLayout->addLayout(top);
        m_analyzeProgress = new QProgressBar();
        m_analyzeProgress->setTextVisible(false);
        rowLayout->addWidget(m_analyzeProgress);
        m_analyzeStatusLabel = new QLabel();
        m_analyzeStatusLabel->setObjectName("SettingsSubtitle");
        rowLayout->addWidget(m_analyzeStatusLabel);
        AddSettingsRow(library, row);
    }

    {
        // Detect Genres: like Analyze above.
        auto* row = new QWidget();
        auto* rowLayout = new QVBoxLayout(row);
        rowLayout->setContentsMargins(16, 10, 16, 10);
        rowLayout->setSpacing(4);
        auto* top = new QHBoxLayout();
        m_genreButton = new QPushButton(tr("Detect Genres"));
        m_genreButton->setObjectName("LinkButton");
        m_genreButton->setCursor(Qt::PointingHandCursor);
        connect(m_genreButton, &QPushButton::clicked, this, &PlayerWindow::startGenreDetection);
        top->addWidget(m_genreButton);
        top->addStretch();
        m_genreStopButton = new QPushButton(tr("Stop"));
        m_genreStopButton->setObjectName("LinkButton");
        m_genreStopButton->setCursor(Qt::PointingHandCursor);
        connect(m_genreStopButton, &QPushButton::clicked, this, [this]() { m_genreAnalyzer->cancel(); });
        top->addWidget(m_genreStopButton);
        rowLayout->addLayout(top);
        m_genreProgress = new QProgressBar();
        m_genreProgress->setTextVisible(false);
        rowLayout->addWidget(m_genreProgress);
        m_genreStatusLabel = new QLabel();
        m_genreStatusLabel->setObjectName("SettingsSubtitle");
        rowLayout->addWidget(m_genreStatusLabel);
        AddSettingsRow(library, row);
    }
    chevronRow(library, tr("Find Duplicates"), [this]() { openDuplicates(); });
    AddSettingsFooter(l, tr(
        "Detect Genres listens to 30 seconds of each song on this computer (nothing is uploaded) and suggests a "
        "genre and subgenre, like Electronic · House. Use Fill Genre from analysis in the tag editor to save it "
        "to the song."));
    AddSettingsFooter(l, tr(
        "Analysis finds each song's tempo (BPM), key, and where its sound starts and ends, on this "
        "computer (nothing is uploaded). It unlocks Smart Options. Select a song in Tracks to edit its "
        "tags or change its cover."));

    // --- Smart Options -----------------------------------------------------
    SettingsCard smart = AddSettingsSection(l, tr("Smart Options"));
    {
        QLabel* lockIcon = nullptr;
        QLabel* lockText = nullptr;
        m_smartLockRow = MakeSettingsRow(tr("Run \"Analyze Your Songs' Key and BPM\" (above) to unlock these."),
                                         nullptr, &lockIcon, &lockText);
        lockText->setObjectName("SettingsValue");
        m_settingsIcons << qMakePair(lockIcon, theme::Icon::Lock);
        AddSettingsRow(smart, m_smartLockRow);
    }
    m_smartTransitionsSwitch = new ToggleSwitch();
    connect(m_smartTransitionsSwitch, &QAbstractButton::toggled, this, [this](bool on) {
        m_smartTransitions = on;
        if (!m_engine.isCrossfading())
        {
            m_queue.cancelPendingNext(); // trim points are worked out again for what plays next
        }
    });
    iconRow(smart, tr("Smart Transitions"), theme::Icon::Wand, m_smartTransitionsSwitch);

    m_beatMatchSwitch = new ToggleSwitch();
    connect(m_beatMatchSwitch, &QAbstractButton::toggled, this, [this](bool on) { m_beatMatchedCrossfade = on; });
    iconRow(smart, tr("Beat-Matched Crossfades"), theme::Icon::Metronome, m_beatMatchSwitch);

    m_harmonicShuffleSwitch = new ToggleSwitch();
    connect(m_harmonicShuffleSwitch, &QAbstractButton::toggled, this, [this](bool on) {
        m_harmonicShuffle = on;
        updateSmartOrder();
    });
    iconRow(smart, tr("Harmonic Shuffle"), theme::Icon::Dial, m_harmonicShuffleSwitch);
    AddSettingsFooter(l, tr(
        "Smart Transitions: songs from the same album play gapless, crossfades start where a song's ending "
        "actually fades out, and silence at the start and end is skipped. Beat-Matched Crossfades (needs "
        "Crossfade on): the next song's tempo is matched and its beats lined up during the fade, then it "
        "eases back to normal speed. Harmonic Shuffle: Smart shuffle plays songs in compatible keys and "
        "similar tempos next to each other. Songs that haven't been analyzed, or have no steady beat, use "
        "normal transitions."));

    // --- Appearance --------------------------------------------------------
    SettingsCard appearance = AddSettingsSection(l, tr("Appearance"));
    m_themeCombo = new QComboBox();
    m_themeCombo->addItems({tr("System"), tr("Light"), tr("Dark")});
    m_themeCombo->setMinimumWidth(130);
    connect(m_themeCombo, QOverload<int>::of(&QComboBox::currentIndexChanged), this, &PlayerWindow::setThemeMode);
    AddSettingsRow(appearance, MakeSettingsRow(tr("Theme"), m_themeCombo));

    m_languageCombo = new QComboBox();
    for (const LanguageOption& option : LanguageOptions())
    {
        // Each language under its own name, so it can be found whatever the current one is.
        m_languageCombo->addItem(option.code == QLatin1String("system") ? tr("System default") : option.nativeName,
                                 option.code);
    }
    m_languageCombo->setMinimumWidth(130);
    m_languageCombo->setCurrentIndex(qMax(0, m_languageCombo->findData(SavedLanguage())));
    connect(m_languageCombo, QOverload<int>::of(&QComboBox::currentIndexChanged), this, &PlayerWindow::onLanguageChosen);
    AddSettingsRow(appearance, MakeSettingsRow(tr("Language"), m_languageCombo));
    AddSettingsFooter(l, tr(
        "A new language is used after AudioForge restarts. System default follows the Windows display "
        "language, or English if it isn't one of these."));

    // --- Playback ----------------------------------------------------------
    SettingsCard playback = AddSettingsSection(l, tr("Playback"));
    m_replayGainSwitch = new ToggleSwitch();
    m_replayGainSwitch->setChecked(m_replayGainEnabled);
    connect(m_replayGainSwitch, &QAbstractButton::toggled, this, [this](bool on) {
        if (on != m_replayGainEnabled)
        {
            toggleReplayGain(); // keeps the player bar's ReplayGain pill in step
        }
    });
    AddSettingsRow(playback, MakeSettingsRow(tr("ReplayGain"), m_replayGainSwitch));

    m_crossfadeCheckbox = new ToggleSwitch();
    connect(m_crossfadeCheckbox, &QAbstractButton::toggled, this, [this](bool checked) {
        m_crossfadeEnabled = checked;
        m_crossfadeSlider->setEnabled(checked);
        m_crossfadeLabel->setEnabled(checked);
        if (!checked && m_engine.isCrossfading() && !m_gaplessHandOff)
        {
            abortCrossfade();
            applyVolume();
        }
        updateSmartOptionsUi(); // Beat-Matched Crossfades needs Crossfade on
    });
    AddSettingsRow(playback, MakeSettingsRow(tr("Crossfade"), m_crossfadeCheckbox));

    {
        auto* row = new QWidget();
        auto* rowLayout = new QVBoxLayout(row);
        rowLayout->setContentsMargins(16, 10, 16, 10);
        m_crossfadeLabel = new QLabel(tr("Crossfade length: %1 s").arg(5));
        rowLayout->addWidget(m_crossfadeLabel);
        m_crossfadeSlider = new QSlider(Qt::Horizontal);
        m_crossfadeSlider->setRange(2, 15);
        m_crossfadeSlider->setValue(5);
        m_crossfadeSlider->setFocusPolicy(Qt::NoFocus);
        connect(m_crossfadeSlider, &QSlider::valueChanged, this, [this](int value) {
            m_crossfadeSeconds = value;
            m_crossfadeLabel->setText(tr("Crossfade length: %1 s").arg(value));
        });
        rowLayout->addWidget(m_crossfadeSlider);
        AddSettingsRow(playback, row);
    }

    m_sleepFadeSwitch = new ToggleSwitch();
    m_sleepFadeSwitch->setChecked(m_sleepFadeOut);
    connect(m_sleepFadeSwitch, &QAbstractButton::toggled, this, [this](bool on) {
        m_sleepFadeOut = on;
        if (!on && m_sleepVolumeFactor < 1.0f)
        {
            m_sleepVolumeFactor = 1.0f;
            applyVolume();
        }
    });
    AddSettingsRow(playback, MakeSettingsRow(tr("Fade Out Sleep Timer"), m_sleepFadeSwitch));

    m_resumeLongSwitch = new ToggleSwitch();
    m_resumeLongSwitch->setChecked(m_rememberLongTracks);
    connect(m_resumeLongSwitch, &QAbstractButton::toggled, this, [this](bool on) { m_rememberLongTracks = on; });
    AddSettingsRow(playback, MakeSettingsRow(tr("Resume Long Tracks"), m_resumeLongSwitch));
    AddSettingsFooter(l, tr(
        "ReplayGain evens out loudness between songs using the gain stored in their tags. With crossfade "
        "off, songs play gapless. The sleep timer is on the Now Playing screen. Resume Long Tracks: "
        "audiobooks and mixes (10 minutes or longer) continue where you stopped."));

    // --- Windows -------------------------------------------------------------
    {
        SettingsCard windowsCard = AddSettingsSection(l, tr("Windows"));
        m_trayKeepSwitch = new ToggleSwitch();
        connect(m_trayKeepSwitch, &QAbstractButton::toggled, this, [this](bool on) { m_keepPlayingInTray = on; });
        AddSettingsRow(windowsCard, MakeSettingsRow(tr("Keep Playing in the Tray When Closed"), m_trayKeepSwitch));
        m_notifySwitch = new ToggleSwitch();
        connect(m_notifySwitch, &QAbstractButton::toggled, this, [this](bool on) { m_songNotifications = on; });
        AddSettingsRow(windowsCard, MakeSettingsRow(tr("Notify When a Song Starts"), m_notifySwitch));
        AddSettingsFooter(l, tr(
            "The song, its cover and the play buttons also show in Windows' media controls (the volume overlay "
            "and the lock screen), and the keyboard's media keys work while AudioForge is in the background. "
            "The taskbar preview has Previous / Play / Next. Notifications only show while you're in another app. "
            "With Keep Playing in the Tray on, closing the window keeps the music going; quit from the tray icon."));
    }

    // --- Video Wallpaper ---------------------------------------------------
    SettingsCard wallpaper = AddSettingsSection(l, tr("Video Wallpaper"));
    m_videoWallpaperCheckbox = new ToggleSwitch();
    connect(m_videoWallpaperCheckbox, &QAbstractButton::toggled, this, [this](bool checked) {
        m_videoWallpaperEnabled = checked;
        m_videoWallpaperOpacitySlider->setEnabled(checked); // dimming a wallpaper that's off is meaningless
        if (!m_queue.isEmpty())
        {
            // Re-resolve immediately rather than waiting for the next
            // track change -- handles both turning it off (clears the
            // video right away) and back on (shows it right away).
            updateWallpaperForTrack(m_queue.currentTrack());
        }
    });
    AddSettingsRow(wallpaper, MakeSettingsRow(tr("Show Video Wallpapers"), m_videoWallpaperCheckbox));

    {
        auto* row = new QWidget();
        auto* rowLayout = new QVBoxLayout(row);
        rowLayout->setContentsMargins(16, 10, 16, 10);
        auto* opacityLabel = new QLabel();
        rowLayout->addWidget(opacityLabel);
        m_videoWallpaperOpacitySlider = new QSlider(Qt::Horizontal);
        m_videoWallpaperOpacitySlider->setRange(0, 100);
        m_videoWallpaperOpacitySlider->setValue(m_videoWallpaperOpacityPercent);
        m_videoWallpaperOpacitySlider->setFocusPolicy(Qt::NoFocus);
        opacityLabel->setText(tr("Opacity: %1%").arg(m_videoWallpaperOpacityPercent));
        connect(m_videoWallpaperOpacitySlider, &QSlider::valueChanged, this, [this, opacityLabel](int value) {
            m_videoWallpaperOpacityPercent = value;
            m_wallpaperWidget->setOpacity(value / 100.0);
            opacityLabel->setText(tr("Opacity: %1%").arg(value));
        });
        rowLayout->addWidget(m_videoWallpaperOpacitySlider);
        AddSettingsRow(wallpaper, row);
    }

    // --- Visualizer ----------------------------------------------------------
    SettingsCard visualizer = AddSettingsSection(l, tr("Visualizer"));
    {
        // Every style as a live preview, like the Android app; click one to use it.
        m_visualizerStyleGrid = new VisualizerStyleGrid(&m_engine.visualizer());
        connect(m_visualizerStyleGrid, &VisualizerStyleGrid::styleChosen, this, [this](int index) {
            m_visualizerStyle = qBound(0, index, kVisualizerStyleCount - 1);
            m_visualizer->setStyle(static_cast<VisualizerStyle>(m_visualizerStyle));
        });
        auto* row = new QWidget();
        auto* rowLayout = new QVBoxLayout(row);
        rowLayout->setContentsMargins(16, 14, 16, 10);
        rowLayout->addWidget(m_visualizerStyleGrid);
        AddSettingsRow(visualizer, row);
    }
    chevronRow(visualizer, tr("Custom GIFs and Videos"), [this]() { openVisualizerMediaDialog(); });
    AddSettingsFooter(l, tr(
        "Shows in place of the cover when you click the visualizer button on Now Playing. Colours follow the "
        "song's cover. A GIF or looping video can show instead, for every song or for chosen songs."));

    // --- Lyrics Lookup -----------------------------------------------------
    SettingsCard lyrics = AddSettingsSection(l, tr("Lyrics Lookup"));
    {
        auto* row = new QWidget();
        auto* rowLayout = new QHBoxLayout(row);
        rowLayout->setContentsMargins(16, 10, 16, 10);
        m_musixmatchApiKeyEdit = new QLineEdit();
        m_musixmatchApiKeyEdit->setEchoMode(QLineEdit::Password);
        m_musixmatchApiKeyEdit->setPlaceholderText(tr("Musixmatch API key (optional)"));
        connect(m_musixmatchApiKeyEdit, &QLineEdit::textChanged, this, [this](const QString& text) {
            m_musixmatchApiKey = text.trimmed();
        });
        rowLayout->addWidget(m_musixmatchApiKeyEdit);
        AddSettingsRow(lyrics, row);
    }
    AddSettingsFooter(l, tr(
        "Lyrics are looked up on LRCLIB (free, no key), then Musixmatch if you add an API key (free-tier "
        "keys only return a snippet), then a same-name .lrc or .txt file next to the song. The key is "
        "stored encrypted for your Windows account."));

    // --- Discord Rich Presence --------------------------------------------------
    SettingsCard discord = AddSettingsSection(l, tr("Discord Rich Presence"));
    m_discordPresenceCheckbox = new ToggleSwitch();
    connect(m_discordPresenceCheckbox, &QAbstractButton::toggled, this, [this](bool checked) {
        m_discordPresenceEnabled = checked;
        m_discordPresence.setEnabled(checked);
    });
    AddSettingsRow(discord, MakeSettingsRow(tr("Show what I'm playing on Discord"), m_discordPresenceCheckbox));
    {
        auto* row = new QWidget();
        auto* rowLayout = new QHBoxLayout(row);
        rowLayout->setContentsMargins(16, 10, 16, 10);
        m_discordClientIdEdit = new QLineEdit();
        m_discordClientIdEdit->setPlaceholderText(tr("Discord Application Client ID"));
        connect(m_discordClientIdEdit, &QLineEdit::textChanged, this, [this](const QString& text) {
            m_discordClientId = text.trimmed();
            m_discordPresence.init(m_discordClientId);
        });
        rowLayout->addWidget(m_discordClientIdEdit);
        AddSettingsRow(discord, row);
    }
    AddSettingsFooter(l, tr(
        "Needs a free \"Application\" client ID from discord.com/developers/applications (create one and "
        "copy its Client ID -- nothing needs to be published). Discord's desktop app must be running."));

    // --- Visible Tabs ------------------------------------------------------
    SettingsCard tabs = AddSettingsSection(l, tr("Visible Tabs"));
    // The library tabs (0-4) and Podcasts (8) can be hidden; Equalizer,
    // Wallpapers and Settings always stay. (Built while Settings is added,
    // so the five before it exist; Podcasts comes after it, so its row is
    // filled in by name.) m_tabVisibilityCheckboxes[5] is Podcasts' switch.
    for (int tabIndex : {0, 1, 2, 3, 4, 8})
    {
        auto* toggle = new ToggleSwitch();
        toggle->setChecked(true);
        m_tabVisibilityCheckboxes << toggle; // persisted by saveSettings()
        connect(toggle, &QAbstractButton::toggled, this, [this, tabIndex](bool checked) {
            m_tabs->setTabVisible(tabIndex, checked); // also moves the current tab off a hidden one
            m_navButtons[tabIndex]->setVisible(checked);
        });
        AddSettingsRow(tabs, MakeSettingsRow(tabIndex == 8 ? tr("Podcasts") : m_tabs->tabText(tabIndex), toggle));
    }

    // --- About -------------------------------------------------------------
    SettingsCard about = AddSettingsSection(l, tr("About"));
    {
        // Version: click to check for updates; the result shows underneath.
        auto* row = new QWidget();
        row->setMinimumHeight(46);
        auto* rowLayout = new QVBoxLayout(row);
        rowLayout->setContentsMargins(16, 8, 16, 8);
        rowLayout->setSpacing(2);
        auto* top = new QHBoxLayout();
        m_versionButton = new QPushButton(tr("Version"));
        m_versionButton->setObjectName("RowButton");
        m_versionButton->setCursor(Qt::PointingHandCursor);
        m_versionButton->setToolTip(tr("Check for updates"));
        connect(m_versionButton, &QPushButton::clicked, m_updateChecker, &UpdateChecker::check);
        top->addWidget(m_versionButton, 1);
        auto* versionValue = MakeValueLabel();
        versionValue->setText(app::Version());
        top->addWidget(versionValue);
        rowLayout->addLayout(top);
        auto* status = new QHBoxLayout();
        m_versionStatusLabel = new QLabel();
        m_versionStatusLabel->setObjectName("SettingsSubtitle");
        m_versionStatusLabel->setWordWrap(true);
        status->addWidget(m_versionStatusLabel, 1);
        m_downloadUpdateButton = new QPushButton(tr("Open Download Page"));
        m_downloadUpdateButton->setObjectName("LinkButton");
        m_downloadUpdateButton->setCursor(Qt::PointingHandCursor);
        connect(m_downloadUpdateButton, &QPushButton::clicked, this, [this]() {
            QDesktopServices::openUrl(QUrl(m_updateChecker->releaseUrl()));
        });
        status->addWidget(m_downloadUpdateButton);
        rowLayout->addLayout(status);
        AddSettingsRow(about, row);
    }
    connect(m_updateChecker, &UpdateChecker::stateChanged, this, &PlayerWindow::updateVersionUi);
    connect(m_updateChecker, &UpdateChecker::updateNoticed, this, &PlayerWindow::showUpdateNotice);
    updateVersionUi();

    m_autoUpdateSwitch = new ToggleSwitch();
    m_autoUpdateSwitch->setChecked(m_updateChecker->autoCheckEnabled());
    connect(m_autoUpdateSwitch, &QAbstractButton::toggled, m_updateChecker, &UpdateChecker::setAutoCheckEnabled);
    AddSettingsRow(about, MakeSettingsRow(tr("Check for Updates Automatically"), m_autoUpdateSwitch));

    chevronRow(about, tr("Report a Problem"), [this]() { reportProblem(); });
    chevronRow(about, tr("Source Code on GitHub"), []() { QDesktopServices::openUrl(QUrl(QString::fromLatin1(app::kRepoUrl))); });
    chevronRow(about, tr("Terms & Conditions"), [this]() {
        openTextPage(tr("Terms & Conditions"), ReadLocalizedText(":/about/terms"));
    });
    chevronRow(about, tr("Privacy"), [this]() { openTextPage(tr("Privacy"), ReadLocalizedText(":/about/privacy")); });
    chevronRow(about, tr("Acknowledgements"), [this]() {
        openTextPage(tr("Acknowledgements"), acknowledgementsMarkdown());
    });
    AddSettingsFooter(l, tr(
        "Click Version to see if a newer AudioForge for Windows is on GitHub. The automatic check runs at "
        "most once a day. Only the newest version number is read: nothing is downloaded or installed, a "
        "new version opens its download page in your browser."));

    l->addStretch();
    return scroll;
}

void PlayerWindow::onLanguageChosen(int index)
{
    const QString code = m_languageCombo->itemData(index).toString();
    if (code.isEmpty() || code == SavedLanguage())
    {
        return;
    }
    SaveLanguage(code);
    if (ResolveLanguage(code) == ActiveLanguage())
    {
        return; // e.g. "System default" when Windows is already in the language shown
    }

    QMessageBox box(QMessageBox::Question, tr("Restart AudioForge?"),
                    tr("AudioForge needs to restart to change the language. Your queue and where you were "
                       "in the song are kept."),
                    QMessageBox::NoButton, this);
    QPushButton* restart = box.addButton(tr("Restart Now"), QMessageBox::AcceptRole);
    box.addButton(tr("Later"), QMessageBox::RejectRole);
    box.setDefaultButton(restart);
    box.exec();
    if (box.clickedButton() == restart)
    {
        restartApp();
    }
}

void PlayerWindow::restartApp()
{
    close(); // closeEvent saves the queue, library, settings and wallpapers -- same as quitting
    if (!QProcess::startDetached(QCoreApplication::applicationFilePath(), QCoreApplication::arguments().mid(1)))
    {
        ErrorReporter::logOnly("Couldn't restart: " + QCoreApplication::applicationFilePath());
    }
    qApp->quit();
}

void PlayerWindow::updateVersionUi()
{
    using State = UpdateChecker::State;
    const State state = m_updateChecker->state();
    QString status;
    switch (state)
    {
        case State::Idle:      break;
        case State::Checking:  status = tr("Checking for updates…"); break;
        case State::UpToDate:  status = tr("You have the latest version."); break;
        case State::Available: status = tr("Version %1 is available.").arg(m_updateChecker->availableVersion()); break;
        case State::Failed:    status = tr("Couldn't check for updates. Check your internet connection and try again."); break;
    }
    m_versionStatusLabel->setText(status);
    m_versionStatusLabel->setVisible(!status.isEmpty());
    m_downloadUpdateButton->setVisible(state == State::Available);
    m_versionButton->setEnabled(state != State::Checking);
}

void PlayerWindow::showUpdateNotice(const QString& version, const QString& url)
{
    QMessageBox box(QMessageBox::Information, tr("Update available"),
                    tr("AudioForge %1 is available. You have %2.").arg(version, app::Version()),
                    QMessageBox::NoButton, this);
    QPushButton* open = box.addButton(tr("Open Download Page"), QMessageBox::AcceptRole);
    box.addButton(tr("Later"), QMessageBox::RejectRole);
    box.setDefaultButton(open);
    box.exec();
    if (box.clickedButton() == open)
    {
        QDesktopServices::openUrl(QUrl(url));
    }
}

void PlayerWindow::reportProblem()
{
    // The subject and the details stay in English so every report can be
    // read; only versions are filled in (no logs, file names or library data).
    const QString body = tr("Describe the problem here:") + QStringLiteral("\n\n\n---\n")
        + QString("App: AudioForge %1 for Windows\n").arg(app::Version())
        + QString("Windows: %1 (%2)\n").arg(QSysInfo::prettyProductName(), QSysInfo::kernelVersion())
        + QString("Language: %1 (Windows: %2)\n").arg(ActiveLanguage(), QLocale::system().name());
    QUrlQuery query;
    query.addQueryItem("subject", QStringLiteral("AudioForge Windows – problem report"));
    query.addQueryItem("body", body);
    const QString email = QString::fromLatin1(app::kSupportEmail);
    QUrl url(QStringLiteral("mailto:") + email);
    url.setQuery(query);
    if (!QDesktopServices::openUrl(url))
    {
        QGuiApplication::clipboard()->setText(email);
        showToast(tr("No email app found. The address was copied: %1").arg(email));
    }
}

void PlayerWindow::openTextPage(const QString& title, const QString& markdown)
{
    TextPageDialog dialog(title, markdown, this);
    dialog.exec();
}

QString PlayerWindow::acknowledgementsMarkdown() const
{
    // Kept by hand: update it whenever CMakeLists.txt, vendor/ or the
    // bundled fonts change.
    struct Entry
    {
        const char* name;
        const char* url;
        const char* license;    // empty for an online service
        const char* licenseUrl;
    };
    static const Entry libraries[] = {
        {"Qt 6", "https://www.qt.io", "GNU LGPL 3.0", "https://www.gnu.org/licenses/lgpl-3.0.html"},
        {"TagLib", "https://taglib.org", "GNU LGPL 2.1 / MPL 1.1", "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html"},
        {"miniaudio", "https://miniaud.io", "Public domain (Unlicense) / MIT No Attribution", "https://github.com/mackron/miniaudio/blob/master/LICENSE"},
        // MSYS2's FFmpeg (bundled with Qt Multimedia) is built with GPL parts; it reports "GPL version 3 or later".
        {"FFmpeg", "https://ffmpeg.org", "GNU GPL 3.0+", "https://ffmpeg.org/legal.html"},
        {"ONNX Runtime", "https://onnxruntime.ai", "MIT License", "https://github.com/microsoft/onnxruntime/blob/main/LICENSE"},
        {"YAMNet", "https://github.com/tensorflow/models/tree/master/research/audioset/yamnet", "Apache License 2.0",
         "https://www.apache.org/licenses/LICENSE-2.0"},
    };
    static const Entry fonts[] = {
        {"Inter", "https://rsms.me/inter/", "SIL Open Font License 1.1", "https://openfontlicense.org"},
        {"Playfair Display", "https://github.com/clauseggers/Playfair", "SIL Open Font License 1.1", "https://openfontlicense.org"},
    };
    static const Entry services[] = {
        {"MusicBrainz", "https://musicbrainz.org", "", ""},
        {"LRCLIB", "https://lrclib.net", "", ""},
        {"Musixmatch", "https://www.musixmatch.com", "", ""},
        {"GitHub", "https://github.com", "", ""},
        {"Discord", "https://discord.com", "", ""},
    };
    auto list = [](const Entry* begin, const Entry* end) {
        QString text;
        for (const Entry* e = begin; e != end; ++e)
        {
            text += QString("- [%1](%2)").arg(QString::fromUtf8(e->name), QString::fromUtf8(e->url));
            if (*e->license)
            {
                text += QString(" — [%1](%2)").arg(QString::fromUtf8(e->license), QString::fromUtf8(e->licenseUrl));
            }
            text += '\n';
        }
        return text;
    };
    return tr("AudioForge is built with these libraries, fonts and services. Thank you to everyone who works on them.")
        + "\n\n## " + tr("Libraries") + "\n\n" + list(std::begin(libraries), std::end(libraries))
        + "\n" + tr("FFmpeg plays the video wallpapers, through Qt Multimedia.") + " "
        + tr("ONNX Runtime and Google's YAMNet model detect genres, on this computer.") + "\n"
        + "\n## " + tr("Fonts") + "\n\n" + list(std::begin(fonts), std::end(fonts))
        + "\n## " + tr("Online services") + "\n\n" + list(std::begin(services), std::end(services))
        + "\n" + tr("Each licence's full text is on the page linked next to it.") + "\n";
}

QWidget* PlayerWindow::buildWallpapersTab()
{
    auto* w = new QWidget();
    auto* l = new QVBoxLayout(w);

    // --- Global wallpaper ---
    l->addWidget(sectionHeader(tr("Global Wallpaper")));
    l->addWidget(new QLabel(tr("Used whenever the currently playing track has no wallpaper of its own.")));

    m_globalWallpaperLabel = new QLabel();
    l->addWidget(m_globalWallpaperLabel);

    auto* globalButtonsRow = new QHBoxLayout();
    auto* chooseGlobalButton = new QPushButton(tr("Choose Video..."));
    connect(chooseGlobalButton, &QPushButton::clicked, this, &PlayerWindow::chooseGlobalWallpaper);
    globalButtonsRow->addWidget(chooseGlobalButton);

    auto* clearGlobalButton = new QPushButton(tr("Clear"));
    connect(clearGlobalButton, &QPushButton::clicked, this, &PlayerWindow::clearGlobalWallpaper);
    globalButtonsRow->addWidget(clearGlobalButton);
    globalButtonsRow->addStretch();
    l->addLayout(globalButtonsRow);

    l->addSpacing(16);

    // --- Per-track wallpapers ---
    l->addWidget(sectionHeader(tr("Per-Track Wallpapers")));

    m_wallpaperEntriesList = new QListWidget();
    l->addWidget(m_wallpaperEntriesList, 1);

    auto* entryButtonsRow = new QHBoxLayout();
    auto* addEntryButton = new QPushButton(tr("Add Wallpaper..."));
    connect(addEntryButton, &QPushButton::clicked, this, &PlayerWindow::addWallpaperEntry);
    entryButtonsRow->addWidget(addEntryButton);

    auto* editTracksButton = new QPushButton(tr("Edit Tracks..."));
    connect(editTracksButton, &QPushButton::clicked, this, &PlayerWindow::editWallpaperEntryTracks);
    entryButtonsRow->addWidget(editTracksButton);

    auto* removeEntryButton = new QPushButton(tr("Remove"));
    connect(removeEntryButton, &QPushButton::clicked, this, &PlayerWindow::removeSelectedWallpaperEntry);
    entryButtonsRow->addWidget(removeEntryButton);
    l->addLayout(entryButtonsRow);

    refreshGlobalWallpaperLabel();
    refreshWallpaperEntriesList();

    return w;
}

namespace {

// Approximate standard 10-band graphic EQ curves (dB, one entry per band:
// 31/62/125/250/500/1K/2K/4K/8K/16K) -- the usual genre shapes, not
// measured/scientific values. Defined as a lazily-built static local so it
// costs nothing until the Equalizer tab is actually opened.
const QVector<QPair<QString, QVector<float>>>& EqPresets()
{
    static const QVector<QPair<QString, QVector<float>>> presets = {
        {QT_TR_NOOP("Rock"),         {5, 4, 3, 0, -2, -1, 0, 2, 3, 4}},
        {QT_TR_NOOP("Pop"),          {-1, 1, 3, 4, 3, 0, -1, -1, -1, -1}},
        {QT_TR_NOOP("Jazz"),         {3, 2, 1, 2, -1, -1, 0, 1, 2, 3}},
        {QT_TR_NOOP("Classical"),    {4, 3, 2, 1, -1, -2, -2, 0, 2, 3}},
        {QT_TR_NOOP("Dance"),        {6, 5, 2, 0, 0, -3, -3, 0, 3, 4}},
        {QT_TR_NOOP("Hip-Hop"),      {6, 5, 3, 1, -1, -1, 0, 1, 2, 3}},
        {QT_TR_NOOP("Blues"),        {3, 2, 0, -1, 0, 1, 2, 2, 1, 1}},
        {QT_TR_NOOP("Vocal Boost"),  {-2, -2, -1, 1, 3, 4, 3, 1, 0, -1}},
    };
    return presets;
}

} // namespace

QWidget* PlayerWindow::buildEqualizerTab()
{
    auto* scroll = new QScrollArea();
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    scroll->setWidget(buildEqualizerSection());
    return scroll;
}

QWidget* PlayerWindow::buildEqualizerSection()
{
    auto* section = new QWidget();
    auto* sectionLayout = new QVBoxLayout(section);
    sectionLayout->setContentsMargins(16, 16, 16, 16);

    auto* headerRow = new QHBoxLayout();
    headerRow->addWidget(sectionHeader(tr("Equalizer")));
    headerRow->addStretch();
    m_eqEnabledCheckbox = new QCheckBox(tr("Enabled"));
    connect(m_eqEnabledCheckbox, &QCheckBox::toggled, this, &PlayerWindow::onEqEnabledToggled);
    headerRow->addWidget(m_eqEnabledCheckbox);
    sectionLayout->addLayout(headerRow);

    if (!m_engine.isEqualizerAvailable())
    {
        sectionLayout->addWidget(new QLabel(tr("Equalizer unavailable -- the audio engine couldn't set it up.")));
        m_eqEnabledCheckbox->setEnabled(false);
        sectionLayout->addStretch();
        return section;
    }

    sectionLayout->addSpacing(12);

    auto* bandsRow = new QHBoxLayout();
    bandsRow->setSpacing(24);
    for (int i = 0; i < AudioEngine::kEqBandCount; ++i)
    {
        auto* bandColumn = new QVBoxLayout();
        bandColumn->setSpacing(8);

        // dB readout in its own row above the slider -- floating it over
        // the handle put the text right on top of the slider's track line.
        auto* valueLabel = new QLabel("0.0");
        valueLabel->setObjectName("EqValue");
        valueLabel->setAlignment(Qt::AlignCenter);
        valueLabel->setMinimumWidth(40);
        m_eqBandValueLabels << valueLabel;
        bandColumn->addWidget(valueLabel, 0, Qt::AlignHCenter);

        auto* slider = new QSlider(Qt::Vertical);
        slider->setRange(-150, 150); // tenths of a dB: -15.0..+15.0
        slider->setValue(0);
        slider->setMinimumHeight(220);
        int bandIndex = i;
        connect(slider, &QSlider::valueChanged, this, [this, bandIndex](int value) { onEqBandChanged(bandIndex, value); });
        m_eqBandSliders << slider;

        bandColumn->addWidget(slider, 1, Qt::AlignHCenter);

        float freq = AudioEngine::equalizerBandFrequency(i);
        QString freqText = freq >= 1000.0f ? QString("%1K").arg(freq / 1000.0f, 0, 'g', 2) : QString::number(static_cast<int>(freq));
        auto* freqLabel = new QLabel(freqText);
        freqLabel->setObjectName("MutedLabel");
        freqLabel->setAlignment(Qt::AlignCenter);
        bandColumn->addWidget(freqLabel);

        bandsRow->addLayout(bandColumn);
    }
    sectionLayout->addLayout(bandsRow);

    sectionLayout->addSpacing(16);

    auto* presetRow = new QHBoxLayout();
    presetRow->addWidget(new QLabel(tr("Preset")));
    m_eqPresetCombo = new QComboBox();
    m_eqPresetCombo->addItem(tr("Custom"));
    for (const auto& preset : EqPresets())
    {
        m_eqPresetCombo->addItem(tr(preset.first.toUtf8().constData()));
    }
    connect(m_eqPresetCombo, QOverload<int>::of(&QComboBox::currentIndexChanged), this, &PlayerWindow::applyEqPreset);
    presetRow->addWidget(m_eqPresetCombo, 1);
    sectionLayout->addLayout(presetRow);

    sectionLayout->addSpacing(16);

    auto* postGainRow = new QHBoxLayout();
    postGainRow->addWidget(new QLabel(tr("Post-gain")));
    m_eqPostGainValueLabel = new QLabel("0.0dB");
    postGainRow->addWidget(m_eqPostGainValueLabel);
    sectionLayout->addLayout(postGainRow);

    m_eqPostGainSlider = new QSlider(Qt::Horizontal);
    m_eqPostGainSlider->setRange(-150, 150);
    m_eqPostGainSlider->setValue(0);
    connect(m_eqPostGainSlider, &QSlider::valueChanged, this, &PlayerWindow::onEqPostGainChanged);
    sectionLayout->addWidget(m_eqPostGainSlider);

    auto* resetButton = new QPushButton(tr("Reset Equalizer"));
    connect(resetButton, &QPushButton::clicked, this, &PlayerWindow::resetEqualizer);
    sectionLayout->addWidget(resetButton);

    sectionLayout->addStretch();

    return section;
}

void PlayerWindow::applyEqPreset(int index)
{
    const auto& presets = EqPresets();
    if (index <= 0 || index > presets.size())
    {
        return; // index 0 is "Custom" -- nothing to apply
    }

    const QVector<float>& gains = presets[index - 1].second;
    for (int i = 0; i < m_eqBandSliders.size() && i < gains.size(); ++i)
    {
        m_eqBandSliders[i]->setValue(static_cast<int>(gains[i] * 10.0f)); // fires onEqBandChanged
    }
}

void PlayerWindow::onEqEnabledToggled(bool checked)
{
    m_engine.setEqualizerEnabled(checked);
    for (QSlider* slider : m_eqBandSliders)
    {
        slider->setEnabled(checked);
    }
    if (m_eqPostGainSlider)
    {
        m_eqPostGainSlider->setEnabled(checked);
    }
}

void PlayerWindow::onEqBandChanged(int bandIndex, int sliderValue)
{
    float gainDb = sliderValue / 10.0f;
    m_engine.setEqualizerBandGain(bandIndex, gainDb);
    m_eqBandValueLabels[bandIndex]->setText(QString("%1%2").arg(gainDb > 0.0f ? "+" : "").arg(gainDb, 0, 'f', 1));
}

void PlayerWindow::onEqPostGainChanged(int sliderValue)
{
    m_eqPostGainDb = sliderValue / 10.0f;
    m_eqPostGainValueLabel->setText(QString("%1dB").arg(m_eqPostGainDb, 0, 'f', 1));
    applyVolume(); // post-gain is folded into the same volume math as ReplayGain -- see baseVolumeFor()
}

void PlayerWindow::resetEqualizer()
{
    if (m_eqPresetCombo)
    {
        m_eqPresetCombo->setCurrentIndex(0); // "Custom" -- doesn't itself change any gains
    }
    for (QSlider* slider : m_eqBandSliders)
    {
        slider->setValue(0); // triggers onEqBandChanged, which zeroes the engine + label
    }
    if (m_eqPostGainSlider)
    {
        m_eqPostGainSlider->setValue(0); // triggers onEqPostGainChanged
    }
}

QLabel* PlayerWindow::sectionHeader(const QString& text)
{
    auto* label = new QLabel(text);
    QFont f = label->font();
    f.setBold(true);
    f.setPointSize(f.pointSize() + 1);
    label->setFont(f);
    return label;
}

QWidget* PlayerWindow::buildSidebar()
{
    auto* sidebar = new QFrame();
    sidebar->setObjectName("Sidebar");
    sidebar->setFixedWidth(230); // 72 when compact -- see setSidebarCompact()
    m_sidebar = sidebar;

    auto* l = new QVBoxLayout(sidebar);
    m_sidebarLayout = l;
    l->setContentsMargins(16, 26, 16, 16);
    l->setSpacing(2);

    auto* brand = new QLabel(QString::fromUtf8("✦  AUDIOFORGE"));
    brand->setObjectName("BrandLabel");
    QFont brandFont = brand->font();
    brandFont.setLetterSpacing(QFont::AbsoluteSpacing, 3);
    brand->setFont(brandFont);
    brand->setContentsMargins(10, 0, 0, 0);
    l->addWidget(brand);
    m_brandLabel = brand;
    l->addSpacing(26);

    // One nav button per m_tabs page (labelled with the tab's own name),
    // indexed by tab index so the Settings tab's visibility switches and
    // onTabChanged() can address them directly.
    auto* group = new QButtonGroup(sidebar);
    group->setExclusive(true);
    for (int i = 0; i < m_tabs->count(); ++i)
    {
        auto* button = new QPushButton(QStringLiteral("  ") + m_tabs->tabText(i));
        button->setToolTip(m_tabs->tabText(i)); // the only label once the sidebar is compact
        button->setObjectName("NavButton");
        button->setCheckable(true);
        button->setIconSize(QSize(18, 18));
        button->setCursor(Qt::PointingHandCursor);
        button->setFocusPolicy(Qt::NoFocus);
        group->addButton(button, i);
        m_navButtons << button;
    }
    connect(group, &QButtonGroup::idClicked, this, [this](int index) { m_tabs->setCurrentIndex(index); });

    // Library views first (Tracks leads, like most players), tools after.
    for (int index : {1, 0, 2, 4, 3, 8})
    {
        l->addWidget(m_navButtons[index]);
    }

    m_playlistsSection = new QWidget(); // caption + list, hidden in the compact sidebar
    auto* playlistsLayout = new QVBoxLayout(m_playlistsSection);
    playlistsLayout->setContentsMargins(0, 22, 0, 0);
    playlistsLayout->setSpacing(4);
    auto* playlistsHeader = new QHBoxLayout();
    auto* playlistsCaption = new QLabel(tr("YOUR PLAYLISTS"));
    playlistsCaption->setObjectName("SectionCaption");
    QFont captionFont = playlistsCaption->font();
    captionFont.setLetterSpacing(QFont::AbsoluteSpacing, 1.5);
    playlistsCaption->setFont(captionFont);
    playlistsCaption->setContentsMargins(10, 0, 0, 0);
    playlistsHeader->addWidget(playlistsCaption);
    playlistsHeader->addStretch();
    m_newPlaylistButton = MakeIconButton(26, 14, tr("New playlist"));
    connect(m_newPlaylistButton, &QPushButton::clicked, this, [this]() { showNewPlaylistMenu(m_newPlaylistButton); });
    playlistsHeader->addWidget(m_newPlaylistButton);
    playlistsLayout->addLayout(playlistsHeader);

    m_sidebarPlaylistsList = new QListWidget();
    m_sidebarPlaylistsList->setObjectName("SidebarList");
    m_sidebarPlaylistsList->setSelectionMode(QAbstractItemView::NoSelection);
    m_sidebarPlaylistsList->setFocusPolicy(Qt::NoFocus);
    m_sidebarPlaylistsList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_sidebarPlaylistsList->setCursor(Qt::PointingHandCursor);
    connect(m_sidebarPlaylistsList, &QListWidget::itemClicked, this, &PlayerWindow::activatePlaylistItem);
    enableTracksMenu(m_sidebarPlaylistsList, /*playlistRows=*/true);

    playlistsLayout->addWidget(m_sidebarPlaylistsList, 1);
    l->addWidget(m_playlistsSection, 1);
    l->addStretch(0); // takes the playlists' room while they're hidden (compact)
    m_sidebarSpacerIndex = l->count() - 1;

    l->addSpacing(10);
    for (int index : {5, 6, 7})
    {
        l->addWidget(m_navButtons[index]);
    }

    m_openButton = new QPushButton(QStringLiteral("  ") + tr("Open File..."));
    m_openButton->setObjectName("NavButton");
    m_openButton->setIconSize(QSize(18, 18));
    m_openButton->setCursor(Qt::PointingHandCursor);
    m_openButton->setFocusPolicy(Qt::NoFocus);
    connect(m_openButton, &QPushButton::clicked, this, &PlayerWindow::openFile);
    l->addWidget(m_openButton);

    m_openButton->setToolTip(tr("Open File..."));
    return sidebar;
}

// Small screens (a 1366x768 laptop at 125% scaling is ~1090 px wide): the
// sidebar shrinks to an icon rail, which is what lets the window fit.
void PlayerWindow::setSidebarCompact(bool compact)
{
    if (compact == m_sidebarCompact)
    {
        return;
    }
    m_sidebarCompact = compact;
    for (int i = 0; i < m_navButtons.size(); ++i)
    {
        m_navButtons[i]->setText(compact ? QString() : QStringLiteral("  ") + m_tabs->tabText(i));
    }
    m_openButton->setText(compact ? QString() : QStringLiteral("  ") + tr("Open File..."));
    m_brandLabel->setText(compact ? QString::fromUtf8("\u2726") : QString::fromUtf8("\u2726  AUDIOFORGE"));
    m_brandLabel->setAlignment(compact ? Qt::AlignHCenter : Qt::AlignLeft);
    m_brandLabel->setContentsMargins(compact ? 0 : 10, 0, 0, 0);
    m_playlistsSection->setVisible(!compact);
    m_sidebarLayout->setStretch(m_sidebarSpacerIndex, compact ? 1 : 0);
    m_sidebarLayout->setContentsMargins(compact ? 12 : 16, 26, compact ? 12 : 16, 16);
    m_sidebar->setFixedWidth(compact ? 72 : 230);
}

void PlayerWindow::onTabChanged(int index)
{
    if (index >= 0 && index < m_navButtons.size())
    {
        m_navButtons[index]->setChecked(true);
    }
    m_pageTitleLabel->setText(m_tabs->tabText(index));
    if (index == 8)
    {
        refreshPodcasts(); // played marks and "left" times move on while it's hidden
    }
}

QWidget* PlayerWindow::buildPlayerBar()
{
    auto* bar = new QFrame();
    bar->setObjectName("PlayerBar");
    auto* outerLayout = new QHBoxLayout(bar);
    outerLayout->setContentsMargins(12, 10, 18, 10);
    outerLayout->setSpacing(18);

    // Left and right blocks share one fixed width so the transport column
    // between them stays centered in the bar.
    static constexpr int kSideWidth = 280;

    // Clicking the cover/title area opens the full Now Playing page.
    auto* clickableInfo = new ClickableWidget();
    clickableInfo->setMinimumWidth(180); // shrinks on small screens, never wider than kSideWidth
    clickableInfo->setMaximumWidth(kSideWidth);
    clickableInfo->setCursor(Qt::PointingHandCursor);
    clickableInfo->setToolTip(tr("Open Now Playing"));
    connect(clickableInfo, &ClickableWidget::clicked, this, [this]() { m_stack->setCurrentIndex(1); });
    auto* infoLayout = new QHBoxLayout(clickableInfo);
    infoLayout->setContentsMargins(0, 0, 0, 0);

    m_coverArtLabel = new QLabel();
    m_coverArtLabel->setObjectName("CoverArt");
    m_coverArtLabel->setFixedSize(56, 56);
    m_coverArtLabel->setScaledContents(false); // pixmap is pre-cropped to exact label size (see ScaledCoverArt)
    infoLayout->addWidget(m_coverArtLabel);
    infoLayout->addSpacing(10);

    auto* infoTextLayout = new QVBoxLayout();
    infoTextLayout->setSpacing(2);
    infoTextLayout->addStretch();
    infoLayout->addLayout(infoTextLayout, 1);

    m_titleLabel = new QLabel(tr("No file loaded"));
    m_titleLabel->setObjectName("MiniTitle");
    m_titleLabel->setMinimumWidth(0);
    m_titleLabel->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred); // eliding handles overflow; don't let a long title widen the bar
    infoTextLayout->addWidget(m_titleLabel);

    m_formatLabel = new QLabel();
    m_formatLabel->setObjectName("MutedLabel");
    m_formatLabel->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
    // Reserve a full line's height right away -- a genuinely empty QLabel
    // has a near-zero sizeHint, so without this the bar's required height
    // silently jumps by one line the first time a track loads and
    // FormatAudioInfo() gives it real text for the first time. That jump
    // happened after bar->setMinimumHeight() below had already measured
    // (and locked in) the smaller, empty-label height, so nothing pushed
    // back against it until some unrelated relayout (fullscreen toggle,
    // switching pages) caught the window up.
    m_formatLabel->setMinimumHeight(m_formatLabel->fontMetrics().height());
    infoTextLayout->addWidget(m_formatLabel);
    infoTextLayout->addStretch();

    outerLayout->addWidget(clickableInfo, 2); // sides share space evenly up to kSideWidth; the middle takes the rest

    // Center: transport buttons over the seek bar.
    auto* layout = new QVBoxLayout();
    layout->setSpacing(4);
    outerLayout->addLayout(layout, 5);

    auto* transportRow = new QHBoxLayout();
    transportRow->setSpacing(10);
    m_shuffleButton = MakeIconButton(36, 18, tr("Shuffle: Off"));
    m_shuffleButton->setCheckable(true);
    m_prevButton = MakeIconButton(36, 20, tr("Previous"));
    m_playButton = MakeIconButton(44, 20, tr("Play"));
    m_playButton->setObjectName("PlayButton");
    m_nextButton = MakeIconButton(36, 20, tr("Next"));
    m_repeatButton = MakeIconButton(36, 18, tr("Repeat: Off"));
    m_repeatButton->setCheckable(true);
    transportRow->addStretch();
    transportRow->addWidget(m_shuffleButton);
    transportRow->addWidget(m_prevButton);
    transportRow->addWidget(m_playButton);
    transportRow->addWidget(m_nextButton);
    transportRow->addWidget(m_repeatButton);
    transportRow->addStretch();
    layout->addLayout(transportRow);

    auto* seekRow = new QHBoxLayout();
    seekRow->setSpacing(10);
    m_timeLabel = new QLabel("0:00");
    m_timeLabel->setObjectName("TimeLabel");
    m_timeLabel->setFixedWidth(40);
    m_timeLabel->setAlignment(Qt::AlignRight | Qt::AlignVCenter);
    seekRow->addWidget(m_timeLabel);
    m_seekSlider = new ClickSeekSlider(Qt::Horizontal);
    m_seekSlider->setRange(0, 0);
    m_seekSlider->setMaximumWidth(620);
    seekRow->addWidget(m_seekSlider, 1);
    m_durationLabel = new QLabel("0:00");
    m_durationLabel->setObjectName("TimeLabel");
    m_durationLabel->setFixedWidth(40);
    seekRow->addWidget(m_durationLabel);
    layout->addLayout(seekRow);

    // Right: ReplayGain toggle, stop, volume.
    auto* right = new QWidget();
    right->setMinimumWidth(250);
    right->setMaximumWidth(kSideWidth);
    auto* rightLayout = new QHBoxLayout(right);
    rightLayout->setContentsMargins(0, 0, 0, 0);
    rightLayout->setSpacing(6);
    rightLayout->addStretch();

    m_replayGainButton = new QPushButton(tr("ReplayGain"));
    m_replayGainButton->setObjectName("PillToggle");
    m_replayGainButton->setCheckable(true);
    m_replayGainButton->setChecked(true);
    m_replayGainButton->setFocusPolicy(Qt::NoFocus);
    m_replayGainButton->setCursor(Qt::PointingHandCursor);
    m_replayGainButton->setToolTip(tr("Normalize loudness using each track's ReplayGain tag"));
    rightLayout->addWidget(m_replayGainButton);

    m_stopButton = MakeIconButton(32, 16, tr("Stop"));
    rightLayout->addWidget(m_stopButton);

    m_queueButton = MakeIconButton(32, 18, tr("Up Next"));
    m_queueButton->setCheckable(true); // accent while the panel is open
    connect(m_queueButton, &QPushButton::clicked, this, [this](bool checked) { setUpNextVisible(checked); });
    rightLayout->addWidget(m_queueButton);

    m_volumeButton = MakeIconButton(32, 18, tr("Mute (Ctrl+M)"));
    connect(m_volumeButton, &QPushButton::clicked, this, &PlayerWindow::toggleMute);
    rightLayout->addWidget(m_volumeButton);

    // Range goes past 100 for a volume "boost" -- values above 1.0 are just
    // a linear gain multiplier to miniaudio. Loud source material can clip
    // past ~150-200%; a proper limiter is a good follow-up.
    m_volumeSlider = new ClickSeekSlider(Qt::Horizontal);
    m_volumeSlider->setRange(0, 200);
    m_volumeSlider->setValue(100);
    m_volumeSlider->setMinimumWidth(50);
    m_volumeSlider->setMaximumWidth(84);
    m_volumeSlider->setFocusPolicy(Qt::NoFocus);
    m_volumeSlider->setToolTip(tr("Volume (Ctrl+Up / Ctrl+Down)"));
    rightLayout->addWidget(m_volumeSlider);
    outerLayout->addWidget(right, 2);

    setControlsEnabled(false);

    connect(m_playButton, &QPushButton::clicked, this, &PlayerWindow::togglePlayPause);
    connect(m_stopButton, &QPushButton::clicked, this, &PlayerWindow::stop);
    connect(m_prevButton, &QPushButton::clicked, this, &PlayerWindow::previous);
    connect(m_nextButton, &QPushButton::clicked, this, [this]() { next(false); });
    connect(m_shuffleButton, &QPushButton::clicked, this, &PlayerWindow::cycleShuffleMode);
    connect(m_repeatButton, &QPushButton::clicked, this, &PlayerWindow::cycleRepeatMode);
    connect(m_replayGainButton, &QPushButton::clicked, this, &PlayerWindow::toggleReplayGain);

    connect(m_seekSlider, &QSlider::sliderPressed, this, [this]() { m_seeking = true; });
    connect(m_seekSlider, &QSlider::sliderReleased, this, &PlayerWindow::seekToSliderValue);

    connect(m_volumeSlider, &QSlider::valueChanged, this, &PlayerWindow::applyVolume);
    connect(m_volumeSlider, &QSlider::valueChanged, this, &PlayerWindow::updateVolumeIcons);

    // This bar's own content (seek/time/transport/secondary/volume rows)
    // is exactly the same size whether idle or playing -- only the Tracks
    // table above it (in rootLayout) grows or shrinks. Pinning the
    // minimum height here, before anything ever plays, stops that table
    // from ever compressing this bar enough to push the Volume row (or
    // anything else in it) below the visible window.
    bar->setMinimumHeight(bar->sizeHint().height());

    return bar;
}

QWidget* PlayerWindow::buildNowPlayingPage()
{
    // Three stacked layers: the ambient background (the cover, blurred and
    // darkened -- or generated art), the video wallpaper over it (transparent
    // when there's no video), and the content on top. The page is always
    // dark, so everything on it is drawn light, in both themes.
    auto* page = new QWidget();
    m_ambientBackground = new QLabel();
    m_ambientBackground->setScaledContents(true);
    m_ambientBackground->setStyleSheet("background: #0b0a0b;");
    m_wallpaperWidget = new VideoBackgroundWidget();

    auto* content = new QWidget();
    content->setObjectName("NowPlayingContent");
    content->setAttribute(Qt::WA_StyledBackground); // a plain QWidget only paints a stylesheet background with this
    // Light-on-dark overrides for the shared button and slider styles.
    content->setStyleSheet(
        // A light dark tint over the video wallpaper, so the light text always reads.
        "QWidget#NowPlayingContent { background: rgba(0,0,0,85); }"
        "QPushButton#IconButton:hover { background: rgba(255,255,255,28); }"
        "QPushButton#IconButton:pressed { background: rgba(255,255,255,16); }"
        "QPushButton#PillToggle { color: rgba(255,255,255,170); border-color: rgba(255,255,255,70); }"
        "QPushButton#PillToggle:hover { color: white; }"
        "QLabel#NowPlayingTime { color: rgba(255,255,255,150); font-size: 11px; }"
        "QSlider::groove:horizontal { background: rgba(255,255,255,45); }"
        "QSlider::add-page:horizontal { background: rgba(255,255,255,45); }"
        "QSlider::sub-page:horizontal { background: rgba(255,255,255,230); }"
        "QSlider::handle:horizontal { background: white; }");
    m_nowPlayingPage = content;
    // Right-click anywhere: Love, Add to Playlist, Edit Tags, Change Cover...
    content->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(content, &QWidget::customContextMenuRequested, this, [this, content](const QPoint& pos) {
        showNowPlayingMenu(content->mapToGlobal(pos));
    });
    auto* pageLayout = new QVBoxLayout(content);
    pageLayout->setContentsMargins(36, 24, 36, 28);

    auto* topRow = new QHBoxLayout();
    m_backButton = MakeIconButton(38, 20, tr("Back to library"));
    connect(m_backButton, &QPushButton::clicked, this, [this]() { m_stack->setCurrentIndex(0); });
    topRow->addWidget(m_backButton);
    topRow->addStretch();
    m_bigQueueButton = MakeIconButton(38, 18, tr("Up Next"));
    connect(m_bigQueueButton, &QPushButton::clicked, this, [this]() {
        m_stack->setCurrentIndex(0); // the queue lives on the library page
        setUpNextVisible(true);
    });
    topRow->addWidget(m_bigQueueButton);
    m_visualizerButton = MakeIconButton(38, 18, tr("Visualizer"));
    m_visualizerButton->setCheckable(true); // accent while it's on
    connect(m_visualizerButton, &QPushButton::clicked, this, [this](bool checked) { setVisualizerOn(checked); });
    topRow->addWidget(m_visualizerButton);
    m_sleepButton = MakeIconButton(38, 18, tr("Sleep timer"));
    m_sleepButton->setCheckable(true); // shows the accent color while a timer is set
    connect(m_sleepButton, &QPushButton::clicked, this, [this]() {
        m_sleepButton->setChecked(m_sleepMinutes != 0); // the click toggled it -- the menu decides
        openSleepTimerMenu();
    });
    topRow->addWidget(m_sleepButton);
    m_fullscreenButton = MakeIconButton(38, 18, tr("Full screen (F11)"));
    connect(m_fullscreenButton, &QPushButton::clicked, this, &PlayerWindow::toggleFullScreen);
    topRow->addWidget(m_fullscreenButton);
    pageLayout->addLayout(topRow);

    pageLayout->addStretch(1);

    // --- Left: cover, title, progress, controls -------------------------------
    static constexpr int kCoverSize = 360;
    auto* player = new QWidget();
    player->setFixedWidth(kCoverSize);
    auto* playerColumn = new QVBoxLayout(player);
    playerColumn->setContentsMargins(0, 0, 0, 0);
    playerColumn->setSpacing(0);

    m_bigCoverArtLabel = new QLabel();
    m_bigCoverArtLabel->setFixedSize(kCoverSize, kCoverSize);
    m_bigCoverArtLabel->setScaledContents(false); // pixmap is pre-cropped to exact label size (see ScaledCoverArt)
    m_bigCoverArtLabel->setStyleSheet("background: transparent;");
    // The visualizer takes the cover's place while it's on.
    m_visualizer = new VisualizerWidget(&m_engine.visualizer());
    m_visualizer->setFixedSize(kCoverSize, kCoverSize);
    m_coverStack = new QStackedWidget();
    m_coverStack->setFixedSize(kCoverSize, kCoverSize);
    m_coverStack->setStyleSheet("background: transparent;");
    m_coverStack->addWidget(m_bigCoverArtLabel); // 0
    m_coverStack->addWidget(m_visualizer);       // 1
    // A soft shadow lifts the cover off the background.
    auto* coverShadow = new QGraphicsDropShadowEffect(m_coverStack);
    coverShadow->setBlurRadius(48);
    coverShadow->setOffset(0, 14);
    coverShadow->setColor(QColor(0, 0, 0, 150));
    m_coverStack->setGraphicsEffect(coverShadow);
    playerColumn->addWidget(m_coverStack);
    playerColumn->addSpacing(24);

    // Title (scrolling when it's too long) and artist, the heart beside them.
    auto* titleRow = new QHBoxLayout();
    titleRow->setSpacing(8);
    auto* titleColumn = new QVBoxLayout();
    titleColumn->setSpacing(2);

    m_bigTitleLabel = new QLabel(tr("No file loaded"));
    QFont titleFont = font();
    titleFont.setPixelSize(26);
    titleFont.setWeight(QFont::Bold);
    m_bigTitleLabel->setFont(titleFont);
    m_bigTitleLabel->setWordWrap(false); // no wrap -- overflow is elided/scrolled instead of growing into a block
    m_bigTitleLabel->setStyleSheet("background: transparent; color: #f6ede7;");
    m_bigTitleLabel->adjustSize(); // sizeHint needs the real font applied first

    // Fixed-height clipping container -- a QLabel alone can't crop text
    // that's wider than the space available, but a QScrollArea can (and
    // lets us scroll it programmatically). Scrollbars stay hidden; the
    // marquee timer below drives the actual scrolling, not the user.
    m_bigTitleScrollArea = new QScrollArea();
    m_bigTitleScrollArea->setWidget(m_bigTitleLabel);
    m_bigTitleScrollArea->setWidgetResizable(false);
    m_bigTitleScrollArea->setFrameShape(QFrame::NoFrame);
    m_bigTitleScrollArea->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_bigTitleScrollArea->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_bigTitleScrollArea->setFixedHeight(m_bigTitleLabel->sizeHint().height());
    m_bigTitleScrollArea->setStyleSheet("background: transparent; border: none;");
    // A stylesheet on a QScrollArea doesn't cascade to its internal
    // viewport() widget -- a separate child Qt creates with its own
    // opaque palette background -- so that needs clearing explicitly too.
    m_bigTitleScrollArea->viewport()->setStyleSheet("background: transparent;");
    m_bigTitleScrollArea->viewport()->setAutoFillBackground(false);
    // Horizontal Ignored keeps the scroll area's sizeHint (the full,
    // unclipped title) from counting toward the page's minimum width.
    m_bigTitleScrollArea->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Fixed);
    titleColumn->addWidget(m_bigTitleScrollArea);

    // Three-phase cycle, like Harmonoid's title marquee: sits still showing
    // "...ellipsis" long enough to read (phase 0), slowly scrolls left to
    // reveal the full text (phase 1), holds fully revealed for a moment
    // (phase 2), then snaps back to the start and repeats. A complete
    // no-op whenever the title already fits (m_titleMarqueeNeeded false),
    // set once per track by resetTitleMarquee().
    m_titleMarqueeTimer = new QTimer(this);
    connect(m_titleMarqueeTimer, &QTimer::timeout, this, [this]() {
        if (!m_titleMarqueeNeeded)
        {
            return;
        }

        QScrollBar* bar = m_bigTitleScrollArea->horizontalScrollBar();

        if (m_titleMarqueePhase != 1) // paused, either at the start or the end
        {
            if (--m_titleMarqueeTicksRemaining > 0)
            {
                return;
            }

            if (m_titleMarqueePhase == 0)
            {
                // Start-pause over -- swap in the full (unelided) text so
                // there's something for the scroll to actually reveal.
                m_bigTitleLabel->setText(m_titleMarqueeFullText);
                m_bigTitleLabel->adjustSize();
                m_titleMarqueePhase = 1;
            }
            else
            {
                // End-pause over -- snap back to the start and show the
                // elided "..." version again while paused there.
                bar->setValue(0);
                applyElidedTitleText();
                m_titleMarqueePhase = 0;
                m_titleMarqueeTicksRemaining = 30; // ~1.5s at 50ms/tick
            }
            return;
        }

        int next = bar->value() + 1; // one pixel per tick -- slow, deliberate scroll
        if (next >= bar->maximum())
        {
            bar->setValue(bar->maximum());
            m_titleMarqueePhase = 2;
            m_titleMarqueeTicksRemaining = 24; // ~1.2s hold once fully revealed
        }
        else
        {
            bar->setValue(next);
        }
    });
    m_titleMarqueeTimer->start(50);

    // Artist  •  Album. A reserved height: this page sits in m_stack, which
    // sizes itself off the largest minimum across all its pages, so a label
    // growing from empty would otherwise grow the window while the library shows.
    m_bigSubtitleLabel = new QLabel();
    QFont subtitleFont = font();
    subtitleFont.setPixelSize(15);
    m_bigSubtitleLabel->setFont(subtitleFont);
    m_bigSubtitleLabel->setStyleSheet("background: transparent; color: rgba(246,237,231,175);");
    m_bigSubtitleLabel->setMinimumHeight(m_bigSubtitleLabel->fontMetrics().height());
    m_bigSubtitleLabel->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
    titleColumn->addWidget(m_bigSubtitleLabel);
    titleRow->addLayout(titleColumn, 1);

    m_loveButton = MakeIconButton(36, 22, tr("Love"));
    m_loveButton->setEnabled(false);
    connect(m_loveButton, &QPushButton::clicked, this, &PlayerWindow::toggleLoved);
    titleRow->addWidget(m_loveButton, 0, Qt::AlignVCenter);
    playerColumn->addLayout(titleRow);

    m_bigFormatLabel = new QLabel();
    m_bigFormatLabel->setStyleSheet("background: transparent; color: rgba(255,255,255,110); font-size: 11px;");
    m_bigFormatLabel->setMinimumHeight(m_bigFormatLabel->fontMetrics().height());
    playerColumn->addSpacing(4);
    playerColumn->addWidget(m_bigFormatLabel);
    playerColumn->addSpacing(18);

    // Progress, with the times under its two ends.
    m_bigSeekSlider = new ClickSeekSlider(Qt::Horizontal);
    m_bigSeekSlider->setRange(0, 0);
    connect(m_bigSeekSlider, &QSlider::sliderPressed, this, [this]() { m_seeking = true; });
    connect(m_bigSeekSlider, &QSlider::sliderReleased, this, [this]() {
        m_seekSlider->setValue(m_bigSeekSlider->value());
        seekToSliderValue();
    });
    playerColumn->addWidget(m_bigSeekSlider);
    auto* timesRow = new QHBoxLayout();
    m_bigTimeLabel = new QLabel("0:00");
    m_bigTimeLabel->setObjectName("NowPlayingTime");
    timesRow->addWidget(m_bigTimeLabel);
    timesRow->addStretch();
    m_bigDurationLabel = new QLabel("0:00");
    m_bigDurationLabel->setObjectName("NowPlayingTime");
    timesRow->addWidget(m_bigDurationLabel);
    playerColumn->addLayout(timesRow);
    playerColumn->addSpacing(10);

    // Shuffle  Previous  Play  Next  Repeat, spread across the column.
    auto* transportRow = new QHBoxLayout();
    m_bigShuffleButton = MakeIconButton(42, 20, tr("Shuffle: Off"));
    m_bigShuffleButton->setCheckable(true);
    m_bigPrevButton = MakeIconButton(48, 26, tr("Previous"));
    m_bigPlayButton = MakeIconButton(64, 28, tr("Play"));
    m_bigPlayButton->setObjectName("BigPlayButton");
    m_bigNextButton = MakeIconButton(48, 26, tr("Next"));
    m_bigRepeatButton = MakeIconButton(42, 20, tr("Repeat: Off"));
    m_bigRepeatButton->setCheckable(true);
    transportRow->addWidget(m_bigShuffleButton);
    transportRow->addStretch();
    transportRow->addWidget(m_bigPrevButton);
    transportRow->addSpacing(12);
    transportRow->addWidget(m_bigPlayButton);
    transportRow->addSpacing(12);
    transportRow->addWidget(m_bigNextButton);
    transportRow->addStretch();
    transportRow->addWidget(m_bigRepeatButton);
    playerColumn->addLayout(transportRow);
    playerColumn->addSpacing(12);

    // Volume, and A-B repeat.
    auto* extrasRow = new QHBoxLayout();
    extrasRow->setSpacing(6);
    m_bigVolumeButton = MakeIconButton(32, 18, tr("Mute (Ctrl+M)"));
    connect(m_bigVolumeButton, &QPushButton::clicked, this, &PlayerWindow::toggleMute);
    extrasRow->addWidget(m_bigVolumeButton);
    // Mirrors the mini player bar's m_volumeSlider rather than being a second
    // source of truth: m_volumeSlider stays what applyVolume() etc. read.
    m_bigVolumeSlider = new ClickSeekSlider(Qt::Horizontal);
    m_bigVolumeSlider->setRange(0, 200);
    m_bigVolumeSlider->setValue(m_volumeSlider->value());
    m_bigVolumeSlider->setFixedWidth(140);
    m_bigVolumeSlider->setFocusPolicy(Qt::NoFocus);
    extrasRow->addWidget(m_bigVolumeSlider);
    extrasRow->addStretch();
    m_abButton = new QPushButton(QStringLiteral("A–B"));
    m_abButton->setObjectName("PillToggle");
    m_abButton->setCheckable(true); // the accent fill while it loops
    m_abButton->setFocusPolicy(Qt::NoFocus);
    m_abButton->setCursor(Qt::PointingHandCursor);
    connect(m_abButton, &QPushButton::clicked, this, &PlayerWindow::cycleAbRepeat);
    extrasRow->addWidget(m_abButton);
    playerColumn->addLayout(extrasRow);

    // --- Right: lyrics ---------------------------------------------------------
    auto* lyricsPanel = new QWidget();
    lyricsPanel->setMinimumWidth(320);
    lyricsPanel->setMaximumWidth(680);
    lyricsPanel->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Ignored); // as tall as the player column
    auto* lyricsColumn = new QVBoxLayout(lyricsPanel);
    lyricsColumn->setContentsMargins(0, 0, 0, 0);
    lyricsColumn->setSpacing(6);
    auto* lyricsHeader = new QHBoxLayout();
    auto* lyricsCaption = new QLabel(tr("LYRICS"));
    QFont captionFont = lyricsCaption->font();
    captionFont.setPixelSize(11);
    captionFont.setWeight(QFont::DemiBold);
    captionFont.setLetterSpacing(QFont::AbsoluteSpacing, 2);
    lyricsCaption->setFont(captionFont);
    lyricsCaption->setStyleSheet("background: transparent; color: rgba(255,255,255,120);");
    lyricsHeader->addWidget(lyricsCaption);
    lyricsHeader->addStretch();

    // Lyrics timing, per song (synced lyrics only): a small menu.
    auto* timingButton = new QPushButton();
    timingButton->setObjectName("PillToggle");
    timingButton->setCursor(Qt::PointingHandCursor);
    timingButton->setFocusPolicy(Qt::NoFocus);
    connect(timingButton, &QPushButton::clicked, this, [this, timingButton]() {
        QMenu menu(this);
        menu.addAction(tr("Show 0.5 s Earlier"), this, [this]() { setLyricsOffset(m_lyricsOffsetMs - 500); });
        menu.addAction(tr("Show 0.5 s Later"), this, [this]() { setLyricsOffset(m_lyricsOffsetMs + 500); });
        QAction* reset = menu.addAction(tr("Reset Timing"), this, [this]() { setLyricsOffset(0); });
        reset->setEnabled(m_lyricsOffsetMs != 0);
        menu.exec(timingButton->mapToGlobal(QPoint(0, timingButton->height())));
    });
    m_lyricsOffsetRow = timingButton;
    m_lyricsTimingButton = timingButton;
    m_lyricsOffsetRow->hide();
    lyricsHeader->addWidget(timingButton);
    lyricsColumn->addLayout(lyricsHeader);

    m_lyricsList = new QListWidget();
    m_lyricsList->setStyleSheet(
        "QListWidget { background: transparent; border: none; }"
        "QListWidget::item { border: none; padding: 7px 0; }"
        "QListWidget::item:selected, QListWidget::item:hover { background: transparent; }");
    m_lyricsList->setSelectionMode(QAbstractItemView::NoSelection);
    m_lyricsList->setFocusPolicy(Qt::NoFocus);
    m_lyricsList->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff); // teleprompter-style: it scrolls itself
    m_lyricsList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_lyricsList->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    m_lyricsList->setWordWrap(true);
    m_lyricsBaseFont = m_lyricsList->font();
    m_lyricsBaseFont.setPixelSize(22);
    m_lyricsBaseFont.setWeight(QFont::DemiBold);
    m_lyricsList->setFont(m_lyricsBaseFont);
    m_lyricsList->viewport()->setMouseTracking(true); // needed for hover, no click/drag required
    m_lyricsList->viewport()->installEventFilter(this); // hover, click to seek, and the padding rows' height
    // Fades the lines out at the top and bottom edges.
    auto* fade = new QGraphicsOpacityEffect(m_lyricsList);
    QLinearGradient fadeMask(0, 0, 0, 1);
    fadeMask.setCoordinateMode(QGradient::ObjectBoundingMode);
    fadeMask.setColorAt(0.0, Qt::transparent);
    fadeMask.setColorAt(0.12, Qt::black);
    fadeMask.setColorAt(0.88, Qt::black);
    fadeMask.setColorAt(1.0, Qt::transparent);
    fade->setOpacityMask(fadeMask);
    m_lyricsList->setGraphicsEffect(fade);
    lyricsColumn->addWidget(m_lyricsList, 1);

    auto* mainRow = new QHBoxLayout();
    mainRow->addStretch(1);
    mainRow->addWidget(player, 0, Qt::AlignVCenter);
    mainRow->addSpacing(64);
    mainRow->addWidget(lyricsPanel, 3);
    mainRow->addStretch(1);
    // The lyrics are exactly as tall as the player column.
    auto* mainBox = new QWidget();
    mainBox->setLayout(mainRow);
    mainRow->setContentsMargins(0, 0, 0, 0);
    pageLayout->addWidget(mainBox);
    pageLayout->addStretch(1);

    // These just call the exact same methods the mini player bar's buttons
    // do -- one shared playback state, two sets of buttons.
    connect(m_bigShuffleButton, &QPushButton::clicked, this, &PlayerWindow::cycleShuffleMode);
    connect(m_bigPrevButton, &QPushButton::clicked, this, &PlayerWindow::previous);
    connect(m_bigPlayButton, &QPushButton::clicked, this, &PlayerWindow::togglePlayPause);
    connect(m_bigNextButton, &QPushButton::clicked, this, [this]() { next(false); });
    connect(m_bigRepeatButton, &QPushButton::clicked, this, &PlayerWindow::cycleRepeatMode);

    connect(m_bigVolumeSlider, &QSlider::valueChanged, this, [this](int value) {
        m_volumeSlider->setValue(value); // fires applyVolume() via m_volumeSlider's own connection
    });
    connect(m_volumeSlider, &QSlider::valueChanged, this, [this](int value) {
        m_bigVolumeSlider->blockSignals(true); // avoid feeding straight back into the lambda above
        m_bigVolumeSlider->setValue(value);
        m_bigVolumeSlider->blockSignals(false);
    });

    // StackAll keeps every layer visible at once -- added bottom to top.
    // Only currentWidget() receives mouse/keyboard input, so content must be
    // made current or every button/slider on this page would stop responding.
    auto* pageLayers = new QStackedLayout(page);
    pageLayers->setStackingMode(QStackedLayout::StackAll);
    pageLayers->addWidget(m_ambientBackground);
    pageLayers->addWidget(m_wallpaperWidget);
    pageLayers->addWidget(content);
    pageLayers->setCurrentWidget(content);
    // The order on screen, explicitly: adding the first layer made it the
    // current one and raised it, which isn't reliably undone by the later adds.
    m_wallpaperWidget->stackUnder(content);
    m_ambientBackground->stackUnder(m_wallpaperWidget);

    return page;
}

void PlayerWindow::toggleFullScreen()
{
    if (isFullScreen())
    {
        showNormal();
    }
    else
    {
        showFullScreen();
    }
}

// --- Folders / Tracks / Albums / Artists ------------------------------

void PlayerWindow::addFolder()
{
    QString dir = QFileDialog::getExistingDirectory(this, tr("Select Music Folder"));
    if (dir.isEmpty())
    {
        return;
    }
    if (!m_library.addFolder(dir))
    {
        return; // already added
    }
    refreshFoldersList();
    startLibraryScan(); // reads the new folder's songs in the background
}

void PlayerWindow::removeFolder(const QString& dir)
{
    m_library.removeFolder(dir);
    refreshFoldersList();
    refreshTracksTable();
    refreshAlbumsAndArtists();
    updateLibraryStatsUi();
    refreshPodcasts();
}

void PlayerWindow::setFolderIsPodcast(const QString& dir, bool podcast)
{
    QStringList folders = m_library.podcastFolders();
    folders.removeAll(dir);
    if (podcast)
    {
        folders << dir;
    }
    m_library.setPodcastFolders(folders); // the songs move between Tracks and Podcasts at once
    refreshTracksTable();
    refreshAlbumsAndArtists();
    refreshPlaylistsList();
    updateLibraryStatsUi();
    refreshPodcasts();
    startLibraryScan(); // a podcast folder's .m4a files are only found by a scan
}

void PlayerWindow::refreshLibrary()
{
    startLibraryScan();
}

void PlayerWindow::startLibraryScan()
{
    if (m_scanThread)
    {
        m_scanPending = true; // one more pass once this one's done (e.g. a folder added mid-scan)
        return;
    }

    const QStringList folders = m_library.folders();
    const QStringList podcastFolders = m_library.podcastFolders();
    QHash<QString, TrackInfo> known;
    for (const TrackInfo& t : m_library.allTracks())
    {
        known.insert(t.path, t);
    }
    m_scanCancel = std::make_shared<std::atomic<bool>>(false);
    auto cancel = m_scanCancel;

    m_scanThread = QThread::create([this, folders, podcastFolders, known, cancel]() {
        QElapsedTimer sinceReport;
        sinceReport.start();
        const QVector<TrackInfo> tracks = MusicLibrary::ScanFolders(folders, podcastFolders, known,
            [this, &sinceReport](int done, int total) {
                // A few updates a second is plenty for a progress bar.
                if (done == total || sinceReport.elapsed() >= 80)
                {
                    sinceReport.restart();
                    QMetaObject::invokeMethod(this, [this, done, total]() { onLibraryScanProgress(done, total); },
                                              Qt::QueuedConnection);
                }
            },
            *cancel);
        if (!cancel->load())
        {
            QMetaObject::invokeMethod(this, [this, tracks]() { onLibraryScanFinished(tracks); }, Qt::QueuedConnection);
        }
    });
    connect(m_scanThread, &QThread::finished, m_scanThread, &QObject::deleteLater);
    m_scanThread->start(QThread::LowPriority);

    m_refreshLibraryButton->setEnabled(false);
    m_analyzeButton->setEnabled(false);
    m_genreButton->setEnabled(false);
    m_scanLabel->setText(tr("Loading library\u2026"));
    m_scanProgress->setRange(0, 0); // "busy" until the file count is known
    // Only shown if it takes a moment -- an up-to-date library is checked in a blink.
    QTimer::singleShot(300, this, [this]() {
        if (m_scanThread)
        {
            m_scanBar->show();
        }
    });
}

void PlayerWindow::onLibraryScanProgress(int done, int total)
{
    m_scanProgress->setRange(0, qMax(1, total));
    m_scanProgress->setValue(done);
    m_scanLabel->setText(tr("Loading library\u2026 %1 of %2").arg(done).arg(total));
}

void PlayerWindow::onLibraryScanFinished(const QVector<TrackInfo>& scanned)
{
    m_scanThread = nullptr; // finishing on its own; deleteLater cleans it up

    // A folder removed while the scan ran must not come back with it.
    const QVector<TrackInfo> tracks = TracksInFolders(scanned, m_library.folders());

    // The usual case at launch: the cache was already right. Rebuilding every
    // view anyway would make a big library hitch for nothing.
    bool unchanged = tracks.size() == m_library.allTracks().size();
    for (int i = 0; unchanged && i < tracks.size(); ++i)
    {
        const TrackInfo& a = tracks[i];
        const TrackInfo& b = m_library.allTracks()[i];
        unchanged = a.path == b.path && a.modifiedMs == b.modifiedMs && a.addedMs == b.addedMs;
    }

    if (!unchanged)
    {
        m_library.setTracks(tracks);
        MusicLibrary::SaveCache(tracks);
        refreshTracksTable();
        refreshAlbumsAndArtists();
        refreshPlaylistsList();
        updateLibraryStatsUi();
        refreshPodcasts();
    }

    m_scanBar->hide();
    m_refreshLibraryButton->setEnabled(true);
    updateAnalysisProgressUi(); // re-enables Analyze
    updateGenreProgressUi();    // ...and Detect Genres

    if (m_scanPending)
    {
        m_scanPending = false;
        startLibraryScan();
    }
}

void PlayerWindow::refreshFoldersList()
{
    m_foldersList->clear();
    for (const QString& dir : m_library.folders())
    {
        auto* item = new QListWidgetItem();
        m_foldersList->addItem(item);

        auto* rowWidget = new QWidget();
        auto* rowLayout = new QHBoxLayout(rowWidget);
        rowLayout->setContentsMargins(4, 2, 4, 2);
        rowLayout->addWidget(new QLabel(dir), 1);

        auto* podcast = new QCheckBox(tr("Podcast"));
        podcast->setChecked(m_library.isPodcastFolder(dir));
        podcast->setToolTip(tr("This folder holds podcasts: show its episodes in the Podcasts tab only."));
        connect(podcast, &QCheckBox::toggled, this, [this, dir](bool checked) { setFolderIsPodcast(dir, checked); });
        rowLayout->addWidget(podcast);

        auto* removeButton = new QPushButton(tr("Remove"));
        connect(removeButton, &QPushButton::clicked, this, [this, dir]() { removeFolder(dir); });
        rowLayout->addWidget(removeButton);

        // Measured once it's in the list: before that the app's stylesheet
        // (the buttons' padding) isn't applied yet, the row came out too small
        // and Remove was squashed into a sliver. Width 0: the row takes the
        // list's width, so Remove stays inside it.
        m_foldersList->setItemWidget(item, rowWidget);
        rowWidget->ensurePolished();
        removeButton->ensurePolished();
        podcast->ensurePolished();
        item->setSizeHint(QSize(0, rowWidget->sizeHint().height()));
    }
}

void PlayerWindow::fitTracksColumnsOnce()
{
    // Until the user has their own widths (remembered from then on): the
    // columns share the list's width, so nothing runs off the right edge.
    const int width = m_tracksTable->viewport()->width();
    if (m_tracksColumnsFitted || width < 300 || !m_tracksTable->isVisible())
    {
        return;
    }
    m_tracksColumnsFitted = true;
    QHeaderView* header = m_tracksTable->horizontalHeader();
    const int year = 64;
    const int genre = qMin(160, width / 7);
    const int rest = width - year - genre;
    header->resizeSection(4, year);
    header->resizeSection(3, genre);
    header->resizeSection(0, rest * 50 / 100);
    header->resizeSection(1, rest * 25 / 100);
    header->resizeSection(2, rest - rest * 50 / 100 - rest * 25 / 100);
}

void PlayerWindow::refreshTracksTable()
{
    // Filled in one go with repaints off -- row-by-row inserts with sorting
    // and painting live made a big library take seconds.
    m_tracksTable->setUpdatesEnabled(false);
    m_tracksTable->setSortingEnabled(false);
    m_tracksTable->clearContents();
    m_tracksTable->setRowCount(m_library.tracks().size());

    int row = -1;
    for (const TrackInfo& t : m_library.tracks())
    {
        ++row;

        auto* titleItem = new QTableWidgetItem(t.title);
        titleItem->setData(Qt::UserRole, t.path);
        m_tracksTable->setItem(row, 0, titleItem);
        m_tracksTable->setItem(row, 1, new QTableWidgetItem(t.artist));
        m_tracksTable->setItem(row, 2, new QTableWidgetItem(t.album));
        // The file's genre, or the detected one if it's empty or a YouTube category like "Music".
        const QString genre = m_songData->shownGenre(t);
        auto* genreItem = new QTableWidgetItem(genre);
        if (!genre.isEmpty() && genre != t.genre)
        {
            genreItem->setToolTip(t.genre.trimmed().isEmpty()
                                      ? tr("Detected by AudioForge (the file has no genre)")
                                      : tr("Detected by AudioForge (the file says \"%1\")").arg(t.genre));
        }
        m_tracksTable->setItem(row, 3, genreItem);
        m_tracksTable->setItem(row, 4, new QTableWidgetItem(t.year > 0 ? QString::number(t.year) : QString()));
    }

    m_tracksTable->setSortingEnabled(true);
    m_tracksTable->setUpdatesEnabled(true);
    fitTracksColumnsOnce();

    // Rebuilt rows start out visible -- re-apply whatever's still typed in
    // the search bar so a refresh (tag edit, cover change, rescan) doesn't
    // silently un-filter the table.
    applySearchFilter(m_searchBar->text());
}

void PlayerWindow::refreshAlbumsAndArtists()
{
    m_albumsList->clear();
    QMap<QString, QStringList> albums = m_library.albumGroups();
    for (auto it = albums.constBegin(); it != albums.constEnd(); ++it)
    {
        auto* item = new QListWidgetItem(tr("%1  (%2 tracks)").arg(it.key()).arg(it.value().size()));
        item->setData(Qt::UserRole, it.value());
        item->setData(kGroupNameRole, it.key());
        m_albumsList->addItem(item);
    }

    m_artistsList->clear();
    QMap<QString, QStringList> artists = m_library.artistGroups();
    for (auto it = artists.constBegin(); it != artists.constEnd(); ++it)
    {
        auto* item = new QListWidgetItem(tr("%1  (%2 tracks)").arg(it.key()).arg(it.value().size()));
        item->setData(Qt::UserRole, it.value());
        item->setData(kGroupNameRole, it.key());
        m_artistsList->addItem(item);
    }
}


void PlayerWindow::updateLibraryStatsUi()
{
    const MusicLibrary::Stats s = m_library.stats([this](const TrackInfo& t) { return m_songData->shownGenre(t); });
    const int values[] = {s.trackCount, s.albumCount, s.artistCount, s.genreCount};
    for (int i = 0; i < m_statValueLabels.size(); ++i)
    {
        m_statValueLabels[i]->setText(QString::number(values[i]));
    }
    updateAnalysisProgressUi();
    updateGenreProgressUi();
}

// Just the Analyze row -- called on every analysis progress tick, so it
// mustn't walk the whole library (the counts above do).
void PlayerWindow::updateAnalysisProgressUi()
{
    const bool running = m_analyzer->isRunning();
    m_analyzeButton->setVisible(!running);
    m_analyzeStopButton->setVisible(running);
    m_analyzeProgress->setVisible(running);
    if (running)
    {
        m_analyzeProgress->setRange(0, qMax(1, m_analyzer->total()));
        m_analyzeProgress->setValue(m_analyzer->done());
        m_analyzeStatusLabel->setText(tr("Analyzing… %1 of %2. You can keep using the app.")
                                          .arg(m_analyzer->done()).arg(m_analyzer->total()));
    }
    else
    {
        const int total = m_library.tracks().size();
        const int analyzed = m_songData->analyzedCount(m_library.tracks());
        m_analyzeButton->setEnabled(total > 0 && !m_scanThread);
        m_analyzeStatusLabel->setText(analyzed == total ? tr("All %1 songs analyzed").arg(total)
                                                        : tr("%1 of %2 songs analyzed").arg(analyzed).arg(total));
    }
}

void PlayerWindow::startGenreDetection()
{
    m_genreAnalyzer->start(m_library.tracks());
    updateGenreProgressUi();
}

void PlayerWindow::updateGenreProgressUi()
{
    const bool running = m_genreAnalyzer->isRunning();
    m_genreButton->setVisible(!running);
    m_genreStopButton->setVisible(running);
    m_genreProgress->setVisible(running);
    if (running)
    {
        m_genreProgress->setRange(0, qMax(1, m_genreAnalyzer->total()));
        m_genreProgress->setValue(m_genreAnalyzer->done());
        m_genreStatusLabel->setText(tr("Detecting genres… %1 of %2")
                                        .arg(m_genreAnalyzer->done()).arg(m_genreAnalyzer->total()));
    }
    else
    {
        const int total = m_library.tracks().size();
        const int detected = m_songData->detectedGenreCount(m_library.tracks());
        m_genreButton->setEnabled(total > 0 && !m_scanThread);
        m_genreStatusLabel->setText(detected == total ? tr("Genres detected for all %1 songs").arg(total)
                                                      : tr("Genres detected for %1 of %2 songs").arg(detected).arg(total));
    }
}

void PlayerWindow::updateSmartOptionsUi()
{
    const bool locked = !m_analysisCompleted;
    SetRowVisible(m_smartLockRow, locked);
    m_smartTransitionsSwitch->setEnabled(!locked);
    m_beatMatchSwitch->setEnabled(!locked && m_crossfadeEnabled);
    m_harmonicShuffleSwitch->setEnabled(!locked);
}

void PlayerWindow::startAnalysis()
{
    m_analyzer->start(m_library.tracks()); // finished(true) right away if everything's already analyzed
    updateAnalysisProgressUi();
}

void PlayerWindow::onAnalysisFinished(bool completed)
{
    if (completed && !m_analysisCompleted)
    {
        m_analysisCompleted = true;
        updateSmartOptionsUi();
    }
    updateLibraryStatsUi();
    updateSmartOrder(); // newly analyzed songs join the harmonic order
    refreshPlaylistsList(); // smart playlists with BPM / Key rules
}

void PlayerWindow::updateSmartOrder()
{
    if (m_analysisCompleted && m_harmonicShuffle)
    {
        // A snapshot, so the order works even while analysis carries on.
        const QHash<QString, SongAnalysis> analysis = m_songData->allAnalysis();
        m_queue.setSmartOrder([analysis](const QVector<TrackInfo>& items, int start) {
            return HarmonicOrder(items, start, analysis);
        });
    }
    else
    {
        m_queue.setSmartOrder({});
    }

    // Changing the order drops the queue's pending pick, so a hand-off in
    // progress is settled first: an audible crossfade finishes, an unheard one is dropped.
    settleHandOff();
    m_queue.reshuffle();
    refreshUpNext();
}

void PlayerWindow::openDuplicates()
{
    DuplicatesDialog dialog(
        m_library.tracks(),
        [this](const QVector<TrackInfo>& group, int index) { setQueueAndPlay(group, index, tr("Duplicates")); },
        [this](const QString&) { refreshLibrary(); },
        this);
    dialog.exec();
}

void PlayerWindow::setThemeMode(int mode)
{
    m_themeMode = qBound(0, mode, 2);
    if (m_themeCombo->currentIndex() != m_themeMode)
    {
        m_themeCombo->blockSignals(true);
        m_themeCombo->setCurrentIndex(m_themeMode);
        m_themeCombo->blockSignals(false);
    }
    applyTheme();
}

void PlayerWindow::applySearchFilter(const QString& text)
{
    QString needle = text.trimmed().toLower();
    for (int row = 0; row < m_tracksTable->rowCount(); ++row)
    {
        bool matches = needle.isEmpty();
        if (!matches)
        {
            for (int col = 0; col < 3; ++col) // Title, Artist, Album
            {
                QTableWidgetItem* item = m_tracksTable->item(row, col);
                if (item && item->text().toLower().contains(needle))
                {
                    matches = true;
                    break;
                }
            }
        }
        m_tracksTable->setRowHidden(row, !matches);
    }
}

// --- MusicBrainz tagger -------------------------------------------

QString PlayerWindow::selectedTrackPath() const
{
    QTableWidgetItem* item = m_tracksTable->currentItem();
    return item ? m_tracksTable->item(item->row(), 0)->data(Qt::UserRole).toString() : QString();
}

void PlayerWindow::openManualTagDialog(QString path)
{
    if (path.isEmpty())
    {
        path = selectedTrackPath(); // the Tracks list's row
    }
    if (path.isEmpty())
    {
        return;
    }

    QMap<QString, QString> tags = ReadAllTags(path);

    ManualTagDialog dialog(path, tags, ReadCoverArt(path), this);
    if (const DetectedGenre* genre = m_songData->genreFor(m_library.findTrackInfo(path));
        genre && genre->status == DetectedGenre::Status::Ok)
    {
        dialog.setDetectedGenre(genre->label(), genre->subgenre.isEmpty() ? genre->broad : genre->subgenre);
    }
    dialog.setSaveFunction([this, path](const QMap<QString, QString>& newTags) {
        return writeTrackFile(path, [&]() { return WriteAllTags(path, newTags); });
    });

    // "Fill from internet" reuses the exact same lookup as the "MusicBrainz"
    // menu item -- lookupSelectedTrackOnMusicBrainz() reads whatever row is
    // currently selected, which is still this track's row since selection
    // doesn't change while this modal dialog is open. Passing the dialog
    // tells the reply handler to feed its result into this dialog's fields
    // instead of writing straight to disk -- and to drop the result if the
    // dialog has been closed by the time the reply lands.
    connect(&dialog, &ManualTagDialog::fillFromInternetRequested, this, [this, &dialog, path]() {
        lookupSelectedTrackOnMusicBrainz(&dialog, path);
    });

    if (dialog.exec() == QDialog::Accepted)
    {
        afterTrackFileWritten(path);
    }
}

void PlayerWindow::openChangeCoverDialog(QString path)
{
    if (path.isEmpty())
    {
        path = selectedTrackPath(); // the Tracks list's row
    }
    if (path.isEmpty())
    {
        return;
    }

    QString imagePath = QFileDialog::getOpenFileName(this, tr("Choose Cover Image"), QString(),
        tr("Images") + " (*.jpg *.jpeg *.png)");
    if (imagePath.isEmpty())
    {
        return;
    }

    QFile imageFile(imagePath);
    if (!imageFile.open(QIODevice::ReadOnly))
    {
        ErrorReporter::warn(this, tr("Couldn't read image"), tr("The selected image file couldn't be opened."));
        return;
    }
    QByteArray imageData = imageFile.readAll();
    QString mimeType = imagePath.toLower().endsWith(".png") ? "image/png" : "image/jpeg";

    if (!writeTrackFile(path, [&]() { return WriteCoverArt(path, imageData, mimeType); }))
    {
        ErrorReporter::warn(this, tr("Couldn't save cover"), tr("The file's cover art couldn't be written to."));
        return;
    }

    afterTrackFileWritten(path);
}

bool PlayerWindow::writeTrackFile(const QString& path, const std::function<bool()>& write)
{
    // The incoming side of a crossfade also holds the file open -- just
    // drop the fade (the queue keeps its pending pick, so it restarts).
    if (m_engine.isCrossfading() && m_queue.hasPendingNext() && m_queue.pendingNextTrack().path == path)
    {
        m_engine.abortCrossfade();
        applyVolume();
    }

    const bool isPlayingTrack = m_engine.isLoaded() && !m_queue.isEmpty() && m_queue.currentTrack().path == path;
    if (!isPlayingTrack)
    {
        return write();
    }

    const float cursor = m_engine.cursorSeconds();
    const bool wasPlaying = m_engine.isPlaying();
    abortCrossfade();
    m_engine.unload();

    const bool written = write();

    if (m_engine.loadAndPlay(path))
    {
        m_engine.seekToSeconds(cursor);
        applyVolume();
        if (!wasPlaying)
        {
            m_engine.pause();
        }
        m_seekSlider->setRange(0, static_cast<int>(m_engine.lengthSeconds()));
        m_wasAtEnd = false;
    }
    else
    {
        ErrorReporter::warn(this, tr("Failed to reload track"), tr("Could not reload: %1").arg(QFileInfo(path).fileName()));
        setControlsEnabled(false);
    }
    return written;
}

void PlayerWindow::afterTrackFileWritten(const QString& path)
{
    // Re-read the file so the in-memory library reflects exactly what was
    // actually saved.
    m_library.refreshTrack(path);
    refreshTracksTable();
    refreshAlbumsAndArtists();
    updateLibraryStatsUi();

    if (!m_queue.isEmpty() && m_queue.currentTrack().path == path)
    {
        updateNowPlayingUi(m_library.findTrackInfo(path)); // repaints titles + cover art with what was just written
    }
}

namespace {

// Inside a Lucene quoted phrase only `"` and `\` are special -- escaping
// those and quoting the whole value keeps titles like "AC/DC: Live (1992)"
// from being parsed as field names / grouping / operators.
QString LucenePhrase(const QString& value)
{
    QString escaped = value;
    escaped.replace("\\", "\\\\");
    escaped.replace("\"", "\\\"");
    return "\"" + escaped + "\"";
}

} // namespace

void PlayerWindow::lookupSelectedTrackOnMusicBrainz(ManualTagDialog* targetDialog, QString path)
{
    if (m_musicBrainzLookupInFlight)
    {
        return; // e.g. "Fill from internet" clicked again before the first reply landed
    }

    if (path.isEmpty())
    {
        path = selectedTrackPath(); // the Tracks list's row
    }
    if (path.isEmpty())
    {
        return;
    }
    const TrackInfo info = m_library.findTrackInfo(path);
    QString title = info.title.isEmpty() ? QFileInfo(path).completeBaseName() : info.title; // a file opened outside the library
    QString artist = info.artist;

    // MusicBrainz's recording search endpoint (Lucene query syntax).
    QString query = "recording:" + LucenePhrase(title);
    if (!artist.isEmpty())
    {
        query += " AND artist:" + LucenePhrase(artist);
    }

    QUrl url("https://musicbrainz.org/ws/2/recording/");
    QUrlQuery urlQuery;
    urlQuery.addQueryItem("query", query);
    urlQuery.addQueryItem("fmt", "json");
    urlQuery.addQueryItem("limit", "10");
    url.setQuery(urlQuery);

    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::UserAgentHeader, app::UserAgent());

    m_musicBrainzLookupInFlight = true;
    m_musicBrainzButton->setEnabled(false);
    m_musicBrainzButton->setText(tr("Looking up..."));

    // QPointer goes null if the (stack-local) manual tag dialog is closed
    // while this request is in flight; forManualDialog remembers that the
    // lookup was for a dialog at all, so a closed one means "discard", not
    // "write to disk".
    const bool forManualDialog = targetDialog != nullptr;
    QPointer<ManualTagDialog> target(targetDialog);
    QNetworkReply* reply = m_network->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, path, forManualDialog, target]() {
        handleMusicBrainzReply(reply, path, forManualDialog, target);
    });
}

void PlayerWindow::handleMusicBrainzReply(QNetworkReply* reply, const QString& path,
                                          bool forManualDialog, QPointer<ManualTagDialog> targetDialog)
{
    reply->deleteLater();
    m_musicBrainzLookupInFlight = false;
    m_musicBrainzButton->setText(tr("Edit Tags..."));
    m_musicBrainzButton->setEnabled(!m_tracksTable->selectedItems().isEmpty());

    if (forManualDialog && !targetDialog)
    {
        return; // the manual tag dialog was closed mid-lookup -- nothing to fill in, and never write directly
    }

    if (reply->error() != QNetworkReply::NoError)
    {
        ErrorReporter::warn(this, tr("MusicBrainz lookup failed"), reply->errorString());
        return;
    }

    QJsonDocument doc = QJsonDocument::fromJson(reply->readAll());
    QJsonArray recordings = doc.object().value("recordings").toArray();

    QVector<MusicBrainzCandidate> candidates;
    for (const QJsonValue& v : recordings)
    {
        QJsonObject rec = v.toObject();
        MusicBrainzCandidate c;
        c.title = rec.value("title").toString();

        QJsonArray artistCredit = rec.value("artist-credit").toArray();
        QStringList artistNames;
        for (const QJsonValue& a : artistCredit)
        {
            artistNames << a.toObject().value("name").toString();
        }
        c.artist = artistNames.join(", ");

        QJsonArray releases = rec.value("releases").toArray();
        if (!releases.isEmpty())
        {
            QJsonObject release = releases.first().toObject();
            c.album = release.value("title").toString();
            QString date = release.value("date").toString();
            if (date.size() >= 4)
            {
                c.year = date.left(4).toUInt();
            }
        }

        if (!c.title.isEmpty())
        {
            candidates << c;
        }
    }

    MusicBrainzResultDialog dialog(candidates, this);
    if (dialog.exec() != QDialog::Accepted)
    {
        return;
    }

    int idx = dialog.selectedIndex();
    if (idx < 0 || idx >= candidates.size())
    {
        return;
    }

    const MusicBrainzCandidate& chosen = candidates[idx];

    // Triggered via "Fill from internet" inside the manual tag dialog --
    // hand the candidate to that dialog's fields for review instead of
    // writing to disk here; the dialog's own Save button does the write.
    if (forManualDialog)
    {
        if (targetDialog)
        {
            targetDialog->applyLookupResult(chosen.title, chosen.artist, chosen.album, chosen.year);
        }
        return;
    }

    if (!writeTrackFile(path, [&]() { return WriteBasicTags(path, chosen.title, chosen.artist, chosen.album, chosen.year); }))
    {
        ErrorReporter::warn(this, tr("Couldn't save tags"), tr("The file's tags could not be written to."));
        return;
    }

    afterTrackFileWritten(path);
}

// --- Lyrics (Musixmatch API, then local .lrc/.txt, then nothing) -------

void PlayerWindow::fetchLyricsFor(const TrackInfo& info)
{
    QString path = info.path; // guards against a stale reply landing after the track's changed
    setLyricsPlaceholder(tr("Loading lyrics..."));

    LyricsResult cached = ReadCachedLyrics(path);
    if (!cached.rawText.isEmpty())
    {
        setLyricsContent(cached.rawText, cached.synced);
        return;
    }

    if (info.title.isEmpty())
    {
        applyFallbackOrClearLyrics(path);
        return;
    }

    fetchFromLrclib(info, path);
}

void PlayerWindow::fetchFromLrclib(const TrackInfo& info, const QString& path)
{
    QUrl url("https://lrclib.net/api/search");
    QUrlQuery query;
    query.addQueryItem("track_name", info.title);
    if (!info.artist.isEmpty())
    {
        query.addQueryItem("artist_name", info.artist);
    }
    url.setQuery(query);

    // LRCLIB needs no API key -- it asks (doesn't require) an identifying
    // User-Agent, same courtesy as the MusicBrainz request above.
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::UserAgentHeader, app::UserAgent());

    QNetworkReply* reply = m_network->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, path, info]() {
        reply->deleteLater();

        if (m_queue.isEmpty() || m_queue.currentTrack().path != path)
        {
            return; // a different track is playing now
        }

        if (reply->error() == QNetworkReply::NoError)
        {
            QJsonArray results = QJsonDocument::fromJson(reply->readAll()).array();
            if (!results.isEmpty())
            {
                QJsonObject best = results.first().toObject();
                QString synced = best.value("syncedLyrics").toString();
                QString plain = best.value("plainLyrics").toString();

                if (!synced.trimmed().isEmpty())
                {
                    WriteCachedLyrics(path, synced, /*synced=*/true);
                    setLyricsContent(synced, /*synced=*/true);
                    return;
                }
                if (!plain.trimmed().isEmpty())
                {
                    WriteCachedLyrics(path, plain, /*synced=*/false);
                    setLyricsContent(plain, /*synced=*/false);
                    return;
                }
            }
        }

        // No match, no lyrics on the match, or a network error -- Musixmatch
        // is the next rung (its snippet is worth trying before giving up).
        fetchFromMusixmatch(info, path);
    });
}

void PlayerWindow::fetchFromMusixmatch(const TrackInfo& info, const QString& path)
{
    if (m_musixmatchApiKey.isEmpty())
    {
        applyFallbackOrClearLyrics(path);
        return;
    }

    QUrl searchUrl("https://api.musixmatch.com/ws/1.1/track.search");
    QUrlQuery searchQuery;
    searchQuery.addQueryItem("q_track", info.title);
    if (!info.artist.isEmpty())
    {
        searchQuery.addQueryItem("q_artist", info.artist);
    }
    searchQuery.addQueryItem("page_size", "1");
    searchQuery.addQueryItem("s_track_rating", "desc");
    searchQuery.addQueryItem("apikey", m_musixmatchApiKey);
    searchUrl.setQuery(searchQuery);

    QNetworkReply* searchReply = m_network->get(QNetworkRequest(searchUrl));
    connect(searchReply, &QNetworkReply::finished, this, [this, searchReply, path]() {
        searchReply->deleteLater();

        // The track may well have changed while this request was in
        // flight (e.g. user skipped ahead) -- don't apply a stale result.
        if (m_queue.isEmpty() || m_queue.currentTrack().path != path)
        {
            return;
        }

        if (searchReply->error() != QNetworkReply::NoError)
        {
            applyFallbackOrClearLyrics(path);
            return;
        }

        QJsonObject searchBody = QJsonDocument::fromJson(searchReply->readAll())
            .object().value("message").toObject().value("body").toObject();
        QJsonArray trackList = searchBody.value("track_list").toArray();
        if (trackList.isEmpty())
        {
            applyFallbackOrClearLyrics(path);
            return;
        }

        QJsonObject track = trackList.first().toObject().value("track").toObject();
        qint64 trackId = static_cast<qint64>(track.value("track_id").toDouble());
        if (trackId <= 0)
        {
            applyFallbackOrClearLyrics(path);
            return;
        }

        QUrl lyricsUrl("https://api.musixmatch.com/ws/1.1/track.lyrics.get");
        QUrlQuery lyricsQuery;
        lyricsQuery.addQueryItem("track_id", QString::number(trackId));
        lyricsQuery.addQueryItem("apikey", m_musixmatchApiKey);
        lyricsUrl.setQuery(lyricsQuery);

        QNetworkReply* lyricsReply = m_network->get(QNetworkRequest(lyricsUrl));
        connect(lyricsReply, &QNetworkReply::finished, this, [this, lyricsReply, path]() {
            lyricsReply->deleteLater();

            if (m_queue.isEmpty() || m_queue.currentTrack().path != path)
            {
                return;
            }

            if (lyricsReply->error() != QNetworkReply::NoError)
            {
                applyFallbackOrClearLyrics(path);
                return;
            }

            QJsonObject lyricsBody = QJsonDocument::fromJson(lyricsReply->readAll())
                .object().value("message").toObject().value("body").toObject();
            QString lyricsText = lyricsBody.value("lyrics").toObject().value("lyrics_body").toString().trimmed();

            if (lyricsText.isEmpty())
            {
                applyFallbackOrClearLyrics(path);
                return;
            }

            // Musixmatch's free tier returns roughly a 30% snippet, with
            // their own attribution/notice appended to lyrics_body --
            // shown as-is rather than trimmed further.
            setLyricsContent(lyricsText, /*synced=*/false);
        });
    });
}

void PlayerWindow::applyFallbackOrClearLyrics(const QString& path)
{
    if (m_queue.isEmpty() || m_queue.currentTrack().path != path)
    {
        return; // a different track is playing now; nothing to show here
    }

    LyricsResult sidecar = ReadSidecarLyrics(path);
    if (!sidecar.rawText.isEmpty())
    {
        setLyricsContent(sidecar.rawText, sidecar.synced);
    }
    else
    {
        setLyricsPlaceholder(tr("No lyrics found."));
    }
}

void PlayerWindow::setLyricsOffset(int offsetMs)
{
    if (m_queue.isEmpty())
    {
        return;
    }
    m_lyricsOffsetMs = qBound(-60000, offsetMs, 60000);
    m_songData->setLyricsOffset(m_queue.currentTrack().path, m_lyricsOffsetMs);
    m_lastLyricsLineIndex = -2; // restyle on the next tick, even if it's the same line index
    updateLyricsOffsetUi();
}

void PlayerWindow::updateLyricsOffsetUi()
{
    m_lyricsOffsetRow->setVisible(!m_syncedLyrics.isEmpty());
    m_lyricsTimingButton->setText(m_lyricsOffsetMs == 0
        ? tr("Lyrics timing")
        : tr("Lyrics timing: %1").arg(QString("%1%2 s").arg(m_lyricsOffsetMs > 0 ? "+" : "−")
                                          .arg(std::abs(m_lyricsOffsetMs) / 1000.0, 0, 'f', 1)));
}

void PlayerWindow::setLyricsPlaceholder(const QString& text)
{
    m_lyricsList->clear();
    m_syncedLyrics.clear();
    m_lastLyricsLineIndex = -1;
    m_hoveredLyricsLineIndex = -1;
    updateLyricsOffsetUi();

    // In the middle, between the padding rows.
    m_lyricsList->addItem(new QListWidgetItem());
    m_lyricsLead = 1;
    m_lyricsCentered = true;
    auto* item = new QListWidgetItem(text);
    item->setFlags(item->flags() & ~Qt::ItemIsSelectable);
    item->setForeground(QColor(255, 255, 255, 130));
    m_lyricsList->addItem(item);
    m_lyricsList->addItem(new QListWidgetItem());
    updateLyricsPadding();
}

void PlayerWindow::setLyricsContent(const QString& rawText, bool synced)
{
    m_lyricsList->clear();
    m_syncedLyrics.clear();
    m_lastLyricsLineIndex = -1;
    m_hoveredLyricsLineIndex = -1;

    if (synced)
    {
        m_syncedLyrics = ParseSyncedLyrics(rawText);
    }
    updateLyricsOffsetUi();

    // Padding rows before and after (see updateLyricsPadding()).
    m_lyricsList->addItem(new QListWidgetItem());
    m_lyricsLead = 1;
    m_lyricsCentered = synced && !m_syncedLyrics.isEmpty();
    if (synced && !m_syncedLyrics.isEmpty())
    {
        // One item per timed line -- left dim for now; the next playback
        // tick's updateSyncedLyricsHighlight() call lights up whichever
        // line the current cursor position actually falls on. Empty padding
        // rows before and after (half the list's height each) let the first
        // and last lines scroll to the middle too.
        for (const SyncedLyricLine& line : m_syncedLyrics)
        {
            auto* item = new QListWidgetItem(line.text.isEmpty() ? QStringLiteral("♪") : line.text);
            item->setFlags(item->flags() & ~Qt::ItemIsSelectable);
            item->setFont(m_lyricsBaseFont);
            item->setForeground(QColor(255, 255, 255, 90));
            m_lyricsList->addItem(item);
        }
    }
    else
    {
        // Not synced (Musixmatch snippet, a .txt sidecar, or a .lrc with no
        // valid timestamp tags after all) -- just show it as plain lines,
        // uniformly styled, no highlight tracking.
        for (const QString& line : rawText.trimmed().split('\n'))
        {
            if (line.trimmed().isEmpty())
            {
                continue;
            }
            auto* item = new QListWidgetItem(line.trimmed());
            item->setFlags(item->flags() & ~Qt::ItemIsSelectable);
            item->setFont(m_lyricsBaseFont);
            item->setForeground(QColor(255, 255, 255, 200));
            m_lyricsList->addItem(item);
        }
    }
    m_lyricsList->addItem(new QListWidgetItem());
    updateLyricsPadding();
}

void PlayerWindow::updateSyncedLyricsHighlight(float cursorSeconds)
{
    int currentIndex = -1;
    for (int i = 0; i < m_syncedLyrics.size(); ++i)
    {
        if (m_syncedLyrics[i].seconds <= cursorSeconds)
        {
            currentIndex = i;
        }
        else
        {
            break;
        }
    }

    if (currentIndex == m_lastLyricsLineIndex)
    {
        return; // still the same line -- nothing to restyle or rescroll
    }
    m_lastLyricsLineIndex = currentIndex;

    for (int i = 0; i < m_syncedLyrics.size(); ++i)
    {
        QListWidgetItem* item = m_lyricsList->item(i + m_lyricsLead);
        if (!item)
        {
            break;
        }
        QFont font = m_lyricsBaseFont;
        if (i == currentIndex)
        {
            font.setWeight(QFont::Bold);
            font.setPixelSize(m_lyricsBaseFont.pixelSize() + 6);
            item->setForeground(QColor(255, 255, 255, 255));
        }
        else
        {
            // Sung lines stay a little brighter than the ones still to come.
            item->setForeground(QColor(255, 255, 255, i < currentIndex ? 110 : 80));
        }
        font.setUnderline(i == m_hoveredLyricsLineIndex); // keep the hover underline through a restyle
        item->setFont(font);
    }

    // The current line in the middle (before the first one: the start of the song, at the top).
    if (QListWidgetItem* item = m_lyricsList->item(qMax(0, currentIndex + m_lyricsLead)))
    {
        m_lyricsList->scrollToItem(item, currentIndex >= 0 ? QAbstractItemView::PositionAtCenter
                                                           : QAbstractItemView::PositionAtTop);
    }
}

void PlayerWindow::updateLyricsPadding()
{
    if (m_lyricsLead == 0 || m_lyricsList->count() < 2)
    {
        return;
    }
    // Synced lyrics and the "No lyrics" note: half the height above, so any
    // line can sit in the middle. Plain lyrics read from the top: just clear of the fade.
    const int height = m_lyricsList->viewport()->height();
    m_lyricsList->item(0)->setSizeHint(QSize(1, m_lyricsCentered ? height / 2 : height / 7));
    m_lyricsList->item(m_lyricsList->count() - 1)->setSizeHint(QSize(1, height / 2));
    if (m_syncedLyrics.isEmpty())
    {
        m_lyricsList->scrollToItem(m_lyricsList->item(m_lyricsCentered ? 1 : 0),
                                   m_lyricsCentered ? QAbstractItemView::PositionAtCenter : QAbstractItemView::PositionAtTop);
    }
    m_lastLyricsLineIndex = -2; // synced: re-center on the next tick
}

bool PlayerWindow::eventFilter(QObject* watched, QEvent* event)
{
    if (watched != m_lyricsList->viewport())
    {
        return QWidget::eventFilter(watched, event);
    }
    if (event->type() == QEvent::Resize)
    {
        updateLyricsPadding();
    }

    if (event->type() == QEvent::MouseMove || event->type() == QEvent::Leave)
    {
        QListWidgetItem* item = nullptr;
        if (event->type() == QEvent::MouseMove)
        {
            item = m_lyricsList->itemAt(static_cast<QMouseEvent*>(event)->pos());
        }

        int index = item ? m_lyricsList->row(item) - m_lyricsLead : -1;
        // Only lines that came from synced lyrics have a real timestamp --
        // for plain/unsynced lyrics m_syncedLyrics is empty, so this is
        // always false and hover has no visible effect at all.
        const bool hasTimestamp = index >= 0 && index < m_syncedLyrics.size();
        if (!hasTimestamp)
        {
            index = -1;
        }

        if (index != m_hoveredLyricsLineIndex)
        {
            if (QListWidgetItem* old = m_hoveredLyricsLineIndex >= 0 ? m_lyricsList->item(m_hoveredLyricsLineIndex + m_lyricsLead) : nullptr)
            {
                QFont font = old->font();
                font.setUnderline(false);
                old->setFont(font);
            }
            m_hoveredLyricsLineIndex = index;
            if (m_hoveredLyricsLineIndex >= 0)
            {
                QFont font = item->font();
                font.setUnderline(true);
                item->setFont(font);
            }
        }
        m_lyricsList->viewport()->setCursor(hasTimestamp ? Qt::PointingHandCursor : Qt::ArrowCursor);
    }
    else if (event->type() == QEvent::MouseButtonRelease)
    {
        auto* mouseEvent = static_cast<QMouseEvent*>(event);
        if (mouseEvent->button() == Qt::LeftButton)
        {
            QListWidgetItem* item = m_lyricsList->itemAt(mouseEvent->pos());
            int index = item ? m_lyricsList->row(item) - m_lyricsLead : -1;
            if (index >= 0 && index < m_syncedLyrics.size())
            {
                seekTo(qMax(0.0f, m_syncedLyrics[index].seconds + m_lyricsOffsetMs / 1000.0f)); // the line's own time, with the offset
                // no manual UI push needed -- the next updatePlayback() tick
                // (m_seeking is false here) picks up the new cursor and
                // moves both seek sliders + the highlighted line itself
            }
            // no timestamp on this line -- intentionally does nothing
        }
    }

    return QWidget::eventFilter(watched, event);
}

void PlayerWindow::openGroupTracks(const QString& title, const QStringList& paths)
{
    QVector<TrackInfo> subset = m_library.buildQueueFromPaths(paths);

    TrackListDialog dialog(title, subset, this);
    if (dialog.exec() == QDialog::Accepted && !dialog.chosenPath().isEmpty())
    {
        int idx = 0;
        for (int i = 0; i < subset.size(); ++i)
        {
            if (subset[i].path == dialog.chosenPath())
            {
                idx = i;
                break;
            }
        }
        setQueueAndPlay(subset, idx, title);
    }
}

// --- Playlists ---------------------------------------------------------

void PlayerWindow::openCreatePlaylistDialog()
{
    CreatePlaylistDialog dialog(m_library.tracks(), this);
    if (dialog.exec() == QDialog::Accepted)
    {
        PlaylistData playlist;
        playlist.name = dialog.resultName();
        playlist.trackPaths = dialog.resultTrackPaths();
        m_library.addPlaylist(playlist);
        refreshPlaylistsList();
    }
}

void PlayerWindow::refreshPlaylistsList()
{
    // Rows: Qt::UserRole = the regular playlist's index (-1 for a smart one),
    // kSmartIdRole = the smart playlist's id, kNewCardRole = a "+ New" card.
    const theme::Tokens& t = theme::tokens(m_darkMode);
    const QIcon smartIcon = theme::icon(theme::Icon::SmartPlaylist, t.accent);
    auto countText = [this](int count) { return count == 1 ? tr("1 song") : tr("%1 songs").arg(count); };
    // The "+ New" card's picture: an outlined square with a plus.
    auto newCardIcon = [&t](int size) {
        constexpr qreal kDpr = 2.0;
        QPixmap pixmap(qRound(size * kDpr), qRound(size * kDpr));
        pixmap.setDevicePixelRatio(kDpr);
        pixmap.fill(Qt::transparent);
        QPainter p(&pixmap);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(QPen(t.border, 1.5, Qt::DashLine));
        p.setBrush(t.surface);
        p.drawRoundedRect(QRectF(1, 1, size - 2, size - 2), 12, 12);
        const int plus = size / 3;
        p.drawPixmap((size - plus) / 2, (size - plus) / 2, theme::icon(theme::Icon::Plus, t.muted).pixmap(plus, plus));
        return QIcon(pixmap);
    };

    m_playlistsList->clear();
    m_smartPlaylistsList->clear();
    m_sidebarPlaylistsList->clear();

    // The user's own playlists.
    const QVector<PlaylistData>& playlists = m_library.playlists();
    for (int i = 0; i < playlists.size(); ++i)
    {
        const PlaylistData& pl = playlists[i];
        auto* item = new QListWidgetItem(QIcon(playlistArt(pl, 150)), pl.name + '\n' + countText(pl.trackPaths.size()));
        item->setData(Qt::UserRole, i);
        item->setToolTip(pl.name);
        m_playlistsList->addCard(item);

        auto* sidebarItem = new QListWidgetItem(pl.name);
        sidebarItem->setData(Qt::UserRole, i);
        sidebarItem->setToolTip(countText(pl.trackPaths.size()));
        m_sidebarPlaylistsList->addItem(sidebarItem);
    }
    auto* newPlaylist = new QListWidgetItem(newCardIcon(150), tr("New Playlist"));
    newPlaylist->setData(Qt::UserRole, -1);
    newPlaylist->setData(kNewCardRole, true);
    m_playlistsList->addCard(newPlaylist);

    // Smart playlists: the built-in ones (Playlists tab only), then the user's (both lists).
    QVector<const SmartPlaylist*> smart;
    for (const SmartPlaylist& p : BuiltInSmartPlaylists())
    {
        smart << &p;
    }
    for (const SmartPlaylist& p : m_smartPlaylists)
    {
        smart << &p;
    }
    for (const SmartPlaylist* p : smart)
    {
        const int count = smartPlaylistTracks(*p).size();
        // The built-in ones each have their own picture and colour; the user's are coloured by id.
        theme::Icon mark = theme::Icon::SmartPlaylist;
        int hue = -1;
        if (p->id == QLatin1String("builtin:loved")) { mark = theme::Icon::HeartFilled; hue = 345; }
        else if (p->id == QLatin1String("builtin:most-played")) { mark = theme::Icon::Equalizer; hue = 25; }
        else if (p->id == QLatin1String("builtin:recently-played")) { mark = theme::Icon::Timer; hue = 205; }
        else if (p->id == QLatin1String("builtin:recently-added")) { mark = theme::Icon::Plus; hue = 150; }
        else if (p->id == QLatin1String("builtin:never-played")) { mark = theme::Icon::Play; hue = 270; }
        auto* item = new QListWidgetItem(QIcon(theme::generatedArt(p->id, 72, 14, mark, hue)),
                                         p->displayName() + '\n' + countText(count));
        item->setData(Qt::UserRole, -1);
        item->setData(kSmartIdRole, p->id);
        item->setToolTip(p->displayName());
        m_smartPlaylistsList->addCard(item);
        if (!p->builtIn)
        {
            auto* sidebarItem = new QListWidgetItem(smartIcon, p->displayName());
            sidebarItem->setData(Qt::UserRole, -1);
            sidebarItem->setData(kSmartIdRole, p->id);
            sidebarItem->setToolTip(countText(count));
            m_sidebarPlaylistsList->addItem(sidebarItem);
        }
    }
    auto* newSmart = new QListWidgetItem(newCardIcon(72), tr("New Smart Playlist"));
    newSmart->setData(Qt::UserRole, -1);
    newSmart->setData(kNewCardRole, true);
    m_smartPlaylistsList->addCard(newSmart);
}

QPixmap PlayerWindow::playlistArt(const PlaylistData& playlist, int size)
{
    // Up to four different covers, in playlist order: four make a 2x2 mosaic,
    // fewer show the first one; none, generated art from the name.
    const QString key = QString::number(size) + '|' + playlist.name + '|' + playlist.trackPaths.mid(0, 12).join('|');
    if (auto it = m_playlistArtCache.constFind(key); it != m_playlistArtCache.constEnd())
    {
        return it.value(); // reading covers is slow; the list is refreshed often
    }
    QStringList withCover;
    QVector<QByteArray> covers;
    for (const QString& path : playlist.trackPaths)
    {
        if (covers.size() == 4 || withCover.size() >= 12) // don't read a long playlist's every file
        {
            break;
        }
        withCover << path;
        QByteArray cover = ReadCoverArt(path);
        if (!cover.isEmpty() && !covers.contains(cover))
        {
            covers << cover;
        }
    }
    QPixmap art;
    if (covers.isEmpty())
    {
        art = theme::generatedArt(playlist.name, size, 12, theme::Icon::Playlist);
    }
    else
    {
        constexpr qreal kDpr = 2.0;
        const int px = qRound(size * kDpr);
        QPixmap mosaic(px, px);
        mosaic.fill(Qt::transparent);
        QPainter p(&mosaic);
        if (covers.size() < 4)
        {
            p.drawPixmap(0, 0, ScaledCoverArt(covers.first(), QSize(px, px)));
        }
        else
        {
            const int half = px / 2;
            for (int i = 0; i < 4; ++i)
            {
                p.drawPixmap((i % 2) * half, (i / 2) * half, ScaledCoverArt(covers[i], QSize(half, half)));
            }
        }
        p.end();
        art = theme::roundedPixmap(mosaic, 12 * kDpr);
        art.setDevicePixelRatio(kDpr);
    }
    m_playlistArtCache.insert(key, art);
    return art;
}

void PlayerWindow::activatePlaylistItem(QListWidgetItem* item)
{
    if (item->data(kNewCardRole).toBool())
    {
        return; // handled on the single click
    }
    const QString smartId = item->data(kSmartIdRole).toString();
    if (!smartId.isEmpty())
    {
        openSmartPlaylist(smartId);
    }
    else
    {
        openPlaylistTracks(item->data(Qt::UserRole).toInt());
    }
}

void PlayerWindow::showNewPlaylistMenu(QWidget* anchor, QPoint at)
{
    QMenu menu(this);
    menu.addAction(tr("New Playlist..."), this, &PlayerWindow::openCreatePlaylistDialog);
    menu.addAction(MenuIcon(theme::Icon::SmartPlaylist, m_darkMode), tr("New Smart Playlist..."), this,
                   [this]() { editSmartPlaylist(QString()); });
    menu.addSeparator();
    menu.addAction(tr("Import .m3u Playlist..."), this, &PlayerWindow::importM3u);
    menu.exec(anchor->mapToGlobal(at.x() >= 0 ? at : QPoint(0, anchor->height())));
}

// --- Smart playlists ----------------------------------------------------------------

const SmartPlaylist* PlayerWindow::smartPlaylist(const QString& id) const
{
    for (const SmartPlaylist& p : BuiltInSmartPlaylists())
    {
        if (p.id == id)
        {
            return &p;
        }
    }
    for (const SmartPlaylist& p : m_smartPlaylists)
    {
        if (p.id == id)
        {
            return &p;
        }
    }
    return nullptr;
}

QVector<TrackInfo> PlayerWindow::smartPlaylistTracks(const SmartPlaylist& playlist) const
{
    return playlist.tracks(m_library.tracks(), m_songData->allStats(),
                           [this](const TrackInfo& t) { return m_songData->analysisFor(t); },
                           [this](const TrackInfo& t) { return m_songData->shownGenre(t); },
                           QDateTime::currentMSecsSinceEpoch());
}

void PlayerWindow::openSmartPlaylist(const QString& id)
{
    const SmartPlaylist* playlist = smartPlaylist(id);
    if (!playlist)
    {
        return;
    }
    const QVector<TrackInfo> tracks = smartPlaylistTracks(*playlist);
    const QString name = playlist->displayName();
    TrackListDialog dialog(name, tracks, this,
                           playlist->builtIn ? tr("Songs appear here as you play and love them.")
                                             : tr("No songs match the rules yet."));
    if (dialog.exec() == QDialog::Accepted && !dialog.chosenPath().isEmpty())
    {
        int index = 0;
        for (int i = 0; i < tracks.size(); ++i)
        {
            if (tracks[i].path == dialog.chosenPath())
            {
                index = i;
                break;
            }
        }
        setQueueAndPlay(tracks, index, name);
    }
}

void PlayerWindow::editSmartPlaylist(const QString& id)
{
    SmartPlaylist initial;
    if (!id.isEmpty())
    {
        const SmartPlaylist* existing = smartPlaylist(id);
        if (!existing || existing->builtIn)
        {
            return;
        }
        initial = *existing;
    }
    SmartPlaylistDialog dialog(initial, [this](const SmartPlaylist& p) { return smartPlaylistTracks(p).size(); }, this);
    if (dialog.exec() != QDialog::Accepted)
    {
        return;
    }
    SmartPlaylist result = dialog.result();
    if (result.id.isEmpty())
    {
        result.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        m_smartPlaylists << result;
    }
    else
    {
        for (SmartPlaylist& p : m_smartPlaylists)
        {
            if (p.id == result.id)
            {
                p = result;
            }
        }
    }
    SaveSmartPlaylists(m_smartPlaylists);
    refreshPlaylistsList();
}

void PlayerWindow::deletePlaylistAt(int index)
{
    if (index < 0 || index >= m_library.playlists().size())
    {
        return;
    }
    const QString name = m_library.playlists()[index].name;
    if (!ErrorReporter::confirm(this, tr("Delete playlist"), tr("Delete \"%1\"? This can't be undone.").arg(name)))
    {
        return;
    }
    m_library.removePlaylistAt(index);
    refreshPlaylistsList();
    showToast(tr("Deleted “%1”.").arg(name));
}

void PlayerWindow::addToPlaylistMenu(QMenu& menu, const QVector<TrackInfo>& tracks)
{
    // "Add to Playlist" > each of the user's playlists, then "New Playlist...".
    QMenu* sub = menu.addMenu(MenuIcon(theme::Icon::Playlist, m_darkMode), tr("Add to Playlist"));
    sub->setEnabled(!tracks.isEmpty());
    QStringList paths;
    for (const TrackInfo& t : tracks)
    {
        paths << t.path;
    }
    auto addTo = [this, paths](int index) {
        if (index < 0 || index >= m_library.playlists().size())
        {
            return;
        }
        PlaylistData& playlist = m_library.playlists()[index];
        int added = 0;
        for (const QString& path : paths)
        {
            if (!playlist.trackPaths.contains(path)) // already in it: not twice
            {
                playlist.trackPaths << path;
                ++added;
            }
        }
        const QString name = playlist.name;
        refreshPlaylistsList();
        showToast(added == 0 ? tr("Already in “%1”.").arg(name)
                  : added == 1 ? tr("Added to “%1”.").arg(name)
                               : tr("Added %1 songs to “%2”.").arg(added).arg(name));
    };
    const QVector<PlaylistData>& playlists = m_library.playlists();
    for (int i = 0; i < playlists.size(); ++i)
    {
        sub->addAction(playlists[i].name, this, [addTo, i]() { addTo(i); });
    }
    if (!playlists.isEmpty())
    {
        sub->addSeparator();
    }
    sub->addAction(MenuIcon(theme::Icon::Plus, m_darkMode), tr("New Playlist..."), this, [this, paths]() {
        bool ok = false;
        const QString name = QInputDialog::getText(this, tr("New Playlist"), tr("Playlist name:"),
                                                   QLineEdit::Normal, QString(), &ok).trimmed();
        if (!ok || name.isEmpty())
        {
            return;
        }
        PlaylistData playlist;
        playlist.name = name;
        playlist.trackPaths = paths;
        m_library.addPlaylist(playlist);
        refreshPlaylistsList();
        showToast(tr("Saved “%1”.").arg(name));
    });
}

void PlayerWindow::showNowPlayingMenu(const QPoint& globalPos)
{
    if (m_queue.isEmpty() || !m_engine.isLoaded())
    {
        return;
    }
    const TrackInfo track = m_queue.currentTrack();
    const QString path = track.path;

    QMenu menu(this);
    const bool loved = m_songData->isLoved(path);
    menu.addAction(MenuIcon(loved ? theme::Icon::HeartFilled : theme::Icon::Heart, m_darkMode),
                   loved ? tr("Remove from Loved") : tr("Love"), this, &PlayerWindow::toggleLoved);
    addToPlaylistMenu(menu, {track});
    menu.addSeparator();
    menu.addAction(tr("Edit Tags..."), this, [this, path]() { openManualTagDialog(path); });
    menu.addAction(tr("Change Cover..."), this, [this, path]() { openChangeCoverDialog(path); });
    menu.addAction(tr("Show in Folder"), this, [path]() { ShowInFileManager(path); });
    menu.addSeparator();
    QAction* visualizer = menu.addAction(MenuIcon(theme::Icon::Visualizer, m_darkMode), tr("Visualizer"));
    visualizer->setCheckable(true);
    visualizer->setChecked(m_visualizerOn);
    connect(visualizer, &QAction::toggled, this, [this](bool on) { setVisualizerOn(on); });
    if (!m_syncedLyrics.isEmpty())
    {
        QMenu* timing = menu.addMenu(tr("Lyrics timing"));
        timing->addAction(tr("Show 0.5 s Earlier"), this, [this]() { setLyricsOffset(m_lyricsOffsetMs - 500); });
        timing->addAction(tr("Show 0.5 s Later"), this, [this]() { setLyricsOffset(m_lyricsOffsetMs + 500); });
        QAction* reset = timing->addAction(tr("Reset Timing"), this, [this]() { setLyricsOffset(0); });
        reset->setEnabled(m_lyricsOffsetMs != 0);
    }
    menu.exec(globalPos);
}

void PlayerWindow::deleteSmartPlaylist(const QString& id)
{
    const SmartPlaylist* playlist = smartPlaylist(id);
    if (!playlist || playlist->builtIn
        || !ErrorReporter::confirm(this, tr("Delete playlist"),
                                   tr("Delete \"%1\"? This can't be undone.").arg(playlist->displayName())))
    {
        return;
    }
    m_smartPlaylists.removeIf([&id](const SmartPlaylist& p) { return p.id == id; });
    SaveSmartPlaylists(m_smartPlaylists);
    refreshPlaylistsList();
}

// --- .m3u ----------------------------------------------------------------------------

void PlayerWindow::importM3u()
{
    const QString path = QFileDialog::getOpenFileName(this, tr("Import .m3u Playlist"), QString(),
                                                      tr("Playlists") + " (*.m3u *.m3u8)");
    if (path.isEmpty())
    {
        return;
    }
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly))
    {
        ErrorReporter::warn(this, tr("Import .m3u Playlist"), tr("The playlist file couldn't be opened."));
        return;
    }
    const QVector<M3uEntry> entries = ParseM3u(DecodeM3u(file.readAll()));
    const QStringList matched = MatchM3u(entries, m_library.tracks(), QFileInfo(path).absolutePath());
    if (matched.isEmpty())
    {
        ErrorReporter::info(this, tr("Import .m3u Playlist"), tr("No songs found in that playlist."));
        return;
    }
    PlaylistData playlist;
    playlist.name = QFileInfo(path).completeBaseName().trimmed();
    if (playlist.name.isEmpty())
    {
        playlist.name = tr("Imported Playlist");
    }
    playlist.trackPaths = matched;
    m_library.addPlaylist(playlist);
    refreshPlaylistsList();
    showToast(tr("%1: %2 of %3 songs found").arg(playlist.name).arg(matched.size()).arg(entries.size()));
}

void PlayerWindow::exportM3u(const QString& name, const QVector<TrackInfo>& tracks)
{
    QString fileName = name;
    fileName.replace(QRegularExpression(QStringLiteral(R"([\\/:*?"<>|])")), QStringLiteral("_"));
    const QString path = QFileDialog::getSaveFileName(this, tr("Export .m3u Playlist"),
        QDir(QStandardPaths::writableLocation(QStandardPaths::MusicLocation)).filePath(fileName + ".m3u8"),
        tr("Playlists") + " (*.m3u8 *.m3u)");
    if (path.isEmpty())
    {
        return;
    }
    QFile file(path);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate) || file.write(WriteM3u(tracks).toUtf8()) < 0)
    {
        ErrorReporter::warn(this, tr("Export .m3u Playlist"), tr("Couldn't save the playlist file."));
        return;
    }
    showToast(tr("Saved %1 as .m3u8.").arg(name));
}

void PlayerWindow::openPlaylistTracks(int playlistIndex)
{
    QVector<PlaylistData>& playlists = m_library.playlists();
    if (playlistIndex < 0 || playlistIndex >= playlists.size())
    {
        return;
    }

    PlaylistEditDialog dialog(playlists[playlistIndex], m_library.tracks(), this);
    dialog.exec();

    bool deleted = dialog.wasDeleted();
    QString chosenPath = dialog.chosenPath();
    QStringList queuePaths = playlists[playlistIndex].trackPaths; // read before any removal below

    if (deleted)
    {
        m_library.removePlaylistAt(playlistIndex);
    }
    refreshPlaylistsList();

    if (!deleted && !chosenPath.isEmpty())
    {
        QVector<TrackInfo> queue = m_library.buildQueueFromPaths(queuePaths);
        int idx = 0;
        for (int i = 0; i < queue.size(); ++i)
        {
            if (queue[i].path == chosenPath)
            {
                idx = i;
                break;
            }
        }
        setQueueAndPlay(queue, idx, playlists[playlistIndex].name);
    }
}

// --- Queue / transport ---------------------------------------------

void PlayerWindow::setQueueAndPlay(const QVector<TrackInfo>& queue, int startIndex, const QString& context)
{
    if (queue.isEmpty())
    {
        return;
    }
    rememberLongTrackPosition(); // the track being left, while the queue still points at it
    abortCrossfade();
    m_queueContext = context;
    m_queue.setQueue(queue, startIndex);
    loadTrack(m_queue.currentTrack());
}

// --- Up Next ----------------------------------------------------------------

void PlayerWindow::settleHandOff()
{
    if (!m_engine.isCrossfading())
    {
        m_queue.cancelPendingNext(); // a pick made for an upcoming hand-off
        return;
    }
    if (!m_gaplessHandOff && m_engine.isIncomingAudible())
    {
        finalizeCrossfade(); // already audible -- let it become the current track
    }
    else
    {
        abortCrossfade();
        applyVolume();
    }
}

void PlayerWindow::playNextTracks(const QVector<TrackInfo>& tracks)
{
    if (tracks.isEmpty())
    {
        return;
    }
    if (m_queue.isEmpty())
    {
        setQueueAndPlay(tracks, 0); // nothing playing yet -- just play them
        return;
    }
    settleHandOff();
    m_queue.playNext(tracks);
    refreshUpNext();
    showToast(tracks.size() == 1 ? tr("“%1” plays next.").arg(tracks.first().title)
                                 : tr("%1 songs play next.").arg(tracks.size()));
}

void PlayerWindow::addTracksToQueue(const QVector<TrackInfo>& tracks)
{
    if (tracks.isEmpty())
    {
        return;
    }
    if (m_queue.isEmpty())
    {
        setQueueAndPlay(tracks, 0);
        return;
    }
    settleHandOff();
    m_queue.addToQueue(tracks);
    refreshUpNext();
    showToast(tracks.size() == 1 ? tr("Added “%1” to the queue.").arg(tracks.first().title)
                                 : tr("Added %1 songs to the queue.").arg(tracks.size()));
}

void PlayerWindow::setUpNextVisible(bool visible)
{
    m_upNextPanel->setVisible(visible);
    m_queueButton->setChecked(visible);
    refreshUpNext();
}

void PlayerWindow::refreshUpNext()
{
    if (!m_upNextPanel->isVisible())
    {
        return; // rebuilt when it's opened
    }

    UpNextContent content;
    if (!m_queue.isEmpty() && m_engine.isLoaded())
    {
        content.current = m_queue.currentTrack();
    }
    for (const PlaybackQueue::QueuedTrack& queued : m_queue.manualQueue())
    {
        content.playingNext << queued.track;
    }
    content.contextName = m_queueContext;
    // History: what played before the current track (the last entry is the current one).
    const int pastCount = m_playHistory.size() - (content.current ? 1 : 0);
    for (int i = qMax(0, pastCount - 20); i < pastCount; ++i)
    {
        content.history << m_playHistory[i];
    }
    if (std::optional<QVector<int>> upcoming = m_queue.upcoming(300))
    {
        QVector<QPair<int, TrackInfo>> items;
        for (int index : *upcoming)
        {
            items << qMakePair(index, m_queue.items()[index]);
        }
        content.upcoming = items;
    }
    content.smartShuffle = m_queue.shuffleMode() == ShuffleMode::Smart;
    content.repeating = m_queue.repeatMode() != RepeatMode::Off;
    m_upNextPanel->setContent(content);
}

void PlayerWindow::saveQueueAsPlaylist()
{
    QStringList paths;
    if (!m_queue.isEmpty())
    {
        paths << m_queue.currentTrack().path;
    }
    for (const PlaybackQueue::QueuedTrack& queued : m_queue.manualQueue())
    {
        paths << queued.track.path;
    }
    for (int index : m_queue.upcoming(5000).value_or(QVector<int>()))
    {
        paths << m_queue.items()[index].path;
    }
    if (paths.isEmpty())
    {
        ErrorReporter::info(this, tr("Nothing to save"), tr("The queue is empty -- play something first."));
        return;
    }

    bool ok = false;
    const QString name = QInputDialog::getText(this, tr("Save Queue as Playlist"), tr("Playlist name:"),
                                               QLineEdit::Normal, tr("Queue"), &ok).trimmed();
    if (!ok)
    {
        return;
    }
    PlaylistData playlist;
    playlist.name = name.isEmpty() ? tr("Queue") : name;
    playlist.trackPaths = paths;
    m_library.addPlaylist(playlist);
    refreshPlaylistsList();
    showToast(tr("Saved the queue as “%1”.").arg(playlist.name));
}

void PlayerWindow::showToast(const QString& message)
{
    m_toast->setText(message);
    m_toast->adjustSize();
    // Centered just above the player bar.
    m_toast->move((width() - m_toast->width()) / 2, height() - m_toast->height() - 130);
    m_toast->raise();
    m_toast->show();
    m_toastTimer->start(2500);
}

void PlayerWindow::recordHistory(const TrackInfo& track)
{
    if (!m_playHistory.isEmpty() && m_playHistory.last().path == track.path)
    {
        return; // the same track again (e.g. restored on launch, or Repeat One)
    }
    m_playHistory << track;
    if (m_playHistory.size() > 100)
    {
        m_playHistory.removeFirst();
    }
}

namespace {

QString QueueStatePath()
{
    return AppDataFolder() + "/queue.json";
}

QJsonArray PathsToJson(const QVector<TrackInfo>& tracks)
{
    QJsonArray array;
    for (const TrackInfo& t : tracks)
    {
        array << t.path;
    }
    return array;
}

} // namespace

// The queue as it was at exit -- the list, where it was, Playing Next, the
// shuffle/repeat modes, the position and History -- so the next launch
// picks up where you stopped (paused), like the iOS app.
void PlayerWindow::saveQueueState()
{
    QJsonObject state;
    if (!m_queue.isEmpty() && m_queue.size() > 0)
    {
        state["list"] = PathsToJson(m_queue.items());
        state["index"] = m_queue.currentIndex();
        state["context"] = m_queueContext;
        if (m_queue.isCurrentFromManualQueue())
        {
            state["currentManual"] = m_queue.currentTrack().path;
        }
        QVector<TrackInfo> playingNext;
        for (const PlaybackQueue::QueuedTrack& queued : m_queue.manualQueue())
        {
            playingNext << queued.track;
        }
        state["playingNext"] = PathsToJson(playingNext);
        state["position"] = m_engine.isLoaded() ? static_cast<double>(m_engine.cursorSeconds()) : 0.0;
    }
    state["shuffle"] = static_cast<int>(m_queue.shuffleMode());
    state["repeat"] = static_cast<int>(m_queue.repeatMode());
    state["history"] = PathsToJson(m_playHistory);

    WriteJsonFile(QueueStatePath(), state);
}

void PlayerWindow::restoreQueueState()
{
    const QJsonObject state = ReadJsonFile(QueueStatePath());
    if (state.isEmpty())
    {
        return; // first launch, or never played anything
    }

    // Files deleted or moved since are left out.
    auto tracksFrom = [this](const QJsonArray& paths) {
        QVector<TrackInfo> tracks;
        for (const QJsonValue& value : paths)
        {
            const QString path = value.toString();
            if (!path.isEmpty() && QFileInfo::exists(path))
            {
                tracks << m_library.findTrackInfo(path);
            }
        }
        return tracks;
    };

    m_playHistory = tracksFrom(state["history"].toArray());

    const auto shuffle = static_cast<ShuffleMode>(qBound(0, state["shuffle"].toInt(), 2));
    const auto repeat = static_cast<RepeatMode>(qBound(0, state["repeat"].toInt(), 2));

    // The list: find where the saved index's track ended up after dropping missing files.
    const QJsonArray savedList = state["list"].toArray();
    const int savedIndex = state["index"].toInt();
    QVector<TrackInfo> list;
    int index = 0;
    for (int i = 0; i < savedList.size(); ++i)
    {
        const QString path = savedList[i].toString();
        if (!QFileInfo::exists(path))
        {
            continue;
        }
        if (i <= savedIndex)
        {
            index = list.size();
        }
        list << m_library.findTrackInfo(path);
    }

    std::optional<TrackInfo> currentManual;
    const QString manualPath = state["currentManual"].toString();
    if (!manualPath.isEmpty() && QFileInfo::exists(manualPath))
    {
        currentManual = m_library.findTrackInfo(manualPath);
    }

    if (list.isEmpty())
    {
        // Nothing to resume -- just the modes.
        while (m_queue.shuffleMode() != shuffle)
        {
            m_queue.cycleShuffleMode();
        }
        while (m_queue.repeatMode() != repeat)
        {
            m_queue.cycleRepeatMode();
        }
        updateShuffleRepeatLabels();
        return;
    }

    m_queueContext = state["context"].toString();
    m_queue.restore(list, index, currentManual, tracksFrom(state["playingNext"].toArray()), shuffle, repeat);
    updateShuffleRepeatLabels();
    loadTrack(m_queue.currentTrack(), /*autoplay=*/false, static_cast<float>(state["position"].toDouble()));
    // The bar's title label has no real width yet at startup -- shorten it once it's laid out.
    QTimer::singleShot(0, this, &PlayerWindow::applyElidedMiniTitle);
}
void PlayerWindow::playTracksTableRow(int row)
{
    // Queue = every row currently shown, in the table's visual (possibly
    // sorted) order -- so Next/Previous follow what's on screen rather than
    // some hidden original order. Rows hidden by the search filter are left
    // out, so the start index is counted among visible rows only.
    QVector<TrackInfo> queue;
    int startIndex = 0;
    for (int r = 0; r < m_tracksTable->rowCount(); ++r)
    {
        if (m_tracksTable->isRowHidden(r))
        {
            continue;
        }
        if (r == row)
        {
            startIndex = queue.size();
        }
        QString path = m_tracksTable->item(r, 0)->data(Qt::UserRole).toString();
        queue << m_library.findTrackInfo(path);
    }
    setQueueAndPlay(queue, startIndex, tr("Tracks"));
}

QVector<TrackInfo> PlayerWindow::selectedTracks() const
{
    QVector<int> rows;
    for (const QModelIndex& index : m_tracksTable->selectionModel()->selectedRows())
    {
        rows << index.row();
    }
    std::sort(rows.begin(), rows.end()); // rows are already in on-screen (sorted) order
    QVector<TrackInfo> tracks;
    for (int row : rows)
    {
        tracks << m_library.findTrackInfo(m_tracksTable->item(row, 0)->data(Qt::UserRole).toString());
    }
    return tracks;
}

void PlayerWindow::showTrackContextMenu(const QPoint& pos)
{
    const QModelIndex clicked = m_tracksTable->indexAt(pos);
    if (!clicked.isValid())
    {
        return;
    }
    // Right-clicking outside the selection acts on just that row, like Explorer.
    if (!m_tracksTable->selectionModel()->isRowSelected(clicked.row(), QModelIndex()))
    {
        m_tracksTable->selectRow(clicked.row());
    }
    m_tracksTable->setCurrentCell(clicked.row(), clicked.column(), QItemSelectionModel::NoUpdate);

    const QVector<TrackInfo> tracks = selectedTracks();
    const bool single = tracks.size() == 1;

    QMenu menu(this);
    menu.addAction(MenuIcon(theme::Icon::Play, m_darkMode), single ? tr("Play") : tr("Play %1 Songs").arg(tracks.size()),
                   this, [this, tracks, single, row = clicked.row()]() {
        if (single)
        {
            playTracksTableRow(row); // the whole visible list, from here -- same as double-click
        }
        else
        {
            setQueueAndPlay(tracks, 0, tr("Selection"));
        }
    });
    menu.addAction(MenuIcon(theme::Icon::Next, m_darkMode), tr("Play Next"),
                   this, [this, tracks]() { playNextTracks(tracks); });
    menu.addAction(MenuIcon(theme::Icon::Queue, m_darkMode), tr("Add to Queue"),
                   this, [this, tracks]() { addTracksToQueue(tracks); });
    addToPlaylistMenu(menu, tracks);
    if (single)
    {
        const QString path = tracks.first().path;
        menu.addSeparator();
        menu.addAction(tr("Edit Tags..."), this, [this, path]() { openManualTagDialog(path); });
        menu.addAction(tr("Change Cover..."), this, [this, path]() { openChangeCoverDialog(path); });
        menu.addAction(tr("Show in Folder"), this, [path]() { ShowInFileManager(path); });
    }
    menu.exec(m_tracksTable->viewport()->mapToGlobal(pos));
}

// Gives a list whose rows each stand for several tracks the Play / Shuffle /
// Play Next / Add to Queue menu: album and artist rows carry their tracks'
// paths, playlist rows (playlistRows) their playlist index.
void PlayerWindow::enableTracksMenu(QListWidget* list, bool playlistRows)
{
    list->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(list, &QListWidget::customContextMenuRequested, this, [this, list, playlistRows](const QPoint& pos) {
        QListWidgetItem* item = list->itemAt(pos);
        if (!item)
        {
            return;
        }
        const QPoint globalPos = list->viewport()->mapToGlobal(pos);
        if (!playlistRows)
        {
            showTracksMenu(globalPos, item->data(Qt::UserRole).toStringList(), item->data(kGroupNameRole).toString());
            return;
        }
        const QString smartId = item->data(kSmartIdRole).toString();
        if (!smartId.isEmpty())
        {
            const SmartPlaylist* playlist = smartPlaylist(smartId);
            if (!playlist)
            {
                return;
            }
            const QVector<TrackInfo> tracks = smartPlaylistTracks(*playlist);
            QStringList paths;
            for (const TrackInfo& t : tracks)
            {
                paths << t.path;
            }
            const QString name = playlist->displayName();
            const bool builtIn = playlist->builtIn;
            showTracksMenu(globalPos, paths, name, [this, smartId, name, tracks, builtIn](QMenu& menu) {
                QAction* exportAction = menu.addAction(tr("Export .m3u Playlist..."), this,
                                                       [this, name, tracks]() { exportM3u(name, tracks); });
                exportAction->setEnabled(!tracks.isEmpty());
                if (!builtIn)
                {
                    menu.addAction(tr("Edit Rules..."), this, [this, smartId]() { editSmartPlaylist(smartId); });
                    menu.addAction(tr("Delete Playlist"), this, [this, smartId]() { deleteSmartPlaylist(smartId); });
                }
            });
            return;
        }
        const int index = item->data(Qt::UserRole).toInt();
        if (index >= 0 && index < m_library.playlists().size())
        {
            const PlaylistData& playlist = m_library.playlists()[index];
            const QString name = playlist.name;
            const QStringList paths = playlist.trackPaths;
            showTracksMenu(globalPos, paths, name, [this, name, paths, index](QMenu& menu) {
                QAction* exportAction = menu.addAction(tr("Export .m3u Playlist..."), this, [this, name, paths]() {
                    exportM3u(name, m_library.buildQueueFromPaths(paths));
                });
                exportAction->setEnabled(!paths.isEmpty());
                menu.addAction(tr("Edit Playlist..."), this, [this, index]() { openPlaylistTracks(index); });
                menu.addAction(tr("Delete Playlist"), this, [this, index]() { deletePlaylistAt(index); });
            });
        }
    });
}

void PlayerWindow::showTracksMenu(const QPoint& globalPos, const QStringList& paths, const QString& name,
                                  const std::function<void(QMenu&)>& extra)
{
    if (paths.isEmpty() && !extra)
    {
        return;
    }
    const QVector<TrackInfo> tracks = m_library.buildQueueFromPaths(paths);

    QMenu menu(this);
    QList<QAction*> playActions;
    playActions << menu.addAction(MenuIcon(theme::Icon::Play, m_darkMode), tr("Play"),
                                  this, [this, tracks, name]() { setQueueAndPlay(tracks, 0, name); });
    playActions << menu.addAction(MenuIcon(theme::Icon::Shuffle, m_darkMode), tr("Shuffle"),
                                  this, [this, tracks, name]() {
        if (m_queue.shuffleMode() == ShuffleMode::Off)
        {
            cycleShuffleMode(); // Off -> Random
        }
        setQueueAndPlay(tracks, static_cast<int>(QRandomGenerator::global()->bounded(tracks.size())), name);
    });
    menu.addSeparator();
    playActions << menu.addAction(MenuIcon(theme::Icon::Next, m_darkMode), tr("Play Next"),
                                  this, [this, tracks]() { playNextTracks(tracks); });
    playActions << menu.addAction(MenuIcon(theme::Icon::Queue, m_darkMode), tr("Add to Queue"),
                                  this, [this, tracks]() { addTracksToQueue(tracks); });
    for (QAction* action : playActions)
    {
        action->setEnabled(!tracks.isEmpty()); // an empty smart playlist still offers its own actions
    }
    if (extra)
    {
        menu.addSeparator();
        extra(menu);
    }
    menu.exec(globalPos);
}

void PlayerWindow::next(bool fromAutoAdvance)
{
    rememberLongTrackPosition();
    if (!fromAutoAdvance)
    {
        abortCrossfade(); // a manual skip always hard-cuts, discarding any in-progress fade
    }

    if (m_queue.isEmpty())
    {
        return;
    }

    if (fromAutoAdvance)
    {
        // Diagnostic: shows in warnings.log why a track did (or didn't) advance.
        // shuffle: 0=Off 1=Random 2=Smart   repeat: 0=Off 1=All 2=One
        ErrorReporter::logOnly(QString("Auto-advance: shuffle=%1 repeat=%2 index=%3 queueSize=%4")
            .arg(static_cast<int>(m_queue.shuffleMode()))
            .arg(static_cast<int>(m_queue.repeatMode()))
            .arg(m_queue.currentIndex())
            .arg(m_queue.size()));
    }

    if (fromAutoAdvance && m_queue.repeatMode() == RepeatMode::One)
    {
        m_countedPath.clear(); // the repeat is another play
        seekTo(trackBegin(m_queue.currentTrack()));
        m_engine.play();
        updateDiscordPresence();
        return;
    }

    if (!m_queue.moveNext())
    {
        return; // end of queue, nothing more to play
    }
    loadTrack(m_queue.currentTrack());
}

void PlayerWindow::previous()
{
    rememberLongTrackPosition();
    abortCrossfade();
    if (m_queue.isEmpty())
    {
        return;
    }
    if (m_queue.movePrevious())
    {
        loadTrack(m_queue.currentTrack());
    }
}

void PlayerWindow::updateShuffleRepeatLabels()
{
    // Icon buttons now: the mode is shown by the glyph (a dot for Smart
    // shuffle, a "1" for Repeat One), the accent color when it's on, and
    // the tooltip spelling it out.
    QString shuffleText;
    theme::Icon shuffleIcon = theme::Icon::Shuffle;
    switch (m_queue.shuffleMode())
    {
        case ShuffleMode::Off:    shuffleText = tr("Shuffle: Off"); break;
        case ShuffleMode::Random: shuffleText = tr("Shuffle: Random"); break;
        case ShuffleMode::Smart:  shuffleText = tr("Shuffle: Smart"); shuffleIcon = theme::Icon::ShuffleSmart; break;
    }

    QString repeatText;
    theme::Icon repeatIcon = theme::Icon::Repeat;
    switch (m_queue.repeatMode())
    {
        case RepeatMode::Off: repeatText = tr("Repeat: Off"); break;
        case RepeatMode::All: repeatText = tr("Repeat: All"); break;
        case RepeatMode::One: repeatText = tr("Repeat: One"); repeatIcon = theme::Icon::RepeatOne; break;
    }

    const bool shuffleOn = m_queue.shuffleMode() != ShuffleMode::Off;
    const bool repeatOn = m_queue.repeatMode() != RepeatMode::Off;
    const theme::Tokens& t = theme::tokens(m_darkMode);
    const QIcon shuffle = theme::icon(shuffleIcon, t.muted, t.accent, t.border);
    const QIcon repeat = theme::icon(repeatIcon, t.muted, t.accent, t.border);
    // Now Playing's: light, whatever the theme.
    const QColor npMuted(246, 237, 231, 165);
    const QIcon bigShuffle = theme::icon(shuffleIcon, npMuted, t.accent, QColor(246, 237, 231, 70));
    const QIcon bigRepeat = theme::icon(repeatIcon, npMuted, t.accent, QColor(246, 237, 231, 70));

    for (QPushButton* button : {m_shuffleButton, m_bigShuffleButton})
    {
        if (button)
        {
            button->setIcon(button == m_bigShuffleButton ? bigShuffle : shuffle);
            button->setChecked(shuffleOn);
            button->setToolTip(shuffleText + "  (Ctrl+S)");
        }
    }
    for (QPushButton* button : {m_repeatButton, m_bigRepeatButton})
    {
        if (button)
        {
            button->setIcon(button == m_bigRepeatButton ? bigRepeat : repeat);
            button->setChecked(repeatOn);
            button->setToolTip(repeatText + "  (Ctrl+R)");
        }
    }
}

void PlayerWindow::cycleRepeatMode()
{
    settleHandOff(); // the next pick depends on the mode
    m_queue.cycleRepeatMode();
    updateShuffleRepeatLabels();
    refreshUpNext();
}

void PlayerWindow::cycleShuffleMode()
{
    settleHandOff(); // the next pick depends on the mode
    m_queue.cycleShuffleMode();
    updateShuffleRepeatLabels();
    refreshUpNext();
}

void PlayerWindow::toggleReplayGain()
{
    m_replayGainEnabled = !m_replayGainEnabled;
    m_replayGainButton->setChecked(m_replayGainEnabled);
    m_replayGainSwitch->setChecked(m_replayGainEnabled); // its toggled handler sees no change -- no loop
    applyVolume();
}

// --- Playback ----------------------------------------------------------

void PlayerWindow::setupShortcuts()
{
    // Plain Left/Right and Space don't get stolen from the search bar or
    // tracks table while they have focus -- QLineEdit/QAbstractItemView
    // already claim those keys for text/row navigation before a QShortcut
    // (default Qt::WindowShortcut context) gets a chance at them, and the
    // same goes for Space vs. a focused QPushButton's own click-on-Space.

    auto* playPause = new QShortcut(QKeySequence(Qt::Key_Space), this);
    connect(playPause, &QShortcut::activated, this, &PlayerWindow::togglePlayPause);

    auto* nextShortcut = new QShortcut(QKeySequence(Qt::CTRL | Qt::Key_Right), this);
    connect(nextShortcut, &QShortcut::activated, this, [this]() { next(false); });

    auto* prevShortcut = new QShortcut(QKeySequence(Qt::CTRL | Qt::Key_Left), this);
    connect(prevShortcut, &QShortcut::activated, this, &PlayerWindow::previous);

    auto* seekForward = new QShortcut(QKeySequence(Qt::Key_Right), this);
    connect(seekForward, &QShortcut::activated, this, [this]() { seekBySeconds(5.0f); });

    auto* seekBack = new QShortcut(QKeySequence(Qt::Key_Left), this);
    connect(seekBack, &QShortcut::activated, this, [this]() { seekBySeconds(-5.0f); });

    auto* volumeUp = new QShortcut(QKeySequence(Qt::CTRL | Qt::Key_Up), this);
    connect(volumeUp, &QShortcut::activated, this, [this]() { adjustVolume(5); });

    auto* volumeDown = new QShortcut(QKeySequence(Qt::CTRL | Qt::Key_Down), this);
    connect(volumeDown, &QShortcut::activated, this, [this]() { adjustVolume(-5); });

    auto* muteShortcut = new QShortcut(QKeySequence(Qt::CTRL | Qt::Key_M), this);
    connect(muteShortcut, &QShortcut::activated, this, &PlayerWindow::toggleMute);

    auto* shuffleShortcut = new QShortcut(QKeySequence(Qt::CTRL | Qt::Key_S), this);
    connect(shuffleShortcut, &QShortcut::activated, this, &PlayerWindow::cycleShuffleMode);

    auto* repeatShortcut = new QShortcut(QKeySequence(Qt::CTRL | Qt::Key_R), this);
    connect(repeatShortcut, &QShortcut::activated, this, &PlayerWindow::cycleRepeatMode);

    auto* fullScreenShortcut = new QShortcut(QKeySequence(Qt::Key_F11), this);
    connect(fullScreenShortcut, &QShortcut::activated, this, &PlayerWindow::toggleFullScreen);

    auto* searchFocusShortcut = new QShortcut(QKeySequence(Qt::CTRL | Qt::Key_F), this);
    connect(searchFocusShortcut, &QShortcut::activated, this, [this]() {
        m_searchBar->setFocus();
        m_searchBar->selectAll();
    });

    // Dedicated media keys, where the keyboard has them.
    auto* mediaTogglePlayPause = new QShortcut(QKeySequence(Qt::Key_MediaTogglePlayPause), this);
    connect(mediaTogglePlayPause, &QShortcut::activated, this, &PlayerWindow::togglePlayPause);

    auto* mediaPlay = new QShortcut(QKeySequence(Qt::Key_MediaPlay), this);
    connect(mediaPlay, &QShortcut::activated, this, &PlayerWindow::togglePlayPause);

    auto* mediaStop = new QShortcut(QKeySequence(Qt::Key_MediaStop), this);
    connect(mediaStop, &QShortcut::activated, this, &PlayerWindow::stop);

    auto* mediaNext = new QShortcut(QKeySequence(Qt::Key_MediaNext), this);
    connect(mediaNext, &QShortcut::activated, this, [this]() { next(false); });

    auto* mediaPrev = new QShortcut(QKeySequence(Qt::Key_MediaPrevious), this);
    connect(mediaPrev, &QShortcut::activated, this, &PlayerWindow::previous);
}

void PlayerWindow::togglePlayPause()
{
    if (!m_engine.isLoaded())
    {
        return;
    }
    if (m_engine.isPlaying())
    {
        pause();
    }
    else
    {
        play();
    }
}

void PlayerWindow::seekBySeconds(float deltaSeconds)
{
    if (!m_engine.isLoaded())
    {
        return;
    }
    float target = qBound(0.0f, m_engine.cursorSeconds() + deltaSeconds, m_engine.lengthSeconds());
    seekTo(target);
}

void PlayerWindow::seekTo(float seconds)
{
    // A seek mid-crossfade used to move only the outgoing track: seeking
    // back left the incoming one running silently at volume 0 (so it was
    // already partway through once the fade restarted). Drop the fade
    // instead -- the queue keeps its pending pick, so if the new position
    // is still inside the crossfade window the next tick restarts the fade
    // into the same track, from its beginning.
    if (m_engine.isCrossfading())
    {
        m_engine.abortCrossfade(); // also drops a gapless hand-off's scheduled stop
        m_gaplessHandOff = false;
        applyVolume(); // the fade had turned the outgoing track down
    }
    if (m_abState == AbState::Looping && (seconds < m_abA - 0.05f || seconds > m_abB + 0.05f))
    {
        clearAbRepeat(); // jumped out of the loop
    }
    m_engine.seekToSeconds(seconds);
    m_wasAtEnd = false;
    updateDiscordPresence(); // its elapsed-time counter would otherwise keep the pre-seek position
}

void PlayerWindow::updateDiscordPresence()
{
    if (!m_discordPresenceEnabled || m_queue.isEmpty() || !m_engine.isPlaying())
    {
        return;
    }
    const TrackInfo& info = m_queue.currentTrack();
    m_discordPresence.setNowPlaying(
        info.title.isEmpty() ? QFileInfo(info.path).completeBaseName() : info.title,
        info.artist, static_cast<int>(m_engine.cursorSeconds()), static_cast<int>(m_engine.lengthSeconds()));
}

void PlayerWindow::adjustVolume(int delta)
{
    m_volumeSlider->setValue(qBound(0, m_volumeSlider->value() + delta, 200));
}

void PlayerWindow::toggleMute()
{
    if (m_volumeSlider->value() > 0)
    {
        m_volumeBeforeMute = m_volumeSlider->value();
        m_volumeSlider->setValue(0);
    }
    else
    {
        m_volumeSlider->setValue(m_volumeBeforeMute > 0 ? m_volumeBeforeMute : 100);
    }
}

void PlayerWindow::setControlsEnabled(bool enabled)
{
    m_playButton->setEnabled(enabled);
    m_stopButton->setEnabled(enabled);
    if (m_windowsMedia)
    {
        m_windowsMedia->setPlaybackState(enabled && m_engine.isLoaded(), m_engine.isPlaying());
    }
    m_prevButton->setEnabled(enabled);
    m_nextButton->setEnabled(enabled);
    m_seekSlider->setEnabled(enabled);
    // The Now Playing page is built after the player bar, so these don't
    // exist yet on the very first (constructor-time) call.
    for (QWidget* widget : std::initializer_list<QWidget*>{m_bigPlayButton, m_bigPrevButton, m_bigNextButton, m_bigSeekSlider})
    {
        if (widget)
        {
            widget->setEnabled(enabled);
        }
    }
}

void PlayerWindow::applyTheme()
{
    m_darkMode = m_themeMode == 2
        || (m_themeMode == 0 && QGuiApplication::styleHints()->colorScheme() != Qt::ColorScheme::Light);
    qApp->setPalette(theme::palette(m_darkMode));
    qApp->setStyleSheet(theme::styleSheet(m_darkMode));
    m_themeButton->setToolTip(m_darkMode ? tr("Switch to light theme") : tr("Switch to dark theme"));
    refreshIcons();
    refreshPlaylistsList(); // the smart playlists' icons
}

void PlayerWindow::refreshIcons()
{
    const theme::Tokens& t = theme::tokens(m_darkMode);

    // Indexed by tab index, same as m_navButtons (see buildSidebar).
    const theme::Icon navIcons[] = {
        theme::Icon::Album, theme::Icon::Track, theme::Icon::Artist, theme::Icon::Folder,
        theme::Icon::Playlist, theme::Icon::Equalizer, theme::Icon::Wallpaper, theme::Icon::Settings,
        theme::Icon::Podcast,
    };
    for (int i = 0; i < m_navButtons.size(); ++i)
    {
        m_navButtons[i]->setIcon(theme::icon(navIcons[i], t.muted, t.accent));
    }
    m_openButton->setIcon(theme::icon(theme::Icon::OpenFile, t.muted));
    m_newPlaylistButton->setIcon(theme::icon(theme::Icon::Plus, t.muted));
    m_searchIconAction->setIcon(theme::icon(theme::Icon::Search, t.muted, QColor(), QColor(), 16));
    m_themeButton->setIcon(theme::icon(m_darkMode ? theme::Icon::Sun : theme::Icon::Moon, t.muted));

    m_prevButton->setIcon(theme::icon(theme::Icon::Previous, t.text, QColor(), t.border));
    m_nextButton->setIcon(theme::icon(theme::Icon::Next, t.text, QColor(), t.border));
    // Now Playing is always dark: its icons are light in both themes.
    const QColor npText(246, 237, 231);
    const QColor npMuted(246, 237, 231, 165);
    const QColor npDisabled(246, 237, 231, 70);
    m_bigPrevButton->setIcon(theme::icon(theme::Icon::Previous, npText, QColor(), npDisabled, 26));
    m_bigNextButton->setIcon(theme::icon(theme::Icon::Next, npText, QColor(), npDisabled, 26));
    m_stopButton->setIcon(theme::icon(theme::Icon::Stop, t.muted, QColor(), t.border, 16));
    m_backButton->setIcon(theme::icon(theme::Icon::Back, npText));
    m_podcastBackButton->setIcon(theme::icon(theme::Icon::Back, t.text));
    m_fullscreenButton->setIcon(theme::icon(theme::Icon::FullScreen, npMuted));
    m_sleepButton->setIcon(theme::icon(theme::Icon::Timer, npMuted, t.accent));
    m_visualizerButton->setIcon(theme::icon(theme::Icon::Visualizer, npMuted, t.accent));
    m_visualizerStyleGrid->setThemeColors(t.accent, t.border, t.text);
    m_queueButton->setIcon(theme::icon(theme::Icon::Queue, t.muted, t.accent));
    m_bigQueueButton->setIcon(theme::icon(theme::Icon::Queue, npMuted));
    m_upNextPanel->setAccentColor(t.accent);
    m_upNextPanel->setHeaderIcons(theme::icon(theme::Icon::More, t.muted), theme::icon(theme::Icon::Close, t.muted));
    {
        // Grey tile with a note, for tracks without cover art (as on iOS).
        QPixmap tile(80, 80);
        tile.fill(Qt::transparent);
        QPainter p(&tile);
        p.setRenderHint(QPainter::Antialiasing);
        p.setPen(Qt::NoPen);
        p.setBrush(t.surfaceHi);
        p.drawRoundedRect(QRectF(0, 0, 80, 80), 12, 12);
        p.drawPixmap(20, 20, theme::icon(theme::Icon::Track, t.muted, QColor(), QColor(), 40).pixmap(40, 40).scaled(40, 40));
        p.end();
        tile.setDevicePixelRatio(2.0);
        m_upNextPanel->setPlaceholderCover(tile);
    }

    for (const auto& [label, kind] : m_settingsIcons)
    {
        // Chevrons and the lock are quiet; the Smart Options glyphs use the accent, as on iOS.
        const bool quiet = kind == theme::Icon::Chevron || kind == theme::Icon::Lock;
        const int size = kind == theme::Icon::Chevron ? 16 : 20;
        label->setPixmap(theme::icon(kind, quiet ? t.muted : t.accent, QColor(), QColor(), size).pixmap(size, size));
    }

    if (m_coverPlaceholderShown)
    {
        showCoverPlaceholders();
    }

    updateShuffleRepeatLabels();
    m_shownPlayingState = -1; // force the play/pause glyphs to repaint in the new colors
    updatePlayPauseIcons();
    updateVolumeIcons();
}

void PlayerWindow::updatePlayPauseIcons()
{
    const int playing = m_engine.isPlaying() ? 1 : 0;
    if (playing == m_shownPlayingState)
    {
        return; // called every tick -- only repaint on an actual change
    }
    m_shownPlayingState = playing;
    m_visualizer->setPlaying(playing); // the record only turns while music plays
    if (m_windowsMedia)
    {
        m_windowsMedia->setPlaybackState(m_engine.isLoaded(), playing);
    }
    if (m_trayPlayAction)
    {
        m_trayPlayAction->setText(playing ? tr("Pause") : tr("Play"));
    }
    m_visualizerStyleGrid->setPlaying(playing);

    const theme::Tokens& t = theme::tokens(m_darkMode);
    const theme::Icon kind = playing ? theme::Icon::Pause : theme::Icon::Play;
    const QString tip = playing ? tr("Pause (Space)") : tr("Play (Space)");
    m_playButton->setIcon(theme::icon(kind, t.accentText, QColor(), t.muted, 20));
    m_playButton->setToolTip(tip);
    m_bigPlayButton->setIcon(theme::icon(kind, t.accentText, QColor(), t.muted, 28));
    m_bigPlayButton->setToolTip(tip);
}

void PlayerWindow::showCoverPlaceholders()
{
    // Generated art, its colour from the title: every song without a cover looks a little different.
    const QString seed = m_queue.isEmpty() ? QString() : m_queue.currentTrack().title;
    m_coverArtLabel->setAlignment(Qt::AlignCenter);
    m_bigCoverArtLabel->setAlignment(Qt::AlignCenter);
    m_coverArtLabel->setPixmap(theme::generatedArt(seed, m_coverArtLabel->width(), 8));
    m_bigCoverArtLabel->setPixmap(theme::generatedArt(seed, m_bigCoverArtLabel->width(), 16));
    m_coverPlaceholderShown = true;
}

void PlayerWindow::updateAmbientBackground(const QByteArray& coverArt, const QString& seed)
{
    // The cover shrunk to a few pixels and scaled back up is a smooth blur of
    // its colours; darkened so light text always reads on it.
    QImage source;
    if (coverArt.isEmpty() || !source.loadFromData(coverArt))
    {
        source = theme::generatedArt(seed, 32, 0).toImage();
    }
    const QImage tiny = source.scaled(12, 8, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    QImage blurred = tiny.scaled(192, 108, Qt::IgnoreAspectRatio, Qt::SmoothTransformation)
                         .scaled(960, 540, Qt::IgnoreAspectRatio, Qt::SmoothTransformation)
                         .convertToFormat(QImage::Format_RGB32);
    QPainter p(&blurred);
    p.fillRect(blurred.rect(), QColor(0, 0, 0, 120));
    QLinearGradient shade(0, 0, 0, blurred.height());
    shade.setColorAt(0.0, QColor(0, 0, 0, 40));
    shade.setColorAt(1.0, QColor(0, 0, 0, 150));
    p.fillRect(blurred.rect(), shade);
    p.end();
    m_ambientBackground->setPixmap(QPixmap::fromImage(blurred));
}

void PlayerWindow::updateVolumeIcons()
{
    const theme::Tokens& t = theme::tokens(m_darkMode);
    const QIcon icon = theme::icon(m_volumeSlider->value() == 0 ? theme::Icon::VolumeMute : theme::Icon::Volume, t.muted);
    m_volumeButton->setIcon(icon);
    m_bigVolumeButton->setIcon(theme::icon(m_volumeSlider->value() == 0 ? theme::Icon::VolumeMute : theme::Icon::Volume,
                                           QColor(246, 237, 231, 165))); // Now Playing: always light
}

void PlayerWindow::openFile()
{
    QString path = QFileDialog::getOpenFileName(this, tr("Open MP3"), QString(),
                                                tr("MP3 Files") + " (*.mp3);;" + tr("All Files") + " (*)");
    if (!path.isEmpty())
    {
        // A standalone open isn't part of any library list -- queue of one.
        setQueueAndPlay({m_library.findTrackInfo(path)}, 0);
    }
}

void PlayerWindow::loadTrack(const TrackInfo& info, bool autoplay, float startAt)
{
    if (m_engine.loadAndPlay(info.path, autoplay))
    {
        m_wasAtEnd = false;
        m_handOffBlocked = false;
        m_gaplessHandOff = false;
        m_countedPath.clear(); // each time a song starts, it can count as a play again
        clearAbRepeat();

        // Where to start: a long track resumes where it was left; otherwise
        // Smart Transitions skips silence at the start.
        const float length = m_engine.lengthSeconds();
        // Podcast episodes always continue where they were left; other songs if long and the setting is on.
        const double resume = isPodcast(info.path) || (m_rememberLongTracks && length >= kLongTrackSeconds)
            ? m_songData->resumePosition(info.path) : -1.0;
        if (startAt >= 0.0f)
        {
            m_engine.seekToSeconds(qMin(startAt, qMax(0.0f, length - 1.0f)));
        }
        else if (resume > 15.0 && resume < length - 30.0)
        {
            m_engine.seekToSeconds(static_cast<float>(resume));
        }
        else if (const float begin = trackBegin(info); begin > 0.0f)
        {
            m_engine.seekToSeconds(begin);
        }

        m_currentReplayGainDb = info.replayGainDb;
        applyVolume();
        recordHistory(info);
        updateNowPlayingUi(info);

        m_seekSlider->setRange(0, static_cast<int>(length));

        setControlsEnabled(true);
        updatePlayPauseIcons();
    }
    else
    {
        if (autoplay)
        {
            ErrorReporter::warn(this, tr("Failed to load track"), tr("Could not load: %1").arg(QFileInfo(info.path).fileName()));
        }
        else
        {
            // Restoring last session's track at launch: no popup before the window's even up.
            ErrorReporter::logOnly("Could not restore last track: " + info.path);
        }
        m_titleLabel->setText(tr("Failed to load: %1").arg(QFileInfo(info.path).fileName()));
        m_formatLabel->clear();
        m_coverArtLabel->clear();
        setControlsEnabled(false);
    }
}

void PlayerWindow::updateNowPlayingUi(const TrackInfo& info)
{
    m_lyricsOffsetMs = m_songData->statsFor(info.path).lyricsOffsetMs;
    m_miniTitleFullText = info.title;
    applyElidedMiniTitle();
    // Mini bar's second line: the artist, like most players -- the full
    // format/bitrate line is still on the Now Playing page and in the tooltip.
    m_formatLabel->setText(info.artist.isEmpty() ? FormatAudioInfo(info) : info.artist);
    m_formatLabel->setToolTip(FormatAudioInfo(info));

    resetTitleMarquee(info.title);
    QString subtitle = info.artist;
    if (!info.album.isEmpty())
    {
        subtitle += (subtitle.isEmpty() ? "" : "  •  ") + info.album;
    }
    m_bigSubtitleLabel->setText(subtitle);
    m_bigFormatLabel->setText(FormatAudioInfo(info));

    // Library TrackInfos don't carry cover art (memory) -- read it now, for
    // just the one track being shown.
    const QByteArray coverArt = info.coverArt.isEmpty() ? ReadCoverArt(info.path) : info.coverArt;
    if (!coverArt.isEmpty())
    {
        QPixmap miniCover = theme::roundedPixmap(ScaledCoverArt(coverArt, m_coverArtLabel->size()), 8);
        QPixmap bigCover = theme::roundedPixmap(ScaledCoverArt(coverArt, m_bigCoverArtLabel->size()), 16);
        if (!miniCover.isNull() && !bigCover.isNull())
        {
            m_coverArtLabel->setPixmap(miniCover);
            m_bigCoverArtLabel->setPixmap(bigCover);
            m_coverPlaceholderShown = false;
        }
        else
        {
            showCoverPlaceholders();
        }
    }
    else
    {
        showCoverPlaceholders();
    }

    // Tint the info box's background to roughly match the cover art's
    // dominant color, the way several music apps do -- scoped to just
    // this bounded panel now, not the whole page (a page-wide translucent
    // tint was producing a visible seam artifact at the label boundaries).
    updateVisualizerForTrack(info, coverArt);

    updateAmbientBackground(coverArt, info.title);
    if (m_windowsMedia) // (the song restored at start comes before it exists: setupWindowsIntegration() sends it)
    {
        m_windowsMedia->setTrack(info.title.isEmpty() ? QFileInfo(info.path).completeBaseName() : info.title,
                                 info.artist, info.album, coverArt);
    }
    if (m_tray)
    {
        m_tray->setToolTip(info.artist.isEmpty() ? info.title : info.title + QStringLiteral(" — ") + info.artist);
    }
    if (info.path != m_lastNotifiedPath) // a tag edit re-shows the same song: no second notification
    {
        m_lastNotifiedPath = info.path;
        notifySongStarted(info, coverArt);
    }

    fetchLyricsFor(info);
    updateWallpaperForTrack(info);

    // Uses the real cursor, not 0 -- after a crossfade the new track is
    // already crossfadeSeconds in by the time it becomes "current".
    updateDiscordPresence();
    refreshUpNext();
    updateLoveButton();
    if (isPodcast(info.path))
    {
        refreshPodcasts(); // the episode that's playing, in bold; the last one's time left
    }
}

void PlayerWindow::resetTitleMarquee(const QString& title)
{
    m_titleMarqueeFullText = title;
    m_titleMarqueePhase = 0;
    m_titleMarqueeTicksRemaining = 30; // ~1.5s pause before scrolling starts, if it's even needed
    m_bigTitleScrollArea->horizontalScrollBar()->setValue(0);

    int availableWidth = m_bigTitleScrollArea->viewport()->width();
    if (availableWidth <= 0)
    {
        availableWidth = m_bigTitleScrollArea->width(); // fallback if this runs before the page's ever been shown
    }

    QFontMetrics metrics(m_bigTitleLabel->font());
    m_titleMarqueeNeeded = metrics.horizontalAdvance(title) > availableWidth;

    if (m_titleMarqueeNeeded)
    {
        applyElidedTitleText();
    }
    else
    {
        m_bigTitleLabel->setText(title); // fits as-is -- show it plainly, marquee timer stays a no-op
        m_bigTitleLabel->adjustSize();
    }
}

void PlayerWindow::applyElidedTitleText()
{
    int availableWidth = m_bigTitleScrollArea->viewport()->width();
    QFontMetrics metrics(m_bigTitleLabel->font());
    m_bigTitleLabel->setText(metrics.elidedText(m_titleMarqueeFullText, Qt::ElideRight, availableWidth));
    m_bigTitleLabel->adjustSize();
}

void PlayerWindow::applyElidedMiniTitle()
{
    // Static truncation only -- no marquee/scrolling for the mini bar,
    // just enough so a long title doesn't blow out the compact layout the
    // way it currently does with no eliding at all.
    int availableWidth = m_titleLabel->width();
    if (availableWidth <= 0)
    {
        return; // not laid out yet -- updateNowPlayingUi's next call, or the resize handler, will catch it
    }

    QFontMetrics metrics(m_titleLabel->font());
    m_titleLabel->setText(metrics.elidedText(m_miniTitleFullText, Qt::ElideRight, availableWidth));
}

void PlayerWindow::updateWallpaperForTrack(const TrackInfo& info)
{
    if (!m_videoWallpaperEnabled)
    {
        m_wallpaperWidget->setVideoPath(QString());
        return;
    }

    QString video = m_wallpaperLibrary.resolveVideoFor(info.path);
    m_wallpaperWidget->setVideoPath(video); // empty is fine -- widget handles "no video" itself
}

void PlayerWindow::refreshGlobalWallpaperLabel()
{
    QString path = m_wallpaperLibrary.globalVideoPath();
    m_globalWallpaperLabel->setText(path.isEmpty() ? tr("None") : QFileInfo(path).fileName());
}

void PlayerWindow::chooseGlobalWallpaper()
{
    QString path = QFileDialog::getOpenFileName(this, tr("Choose global wallpaper video"), QString(), tr("Video files") + " (*.mp4)");
    if (path.isEmpty())
    {
        return;
    }

    m_wallpaperLibrary.setGlobalVideoPath(path);
    refreshGlobalWallpaperLabel();

    // If nothing more specific applies to the current track, this should
    // take effect immediately rather than waiting for the next track change.
    if (!m_queue.isEmpty())
    {
        updateWallpaperForTrack(m_queue.currentTrack());
    }
}

void PlayerWindow::clearGlobalWallpaper()
{
    m_wallpaperLibrary.setGlobalVideoPath(QString());
    refreshGlobalWallpaperLabel();

    if (!m_queue.isEmpty())
    {
        updateWallpaperForTrack(m_queue.currentTrack());
    }
}

void PlayerWindow::refreshWallpaperEntriesList()
{
    m_wallpaperEntriesList->clear();
    const QVector<WallpaperEntry>& entries = m_wallpaperLibrary.entries();
    for (int i = 0; i < entries.size(); ++i)
    {
        const WallpaperEntry& entry = entries[i];
        QString label = tr("%1  (%2 tracks)")
            .arg(QFileInfo(entry.videoPath).fileName())
            .arg(entry.trackPaths.size());
        auto* item = new QListWidgetItem(label);
        item->setData(Qt::UserRole, i);
        m_wallpaperEntriesList->addItem(item);
    }
}

void PlayerWindow::addWallpaperEntry()
{
    QString videoPath = QFileDialog::getOpenFileName(this, tr("Choose wallpaper video"), QString(), tr("Video files") + " (*.mp4)");
    if (videoPath.isEmpty())
    {
        return;
    }

    WallpaperTrackPickerDialog dialog(m_library.tracks(), QStringList(), this);
    if (dialog.exec() == QDialog::Accepted)
    {
        WallpaperEntry& entry = m_wallpaperLibrary.addEntry();
        entry.videoPath = videoPath;
        entry.trackPaths = dialog.resultTrackPaths();
        refreshWallpaperEntriesList();

        if (!m_queue.isEmpty())
        {
            updateWallpaperForTrack(m_queue.currentTrack());
        }
    }
}

void PlayerWindow::editWallpaperEntryTracks()
{
    QListWidgetItem* item = m_wallpaperEntriesList->currentItem();
    if (!item)
    {
        ErrorReporter::info(this, tr("No wallpaper selected"), tr("Select a wallpaper from the list first."));
        return;
    }

    int index = item->data(Qt::UserRole).toInt();
    QVector<WallpaperEntry>& entries = m_wallpaperLibrary.entries();
    if (index < 0 || index >= entries.size())
    {
        return;
    }

    WallpaperTrackPickerDialog dialog(m_library.tracks(), entries[index].trackPaths, this);
    if (dialog.exec() == QDialog::Accepted)
    {
        entries[index].trackPaths = dialog.resultTrackPaths();
        refreshWallpaperEntriesList();

        if (!m_queue.isEmpty())
        {
            updateWallpaperForTrack(m_queue.currentTrack());
        }
    }
}

void PlayerWindow::removeSelectedWallpaperEntry()
{
    QListWidgetItem* item = m_wallpaperEntriesList->currentItem();
    if (!item)
    {
        ErrorReporter::info(this, tr("No wallpaper selected"), tr("Select a wallpaper from the list first."));
        return;
    }

    int index = item->data(Qt::UserRole).toInt();
    bool confirmed = ErrorReporter::confirm(this, tr("Remove wallpaper"),
        tr("Remove this wallpaper assignment? This can't be undone."));
    if (confirmed)
    {
        m_wallpaperLibrary.removeEntryAt(index);
        refreshWallpaperEntriesList();

        if (!m_queue.isEmpty())
        {
            updateWallpaperForTrack(m_queue.currentTrack());
        }
    }
}

void PlayerWindow::play()
{
    m_engine.play();
    updatePlayPauseIcons();
    updateDiscordPresence();
}

void PlayerWindow::pause()
{
    // A hand-off whose next track hasn't started yet (gapless, or a
    // beat-matched start) was timed against the clock -- after a pause that
    // timing is wrong, so it's dropped and worked out again near the end.
    if (m_engine.isCrossfading() && !m_engine.isIncomingAudible())
    {
        abortCrossfade();
        applyVolume();
    }
    m_engine.pause();
    rememberLongTrackPosition();
    if (!m_queue.isEmpty() && isPodcast(m_queue.currentTrack().path))
    {
        refreshPodcasts(); // "12:40 left"
    }
    updatePlayPauseIcons();
    if (m_discordPresenceEnabled)
    {
        // No accurate way to show a paused countdown via Discord's
        // timestamp-based activity, so just hide it while paused rather
        // than let it keep counting down as if still playing.
        m_discordPresence.clearPresence();
    }
}

void PlayerWindow::stop()
{
    m_queue.cancelPendingNext();
    m_engine.stop();
    updatePlayPauseIcons();
    if (m_discordPresenceEnabled)
    {
        m_discordPresence.clearPresence();
    }
}

// Combines the volume slider with the current track's ReplayGain value (if
// enabled) into one linear gain applied to the audio engine. ReplayGain
// values are stored in dB, so they need converting to a linear multiplier:
// 10^(dB/20).
void PlayerWindow::applyVolume()
{
    m_engine.setVolume(baseVolumeFor(m_currentReplayGainDb));
}

void PlayerWindow::toggleTheme()
{
    // The quick toggle picks an explicit Light/Dark (leaving "System").
    setThemeMode(m_darkMode ? 1 : 2);
}

void PlayerWindow::seekToSliderValue()
{
    seekTo(static_cast<float>(m_seekSlider->value()));
    m_seeking = false;
}

// --- Crossfade / hand-off ----------------------------------------------

void PlayerWindow::abortCrossfade()
{
    m_engine.abortCrossfade(); // also clears a gapless hand-off's scheduled stop
    m_queue.cancelPendingNext();
    m_gaplessHandOff = false;
}

const SongAnalysis* PlayerWindow::analysisOf(const TrackInfo& track) const
{
    return m_analysisCompleted ? m_songData->analysisFor(track) : nullptr;
}

float PlayerWindow::trackBegin(const TrackInfo& track) const
{
    const SongAnalysis* analysis = m_smartTransitions ? analysisOf(track) : nullptr;
    if (analysis && analysis->audioStart > 0.3)
    {
        return static_cast<float>(analysis->audioStart - 0.05);
    }
    return 0.0f;
}

float PlayerWindow::trackEnd(const TrackInfo& track, float length) const
{
    const SongAnalysis* analysis = m_smartTransitions ? analysisOf(track) : nullptr;
    if (analysis && length > 0.0f && analysis->audioEnd < length - 0.3)
    {
        const float end = static_cast<float>(analysis->audioEnd + 0.1);
        if (end > trackBegin(track) + 1.0f) // a nonsensical trim (e.g. a near-silent file) is ignored
        {
            return end;
        }
    }
    return length;
}

// Lines up the next track before this one ends: a crossfade, or a gapless hand-off.
void PlayerWindow::prepareHandOff(float cursor, float end)
{
    const float pitch = m_engine.activePitch();
    const float remaining = (end - cursor) / pitch; // wall-clock seconds left
    const float fade = static_cast<float>(m_crossfadeSeconds);
    if (remaining > qMax(fade * 2.5f, 12.0f))
    {
        return; // nothing to decide until the end is near
    }
    if (!m_queue.peekNext())
    {
        return; // end of the queue
    }
    const TrackInfo& current = m_queue.currentTrack();
    const TrackInfo& next = m_queue.pendingNextTrack();

    bool useCrossfade = m_crossfadeEnabled && m_engine.lengthSeconds() > fade * 2.0f;
    // Smart: consecutive tracks of the same album stay gapless (live albums, DJ mixes).
    if (useCrossfade && m_smartTransitions && m_analysisCompleted && !current.album.isEmpty()
        && current.album == next.album && current.artist == next.artist)
    {
        useCrossfade = false;
    }

    if (useCrossfade)
    {
        float length = fade;
        // Smart: a long fade-out at the end becomes the crossfade.
        const SongAnalysis* analysis = m_smartTransitions ? analysisOf(current) : nullptr;
        if (analysis)
        {
            const float outro = end - static_cast<float>(analysis->outroStart);
            if (outro > fade)
            {
                length = qMin(qMin(outro, fade * 2.0f), 12.0f);
            }
        }
        if (remaining <= length)
        {
            startHandOff(false, length);
        }
    }
    else if (remaining <= 1.5f)
    {
        startHandOff(true, 0.0f);
    }
}

void PlayerWindow::startHandOff(bool gapless, float fadeLength)
{
    const TrackInfo& next = m_queue.pendingNextTrack();
    const float cursor = m_engine.cursorSeconds();
    const float pitch = m_engine.activePitch();
    const float end = trackEnd(m_queue.currentTrack(), m_engine.lengthSeconds());

    float startPosition = trackBegin(next);
    float delay = 0.0f;
    float nextPitch = 1.0f;
    if (gapless)
    {
        // Start the next track on the exact sample this one stops.
        delay = qMax(0.0f, (end - cursor) / pitch);
    }
    else if (m_beatMatchedCrossfade)
    {
        // Beat-matched: the next track's tempo is matched to this one's (if
        // they're within 8%, counting half/double time) and its first beat
        // starts exactly on one of this track's beats.
        const SongAnalysis* outgoing = analysisOf(m_queue.currentTrack());
        const SongAnalysis* incoming = analysisOf(next);
        if (outgoing && incoming && outgoing->isBeatReliable() && incoming->isBeatReliable())
        {
            double ratio = 1.0;
            double bestDistance = 1e9;
            for (double r : {outgoing->bpm / incoming->bpm, outgoing->bpm * 2.0 / incoming->bpm,
                             outgoing->bpm / (incoming->bpm * 2.0)})
            {
                if (std::abs(r - 1.0) < bestDistance)
                {
                    bestDistance = std::abs(r - 1.0);
                    ratio = r;
                }
            }
            if (bestDistance <= 0.08)
            {
                // This track's next beat, at least 0.15 s away.
                const double outPeriod = outgoing->beatPeriod();
                double beats = std::ceil((cursor - outgoing->firstBeat) / outPeriod);
                double beatTime = outgoing->firstBeat + beats * outPeriod;
                if ((beatTime - cursor) / pitch < 0.15)
                {
                    beatTime += outPeriod;
                }
                // The next track's first beat at or after where its audio starts.
                const double inPeriod = incoming->beatPeriod();
                const double skipped = std::max(0.0, std::ceil((startPosition - incoming->firstBeat) / inPeriod));
                startPosition = static_cast<float>(std::max(0.0, incoming->firstBeat + skipped * inPeriod));
                delay = static_cast<float>((beatTime - cursor) / pitch);
                nextPitch = static_cast<float>(ratio) * pitch;
            }
        }
    }

    const float nextVolume = gapless ? baseVolumeFor(next.replayGainDb) : 0.0f;
    if (!m_engine.startCrossfadeTo(next.path, startPosition, delay, nextPitch, nextVolume))
    {
        // It gets another go (and is skipped if it still can't play) on the normal advance.
        ErrorReporter::logOnly("Hand-off preload failed for: " + next.path);
        m_handOffBlocked = true;
        m_queue.cancelPendingNext();
        return;
    }
    if (gapless)
    {
        m_engine.scheduleActiveStop(delay);
    }
    m_gaplessHandOff = gapless;
    m_fadeLength = qMax(fadeLength, 1.0f);
}

void PlayerWindow::updateCrossfadeProgress(float cursor, float end)
{
    if (!m_queue.hasPendingNext())
    {
        // Every queue change settles a hand-off first, so this shouldn't
        // happen -- but if the pick is ever lost, drop the fade rather than
        // read a track that isn't there.
        ErrorReporter::logOnly("Crossfade without a pending track -- aborted");
        m_engine.abortCrossfade();
        m_gaplessHandOff = false;
        applyVolume();
        return;
    }
    if (m_gaplessHandOff)
    {
        // No fade -- switch over once the next track is actually playing.
        if (m_engine.isIncomingAudible())
        {
            finalizeCrossfade();
        }
        return;
    }

    const float remaining = (end - cursor) / m_engine.activePitch();
    const float progress = 1.0f - qBound(0.0f, remaining / m_fadeLength, 1.0f);

    const float activeBase = baseVolumeFor(m_currentReplayGainDb);
    const float nextBase = baseVolumeFor(m_queue.pendingNextTrack().replayGainDb);
    // The incoming track may not have started yet (a beat-matched start on a beat).
    m_engine.updateCrossfadeVolumes(activeBase * (1.0f - progress), nextBase * progress);

    if (remaining <= 0.05f || m_engine.isAtEnd())
    {
        finalizeCrossfade();
    }
}

float PlayerWindow::baseVolumeFor(float replayGainDb) const
{
    float linear = m_volumeSlider->value() / 100.0f;
    if (m_replayGainEnabled)
    {
        linear *= std::pow(10.0f, replayGainDb / 20.0f);
    }
    linear *= std::pow(10.0f, m_eqPostGainDb / 20.0f);
    return linear * m_sleepVolumeFactor;
}

void PlayerWindow::finalizeCrossfade()
{
    rememberLongTrackPosition(); // the outgoing track, while it's still the current one
    m_engine.finalizeCrossfade();
    m_queue.commitPendingNext();
    recordHistory(m_queue.currentTrack());
    m_countedPath.clear();
    clearAbRepeat();
    m_gaplessHandOff = false;
    m_handOffBlocked = false;

    const TrackInfo& info = m_queue.currentTrack();
    m_currentReplayGainDb = info.replayGainDb;
    applyVolume();
    updateNowPlayingUi(info);

    int length = static_cast<int>(m_engine.lengthSeconds());
    m_seekSlider->setRange(0, length);

    m_wasAtEnd = false;
}

// --- Sleep timer / long tracks ----------------------------------------------

void PlayerWindow::openSleepTimerMenu()
{
    QMenu menu(this);
    auto add = [&](const QString& text, int minutes) {
        QAction* action = menu.addAction(text);
        action->setCheckable(true);
        action->setChecked(m_sleepMinutes == minutes);
        connect(action, &QAction::triggered, this, [this, minutes]() { setSleepTimer(minutes); });
    };
    add(tr("Off"), 0);
    menu.addSeparator();
    for (int minutes : {5, 15, 30, 45, 60, 90})
    {
        add(tr("%1 minutes").arg(minutes), minutes);
    }
    add(tr("End of Track"), -1);
    menu.exec(m_sleepButton->mapToGlobal(QPoint(0, m_sleepButton->height())));
}

void PlayerWindow::setSleepTimer(int minutes)
{
    m_sleepMinutes = minutes;
    m_sleepDeadlineMs = minutes > 0 ? QDateTime::currentMSecsSinceEpoch() + qint64(minutes) * 60 * 1000 : 0;
    if (m_sleepVolumeFactor != 1.0f)
    {
        m_sleepVolumeFactor = 1.0f;
        applyVolume();
    }
    if (minutes == -1 && m_engine.isCrossfading())
    {
        // Stop after the track that's playing: don't hand off to the next one.
        abortCrossfade();
        applyVolume();
    }
    m_sleepButton->setChecked(minutes != 0);
    m_sleepButton->setToolTip(minutes == 0 ? tr("Sleep timer")
                              : minutes == -1 ? tr("Sleep timer: end of track")
                                              : tr("Sleep timer: %1 minutes").arg(minutes));
}

void PlayerWindow::updateSleepTimer(float cursor, float end)
{
    if (m_sleepMinutes == 0)
    {
        return;
    }

    float factor = 1.0f;
    if (m_sleepMinutes > 0)
    {
        const qint64 left = m_sleepDeadlineMs - QDateTime::currentMSecsSinceEpoch();
        if (left <= 0)
        {
            pause();
            setSleepTimer(0); // also restores the volume
            return;
        }
        if (m_sleepFadeOut)
        {
            factor = qBound(0.0f, left / 30000.0f, 1.0f); // fades over the last 30 s
        }
        const int minutesLeft = static_cast<int>((left + 59999) / 60000);
        m_sleepButton->setToolTip(tr("Sleep timer: %1 min left").arg(minutesLeft));
    }
    else if (m_sleepFadeOut && m_engine.isPlaying())
    {
        factor = qBound(0.0f, (end - cursor) / m_engine.activePitch() / 10.0f, 1.0f); // last 10 s of the track
    }

    if (std::abs(factor - m_sleepVolumeFactor) > 0.005f)
    {
        m_sleepVolumeFactor = factor;
        if (!m_engine.isCrossfading())
        {
            applyVolume(); // a crossfade applies it itself, every tick
        }
    }
}

void PlayerWindow::rememberLongTrackPosition()
{
    if (m_queue.isEmpty() || !m_engine.isLoaded())
    {
        return;
    }
    const float length = m_engine.lengthSeconds();
    if (!isPodcast(m_queue.currentTrack().path) && (!m_rememberLongTracks || length < kLongTrackSeconds))
    {
        return;
    }
    const float position = m_engine.cursorSeconds();
    const bool keep = position > 15.0f && position < length - 30.0f;
    m_songData->setResumePosition(m_queue.currentTrack().path, keep ? position : -1.0);
}

// --- Visualizer ----------------------------------------------------------------

void PlayerWindow::setVisualizerOn(bool on)
{
    m_visualizerOn = on;
    m_visualizerButton->setChecked(on);
    m_coverStack->setCurrentIndex(on ? 1 : 0);
    updateVisualizerActive();
}

void PlayerWindow::updateVisualizerActive()
{
    // Only while it's actually on screen: the audio tap costs a little.
    m_visualizer->setActive(m_visualizerOn && m_stack->currentIndex() == 1 && !isMinimized());
}

void PlayerWindow::updateVisualizerForTrack(const TrackInfo& info, const QByteArray& coverArt)
{
    const QColor color = CoverThemeColor(coverArt); // invalid (no cover) -> the accent
    const QPixmap cover = ScaledCoverArt(coverArt, QSize(240, 240));
    m_visualizer->setColor(color);
    m_visualizer->setCover(cover);
    m_visualizerStyleGrid->setColor(color);
    m_visualizerStyleGrid->setCover(cover);
    m_visualizer->setMedia(m_visualizerMedia.resolveFor(info.path));
}

void PlayerWindow::openVisualizerMediaDialog()
{
    VisualizerMediaDialog dialog(m_visualizerMedia, m_library.tracks(), this);
    dialog.exec();
    m_visualizerMedia.save();
    if (!m_queue.isEmpty())
    {
        m_visualizer->setMedia(m_visualizerMedia.resolveFor(m_queue.currentTrack().path));
    }
}

// --- Loved / play counts ----------------------------------------------------

void PlayerWindow::countPlayIfDue(float cursor, float length)
{
    const QString& path = m_queue.currentTrack().path;
    if (path == m_countedPath || length <= 0.0f)
    {
        return;
    }
    // Like Android: half the song, but never more than 4 minutes of it.
    if (cursor >= qBound(1.0f, length / 2.0f, 240.0f))
    {
        m_countedPath = path;
        m_songData->recordPlay(path);
        refreshPlaylistsList(); // Most Played / Recently Played counts
    }
}

void PlayerWindow::toggleLoved()
{
    if (m_queue.isEmpty() || !m_engine.isLoaded())
    {
        return;
    }
    const QString path = m_queue.currentTrack().path;
    m_songData->setLoved(path, !m_songData->isLoved(path));
    updateLoveButton();
    refreshPlaylistsList(); // the Loved smart list's count
}

void PlayerWindow::updateLoveButton()
{
    const bool hasTrack = !m_queue.isEmpty() && m_engine.isLoaded();
    const bool loved = hasTrack && m_songData->isLoved(m_queue.currentTrack().path);
    // Now Playing is always light-on-dark, whatever the theme.
    m_loveButton->setIcon(loved ? theme::icon(theme::Icon::HeartFilled, QColor("#f0c3a4"), QColor(), QColor(), 22)
                                : theme::icon(theme::Icon::Heart, QColor(246, 237, 231, 200), QColor(), QColor(), 22));
    m_loveButton->setToolTip(loved ? tr("Remove from Loved") : tr("Love"));
    m_loveButton->setEnabled(hasTrack);
}

// --- Main update tick ----------------------------------------------

void PlayerWindow::updatePlayback()
{
    updatePlayPauseIcons(); // catches state changes that don't go through play()/pause() (end of queue, media keys, ...)

    if (!m_engine.isLoaded() || m_seeking)
    {
        return;
    }
    ++m_tickCount;

    float cursor = m_engine.cursorSeconds();
    float length = m_engine.lengthSeconds();
    const float end = m_queue.isEmpty() ? length : trackEnd(m_queue.currentTrack(), length);
    if (m_windowsMedia)
    {
        m_windowsMedia->setProgress(cursor, length); // the taskbar icon
    }

    if (!m_syncedLyrics.isEmpty())
    {
        updateSyncedLyricsHighlight(cursor - m_lyricsOffsetMs / 1000.0f); // "+" shows the lines later
    }

    m_seekSlider->blockSignals(true); // avoid re-triggering sliderPressed/valueChanged
    m_seekSlider->setValue(static_cast<int>(cursor));
    m_seekSlider->blockSignals(false);

    if (!m_seeking) // the big seek slider shares the same drag-state guard
    {
        m_bigSeekSlider->blockSignals(true);
        m_bigSeekSlider->setRange(0, static_cast<int>(length));
        m_bigSeekSlider->setValue(static_cast<int>(cursor));
        m_bigSeekSlider->blockSignals(false);
    }

    const QString elapsedText = FormatDuration(static_cast<int>(cursor));
    const QString durationText = FormatDuration(static_cast<int>(length));
    m_timeLabel->setText(elapsedText);
    m_bigTimeLabel->setText(elapsedText);
    m_durationLabel->setText(durationText);
    m_bigDurationLabel->setText(durationText);

    updateSleepTimer(cursor, end);

    const bool playing = m_engine.isPlaying();
    if (playing && m_tickCount % 25 == 0)
    {
        rememberLongTrackPosition(); // every ~5 s, so a crash or power cut loses little
    }
    if (playing && !m_queue.isEmpty())
    {
        countPlayIfDue(cursor, length);
        const QString& path = m_queue.currentTrack().path;
        if (length > 0.0f && cursor >= length - kPodcastPlayedWithinSeconds && isPodcast(path)
            && !m_songData->statsFor(path).played)
        {
            m_songData->setPlayed(path, true); // outros and ads at the end don't need listening to
            refreshPodcasts();
        }
    }
    if (m_abState == AbState::Looping && cursor >= m_abB)
    {
        seekTo(m_abA); // A-B repeat: back to A
        return;
    }

    if (m_engine.isCrossfading())
    {
        updateCrossfadeProgress(cursor, end);
        return;
    }

    // Glide back to normal speed after a beat-matched crossfade.
    const float pitch = m_engine.activePitch();
    if (pitch != 1.0f)
    {
        const float difference = 1.0f - pitch;
        m_engine.setActivePitch(std::abs(difference) < 0.002f ? 1.0f : pitch + difference * 0.12f);
    }

    if (playing && !m_handOffBlocked && m_queue.repeatMode() != RepeatMode::One && m_sleepMinutes != -1
        && m_abState == AbState::Off && !m_queue.isEmpty() && length > 0.0f)
    {
        prepareHandOff(cursor, end);
        if (m_engine.isCrossfading())
        {
            return;
        }
    }

    // Auto-advance when the track finishes on its own (no hand-off: end of
    // queue, Repeat One, a failed preload) -- at the trimmed end under Smart
    // Transitions. Edge-triggered so it doesn't keep calling next() every
    // tick once the queue is exhausted.
    bool atEnd = m_engine.isAtEnd() || (playing && end < length && cursor >= end);
    if (atEnd && !m_wasAtEnd)
    {
        if (m_sleepMinutes == -1)
        {
            // Sleep timer "End of Track": stop here instead of moving on.
            rememberLongTrackPosition();
            pause();
            setSleepTimer(0);
        }
        else
        {
            next(true);
        }
    }
    m_wasAtEnd = atEnd;
}

// --- Settings persistence (%APPDATA%\AudioForge\AudioForge.ini) -----

void PlayerWindow::loadSettings()
{
    QSettings settings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge");

    // Theme: System / Light / Dark. Older settings only have darkMode -- kept as Light/Dark.
    const int themeFallback = settings.value("darkMode", true).toBool() ? 2 : 1;
    setThemeMode(settings.value("themeMode", themeFallback).toInt()); // applies it

    int volume = settings.value("volume", 100).toInt();
    m_volumeSlider->setValue(volume);

    m_replayGainEnabled = settings.value("replayGainEnabled", true).toBool();
    m_replayGainButton->setChecked(m_replayGainEnabled);
    m_replayGainSwitch->setChecked(m_replayGainEnabled);

    m_crossfadeEnabled = settings.value("crossfadeEnabled", false).toBool();
    m_crossfadeCheckbox->setChecked(m_crossfadeEnabled);
    m_crossfadeSlider->setEnabled(m_crossfadeEnabled); // setChecked(false) on an unchecked switch doesn't fire toggled
    m_crossfadeLabel->setEnabled(m_crossfadeEnabled);

    m_crossfadeSeconds = settings.value("crossfadeSeconds", 5).toInt();
    m_crossfadeSlider->setValue(m_crossfadeSeconds);

    m_sleepFadeOut = settings.value("sleepFadeOut", true).toBool();
    m_sleepFadeSwitch->setChecked(m_sleepFadeOut);
    m_rememberLongTracks = settings.value("rememberLongTrackPosition", true).toBool();
    m_resumeLongSwitch->setChecked(m_rememberLongTracks);
    m_keepPlayingInTray = settings.value("keepPlayingInTray", false).toBool();
    m_trayKeepSwitch->setChecked(m_keepPlayingInTray);
    m_songNotifications = settings.value("songNotifications", false).toBool();
    m_notifySwitch->setChecked(m_songNotifications);

    // Smart Options (locked until an analysis run has finished once).
    m_analysisCompleted = settings.value("analysisCompleted", false).toBool();
    m_smartTransitions = settings.value("smartTransitions", false).toBool();
    m_beatMatchedCrossfade = settings.value("beatMatchedCrossfade", false).toBool();
    m_harmonicShuffle = settings.value("harmonicShuffle", false).toBool();
    m_smartTransitionsSwitch->setChecked(m_smartTransitions);
    m_beatMatchSwitch->setChecked(m_beatMatchedCrossfade);
    m_harmonicShuffleSwitch->setChecked(m_harmonicShuffle);
    updateSmartOptionsUi();
    updateSmartOrder();

    // The Tracks list's column widths and order, as last left.
    if (const QByteArray columns = settings.value("tracksColumns").toByteArray(); !columns.isEmpty())
    {
        m_tracksColumnsFitted = m_tracksTable->horizontalHeader()->restoreState(columns);
    }
    m_visualizerStyle = qBound(0, settings.value("visualizerStyle", 0).toInt(), kVisualizerStyleCount - 1);
    m_visualizerStyleGrid->setSelected(m_visualizerStyle);
    m_visualizer->setStyle(static_cast<VisualizerStyle>(m_visualizerStyle));

    m_videoWallpaperEnabled = settings.value("videoWallpaperEnabled", true).toBool();
    m_videoWallpaperCheckbox->setChecked(m_videoWallpaperEnabled);

    m_videoWallpaperOpacityPercent = settings.value("videoWallpaperOpacity", 100).toInt();
    m_videoWallpaperOpacitySlider->setValue(m_videoWallpaperOpacityPercent);
    m_videoWallpaperOpacitySlider->setEnabled(m_videoWallpaperEnabled);
    m_wallpaperWidget->setOpacity(m_videoWallpaperOpacityPercent / 100.0);

    m_musixmatchApiKey = UnprotectSecret(settings.value("musixmatchApiKey").toString());
    m_musixmatchApiKeyEdit->setText(m_musixmatchApiKey);

    m_discordPresenceEnabled = settings.value("discordPresenceEnabled", false).toBool();
    m_discordPresenceCheckbox->setChecked(m_discordPresenceEnabled); // fires the toggled lambda -> setEnabled()

    m_discordClientId = settings.value("discordClientId").toString();
    m_discordClientIdEdit->setText(m_discordClientId); // fires the textChanged lambda -> init()

    if (m_engine.isEqualizerAvailable())
    {
        bool eqEnabled = settings.value("eqEnabled", false).toBool();
        m_eqEnabledCheckbox->setChecked(eqEnabled); // fires onEqEnabledToggled, which calls setEqualizerEnabled

        QStringList bandValues = settings.value("eqBandGainsDb").toStringList();
        for (int i = 0; i < m_eqBandSliders.size(); ++i)
        {
            float gainDb = (i < bandValues.size()) ? bandValues[i].toFloat() : 0.0f;
            m_eqBandSliders[i]->setValue(static_cast<int>(gainDb * 10.0f)); // fires onEqBandChanged
        }

        float postGainDb = settings.value("eqPostGainDb", 0.0).toFloat();
        m_eqPostGainSlider->setValue(static_cast<int>(postGainDb * 10.0f)); // fires onEqPostGainChanged
    }

    QStringList folders;
    for (const QString& dir : settings.value("musicFolders").toStringList())
    {
        if (QDir(dir).exists())
        {
            folders << dir;
        }
        else
        {
            ErrorReporter::logOnly("Saved music folder no longer exists, skipped: " + dir);
        }
    }
    m_library.setFolders(folders);
    QStringList podcastFolders;
    for (const QString& dir : settings.value("podcastFolders").toStringList())
    {
        if (folders.contains(dir))
        {
            podcastFolders << dir;
        }
    }
    m_library.setPodcastFolders(podcastFolders);

    // The last scan's results show straight away; the background scan started
    // at the end of the constructor then catches up with any changes on disk.
    m_library.setTracks(TracksInFolders(MusicLibrary::LoadCache(), folders));
    refreshFoldersList();
    refreshTracksTable();
    refreshAlbumsAndArtists();
    updateLibraryStatsUi();
    refreshPodcasts();

    // Playlists store paths only, so a track whose folder has since gone
    // missing just shows by filename in the playlist (see
    // PlaylistEditDialog::labelFor) rather than the playlist being dropped.
    int playlistCount = settings.beginReadArray("playlists");
    for (int i = 0; i < playlistCount; ++i)
    {
        settings.setArrayIndex(i);
        PlaylistData playlist;
        playlist.name = settings.value("name").toString();
        playlist.trackPaths = settings.value("trackPaths").toStringList();
        if (!playlist.name.isEmpty())
        {
            m_library.addPlaylist(playlist);
        }
    }
    settings.endArray();
    refreshPlaylistsList();

    QList<QVariant> hiddenTabs = settings.value("hiddenTabs").toList();
    for (const QVariant& tab : hiddenTabs)
    {
        int tabIndex = tab.toInt();
        if (tabIndex >= 0 && tabIndex < m_tabVisibilityCheckboxes.size())
        {
            m_tabVisibilityCheckboxes[tabIndex]->setChecked(false); // fires the toggled lambda -> setTabVisible()
        }
    }
}

void PlayerWindow::saveSettings()
{
    QSettings settings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge");
    settings.setValue("themeMode", m_themeMode);
    settings.setValue("darkMode", m_darkMode); // still written, so an older AudioForge reading this file gets a sensible theme
    settings.setValue("volume", m_volumeSlider->value());
    settings.setValue("replayGainEnabled", m_replayGainEnabled);
    settings.setValue("sleepFadeOut", m_sleepFadeOut);
    settings.setValue("rememberLongTrackPosition", m_rememberLongTracks);
    settings.setValue("keepPlayingInTray", m_keepPlayingInTray);
    settings.setValue("songNotifications", m_songNotifications);
    settings.setValue("analysisCompleted", m_analysisCompleted);
    settings.setValue("smartTransitions", m_smartTransitions);
    settings.setValue("beatMatchedCrossfade", m_beatMatchedCrossfade);
    settings.setValue("harmonicShuffle", m_harmonicShuffle);
    settings.setValue("crossfadeEnabled", m_crossfadeEnabled);
    settings.setValue("crossfadeSeconds", m_crossfadeSeconds);
    settings.setValue("visualizerStyle", m_visualizerStyle);
    settings.setValue("tracksColumns", m_tracksTable->horizontalHeader()->saveState());
    settings.setValue("videoWallpaperEnabled", m_videoWallpaperEnabled);
    settings.setValue("videoWallpaperOpacity", m_videoWallpaperOpacityPercent);
    settings.setValue("musixmatchApiKey", ProtectSecret(m_musixmatchApiKey));
    settings.setValue("discordPresenceEnabled", m_discordPresenceEnabled);
    settings.setValue("discordClientId", m_discordClientId);

    if (m_engine.isEqualizerAvailable())
    {
        settings.setValue("eqEnabled", m_engine.isEqualizerEnabled());
        QStringList bandValues;
        for (int i = 0; i < AudioEngine::kEqBandCount; ++i)
        {
            bandValues << QString::number(m_engine.equalizerBandGain(i));
        }
        settings.setValue("eqBandGainsDb", bandValues);
        settings.setValue("eqPostGainDb", m_eqPostGainDb);
    }

    settings.setValue("musicFolders", m_library.folders());
    settings.setValue("podcastFolders", m_library.podcastFolders());

    // Rewritten in full each time (remove first) so deleted playlists don't
    // linger as stale array entries.
    settings.remove("playlists");
    const QVector<PlaylistData>& playlists = m_library.playlists();
    settings.beginWriteArray("playlists", playlists.size());
    for (int i = 0; i < playlists.size(); ++i)
    {
        settings.setArrayIndex(i);
        settings.setValue("name", playlists[i].name);
        settings.setValue("trackPaths", playlists[i].trackPaths);
    }
    settings.endArray();

    QList<QVariant> hiddenTabs;
    for (int i = 0; i < m_tabVisibilityCheckboxes.size(); ++i)
    {
        if (!m_tabVisibilityCheckboxes[i]->isChecked())
        {
            hiddenTabs << i;
        }
    }
    settings.setValue("hiddenTabs", hiddenTabs);
}

} // namespace audioforge