#include "audioforge/dialogs/track_list_dialog.hpp"

#include <QVBoxLayout>
#include <QListWidget>

namespace audioforge {

TrackListDialog::TrackListDialog(const QString& title, const QVector<TrackInfo>& tracks, QWidget* parent) : QDialog(parent)
{
    setWindowTitle(title);
    resize(480, 480);

    auto* layout = new QVBoxLayout(this);
    auto* list = new QListWidget();
    for (const TrackInfo& t : tracks)
    {
        QString label = t.title;
        if (!t.artist.isEmpty())
        {
            label += "  -  " + t.artist;
        }
        auto* item = new QListWidgetItem(label);
        item->setData(Qt::UserRole, t.path);
        list->addItem(item);
    }
    layout->addWidget(list);

    connect(list, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem* item) {
        m_chosenPath = item->data(Qt::UserRole).toString();
        accept();
    });
}

} // namespace audioforge
