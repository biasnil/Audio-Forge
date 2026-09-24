#include "audioforge/dialogs/playlist_edit_dialog.hpp"
#include "audioforge/warnings.hpp"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QListWidget>
#include <QPushButton>
#include <QDialogButtonBox>
#include <QFileInfo>

namespace audioforge {

PlaylistEditDialog::PlaylistEditDialog(PlaylistData& playlist, const QVector<TrackInfo>& allTracks, QWidget* parent)
    : QDialog(parent), m_playlist(playlist), m_allTracks(allTracks)
{
    setWindowTitle(playlist.name);
    resize(480, 560);

    auto* layout = new QVBoxLayout(this);

    m_list = new QListWidget();
    refreshList();
    layout->addWidget(m_list);

    connect(m_list, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem* item) {
        m_chosenPath = item->data(Qt::UserRole).toString();
        accept();
    });

    auto* removeButton = new QPushButton("Remove Selected");
    auto* addButton = new QPushButton("Add Tracks...");
    auto* deleteButton = new QPushButton("Delete Playlist");

    auto* buttonRow = new QHBoxLayout();
    buttonRow->addWidget(removeButton);
    buttonRow->addWidget(addButton);
    buttonRow->addWidget(deleteButton);
    layout->addLayout(buttonRow);

    connect(removeButton, &QPushButton::clicked, this, &PlaylistEditDialog::removeSelected);
    connect(addButton, &QPushButton::clicked, this, &PlaylistEditDialog::addTracks);
    connect(deleteButton, &QPushButton::clicked, this, &PlaylistEditDialog::confirmDelete);
}

QString PlaylistEditDialog::labelFor(const QString& path) const
{
    for (const TrackInfo& t : m_allTracks)
    {
        if (t.path == path)
        {
            QString label = t.title;
            if (!t.artist.isEmpty())
            {
                label += "  -  " + t.artist;
            }
            return label;
        }
    }
    return QFileInfo(path).fileName();
}

void PlaylistEditDialog::refreshList()
{
    m_list->clear();
    for (const QString& path : m_playlist.trackPaths)
    {
        auto* item = new QListWidgetItem(labelFor(path));
        item->setData(Qt::UserRole, path);
        m_list->addItem(item);
    }
}

void PlaylistEditDialog::removeSelected()
{
    // By row, not by path -- a track added to the playlist twice should
    // only lose the one copy that was selected.
    int row = m_list->currentRow();
    if (row < 0 || row >= m_playlist.trackPaths.size())
    {
        return;
    }
    m_playlist.trackPaths.removeAt(row);
    refreshList();
}

void PlaylistEditDialog::addTracks()
{
    QDialog picker(this);
    picker.setWindowTitle("Add tracks to " + m_playlist.name);
    picker.resize(420, 480);

    auto* layout = new QVBoxLayout(&picker);
    auto* list = new QListWidget();
    for (const TrackInfo& t : m_allTracks)
    {
        if (m_playlist.trackPaths.contains(t.path))
        {
            continue; // already in the playlist
        }
        QString label = t.title;
        if (!t.artist.isEmpty())
        {
            label += "  -  " + t.artist;
        }
        auto* item = new QListWidgetItem(label);
        item->setFlags(item->flags() | Qt::ItemIsUserCheckable);
        item->setCheckState(Qt::Unchecked);
        item->setData(Qt::UserRole, t.path);
        list->addItem(item);
    }
    layout->addWidget(list);

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel);
    buttons->button(QDialogButtonBox::Ok)->setText("Add");
    layout->addWidget(buttons);
    connect(buttons, &QDialogButtonBox::accepted, &picker, &QDialog::accept);
    connect(buttons, &QDialogButtonBox::rejected, &picker, &QDialog::reject);

    if (picker.exec() == QDialog::Accepted)
    {
        for (int i = 0; i < list->count(); ++i)
        {
            QListWidgetItem* item = list->item(i);
            if (item->checkState() == Qt::Checked)
            {
                m_playlist.trackPaths << item->data(Qt::UserRole).toString();
            }
        }
        refreshList();
    }
}

void PlaylistEditDialog::confirmDelete()
{
    bool confirmed = ErrorReporter::confirm(this, "Delete playlist",
        "Delete \"" + m_playlist.name + "\"? This can't be undone.");
    if (confirmed)
    {
        m_deleted = true;
        reject();
    }
}

} // namespace audioforge
