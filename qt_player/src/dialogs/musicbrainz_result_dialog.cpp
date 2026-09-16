#include "audioforge/dialogs/musicbrainz_result_dialog.hpp"
#include "audioforge/warnings.hpp"

#include <QVBoxLayout>
#include <QListWidget>
#include <QLabel>
#include <QDialogButtonBox>
#include <QPushButton>

namespace audioforge {

MusicBrainzResultDialog::MusicBrainzResultDialog(const QVector<MusicBrainzCandidate>& candidates, QWidget* parent) : QDialog(parent)
{
    setWindowTitle("MusicBrainz results");
    resize(480, 360);

    auto* layout = new QVBoxLayout(this);

    if (candidates.isEmpty())
    {
        layout->addWidget(new QLabel("No matches found on MusicBrainz."));
    }
    else
    {
        layout->addWidget(new QLabel("Select the correct match:"));
    }

    m_list = new QListWidget();
    for (int i = 0; i < candidates.size(); ++i)
    {
        const MusicBrainzCandidate& c = candidates[i];
        QString label = c.title;
        if (!c.artist.isEmpty())
        {
            label += "  -  " + c.artist;
        }
        if (!c.album.isEmpty() || c.year > 0)
        {
            label += "  (";
            if (!c.album.isEmpty())
            {
                label += c.album;
            }
            if (c.year > 0)
            {
                label += (c.album.isEmpty() ? "" : ", ") + QString::number(c.year);
            }
            label += ")";
        }
        auto* item = new QListWidgetItem(label);
        item->setData(Qt::UserRole, i);
        m_list->addItem(item);
    }
    layout->addWidget(m_list);

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel);
    buttons->button(QDialogButtonBox::Ok)->setText("Apply Tags");
    layout->addWidget(buttons);

    connect(buttons, &QDialogButtonBox::accepted, this, [this]() {
        if (m_list->currentItem())
        {
            accept();
        }
        else
        {
            ErrorReporter::info(this, "Select a match", "Choose one of the results first, or Cancel.");
        }
    });
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_list, &QListWidget::itemDoubleClicked, this, [this](QListWidgetItem*) { accept(); });
}

int MusicBrainzResultDialog::selectedIndex() const
{
    return m_list->currentItem() ? m_list->currentItem()->data(Qt::UserRole).toInt() : -1;
}

} // namespace audioforge
