#include "audioforge/dialogs/missing_covers_dialog.hpp"
#include "audioforge/media_mux.hpp"
#include "audioforge/online_lookup.hpp"
#include "audioforge/track_metadata.hpp"
#include "audioforge/widgets/online_views.hpp"

#include <QApplication>
#include <QHBoxLayout>
#include <QLabel>
#include <QListWidget>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QPixmap>
#include <QPushButton>
#include <QTimer>
#include <QVBoxLayout>

namespace audioforge {

namespace {
constexpr int kChecksPerTick = 8;
constexpr int kLookupGapMs = 350; // gentle on Apple's and Deezer's limits
}

MissingCoversDialog::MissingCoversDialog(QNetworkAccessManager* network, const QVector<Group>& groups, QWidget* parent)
    : QDialog(parent), m_network(network), m_groups(groups)
{
    setWindowTitle(tr("Find missing covers"));
    setWindowFlag(Qt::WindowContextHelpButtonHint, false);
    resize(640, 600);
    auto* l = new QVBoxLayout(this);
    l->setContentsMargins(22, 20, 22, 18);
    l->setSpacing(12);
    auto* heading = new QLabel(tr("Find missing covers"));
    heading->setObjectName("PanelTitle");
    l->addWidget(heading);
    auto* note = new QLabel(tr("Covers for your albums, and for songs without an album, from Apple Music or Deezer. "
                               "Your files only change when you click Save, and only for the ones left ticked."));
    note->setObjectName("PageSubtitle");
    note->setWordWrap(true);
    l->addWidget(note);
    m_list = new QListWidget();
    m_list->setIconSize(QSize(48, 48));
    m_list->setSpacing(2);
    m_list->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_list->setTextElideMode(Qt::ElideRight);
    m_list->setUniformItemSizes(true);
    l->addWidget(m_list, 1);
    m_status = new QLabel();
    m_status->setObjectName("PageSubtitle");
    l->addWidget(m_status);

    auto* buttons = new QHBoxLayout();
    m_stopButton = OnlineButton(tr("Stop"));
    connect(m_stopButton, &QPushButton::clicked, this, [this]() {
        m_stopped = true;
        m_checkTimer->stop();
        m_status->setText(tr("Stopped."));
        updateButtons();
    });
    buttons->addWidget(m_stopButton);
    buttons->addStretch();
    auto* close = OnlineButton(tr("Close"));
    connect(close, &QPushButton::clicked, this, &QDialog::reject);
    buttons->addWidget(close);
    m_saveButton = OnlineButton(tr("Save"), "PrimaryButton");
    connect(m_saveButton, &QPushButton::clicked, this, &MissingCoversDialog::save);
    buttons->addWidget(m_saveButton);
    l->addLayout(buttons);
    connect(m_list, &QListWidget::itemChanged, this, &MissingCoversDialog::updateButtons);

    // First which ones have no cover (reading tags: a few per tick, so the window stays responsive).
    m_checkTimer = new QTimer(this);
    m_checkTimer->setInterval(0);
    connect(m_checkTimer, &QTimer::timeout, this, &MissingCoversDialog::checkSome);
    m_checkTimer->start();
    updateButtons();
}

void MissingCoversDialog::checkSome()
{
    for (int n = 0; n < kChecksPerTick && m_checked < m_groups.size(); ++n, ++m_checked)
    {
        const Group& group = m_groups[m_checked];
        if (group.paths.isEmpty() || !ReadCoverArt(group.paths.first()).isEmpty())
        {
            continue;
        }
        const QString name = group.album.isEmpty() ? group.title : group.album;
        auto* item = new QListWidgetItem(QIcon(theme::generatedArt(name, 48, 6, group.album.isEmpty() ? theme::Icon::Track : theme::Icon::Album)),
                                         QStringLiteral("%1\n%2").arg(name, group.artist.isEmpty() ? tr("Unknown artist") : group.artist));
        item->setFlags(item->flags() & ~Qt::ItemIsUserCheckable);
        item->setToolTip(group.album.isEmpty() ? tr("A song without an album") : tr("%1 songs").arg(group.paths.size()));
        m_list->addItem(item);
        m_missing << m_checked;
        m_found << QByteArray();
    }
    m_status->setText(tr("Checking which songs have no cover… %1 of %2").arg(m_checked).arg(m_groups.size()));
    if (m_checked >= m_groups.size())
    {
        m_checkTimer->stop();
        if (m_missing.isEmpty())
        {
            m_status->setText(tr("Every album and song already has a cover."));
            updateButtons();
            return;
        }
        lookUpNext();
    }
}

void MissingCoversDialog::lookUpNext()
{
    if (m_stopped)
    {
        return;
    }
    if (m_nextLookup >= m_missing.size())
    {
        const int found = static_cast<int>(std::count_if(m_found.begin(), m_found.end(), [](const QByteArray& b) { return !b.isEmpty(); }));
        m_status->setText(tr("Found %1 of %2 covers. Untick any that look wrong, then click Save.").arg(found).arg(m_missing.size()));
        updateButtons();
        return;
    }
    const int row = m_nextLookup++;
    const Group group = m_groups[m_missing[row]];
    m_status->setText(tr("Looking up %1 of %2…").arg(row + 1).arg(m_missing.size()));
    m_list->scrollToItem(m_list->item(row));
    auto gotUrl = [this, row](const QString& url, const QString& source) {
        auto next = [this]() { QTimer::singleShot(kLookupGapMs, this, &MissingCoversDialog::lookUpNext); };
        QListWidgetItem* item = m_list->item(row);
        if (url.isEmpty())
        {
            item->setText(item->text().section('\n', 0, 0) + '\n' + tr("Not found"));
            next();
            return;
        }
        QNetworkReply* reply = NetworkGet(m_network, QUrl(url));
        connect(reply, &QNetworkReply::finished, this, [this, reply, row, source, next]() {
            reply->deleteLater();
            QListWidgetItem* item = m_list->item(row);
            const QByteArray jpeg = reply->error() == QNetworkReply::NoError ? SquareJpeg(reply->readAll(), 1000) : QByteArray();
            QPixmap pixmap;
            if (!jpeg.isEmpty() && pixmap.loadFromData(jpeg))
            {
                m_found[row] = jpeg;
                item->setIcon(QIcon(theme::roundedPixmap(pixmap.scaled(96, 96, Qt::KeepAspectRatio, Qt::SmoothTransformation), 6)));
                item->setText(item->text().section('\n', 0, 0) + '\n' + tr("Found on %1").arg(source));
                item->setFlags(item->flags() | Qt::ItemIsUserCheckable);
                item->setCheckState(Qt::Checked);
            }
            else
            {
                item->setText(item->text().section('\n', 0, 0) + '\n' + tr("Not found"));
            }
            next();
        });
    };
    if (group.album.isEmpty())
    {
        LookupSongCover(m_network, this, group.artist, group.title, gotUrl);
    }
    else
    {
        LookupAlbumCover(m_network, this, group.artist, group.album, gotUrl);
    }
}

void MissingCoversDialog::updateButtons()
{
    int ticked = 0;
    for (int i = 0; i < m_list->count(); ++i)
    {
        ticked += (m_list->item(i)->flags() & Qt::ItemIsUserCheckable) && m_list->item(i)->checkState() == Qt::Checked ? 1 : 0;
    }
    const bool busy = !m_stopped && (m_checkTimer->isActive() || m_nextLookup < m_missing.size());
    m_stopButton->setVisible(busy);
    m_saveButton->setEnabled(ticked > 0);
    m_saveButton->setText(ticked > 0 ? tr("Save %1 covers").arg(ticked) : tr("Save"));
}

void MissingCoversDialog::save()
{
    m_stopped = true; // no more lookups while writing
    QStringList written;
    int failed = 0;
    for (int row = 0; row < m_list->count(); ++row)
    {
        QListWidgetItem* item = m_list->item(row);
        if (!(item->flags() & Qt::ItemIsUserCheckable) || item->checkState() != Qt::Checked || m_found[row].isEmpty())
        {
            continue;
        }
        for (const QString& path : m_groups[m_missing[row]].paths)
        {
            if (saveCover && saveCover(path, m_found[row]))
            {
                written << path;
            }
            else
            {
                ++failed;
            }
        }
        item->setFlags(item->flags() & ~Qt::ItemIsUserCheckable);
        item->setText(item->text().section('\n', 0, 0) + '\n' + tr("Saved"));
        QApplication::processEvents();
    }
    m_status->setText(failed == 0 ? tr("Saved covers to %1 songs.").arg(written.size())
                                  : tr("Saved covers to %1 songs; %2 couldn't be written (read-only or in use).").arg(written.size()).arg(failed));
    updateButtons();
    emit coversSaved(written);
}

} // namespace audioforge
