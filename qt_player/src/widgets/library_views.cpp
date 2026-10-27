#include "audioforge/widgets/library_views.hpp"
#include "audioforge/track_metadata.hpp"

#include <QApplication>
#include <QContextMenuEvent>
#include <QFileInfo>
#include <QGraphicsDropShadowEffect>
#include <QHBoxLayout>
#include <QHeaderView>
#include <QLabel>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QPushButton>
#include <QScrollBar>
#include <QTableWidget>

namespace audioforge {

namespace {

QFont PixelFont(const QFont& base, int px, QFont::Weight weight = QFont::Normal)
{
    QFont f(base);
    f.setPixelSize(px);
    f.setWeight(weight);
    return f;
}

// Digits all the same width, so columns of numbers line up.
QFont Tabular(QFont f)
{
    f.setFeature(QFont::Tag("tnum"), 1);
    return f;
}

QColor WithAlpha(QColor c, qreal alpha)
{
    c.setAlphaF(alpha);
    return c;
}

void DrawElided(QPainter* p, const QRect& rect, int flags, const QString& text)
{
    p->drawText(rect, flags, p->fontMetrics().elidedText(text, Qt::ElideRight, rect.width()));
}

// Decoded, cropped to a square and rounded -- on a worker thread, so
// QImage only (QPixmap belongs to the UI thread).
QImage CoverImage(const QByteArray& bytes, int px, qreal radiusPx)
{
    QImage image;
    if (bytes.isEmpty() || !image.loadFromData(bytes))
    {
        return QImage();
    }
    QImage scaled = image.scaled(px, px, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
    scaled = scaled.copy((scaled.width() - px) / 2, (scaled.height() - px) / 2, px, px);
    QImage out(px, px, QImage::Format_ARGB32_Premultiplied);
    out.fill(Qt::transparent);
    QPainter p(&out);
    p.setRenderHint(QPainter::Antialiasing);
    p.setPen(Qt::NoPen);
    p.setBrush(QBrush(scaled));
    p.drawRoundedRect(QRectF(0, 0, px, px), radiusPx, radiusPx);
    return out;
}

constexpr int kMaxWaiting = 400;    // further back than this, a scrolled-past row isn't worth reading
constexpr int kMaxCachedCovers = 4000;

} // namespace

// --- CoverCache --------------------------------------------------------------------

CoverCache::CoverCache(QObject* parent) : QObject(parent)
{
    m_pool.setMaxThreadCount(2);
    // Covers arrive one by one; the views repaint for a batch of them.
    m_notify.setSingleShot(true);
    m_notify.setInterval(60);
    connect(&m_notify, &QTimer::timeout, this, &CoverCache::loaded);
}

CoverCache::~CoverCache()
{
    m_pool.clear();
    m_pool.waitForDone();
}

QPixmap CoverCache::cover(const QString& path, int size, qreal radius, const QString& seed, theme::Icon mark)
{
    if (!path.isEmpty())
    {
        const QString key = QStringLiteral("%1|%2|%3").arg(path).arg(size).arg(radius);
        const auto it = m_pixmaps.constFind(key);
        if (it != m_pixmaps.constEnd())
        {
            return *it;
        }
        if (!m_noCover.contains(key) && !m_pending.contains(key))
        {
            m_pending.insert(key);
            m_waiting.push_back({key, path, size, radius});
            if (m_waiting.size() > kMaxWaiting)
            {
                m_pending.remove(m_waiting.takeFirst().key);
            }
            startNext();
        }
    }

    const QString generatedKey = QStringLiteral("%1|%2|%3|%4").arg(seed).arg(size).arg(radius).arg(static_cast<int>(mark));
    auto it = m_generated.find(generatedKey);
    if (it == m_generated.end())
    {
        it = m_generated.insert(generatedKey, theme::generatedArt(seed, size, radius, mark));
    }
    return *it;
}

void CoverCache::invalidate(const QString& path)
{
    const QString prefix = path + '|';
    m_pixmaps.removeIf([&prefix](const auto& entry) { return entry.key().startsWith(prefix); });
    m_noCover.removeIf([&prefix](const QString& key) { return key.startsWith(prefix); });
}

void CoverCache::startNext()
{
    const qreal dpr = qApp->devicePixelRatio();
    while (m_running < 2 && !m_waiting.isEmpty())
    {
        const Request request = m_waiting.takeLast(); // the most recently painted first
        ++m_running;
        const int px = qRound(request.size * dpr);
        const qreal radiusPx = request.radius * dpr;
        m_pool.start([this, request, px, radiusPx, dpr]() {
            const QImage image = CoverImage(ReadCoverArt(request.path), px, radiusPx);
            QMetaObject::invokeMethod(this, [this, request, image, dpr]() {
                --m_running;
                m_pending.remove(request.key);
                if (image.isNull())
                {
                    m_noCover.insert(request.key);
                }
                else
                {
                    if (m_pixmaps.size() > kMaxCachedCovers)
                    {
                        m_pixmaps.clear();
                    }
                    QPixmap pixmap = QPixmap::fromImage(image);
                    pixmap.setDevicePixelRatio(dpr);
                    m_pixmaps.insert(request.key, pixmap);
                    if (!m_notify.isActive())
                    {
                        m_notify.start();
                    }
                }
                startNext();
            }, Qt::QueuedConnection);
        });
    }
}

// --- Tracks table ------------------------------------------------------------------

bool TrackTableItem::operator<(const QTableWidgetItem& other) const
{
    const QVariant a = data(kTrackSortRole);
    const QVariant b = other.data(kTrackSortRole);
    if (a.isValid() && b.isValid())
    {
        if (a.typeId() == QMetaType::QString)
        {
            return QString::localeAwareCompare(a.toString(), b.toString()) < 0;
        }
        return QVariant::compare(a, b) == QPartialOrdering::Less;
    }
    return QString::localeAwareCompare(text(), other.text()) < 0;
}

TracksTableDelegate::TracksTableDelegate(QTableWidget* table, CoverCache* covers)
    : QStyledItemDelegate(table), m_table(table), m_covers(covers)
{
    m_t = theme::tokens(true);
    table->viewport()->setMouseTracking(true);
    table->viewport()->installEventFilter(this);
}

void TracksTableDelegate::setColors(const theme::Tokens& tokens)
{
    m_t = tokens;
    m_playingMark = theme::icon(theme::Icon::Visualizer, tokens.accent, QColor(), QColor(), 15).pixmap(15, 15);
    m_heart = theme::icon(theme::Icon::HeartFilled, tokens.accent, QColor(), QColor(), 14).pixmap(14, 14);
    m_table->viewport()->update();
}

void TracksTableDelegate::setPlayingPath(const QString& path)
{
    if (path != m_playingPath)
    {
        m_playingPath = path;
        m_table->viewport()->update();
    }
}

void TracksTableDelegate::setSkeleton(bool on)
{
    if (on != m_skeleton)
    {
        m_skeleton = on;
        m_table->viewport()->update();
    }
}

QSize TracksTableDelegate::sizeHint(const QStyleOptionViewItem&, const QModelIndex&) const
{
    return QSize(40, 46);
}

void TracksTableDelegate::paint(QPainter* p, const QStyleOptionViewItem& option, const QModelIndex& index) const
{
    using namespace tracks_column;
    p->save();
    p->setRenderHint(QPainter::Antialiasing);
    const QRect r = option.rect;
    const int column = index.column();
    const QString path = index.siblingAtColumn(Title).data(kTrackPathRole).toString();
    const bool playing = !m_playingPath.isEmpty() && path == m_playingPath;
    const bool selected = option.state & QStyle::State_Selected;

    if (selected)
    {
        p->fillRect(r, m_t.surfaceHi);
    }
    else if (playing || index.row() == m_hoverRow)
    {
        p->fillRect(r, m_t.surface);
    }
    p->fillRect(QRect(r.left(), r.bottom(), r.width(), 1), WithAlpha(m_t.text, 0.07));

    // 12 between columns (6 each side), 10 more at the table's two ends.
    const QRect cell = r.adjusted(column == Number ? 10 : 6, 0, column == Loved ? -10 : -6, -1);
    const QFont base = option.font;
    const int right = Qt::AlignRight | Qt::AlignVCenter;
    const int left = Qt::AlignLeft | Qt::AlignVCenter;

    switch (column)
    {
        case Number:
            if (playing)
            {
                p->drawPixmap(cell.right() - 14, cell.center().y() - 7, 15, 15, m_playingMark);
            }
            else
            {
                p->setFont(Tabular(PixelFont(base, 12)));
                p->setPen(m_t.muted);
                p->drawText(cell, right, index.data(kTrackNumberRole).toString());
            }
            break;
        case Title:
        {
            const QString title = index.data().toString();
            const QRect cover(cell.left(), cell.center().y() - 16, 32, 32);
            p->drawPixmap(cover, m_covers->cover(path, 32, 4, title));
            const int textLeft = cover.right() + 13;
            const QFont titleFont = PixelFont(base, 13);
            const QFont artistFont = PixelFont(base, 12);
            const int titleHeight = QFontMetrics(titleFont).height();
            const int artistHeight = QFontMetrics(artistFont).height();
            const int top = cell.center().y() - (titleHeight + artistHeight) / 2;
            p->setFont(titleFont);
            p->setPen(playing ? m_t.accent : m_t.text);
            DrawElided(p, QRect(textLeft, top, cell.right() - textLeft, titleHeight), left, title);
            p->setFont(artistFont);
            p->setPen(m_t.muted);
            DrawElided(p, QRect(textLeft, top + titleHeight, cell.right() - textLeft, artistHeight), left,
                       index.data(kTrackArtistRole).toString());
            break;
        }
        case Album:
        case Genre:
            p->setFont(PixelFont(base, 13));
            p->setPen(m_t.textSoft);
            DrawElided(p, cell, left, index.data().toString());
            break;
        case Year:
            p->setFont(Tabular(PixelFont(base, 13)));
            p->setPen(m_t.textSoft);
            p->drawText(cell, left, index.data().toString());
            break;
        case Plays:
        {
            p->setFont(Tabular(PixelFont(base, 13)));
            p->setPen(m_t.textSoft);
            p->drawText(cell, right, QString::number(playCount ? playCount(path) : 0));
            break;
        }
        case Time:
            p->setFont(Tabular(PixelFont(base, 13)));
            p->setPen(m_t.textSoft);
            p->drawText(cell, right, index.data().toString());
            break;
        case Loved:
            if (isLoved && isLoved(path))
            {
                p->drawPixmap(cell.right() - 13, cell.center().y() - 7, 14, 14, m_heart);
            }
            break;
        default:
            break;
    }
    p->restore();
}

bool TracksTableDelegate::eventFilter(QObject* watched, QEvent* event)
{
    if (watched != m_table->viewport())
    {
        return false;
    }
    switch (event->type())
    {
        case QEvent::MouseMove:
        {
            const int row = m_table->rowAt(static_cast<QMouseEvent*>(event)->position().toPoint().y());
            if (row != m_hoverRow)
            {
                m_hoverRow = row;
                m_table->viewport()->update();
            }
            break;
        }
        case QEvent::Leave:
            if (m_hoverRow != -1)
            {
                m_hoverRow = -1;
                m_table->viewport()->update();
            }
            break;
        case QEvent::Paint:
            if (m_skeleton && m_table->rowCount() == 0)
            {
                QPainter p(m_table->viewport());
                paintSkeleton(&p);
            }
            break;
        default:
            break;
    }
    return false;
}

// Grey placeholder rows in the columns' places, fading out down the list.
void TracksTableDelegate::paintSkeleton(QPainter* p) const
{
    using namespace tracks_column;
    p->setRenderHint(QPainter::Antialiasing);
    p->setPen(Qt::NoPen);
    const QHeaderView* header = m_table->horizontalHeader();
    auto columnRect = [header](int column, int top) {
        return QRect(header->sectionViewportPosition(column), top, header->sectionSize(column), 46);
    };
    constexpr int kRows = 11;
    for (int row = 0; row < kRows; ++row)
    {
        const int top = row * 46;
        p->setOpacity(1.0 - 0.7 * row / (kRows - 1));
        p->fillRect(QRect(0, top + 45, m_table->viewport()->width(), 1), WithAlpha(m_t.text, 0.07));
        p->setBrush(m_t.surfaceHi);
        auto bar = [p](int x, int y, int width, int height) { p->drawRoundedRect(QRectF(x, y, width, height), 3, 3); };
        // Lengths vary a little from row to row, like real text.
        const int vary = (row * 37) % 23;

        const QRect number = columnRect(Number, top);
        bar(number.right() - 6 - 16, top + 19, 16, 8);
        const QRect title = columnRect(Title, top).adjusted(6, 0, -6, 0);
        bar(title.left(), top + 7, 32, 32);
        bar(title.left() + 44, top + 13, qMin(title.width() - 44, 90 + vary * 3), 10);
        bar(title.left() + 44, top + 28, qMin(title.width() - 44, 56 + vary * 2), 8);
        for (int column : {Album, Genre})
        {
            const QRect cell = columnRect(column, top).adjusted(6, 0, -6, 0);
            bar(cell.left(), top + 18, qMin(cell.width(), (column == Album ? 70 : 50) + vary * 2), 10);
        }
        const QRect year = columnRect(Year, top).adjusted(6, 0, -6, 0);
        bar(year.left(), top + 18, qMin(year.width(), 30), 10);
        for (int column : {Plays, Time})
        {
            const QRect cell = columnRect(column, top).adjusted(6, 0, -6, 0);
            bar(cell.right() - 24, top + 18, 24, 10);
        }
    }
    p->setOpacity(1.0);
}

// --- Selection bar -----------------------------------------------------------------

SelectionActionBar::SelectionActionBar(QWidget* parent) : QFrame(parent)
{
    setObjectName("SelectionBar");
    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(14, 5, 6, 5);
    layout->setSpacing(2);

    m_countLabel = new QLabel();
    QFont countFont = m_countLabel->font();
    countFont.setPixelSize(13);
    countFont.setWeight(QFont::Medium);
    m_countLabel->setFont(countFont);
    layout->addWidget(m_countLabel);
    layout->addSpacing(10);

    auto addButton = [this, layout](const QString& text, const char* objectName) {
        auto* button = new QPushButton(text);
        button->setObjectName(objectName);
        button->setCursor(Qt::PointingHandCursor);
        button->setFocusPolicy(Qt::NoFocus);
        layout->addWidget(button);
        return button;
    };
    connect(addButton(tr("Play next"), "SelectionPrimary"), &QPushButton::clicked, this, &SelectionActionBar::playNextClicked);
    connect(addButton(tr("Add to queue"), "FlatButton"), &QPushButton::clicked, this, &SelectionActionBar::addToQueueClicked);
    QPushButton* addToPlaylist = addButton(tr("Add to playlist"), "FlatButton");
    connect(addToPlaylist, &QPushButton::clicked, this, [this, addToPlaylist]() {
        emit addToPlaylistClicked(addToPlaylist->mapToGlobal(QPoint(0, addToPlaylist->height())));
    });
    auto* divider = new QFrame();
    divider->setObjectName("VDivider");
    divider->setFixedHeight(16);
    layout->addSpacing(4);
    layout->addWidget(divider);
    layout->addSpacing(4);
    connect(addButton(tr("Edit tags"), "FlatButton"), &QPushButton::clicked, this, &SelectionActionBar::editTagsClicked);
    connect(addButton(tr("Change cover"), "FlatButton"), &QPushButton::clicked, this, &SelectionActionBar::changeCoverClicked);
    m_closeButton = addButton(QString(), "FlatButton");
    m_closeButton->setFixedSize(30, 30);
    m_closeButton->setIconSize(QSize(14, 14));
    m_closeButton->setToolTip(tr("Clear selection (Esc)"));
    m_closeButton->setAccessibleName(tr("Clear selection"));
    connect(m_closeButton, &QPushButton::clicked, this, &SelectionActionBar::clearClicked);

    auto* shadow = new QGraphicsDropShadowEffect(this);
    shadow->setBlurRadius(18);
    shadow->setOffset(0, 6);
    shadow->setColor(QColor(0, 0, 0, 140));
    setGraphicsEffect(shadow);
}

void SelectionActionBar::setCount(int count)
{
    m_countLabel->setText(tr("%1 selected").arg(count));
    adjustSize();
}

void SelectionActionBar::setCloseIcon(const QIcon& icon)
{
    m_closeButton->setIcon(icon);
}

// --- A-Z index ---------------------------------------------------------------------

namespace {
constexpr int kLetterPitch = 13; // 10 px letter + 3 px gap
}

AlphabetIndex::AlphabetIndex(QWidget* parent)
    : QWidget(parent), m_letters(QStringLiteral("#ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
{
    setCursor(Qt::PointingHandCursor);
    setFixedSize(sizeHint());
    setToolTip(tr("Jump to a letter"));
}

QSize AlphabetIndex::sizeHint() const
{
    return QSize(14, m_letters.size() * kLetterPitch);
}

void AlphabetIndex::setColors(const QColor& normal, const QColor& current)
{
    m_normal = normal;
    m_highlight = current;
    update();
}

void AlphabetIndex::setCurrent(QChar letter)
{
    if (letter != m_current)
    {
        m_current = letter;
        update();
    }
}

QChar AlphabetIndex::letterOf(const QString& text)
{
    // "Émilie" files under E.
    const QString decomposed = text.trimmed().normalized(QString::NormalizationForm_D);
    if (decomposed.isEmpty())
    {
        return '#';
    }
    const QChar first = decomposed.at(0).toUpper();
    return first >= 'A' && first <= 'Z' ? first : QChar('#');
}

QChar AlphabetIndex::letterAt(int y) const
{
    const int index = qBound(0, y / kLetterPitch, static_cast<int>(m_letters.size()) - 1);
    return m_letters.at(index);
}

void AlphabetIndex::paintEvent(QPaintEvent*)
{
    QPainter p(this);
    QFont f = font();
    f.setPixelSize(10);
    for (int i = 0; i < m_letters.size(); ++i)
    {
        const bool current = m_letters.at(i) == m_current;
        f.setWeight(current ? QFont::DemiBold : QFont::Normal);
        p.setFont(f);
        p.setPen(current ? m_highlight : m_normal);
        p.drawText(QRect(0, i * kLetterPitch, width(), kLetterPitch), Qt::AlignCenter, QString(m_letters.at(i)));
    }
}

void AlphabetIndex::mousePressEvent(QMouseEvent* event)
{
    emit letterClicked(letterAt(event->position().toPoint().y()));
}

// --- Album grid --------------------------------------------------------------------

namespace {
constexpr int kGridGap = 18;
constexpr int kCardTextHeight = 8 + 18 + 16; // gap, title line, artist line
constexpr int kPanelMargin = 20;              // above and below the open album's panel
constexpr int kNotch = 7;                     // the panel's notch: 14 wide, 7 tall
}

AlbumGrid::AlbumGrid(CoverCache* covers, int columns, QWidget* parent)
    : QWidget(parent), m_covers(covers), m_columns(columns)
{
    m_t = theme::tokens(true);
    setMouseTracking(true);
    setFocusPolicy(Qt::ClickFocus); // so Esc reaches the page's shortcut
    connect(covers, &CoverCache::loaded, this, qOverload<>(&QWidget::update));
}

void AlbumGrid::setAlbums(const QVector<AlbumCard>& albums)
{
    m_albums = albums;
    m_hover = -1;
    if (m_open >= m_albums.size())
    {
        m_open = -1;
    }
    relayout();
    update();
}

void AlbumGrid::setColors(const theme::Tokens& tokens)
{
    m_t = tokens;
    update();
}

void AlbumGrid::setPanel(AlbumPanel* panel)
{
    m_panel = panel;
    panel->setParent(this);
    panel->hide();
    relayout();
}

void AlbumGrid::setOpenIndex(int index)
{
    m_open = index >= 0 && index < m_albums.size() ? index : -1;
    relayout();
    update();
}

int AlbumGrid::cardWidth() const
{
    return qMax(60, (width() - (m_columns - 1) * kGridGap) / m_columns);
}

QRect AlbumGrid::cardRect(int index) const
{
    const int row = index / m_columns;
    if (row >= m_rowTop.size())
    {
        return QRect();
    }
    const int w = cardWidth();
    return QRect((index % m_columns) * (w + kGridGap), m_rowTop[row], w, w + kCardTextHeight);
}

int AlbumGrid::openCardCenterX() const
{
    return m_open >= 0 ? cardRect(m_open).center().x() : 0;
}

void AlbumGrid::relayout()
{
    const int w = cardWidth();
    const int cardHeight = w + kCardTextHeight;
    const int rows = (m_albums.size() + m_columns - 1) / m_columns;
    const int openRow = m_open >= 0 ? m_open / m_columns : -1;
    m_rowTop.resize(rows);
    int y = 0;
    for (int row = 0; row < rows; ++row)
    {
        m_rowTop[row] = y;
        y += cardHeight;
        if (row == openRow && m_panel)
        {
            y += kPanelMargin;
            const int panelHeight = m_panel->heightForWidth(width());
            m_panel->setGeometry(0, y, width(), panelHeight);
            m_panel->setNotchX(cardRect(m_open).center().x());
            y += panelHeight + kPanelMargin;
        }
        else if (row + 1 < rows)
        {
            y += kGridGap;
        }
    }
    if (m_panel)
    {
        m_panel->setVisible(openRow >= 0);
    }
    m_height = y + 24;
    setMinimumHeight(m_height);
    updateGeometry();
}

QSize AlbumGrid::sizeHint() const
{
    return QSize(600, m_height);
}

void AlbumGrid::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    if (event->oldSize().width() != width())
    {
        relayout();
    }
}

void AlbumGrid::paintEvent(QPaintEvent* event)
{
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const QFont titleFont = PixelFont(font(), 13, QFont::Medium);
    const QFont subtitleFont = PixelFont(font(), 12);
    for (int i = 0; i < m_albums.size(); ++i)
    {
        const QRect card = cardRect(i);
        if (!card.intersects(event->rect()))
        {
            continue;
        }
        const AlbumCard& album = m_albums[i];
        const QRect cover(card.left(), card.top(), card.width(), card.width());
        p.drawPixmap(cover, m_covers->cover(album.coverPath, card.width(), 8, album.name, theme::Icon::Album));
        if (i == m_hover && i != m_open)
        {
            p.setPen(Qt::NoPen);
            p.setBrush(QColor(255, 255, 255, 18));
            p.drawRoundedRect(QRectF(cover), 8, 8);
        }
        if (i == m_open)
        {
            p.setPen(QPen(m_t.accent, 2));
            p.setBrush(Qt::NoBrush);
            p.drawRoundedRect(QRectF(cover).adjusted(1, 1, -1, -1), 7, 7);
        }
        p.setFont(titleFont);
        p.setPen(i == m_open ? m_t.accent : m_t.text);
        DrawElided(&p, QRect(card.left(), cover.bottom() + 9, card.width(), 18), Qt::AlignLeft | Qt::AlignVCenter, album.name);
        p.setFont(subtitleFont);
        p.setPen(m_t.muted);
        DrawElided(&p, QRect(card.left(), cover.bottom() + 27, card.width(), 16), Qt::AlignLeft | Qt::AlignVCenter,
                   album.subtitle);
    }
}

int AlbumGrid::indexAt(const QPoint& pos) const
{
    for (int i = 0; i < m_albums.size(); ++i)
    {
        if (cardRect(i).contains(pos))
        {
            return i;
        }
    }
    return -1;
}

void AlbumGrid::mouseMoveEvent(QMouseEvent* event)
{
    const int index = indexAt(event->position().toPoint());
    if (index != m_hover)
    {
        m_hover = index;
        setCursor(index >= 0 ? Qt::PointingHandCursor : Qt::ArrowCursor);
        setToolTip(index >= 0 ? m_albums[index].name : QString());
        update();
    }
}

void AlbumGrid::mousePressEvent(QMouseEvent* event)
{
    if (event->button() == Qt::LeftButton)
    {
        const int index = indexAt(event->position().toPoint());
        if (index >= 0)
        {
            emit cardClicked(index);
        }
    }
    QWidget::mousePressEvent(event);
}

void AlbumGrid::mouseDoubleClickEvent(QMouseEvent* event)
{
    const int index = indexAt(event->position().toPoint());
    if (index >= 0 && event->button() == Qt::LeftButton)
    {
        emit cardDoubleClicked(index);
    }
}

void AlbumGrid::leaveEvent(QEvent*)
{
    if (m_hover != -1)
    {
        m_hover = -1;
        update();
    }
}

void AlbumGrid::contextMenuEvent(QContextMenuEvent* event)
{
    const int index = indexAt(event->pos());
    if (index >= 0)
    {
        emit cardMenuRequested(index, event->globalPos());
    }
}

// --- Album panel -------------------------------------------------------------------

namespace {
constexpr int kPanelPadding = 18;
constexpr int kPanelCover = 150;
constexpr int kPanelHeaderHeight = 56; // title + details, above the track list
constexpr int kTrackRowHeight = 26;
constexpr int kTrackColumnGap = 28;
}

AlbumPanel::AlbumPanel(CoverCache* covers, QWidget* parent) : QWidget(parent), m_covers(covers)
{
    m_t = theme::tokens(true);
    setMouseTracking(true);
    connect(covers, &CoverCache::loaded, this, qOverload<>(&QWidget::update));

    m_title = new QLabel(this);
    m_title->setFont(PixelFont(font(), 22, QFont::Medium));
    m_details = new QLabel(this);
    m_details->setObjectName("SoftLabel");
    m_details->setFont(PixelFont(font(), 13));

    m_shuffle = new QPushButton(tr("Shuffle"), this);
    m_play = new QPushButton(tr("Play"), this);
    m_play->setObjectName("PrimaryButton");
    m_more = new QPushButton(this);
    m_more->setObjectName("IconButton");
    m_more->setFixedSize(32, 32);
    m_more->setIconSize(QSize(16, 16));
    m_more->setToolTip(tr("More"));
    m_more->setAccessibleName(tr("More"));
    for (QPushButton* button : {m_shuffle, m_play, m_more})
    {
        button->setCursor(Qt::PointingHandCursor);
        button->setFocusPolicy(Qt::NoFocus);
    }
    connect(m_shuffle, &QPushButton::clicked, this, &AlbumPanel::shuffleClicked);
    connect(m_play, &QPushButton::clicked, this, &AlbumPanel::playClicked);
    connect(m_more, &QPushButton::clicked, this, [this]() {
        emit moreClicked(m_more->mapToGlobal(QPoint(0, m_more->height())));
    });
}

void AlbumPanel::setColors(const theme::Tokens& tokens, const QIcon& play, const QIcon& shuffle, const QIcon& more)
{
    m_t = tokens;
    m_play->setIcon(play);
    m_shuffle->setIcon(shuffle);
    m_more->setIcon(more);
    update();
}

void AlbumPanel::setAlbum(const QString& title, const QString& details, const QVector<TrackInfo>& tracks)
{
    m_title->setText(title);
    m_title->setToolTip(title);
    m_details->setText(details);
    m_tracks = tracks;
    m_hoverTrack = -1;
    QResizeEvent resize(size(), size());
    QApplication::sendEvent(this, &resize);
    update();
}

void AlbumPanel::setPlayingPath(const QString& path)
{
    if (path != m_playingPath)
    {
        m_playingPath = path;
        update();
    }
}

void AlbumPanel::setNotchX(int x)
{
    m_notchX = x;
    // Lay the header out for the current width (the grid sets the geometry first).
    const QRect content = rect().adjusted(kPanelPadding, kNotch + kPanelPadding, -kPanelPadding, -kPanelPadding);
    const int textLeft = content.left() + kPanelCover + 22;
    int buttonsRight = content.right() + 1;
    m_more->move(buttonsRight - m_more->width(), content.top());
    buttonsRight -= m_more->width() + 6;
    m_play->adjustSize();
    m_play->move(buttonsRight - m_play->width(), content.top());
    buttonsRight -= m_play->width() + 8;
    m_shuffle->adjustSize();
    m_shuffle->move(buttonsRight - m_shuffle->width(), content.top());
    buttonsRight -= m_shuffle->width() + 12;
    const int textWidth = qMax(40, buttonsRight - textLeft);
    m_title->setGeometry(textLeft, content.top() - 2, textWidth, 28);
    m_title->setText(m_title->fontMetrics().elidedText(m_title->toolTip(), Qt::ElideRight, textWidth));
    m_details->setGeometry(textLeft, content.top() + 28, textWidth, 18);
    update();
}

int AlbumPanel::rowCount() const
{
    return qMax(1, static_cast<int>((m_tracks.size() + 1) / 2));
}

int AlbumPanel::heightForWidth(int) const
{
    return kNotch + 2 * kPanelPadding + qMax(kPanelCover, kPanelHeaderHeight + rowCount() * kTrackRowHeight);
}

QRect AlbumPanel::trackListRect() const
{
    const int left = kPanelPadding + kPanelCover + 22;
    return QRect(left, kNotch + kPanelPadding + kPanelHeaderHeight, width() - kPanelPadding - left,
                 rowCount() * kTrackRowHeight);
}

int AlbumPanel::trackAt(const QPoint& pos) const
{
    const QRect list = trackListRect();
    if (!list.contains(pos))
    {
        return -1;
    }
    const int columnWidth = (list.width() - kTrackColumnGap) / 2;
    const int x = pos.x() - list.left();
    int column = 0;
    if (x > columnWidth + kTrackColumnGap)
    {
        column = 1;
    }
    else if (x > columnWidth)
    {
        return -1; // the gap
    }
    const int index = column * rowCount() + (pos.y() - list.top()) / kTrackRowHeight;
    return index < m_tracks.size() ? index : -1;
}

void AlbumPanel::paintEvent(QPaintEvent*)
{
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);

    // The panel, with a notch on its top edge pointing at the open card.
    const QRectF box = QRectF(rect()).adjusted(0.5, kNotch + 0.5, -0.5, -0.5);
    QPainterPath shape;
    shape.addRoundedRect(box, 14, 14);
    QPainterPath notch;
    const qreal nx = qBound<qreal>(box.left() + 22, m_notchX, box.right() - 22);
    notch.moveTo(nx - kNotch, box.top() + 1);
    notch.lineTo(nx, box.top() - kNotch + 0.5);
    notch.lineTo(nx + kNotch, box.top() + 1);
    notch.closeSubpath();
    shape = shape.united(notch);
    p.setPen(QPen(m_t.border, 1));
    p.setBrush(m_t.surface);
    p.drawPath(shape);

    const QRect cover(kPanelPadding, kNotch + kPanelPadding, kPanelCover, kPanelCover);
    const QString coverPath = m_tracks.isEmpty() ? QString() : m_tracks.first().path;
    p.drawPixmap(cover, m_covers->cover(coverPath, kPanelCover, 8, m_title->toolTip(), theme::Icon::Album));

    // The tracks, two columns filled top to bottom.
    const QRect list = trackListRect();
    const int rows = rowCount();
    const int columnWidth = (list.width() - kTrackColumnGap) / 2;
    const QFont numberFont = Tabular(PixelFont(font(), 11));
    const QFont titleFont = PixelFont(font(), 13);
    const QFont timeFont = Tabular(PixelFont(font(), 12));
    for (int i = 0; i < m_tracks.size(); ++i)
    {
        const TrackInfo& track = m_tracks[i];
        const QRect row(list.left() + (i / rows) * (columnWidth + kTrackColumnGap),
                        list.top() + (i % rows) * kTrackRowHeight, columnWidth, kTrackRowHeight);
        const bool playing = track.path == m_playingPath;
        if (i == m_hoverTrack)
        {
            p.setPen(Qt::NoPen);
            p.setBrush(m_t.surfaceHi);
            p.drawRoundedRect(QRectF(row).adjusted(-6, 1, 6, -1), 6, 6);
        }
        p.setFont(numberFont);
        p.setPen(playing ? m_t.accent : m_t.muted);
        p.drawText(QRect(row.left(), row.top(), 20, row.height()), Qt::AlignRight | Qt::AlignVCenter, QString::number(i + 1));
        const QString time = track.durationSeconds > 0 ? FormatDuration(static_cast<int>(track.durationSeconds)) : QString();
        p.setFont(timeFont);
        p.setPen(m_t.muted);
        const int timeWidth = p.fontMetrics().horizontalAdvance(time);
        p.drawText(QRect(row.right() - timeWidth, row.top(), timeWidth + 1, row.height()), Qt::AlignRight | Qt::AlignVCenter, time);
        p.setFont(titleFont);
        p.setPen(playing ? m_t.accent : m_t.text);
        const QString title = track.title.isEmpty() ? QFileInfo(track.path).completeBaseName() : track.title;
        DrawElided(&p, QRect(row.left() + 30, row.top(), row.width() - 30 - timeWidth - 10, row.height()),
                   Qt::AlignLeft | Qt::AlignVCenter, title);
    }
}

void AlbumPanel::mouseMoveEvent(QMouseEvent* event)
{
    const int index = trackAt(event->position().toPoint());
    if (index != m_hoverTrack)
    {
        m_hoverTrack = index;
        setCursor(index >= 0 ? Qt::PointingHandCursor : Qt::ArrowCursor);
        setToolTip(index >= 0 ? tr("Double-click to play") : QString());
        update();
    }
}

void AlbumPanel::mouseDoubleClickEvent(QMouseEvent* event)
{
    const int index = trackAt(event->position().toPoint());
    if (index >= 0)
    {
        emit trackActivated(index);
    }
}

void AlbumPanel::leaveEvent(QEvent*)
{
    if (m_hoverTrack != -1)
    {
        m_hoverTrack = -1;
        update();
    }
}

// --- Artist rows -------------------------------------------------------------------

ArtistRowDelegate::ArtistRowDelegate(CoverCache* covers, QObject* parent)
    : QStyledItemDelegate(parent), m_covers(covers), m_t(theme::tokens(true))
{
}

QSize ArtistRowDelegate::sizeHint(const QStyleOptionViewItem&, const QModelIndex&) const
{
    return QSize(200, 50);
}

void ArtistRowDelegate::paint(QPainter* p, const QStyleOptionViewItem& option, const QModelIndex& index) const
{
    p->save();
    p->setRenderHint(QPainter::Antialiasing);
    const QRect r = option.rect;
    const bool selected = option.state & QStyle::State_Selected;
    if (selected || (option.state & QStyle::State_MouseOver))
    {
        p->setPen(Qt::NoPen);
        p->setBrush(selected ? m_t.surfaceHi : m_t.surface);
        p->drawRoundedRect(QRectF(r), 8, 8);
    }
    const QString name = index.data().toString();
    const QRect avatar(r.left() + 8, r.center().y() - 17, 34, 34);
    p->drawPixmap(avatar, m_covers->cover(QString(), 34, 17, name, theme::Icon::Artist));
    const int textLeft = avatar.right() + 13;
    const QFont nameFont = PixelFont(option.font, 13);
    const QFont metaFont = PixelFont(option.font, 11);
    const int nameHeight = QFontMetrics(nameFont).height();
    const int metaHeight = QFontMetrics(metaFont).height();
    const int top = r.center().y() - (nameHeight + metaHeight + 1) / 2;
    p->setFont(nameFont);
    p->setPen(selected ? m_t.accent : m_t.text);
    DrawElided(p, QRect(textLeft, top, r.right() - 8 - textLeft, nameHeight), Qt::AlignLeft | Qt::AlignVCenter, name);
    p->setFont(metaFont);
    p->setPen(m_t.muted);
    DrawElided(p, QRect(textLeft, top + nameHeight + 1, r.right() - 8 - textLeft, metaHeight), Qt::AlignLeft | Qt::AlignVCenter,
               index.data(kArtistMetaRole).toString());
    p->restore();
}

// --- Most played -------------------------------------------------------------------

namespace {
constexpr int kRankedRowHeight = 42;
}

MostPlayedList::MostPlayedList(CoverCache* covers, QWidget* parent) : QWidget(parent), m_covers(covers)
{
    m_t = theme::tokens(true);
    setMouseTracking(true);
    connect(covers, &CoverCache::loaded, this, qOverload<>(&QWidget::update));
}

void MostPlayedList::setColors(const theme::Tokens& tokens)
{
    m_t = tokens;
    update();
}

void MostPlayedList::setTracks(const QVector<RankedTrack>& tracks)
{
    m_tracks = tracks;
    m_hover = -1;
    setFixedHeight(qMax(1, static_cast<int>(m_tracks.size())) * kRankedRowHeight);
    update();
}

void MostPlayedList::setPlayingPath(const QString& path)
{
    if (path != m_playingPath)
    {
        m_playingPath = path;
        update();
    }
}

QSize MostPlayedList::sizeHint() const
{
    return QSize(400, qMax(1, static_cast<int>(m_tracks.size())) * kRankedRowHeight);
}

int MostPlayedList::rowAt(const QPoint& pos) const
{
    const int row = pos.y() / kRankedRowHeight;
    return pos.y() >= 0 && row < m_tracks.size() ? row : -1;
}

void MostPlayedList::paintEvent(QPaintEvent*)
{
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const QFont rankFont = Tabular(PixelFont(font(), 12));
    const QFont titleFont = PixelFont(font(), 13);
    const QFont smallFont = Tabular(PixelFont(font(), 12));
    for (int i = 0; i < m_tracks.size(); ++i)
    {
        const TrackInfo& track = m_tracks[i].track;
        const QRect row(0, i * kRankedRowHeight, width(), kRankedRowHeight);
        const bool playing = track.path == m_playingPath;
        if (playing || i == m_hover)
        {
            p.setPen(Qt::NoPen);
            p.setBrush(i == m_hover && !playing ? m_t.surface : m_t.surface);
            p.drawRoundedRect(QRectF(row).adjusted(0, 1, 0, -1), 8, 8);
        }
        p.fillRect(QRect(row.left(), row.bottom(), row.width(), 1), WithAlpha(m_t.text, 0.07));
        const QRect inner = row.adjusted(10, 0, -10, 0);
        p.setFont(rankFont);
        p.setPen(m_t.muted);
        p.drawText(QRect(inner.left(), row.top(), 16, row.height()), Qt::AlignRight | Qt::AlignVCenter, QString::number(i + 1));

        const int timeWidth = 44;
        const int playsWidth = 90;
        const int restLeft = inner.left() + 30;
        const int restWidth = inner.width() - 30 - timeWidth - playsWidth;
        const int titleWidth = restWidth * 55 / 100;
        const QString title = track.title.isEmpty() ? QFileInfo(track.path).completeBaseName() : track.title;
        const QRect cover(restLeft, row.center().y() - 14, 28, 28);
        p.drawPixmap(cover, m_covers->cover(track.path, 28, 4, title));
        p.setFont(titleFont);
        p.setPen(playing ? m_t.accent : m_t.text);
        DrawElided(&p, QRect(cover.right() + 13, row.top(), titleWidth - 41, row.height()), Qt::AlignLeft | Qt::AlignVCenter, title);
        p.setPen(m_t.textSoft);
        DrawElided(&p, QRect(restLeft + titleWidth + 12, row.top(), restWidth - titleWidth - 12, row.height()),
                   Qt::AlignLeft | Qt::AlignVCenter, track.album);
        p.setFont(smallFont);
        p.setPen(m_t.muted);
        p.drawText(QRect(inner.right() - timeWidth - playsWidth, row.top(), playsWidth - 12, row.height()),
                   Qt::AlignRight | Qt::AlignVCenter,
                   m_tracks[i].plays == 1 ? tr("1 play") : tr("%1 plays").arg(m_tracks[i].plays));
        p.setFont(PixelFont(smallFont, 13));
        p.setPen(m_t.textSoft);
        p.drawText(QRect(inner.right() - timeWidth, row.top(), timeWidth, row.height()), Qt::AlignRight | Qt::AlignVCenter,
                   track.durationSeconds > 0 ? FormatDuration(static_cast<int>(track.durationSeconds)) : QString());
    }
}

void MostPlayedList::mouseMoveEvent(QMouseEvent* event)
{
    const int row = rowAt(event->position().toPoint());
    if (row != m_hover)
    {
        m_hover = row;
        setCursor(row >= 0 ? Qt::PointingHandCursor : Qt::ArrowCursor);
        update();
    }
}

void MostPlayedList::mouseDoubleClickEvent(QMouseEvent* event)
{
    const int row = rowAt(event->position().toPoint());
    if (row >= 0)
    {
        emit trackActivated(row);
    }
}

void MostPlayedList::leaveEvent(QEvent*)
{
    if (m_hover != -1)
    {
        m_hover = -1;
        update();
    }
}

void MostPlayedList::contextMenuEvent(QContextMenuEvent* event)
{
    const int row = rowAt(event->pos());
    if (row >= 0)
    {
        emit trackMenuRequested(row, event->globalPos());
    }
}

QString FormatLongDuration(double seconds)
{
    const qint64 total = static_cast<qint64>(seconds);
    const qint64 days = total / 86400;
    const qint64 hours = total % 86400 / 3600;
    const qint64 minutes = total % 3600 / 60;
    if (days > 0)
    {
        return (days == 1 ? QObject::tr("1 day") : QObject::tr("%1 days").arg(days)) + ' '
            + (hours == 1 ? QObject::tr("1 hour") : QObject::tr("%1 hours").arg(hours));
    }
    if (hours > 0)
    {
        return QObject::tr("%1 h %2 min").arg(hours).arg(minutes);
    }
    return QObject::tr("%1 min").arg(minutes);
}

} // namespace audioforge
