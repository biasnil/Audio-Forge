#include "audioforge/dialogs/duplicates_dialog.hpp"
#include "audioforge/app_files.hpp"
#include "audioforge/theme.hpp"
#include "audioforge/track_metadata.hpp"
#include "audioforge/warnings.hpp"

#include <QCheckBox>
#include <QDir>
#include <QEvent>
#include <QFile>
#include <QFileInfo>
#include <QFrame>
#include <QHBoxLayout>
#include <QLabel>
#include <QLocale>
#include <QPushButton>
#include <QScrollArea>
#include <QVBoxLayout>

namespace audioforge {

namespace {

constexpr int kCoverSize = 44;

QLabel* Muted(const QString& text, const QString& objectName = "SettingsSubtitle")
{
    auto* label = new QLabel(text);
    label->setObjectName(objectName);
    return label;
}

QPushButton* SmallIconButton(const QIcon& icon, const QString& toolTip)
{
    auto* button = new QPushButton();
    button->setObjectName("IconButton");
    button->setIcon(icon);
    button->setIconSize(QSize(18, 18));
    button->setFixedSize(32, 32);
    button->setToolTip(toolTip);
    button->setCursor(Qt::PointingHandCursor);
    return button;
}

// A small rounded label: "Best quality", "Similar names".
QLabel* Badge(const QString& text, const QColor& background, const QColor& foreground)
{
    auto* label = new QLabel(text);
    label->setStyleSheet(QString("QLabel { background: %1; color: %2; border-radius: 9px; padding: 2px 9px; "
                                 "font-size: 11px; font-weight: 600; }")
                             .arg(background.name(QColor::HexArgb), foreground.name()));
    label->setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    return label;
}

QPixmap CoverOf(const QString& path, const QString& seed)
{
    const QByteArray bytes = ReadCoverArt(path);
    QPixmap cover;
    if (!bytes.isEmpty() && cover.loadFromData(bytes))
    {
        constexpr qreal kDpr = 2.0;
        const int px = qRound(kCoverSize * kDpr);
        cover = cover.scaled(px, px, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation)
                    .copy(0, 0, px, px);
        cover = theme::roundedPixmap(cover, 6 * kDpr);
        cover.setDevicePixelRatio(kDpr);
        return cover;
    }
    return theme::generatedArt(seed, kCoverSize, 6);
}

// Everything in a box, removed. Deleted later: the button that asked for the
// rebuild may be inside one of these cards.
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

DuplicatesDialog::DuplicatesDialog(const QVector<TrackInfo>& tracks, PlayFn play, DeletedFn deleted, QWidget* parent)
    : QDialog(parent), m_tracks(tracks), m_play(std::move(play)), m_deleted(std::move(deleted))
{
    setWindowTitle(tr("Duplicates"));
    resize(860, 640);
    m_dark = palette().color(QPalette::Window).lightness() < 128;

    auto* layout = new QVBoxLayout(this);
    m_summary = new QLabel();
    m_summary->setObjectName("MutedLabel");
    m_summary->setWordWrap(true);
    layout->addWidget(m_summary);

    auto* scroll = new QScrollArea();
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    auto* content = new QWidget();
    auto* contentLayout = new QVBoxLayout(content);
    contentLayout->setContentsMargins(0, 0, 8, 0);
    m_cards = new QVBoxLayout();
    m_cards->setSpacing(10);
    contentLayout->addLayout(m_cards);
    contentLayout->addStretch();
    scroll->setWidget(content);
    layout->addWidget(scroll, 1);

    // "Marked as not duplicates. Undo"
    m_undoBar = new QWidget();
    auto* undoLayout = new QHBoxLayout(m_undoBar);
    undoLayout->setContentsMargins(0, 0, 0, 0);
    undoLayout->addWidget(new QLabel(tr("Marked as not duplicates.")));
    auto* undo = new QPushButton(tr("Undo"));
    undo->setObjectName("LinkButton");
    undo->setCursor(Qt::PointingHandCursor);
    connect(undo, &QPushButton::clicked, this, [this]() { setDismissed(m_beforeLastDismiss, false); });
    undoLayout->addWidget(undo);
    undoLayout->addStretch();
    m_undoBar->hide();
    layout->addWidget(m_undoBar);

    auto* buttons = new QHBoxLayout();
    m_trashButton = new QPushButton();
    m_trashButton->setObjectName("PrimaryButton");
    connect(m_trashButton, &QPushButton::clicked, this, [this]() { trash(checkedPaths()); });
    buttons->addWidget(m_trashButton);
    buttons->addStretch();
    m_showDismissed = new QCheckBox();
    connect(m_showDismissed, &QCheckBox::toggled, this, &DuplicatesDialog::rebuild);
    buttons->addWidget(m_showDismissed);
    auto* closeButton = new QPushButton(tr("Close"));
    connect(closeButton, &QPushButton::clicked, this, &QDialog::accept);
    buttons->addWidget(closeButton);
    layout->addLayout(buttons);

    m_dismissed = LoadDuplicateDismissals(m_tracks);
    refind();
}

void DuplicatesDialog::refind()
{
    m_result = FindDuplicates(m_tracks, m_dismissed);
    rebuild();
}

void DuplicatesDialog::rebuild()
{
    ClearBox(m_cards);
    m_ticks.clear();
    m_copyRows.clear();

    // One section: "Same song (N)", "Similar names (N)" or "Dismissed (N)", then its cards.
    auto addSection = [this](const QString& title, const QVector<DuplicateGroup>& groups, bool similar, bool dismissed) {
        int count = 0;
        for (const DuplicateGroup& g : groups)
        {
            count += dismissed || g.similar == similar ? 1 : 0;
        }
        if (count == 0)
        {
            return;
        }
        auto* heading = new QLabel(title.arg(count));
        heading->setObjectName("SectionHeading");
        m_cards->addWidget(heading);
        for (int g = 0; g < groups.size(); ++g)
        {
            if (dismissed || groups[g].similar == similar)
            {
                m_cards->addWidget(makeCard(groups[g], g, dismissed));
            }
        }
    };
    addSection(tr("Same song (%1)"), m_result.groups, false, false);
    addSection(tr("Similar names (%1)"), m_result.groups, true, false);
    if (m_showDismissed->isChecked())
    {
        addSection(tr("Dismissed (%1)"), m_result.dismissed, false, true);
    }
    if (m_result.groups.isEmpty() && !m_showDismissed->isChecked())
    {
        m_cards->addWidget(makeEmptyState());
    }

    int copies = 0;
    for (const DuplicateGroup& g : m_result.groups)
    {
        copies += g.tracks.size();
    }
    m_summary->setText(tr("%1 songs are stored more than once (%2 files). Double-click a copy to play it; tick copies to move "
                          "several to the Recycle Bin at once.").arg(m_result.groups.size()).arg(copies));
    m_summary->setVisible(!m_result.groups.isEmpty());
    m_showDismissed->setText(tr("Show dismissed (%1)").arg(m_result.dismissed.size()));
    m_showDismissed->setVisible(!m_result.dismissed.isEmpty() || m_showDismissed->isChecked());
    updateButtons();
}

QWidget* DuplicatesDialog::makeEmptyState()
{
    const theme::Tokens& t = theme::tokens(m_dark);
    auto* box = new QWidget();
    auto* l = new QVBoxLayout(box);
    l->setContentsMargins(0, 80, 0, 0);
    l->setSpacing(8);
    auto* icon = new QLabel();
    icon->setPixmap(theme::icon(theme::Icon::CheckCircle, t.accent, QColor(), QColor(), 56).pixmap(56, 56));
    icon->setAlignment(Qt::AlignCenter);
    l->addWidget(icon);
    auto* title = new QLabel(tr("No duplicates"));
    title->setObjectName("PanelTitle");
    title->setAlignment(Qt::AlignCenter);
    l->addWidget(title);
    auto* subtitle = Muted(tr("Every song in your library is stored once."), "MutedLabel");
    subtitle->setAlignment(Qt::AlignCenter);
    l->addWidget(subtitle);
    return box;
}

QFrame* DuplicatesDialog::makeCard(const DuplicateGroup& group, int index, bool dismissed)
{
    const theme::Tokens& t = theme::tokens(m_dark);
    auto* card = new QFrame();
    card->setObjectName("SettingsCard");
    auto* l = new QVBoxLayout(card);
    l->setContentsMargins(16, 12, 12, 10);
    l->setSpacing(4);

    // "Yellow — Coldplay   [Similar names]                [Not Duplicates]"
    auto* top = new QHBoxLayout();
    const TrackInfo& first = group.tracks.first();
    auto* title = new QLabel(first.artist.isEmpty() ? first.title : first.title + QStringLiteral(" — ") + first.artist);
    title->setStyleSheet("font-size: 14px; font-weight: 600;");
    title->setTextFormat(Qt::PlainText);
    top->addWidget(title, 1);
    auto* groupButton = new QPushButton(dismissed ? tr("Restore") : tr("Not Duplicates"));
    groupButton->setToolTip(dismissed ? tr("Show this group as duplicates again.")
                                      : tr("These songs aren't copies of each other: don't show them as duplicates again."));
    connect(groupButton, &QPushButton::clicked, this, [this, index, dismissed]() {
        dismissed ? restoreDismissed(index) : markNotDuplicates(index);
    });
    top->addWidget(groupButton);
    l->addLayout(top);
    l->addSpacing(4);

    for (int i = 0; i < group.tracks.size(); ++i)
    {
        const TrackInfo& track = group.tracks[i];
        const QFileInfo file(track.path);
        auto* row = new QHBoxLayout();
        row->setSpacing(10);
        if (!dismissed)
        {
            auto* tick = new QCheckBox();
            tick->setToolTip(tr("Tick copies to move several to the Recycle Bin at once."));
            connect(tick, &QCheckBox::toggled, this, &DuplicatesDialog::updateButtons);
            m_ticks.push_back({tick, track.path, index});
            row->addWidget(tick);
        }
        auto* cover = new QLabel();
        cover->setPixmap(CoverOf(track.path, track.title));
        cover->setFixedSize(kCoverSize, kCoverSize);
        row->addWidget(cover);

        // The folder (what tells copies apart), the file name under it.
        auto* where = new QVBoxLayout();
        where->setSpacing(0);
        auto* folder = new QLabel(QDir::toNativeSeparators(file.absolutePath()));
        folder->setToolTip(QDir::toNativeSeparators(track.path));
        folder->setMinimumWidth(80);
        folder->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
        where->addWidget(folder);
        auto* name = Muted(file.fileName());
        name->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
        where->addWidget(name);
        row->addLayout(where, 1);

        QStringList details;
        details << FormatDuration(static_cast<int>(track.durationSeconds + 0.5));
        if (track.bitrateKbps > 0)
        {
            details << tr("%1 kbps").arg(track.bitrateKbps);
        }
        if (file.exists())
        {
            details << QLocale().formattedDataSize(file.size(), 1);
        }
        row->addWidget(Muted(details.join(QStringLiteral("  ·  ")), "MutedLabel"));
        if (track.path == group.best)
        {
            row->addWidget(Badge(tr("Best quality"), t.accent, t.accentText));
        }

        auto* play = SmallIconButton(theme::icon(theme::Icon::Play, t.muted), tr("Play"));
        connect(play, &QPushButton::clicked, this, [this, group, i]() {
            if (m_play)
            {
                m_play(group.tracks, i);
            }
        });
        row->addWidget(play);
        auto* show = SmallIconButton(theme::icon(theme::Icon::Folder, t.muted), tr("Show in Folder"));
        const QString path = track.path;
        connect(show, &QPushButton::clicked, this, [path]() { ShowInFileManager(path); });
        row->addWidget(show);
        if (!dismissed)
        {
            auto* bin = SmallIconButton(theme::icon(theme::Icon::Trash, t.muted), tr("Move to Recycle Bin..."));
            connect(bin, &QPushButton::clicked, this, [this, path]() { trash({path}); });
            row->addWidget(bin);
        }

        // Double-click a copy to play it, as before.
        auto* rowWidget = new QWidget();
        rowWidget->setLayout(row);
        row->setContentsMargins(0, 4, 0, 4);
        rowWidget->setProperty("copyIndex", i);
        rowWidget->installEventFilter(this);
        rowWidget->setToolTip(tr("Double-click to play this copy."));
        l->addWidget(rowWidget);
        if (i + 1 < group.tracks.size())
        {
            auto* divider = new QFrame();
            divider->setObjectName("SettingsDivider");
            l->addWidget(divider);
        }
        m_copyRows.insert(rowWidget, {index, i, dismissed});
    }
    return card;
}

bool DuplicatesDialog::eventFilter(QObject* watched, QEvent* event)
{
    if (event->type() == QEvent::MouseButtonDblClick && m_play)
    {
        if (auto it = m_copyRows.constFind(watched); it != m_copyRows.constEnd())
        {
            const auto& groups = it->dismissed ? m_result.dismissed : m_result.groups;
            if (it->group >= 0 && it->group < groups.size())
            {
                m_play(groups[it->group].tracks, it->copy);
                return true;
            }
        }
    }
    return QDialog::eventFilter(watched, event);
}

QStringList DuplicatesDialog::checkedPaths() const
{
    QStringList paths;
    for (const Tick& tick : m_ticks)
    {
        if (tick.box->isChecked())
        {
            paths << tick.path;
        }
    }
    return paths;
}

void DuplicatesDialog::updateButtons()
{
    const int checked = checkedPaths().size();
    m_trashButton->setVisible(!m_result.groups.isEmpty());
    m_trashButton->setEnabled(checked > 0);
    m_trashButton->setText(checked == 0   ? tr("Tick copies to remove")
                           : checked == 1 ? tr("Move to Recycle Bin...")
                                          : tr("Move %1 to Recycle Bin...").arg(checked));
}

void DuplicatesDialog::trash(const QStringList& paths)
{
    if (paths.isEmpty())
    {
        return;
    }
    const QString question = paths.size() == 1
        ? tr("Move this copy to the Recycle Bin?") + "\n\n" + QDir::toNativeSeparators(paths.first())
        : tr("Move these %1 copies to the Recycle Bin?").arg(paths.size());
    if (!ErrorReporter::confirm(this, tr("Move to Recycle Bin"), question))
    {
        return;
    }
    QStringList failed;
    for (const QString& path : paths)
    {
        if (!QFile::moveToTrash(path))
        {
            failed << QDir::toNativeSeparators(path);
            continue;
        }
        m_tracks.removeIf([&path](const TrackInfo& t) { return t.path == path; });
        if (m_deleted)
        {
            m_deleted(path);
        }
    }
    if (!failed.isEmpty())
    {
        ErrorReporter::warn(this, tr("Couldn't move the file"),
            tr("The file couldn't be moved to the Recycle Bin. If it's the song that's playing, "
               "play something else first and try again.") + "\n\n" + failed.join('\n'));
    }
    refind(); // a group left with one copy isn't a duplicate any more
}

void DuplicatesDialog::markNotDuplicates(int groupIndex)
{
    if (groupIndex < 0 || groupIndex >= m_result.groups.size())
    {
        return;
    }
    QStringList members;
    for (const TrackInfo& t : m_result.groups[groupIndex].tracks)
    {
        members << t.path;
    }
    QStringList ticked;
    for (const Tick& tick : m_ticks)
    {
        if (tick.group == groupIndex && tick.box->isChecked())
        {
            ticked << tick.path;
        }
    }
    // Ticked copies: just those aren't copies of the rest. Nothing (or everything) ticked: the whole group.
    const QSet<QString> pairs = ticked.isEmpty() || ticked.size() == members.size()
                                    ? PairsAmong(members)
                                    : PairsForSelection(ticked, members);
    setDismissed(m_dismissed | pairs, true);
}

void DuplicatesDialog::restoreDismissed(int groupIndex)
{
    if (groupIndex < 0 || groupIndex >= m_result.dismissed.size())
    {
        return;
    }
    QStringList members;
    for (const TrackInfo& t : m_result.dismissed[groupIndex].tracks)
    {
        members << t.path;
    }
    setDismissed(m_dismissed - PairsAmong(members), false);
}

void DuplicatesDialog::setDismissed(const QSet<QString>& pairs, bool offerUndo)
{
    if (offerUndo)
    {
        m_beforeLastDismiss = m_dismissed;
    }
    m_dismissed = pairs;
    SaveDuplicateDismissals(m_dismissed);
    m_undoBar->setVisible(offerUndo);
    refind();
}

} // namespace audioforge
