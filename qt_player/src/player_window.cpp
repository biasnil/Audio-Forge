#include "audioforge/player_window.hpp"
#include "audioforge/track_metadata.hpp"
#include "audioforge/warnings.hpp"
#include "audioforge/widgets/clickable_widget.hpp"
#include "audioforge/dialogs/track_list_dialog.hpp"
#include "audioforge/dialogs/create_playlist_dialog.hpp"
#include "audioforge/dialogs/playlist_edit_dialog.hpp"
#include "audioforge/dialogs/musicbrainz_result_dialog.hpp"
#include "audioforge/dialogs/manual_tag_dialog.hpp"
#include "audioforge/track_metadata_tags.hpp"
#include "audioforge/lyrics_provider.hpp"
#include "audioforge/cover_art_writer.hpp"

#include <QVBoxLayout>
#include <QMenu>
#include <QShortcut>
#include <QFile>
#include <QScrollArea>
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
#include <QSettings>
#include <QStandardPaths>
#include <QCloseEvent>

#include <cmath>

namespace audioforge {

// Qt's built-in "Fusion" style is the one that actually respects a custom
// QPalette on every platform -- the native Windows style ignores most
// palette colors, so switching themes would silently do nothing without it.
static QPalette DarkPalette()
{
    QPalette p;
    p.setColor(QPalette::Window, QColor(30, 31, 34));
    p.setColor(QPalette::WindowText, Qt::white);
    p.setColor(QPalette::Base, QColor(24, 25, 28));
    p.setColor(QPalette::AlternateBase, QColor(45, 46, 50));
    p.setColor(QPalette::ToolTipBase, Qt::white);
    p.setColor(QPalette::ToolTipText, Qt::white);
    p.setColor(QPalette::Text, Qt::white);
    p.setColor(QPalette::Button, QColor(45, 46, 50));
    p.setColor(QPalette::ButtonText, Qt::white);
    p.setColor(QPalette::BrightText, Qt::red);
    p.setColor(QPalette::Link, QColor(100, 150, 230));
    p.setColor(QPalette::Highlight, QColor(60, 110, 200));
    p.setColor(QPalette::HighlightedText, Qt::black);
    p.setColor(QPalette::Disabled, QPalette::Text, QColor(120, 120, 120));
    p.setColor(QPalette::Disabled, QPalette::ButtonText, QColor(120, 120, 120));
    return p;
}

static QPalette LightPalette()
{
    QPalette p;
    p.setColor(QPalette::Window, QColor(240, 240, 240));
    p.setColor(QPalette::WindowText, Qt::black);
    p.setColor(QPalette::Base, Qt::white);
    p.setColor(QPalette::AlternateBase, QColor(233, 233, 233));
    p.setColor(QPalette::ToolTipBase, Qt::black);
    p.setColor(QPalette::ToolTipText, Qt::black);
    p.setColor(QPalette::Text, Qt::black);
    p.setColor(QPalette::Button, QColor(230, 230, 230));
    p.setColor(QPalette::ButtonText, Qt::black);
    p.setColor(QPalette::BrightText, Qt::red);
    p.setColor(QPalette::Link, QColor(0, 90, 200));
    p.setColor(QPalette::Highlight, QColor(60, 130, 230));
    p.setColor(QPalette::HighlightedText, Qt::white);
    p.setColor(QPalette::Disabled, QPalette::Text, QColor(160, 160, 160));
    p.setColor(QPalette::Disabled, QPalette::ButtonText, QColor(160, 160, 160));
    return p;
}

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
    resize(950, 680);

    if (!m_engine.init())
    {
        ErrorReporter::warn(this, "Audio engine failed to start",
            "The audio engine could not be initialized (no audio device found?). Playback controls won't work.");
    }

    m_network = new QNetworkAccessManager(this);

    m_stack = new QStackedWidget();
    auto* outerLayout = new QVBoxLayout(this);
    outerLayout->addWidget(m_stack);

    // --- Library page (search + tabs + mini player bar) ---
    auto* libraryPage = new QWidget();
    auto* rootLayout = new QVBoxLayout(libraryPage);

    m_searchBar = new QLineEdit();
    m_searchBar->setPlaceholderText("Search your music...");
    connect(m_searchBar, &QLineEdit::textChanged, this, &PlayerWindow::applySearchFilter);
    rootLayout->addWidget(m_searchBar);

    m_tabs = new QTabWidget();
    rootLayout->addWidget(m_tabs, 1);

    m_tabs->addTab(buildAlbumsTab(), "Albums");     // index 0
    m_tabs->addTab(buildTracksTab(), "Tracks");      // index 1
    m_tabs->addTab(buildArtistsTab(), "Artists");    // index 2
    m_tabs->addTab(buildFoldersTab(), "Folders");    // index 3
    m_tabs->addTab(buildPlaylistsTab(), "Playlists");// index 4
    m_tabs->addTab(buildEqualizerTab(), "Equalizer"); // index 5 (always visible, not in the visibility config)
    m_tabs->addTab(buildSettingsTab(), "Settings");  // index 6 (always visible, not in the visibility config)
    m_tabs->setCurrentIndex(1); // Tracks

    rootLayout->addWidget(buildPlayerBar());

    m_stack->addWidget(libraryPage);          // index 0
    m_stack->addWidget(buildNowPlayingPage());// index 1
    m_stack->setCurrentIndex(0);

    // Qt apps are event-loop driven, not a manual render loop -- a timer is
    // the normal way to poll playback position, detect end-of-track /
    // crossfade windows, and refresh the UI periodically.
    m_updateTimer = new QTimer(this);
    connect(m_updateTimer, &QTimer::timeout, this, &PlayerWindow::updatePlayback);
    m_updateTimer->start(200);

    setupShortcuts();
    loadSettings();
}

PlayerWindow::~PlayerWindow() = default; // AudioEngine's own destructor tears down miniaudio

void PlayerWindow::closeEvent(QCloseEvent* event)
{
    saveSettings();
    QWidget::closeEvent(event);
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
    m_tracksTable->setEditTriggers(QAbstractItemView::NoEditTriggers);
    m_tracksTable->setSelectionBehavior(QAbstractItemView::SelectRows);
    m_tracksTable->setSelectionMode(QAbstractItemView::SingleSelection);
    m_tracksTable->setSortingEnabled(true);

    connect(m_tracksTable, &QTableWidget::cellDoubleClicked, this, [this](int row, int) {
        // Queue = every row currently shown, in the table's visual
        // (possibly sorted) order -- so Next/Previous follow what's on
        // screen rather than some hidden original order.
        QVector<TrackInfo> queue;
        for (int r = 0; r < m_tracksTable->rowCount(); ++r)
        {
            QString path = m_tracksTable->item(r, 0)->data(Qt::UserRole).toString();
            queue << m_library.findTrackInfo(path);
        }
        setQueueAndPlay(queue, row);
    });

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

QWidget* PlayerWindow::buildSettingsTab()
{
    auto* w = new QWidget();
    auto* l = new QVBoxLayout(w);

    // --- Stats ---
    l->addWidget(sectionHeader("Library"));
    m_statsLabel = new QLabel();
    l->addWidget(m_statsLabel);

    auto* refreshButton = new QPushButton("Refresh Library");
    connect(refreshButton, &QPushButton::clicked, this, &PlayerWindow::refreshLibrary);
    l->addWidget(refreshButton);

    l->addSpacing(16);

    // --- Visible tabs ---
    l->addWidget(sectionHeader("Visible Tabs"));
    const QVector<QPair<QString, int>> tabConfig = {
        {"Albums", 0}, {"Tracks", 1}, {"Artists", 2}, {"Folders", 3}, {"Playlists", 4}
    };
    for (const auto& entry : tabConfig)
    {
        auto* checkbox = new QCheckBox(entry.first);
        checkbox->setChecked(true);
        int tabIndex = entry.second;
        connect(checkbox, &QCheckBox::toggled, this, [this, tabIndex](bool checked) {
            m_tabs->setTabVisible(tabIndex, checked);
        });
        l->addWidget(checkbox);
    }

    l->addSpacing(16);

    // --- Lyrics ---
    l->addWidget(sectionHeader("Lyrics (Musixmatch)"));
    l->addWidget(new QLabel("API key -- get one at musixmatch.com. Free-tier keys only return a lyrics snippet;\nwithout a key (or if no match is found), a same-name .lrc/.txt next to the track is used instead."));
    m_musixmatchApiKeyEdit = new QLineEdit();
    m_musixmatchApiKeyEdit->setEchoMode(QLineEdit::Password);
    m_musixmatchApiKeyEdit->setPlaceholderText("Musixmatch API key (optional)");
    connect(m_musixmatchApiKeyEdit, &QLineEdit::textChanged, this, [this](const QString& text) {
        m_musixmatchApiKey = text.trimmed();
    });
    l->addWidget(m_musixmatchApiKeyEdit);

    l->addSpacing(16);

    // --- Crossfade ---
    l->addWidget(sectionHeader("Crossfade"));
    m_crossfadeCheckbox = new QCheckBox("Crossfade between tracks");
    connect(m_crossfadeCheckbox, &QCheckBox::toggled, this, [this](bool checked) {
        m_crossfadeEnabled = checked;
    });
    l->addWidget(m_crossfadeCheckbox);

    m_crossfadeLabel = new QLabel("Crossfade duration: 5s");
    l->addWidget(m_crossfadeLabel);

    m_crossfadeSlider = new QSlider(Qt::Horizontal);
    m_crossfadeSlider->setRange(2, 15);
    m_crossfadeSlider->setValue(5);
    connect(m_crossfadeSlider, &QSlider::valueChanged, this, [this](int value) {
        m_crossfadeSeconds = value;
        m_crossfadeLabel->setText(QString("Crossfade duration: %1s").arg(value));
    });
    l->addWidget(m_crossfadeSlider);

    l->addStretch();
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

        auto* slider = new QSlider(Qt::Vertical);
        slider->setRange(-150, 150); // tenths of a dB: -15.0..+15.0
        slider->setValue(0);
        slider->setMinimumHeight(220);
        int bandIndex = i;
        connect(slider, &QSlider::valueChanged, this, [this, bandIndex](int value) { onEqBandChanged(bandIndex, value); });
        m_eqBandSliders << slider;

        // The dB label is a child of the slider itself, floated at the
        // handle's position and repositioned every time the value changes
        // (see positionEqValueLabel) -- rather than a fixed row above the
        // slider that drifts away from wherever the handle actually is.
        auto* valueLabel = new QLabel("0.0", slider);
        valueLabel->setStyleSheet("color: white; font-weight: bold; background: transparent;");
        valueLabel->setAttribute(Qt::WA_TransparentForMouseEvents); // don't block dragging the handle underneath it
        m_eqBandValueLabels << valueLabel;

        bandColumn->addWidget(slider, 0, Qt::AlignHCenter);

        float freq = AudioEngine::equalizerBandFrequency(i);
        QString freqText = freq >= 1000.0f ? QString("%1K").arg(freq / 1000.0f, 0, 'g', 2) : QString::number(static_cast<int>(freq));
        auto* freqLabel = new QLabel(freqText);
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

    // Slider geometry isn't final until this widget's actually been laid
    // out and shown, so the first label placement is deferred one event
    // loop turn rather than computed here (where every slider would still
    // report a 0-height rect).
    QTimer::singleShot(0, this, [this]() {
        for (int i = 0; i < m_eqBandSliders.size(); ++i)
        {
            positionEqValueLabel(m_eqBandSliders[i], m_eqBandValueLabels[i]);
        }
    });

    return section;
}

void PlayerWindow::positionEqValueLabel(QSlider* slider, QLabel* label)
{
    label->adjustSize();

    QStyleOptionSlider opt;
    opt.initFrom(slider);
    opt.orientation = slider->orientation();
    opt.minimum = slider->minimum();
    opt.maximum = slider->maximum();
    opt.sliderPosition = slider->value();
    opt.sliderValue = slider->value();
    opt.subControls = QStyle::SC_SliderHandle;

    QRect handleRect = slider->style()->subControlRect(QStyle::CC_Slider, &opt, QStyle::SC_SliderHandle, slider);

    int x = (slider->width() - label->width()) / 2;
    int y = qMax(0, handleRect.center().y() - label->height() - 4); // just above the handle; clamped so it's never clipped off the top
    label->move(x, y);
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
    m_eqBandValueLabels[bandIndex]->setText(QString::number(gainDb, 'f', 1));
    positionEqValueLabel(m_eqBandSliders[bandIndex], m_eqBandValueLabels[bandIndex]);
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

QWidget* PlayerWindow::buildPlayerBar()
{
    auto* bar = new QWidget();
    auto* outerLayout = new QHBoxLayout(bar);

    // Clicking the cover/title area opens the full Now Playing page.
    auto* clickableInfo = new ClickableWidget();
    connect(clickableInfo, &ClickableWidget::clicked, this, [this]() { m_stack->setCurrentIndex(1); });
    auto* infoLayout = new QHBoxLayout(clickableInfo);
    infoLayout->setContentsMargins(0, 0, 0, 0);

    m_coverArtLabel = new QLabel();
    m_coverArtLabel->setFixedSize(72, 72);
    m_coverArtLabel->setScaledContents(false); // pixmap is pre-cropped to exact label size (see ScaledCoverArt)
    infoLayout->addWidget(m_coverArtLabel);
    infoLayout->addSpacing(10);

    auto* infoTextLayout = new QVBoxLayout();
    infoLayout->addLayout(infoTextLayout, 1);

    m_titleLabel = new QLabel("No file loaded");
    infoTextLayout->addWidget(m_titleLabel);

    m_formatLabel = new QLabel();
    m_formatLabel->setStyleSheet("color: gray;");
    infoTextLayout->addWidget(m_formatLabel);

    outerLayout->addWidget(clickableInfo);

    auto* layout = new QVBoxLayout();
    outerLayout->addLayout(layout, 1);

    m_seekSlider = new QSlider(Qt::Horizontal);
    m_seekSlider->setRange(0, 0);
    layout->addWidget(m_seekSlider);

    m_timeLabel = new QLabel("00:00 / 00:00");
    layout->addWidget(m_timeLabel);

    // Transport row: Shuffle | Previous | Play | Pause | Stop | Next | Repeat
    auto* transportRow = new QHBoxLayout();
    m_shuffleButton = new QPushButton("Shuffle: Off");
    m_prevButton = new QPushButton("Previous");
    m_playButton = new QPushButton("Play");
    m_pauseButton = new QPushButton("Pause");
    m_stopButton = new QPushButton("Stop");
    m_nextButton = new QPushButton("Next");
    m_repeatButton = new QPushButton("Repeat: Off");
    transportRow->addWidget(m_shuffleButton);
    transportRow->addWidget(m_prevButton);
    transportRow->addWidget(m_playButton);
    transportRow->addWidget(m_pauseButton);
    transportRow->addWidget(m_stopButton);
    transportRow->addWidget(m_nextButton);
    transportRow->addWidget(m_repeatButton);
    layout->addLayout(transportRow);

    // Secondary row: Open File / ReplayGain / theme
    auto* secondaryRow = new QHBoxLayout();
    m_openButton = new QPushButton("Open File...");
    m_replayGainButton = new QPushButton("ReplayGain: On");
    m_themeButton = new QPushButton("Light Mode");
    secondaryRow->addWidget(m_openButton);
    secondaryRow->addWidget(m_replayGainButton);
    secondaryRow->addWidget(m_themeButton);
    layout->addLayout(secondaryRow);

    // Range goes past 100 for a volume "boost" -- values above 1.0 are just
    // a linear gain multiplier to miniaudio. Loud source material can clip
    // past ~150-200%; a proper limiter is a good follow-up.
    layout->addWidget(new QLabel("Volume"));
    m_volumeSlider = new QSlider(Qt::Horizontal);
    m_volumeSlider->setRange(0, 200);
    m_volumeSlider->setValue(100);
    layout->addWidget(m_volumeSlider);

    setControlsEnabled(false);

    connect(m_openButton, &QPushButton::clicked, this, &PlayerWindow::openFile);
    connect(m_playButton, &QPushButton::clicked, this, &PlayerWindow::play);
    connect(m_pauseButton, &QPushButton::clicked, this, &PlayerWindow::pause);
    connect(m_stopButton, &QPushButton::clicked, this, &PlayerWindow::stop);
    connect(m_prevButton, &QPushButton::clicked, this, &PlayerWindow::previous);
    connect(m_nextButton, &QPushButton::clicked, this, [this]() { next(false); });
    connect(m_shuffleButton, &QPushButton::clicked, this, &PlayerWindow::cycleShuffleMode);
    connect(m_repeatButton, &QPushButton::clicked, this, &PlayerWindow::cycleRepeatMode);
    connect(m_replayGainButton, &QPushButton::clicked, this, &PlayerWindow::toggleReplayGain);

    connect(m_seekSlider, &QSlider::sliderPressed, this, [this]() { m_seeking = true; });
    connect(m_seekSlider, &QSlider::sliderReleased, this, &PlayerWindow::seekToSliderValue);

    connect(m_volumeSlider, &QSlider::valueChanged, this, &PlayerWindow::applyVolume);
    connect(m_themeButton, &QPushButton::clicked, this, &PlayerWindow::toggleTheme);

    return bar;
}

QWidget* PlayerWindow::buildNowPlayingPage()
{
    auto* page = new QWidget();
    m_nowPlayingPage = page;
    auto* pageLayout = new QVBoxLayout(page);
    pageLayout->setContentsMargins(24, 16, 24, 16);

    auto* topRow = new QHBoxLayout();
    auto* backButton = new QPushButton("← Back");
    backButton->setFlat(true);
    connect(backButton, &QPushButton::clicked, this, [this]() { m_stack->setCurrentIndex(0); });
    topRow->addWidget(backButton);
    topRow->addStretch();
    pageLayout->addLayout(topRow);

    pageLayout->addStretch(1);

    auto* contentRow = new QHBoxLayout();
    contentRow->addStretch();

    m_bigCoverArtLabel = new QLabel();
    m_bigCoverArtLabel->setFixedSize(280, 280);
    m_bigCoverArtLabel->setScaledContents(false); // pixmap is pre-cropped to exact label size (see ScaledCoverArt)
    m_bigCoverArtLabel->setStyleSheet("background-color: rgba(255,255,255,30); border-radius: 8px;");
    contentRow->addWidget(m_bigCoverArtLabel);

    auto* textColumn = new QVBoxLayout();
    m_bigTitleLabel = new QLabel("No file loaded");
    QFont titleFont = m_bigTitleLabel->font();
    titleFont.setPointSize(titleFont.pointSize() + 10);
    titleFont.setBold(true);
    m_bigTitleLabel->setFont(titleFont);
    m_bigTitleLabel->setWordWrap(true);
    textColumn->addWidget(m_bigTitleLabel);

    m_bigSubtitleLabel = new QLabel();
    textColumn->addWidget(m_bigSubtitleLabel);

    m_bigFormatLabel = new QLabel();
    m_bigFormatLabel->setStyleSheet("color: rgba(255,255,255,150);");
    textColumn->addWidget(m_bigFormatLabel);

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
    textColumn->addWidget(m_lyricsList, 1);

    contentRow->addSpacing(24);
    contentRow->addLayout(textColumn, 1);
    contentRow->addStretch();
    pageLayout->addLayout(contentRow);

    pageLayout->addStretch(1);

    m_bigSeekSlider = new QSlider(Qt::Horizontal);
    m_bigSeekSlider->setRange(0, 0);
    connect(m_bigSeekSlider, &QSlider::sliderPressed, this, [this]() { m_seeking = true; });
    connect(m_bigSeekSlider, &QSlider::sliderReleased, this, [this]() {
        m_seekSlider->setValue(m_bigSeekSlider->value());
        seekToSliderValue();
    });
    pageLayout->addWidget(m_bigSeekSlider);

    m_bigTimeLabel = new QLabel("00:00 / 00:00");
    m_bigTimeLabel->setAlignment(Qt::AlignCenter);
    pageLayout->addWidget(m_bigTimeLabel);

    auto* bigTransportRow = new QHBoxLayout();
    bigTransportRow->addStretch();
    auto* bigShuffle = new QPushButton("Shuffle");
    auto* bigPrev = new QPushButton("Previous");
    auto* bigPlay = new QPushButton("Play");
    auto* bigPause = new QPushButton("Pause");
    auto* bigNext = new QPushButton("Next");
    auto* bigRepeat = new QPushButton("Repeat");
    bigTransportRow->addWidget(bigShuffle);
    bigTransportRow->addWidget(bigPrev);
    bigTransportRow->addWidget(bigPlay);
    bigTransportRow->addWidget(bigPause);
    bigTransportRow->addWidget(bigNext);
    bigTransportRow->addWidget(bigRepeat);
    bigTransportRow->addStretch();
    pageLayout->addLayout(bigTransportRow);

    // These just call the exact same methods the mini player bar's buttons
    // do -- one shared playback state, two sets of buttons.
    connect(bigShuffle, &QPushButton::clicked, this, &PlayerWindow::cycleShuffleMode);
    connect(bigPrev, &QPushButton::clicked, this, &PlayerWindow::previous);
    connect(bigPlay, &QPushButton::clicked, this, &PlayerWindow::play);
    connect(bigPause, &QPushButton::clicked, this, &PlayerWindow::pause);
    connect(bigNext, &QPushButton::clicked, this, [this]() { next(false); });
    connect(bigRepeat, &QPushButton::clicked, this, &PlayerWindow::cycleRepeatMode);

    auto* bottomRow = new QHBoxLayout();
    bottomRow->addStretch();
    auto* fullscreenButton = new QPushButton("Full Screen");
    connect(fullscreenButton, &QPushButton::clicked, this, &PlayerWindow::toggleFullScreen);
    bottomRow->addWidget(fullscreenButton);
    pageLayout->addLayout(bottomRow);

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
    refreshTracksTable();
    refreshAlbumsAndArtists();
    updateStats();
}

void PlayerWindow::removeFolder(const QString& dir)
{
    m_library.removeFolder(dir);
    refreshFoldersList();
    refreshTracksTable();
    refreshAlbumsAndArtists();
    updateStats();
}

void PlayerWindow::refreshLibrary()
{
    m_library.refreshAll();
    refreshTracksTable();
    refreshAlbumsAndArtists();
    refreshPlaylistsList();
    updateStats();
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
    m_tracksTable->setSortingEnabled(false);
    m_tracksTable->setRowCount(0);

    for (const TrackInfo& t : m_library.tracks())
    {
        int row = m_tracksTable->rowCount();
        m_tracksTable->insertRow(row);

        auto* titleItem = new QTableWidgetItem(t.title);
        titleItem->setData(Qt::UserRole, t.path);
        m_tracksTable->setItem(row, 0, titleItem);
        m_tracksTable->setItem(row, 1, new QTableWidgetItem(t.artist));
        m_tracksTable->setItem(row, 2, new QTableWidgetItem(t.album));
        m_tracksTable->setItem(row, 3, new QTableWidgetItem(t.genre));
        m_tracksTable->setItem(row, 4, new QTableWidgetItem(t.year > 0 ? QString::number(t.year) : QString()));
    }

    m_tracksTable->setSortingEnabled(true);
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

void PlayerWindow::updateStats()
{
    MusicLibrary::Stats s = m_library.stats();
    m_statsLabel->setText(QString("Tracks: %1\nAlbums: %2\nArtists: %3\nGenres: %4")
        .arg(s.trackCount).arg(s.albumCount).arg(s.artistCount).arg(s.genreCount));
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
    TrackInfo info = m_library.findTrackInfo(path);

    ManualTagDialog dialog(path, tags, info.coverArt, this);

    // "Fill from internet" reuses the exact same lookup as the "MusicBrainz"
    // menu item -- lookupSelectedTrackOnMusicBrainz() reads whatever row is
    // currently selected, which is still this track's row since selection
    // doesn't change while this modal dialog is open. Setting
    // m_activeManualTagDialog tells handleMusicBrainzReply() to feed its
    // result into this dialog's fields instead of writing straight to disk.
    connect(&dialog, &ManualTagDialog::fillFromInternetRequested, this, [this, &dialog]() {
        m_activeManualTagDialog = &dialog;
        lookupSelectedTrackOnMusicBrainz();
    });

    if (dialog.exec() == QDialog::Accepted)
    {
        m_library.refreshTrack(path);
        refreshTracksTable();
        refreshAlbumsAndArtists();
        updateStats();
    }
    m_activeManualTagDialog = nullptr; // safety net if the dialog closed mid-lookup
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

    if (!WriteCoverArt(path, imageData, mimeType))
    {
        ErrorReporter::warn(this, "Couldn't save cover", "The file's cover art couldn't be written to.");
        return;
    }

    m_library.refreshTrack(path);
    refreshTracksTable();
    refreshAlbumsAndArtists();

    if (!m_queue.isEmpty() && m_queue.currentTrack().path == path)
    {
        updateNowPlayingUi(m_library.findTrackInfo(path)); // repaints mini bar + Now Playing cover with the new art
    }
}

void PlayerWindow::lookupSelectedTrackOnMusicBrainz()
{
    QTableWidgetItem* item = m_tracksTable->currentItem();
    if (!item)
    {
        return;
    }
    int row = item->row();
    QString path = m_tracksTable->item(row, 0)->data(Qt::UserRole).toString();
    QString title = m_tracksTable->item(row, 0)->text();
    QString artist = m_tracksTable->item(row, 1)->text();

    m_pendingLookupPath = path;

    // MusicBrainz's recording search endpoint. Their usage policy asks for
    // an identifying User-Agent on every request; a generic one is used
    // here, but for real/frequent use it should name the app and include a
    // contact (see musicbrainz.org/doc/MusicBrainz_API).
    QString query = title;
    if (!artist.isEmpty())
    {
        query += " AND artist:\"" + artist + "\"";
    }

    QUrl url("https://musicbrainz.org/ws/2/recording/");
    QUrlQuery urlQuery;
    urlQuery.addQueryItem("query", query);
    urlQuery.addQueryItem("fmt", "json");
    urlQuery.addQueryItem("limit", "10");
    url.setQuery(urlQuery);

    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::UserAgentHeader, "AudioForge/1.0 ( no-contact-set )");

    m_musicBrainzButton->setEnabled(false);
    m_musicBrainzButton->setText("Looking up...");

    QNetworkReply* reply = m_network->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() { handleMusicBrainzReply(reply); });
}

void PlayerWindow::handleMusicBrainzReply(QNetworkReply* reply)
{
    reply->deleteLater();
    m_musicBrainzButton->setText("Tag Music");
    m_musicBrainzButton->setEnabled(!m_tracksTable->selectedItems().isEmpty());

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
        m_activeManualTagDialog = nullptr; // consume even on cancel
        return;
    }

    int idx = dialog.selectedIndex();
    if (idx < 0 || idx >= candidates.size())
    {
        m_activeManualTagDialog = nullptr;
        return;
    }

    const MusicBrainzCandidate& chosen = candidates[idx];

    // Triggered via "Fill from internet" inside the manual tag dialog --
    // hand the candidate to that dialog's fields for review instead of
    // writing to disk here; the dialog's own Save button does the write.
    if (m_activeManualTagDialog)
    {
        m_activeManualTagDialog->applyLookupResult(chosen.title, chosen.artist, chosen.album, chosen.year);
        m_activeManualTagDialog = nullptr;
        return;
    }

    if (!WriteBasicTags(m_pendingLookupPath, chosen.title, chosen.artist, chosen.album, chosen.year))
    {
        ErrorReporter::warn(this, "Couldn't save tags", "The file's tags could not be written to.");
        return;
    }

    // Re-read the file so the in-memory library reflects exactly what was
    // actually saved (also picks up cover art / audio properties untouched
    // by this write).
    m_library.refreshTrack(m_pendingLookupPath);
    refreshTracksTable();
    refreshAlbumsAndArtists();
    updateStats();
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
    // User-Agent, same courtesy as the existing MusicBrainz request below.
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::UserAgentHeader, "AudioForge/1.0 ( no-contact-set )");

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
        item->setFont(font);
    }

    if (currentIndex >= 0)
    {
        m_lyricsList->scrollToItem(m_lyricsList->item(currentIndex), QAbstractItemView::PositionAtCenter);
    }
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
        setQueueAndPlay(subset, idx);
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
        setQueueAndPlay(queue, idx);
    }
}

// --- Queue / transport ---------------------------------------------

void PlayerWindow::setQueueAndPlay(const QVector<TrackInfo>& queue, int startIndex)
{
    if (queue.isEmpty())
    {
        return;
    }
    abortCrossfade();
    m_queue.setQueue(queue, startIndex);
    loadTrack(m_queue.currentTrack());
}

void PlayerWindow::next(bool fromAutoAdvance)
{
    if (!fromAutoAdvance)
    {
        abortCrossfade(); // a manual skip always hard-cuts, discarding any in-progress fade
    }

    if (m_queue.isEmpty())
    {
        return;
    }

    if (fromAutoAdvance && m_queue.repeatMode() == RepeatMode::One)
    {
        m_engine.seekToSeconds(0);
        m_engine.play();
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

void PlayerWindow::cycleRepeatMode()
{
    m_queue.cycleRepeatMode();
    switch (m_queue.repeatMode())
    {
        case RepeatMode::Off: m_repeatButton->setText("Repeat: Off"); break;
        case RepeatMode::All: m_repeatButton->setText("Repeat: All"); break;
        case RepeatMode::One: m_repeatButton->setText("Repeat: One"); break;
    }
}

void PlayerWindow::cycleShuffleMode()
{
    m_queue.cycleShuffleMode();
    switch (m_queue.shuffleMode())
    {
        case ShuffleMode::Off: m_shuffleButton->setText("Shuffle: Off"); break;
        case ShuffleMode::Random: m_shuffleButton->setText("Shuffle: Random"); break;
        case ShuffleMode::Smart: m_shuffleButton->setText("Shuffle: Smart"); break;
    }
}

void PlayerWindow::toggleReplayGain()
{
    m_replayGainEnabled = !m_replayGainEnabled;
    m_replayGainButton->setText(m_replayGainEnabled ? "ReplayGain: On" : "ReplayGain: Off");
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
    m_engine.seekToSeconds(target);
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
    m_pauseButton->setEnabled(enabled);
    m_stopButton->setEnabled(enabled);
    m_prevButton->setEnabled(enabled);
    m_nextButton->setEnabled(enabled);
    m_seekSlider->setEnabled(enabled);
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

void PlayerWindow::loadTrack(const TrackInfo& info)
{
    if (m_engine.loadAndPlay(info.path))
    {
        m_wasAtEnd = false;
        m_currentReplayGainDb = info.replayGainDb;
        applyVolume();
        updateNowPlayingUi(info);

        int length = static_cast<int>(m_engine.lengthSeconds());
        m_seekSlider->setRange(0, length);

        setControlsEnabled(true);
    }
    else
    {
        ErrorReporter::warn(this, "Failed to load track", "Could not load: " + QFileInfo(info.path).fileName());
        m_titleLabel->setText("Failed to load: " + QFileInfo(info.path).fileName());
        m_formatLabel->clear();
        m_coverArtLabel->clear();
        setControlsEnabled(false);
    }
}

void PlayerWindow::updateNowPlayingUi(const TrackInfo& info)
{
    m_titleLabel->setText(info.title);
    m_formatLabel->setText(FormatAudioInfo(info));

    m_bigTitleLabel->setText(info.title);
    QString subtitle = info.artist;
    if (!info.album.isEmpty())
    {
        subtitle += (subtitle.isEmpty() ? "" : "  •  ") + info.album;
    }
    m_bigSubtitleLabel->setText(subtitle);
    m_bigFormatLabel->setText(FormatAudioInfo(info));

    if (!info.coverArt.isEmpty())
    {
        QPixmap miniCover = ScaledCoverArt(info.coverArt, m_coverArtLabel->size());
        QPixmap bigCover = ScaledCoverArt(info.coverArt, m_bigCoverArtLabel->size());
        if (!miniCover.isNull() && !bigCover.isNull())
        {
            m_coverArtLabel->setPixmap(miniCover);
            m_bigCoverArtLabel->setPixmap(bigCover);
        }
        else
        {
            m_coverArtLabel->clear();
            m_bigCoverArtLabel->clear();
        }
    }
    else
    {
        m_coverArtLabel->clear();
        m_bigCoverArtLabel->clear();
    }

    // Tint the Now Playing page's background to roughly match the cover
    // art's dominant color, the way several music apps do.
    QColor bg = AverageColor(info.coverArt);
    m_nowPlayingPage->setStyleSheet(QString("background-color: %1;").arg(bg.name()));

    fetchLyricsFor(info);
}

void PlayerWindow::play()
{
    m_engine.play();
}

void PlayerWindow::pause()
{
    m_engine.pause();
}

void PlayerWindow::stop()
{
    m_queue.cancelPendingNext();
    m_engine.stop();
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
    m_darkMode = !m_darkMode;
    qApp->setPalette(m_darkMode ? DarkPalette() : LightPalette());
    m_themeButton->setText(m_darkMode ? "Light Mode" : "Dark Mode");
}

void PlayerWindow::seekToSliderValue()
{
    m_engine.seekToSeconds(static_cast<float>(m_seekSlider->value()));
    m_seeking = false;
}

// --- Crossfade ---------------------------------------------------------

void PlayerWindow::abortCrossfade()
{
    m_engine.abortCrossfade();
    m_queue.cancelPendingNext();
}

void PlayerWindow::tryStartCrossfade()
{
    if (m_queue.repeatMode() == RepeatMode::One)
    {
        return; // restarting the same track doesn't need a fade
    }

    if (!m_queue.peekNext())
    {
        return; // end of queue, nothing to fade into
    }

    const TrackInfo& nextInfo = m_queue.pendingNextTrack();
    if (!m_engine.startCrossfadeTo(nextInfo.path))
    {
        m_queue.cancelPendingNext();
        ErrorReporter::logOnly("Crossfade preload failed for: " + nextInfo.path);
    }
}

void PlayerWindow::updateCrossfadeProgress(float cursor, float length)
{
    float remaining = length - cursor;
    float progress = 1.0f - qBound(0.0f, remaining / static_cast<float>(m_crossfadeSeconds), 1.0f);

    float activeBase = baseVolumeFor(m_currentReplayGainDb);
    float nextBase = baseVolumeFor(m_queue.pendingNextTrack().replayGainDb);
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
    return linear;
}

void PlayerWindow::finalizeCrossfade()
{
    m_engine.finalizeCrossfade();
    m_queue.commitPendingNext();

    const TrackInfo& info = m_queue.currentTrack();
    m_currentReplayGainDb = info.replayGainDb;
    applyVolume();
    updateNowPlayingUi(info);

    int length = static_cast<int>(m_engine.lengthSeconds());
    m_seekSlider->setRange(0, length);

    m_wasAtEnd = false;
}

// --- Main update tick ----------------------------------------------

void PlayerWindow::updatePlayback()
{
    if (!m_engine.isLoaded() || m_seeking)
    {
        return;
    }

    float cursor = m_engine.cursorSeconds();
    float length = m_engine.lengthSeconds();

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

    QString timeText = QString("%1:%2 / %3:%4")
        .arg(static_cast<int>(cursor) / 60, 2, 10, QChar('0'))
        .arg(static_cast<int>(cursor) % 60, 2, 10, QChar('0'))
        .arg(static_cast<int>(length) / 60, 2, 10, QChar('0'))
        .arg(static_cast<int>(length) % 60, 2, 10, QChar('0'));
    m_timeLabel->setText(timeText);
    m_bigTimeLabel->setText(timeText);

    if (m_engine.isCrossfading())
    {
        updateCrossfadeProgress(cursor, length);
        return;
    }

    if (m_crossfadeEnabled && length > 0.0f && (length - cursor) <= static_cast<float>(m_crossfadeSeconds))
    {
        tryStartCrossfade();
        if (m_engine.isCrossfading())
        {
            return;
        }
    }

    // Auto-advance when the track finishes on its own (crossfade-off path,
    // or crossfade couldn't start e.g. end of queue). Edge-triggered so it
    // doesn't keep calling next() every tick once the queue is exhausted.
    bool atEnd = m_engine.isAtEnd();
    if (atEnd && !m_wasAtEnd)
    {
        next(true);
    }
    m_wasAtEnd = atEnd;
}

// --- Settings persistence (%APPDATA%\AudioForge\AudioForge.ini) -----

void PlayerWindow::loadSettings()
{
    QSettings settings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge");

    m_darkMode = settings.value("darkMode", true).toBool();
    qApp->setPalette(m_darkMode ? DarkPalette() : LightPalette());
    m_themeButton->setText(m_darkMode ? "Light Mode" : "Dark Mode");

    int volume = settings.value("volume", 100).toInt();
    m_volumeSlider->setValue(volume);

    m_replayGainEnabled = settings.value("replayGainEnabled", true).toBool();
    m_replayGainButton->setText(m_replayGainEnabled ? "ReplayGain: On" : "ReplayGain: Off");

    m_crossfadeEnabled = settings.value("crossfadeEnabled", false).toBool();
    m_crossfadeCheckbox->setChecked(m_crossfadeEnabled);

    m_crossfadeSeconds = settings.value("crossfadeSeconds", 5).toInt();
    m_crossfadeSlider->setValue(m_crossfadeSeconds);

    m_musixmatchApiKey = settings.value("musixmatchApiKey").toString();
    m_musixmatchApiKeyEdit->setText(m_musixmatchApiKey);

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

    QStringList folders = settings.value("musicFolders").toStringList();
    for (const QString& dir : folders)
    {
        if (QDir(dir).exists())
        {
            m_library.addFolder(dir);
        }
        else
        {
            ErrorReporter::logOnly("Saved music folder no longer exists, skipped: " + dir);
        }
    }
    refreshFoldersList();
    refreshTracksTable();
    refreshAlbumsAndArtists();
    updateStats();
}

void PlayerWindow::saveSettings()
{
    QSettings settings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge");
    settings.setValue("darkMode", m_darkMode);
    settings.setValue("volume", m_volumeSlider->value());
    settings.setValue("replayGainEnabled", m_replayGainEnabled);
    settings.setValue("crossfadeEnabled", m_crossfadeEnabled);
    settings.setValue("crossfadeSeconds", m_crossfadeSeconds);
    settings.setValue("musixmatchApiKey", m_musixmatchApiKey);

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
}

} // namespace audioforge