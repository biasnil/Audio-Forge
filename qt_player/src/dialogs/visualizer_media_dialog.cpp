#include "audioforge/dialogs/visualizer_media_dialog.hpp"
#include "audioforge/dialogs/wallpaper_track_picker_dialog.hpp"
#include "audioforge/translations.hpp"
#include "audioforge/warnings.hpp"
#include "audioforge/widgets/visualizer_widget.hpp"

#include <QButtonGroup>
#include <QCheckBox>
#include <QDialogButtonBox>
#include <QDir>
#include <QFileDialog>
#include <QFileInfo>
#include <QFrame>
#include <QHBoxLayout>
#include <QLabel>
#include <QPushButton>
#include <QScrollArea>
#include <QVBoxLayout>

namespace audioforge {

namespace {

constexpr int kPreviewSize = 76;

QLabel* Heading(const QString& text)
{
    auto* label = new QLabel(text);
    label->setObjectName("SettingsSectionTitle");
    return label;
}

QFrame* Card()
{
    auto* card = new QFrame();
    card->setObjectName("SettingsCard");
    return card;
}

// Everything in a box, removed (for refresh()). Deleted later: the button
// that asked for the refresh may be inside one of these cards.
void ClearBox(QVBoxLayout* box)
{
    while (QLayoutItem* item = box->takeAt(0))
    {
        if (QWidget* widget = item->widget())
        {
            widget->hide();
            widget->deleteLater();
        }
        delete item;
    }
}

} // namespace

VisualizerMediaDialog::VisualizerMediaDialog(VisualizerMediaLibrary& library, const QVector<TrackInfo>& allTracks,
                                             QWidget* parent)
    : QDialog(parent), m_library(library), m_allTracks(allTracks)
{
    setWindowTitle(tr("Custom GIFs and Videos"));
    resize(600, 620);
    auto* layout = new QVBoxLayout(this);

    auto* explanation = new QLabel(tr("While the visualizer is on, a song's own GIF or looping video shows instead of "
                                      "the style. Songs without one use the one for every song, if set."));
    explanation->setObjectName("SettingsFooter");
    explanation->setWordWrap(true);
    layout->addWidget(explanation);

    auto* scroll = new QScrollArea();
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    auto* content = new QWidget();
    auto* contentLayout = new QVBoxLayout(content);
    contentLayout->setContentsMargins(0, 0, 8, 0);

    contentLayout->addWidget(Heading(tr("For Every Song")));
    m_globalBox = new QVBoxLayout();
    contentLayout->addLayout(m_globalBox);

    contentLayout->addWidget(Heading(tr("For Chosen Songs")));
    m_entriesBox = new QVBoxLayout();
    m_entriesBox->setSpacing(8);
    contentLayout->addLayout(m_entriesBox);
    auto* addRow = new QHBoxLayout();
    auto* add = new QPushButton(tr("Add GIF or Video..."));
    add->setObjectName("PrimaryButton");
    connect(add, &QPushButton::clicked, this, &VisualizerMediaDialog::addEntry);
    addRow->addWidget(add);
    addRow->addStretch();
    contentLayout->addSpacing(4);
    contentLayout->addLayout(addRow);
    contentLayout->addStretch();

    scroll->setWidget(content);
    layout->addWidget(scroll, 1);

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Close);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::accept);
    layout->addWidget(buttons);

    refresh();
}

void VisualizerMediaDialog::refresh()
{
    ClearBox(m_globalBox);
    if (const VisualizerMediaEntry* global = m_library.globalEntry())
    {
        m_globalBox->addWidget(makeCard(*global));
    }
    else
    {
        QFrame* card = Card();
        auto* row = new QHBoxLayout(card);
        row->setContentsMargins(16, 12, 12, 12);
        auto* text = new QLabel(tr("None: songs without their own show the visualizer style."));
        text->setObjectName("MutedLabel");
        text->setWordWrap(true);
        row->addWidget(text, 1);
        auto* choose = new QPushButton(tr("Choose..."));
        connect(choose, &QPushButton::clicked, this, &VisualizerMediaDialog::chooseGlobal);
        row->addWidget(choose);
        m_globalBox->addWidget(card);
    }

    ClearBox(m_entriesBox);
    bool any = false;
    for (const VisualizerMediaEntry& e : m_library.entries())
    {
        if (!e.global)
        {
            m_entriesBox->addWidget(makeCard(e));
            any = true;
        }
    }
    if (!any)
    {
        auto* empty = new QLabel(tr("No GIFs or videos for chosen songs yet. Add one, then pick the songs it shows for."));
        empty->setObjectName("MutedLabel");
        empty->setWordWrap(true);
        empty->setContentsMargins(6, 4, 6, 4);
        m_entriesBox->addWidget(empty);
    }
}

QFrame* VisualizerMediaDialog::makeCard(const VisualizerMediaEntry& entry)
{
    const QString id = entry.id;
    QFrame* card = Card();
    auto* row = new QHBoxLayout(card);
    row->setContentsMargins(12, 12, 12, 12);
    row->setSpacing(14);

    // Moving, exactly as Now Playing will show it.
    auto* preview = new VisualizerWidget(nullptr, card);
    preview->setFixedSize(kPreviewSize, kPreviewSize);
    preview->setMedia(&entry);
    preview->setPlaying(true);
    preview->setActive(true);
    row->addWidget(preview, 0, Qt::AlignTop);

    auto* column = new QVBoxLayout();
    column->setSpacing(6);
    const QFileInfo file(entry.path);
    auto* name = new QLabel(file.fileName());
    name->setStyleSheet("font-weight: 600;");
    name->setToolTip(QDir::toNativeSeparators(entry.path));
    name->setTextInteractionFlags(Qt::NoTextInteraction);
    column->addWidget(name);

    QString subtitle;
    if (!file.exists())
    {
        subtitle = tr("File not found");
    }
    else if (entry.global)
    {
        subtitle = tr("Every song without its own");
    }
    else
    {
        subtitle = entry.trackPaths.size() == 1 ? tr("1 song") : tr("%1 songs").arg(entry.trackPaths.size());
    }
    auto* sub = new QLabel(subtitle);
    sub->setObjectName("SettingsSubtitle");
    column->addWidget(sub);

    // Round / Square and Spin: apply at once, the preview follows.
    auto* options = new QHBoxLayout();
    options->setSpacing(6);
    auto* round = new QPushButton(tr("Round"));
    auto* square = new QPushButton(tr("Square"));
    auto* shape = new QButtonGroup(card);
    for (QPushButton* button : {round, square})
    {
        button->setObjectName("PillToggle");
        button->setCheckable(true);
        button->setCursor(Qt::PointingHandCursor);
        shape->addButton(button);
        options->addWidget(button);
    }
    (entry.round ? round : square)->setChecked(true);
    auto* spin = new QCheckBox(tr("Spin while playing"));
    spin->setChecked(entry.spin);
    options->addSpacing(10);
    options->addWidget(spin);
    options->addStretch();
    column->addLayout(options);

    auto apply = [this, id, round, spin, preview]() {
        if (VisualizerMediaEntry* e = entryById(id))
        {
            e->round = round->isChecked();
            e->spin = spin->isChecked();
            preview->setMedia(e); // same file: only the shape and spin change
        }
    };
    connect(round, &QPushButton::toggled, this, apply);
    connect(spin, &QCheckBox::toggled, this, apply);

    auto* buttons = new QHBoxLayout();
    buttons->addStretch();
    if (entry.global)
    {
        auto* change = new QPushButton(tr("Change..."));
        connect(change, &QPushButton::clicked, this, &VisualizerMediaDialog::chooseGlobal);
        buttons->addWidget(change);
        auto* clear = new QPushButton(tr("Clear"));
        connect(clear, &QPushButton::clicked, this, [this]() {
            m_library.clearGlobal();
            refresh();
        });
        buttons->addWidget(clear);
    }
    else
    {
        auto* edit = new QPushButton(tr("Edit Songs..."));
        connect(edit, &QPushButton::clicked, this, [this, id]() { editSongs(id); });
        buttons->addWidget(edit);
        auto* remove = new QPushButton(tr("Remove"));
        connect(remove, &QPushButton::clicked, this, [this, id]() { removeEntry(id); });
        buttons->addWidget(remove);
    }
    column->addLayout(buttons);
    row->addLayout(column, 1);
    return card;
}

VisualizerMediaEntry* VisualizerMediaDialog::entryById(const QString& id)
{
    for (VisualizerMediaEntry& e : m_library.entries())
    {
        if (e.id == id)
        {
            return &e;
        }
    }
    return nullptr;
}

QString VisualizerMediaDialog::chooseFile()
{
    return QFileDialog::getOpenFileName(this, tr("Choose a GIF or Video"), QString(),
        tr("GIFs and videos") + " (*.gif *.webp *.png *.jpg *.jpeg *.mp4 *.m4v *.mov *.webm *.mkv *.avi *.wmv)");
}

void VisualizerMediaDialog::chooseGlobal()
{
    const QString path = chooseFile();
    if (!path.isEmpty())
    {
        m_library.setGlobalPath(path);
        refresh();
    }
}

void VisualizerMediaDialog::addEntry()
{
    const QString path = chooseFile();
    if (path.isEmpty())
    {
        return;
    }
    WallpaperTrackPickerDialog picker(m_allTracks, QStringList(), this, tr("Choose songs for this GIF or video"));
    if (picker.exec() != QDialog::Accepted)
    {
        return;
    }
    m_library.addEntry(path, picker.resultTrackPaths());
    refresh();
}

void VisualizerMediaDialog::editSongs(const QString& id)
{
    VisualizerMediaEntry* entry = entryById(id);
    if (!entry)
    {
        return;
    }
    WallpaperTrackPickerDialog picker(m_allTracks, entry->trackPaths, this, tr("Choose songs for this GIF or video"));
    if (picker.exec() == QDialog::Accepted)
    {
        // Looked up again: the picker ran an event loop.
        if (VisualizerMediaEntry* e = entryById(id))
        {
            e->trackPaths = picker.resultTrackPaths();
        }
        refresh();
    }
}

void VisualizerMediaDialog::removeEntry(const QString& id)
{
    if (entryById(id) && ErrorReporter::confirm(this, tr("Remove"), tr("Remove this GIF or video from the visualizer?")))
    {
        m_library.removeEntry(id);
        refresh();
    }
}

} // namespace audioforge
