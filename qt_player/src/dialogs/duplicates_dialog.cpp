#include "audioforge/dialogs/duplicates_dialog.hpp"
#include "audioforge/warnings.hpp"
#include "audioforge/app_files.hpp"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QLabel>
#include <QMap>
#include <QPushButton>
#include <QRegularExpression>
#include <QTreeWidget>
#include <QVBoxLayout>

#include <algorithm>

namespace audioforge {

namespace {

QString Normalized(const QString& text)
{
    static const QRegularExpression bracketed(QStringLiteral(u"\\s*[(\\[（【][^)\\]）】]*[)\\]）】]"));
    QString stripped = text.toLower();
    stripped.remove(bracketed);
    QString result;
    for (const QChar c : stripped)
    {
        if (c.isLetterOrNumber())
        {
            result += c;
        }
    }
    return result;
}


constexpr int kPathRole = Qt::UserRole;
constexpr int kGroupRole = Qt::UserRole + 1;
constexpr int kIndexRole = Qt::UserRole + 2;

} // namespace

QVector<QVector<TrackInfo>> FindDuplicateGroups(const QVector<TrackInfo>& tracks)
{
    QMap<QString, QVector<TrackInfo>> byName;
    for (const TrackInfo& t : tracks)
    {
        byName[Normalized(t.title) + "|" + Normalized(t.artist)] << t;
    }

    QVector<QVector<TrackInfo>> result;
    for (auto it = byName.begin(); it != byName.end(); ++it)
    {
        QVector<TrackInfo>& candidates = it.value();
        if (candidates.size() < 2)
        {
            continue;
        }
        // Split by length, so a live version or a remix isn't called a duplicate.
        std::sort(candidates.begin(), candidates.end(),
                  [](const TrackInfo& a, const TrackInfo& b) { return a.durationSeconds < b.durationSeconds; });
        QVector<TrackInfo> cluster;
        for (const TrackInfo& t : candidates)
        {
            if (!cluster.isEmpty() && t.durationSeconds - cluster.last().durationSeconds > 2.0)
            {
                if (cluster.size() > 1)
                {
                    result << cluster;
                }
                cluster.clear();
            }
            cluster << t;
        }
        if (cluster.size() > 1)
        {
            result << cluster;
        }
    }
    std::sort(result.begin(), result.end(), [](const QVector<TrackInfo>& a, const QVector<TrackInfo>& b) {
        return QString::localeAwareCompare(a.first().title, b.first().title) < 0;
    });
    return result;
}

DuplicatesDialog::DuplicatesDialog(const QVector<TrackInfo>& tracks, PlayFn play, DeletedFn deleted, QWidget* parent)
    : QDialog(parent), m_groups(FindDuplicateGroups(tracks)), m_play(std::move(play)), m_deleted(std::move(deleted))
{
    setWindowTitle("Duplicates");
    resize(820, 520);

    auto* layout = new QVBoxLayout(this);
    m_summary = new QLabel();
    m_summary->setObjectName("MutedLabel");
    m_summary->setWordWrap(true);
    layout->addWidget(m_summary);

    m_tree = new QTreeWidget();
    m_tree->setColumnCount(4);
    m_tree->setHeaderLabels({"Song", "Length", "Bitrate", "Location"});
    m_tree->header()->setSectionResizeMode(0, QHeaderView::ResizeToContents);
    m_tree->header()->setSectionResizeMode(3, QHeaderView::Stretch);
    m_tree->setRootIsDecorated(false);
    m_tree->setUniformRowHeights(true);
    m_tree->setTextElideMode(Qt::ElideMiddle);
    connect(m_tree, &QTreeWidget::itemDoubleClicked, this, [this](QTreeWidgetItem* item) {
        if (!item->data(0, kPathRole).isValid())
        {
            return; // a group header
        }
        const int group = item->data(0, kGroupRole).toInt();
        const int index = item->data(0, kIndexRole).toInt();
        if (m_play && group >= 0 && group < m_groups.size())
        {
            m_play(m_groups[group], index);
        }
    });
    connect(m_tree, &QTreeWidget::itemSelectionChanged, this, &DuplicatesDialog::updateButtons);
    layout->addWidget(m_tree, 1);

    auto* buttons = new QHBoxLayout();
    m_showButton = new QPushButton("Show in Folder");
    connect(m_showButton, &QPushButton::clicked, this, &DuplicatesDialog::showSelectedInFolder);
    buttons->addWidget(m_showButton);
    m_trashButton = new QPushButton("Move to Recycle Bin...");
    connect(m_trashButton, &QPushButton::clicked, this, &DuplicatesDialog::trashSelected);
    buttons->addWidget(m_trashButton);
    buttons->addStretch();
    auto* closeButton = new QPushButton("Close");
    connect(closeButton, &QPushButton::clicked, this, &QDialog::accept);
    buttons->addWidget(closeButton);
    layout->addLayout(buttons);

    rebuild();
}

void DuplicatesDialog::rebuild()
{
    m_tree->clear();
    int copies = 0;
    for (int g = 0; g < m_groups.size(); ++g)
    {
        const QVector<TrackInfo>& group = m_groups[g];
        copies += group.size();

        auto* header = new QTreeWidgetItem(m_tree);
        header->setText(0, QString("%1 — %2 copies").arg(group.first().title).arg(group.size()));
        header->setFirstColumnSpanned(true);
        header->setFlags(Qt::ItemIsEnabled);
        QFont font = header->font(0);
        font.setBold(true);
        header->setFont(0, font);

        for (int i = 0; i < group.size(); ++i)
        {
            const TrackInfo& t = group[i];
            auto* item = new QTreeWidgetItem(m_tree);
            item->setText(0, "    " + t.title + (t.artist.isEmpty() ? QString() : "  ·  " + t.artist));
            item->setText(1, FormatDuration(static_cast<int>(t.durationSeconds + 0.5)));
            item->setText(2, t.bitrateKbps > 0 ? QString("%1 kbps").arg(t.bitrateKbps) : QString());
            item->setText(3, QDir::toNativeSeparators(t.path));
            item->setToolTip(3, QDir::toNativeSeparators(t.path));
            item->setData(0, kPathRole, t.path);
            item->setData(0, kGroupRole, g);
            item->setData(0, kIndexRole, i);
        }
    }

    m_summary->setText(m_groups.isEmpty()
        ? "No duplicates — every song is stored once."
        : QString("%1 songs are stored more than once (%2 files). Double-click a copy to play it.")
              .arg(m_groups.size()).arg(copies));
    updateButtons();
}

void DuplicatesDialog::updateButtons()
{
    QTreeWidgetItem* item = m_tree->currentItem();
    const bool fileSelected = item && item->isSelected() && item->data(0, kPathRole).isValid();
    m_showButton->setEnabled(fileSelected);
    m_trashButton->setEnabled(fileSelected);
}

void DuplicatesDialog::showSelectedInFolder()
{
    QTreeWidgetItem* item = m_tree->currentItem();
    if (!item || !item->data(0, kPathRole).isValid())
    {
        return;
    }
    ShowInFileManager(item->data(0, kPathRole).toString());
}

void DuplicatesDialog::trashSelected()
{
    QTreeWidgetItem* item = m_tree->currentItem();
    if (!item || !item->data(0, kPathRole).isValid())
    {
        return;
    }
    const QString path = item->data(0, kPathRole).toString();
    if (!ErrorReporter::confirm(this, "Move to Recycle Bin",
            "Move this copy to the Recycle Bin?\n\n" + QDir::toNativeSeparators(path)))
    {
        return;
    }
    if (!QFile::moveToTrash(path))
    {
        ErrorReporter::warn(this, "Couldn't move the file",
            "The file couldn't be moved to the Recycle Bin. If it's the song that's playing, "
            "play something else first and try again.");
        return;
    }
    if (m_deleted)
    {
        m_deleted(path);
    }

    // Drop it from its group; a group left with one copy isn't a duplicate any more.
    const int g = item->data(0, kGroupRole).toInt();
    const int i = item->data(0, kIndexRole).toInt();
    if (g >= 0 && g < m_groups.size() && i >= 0 && i < m_groups[g].size())
    {
        m_groups[g].removeAt(i);
        if (m_groups[g].size() < 2)
        {
            m_groups.removeAt(g);
        }
    }
    rebuild();
}

} // namespace audioforge
