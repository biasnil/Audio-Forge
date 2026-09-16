#include "audioforge/dialogs/create_playlist_dialog.hpp"
#include "audioforge/warnings.hpp"

#include <QVBoxLayout>
#include <QLineEdit>
#include <QListWidget>
#include <QDialogButtonBox>
#include <QPushButton>

namespace audioforge {

CreatePlaylistDialog::CreatePlaylistDialog(const QVector<TrackInfo>& allTracks, QWidget* parent) : QDialog(parent)
{
    setWindowTitle("Create new playlist");
    resize(420, 480);

    auto* layout = new QVBoxLayout(this);

    m_nameEdit = new QLineEdit();
    m_nameEdit->setPlaceholderText("Enter name for the playlist");
    layout->addWidget(m_nameEdit);

    m_trackList = new QListWidget();
    for (const TrackInfo& t : allTracks)
    {
        QString label = t.title;
        if (!t.artist.isEmpty())
        {
            label += "  -  " + t.artist;
        }
        auto* item = new QListWidgetItem(label);
        item->setFlags(item->flags() | Qt::ItemIsUserCheckable);
        item->setCheckState(Qt::Unchecked);
        item->setData(Qt::UserRole, t.path);
        m_trackList->addItem(item);
    }
    layout->addWidget(m_trackList);

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel);
    buttons->button(QDialogButtonBox::Ok)->setText("Create");
    layout->addWidget(buttons);

    connect(buttons, &QDialogButtonBox::accepted, this, &CreatePlaylistDialog::tryAccept);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
}

QString CreatePlaylistDialog::resultName() const
{
    return m_nameEdit->text().trimmed();
}

QStringList CreatePlaylistDialog::resultTrackPaths() const
{
    QStringList paths;
    for (int i = 0; i < m_trackList->count(); ++i)
    {
        QListWidgetItem* item = m_trackList->item(i);
        if (item->checkState() == Qt::Checked)
        {
            paths << item->data(Qt::UserRole).toString();
        }
    }
    return paths;
}

void CreatePlaylistDialog::tryAccept()
{
    if (resultName().isEmpty())
    {
        ErrorReporter::warn(this, "Name required", "Please enter a name for the playlist.");
        return;
    }
    accept();
}

} // namespace audioforge
