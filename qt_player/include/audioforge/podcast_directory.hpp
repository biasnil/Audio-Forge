#pragma once

#include "audioforge/theme.hpp"

#include <QDateTime>
#include <QHash>
#include <QJsonObject>
#include <QVector>
#include <QWidget>

class QFile;
class QLabel;
class QListWidget;
class QListWidgetItem;
class QNetworkAccessManager;
class QNetworkReply;
class QPushButton;
class QStackedWidget;

namespace audioforge {

class WebImageCache;
class OnlineRowDelegate;

// A show from the podcast directory (Apple's, which most apps search).
struct PodcastFeedInfo
{
    QString title;
    QString author;
    QString feedUrl;
    QString artworkUrl;
    QString genre;
    qint64 lastCheckedMs = 0; // subscriptions: when its episodes were last looked at
    int newEpisodes = 0;      // ...and how many came out since the check before

    QJsonObject toJson() const;
    static PodcastFeedInfo fromJson(const QJsonObject& object);
};

struct FeedEpisode
{
    QString title;
    QString url;          // the audio file
    QString description;
    QDateTime published;
    int seconds = 0;
};

// An RSS feed's episodes, newest first; `info` gets the show's own details.
QVector<FeedEpisode> ParsePodcastFeed(const QByteArray& xml, PodcastFeedInfo* info);

// Where downloaded episodes go: a Podcasts folder in the user's home, one
// folder per show -- added to the library as a Podcast folder, so they play
// like any other episode (resume, played marks).
QString PodcastsRoot();

// The Podcasts tab's online part: search, subscriptions, a show's episodes
// with Download, and "Check for new episodes".
class PodcastDirectoryPage : public QWidget
{
    Q_OBJECT

public:
    PodcastDirectoryPage(QNetworkAccessManager* network, WebImageCache* images, QWidget* parent = nullptr);

    void setColors(const theme::Tokens& tokens);
    void search(const QString& text);
    void showSubscriptions();
    // Apple's top podcasts of the day for this region: fetched once a day at
    // most (kept in podcast_top.json), the saved list shown otherwise.
    void showTopPodcasts();
    void checkForNewEpisodes();
    int subscriptionCount() const { return m_subscriptions.size(); }

signals:
    void episodeDownloaded(const QString& path); // a new file under PodcastsRoot()
    void playFile(const QString& path, const QString& show);
    void message(const QString& text);          // for a toast

protected:
    bool eventFilter(QObject* watched, QEvent* event) override; // row buttons

private:
    void showFeedList(const QVector<PodcastFeedInfo>& feeds, const QString& emptyText, bool ranked = false);
    void fetchTopPodcasts(const QString& country);
    void openShow(const PodcastFeedInfo& feed);
    void showEpisodes();
    void updateEpisodeRow(int index);
    void toggleSubscription(const PodcastFeedInfo& feed);
    bool isSubscribed(const QString& feedUrl) const;
    int subscriptionIndex(const QString& feedUrl) const;
    void saveSubscriptions() const;
    QString episodePath(const FeedEpisode& episode) const; // for the open show
    void download(const FeedEpisode& episode);
    void updateSubscribeButton();

    QNetworkAccessManager* m_network;
    WebImageCache* m_images;
    OnlineRowDelegate* m_delegate;
    QStackedWidget* m_stack;    // the shows (0) or one show (1)
    QLabel* m_listTitle;
    QListWidget* m_feedList;
    QLabel* m_feedStatus;
    QLabel* m_showArt;
    QLabel* m_showTitle;
    QLabel* m_showDetails;
    QPushButton* m_subscribeButton;
    QListWidget* m_episodeList;
    QLabel* m_episodeStatus;

    QVector<PodcastFeedInfo> m_subscriptions;
    QVector<PodcastFeedInfo> m_listed;   // the feeds the list shows
    PodcastFeedInfo m_show;              // the open show
    QVector<FeedEpisode> m_episodes;
    QHash<QString, double> m_downloads;  // episode URL -> progress, while it downloads
    int m_generation = 0;
};

} // namespace audioforge
