#include "audioforge/player_window.hpp"
#include "audioforge/track_metadata.hpp"
#include "audioforge/warnings.hpp"
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
#include "audioforge/widgets/toggle_switch.hpp"
#include "audioforge/widgets/up_next_panel.hpp"
#include "audioforge/track_metadata_tags.hpp"
#include "audioforge/lyrics_provider.hpp"
#include "audioforge/cover_art_writer.hpp"
#include "audioforge/discord_presence.hpp"
#include "audioforge/secret_store.hpp"
#include "audioforge/theme.hpp"
#include "audioforge/app_files.hpp"

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
#include <QPainter>
#include <QProgressBar>
#include <QStyleHints>

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

// Averages a downscaled version of the image to get a representative color
// -- used to tint the Now Playing page's background to roughly match the
// cover art, the way several music apps do. Falls back to a neutral dark
// gray for tracks with no embedded art.
static QColor AverageColor(const QByteArray& imageBytes)
{
    QColor fallback(35, 36, 40);
    if (imageBytes.isEmpty())
    {
        return fallback;
    }

    QImage image;
    if (!image.loadFromData(imageBytes))
    {
        return fallback;
    }

    QImage small = image.scaled(24, 24, Qt::IgnoreAspectRatio, Qt::FastTransformation).convertToFormat(QImage::Format_RGB32);
    qint64 r = 0, g = 0, b = 0;
    int count = small.width() * small.height();
    if (count == 0)
    {
        return fallback;
    }

    for (int y = 0; y < small.height(); ++y)
    {
        const QRgb* line = reinterpret_cast<const QRgb*>(small.constScanLine(y));
        for (int x = 0; x < small.width(); ++x)
        {
            QRgb px = line[x];
            r += qRed(px);
            g += qGreen(px);
            b += qBlue(px);
        }
    }

    // Darken a bit so white text stays readable on top of it.
    QColor avg(static_cast<int>(r / count * 0.55), static_cast<int>(g / count * 0.55), static_cast<int>(b / count * 0.55));
    return avg;
}

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
        ErrorReporter::warn(this, "Audio engine failed to start",
            "The audio engine could not be initialized (no audio device found?). Playback controls won't work.");
    }

    m_network = new QNetworkAccessManager(this);

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
    m_searchBar->setPlaceholderText("Search songs, artists, albums...");
    m_searchBar->setClearButtonEnabled(true);
    m_searchBar->setFixedWidth(320);
    m_searchIconAction = m_searchBar->addAction(QIcon(), QLineEdit::LeadingPosition);
    connect(m_searchBar, &QLineEdit::textChanged, this, &PlayerWindow::applySearchFilter);
    topBar->addWidget(m_searchBar);

    m_themeButton = MakeIconButton(36, 18, "Toggle light / dark theme");
    connect(m_themeButton, &QPushButton::clicked, this, &PlayerWindow::toggleTheme);
    topBar->addWidget(m_themeButton);
    rootLayout->addLayout(topBar);

    // Library scan progress -- only shown while a scan is running.
    m_scanBar = new QWidget();
    auto* scanLayout = new QHBoxLayout(m_scanBar);
    scanLayout->setContentsMargins(4, 0, 4, 0);
    scanLayout->setSpacing(12);
    m_scanLabel = new QLabel("Loading library\u2026");
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

    m_tabs->addTab(buildAlbumsTab(), "Albums");     // index 0
    m_tabs->addTab(buildTracksTab(), "Tracks");      // index 1
    m_tabs->addTab(buildArtistsTab(), "Artists");    // index 2
    m_tabs->addTab(buildFoldersTab(), "Folders");    // index 3
    m_tabs->addTab(buildPlaylistsTab(), "Playlists");// index 4
    m_tabs->addTab(buildEqualizerTab(), "Equalizer"); // index 5 (always visible, not in the visibility config)
    m_tabs->addTab(buildWallpapersTab(), "Wallpapers"); // index 6 (always visible, not in the visibility config)
    m_tabs->addTab(buildSettingsTab(), "Settings");  // index 7 (always visible, not in the visibility config)
    rootLayout->addWidget(buildPlayerBar());

    libraryLayout->addWidget(buildSidebar());
    libraryLayout->addWidget(mainArea, 1);

    connect(m_tabs, &QTabWidget::currentChanged, this, &PlayerWindow::onTabChanged);
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
}

PlayerWindow::~PlayerWindow()
{
    if (m_scanThread)
    {
        m_scanCancel->store(true); // it stops after the file it's reading
        m_scanThread->wait();
    }
    // Analyzer first: cancelling it writes into the store. Disconnected so
    // its "finished" signal can't reach this half-destroyed window.
    m_analyzer->disconnect();
    delete m_analyzer;
    delete m_songData; // saves anything pending
    // AudioEngine's own destructor tears down miniaudio
}

void PlayerWindow::closeEvent(QCloseEvent* event)
{
    rememberLongTrackPosition();
    m_songData->saveNow();
    saveQueueState();
    MusicLibrary::SaveCache(m_library.tracks()); // keeps tag edits made since the last scan
    saveSettings();
    m_wallpaperLibrary.save();
    QWidget::closeEvent(event);
}

void PlayerWindow::changeEvent(QEvent* event)
{
    if (event->type() == QEvent::WindowStateChange)
    {
        m_wallpaperWidget->setPaused(isMinimized() || m_stack->currentIndex() != 1);
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
        openGroupTracks(item->text(), item->data(Qt::UserRole).toStringList());
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
        openGroupTracks(item->text(), item->data(Qt::UserRole).toStringList());
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
    m_tracksTable->setHorizontalHeaderLabels({"Title", "Artist", "Album", "Genre", "Year"});
    m_tracksTable->horizontalHeader()->setSectionResizeMode(0, QHeaderView::Stretch);
    m_tracksTable->horizontalHeader()->setSectionResizeMode(4, QHeaderView::ResizeToContents); // Year
    m_tracksTable->horizontalHeader()->setSectionResizeMode(3, QHeaderView::ResizeToContents); // Genre
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
    m_musicBrainzButton = new QPushButton("Tag Music");
    m_musicBrainzButton->setEnabled(false);
    connect(m_musicBrainzButton, &QPushButton::clicked, this, &PlayerWindow::openTagMusicMenu);
    buttonRow->addWidget(m_musicBrainzButton);

    m_changeCoverButton = new QPushButton("Change Cover...");
    m_changeCoverButton->setEnabled(false);
    connect(m_changeCoverButton, &QPushButton::clicked, this, &PlayerWindow::openChangeCoverDialog);
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
    l->addWidget(m_foldersList);

    auto* addFolderButton = new QPushButton("Add Folder...");
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
    auto* w = new QWidget();
    auto* l = new QVBoxLayout(w);

    m_playlistsList = new QListWidget();
    connect(m_playlistsList, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem* item) {
        int index = item->data(Qt::UserRole).toInt();
        openPlaylistTracks(index);
    });
    enableTracksMenu(m_playlistsList, /*playlistRows=*/true);

    l->addWidget(m_playlistsList, 1);

    auto* addPlaylistButton = new QPushButton("+");
    addPlaylistButton->setFixedSize(44, 44);
    QFont f = addPlaylistButton->font();
    f.setPointSize(f.pointSize() + 4);
    addPlaylistButton->setFont(f);
    connect(addPlaylistButton, &QPushButton::clicked, this, &PlayerWindow::openCreatePlaylistDialog);

    auto* row = new QHBoxLayout();
    row->addStretch();
    row->addWidget(addPlaylistButton);
    row->addStretch();
    l->addLayout(row);

    return w;
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

    // --- Library ---------------------------------------------------------
    SettingsCard library = AddSettingsSection(l, "Library");
    for (const QString& name : {QStringLiteral("Songs"), QStringLiteral("Albums"), QStringLiteral("Artists"), QStringLiteral("Genres")})
    {
        QLabel* value = MakeValueLabel();
        m_statValueLabels << value;
        AddSettingsRow(library, MakeSettingsRow(name, value));
    }

    {
        m_refreshLibraryButton = new QPushButton("Refresh Library");
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
        m_analyzeButton = new QPushButton("Analyze Your Songs' Key and BPM");
        m_analyzeButton->setObjectName("LinkButton");
        m_analyzeButton->setCursor(Qt::PointingHandCursor);
        connect(m_analyzeButton, &QPushButton::clicked, this, &PlayerWindow::startAnalysis);
        top->addWidget(m_analyzeButton);
        top->addStretch();
        m_analyzeStopButton = new QPushButton("Stop");
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
        auto* chevron = new QLabel();
        m_settingsIcons << qMakePair(chevron, theme::Icon::Chevron);
        auto* duplicatesButton = new QPushButton("Find Duplicates");
        duplicatesButton->setObjectName("RowButton");
        duplicatesButton->setCursor(Qt::PointingHandCursor);
        connect(duplicatesButton, &QPushButton::clicked, this, &PlayerWindow::openDuplicates);
        auto* row = new QWidget();
        row->setMinimumHeight(46);
        auto* rowLayout = new QHBoxLayout(row);
        rowLayout->setContentsMargins(16, 8, 16, 8);
        rowLayout->addWidget(duplicatesButton, 1);
        rowLayout->addWidget(chevron);
        AddSettingsRow(library, row);
    }
    AddSettingsFooter(l,
        "Analysis finds each song's tempo (BPM), key, and where its sound starts and ends, on this "
        "computer (nothing is uploaded). It unlocks Smart Options. Select a song in Tracks to edit its "
        "tags or change its cover.");

    // --- Smart Options -----------------------------------------------------
    SettingsCard smart = AddSettingsSection(l, "Smart Options");
    {
        QLabel* lockIcon = nullptr;
        QLabel* lockText = nullptr;
        m_smartLockRow = MakeSettingsRow("Run \"Analyze Your Songs' Key and BPM\" (above) to unlock these.",
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
    iconRow(smart, "Smart Transitions", theme::Icon::Wand, m_smartTransitionsSwitch);

    m_beatMatchSwitch = new ToggleSwitch();
    connect(m_beatMatchSwitch, &QAbstractButton::toggled, this, [this](bool on) { m_beatMatchedCrossfade = on; });
    iconRow(smart, "Beat-Matched Crossfades", theme::Icon::Metronome, m_beatMatchSwitch);

    m_harmonicShuffleSwitch = new ToggleSwitch();
    connect(m_harmonicShuffleSwitch, &QAbstractButton::toggled, this, [this](bool on) {
        m_harmonicShuffle = on;
        updateSmartOrder();
    });
    iconRow(smart, "Harmonic Shuffle", theme::Icon::Dial, m_harmonicShuffleSwitch);
    AddSettingsFooter(l,
        "Smart Transitions: songs from the same album play gapless, crossfades start where a song's ending "
        "actually fades out, and silence at the start and end is skipped. Beat-Matched Crossfades (needs "
        "Crossfade on): the next song's tempo is matched and its beats lined up during the fade, then it "
        "eases back to normal speed. Harmonic Shuffle: Smart shuffle plays songs in compatible keys and "
        "similar tempos next to each other. Songs that haven't been analyzed, or have no steady beat, use "
        "normal transitions.");

    // --- Appearance --------------------------------------------------------
    SettingsCard appearance = AddSettingsSection(l, "Appearance");
    m_themeCombo = new QComboBox();
    m_themeCombo->addItems({"System", "Light", "Dark"});
    m_themeCombo->setMinimumWidth(130);
    connect(m_themeCombo, QOverload<int>::of(&QComboBox::currentIndexChanged), this, &PlayerWindow::setThemeMode);
    AddSettingsRow(appearance, MakeSettingsRow("Theme", m_themeCombo));

    // --- Playback ----------------------------------------------------------
    SettingsCard playback = AddSettingsSection(l, "Playback");
    m_replayGainSwitch = new ToggleSwitch();
    m_replayGainSwitch->setChecked(m_replayGainEnabled);
    connect(m_replayGainSwitch, &QAbstractButton::toggled, this, [this](bool on) {
        if (on != m_replayGainEnabled)
        {
            toggleReplayGain(); // keeps the player bar's ReplayGain pill in step
        }
    });
    AddSettingsRow(playback, MakeSettingsRow("ReplayGain", m_replayGainSwitch));

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
    AddSettingsRow(playback, MakeSettingsRow("Crossfade", m_crossfadeCheckbox));

    {
        auto* row = new QWidget();
        auto* rowLayout = new QVBoxLayout(row);
        rowLayout->setContentsMargins(16, 10, 16, 10);
        m_crossfadeLabel = new QLabel("Crossfade length: 5 s");
        rowLayout->addWidget(m_crossfadeLabel);
        m_crossfadeSlider = new QSlider(Qt::Horizontal);
        m_crossfadeSlider->setRange(2, 15);
        m_crossfadeSlider->setValue(5);
        m_crossfadeSlider->setFocusPolicy(Qt::NoFocus);
        connect(m_crossfadeSlider, &QSlider::valueChanged, this, [this](int value) {
            m_crossfadeSeconds = value;
            m_crossfadeLabel->setText(QString("Crossfade length: %1 s").arg(value));
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
    AddSettingsRow(playback, MakeSettingsRow("Fade Out Sleep Timer", m_sleepFadeSwitch));

    m_resumeLongSwitch = new ToggleSwitch();
    m_resumeLongSwitch->setChecked(m_rememberLongTracks);
    connect(m_resumeLongSwitch, &QAbstractButton::toggled, this, [this](bool on) { m_rememberLongTracks = on; });
    AddSettingsRow(playback, MakeSettingsRow("Resume Long Tracks", m_resumeLongSwitch));
    AddSettingsFooter(l,
        "ReplayGain evens out loudness between songs using the gain stored in their tags. With crossfade "
        "off, songs play gapless. The sleep timer is on the Now Playing screen. Resume Long Tracks: "
        "audiobooks and mixes (10 minutes or longer) continue where you stopped.");

    // --- Video Wallpaper ---------------------------------------------------
    SettingsCard wallpaper = AddSettingsSection(l, "Video Wallpaper");
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
    AddSettingsRow(wallpaper, MakeSettingsRow("Show Video Wallpapers", m_videoWallpaperCheckbox));

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
        opacityLabel->setText(QString("Opacity: %1%").arg(m_videoWallpaperOpacityPercent));
        connect(m_videoWallpaperOpacitySlider, &QSlider::valueChanged, this, [this, opacityLabel](int value) {
            m_videoWallpaperOpacityPercent = value;
            m_wallpaperWidget->setOpacity(value / 100.0);
            opacityLabel->setText(QString("Opacity: %1%").arg(value));
        });
        rowLayout->addWidget(m_videoWallpaperOpacitySlider);
        AddSettingsRow(wallpaper, row);
    }

    // --- Lyrics Lookup -----------------------------------------------------
    SettingsCard lyrics = AddSettingsSection(l, "Lyrics Lookup");
    {
        auto* row = new QWidget();
        auto* rowLayout = new QHBoxLayout(row);
        rowLayout->setContentsMargins(16, 10, 16, 10);
        m_musixmatchApiKeyEdit = new QLineEdit();
        m_musixmatchApiKeyEdit->setEchoMode(QLineEdit::Password);
        m_musixmatchApiKeyEdit->setPlaceholderText("Musixmatch API key (optional)");
        connect(m_musixmatchApiKeyEdit, &QLineEdit::textChanged, this, [this](const QString& text) {
            m_musixmatchApiKey = text.trimmed();
        });
        rowLayout->addWidget(m_musixmatchApiKeyEdit);
        AddSettingsRow(lyrics, row);
    }
    AddSettingsFooter(l,
        "Lyrics are looked up on LRCLIB (free, no key), then Musixmatch if you add an API key (free-tier "
        "keys only return a snippet), then a same-name .lrc or .txt file next to the song. The key is "
        "stored encrypted for your Windows account.");

    // --- Discord Rich Presence --------------------------------------------------
    SettingsCard discord = AddSettingsSection(l, "Discord Rich Presence");
    m_discordPresenceCheckbox = new ToggleSwitch();
    connect(m_discordPresenceCheckbox, &QAbstractButton::toggled, this, [this](bool checked) {
        m_discordPresenceEnabled = checked;
        m_discordPresence.setEnabled(checked);
    });
    AddSettingsRow(discord, MakeSettingsRow("Show what I'm playing on Discord", m_discordPresenceCheckbox));
    {
        auto* row = new QWidget();
        auto* rowLayout = new QHBoxLayout(row);
        rowLayout->setContentsMargins(16, 10, 16, 10);
        m_discordClientIdEdit = new QLineEdit();
        m_discordClientIdEdit->setPlaceholderText("Discord Application Client ID");
        connect(m_discordClientIdEdit, &QLineEdit::textChanged, this, [this](const QString& text) {
            m_discordClientId = text.trimmed();
            m_discordPresence.init(m_discordClientId);
        });
        rowLayout->addWidget(m_discordClientIdEdit);
        AddSettingsRow(discord, row);
    }
    AddSettingsFooter(l,
        "Needs a free \"Application\" client ID from discord.com/developers/applications (create one and "
        "copy its Client ID -- nothing needs to be published). Discord's desktop app must be running.");

    // --- Visible Tabs ------------------------------------------------------
    SettingsCard tabs = AddSettingsSection(l, "Visible Tabs");
    // The library tabs (0-4) can be hidden; Equalizer, Wallpapers and
    // Settings always stay. (Built while Settings is added as the last tab,
    // so the five before it already exist.)
    for (int tabIndex = 0; tabIndex < 5; ++tabIndex)
    {
        auto* toggle = new ToggleSwitch();
        toggle->setChecked(true);
        m_tabVisibilityCheckboxes << toggle; // persisted by saveSettings()
        connect(toggle, &QAbstractButton::toggled, this, [this, tabIndex](bool checked) {
            m_tabs->setTabVisible(tabIndex, checked); // also moves the current tab off a hidden one
            m_navButtons[tabIndex]->setVisible(checked);
        });
        AddSettingsRow(tabs, MakeSettingsRow(m_tabs->tabText(tabIndex), toggle));
    }

    l->addStretch();
    return scroll;
}

QWidget* PlayerWindow::buildWallpapersTab()
{
    auto* w = new QWidget();
    auto* l = new QVBoxLayout(w);

    // --- Global wallpaper ---
    l->addWidget(sectionHeader("Global Wallpaper"));
    l->addWidget(new QLabel("Used whenever the currently playing track has no wallpaper of its own."));

    m_globalWallpaperLabel = new QLabel();
    l->addWidget(m_globalWallpaperLabel);

    auto* globalButtonsRow = new QHBoxLayout();
    auto* chooseGlobalButton = new QPushButton("Choose Video...");
    connect(chooseGlobalButton, &QPushButton::clicked, this, &PlayerWindow::chooseGlobalWallpaper);
    globalButtonsRow->addWidget(chooseGlobalButton);

    auto* clearGlobalButton = new QPushButton("Clear");
    connect(clearGlobalButton, &QPushButton::clicked, this, &PlayerWindow::clearGlobalWallpaper);
    globalButtonsRow->addWidget(clearGlobalButton);
    globalButtonsRow->addStretch();
    l->addLayout(globalButtonsRow);

    l->addSpacing(16);

    // --- Per-track wallpapers ---
    l->addWidget(sectionHeader("Per-Track Wallpapers"));

    m_wallpaperEntriesList = new QListWidget();
    l->addWidget(m_wallpaperEntriesList, 1);

    auto* entryButtonsRow = new QHBoxLayout();
    auto* addEntryButton = new QPushButton("Add Wallpaper...");
    connect(addEntryButton, &QPushButton::clicked, this, &PlayerWindow::addWallpaperEntry);
    entryButtonsRow->addWidget(addEntryButton);

    auto* editTracksButton = new QPushButton("Edit Tracks...");
    connect(editTracksButton, &QPushButton::clicked, this, &PlayerWindow::editWallpaperEntryTracks);
    entryButtonsRow->addWidget(editTracksButton);

    auto* removeEntryButton = new QPushButton("Remove");
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
        {"Rock",         {5, 4, 3, 0, -2, -1, 0, 2, 3, 4}},
        {"Pop",          {-1, 1, 3, 4, 3, 0, -1, -1, -1, -1}},
        {"Jazz",         {3, 2, 1, 2, -1, -1, 0, 1, 2, 3}},
        {"Classical",    {4, 3, 2, 1, -1, -2, -2, 0, 2, 3}},
        {"Dance",        {6, 5, 2, 0, 0, -3, -3, 0, 3, 4}},
        {"Hip-Hop",      {6, 5, 3, 1, -1, -1, 0, 1, 2, 3}},
        {"Blues",        {3, 2, 0, -1, 0, 1, 2, 2, 1, 1}},
        {"Vocal Boost",  {-2, -2, -1, 1, 3, 4, 3, 1, 0, -1}},
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
    headerRow->addWidget(sectionHeader("Equalizer"));
    headerRow->addStretch();
    m_eqEnabledCheckbox = new QCheckBox("Enabled");
    connect(m_eqEnabledCheckbox, &QCheckBox::toggled, this, &PlayerWindow::onEqEnabledToggled);
    headerRow->addWidget(m_eqEnabledCheckbox);
    sectionLayout->addLayout(headerRow);

    if (!m_engine.isEqualizerAvailable())
    {
        sectionLayout->addWidget(new QLabel("Equalizer unavailable -- the audio engine couldn't set it up."));
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
    presetRow->addWidget(new QLabel("Preset"));
    m_eqPresetCombo = new QComboBox();
    m_eqPresetCombo->addItem("Custom");
    for (const auto& preset : EqPresets())
    {
        m_eqPresetCombo->addItem(preset.first);
    }
    connect(m_eqPresetCombo, QOverload<int>::of(&QComboBox::currentIndexChanged), this, &PlayerWindow::applyEqPreset);
    presetRow->addWidget(m_eqPresetCombo, 1);
    sectionLayout->addLayout(presetRow);

    sectionLayout->addSpacing(16);

    auto* postGainRow = new QHBoxLayout();
    postGainRow->addWidget(new QLabel("Post-gain"));
    m_eqPostGainValueLabel = new QLabel("0.0dB");
    postGainRow->addWidget(m_eqPostGainValueLabel);
    sectionLayout->addLayout(postGainRow);

    m_eqPostGainSlider = new QSlider(Qt::Horizontal);
    m_eqPostGainSlider->setRange(-150, 150);
    m_eqPostGainSlider->setValue(0);
    connect(m_eqPostGainSlider, &QSlider::valueChanged, this, &PlayerWindow::onEqPostGainChanged);
    sectionLayout->addWidget(m_eqPostGainSlider);

    auto* resetButton = new QPushButton("Reset Equalizer");
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
    for (int index : {1, 0, 2, 4, 3})
    {
        l->addWidget(m_navButtons[index]);
    }

    m_playlistsSection = new QWidget(); // caption + list, hidden in the compact sidebar
    auto* playlistsLayout = new QVBoxLayout(m_playlistsSection);
    playlistsLayout->setContentsMargins(0, 22, 0, 0);
    playlistsLayout->setSpacing(4);
    auto* playlistsHeader = new QHBoxLayout();
    auto* playlistsCaption = new QLabel("YOUR PLAYLISTS");
    playlistsCaption->setObjectName("SectionCaption");
    QFont captionFont = playlistsCaption->font();
    captionFont.setLetterSpacing(QFont::AbsoluteSpacing, 1.5);
    playlistsCaption->setFont(captionFont);
    playlistsCaption->setContentsMargins(10, 0, 0, 0);
    playlistsHeader->addWidget(playlistsCaption);
    playlistsHeader->addStretch();
    m_newPlaylistButton = MakeIconButton(26, 14, "New playlist");
    connect(m_newPlaylistButton, &QPushButton::clicked, this, &PlayerWindow::openCreatePlaylistDialog);
    playlistsHeader->addWidget(m_newPlaylistButton);
    playlistsLayout->addLayout(playlistsHeader);

    m_sidebarPlaylistsList = new QListWidget();
    m_sidebarPlaylistsList->setObjectName("SidebarList");
    m_sidebarPlaylistsList->setSelectionMode(QAbstractItemView::NoSelection);
    m_sidebarPlaylistsList->setFocusPolicy(Qt::NoFocus);
    m_sidebarPlaylistsList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_sidebarPlaylistsList->setCursor(Qt::PointingHandCursor);
    connect(m_sidebarPlaylistsList, &QListWidget::itemClicked, this, [this](QListWidgetItem* item) {
        openPlaylistTracks(item->data(Qt::UserRole).toInt());
    });
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

    m_openButton = new QPushButton("  Open File...");
    m_openButton->setObjectName("NavButton");
    m_openButton->setIconSize(QSize(18, 18));
    m_openButton->setCursor(Qt::PointingHandCursor);
    m_openButton->setFocusPolicy(Qt::NoFocus);
    connect(m_openButton, &QPushButton::clicked, this, &PlayerWindow::openFile);
    l->addWidget(m_openButton);

    m_openButton->setToolTip("Open File...");
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
    m_openButton->setText(compact ? QString() : QStringLiteral("  Open File..."));
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
    clickableInfo->setToolTip("Open Now Playing");
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

    m_titleLabel = new QLabel("No file loaded");
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
    m_shuffleButton = MakeIconButton(36, 18, "Shuffle: Off");
    m_shuffleButton->setCheckable(true);
    m_prevButton = MakeIconButton(36, 20, "Previous");
    m_playButton = MakeIconButton(44, 20, "Play");
    m_playButton->setObjectName("PlayButton");
    m_nextButton = MakeIconButton(36, 20, "Next");
    m_repeatButton = MakeIconButton(36, 18, "Repeat: Off");
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

    m_replayGainButton = new QPushButton("ReplayGain");
    m_replayGainButton->setObjectName("PillToggle");
    m_replayGainButton->setCheckable(true);
    m_replayGainButton->setChecked(true);
    m_replayGainButton->setFocusPolicy(Qt::NoFocus);
    m_replayGainButton->setCursor(Qt::PointingHandCursor);
    m_replayGainButton->setToolTip("Normalize loudness using each track's ReplayGain tag");
    rightLayout->addWidget(m_replayGainButton);

    m_stopButton = MakeIconButton(32, 16, "Stop");
    rightLayout->addWidget(m_stopButton);

    m_queueButton = MakeIconButton(32, 18, "Up Next");
    m_queueButton->setCheckable(true); // accent while the panel is open
    connect(m_queueButton, &QPushButton::clicked, this, [this](bool checked) { setUpNextVisible(checked); });
    rightLayout->addWidget(m_queueButton);

    m_volumeButton = MakeIconButton(32, 18, "Mute (Ctrl+M)");
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
    m_volumeSlider->setToolTip("Volume (Ctrl+Up / Ctrl+Down)");
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
    // Live video wallpaper -- scoped to just this
    // page: `page` is a thin shell holding two stacked layers, the video
    // (bottom) and the real content (top, everything below used to attach
    // directly to `page` -- it now attaches to `content` instead, and
    // `content` is what m_nowPlayingPage points at, unchanged for every
    // other function that already reads/writes m_nowPlayingPage).
    auto* page = new QWidget();
    m_wallpaperWidget = new VideoBackgroundWidget();

    auto* content = new QWidget();
    m_nowPlayingPage = content;
    auto* pageLayout = new QVBoxLayout(content);
    pageLayout->setContentsMargins(36, 24, 36, 28);

    auto* topRow = new QHBoxLayout();
    m_backButton = MakeIconButton(38, 20, "Back to library");
    connect(m_backButton, &QPushButton::clicked, this, [this]() { m_stack->setCurrentIndex(0); });
    topRow->addWidget(m_backButton);
    topRow->addStretch();
    m_bigQueueButton = MakeIconButton(38, 18, "Up Next");
    connect(m_bigQueueButton, &QPushButton::clicked, this, [this]() {
        m_stack->setCurrentIndex(0); // the queue lives on the library page
        setUpNextVisible(true);
    });
    topRow->addWidget(m_bigQueueButton);
    m_sleepButton = MakeIconButton(38, 18, "Sleep timer");
    m_sleepButton->setCheckable(true); // shows the accent color while a timer is set
    connect(m_sleepButton, &QPushButton::clicked, this, [this]() {
        m_sleepButton->setChecked(m_sleepMinutes != 0); // the click toggled it -- the menu decides
        openSleepTimerMenu();
    });
    topRow->addWidget(m_sleepButton);
    m_fullscreenButton = MakeIconButton(38, 18, "Full screen (F11)");
    connect(m_fullscreenButton, &QPushButton::clicked, this, &PlayerWindow::toggleFullScreen);
    topRow->addWidget(m_fullscreenButton);
    pageLayout->addLayout(topRow);

    pageLayout->addStretch(1);

    auto* contentRow = new QHBoxLayout();
    contentRow->addStretch();

    m_bigCoverArtLabel = new QLabel();
    m_bigCoverArtLabel->setObjectName("CoverArt");
    m_bigCoverArtLabel->setFixedSize(320, 320);
    m_bigCoverArtLabel->setScaledContents(false); // pixmap is pre-cropped to exact label size (see ScaledCoverArt)
    contentRow->addWidget(m_bigCoverArtLabel, 0, Qt::AlignTop);

    // A fixed, deliberate width for the whole info column (title through
    // lyrics) -- not derived from window size. Single source of truth for
    // both the box below and the title scroll area further down, so they
    // always match.
    static constexpr int kBigTitleBoxWidth = 620;

    // The readable backdrop lives here now -- a bounded panel behind just
    // the title/subtitle/format/lyrics column -- instead of tinting the
    // entire page (which produced a visible seam artifact at the label
    // boundaries; see the box's own comment further down for the size
    // policy reasoning that keeps a wide child from forcing this, or any
    // ancestor up to the window, to grow).
    auto* infoBox = new QWidget();
    infoBox->setStyleSheet("background-color: rgba(0, 0, 0, 150); border-radius: 16px;");
    infoBox->setMaximumWidth(kBigTitleBoxWidth);
    infoBox->setMinimumHeight(320); // at least as tall as the cover beside it
    infoBox->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
    m_infoBox = infoBox; // updateNowPlayingUi() re-tints this per track

    // Everything inside the box is always drawn light-on-dark: the box is
    // tinted from the (darkened) cover art color in both themes.
    auto* textColumn = new QVBoxLayout(infoBox);
    textColumn->setContentsMargins(24, 20, 24, 16);
    textColumn->setSpacing(4);

    auto* caption = new QLabel("NOW PLAYING");
    caption->setObjectName("NowPlayingCaption");
    QFont captionFont = caption->font();
    captionFont.setLetterSpacing(QFont::AbsoluteSpacing, 3);
    caption->setFont(captionFont);
    caption->setStyleSheet("background: transparent; color: #f0c3a4;");
    textColumn->addWidget(caption);

    m_bigTitleLabel = new QLabel("No file loaded");
    QFont titleFont;
    titleFont.setFamilies({"Playfair Display", "Microsoft YaHei UI", "Georgia"}); // CJK titles fall back to YaHei
    titleFont.setStyleHint(QFont::Serif);
    titleFont.setPointSize(34);
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
    // A fixed, deliberate width for this title area -- not derived from
    // the window's current size. It stays this width whether the window
    // is windowed, maximized, or resized; only how much of the title
    // that fits (and therefore whether the marquee runs at all) changes.
    m_bigTitleScrollArea->setMinimumWidth(0);
    m_bigTitleScrollArea->setMaximumWidth(kBigTitleBoxWidth - 32); // minus infoBox's own left+right margins
    // Horizontal Ignored keeps this scroll area's sizeHint (which tracks
    // the full unclipped title -- easily much wider than the box) from
    // ever counting toward this page's, m_stack's, or the window's own
    // minimum size -- it's the fixed width above that actually renders,
    // this just stops the unconstrained sizeHint from leaking upward.
    m_bigTitleScrollArea->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Fixed);
    textColumn->addWidget(m_bigTitleScrollArea);

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

    m_bigSubtitleLabel = new QLabel();
    // Same reserved-height fix as m_formatLabel in buildPlayerBar(), but
    // more consequential here: this page is inside m_stack, and
    // QStackedWidget sizes itself off the LARGEST minimum size across ALL
    // its pages, not just the one currently shown. So this label jumping
    // from empty to real text the first time a track plays (in
    // updateNowPlayingUi()) was silently growing the whole window's
    // required height even while the Library page, not this one, was the
    // one actually visible -- which is exactly why toggling to Now
    // Playing and back (forcing Qt to finish laying this page out) made
    // it "fix itself".
    QFont subtitleFont;
    subtitleFont.setFamilies({"Playfair Display", "Microsoft YaHei UI", "Georgia"});
    subtitleFont.setStyleHint(QFont::Serif);
    subtitleFont.setItalic(true);
    subtitleFont.setPointSize(15);
    m_bigSubtitleLabel->setFont(subtitleFont);
    m_bigSubtitleLabel->setStyleSheet("background: transparent; color: #f0c3a4;");
    m_bigSubtitleLabel->setMinimumHeight(m_bigSubtitleLabel->fontMetrics().height());
    textColumn->addWidget(m_bigSubtitleLabel);

    m_bigFormatLabel = new QLabel();
    m_bigFormatLabel->setStyleSheet("background: transparent; color: rgba(255,255,255,140);");
    m_bigFormatLabel->setMinimumHeight(m_bigFormatLabel->fontMetrics().height());
    textColumn->addWidget(m_bigFormatLabel);
    textColumn->addSpacing(10);

    m_lyricsList = new QListWidget();
    m_lyricsList->setStyleSheet(
        "QListWidget { background: transparent; border: none; }"
        "QListWidget::item { border: none; padding: 3px 0; }"
        "QListWidget::item:selected { background: transparent; }");
    m_lyricsList->setSelectionMode(QAbstractItemView::NoSelection);
    m_lyricsList->setFocusPolicy(Qt::NoFocus);
    m_lyricsList->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff); // teleprompter-style: it scrolls itself
    m_lyricsList->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_lyricsList->setWordWrap(true);
    m_lyricsBaseFont = m_lyricsList->font();
    m_lyricsBaseFont.setPointSize(15);
    m_lyricsList->setFont(m_lyricsBaseFont);
    m_lyricsList->viewport()->setMouseTracking(true); // needed for hover, no click/drag required
    m_lyricsList->viewport()->installEventFilter(this);
    textColumn->addWidget(m_lyricsList, 1);

    contentRow->addSpacing(32);
    contentRow->addWidget(infoBox, 1);
    contentRow->addStretch();
    pageLayout->addLayout(contentRow);

    pageLayout->addStretch(1);

    // Seek row: elapsed | slider | total, capped so it doesn't span a
    // maximized window edge to edge.
    auto* seekRow = new QHBoxLayout();
    seekRow->setSpacing(12);
    seekRow->addStretch();
    m_bigTimeLabel = new QLabel("0:00");
    m_bigTimeLabel->setObjectName("TimeLabel");
    m_bigTimeLabel->setFixedWidth(44);
    m_bigTimeLabel->setAlignment(Qt::AlignRight | Qt::AlignVCenter);
    seekRow->addWidget(m_bigTimeLabel);

    m_bigSeekSlider = new ClickSeekSlider(Qt::Horizontal);
    m_bigSeekSlider->setRange(0, 0);
    m_bigSeekSlider->setMinimumWidth(300);
    m_bigSeekSlider->setMaximumWidth(760);
    connect(m_bigSeekSlider, &QSlider::sliderPressed, this, [this]() { m_seeking = true; });
    connect(m_bigSeekSlider, &QSlider::sliderReleased, this, [this]() {
        m_seekSlider->setValue(m_bigSeekSlider->value());
        seekToSliderValue();
    });
    seekRow->addWidget(m_bigSeekSlider, 8);

    m_bigDurationLabel = new QLabel("0:00");
    m_bigDurationLabel->setObjectName("TimeLabel");
    m_bigDurationLabel->setFixedWidth(44);
    seekRow->addWidget(m_bigDurationLabel);
    seekRow->addStretch();
    pageLayout->addLayout(seekRow);
    pageLayout->addSpacing(6);

    // Transport row: volume on the left, transport centered, an equal-width
    // spacer on the right to keep the transport truly centered.
    static constexpr int kSideWidth = 220;
    auto* bigTransportRow = new QHBoxLayout();
    bigTransportRow->setSpacing(14);

    auto* volumeBox = new QWidget();
    volumeBox->setMinimumWidth(140);
    volumeBox->setMaximumWidth(kSideWidth);
    auto* bigVolumeRow = new QHBoxLayout(volumeBox);
    bigVolumeRow->setContentsMargins(0, 0, 0, 0);
    m_bigVolumeButton = MakeIconButton(34, 18, "Mute (Ctrl+M)");
    connect(m_bigVolumeButton, &QPushButton::clicked, this, &PlayerWindow::toggleMute);
    bigVolumeRow->addWidget(m_bigVolumeButton);

    // Volume -- mirrors the mini player bar's m_volumeSlider rather than
    // being a second source of truth. Moving either one updates the other;
    // m_volumeSlider stays the thing applyVolume()/loadSettings()/etc.
    // actually read from.
    m_bigVolumeSlider = new ClickSeekSlider(Qt::Horizontal);
    m_bigVolumeSlider->setRange(0, 200);
    m_bigVolumeSlider->setValue(m_volumeSlider->value());
    m_bigVolumeSlider->setMinimumWidth(70);
    m_bigVolumeSlider->setMaximumWidth(130);
    m_bigVolumeSlider->setFocusPolicy(Qt::NoFocus);
    bigVolumeRow->addWidget(m_bigVolumeSlider);
    bigVolumeRow->addStretch();

    bigTransportRow->addStretch(1);
    bigTransportRow->addWidget(volumeBox, 1);
    bigTransportRow->addSpacing(40);

    m_bigShuffleButton = MakeIconButton(42, 20, "Shuffle: Off");
    m_bigShuffleButton->setCheckable(true);
    m_bigPrevButton = MakeIconButton(46, 24, "Previous");
    m_bigPlayButton = MakeIconButton(64, 28, "Play");
    m_bigPlayButton->setObjectName("BigPlayButton");
    m_bigNextButton = MakeIconButton(46, 24, "Next");
    m_bigRepeatButton = MakeIconButton(42, 20, "Repeat: Off");
    m_bigRepeatButton->setCheckable(true);
    bigTransportRow->addWidget(m_bigShuffleButton);
    bigTransportRow->addWidget(m_bigPrevButton);
    bigTransportRow->addWidget(m_bigPlayButton);
    bigTransportRow->addWidget(m_bigNextButton);
    bigTransportRow->addWidget(m_bigRepeatButton);

    bigTransportRow->addSpacing(40);
    auto* rightSpacer = new QWidget();
    rightSpacer->setMinimumWidth(140);
    rightSpacer->setMaximumWidth(kSideWidth);
    bigTransportRow->addWidget(rightSpacer, 1);
    bigTransportRow->addStretch(1);
    pageLayout->addLayout(bigTransportRow);

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

    // StackAll keeps both layers visible at once -- video widget added
    // first so it paints behind the content. Only currentWidget() actually
    // receives mouse/keyboard input, so content must be made current or
    // every button/slider on this page would silently stop responding
    // (same issue as the whole-window version of this had).
    auto* pageLayers = new QStackedLayout(page);
    pageLayers->setStackingMode(QStackedLayout::StackAll);
    pageLayers->addWidget(m_wallpaperWidget);
    pageLayers->addWidget(content);
    pageLayers->setCurrentWidget(content);

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
    QString dir = QFileDialog::getExistingDirectory(this, "Select Music Folder");
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
    QHash<QString, TrackInfo> known;
    for (const TrackInfo& t : m_library.tracks())
    {
        known.insert(t.path, t);
    }
    m_scanCancel = std::make_shared<std::atomic<bool>>(false);
    auto cancel = m_scanCancel;

    m_scanThread = QThread::create([this, folders, known, cancel]() {
        QElapsedTimer sinceReport;
        sinceReport.start();
        const QVector<TrackInfo> tracks = MusicLibrary::ScanFolders(folders, known,
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
    m_scanLabel->setText("Loading library\u2026");
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
    m_scanLabel->setText(QString("Loading library\u2026 %1 of %2").arg(done).arg(total));
}

void PlayerWindow::onLibraryScanFinished(const QVector<TrackInfo>& scanned)
{
    m_scanThread = nullptr; // finishing on its own; deleteLater cleans it up

    // A folder removed while the scan ran must not come back with it.
    const QVector<TrackInfo> tracks = TracksInFolders(scanned, m_library.folders());

    // The usual case at launch: the cache was already right. Rebuilding every
    // view anyway would make a big library hitch for nothing.
    bool unchanged = tracks.size() == m_library.tracks().size();
    for (int i = 0; unchanged && i < tracks.size(); ++i)
    {
        const TrackInfo& a = tracks[i];
        const TrackInfo& b = m_library.tracks()[i];
        unchanged = a.path == b.path && a.modifiedMs == b.modifiedMs;
    }

    if (!unchanged)
    {
        m_library.setTracks(tracks);
        MusicLibrary::SaveCache(tracks);
        refreshTracksTable();
        refreshAlbumsAndArtists();
        refreshPlaylistsList();
        updateLibraryStatsUi();
    }

    m_scanBar->hide();
    m_refreshLibraryButton->setEnabled(true);
    updateAnalysisProgressUi(); // re-enables Analyze

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

        auto* removeButton = new QPushButton("Remove");
        connect(removeButton, &QPushButton::clicked, this, [this, dir]() { removeFolder(dir); });
        rowLayout->addWidget(removeButton);

        item->setSizeHint(rowWidget->sizeHint());
        m_foldersList->setItemWidget(item, rowWidget);
    }
}

void PlayerWindow::refreshTracksTable()
{
    // Filled in one go with repaints off -- row-by-row inserts with sorting
    // and painting live made a big library take seconds.
    m_tracksTable->setUpdatesEnabled(false);
    m_tracksTable->setSortingEnabled(false);
    // Auto-sized columns re-measure the whole column after every cell set --
    // quadratic in the row count. Fixed while filling, measured once after.
    QHeaderView* header = m_tracksTable->horizontalHeader();
    header->setSectionResizeMode(3, QHeaderView::Interactive);
    header->setSectionResizeMode(4, QHeaderView::Interactive);
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
        m_tracksTable->setItem(row, 3, new QTableWidgetItem(t.genre));
        m_tracksTable->setItem(row, 4, new QTableWidgetItem(t.year > 0 ? QString::number(t.year) : QString()));
    }

    m_tracksTable->setSortingEnabled(true);
    header->setSectionResizeMode(3, QHeaderView::ResizeToContents);
    header->setSectionResizeMode(4, QHeaderView::ResizeToContents);
    m_tracksTable->setUpdatesEnabled(true);

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
        auto* item = new QListWidgetItem(QString("%1  (%2 tracks)").arg(it.key()).arg(it.value().size()));
        item->setData(Qt::UserRole, it.value());
        m_albumsList->addItem(item);
    }

    m_artistsList->clear();
    QMap<QString, QStringList> artists = m_library.artistGroups();
    for (auto it = artists.constBegin(); it != artists.constEnd(); ++it)
    {
        auto* item = new QListWidgetItem(QString("%1  (%2 tracks)").arg(it.key()).arg(it.value().size()));
        item->setData(Qt::UserRole, it.value());
        m_artistsList->addItem(item);
    }
}


void PlayerWindow::updateLibraryStatsUi()
{
    const MusicLibrary::Stats s = m_library.stats();
    const int values[] = {s.trackCount, s.albumCount, s.artistCount, s.genreCount};
    for (int i = 0; i < m_statValueLabels.size(); ++i)
    {
        m_statValueLabels[i]->setText(QString::number(values[i]));
    }
    updateAnalysisProgressUi();
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
        m_analyzeStatusLabel->setText(QString("Analyzing… %1 of %2. You can keep using the app.")
                                          .arg(m_analyzer->done()).arg(m_analyzer->total()));
    }
    else
    {
        const int total = m_library.tracks().size();
        const int analyzed = m_songData->analyzedCount(m_library.tracks());
        m_analyzeButton->setEnabled(total > 0 && !m_scanThread);
        m_analyzeStatusLabel->setText(analyzed == total ? QString("All %1 songs analyzed").arg(total)
                                                        : QString("%1 of %2 songs analyzed").arg(analyzed).arg(total));
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
        [this](const QVector<TrackInfo>& group, int index) { setQueueAndPlay(group, index, "Duplicates"); },
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

void PlayerWindow::openTagMusicMenu()
{
    QMenu menu(this);
    QAction* viaMusicBrainz = menu.addAction("MusicBrainz");
    QAction* manual = menu.addAction("Manual tagging");
    QAction* chosen = menu.exec(m_musicBrainzButton->mapToGlobal(QPoint(0, m_musicBrainzButton->height())));

    if (chosen == viaMusicBrainz)
    {
        lookupSelectedTrackOnMusicBrainz();
    }
    else if (chosen == manual)
    {
        openManualTagDialog();
    }
}

void PlayerWindow::openManualTagDialog()
{
    QTableWidgetItem* item = m_tracksTable->currentItem();
    if (!item)
    {
        return;
    }
    int row = item->row();
    QString path = m_tracksTable->item(row, 0)->data(Qt::UserRole).toString();

    QMap<QString, QString> tags = ReadAllTags(path);

    ManualTagDialog dialog(path, tags, ReadCoverArt(path), this);
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
    connect(&dialog, &ManualTagDialog::fillFromInternetRequested, this, [this, &dialog]() {
        lookupSelectedTrackOnMusicBrainz(&dialog);
    });

    if (dialog.exec() == QDialog::Accepted)
    {
        afterTrackFileWritten(path);
    }
}

void PlayerWindow::openChangeCoverDialog()
{
    QTableWidgetItem* item = m_tracksTable->currentItem();
    if (!item)
    {
        return;
    }
    int row = item->row();
    QString path = m_tracksTable->item(row, 0)->data(Qt::UserRole).toString();

    QString imagePath = QFileDialog::getOpenFileName(this, "Choose Cover Image", QString(),
        "Images (*.jpg *.jpeg *.png)");
    if (imagePath.isEmpty())
    {
        return;
    }

    QFile imageFile(imagePath);
    if (!imageFile.open(QIODevice::ReadOnly))
    {
        ErrorReporter::warn(this, "Couldn't read image", "The selected image file couldn't be opened.");
        return;
    }
    QByteArray imageData = imageFile.readAll();
    QString mimeType = imagePath.toLower().endsWith(".png") ? "image/png" : "image/jpeg";

    if (!writeTrackFile(path, [&]() { return WriteCoverArt(path, imageData, mimeType); }))
    {
        ErrorReporter::warn(this, "Couldn't save cover", "The file's cover art couldn't be written to.");
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
        ErrorReporter::warn(this, "Failed to reload track", "Could not reload: " + QFileInfo(path).fileName());
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

// MusicBrainz asks for an identifying User-Agent with a way to reach the
// app's maintainer (a project URL is accepted); LRCLIB asks the same.
const char* kUserAgent = "AudioForge/1.0 ( https://github.com/biasnil/Audio-Forge )";

} // namespace

void PlayerWindow::lookupSelectedTrackOnMusicBrainz(ManualTagDialog* targetDialog)
{
    if (m_musicBrainzLookupInFlight)
    {
        return; // e.g. "Fill from internet" clicked again before the first reply landed
    }

    QTableWidgetItem* item = m_tracksTable->currentItem();
    if (!item)
    {
        return;
    }
    int row = item->row();
    QString path = m_tracksTable->item(row, 0)->data(Qt::UserRole).toString();
    QString title = m_tracksTable->item(row, 0)->text();
    QString artist = m_tracksTable->item(row, 1)->text();

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
    request.setHeader(QNetworkRequest::UserAgentHeader, kUserAgent);

    m_musicBrainzLookupInFlight = true;
    m_musicBrainzButton->setEnabled(false);
    m_musicBrainzButton->setText("Looking up...");

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
    m_musicBrainzButton->setText("Tag Music");
    m_musicBrainzButton->setEnabled(!m_tracksTable->selectedItems().isEmpty());

    if (forManualDialog && !targetDialog)
    {
        return; // the manual tag dialog was closed mid-lookup -- nothing to fill in, and never write directly
    }

    if (reply->error() != QNetworkReply::NoError)
    {
        ErrorReporter::warn(this, "MusicBrainz lookup failed", reply->errorString());
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
        ErrorReporter::warn(this, "Couldn't save tags", "The file's tags could not be written to.");
        return;
    }

    afterTrackFileWritten(path);
}

// --- Lyrics (Musixmatch API, then local .lrc/.txt, then nothing) -------

void PlayerWindow::fetchLyricsFor(const TrackInfo& info)
{
    QString path = info.path; // guards against a stale reply landing after the track's changed
    setLyricsPlaceholder("Loading lyrics...");

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
    request.setHeader(QNetworkRequest::UserAgentHeader, kUserAgent);

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
        setLyricsPlaceholder("No lyrics found.");
    }
}

void PlayerWindow::setLyricsPlaceholder(const QString& text)
{
    m_lyricsList->clear();
    m_syncedLyrics.clear();
    m_lastLyricsLineIndex = -1;
    m_hoveredLyricsLineIndex = -1;

    auto* item = new QListWidgetItem(text);
    item->setFlags(item->flags() & ~Qt::ItemIsSelectable);
    item->setForeground(QColor(255, 255, 255, 120));
    m_lyricsList->addItem(item);
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

    if (synced && !m_syncedLyrics.isEmpty())
    {
        // One item per timed line -- left dim for now; the next playback
        // tick's updateSyncedLyricsHighlight() call lights up whichever
        // line the current cursor position actually falls on.
        for (const SyncedLyricLine& line : m_syncedLyrics)
        {
            auto* item = new QListWidgetItem(line.text.isEmpty() ? QStringLiteral(" ") : line.text);
            item->setFlags(item->flags() & ~Qt::ItemIsSelectable);
            item->setFont(m_lyricsBaseFont);
            item->setForeground(QColor(255, 255, 255, 90));
            item->setTextAlignment(Qt::AlignHCenter);
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

    for (int i = 0; i < m_lyricsList->count(); ++i)
    {
        QListWidgetItem* item = m_lyricsList->item(i);
        QFont font = m_lyricsBaseFont;
        int distance = qAbs(i - currentIndex);

        if (i == currentIndex)
        {
            font.setBold(true);
            font.setPointSize(m_lyricsBaseFont.pointSize() + 8);
            item->setForeground(QColor(255, 255, 255, 255));
        }
        else
        {
            int alpha = qMax(50, 190 - distance * 35); // fades out the further a line is from "now"
            item->setForeground(QColor(255, 255, 255, alpha));
        }
        font.setUnderline(i == m_hoveredLyricsLineIndex); // keep the hover underline through a restyle
        item->setFont(font);
    }

    if (currentIndex >= 0)
    {
        m_lyricsList->scrollToItem(m_lyricsList->item(currentIndex), QAbstractItemView::PositionAtCenter);
    }
}

bool PlayerWindow::eventFilter(QObject* watched, QEvent* event)
{
    if (watched != m_lyricsList->viewport())
    {
        return QWidget::eventFilter(watched, event);
    }

    if (event->type() == QEvent::MouseMove || event->type() == QEvent::Leave)
    {
        QListWidgetItem* item = nullptr;
        if (event->type() == QEvent::MouseMove)
        {
            item = m_lyricsList->itemAt(static_cast<QMouseEvent*>(event)->pos());
        }

        int index = item ? m_lyricsList->row(item) : -1;
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
            if (m_hoveredLyricsLineIndex >= 0 && m_hoveredLyricsLineIndex < m_lyricsList->count())
            {
                QFont font = m_lyricsList->item(m_hoveredLyricsLineIndex)->font();
                font.setUnderline(false);
                m_lyricsList->item(m_hoveredLyricsLineIndex)->setFont(font);
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
            int index = item ? m_lyricsList->row(item) : -1;
            if (index >= 0 && index < m_syncedLyrics.size())
            {
                seekTo(m_syncedLyrics[index].seconds);
                // no manual UI push needed -- the next updatePlayback() tick
                // (m_seeking is false here) picks up the new cursor and
                // moves both seek sliders + the highlighted line itself
            }
            // no timestamp on this line -- intentionally does nothing
        }
    }

    return QWidget::eventFilter(watched, event);
}

void PlayerWindow::openGroupTracks(const QString& labelWithCount, const QStringList& paths)
{
    QString title = labelWithCount.section("  (", 0, 0); // strip the "(N tracks)" suffix
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
    m_playlistsList->clear();
    const QVector<PlaylistData>& playlists = m_library.playlists();
    for (int i = 0; i < playlists.size(); ++i)
    {
        const PlaylistData& pl = playlists[i];
        auto* item = new QListWidgetItem(QString("%1  (%2 tracks)").arg(pl.name).arg(pl.trackPaths.size()));
        item->setData(Qt::UserRole, i);
        m_playlistsList->addItem(item);
    }

    m_sidebarPlaylistsList->clear();
    for (int i = 0; i < playlists.size(); ++i)
    {
        auto* item = new QListWidgetItem(playlists[i].name);
        item->setData(Qt::UserRole, i);
        item->setToolTip(QString("%1 tracks").arg(playlists[i].trackPaths.size()));
        m_sidebarPlaylistsList->addItem(item);
    }
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
    showToast(tracks.size() == 1 ? QString("“%1” plays next.").arg(tracks.first().title)
                                 : QString("%1 songs play next.").arg(tracks.size()));
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
    showToast(tracks.size() == 1 ? QString("Added “%1” to the queue.").arg(tracks.first().title)
                                 : QString("Added %1 songs to the queue.").arg(tracks.size()));
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
        ErrorReporter::info(this, "Nothing to save", "The queue is empty -- play something first.");
        return;
    }

    bool ok = false;
    const QString name = QInputDialog::getText(this, "Save Queue as Playlist", "Playlist name:",
                                               QLineEdit::Normal, "Queue", &ok).trimmed();
    if (!ok)
    {
        return;
    }
    PlaylistData playlist;
    playlist.name = name.isEmpty() ? QStringLiteral("Queue") : name;
    playlist.trackPaths = paths;
    m_library.addPlaylist(playlist);
    refreshPlaylistsList();
    showToast(QString("Saved the queue as “%1”.").arg(playlist.name));
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
    setQueueAndPlay(queue, startIndex, "Tracks");
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
    menu.addAction(MenuIcon(theme::Icon::Play, m_darkMode), single ? "Play" : QString("Play %1 Songs").arg(tracks.size()),
                   this, [this, tracks, single, row = clicked.row()]() {
        if (single)
        {
            playTracksTableRow(row); // the whole visible list, from here -- same as double-click
        }
        else
        {
            setQueueAndPlay(tracks, 0, "Selection");
        }
    });
    menu.addAction(MenuIcon(theme::Icon::Next, m_darkMode), "Play Next",
                   this, [this, tracks]() { playNextTracks(tracks); });
    menu.addAction(MenuIcon(theme::Icon::Queue, m_darkMode), "Add to Queue",
                   this, [this, tracks]() { addTracksToQueue(tracks); });
    if (single)
    {
        menu.addSeparator();
        menu.addAction("Tag Music...", this, &PlayerWindow::openTagMusicMenu);
        menu.addAction("Change Cover...", this, &PlayerWindow::openChangeCoverDialog);
        menu.addAction("Show in Folder", this, [path = tracks.first().path]() { ShowInFileManager(path); });
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
            showTracksMenu(globalPos, item->data(Qt::UserRole).toStringList(),
                           item->text().section("  (", 0, 0)); // strip the "(N tracks)" suffix
            return;
        }
        const int index = item->data(Qt::UserRole).toInt();
        if (index >= 0 && index < m_library.playlists().size())
        {
            const PlaylistData& playlist = m_library.playlists()[index];
            showTracksMenu(globalPos, playlist.trackPaths, playlist.name);
        }
    });
}

void PlayerWindow::showTracksMenu(const QPoint& globalPos, const QStringList& paths, const QString& name)
{
    if (paths.isEmpty())
    {
        return;
    }
    const QVector<TrackInfo> tracks = m_library.buildQueueFromPaths(paths);

    QMenu menu(this);
    menu.addAction(MenuIcon(theme::Icon::Play, m_darkMode), "Play",
                   this, [this, tracks, name]() { setQueueAndPlay(tracks, 0, name); });
    menu.addAction(MenuIcon(theme::Icon::Shuffle, m_darkMode), "Shuffle",
                   this, [this, tracks, name]() {
        if (m_queue.shuffleMode() == ShuffleMode::Off)
        {
            cycleShuffleMode(); // Off -> Random
        }
        setQueueAndPlay(tracks, static_cast<int>(QRandomGenerator::global()->bounded(tracks.size())), name);
    });
    menu.addSeparator();
    menu.addAction(MenuIcon(theme::Icon::Next, m_darkMode), "Play Next",
                   this, [this, tracks]() { playNextTracks(tracks); });
    menu.addAction(MenuIcon(theme::Icon::Queue, m_darkMode), "Add to Queue",
                   this, [this, tracks]() { addTracksToQueue(tracks); });
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
        case ShuffleMode::Off:    shuffleText = "Shuffle: Off"; break;
        case ShuffleMode::Random: shuffleText = "Shuffle: Random"; break;
        case ShuffleMode::Smart:  shuffleText = "Shuffle: Smart"; shuffleIcon = theme::Icon::ShuffleSmart; break;
    }

    QString repeatText;
    theme::Icon repeatIcon = theme::Icon::Repeat;
    switch (m_queue.repeatMode())
    {
        case RepeatMode::Off: repeatText = "Repeat: Off"; break;
        case RepeatMode::All: repeatText = "Repeat: All"; break;
        case RepeatMode::One: repeatText = "Repeat: One"; repeatIcon = theme::Icon::RepeatOne; break;
    }

    const bool shuffleOn = m_queue.shuffleMode() != ShuffleMode::Off;
    const bool repeatOn = m_queue.repeatMode() != RepeatMode::Off;
    const theme::Tokens& t = theme::tokens(m_darkMode);
    const QIcon shuffle = theme::icon(shuffleIcon, t.muted, t.accent, t.border);
    const QIcon repeat = theme::icon(repeatIcon, t.muted, t.accent, t.border);

    for (QPushButton* button : {m_shuffleButton, m_bigShuffleButton})
    {
        if (button)
        {
            button->setIcon(shuffle);
            button->setChecked(shuffleOn);
            button->setToolTip(shuffleText + "  (Ctrl+S)");
        }
    }
    for (QPushButton* button : {m_repeatButton, m_bigRepeatButton})
    {
        if (button)
        {
            button->setIcon(repeat);
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
    m_themeButton->setToolTip(m_darkMode ? "Switch to light theme" : "Switch to dark theme");
    refreshIcons();
}

void PlayerWindow::refreshIcons()
{
    const theme::Tokens& t = theme::tokens(m_darkMode);

    // Indexed by tab index, same as m_navButtons (see buildSidebar).
    const theme::Icon navIcons[] = {
        theme::Icon::Album, theme::Icon::Track, theme::Icon::Artist, theme::Icon::Folder,
        theme::Icon::Playlist, theme::Icon::Equalizer, theme::Icon::Wallpaper, theme::Icon::Settings,
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
    m_bigPrevButton->setIcon(theme::icon(theme::Icon::Previous, t.text, QColor(), t.border, 24));
    m_bigNextButton->setIcon(theme::icon(theme::Icon::Next, t.text, QColor(), t.border, 24));
    m_stopButton->setIcon(theme::icon(theme::Icon::Stop, t.muted, QColor(), t.border, 16));
    m_backButton->setIcon(theme::icon(theme::Icon::Back, t.text));
    m_fullscreenButton->setIcon(theme::icon(theme::Icon::FullScreen, t.muted));
    m_sleepButton->setIcon(theme::icon(theme::Icon::Timer, t.muted, t.accent));
    m_queueButton->setIcon(theme::icon(theme::Icon::Queue, t.muted, t.accent));
    m_bigQueueButton->setIcon(theme::icon(theme::Icon::Queue, t.muted));
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

    const theme::Tokens& t = theme::tokens(m_darkMode);
    const theme::Icon kind = playing ? theme::Icon::Pause : theme::Icon::Play;
    const QString tip = playing ? "Pause (Space)" : "Play (Space)";
    m_playButton->setIcon(theme::icon(kind, t.accentText, QColor(), t.muted, 20));
    m_playButton->setToolTip(tip);
    m_bigPlayButton->setIcon(theme::icon(kind, t.accentText, QColor(), t.muted, 28));
    m_bigPlayButton->setToolTip(tip);
}

void PlayerWindow::showCoverPlaceholders()
{
    const theme::Tokens& t = theme::tokens(m_darkMode);
    m_coverArtLabel->setAlignment(Qt::AlignCenter);
    m_bigCoverArtLabel->setAlignment(Qt::AlignCenter);
    m_coverArtLabel->setPixmap(theme::icon(theme::Icon::Track, t.muted, QColor(), QColor(), 24).pixmap(QSize(24, 24)));
    m_bigCoverArtLabel->setPixmap(theme::icon(theme::Icon::Track, t.muted, QColor(), QColor(), 96).pixmap(QSize(96, 96)));
    m_coverPlaceholderShown = true;
}

void PlayerWindow::updateVolumeIcons()
{
    const theme::Tokens& t = theme::tokens(m_darkMode);
    const QIcon icon = theme::icon(m_volumeSlider->value() == 0 ? theme::Icon::VolumeMute : theme::Icon::Volume, t.muted);
    m_volumeButton->setIcon(icon);
    m_bigVolumeButton->setIcon(icon);
}

void PlayerWindow::openFile()
{
    QString path = QFileDialog::getOpenFileName(this, "Open MP3", QString(), "MP3 Files (*.mp3);;All Files (*)");
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

        // Where to start: a long track resumes where it was left; otherwise
        // Smart Transitions skips silence at the start.
        const float length = m_engine.lengthSeconds();
        const double resume = m_rememberLongTracks && length >= kLongTrackSeconds
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
            ErrorReporter::warn(this, "Failed to load track", "Could not load: " + QFileInfo(info.path).fileName());
        }
        else
        {
            // Restoring last session's track at launch: no popup before the window's even up.
            ErrorReporter::logOnly("Could not restore last track: " + info.path);
        }
        m_titleLabel->setText("Failed to load: " + QFileInfo(info.path).fileName());
        m_formatLabel->clear();
        m_coverArtLabel->clear();
        setControlsEnabled(false);
    }
}

void PlayerWindow::updateNowPlayingUi(const TrackInfo& info)
{
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
    QColor bg = AverageColor(coverArt);
    m_infoBox->setStyleSheet(
        QString("background-color: rgba(%1, %2, %3, 190); border-radius: 16px;").arg(bg.red()).arg(bg.green()).arg(bg.blue()));

    fetchLyricsFor(info);
    updateWallpaperForTrack(info);

    // Uses the real cursor, not 0 -- after a crossfade the new track is
    // already crossfadeSeconds in by the time it becomes "current".
    updateDiscordPresence();
    refreshUpNext();
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
    m_globalWallpaperLabel->setText(path.isEmpty() ? "None" : QFileInfo(path).fileName());
}

void PlayerWindow::chooseGlobalWallpaper()
{
    QString path = QFileDialog::getOpenFileName(this, "Choose global wallpaper video", QString(), "Video files (*.mp4)");
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
        QString label = QString("%1  (%2 tracks)")
            .arg(QFileInfo(entry.videoPath).fileName())
            .arg(entry.trackPaths.size());
        auto* item = new QListWidgetItem(label);
        item->setData(Qt::UserRole, i);
        m_wallpaperEntriesList->addItem(item);
    }
}

void PlayerWindow::addWallpaperEntry()
{
    QString videoPath = QFileDialog::getOpenFileName(this, "Choose wallpaper video", QString(), "Video files (*.mp4)");
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
        ErrorReporter::info(this, "No wallpaper selected", "Select a wallpaper from the list first.");
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
        ErrorReporter::info(this, "No wallpaper selected", "Select a wallpaper from the list first.");
        return;
    }

    int index = item->data(Qt::UserRole).toInt();
    bool confirmed = ErrorReporter::confirm(this, "Remove wallpaper",
        "Remove this wallpaper assignment? This can't be undone.");
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
    add("Off", 0);
    menu.addSeparator();
    for (int minutes : {5, 15, 30, 45, 60, 90})
    {
        add(QString("%1 minutes").arg(minutes), minutes);
    }
    add("End of Track", -1);
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
    m_sleepButton->setToolTip(minutes == 0 ? "Sleep timer"
                              : minutes == -1 ? "Sleep timer: end of track"
                                              : QString("Sleep timer: %1 minutes").arg(minutes));
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
        m_sleepButton->setToolTip(QString("Sleep timer: %1 min left").arg(minutesLeft));
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
    if (!m_rememberLongTracks || m_queue.isEmpty() || !m_engine.isLoaded())
    {
        return;
    }
    const float length = m_engine.lengthSeconds();
    if (length < kLongTrackSeconds)
    {
        return;
    }
    const float position = m_engine.cursorSeconds();
    const bool keep = position > 15.0f && position < length - 30.0f;
    m_songData->setResumePosition(m_queue.currentTrack().path, keep ? position : -1.0);
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

    if (!m_syncedLyrics.isEmpty())
    {
        updateSyncedLyricsHighlight(cursor);
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
        && !m_queue.isEmpty() && length > 0.0f)
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

    // The last scan's results show straight away; the background scan started
    // at the end of the constructor then catches up with any changes on disk.
    m_library.setTracks(TracksInFolders(MusicLibrary::LoadCache(), folders));
    refreshFoldersList();
    refreshTracksTable();
    refreshAlbumsAndArtists();
    updateLibraryStatsUi();

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
    settings.setValue("analysisCompleted", m_analysisCompleted);
    settings.setValue("smartTransitions", m_smartTransitions);
    settings.setValue("beatMatchedCrossfade", m_beatMatchedCrossfade);
    settings.setValue("harmonicShuffle", m_harmonicShuffle);
    settings.setValue("crossfadeEnabled", m_crossfadeEnabled);
    settings.setValue("crossfadeSeconds", m_crossfadeSeconds);
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