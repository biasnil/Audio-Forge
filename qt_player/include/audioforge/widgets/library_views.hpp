#pragma once

#include "audioforge/theme.hpp"
#include "audioforge/track_info.hpp"

#include <QFrame>
#include <QHash>
#include <QIcon>
#include <QPixmap>
#include <QSet>
#include <QStyledItemDelegate>
#include <QTableWidgetItem>
#include <QThreadPool>
#include <QTimer>

#include <functional>

class QTableWidget;
class QLabel;
class QPushButton;

// The pieces of the library pages (Tracks, Albums, Artists) that are drawn
// by hand rather than built from stock widgets: the Tracks table's rows, the
// album grid with its inline panel, the artist rows, and the selection bar.
namespace audioforge {

class AlbumPanel;

// Cover thumbnails for the library views. Reading a cover means opening the
// file's tags, too slow to do for every row while painting -- so a miss
// returns generated art at once and the real cover is read on a worker
// thread; `loaded` fires (a few times a second at most) when new ones are in.
class CoverCache : public QObject
{
    Q_OBJECT

public:
    explicit CoverCache(QObject* parent = nullptr);
    ~CoverCache() override;

    // `size` across in logical px, corners rounded by `radius` (size / 2 = a
    // circle). Empty `path`: generated art only (artists).
    QPixmap cover(const QString& path, int size, qreal radius, const QString& seed,
                  theme::Icon mark = theme::Icon::Track);
    void invalidate(const QString& path); // after the file's cover changed

signals:
    void loaded();

private:
    void startNext();

    struct Request
    {
        QString key;
        QString path;
        int size;
        qreal radius;
    };
    QHash<QString, QPixmap> m_pixmaps;  // "path|size|radius" -> the real cover
    QHash<QString, QPixmap> m_generated; // "seed|size|radius|mark" -> generated art
    QSet<QString> m_noCover;             // keys whose file has no cover
    QVector<Request> m_waiting;          // newest last: what's on screen now goes first
    QSet<QString> m_pending;             // waiting or being read
    int m_running = 0;
    QTimer m_notify;
    QThreadPool m_pool; // last: destroyed (and waited for) first
};

// Tracks table: what each column holds. Artist and Added are hidden -- they
// are there to sort by ("Sorted by Artist").
namespace tracks_column {
enum : int { Number, Title, Album, Genre, Year, Plays, Time, Loved, Artist, Added, Count };
}
constexpr int kTrackPathRole = Qt::UserRole;        // Title items: the file
constexpr int kTrackArtistRole = Qt::UserRole + 1;  // Title items: the line under the title
constexpr int kTrackNumberRole = Qt::UserRole + 2;  // Number items: the position shown
constexpr int kTrackSortRole = Qt::UserRole + 3;    // any item: what it sorts by, if not its text

// A table item that sorts by kTrackSortRole when it has one (durations,
// play counts, the artist-album-title key...), else by its text.
class TrackTableItem : public QTableWidgetItem
{
public:
    using QTableWidgetItem::QTableWidgetItem;
    bool operator<(const QTableWidgetItem& other) const override;
};

// Paints every cell of the Tracks table: the cover + title + artist cell,
// the playing row's accent, the heart, row hover across the whole row, and
// the skeleton rows while the first scan runs.
class TracksTableDelegate : public QStyledItemDelegate
{
public:
    TracksTableDelegate(QTableWidget* table, CoverCache* covers);

    void setColors(const theme::Tokens& tokens);
    void setPlayingPath(const QString& path);
    void setSkeleton(bool on); // shown while the table is empty and a scan runs

    std::function<bool(const QString&)> isLoved;
    std::function<int(const QString&)> playCount;

    void paint(QPainter* p, const QStyleOptionViewItem& option, const QModelIndex& index) const override;
    QSize sizeHint(const QStyleOptionViewItem& option, const QModelIndex& index) const override;

protected:
    bool eventFilter(QObject* watched, QEvent* event) override; // row hover, skeleton painting

private:
    void paintSkeleton(QPainter* p) const;

    QTableWidget* m_table;
    CoverCache* m_covers;
    theme::Tokens m_t;
    QString m_playingPath;
    int m_hoverRow = -1;
    bool m_skeleton = false;
    QPixmap m_playingMark; // visualizer glyph in the # column
    QPixmap m_heart;
};

// "2 selected · Play next · Add to queue · Add to playlist | Edit tags · Change cover · ✕",
// floating over the bottom of the Tracks table while rows are selected.
class SelectionActionBar : public QFrame
{
    Q_OBJECT

public:
    explicit SelectionActionBar(QWidget* parent = nullptr);
    void setCount(int count);
    void setCloseIcon(const QIcon& icon);

signals:
    void playNextClicked();
    void addToQueueClicked();
    void addToPlaylistClicked(const QPoint& globalPos);
    void editTagsClicked();
    void changeCoverClicked();
    void clearClicked();

private:
    QLabel* m_countLabel;
    QPushButton* m_closeButton;
};

// The A-Z strip on the Tracks table's right edge.
class AlphabetIndex : public QWidget
{
    Q_OBJECT

public:
    explicit AlphabetIndex(QWidget* parent = nullptr);
    void setColors(const QColor& normal, const QColor& current);
    void setCurrent(QChar letter); // '#' for anything not A-Z
    QSize sizeHint() const override;

    static QChar letterOf(const QString& text);

signals:
    void letterClicked(QChar letter);

protected:
    void paintEvent(QPaintEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;

private:
    QChar letterAt(int y) const;

    QString m_letters;
    QChar m_current;
    QColor m_normal;
    QColor m_highlight;
};

// One album card.
struct AlbumCard
{
    QString name;
    QString subtitle;  // "Artist · Year"
    QString coverPath; // a track whose cover stands for the album
};

// Album covers in a fixed number of columns, with an optional panel opened
// under one card's row (spanning every column) -- the Albums page's inline
// album panel; the Artists page uses it without one. Grows to fit its
// content (it sits in a QScrollArea).
class AlbumGrid : public QWidget
{
    Q_OBJECT

public:
    AlbumGrid(CoverCache* covers, int columns, QWidget* parent = nullptr);

    void setAlbums(const QVector<AlbumCard>& albums);
    void setColors(const theme::Tokens& tokens);
    void setPanel(AlbumPanel* panel); // reparented; shown under the open card's row
    void setOpenIndex(int index);  // -1 closes the panel
    int openIndex() const { return m_open; }
    int count() const { return m_albums.size(); }
    void relayout();               // after the panel's content changed size
    int openCardCenterX() const;   // for the panel's notch, in grid coordinates

    QSize sizeHint() const override;

signals:
    void cardClicked(int index);
    void cardDoubleClicked(int index);
    void cardMenuRequested(int index, const QPoint& globalPos);

protected:
    void paintEvent(QPaintEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void mouseDoubleClickEvent(QMouseEvent* event) override;
    void leaveEvent(QEvent* event) override;
    void contextMenuEvent(QContextMenuEvent* event) override;

private:
    int indexAt(const QPoint& pos) const;
    QRect cardRect(int index) const;
    int cardWidth() const;

    CoverCache* m_covers;
    int m_columns;
    QVector<AlbumCard> m_albums;
    theme::Tokens m_t;
    AlbumPanel* m_panel = nullptr;
    int m_open = -1;
    int m_hover = -1;
    QVector<int> m_rowTop; // y of each row of cards
    int m_height = 0;
};

// The panel opened under an album card: cover, title, details, Shuffle /
// Play / More, and the tracks in two columns.
class AlbumPanel : public QWidget
{
    Q_OBJECT

public:
    explicit AlbumPanel(CoverCache* covers, QWidget* parent = nullptr);

    void setColors(const theme::Tokens& tokens, const QIcon& play, const QIcon& shuffle, const QIcon& more);
    void setAlbum(const QString& title, const QString& details, const QVector<TrackInfo>& tracks);
    void setPlayingPath(const QString& path);
    void setNotchX(int x); // where the notch on the top edge points, in panel coordinates

    bool hasHeightForWidth() const override { return true; }
    int heightForWidth(int width) const override;

signals:
    void playClicked();
    void shuffleClicked();
    void moreClicked(const QPoint& globalPos);
    void trackActivated(int index);

protected:
    void paintEvent(QPaintEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mouseDoubleClickEvent(QMouseEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    QRect trackListRect() const;
    int rowCount() const;
    int trackAt(const QPoint& pos) const;

    CoverCache* m_covers;
    theme::Tokens m_t;
    QLabel* m_title;
    QLabel* m_details;
    QPushButton* m_shuffle;
    QPushButton* m_play;
    QPushButton* m_more;
    QVector<TrackInfo> m_tracks;
    QString m_playingPath;
    int m_notchX = 40;
    int m_hoverTrack = -1;
};

// Artist rows: round avatar, name over "N albums · N songs".
constexpr int kArtistMetaRole = Qt::UserRole + 10;
class ArtistRowDelegate : public QStyledItemDelegate
{
public:
    ArtistRowDelegate(CoverCache* covers, QObject* parent = nullptr);
    void setColors(const theme::Tokens& tokens) { m_t = tokens; }
    void paint(QPainter* p, const QStyleOptionViewItem& option, const QModelIndex& index) const override;
    QSize sizeHint(const QStyleOptionViewItem& option, const QModelIndex& index) const override;

private:
    CoverCache* m_covers;
    theme::Tokens m_t;
};

// An artist's most played songs: rank, cover + title, album, plays, time.
struct RankedTrack
{
    TrackInfo track;
    int plays = 0;
};
class MostPlayedList : public QWidget
{
    Q_OBJECT

public:
    explicit MostPlayedList(CoverCache* covers, QWidget* parent = nullptr);
    void setColors(const theme::Tokens& tokens);
    void setTracks(const QVector<RankedTrack>& tracks);
    void setPlayingPath(const QString& path);
    QSize sizeHint() const override;

signals:
    void trackActivated(int index);
    void trackMenuRequested(int index, const QPoint& globalPos);

protected:
    void paintEvent(QPaintEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mouseDoubleClickEvent(QMouseEvent* event) override;
    void leaveEvent(QEvent* event) override;
    void contextMenuEvent(QContextMenuEvent* event) override;

private:
    int rowAt(const QPoint& pos) const;

    CoverCache* m_covers;
    theme::Tokens m_t;
    QVector<RankedTrack> m_tracks;
    QString m_playingPath;
    int m_hover = -1;
};

// "6 days 18 hours" / "2 h 41 min" / "12 min".
QString FormatLongDuration(double seconds);

} // namespace audioforge
