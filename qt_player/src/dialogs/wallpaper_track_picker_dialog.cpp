#include "audioforge/dialogs/wallpaper_track_picker_dialog.hpp"

#include <QDialogButtonBox>
#include <QListWidget>
#include <QVBoxLayout>

namespace audioforge {

WallpaperTrackPickerDialog::WallpaperTrackPickerDialog(const QVector<TrackInfo>& allTracks,
    const QStringList& initiallyChecked, QWidget* parent) : QDialog(parent)
{
    setWindowTitle("Choose tracks for this wallpaper");
    resize(420, 480);

    auto* layout = new QVBoxLayout(this);

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
        item->setCheckState(initiallyChecked.contains(t.path) ? Qt::Checked : Qt::Unchecked);
        item->setData(Qt::UserRole, t.path);
        m_trackList->addItem(item);
    }
    layout->addWidget(m_trackList);

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel);
    layout->addWidget(buttons);

    connect(buttons, &QDialogButtonBox::accepted, this, &QDialog::accept);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
}

QStringList WallpaperTrackPickerDialog::resultTrackPaths() const
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

} // namespace audioforge
