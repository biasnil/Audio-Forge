#include "audioforge/widgets/online_views.hpp"
#include "audioforge/app_info.hpp"
#include "audioforge/track_metadata.hpp"

#include <QApplication>
#include <QLabel>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QPainter>
#include <QPainterPath>
#include <QPushButton>
#include <QVBoxLayout>

namespace audioforge {

QNetworkReply* NetworkGet(QNetworkAccessManager* network, const QUrl& url)
{
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::UserAgentHeader, app::UserAgent());
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute, QNetworkRequest::NoLessSafeRedirectPolicy);
    request.setTransferTimeout(30000);
    return network->get(request);
}

// --- WebImageCache -------------------------------------------------------------------

namespace {
constexpr int kMaxImageDownloads = 4;
constexpr int kMaxImageBytes = 4 * 1024 * 1024;
}

WebImageCache::WebImageCache(QNetworkAccessManager* network, QObject* parent) : QObject(parent), m_network(network)
{
}

void WebImageCache::prefetch(const QString& url)
{
    if (url.isEmpty() || m_images.contains(url) || m_failed.contains(url) || m_pending.contains(url))
    {
        return;
    }
    if (url.startsWith(QLatin1String("cover:")))
    {
        // A local file's own cover (a downloaded audiobook): read right away.
        const QByteArray data = ReadCoverArt(url.mid(6));
        QImage image;
        if (!data.isEmpty() && image.loadFromData(data))
        {
            m_bytes.insert(url, data);
            m_images.insert(url, image.width() > 600 ? image.scaledToWidth(600, Qt::SmoothTransformation) : image);
        }
        else
        {
            m_failed.insert(url);
        }
        return;
    }
    m_pending.insert(url);
    m_waiting.prepend(url); // newest first: what's on screen now
    startNext();
}

QPixmap WebImageCache::image(const QString& url, int size, qreal radius, const QString& seed, theme::Icon mark)
{
    const qreal r = radius < 0 ? size / 2.0 : radius;
    if (url.startsWith(QLatin1String("cover:")))
    {
        prefetch(url); // local: in at once
    }
    const auto found = m_images.constFind(url);
    if (!url.isEmpty() && found != m_images.constEnd())
    {
        const QString key = QStringLiteral("%1|%2|%3").arg(url).arg(size).arg(r);
        auto it = m_scaled.find(key);
        if (it == m_scaled.end())
        {
            const qreal dpr = qApp->devicePixelRatio();
            const int px = qRound(size * dpr);
            // Logos come in every shape: fit them on a soft tile rather than cropping a wordmark.
            QImage scaled = found->scaled(px, px, Qt::KeepAspectRatio, Qt::SmoothTransformation);
            QPixmap tile(px, px);
            tile.fill(Qt::transparent);
            QPainter p(&tile);
            p.setRenderHint(QPainter::Antialiasing);
            QPainterPath clip;
            clip.addRoundedRect(QRectF(0, 0, px, px), r * dpr, r * dpr);
            p.setClipPath(clip);
            if (scaled.width() != scaled.height())
            {
                p.fillRect(tile.rect(), QColor(255, 255, 255, 235));
            }
            p.drawImage((px - scaled.width()) / 2, (px - scaled.height()) / 2, scaled);
            p.end();
            tile.setDevicePixelRatio(dpr);
            it = m_scaled.insert(key, tile);
        }
        return *it;
    }
    prefetch(url);
    return theme::generatedArt(seed, size, r, mark);
}

QByteArray WebImageCache::bytes(const QString& url) const
{
    return m_bytes.value(url);
}

void WebImageCache::startNext()
{
    while (m_running < kMaxImageDownloads && !m_waiting.isEmpty())
    {
        const QString url = m_waiting.takeFirst();
        ++m_running;
        QNetworkReply* reply = NetworkGet(m_network, QUrl(url));
        connect(reply, &QNetworkReply::finished, this, [this, reply, url]() {
            reply->deleteLater();
            --m_running;
            m_pending.remove(url);
            const QByteArray data = reply->error() == QNetworkReply::NoError ? reply->read(kMaxImageBytes) : QByteArray();
            QImage image;
            if (!data.isEmpty() && image.loadFromData(data))
            {
                m_bytes.insert(url, data);
                m_images.insert(url, image.width() > 600 ? image.scaledToWidth(600, Qt::SmoothTransformation) : image);
                emit loaded();
            }
            else
            {
                m_failed.insert(url); // generated art from now on
            }
            startNext();
        });
    }
}

// --- OnlineRowDelegate -------------------------------------------------------------

namespace {
constexpr int kRowHeight = 56;
constexpr int kPicture = 40;
}

OnlineRowDelegate::OnlineRowDelegate(WebImageCache* images, QObject* parent)
    : QStyledItemDelegate(parent), m_images(images), m_t(theme::tokens(true))
{
}

QSize OnlineRowDelegate::sizeHint(const QStyleOptionViewItem&, const QModelIndex&) const
{
    return QSize(300, kRowHeight);
}

QRect OnlineRowDelegate::trailingRect(const QRect& row, const QModelIndex& index, const QFontMetrics& metrics)
{
    if (index.data(online_role::Star).isValid())
    {
        return QRect(row.right() - 10 - 30, row.center().y() - 15, 30, 30);
    }
    const QString button = index.data(online_role::Button).toString();
    const bool progress = index.data(online_role::Progress).isValid() && index.data(online_role::Progress).toDouble() >= 0;
    if (!button.isEmpty() || progress)
    {
        const int width = qMax(92, metrics.horizontalAdvance(button) + 28);
        return QRect(row.right() - 10 - width, row.center().y() - 14, width, 28);
    }
    return QRect();
}

void OnlineRowDelegate::paint(QPainter* p, const QStyleOptionViewItem& option, const QModelIndex& index) const
{
    using namespace online_role;
    p->save();
    p->setRenderHint(QPainter::Antialiasing);
    const QRect r = option.rect.adjusted(0, 1, 0, -1);
    const bool selected = option.state & QStyle::State_Selected;
    if (selected || (option.state & QStyle::State_MouseOver))
    {
        p->setPen(Qt::NoPen);
        p->setBrush(selected ? m_t.surfaceHi : m_t.surface);
        p->drawRoundedRect(QRectF(r), 8, 8);
    }

    const QString title = index.data().toString();
    const bool round = index.data(Round).toBool();
    const QRect picture(r.left() + 8, r.center().y() - kPicture / 2, kPicture, kPicture);
    const QString seed = index.data(Seed).toString().isEmpty() ? title : index.data(Seed).toString();
    const auto mark = static_cast<theme::Icon>(index.data(Mark).isValid() ? index.data(Mark).toInt() : static_cast<int>(theme::Icon::Track));
    p->drawPixmap(picture, m_images->image(index.data(ImageUrl).toString(), kPicture, round ? -1 : 6, seed, mark));

    QFont base = option.font;
    base.setPixelSize(13);
    const QFontMetrics metrics(base);
    const QRect trailing = trailingRect(r, index, metrics);
    int textRight = (trailing.isValid() ? trailing.left() : r.right() - 10) - 12;

    // Plain text on the right (a length, a date).
    const QString trailingText = index.data(Trailing).toString();
    QFont small = base;
    small.setPixelSize(12);
    small.setFeature(QFont::Tag("tnum"), 1);
    if (!trailingText.isEmpty())
    {
        const int width = QFontMetrics(small).horizontalAdvance(trailingText);
        p->setFont(small);
        p->setPen(m_t.muted);
        p->drawText(QRect(textRight - width, r.top(), width, r.height()), Qt::AlignRight | Qt::AlignVCenter, trailingText);
        textRight -= width + 16;
    }

    const int textLeft = picture.right() + 13;
    const QFontMetrics smallMetrics(small);
    const int top = r.center().y() - (metrics.height() + smallMetrics.height()) / 2;
    p->setFont(base);
    p->setPen(index.data(Accent).toBool() ? m_t.accent : m_t.text);
    p->drawText(QRect(textLeft, top, textRight - textLeft, metrics.height()), Qt::AlignLeft | Qt::AlignVCenter,
                metrics.elidedText(title, Qt::ElideRight, textRight - textLeft));
    p->setFont(small);
    p->setPen(m_t.muted);
    p->drawText(QRect(textLeft, top + metrics.height(), textRight - textLeft, smallMetrics.height()), Qt::AlignLeft | Qt::AlignVCenter,
                smallMetrics.elidedText(index.data(Subtitle).toString(), Qt::ElideRight, textRight - textLeft));

    if (trailing.isValid())
    {
        if (index.data(Star).isValid())
        {
            const bool on = index.data(Star).toBool();
            const QIcon star = theme::icon(on ? theme::Icon::StarFilled : theme::Icon::Star, on ? m_t.accent : m_t.textSoft,
                                           QColor(), QColor(), 18);
            star.paint(p, trailing.adjusted(6, 6, -6, -6));
        }
        else
        {
            const double progress = index.data(Progress).isValid() ? index.data(Progress).toDouble() : -1.0;
            p->setPen(QPen(m_t.border, 1));
            p->setBrush(Qt::NoBrush);
            p->drawRoundedRect(QRectF(trailing).adjusted(0.5, 0.5, -0.5, -0.5), 14, 14);
            QString label = index.data(Button).toString();
            if (progress >= 0)
            {
                // The pill fills up as it downloads.
                QPainterPath pill;
                pill.addRoundedRect(QRectF(trailing).adjusted(1, 1, -1, -1), 13, 13);
                p->save();
                p->setClipRect(QRectF(trailing.left(), trailing.top(), trailing.width() * qBound(0.0, progress, 1.0), trailing.height()));
                QColor fill = m_t.accent;
                fill.setAlphaF(0.35);
                p->fillPath(pill, fill);
                p->restore();
                if (label.isEmpty())
                {
                    label = QStringLiteral("%1%").arg(qRound(progress * 100));
                }
            }
            p->setFont(small);
            p->setPen(m_t.text);
            p->drawText(trailing, Qt::AlignCenter, label);
        }
    }
    p->restore();
}

// --- Page pieces -------------------------------------------------------------------

QVBoxLayout* OnlinePageTitle(const QString& title, QLabel** subtitleOut)
{
    auto* block = new QVBoxLayout();
    block->setSpacing(2);
    auto* titleLabel = new QLabel(title);
    titleLabel->setObjectName("PageTitle");
    block->addWidget(titleLabel);
    auto* subtitle = new QLabel();
    subtitle->setObjectName("PageSubtitle");
    subtitle->setMinimumHeight(subtitle->fontMetrics().height());
    block->addWidget(subtitle);
    if (subtitleOut)
    {
        *subtitleOut = subtitle;
    }
    return block;
}

QPushButton* OnlinePill(const QString& text, bool checkable)
{
    auto* pill = OnlineButton(text, "FilterPill");
    pill->setCheckable(checkable);
    return pill;
}

QPushButton* OnlineButton(const QString& text, const char* objectName)
{
    auto* button = new QPushButton(text);
    if (objectName)
    {
        button->setObjectName(objectName);
    }
    button->setCursor(Qt::PointingHandCursor);
    button->setFocusPolicy(Qt::NoFocus);
    return button;
}

} // namespace audioforge
