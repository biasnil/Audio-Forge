#include "audioforge/widgets/up_next_panel.hpp"
#include "audioforge/theme.hpp"
#include "audioforge/track_metadata.hpp"

#include <QAbstractItemModel>
#include <QEvent>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QLabel>
#include <QListWidget>
#include <QMenu>
#include <QPainter>
#include <QPushButton>
#include <QScrollArea>
#include <QStyledItemDelegate>
#include <QTimer>
#include <QVBoxLayout>

namespace audioforge {

namespace {

constexpr int kRowHeight = 56;
constexpr int kCoverSize = 40;
constexpr int kDataRole = Qt::UserRole;          // Playing Next position / list index
constexpr int kArtistRole = Qt::UserRole + 1;
constexpr int kDurationRole = Qt::UserRole + 2;
constexpr int kCurrentRole = Qt::UserRole + 3;
constexpr int kPathRole = Qt::UserRole + 4;
constexpr int kDimRole = Qt::UserRole + 5;         // History rows are drawn faded
constexpr int kMaxUpcomingCovers = 60;           // covers further down stay placeholders (cheap to show 300 rows)


// One track row: cover, title over artist, length on the right -- the iOS SongRow.
class TrackRowDelegate : public QStyledItemDelegate
{
public:
    using QStyledItemDelegate::QStyledItemDelegate;

    // Set explicitly: the list's stylesheet (selection-background-color) repurposes
    // the palette's Highlight role, so it can't carry the accent.
    static inline QColor accent = QColor(240, 195, 164);

    QSize sizeHint(const QStyleOptionViewItem&, const QModelIndex&) const override
    {
        return QSize(200, kRowHeight);
    }

    void paint(QPainter* p, const QStyleOptionViewItem& option, const QModelIndex& index) const override
    {
        p->save();
        p->setRenderHint(QPainter::Antialiasing);
        if (index.data(kDimRole).toBool())
        {
            p->setOpacity(0.55);
        }
        // Active-group colors even while the window isn't focused, so the current track keeps its accent.
        QPalette pal = option.palette;
        pal.setCurrentColorGroup(QPalette::Active);
        const QRect r = option.rect.adjusted(4, 2, -4, -2);

        if (option.state & (QStyle::State_MouseOver | QStyle::State_Selected))
        {
            p->setPen(Qt::NoPen);
            p->setBrush(pal.color(QPalette::Active, QPalette::AlternateBase));
            p->drawRoundedRect(r, 8, 8);
        }

        const QRect cover(r.left() + 8, r.center().y() - kCoverSize / 2, kCoverSize, kCoverSize);
        const QPixmap pixmap = index.data(Qt::DecorationRole).value<QPixmap>();
        if (!pixmap.isNull())
        {
            p->drawPixmap(cover, pixmap);
        }

        const bool current = index.data(kCurrentRole).toBool();
        const QString duration = index.data(kDurationRole).toString();
        const int durationWidth = duration.isEmpty() ? 0 : option.fontMetrics.horizontalAdvance(duration) + 12;
        const int textLeft = cover.right() + 12;
        const int textWidth = r.right() - 10 - durationWidth - textLeft;

        QFont titleFont = option.font;
        if (current)
        {
            titleFont.setBold(true);
        }
        p->setFont(titleFont);
        p->setPen(current ? accent : pal.color(QPalette::Active, QPalette::Text));
        const QFontMetrics titleMetrics = p->fontMetrics(); // the painter's own metrics, so eliding matches what's drawn
        const QRect titleRect(textLeft, r.top() + 8, textWidth, titleMetrics.height());
        p->drawText(titleRect, Qt::AlignLeft | Qt::AlignVCenter,
                    titleMetrics.elidedText(index.data(Qt::DisplayRole).toString(), Qt::ElideRight, textWidth));

        QFont smallFont = option.font;
        smallFont.setPointSizeF(option.font.pointSizeF() - 1.0);
        p->setFont(smallFont);
        p->setPen(pal.color(QPalette::Active, QPalette::PlaceholderText));
        const QFontMetrics smallMetrics = p->fontMetrics();
        const QRect artistRect(textLeft, titleRect.bottom() + 2, textWidth, smallMetrics.height());
        p->drawText(artistRect, Qt::AlignLeft | Qt::AlignVCenter,
                    smallMetrics.elidedText(index.data(kArtistRole).toString(), Qt::ElideRight, textWidth));

        if (!duration.isEmpty())
        {
            p->drawText(QRect(r.right() - 10 - durationWidth, r.top(), durationWidth, r.height()),
                        Qt::AlignRight | Qt::AlignVCenter, duration);
        }
        p->restore();
    }
};

QLabel* SectionTitle(const QString& text)
{
    auto* label = new QLabel(text);
    label->setObjectName("SettingsSectionTitle");
    return label;
}

QPixmap Thumbnail(const QByteArray& bytes)
{
    QPixmap pix;
    if (bytes.isEmpty() || !pix.loadFromData(bytes))
    {
        return QPixmap();
    }
    const QPixmap scaled = pix.scaled(kCoverSize * 2, kCoverSize * 2, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
    QPixmap cropped = scaled.copy((scaled.width() - kCoverSize * 2) / 2, (scaled.height() - kCoverSize * 2) / 2,
                                  kCoverSize * 2, kCoverSize * 2);
    cropped.setDevicePixelRatio(2.0); // 2x source, crisp on HiDPI
    return theme::roundedPixmap(cropped, 6);
}

} // namespace

UpNextPanel::UpNextPanel(QWidget* parent) : QFrame(parent)
{
    setObjectName("QueuePanel");
    setFixedWidth(360);

    auto* outer = new QVBoxLayout(this);
    outer->setContentsMargins(0, 0, 0, 0);
    outer->setSpacing(0);

    auto* header = new QHBoxLayout();
    header->setContentsMargins(20, 18, 12, 6);
    auto* title = new QLabel("Up Next");
    title->setObjectName("PanelTitle");
    header->addWidget(title);
    header->addStretch();

    m_moreButton = new QPushButton();
    m_moreButton->setObjectName("IconButton");
    m_moreButton->setFixedSize(32, 32);
    m_moreButton->setIconSize(QSize(18, 18));
    m_moreButton->setToolTip("More");
    m_moreButton->setFocusPolicy(Qt::NoFocus);
    m_moreButton->setCursor(Qt::PointingHandCursor);
    connect(m_moreButton, &QPushButton::clicked, this, [this]() {
        QMenu menu(this);
        menu.addAction("Save as Playlist...", this, &UpNextPanel::saveAsPlaylistRequested);
        QAction* clear = menu.addAction("Clear Playing Next", this, &UpNextPanel::clearPlayingNextRequested);
        clear->setEnabled(m_playingNextList->count() > 0);
        menu.exec(m_moreButton->mapToGlobal(QPoint(0, m_moreButton->height())));
    });
    header->addWidget(m_moreButton);

    m_closeButton = new QPushButton();
    m_closeButton->setObjectName("IconButton");
    m_closeButton->setFixedSize(32, 32);
    m_closeButton->setIconSize(QSize(16, 16));
    m_closeButton->setToolTip("Close");
    m_closeButton->setFocusPolicy(Qt::NoFocus);
    m_closeButton->setCursor(Qt::PointingHandCursor);
    connect(m_closeButton, &QPushButton::clicked, this, &UpNextPanel::closeRequested);
    header->addWidget(m_closeButton);
    outer->addLayout(header);

    auto* scroll = new QScrollArea();
    scroll->setWidgetResizable(true);
    scroll->setFrameShape(QFrame::NoFrame);
    scroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    auto* content = new QWidget();
    auto* l = new QVBoxLayout(content);
    l->setContentsMargins(12, 0, 12, 16);
    l->setSpacing(0);
    scroll->setWidget(content);
    outer->addWidget(scroll, 1);

    m_historyTitle = SectionTitle("History");
    l->addWidget(m_historyTitle);
    m_historyList = makeList(false);
    l->addWidget(m_historyList);

    m_nowPlayingTitle = SectionTitle("Now Playing");
    l->addWidget(m_nowPlayingTitle);
    m_nowPlayingList = makeList(false);
    l->addWidget(m_nowPlayingList);

    m_playingNextHeader = new QWidget();
    auto* pnLayout = new QHBoxLayout(m_playingNextHeader);
    pnLayout->setContentsMargins(0, 0, 8, 0);
    pnLayout->addWidget(SectionTitle("Playing Next"));
    pnLayout->addStretch();
    auto* clearButton = new QPushButton("Clear");
    clearButton->setObjectName("LinkButton");
    clearButton->setCursor(Qt::PointingHandCursor);
    clearButton->setFocusPolicy(Qt::NoFocus);
    connect(clearButton, &QPushButton::clicked, this, &UpNextPanel::clearPlayingNextRequested);
    pnLayout->addWidget(clearButton, 0, Qt::AlignBottom);
    l->addWidget(m_playingNextHeader);
    m_playingNextList = makeList(true);
    l->addWidget(m_playingNextList);

    m_contextTitle = SectionTitle("Next From Your List");
    l->addWidget(m_contextTitle);
    m_upcomingNote = new QLabel();
    m_upcomingNote->setObjectName("SettingsValue");
    m_upcomingNote->setWordWrap(true);
    m_upcomingNote->setContentsMargins(12, 6, 12, 6);
    l->addWidget(m_upcomingNote);
    m_upcomingList = makeList(false);
    l->addWidget(m_upcomingList);

    m_footer = new QLabel("Smart shuffle: every song plays once, in this order.");
    m_footer->setObjectName("SettingsFooter");
    m_footer->setWordWrap(true);
    l->addWidget(m_footer);
    l->addStretch();

    // Drag-reordering Playing Next: the list moves the row itself; the queue follows.
    connect(m_playingNextList->model(), &QAbstractItemModel::rowsMoved, this,
            [this](const QModelIndex&, int start, int, const QModelIndex&, int row) {
        if (m_rebuilding)
        {
            return;
        }
        const int to = row > start ? row - 1 : row;
        // Re-emitted on the next event-loop turn: PlayerWindow rebuilds this
        // list in response, which mustn't happen inside the model's own move.
        QTimer::singleShot(0, this, [this, start, to]() { emit movePlayingNext(start, to); });
    });

    m_coverTimer = new QTimer(this);
    m_coverTimer->setInterval(0);
    connect(m_coverTimer, &QTimer::timeout, this, &UpNextPanel::loadNextCovers);
}

QListWidget* UpNextPanel::makeList(bool reorderable)
{
    auto* list = new QListWidget();
    list->setItemDelegate(new TrackRowDelegate(list));
    list->setFrameShape(QFrame::NoFrame);
    list->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    list->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    list->setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    list->setMouseTracking(true);
    list->setSelectionMode(QAbstractItemView::SingleSelection);
    list->setContextMenuPolicy(Qt::CustomContextMenu);
    list->installEventFilter(this);
    if (reorderable)
    {
        list->setDragDropMode(QAbstractItemView::InternalMove);
        list->setDefaultDropAction(Qt::MoveAction);
    }
    connect(list, &QListWidget::customContextMenuRequested, this, [this, list](const QPoint& pos) {
        showRowMenu(list, pos);
    });
    connect(list, &QListWidget::itemDoubleClicked, this, [this, list](QListWidgetItem* item) {
        const int data = item->data(kDataRole).toInt();
        if (list == m_historyList)
        {
            emit playHistoryNow(data);
        }
        else if (list == m_playingNextList)
        {
            emit jumpToPlayingNext(m_playingNextList->row(item)); // row, not stored data: a drag may have moved it
        }
        else if (list == m_upcomingList)
        {
            emit jumpToListItem(data);
        }
    });
    return list;
}

QListWidgetItem* UpNextPanel::makeItem(const TrackInfo& track, bool isCurrent, int data)
{
    auto* item = new QListWidgetItem(track.title);
    item->setData(kArtistRole, track.artist.isEmpty() ? QStringLiteral("Unknown Artist") : track.artist);
    item->setData(kDurationRole, track.durationSeconds > 0.0 ? FormatDuration(static_cast<int>(track.durationSeconds + 0.5)) : QString());
    item->setData(kCurrentRole, isCurrent);
    item->setData(kPathRole, track.path);
    item->setData(kDataRole, data);
    item->setToolTip(track.title + (track.artist.isEmpty() ? QString() : " — " + track.artist));
    auto cached = m_covers.constFind(track.path);
    item->setData(Qt::DecorationRole, cached != m_covers.constEnd() && !cached->isNull() ? *cached : m_placeholder);
    return item;
}

void UpNextPanel::fitHeight(QListWidget* list)
{
    list->setFixedHeight(list->count() * kRowHeight + 4);
}

void UpNextPanel::setContent(const UpNextContent& content)
{
    m_rebuilding = true;
    m_coverQueue.clear();
    auto wantCover = [this](const QString& path) {
        if (!m_covers.contains(path) && !m_coverQueue.contains(path))
        {
            m_coverQueue << path;
        }
    };

    m_historyList->clear();
    for (int i = 0; i < content.history.size(); ++i)
    {
        QListWidgetItem* item = makeItem(content.history[i], false, i);
        item->setData(kDimRole, true);
        m_historyList->addItem(item);
        wantCover(content.history[i].path);
    }
    m_historyTitle->setVisible(!content.history.isEmpty());
    m_historyList->setVisible(!content.history.isEmpty());
    fitHeight(m_historyList);

    m_nowPlayingList->clear();
    if (content.current)
    {
        m_nowPlayingList->addItem(makeItem(*content.current, true, -1));
        wantCover(content.current->path);
    }
    m_nowPlayingTitle->setVisible(content.current.has_value());
    m_nowPlayingList->setVisible(content.current.has_value());
    fitHeight(m_nowPlayingList);

    m_playingNextList->clear();
    for (int i = 0; i < content.playingNext.size(); ++i)
    {
        m_playingNextList->addItem(makeItem(content.playingNext[i], false, i));
        wantCover(content.playingNext[i].path);
    }
    const bool hasPlayingNext = !content.playingNext.isEmpty();
    m_playingNextHeader->setVisible(hasPlayingNext);
    m_playingNextList->setVisible(hasPlayingNext);
    fitHeight(m_playingNextList);

    m_contextTitle->setText(content.contextName.isEmpty() ? QStringLiteral("Next From Your List")
                                                          : "Next From " + content.contextName);
    m_upcomingList->clear();
    if (content.upcoming)
    {
        for (int i = 0; i < content.upcoming->size(); ++i)
        {
            const auto& [index, track] = content.upcoming->at(i);
            m_upcomingList->addItem(makeItem(track, false, index));
            if (i < kMaxUpcomingCovers)
            {
                wantCover(track.path);
            }
        }
        m_upcomingNote->setText(content.repeating ? "Repeating." : "Nothing after this song.");
        m_upcomingNote->setVisible(content.upcoming->isEmpty() && content.current.has_value());
    }
    else
    {
        m_upcomingNote->setText("Random shuffle picks each next song when it's needed.");
        m_upcomingNote->setVisible(true);
    }
    m_upcomingList->setVisible(m_upcomingList->count() > 0);
    fitHeight(m_upcomingList);
    m_footer->setVisible(content.smartShuffle && content.upcoming && !content.upcoming->isEmpty());

    m_rebuilding = false;
    if (!m_coverQueue.isEmpty())
    {
        m_coverTimer->start();
    }
}

void UpNextPanel::setPlaceholderCover(const QPixmap& pixmap)
{
    m_placeholder = pixmap;
    for (QListWidget* list : {m_historyList, m_nowPlayingList, m_playingNextList, m_upcomingList})
    {
        for (int i = 0; i < list->count(); ++i)
        {
            QListWidgetItem* item = list->item(i);
            const QPixmap cover = m_covers.value(item->data(kPathRole).toString());
            item->setData(Qt::DecorationRole, cover.isNull() ? m_placeholder : cover);
        }
    }
}

void UpNextPanel::setAccentColor(const QColor& color)
{
    TrackRowDelegate::accent = color;
    update();
}

void UpNextPanel::setHeaderIcons(const QIcon& more, const QIcon& close)
{
    m_moreButton->setIcon(more);
    m_closeButton->setIcon(close);
}

// A few covers per event-loop turn, so a long queue never stalls the UI.
void UpNextPanel::loadNextCovers()
{
    for (int n = 0; n < 4 && !m_coverQueue.isEmpty(); ++n)
    {
        const QString path = m_coverQueue.takeFirst();
        const QPixmap thumb = Thumbnail(ReadCoverArt(path));
        m_covers.insert(path, thumb);
        if (thumb.isNull())
        {
            continue;
        }
        for (QListWidget* list : {m_historyList, m_nowPlayingList, m_playingNextList, m_upcomingList})
        {
            for (int i = 0; i < list->count(); ++i)
            {
                if (list->item(i)->data(kPathRole).toString() == path)
                {
                    list->item(i)->setData(Qt::DecorationRole, thumb);
                }
            }
        }
    }
    if (m_coverQueue.isEmpty())
    {
        m_coverTimer->stop();
    }
}

void UpNextPanel::showRowMenu(QListWidget* list, const QPoint& pos)
{
    QListWidgetItem* item = list->itemAt(pos);
    if (!item || list == m_nowPlayingList)
    {
        return;
    }
    const int row = list->row(item);
    const int data = item->data(kDataRole).toInt();

    QMenu menu(this);
    if (list == m_historyList)
    {
        menu.addAction("Play Now", this, [this, data]() { emit playHistoryNow(data); });
        menu.addAction("Play Next", this, [this, data]() { emit playHistoryNext(data); });
        menu.addAction("Add to Queue", this, [this, data]() { emit addHistoryToQueue(data); });
    }
    else if (list == m_playingNextList)
    {
        menu.addAction("Play Now", this, [this, row]() { emit jumpToPlayingNext(row); });
        menu.addSeparator();
        QAction* up = menu.addAction("Move Up", this, [this, row]() { emit movePlayingNext(row, row - 1); });
        up->setEnabled(row > 0);
        QAction* down = menu.addAction("Move Down", this, [this, row]() { emit movePlayingNext(row, row + 1); });
        down->setEnabled(row < list->count() - 1);
        menu.addSeparator();
        menu.addAction("Remove from Queue", this, [this, row]() { emit removePlayingNext(row); });
    }
    else
    {
        menu.addAction("Play Now", this, [this, data]() { emit jumpToListItem(data); });
        menu.addSeparator();
        menu.addAction("Remove from Up Next", this, [this, data]() { emit removeListItem(data); });
    }
    menu.exec(list->viewport()->mapToGlobal(pos));
}

bool UpNextPanel::eventFilter(QObject* watched, QEvent* event)
{
    if (event->type() == QEvent::KeyPress && static_cast<QKeyEvent*>(event)->key() == Qt::Key_Delete)
    {
        if (watched == m_playingNextList && m_playingNextList->currentItem())
        {
            emit removePlayingNext(m_playingNextList->currentRow());
            return true;
        }
        if (watched == m_upcomingList && m_upcomingList->currentItem())
        {
            emit removeListItem(m_upcomingList->currentItem()->data(kDataRole).toInt());
            return true;
        }
    }
    return QFrame::eventFilter(watched, event);
}

} // namespace audioforge
