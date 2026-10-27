#pragma once

#include "audioforge/theme.hpp"

#include <QHash>
#include <QObject>
#include <QPixmap>
#include <QSet>
#include <QStyledItemDelegate>
#include <QUrl>

class QLabel;
class QNetworkAccessManager;
class QNetworkReply;
class QPushButton;
class QVBoxLayout;

// Pieces shared by the online pages (Radio, podcast search, free audiobooks).
namespace audioforge {

// A GET with the app's User-Agent (directories ask for one) that follows redirects.
QNetworkReply* NetworkGet(QNetworkAccessManager* network, const QUrl& url);

// Pictures from the web (station logos, podcast artwork, book covers),
// fetched on first use and kept. A miss returns generated art at once;
// `loaded` fires when the real one is in.
class WebImageCache : public QObject
{
    Q_OBJECT

public:
    explicit WebImageCache(QNetworkAccessManager* network, QObject* parent = nullptr);

    // `radius` < 0: a circle. "cover:<path>" is a local file's embedded cover.
    QPixmap image(const QString& url, int size, qreal radius, const QString& seed, theme::Icon mark);
    QByteArray bytes(const QString& url) const; // the downloaded file, if it's in (empty otherwise)
    void prefetch(const QString& url);

signals:
    void loaded();

private:
    void startNext();

    QNetworkAccessManager* m_network;
    QHash<QString, QByteArray> m_bytes;
    QHash<QString, QImage> m_images;
    QHash<QString, QPixmap> m_scaled; // "url|size|radius"
    QSet<QString> m_failed;
    QStringList m_waiting;
    QSet<QString> m_pending;
    int m_running = 0;
};

// Online list rows: picture, title over a details line, and on the right a
// star, a button ("Download", "Subscribe"), a progress pill or plain text.
namespace online_role {
enum : int {
    Subtitle = Qt::UserRole + 40,
    ImageUrl,
    Seed,       // generated art's seed when there's no picture
    Mark,       // its theme::Icon
    Trailing,   // plain text on the right ("1:02:33")
    Star,       // bool: a star (filled when true)
    Button,     // QString: a button's label
    Progress,   // double 0..1 while something downloads (-1 / unset: none)
    Accent,     // bool: playing now
    Round,      // bool: a round picture (artists, stations)
    Data,       // the page's own payload
};
}

class OnlineRowDelegate : public QStyledItemDelegate
{
public:
    OnlineRowDelegate(WebImageCache* images, QObject* parent = nullptr);
    void setColors(const theme::Tokens& tokens) { m_t = tokens; }

    // Where a row's star / button is -- the page checks clicks against it.
    static QRect trailingRect(const QRect& row, const QModelIndex& index, const QFontMetrics& metrics);

    void paint(QPainter* p, const QStyleOptionViewItem& option, const QModelIndex& index) const override;
    QSize sizeHint(const QStyleOptionViewItem& option, const QModelIndex& index) const override;

private:
    WebImageCache* m_images;
    theme::Tokens m_t;
};

// "Radio" over its details line, the page header the library pages use.
QVBoxLayout* OnlinePageTitle(const QString& title, QLabel** subtitleOut);
QPushButton* OnlinePill(const QString& text, bool checkable = true); // a FilterPill
QPushButton* OnlineButton(const QString& text, const char* objectName = nullptr);

} // namespace audioforge
