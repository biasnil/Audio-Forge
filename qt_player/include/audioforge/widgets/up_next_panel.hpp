#pragma once

#include <QFrame>
#include <QHash>
#include <QPixmap>
#include <QStringList>
#include <QVector>

#include <optional>

#include "audioforge/track_info.hpp"

class QLabel;
class QListWidget;
class QListWidgetItem;
class QPushButton;
class QTimer;

namespace audioforge {

// What the Up Next panel shows -- built by PlayerWindow from PlaybackQueue.
struct UpNextContent
{
    QVector<TrackInfo> history;      // played before the current track, oldest first
    std::optional<TrackInfo> current;
    QVector<TrackInfo> playingNext;  // hand-queued tracks (Play Next / Add to Queue)
    QString contextName;             // "Tracks", an album or playlist name...
    // List items after the current one: (list index, track). nullopt in
    // Random shuffle, where each next track is picked only when it's needed.
    std::optional<QVector<QPair<int, TrackInfo>>> upcoming;
    bool smartShuffle = false;
    bool repeating = false;
};

// The queue, like the iOS app's Up Next sheet: History, Now Playing, the hand-queued
// "Playing Next" tracks (drag to reorder), then the rest of the list.
// Double-click plays a track now; right-click or Delete removes it.
class UpNextPanel : public QFrame
{
    Q_OBJECT

public:
    explicit UpNextPanel(QWidget* parent = nullptr);

    void setContent(const UpNextContent& content);
    void setPlaceholderCover(const QPixmap& pixmap); // music-note glyph for tracks without art (theme-colored)
    void setHeaderIcons(const QIcon& more, const QIcon& close);
    void setAccentColor(const QColor& color); // the current track's title color

signals:
    void playHistoryNow(int position);   // positions index UpNextContent::history
    void playHistoryNext(int position);
    void addHistoryToQueue(int position);
    void jumpToPlayingNext(int position);
    void jumpToListItem(int index);
    void removePlayingNext(int position);
    void movePlayingNext(int from, int to);
    void removeListItem(int index);
    void clearPlayingNextRequested();
    void saveAsPlaylistRequested();
    void closeRequested();

protected:
    bool eventFilter(QObject* watched, QEvent* event) override; // Delete key on a row

private:
    QListWidget* makeList(bool reorderable);
    QListWidgetItem* makeItem(const TrackInfo& track, bool isCurrent, int data);
    void fitHeight(QListWidget* list);
    void loadNextCovers();
    void showRowMenu(QListWidget* list, const QPoint& pos);

    QPushButton* m_moreButton = nullptr;
    QPushButton* m_closeButton = nullptr;
    QLabel* m_historyTitle = nullptr;
    QListWidget* m_historyList = nullptr;
    QLabel* m_nowPlayingTitle = nullptr;
    QListWidget* m_nowPlayingList = nullptr;
    QWidget* m_playingNextHeader = nullptr;
    QListWidget* m_playingNextList = nullptr;
    QLabel* m_contextTitle = nullptr;
    QListWidget* m_upcomingList = nullptr;
    QLabel* m_upcomingNote = nullptr;
    QLabel* m_footer = nullptr;

    QPixmap m_placeholder;
    QHash<QString, QPixmap> m_covers;   // path -> 40px rounded thumbnail (null pixmap = no art)
    QStringList m_coverQueue;           // paths still to load
    QTimer* m_coverTimer = nullptr;
    bool m_rebuilding = false;          // ignore the model's move signal while refilling
};

} // namespace audioforge
