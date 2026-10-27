#include "audioforge/podcast_directory.hpp"
#include "audioforge/app_files.hpp"
#include "audioforge/media_mux.hpp"
#include "audioforge/widgets/online_views.hpp"

#include <QDate>
#include <QDir>
#include <QEvent>
#include <QFile>
#include <QFileInfo>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QListWidget>
#include <QLocale>
#include <QMenu>
#include <QMouseEvent>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QPushButton>
#include <QRegularExpression>
#include <QStackedWidget>
#include <QStyle>
#include <QStandardPaths>
#include <QTextDocumentFragment>
#include <QUrlQuery>
#include <QVBoxLayout>
#include <QXmlStreamReader>

#include <algorithm>
#include <memory>

namespace audioforge {

namespace {

QString TopPodcastsFile()
{
    return QDir(AppDataFolder()).filePath(QStringLiteral("podcast_top.json"));
}

QString SubscriptionsFile()
{
    return QDir(AppDataFolder()).filePath(QStringLiteral("podcast_subscriptions.json"));
}

// "01:02:03", "62:03", "3723" -> seconds.
int ParseDuration(const QString& text)
{
    const QStringList parts = text.trimmed().split(':');
    int seconds = 0;
    for (const QString& part : parts)
    {
        seconds = seconds * 60 + part.toInt();
    }
    return seconds;
}

// RFC 2822, as feeds send it -- including the US zone names (PST, EDT...) Qt doesn't know.
QDateTime ParseFeedDate(const QString& raw)
{
    QString text = raw.trimmed();
    QDateTime date = QDateTime::fromString(text, Qt::RFC2822Date);
    if (date.isValid())
    {
        return date;
    }
    static const QHash<QString, QString> kZones = {
        {"EST", "-0500"}, {"EDT", "-0400"}, {"CST", "-0600"}, {"CDT", "-0500"}, {"MST", "-0700"},
        {"MDT", "-0600"}, {"PST", "-0800"}, {"PDT", "-0700"}, {"UTC", "+0000"}, {"UT", "+0000"}, {"Z", "+0000"},
    };
    const int space = text.lastIndexOf(' ');
    if (space > 0 && kZones.contains(text.mid(space + 1).toUpper()))
    {
        text = text.left(space + 1) + kZones.value(text.mid(space + 1).toUpper());
    }
    date = QDateTime::fromString(text, Qt::RFC2822Date);
    return date.isValid() ? date : QDateTime::fromString(raw.trimmed(), Qt::ISODate);
}

QString PlainText(const QString& html)
{
    return QTextDocumentFragment::fromHtml(html).toPlainText().simplified();
}

QString EpisodeDetails(const FeedEpisode& e)
{
    QStringList parts;
    if (e.published.isValid())
    {
        parts << QLocale().toString(e.published.date(), QLocale::ShortFormat);
    }
    if (e.seconds > 0)
    {
        parts << (e.seconds >= 3600 ? QObject::tr("%1 h %2 min").arg(e.seconds / 3600).arg(e.seconds % 3600 / 60)
                                    : QObject::tr("%1 min").arg(qMax(1, e.seconds / 60)));
    }
    return parts.join(QStringLiteral(" · "));
}

// The audio file's extension: from the URL, else the usual one for podcasts.
QString EpisodeExtension(const QString& url)
{
    const QString suffix = QFileInfo(QUrl(url).path()).suffix().toLower();
    static const QStringList known = {"mp3", "m4a", "m4b", "aac", "mp4"};
    return known.contains(suffix) ? suffix : QStringLiteral("mp3");
}

} // namespace

QJsonObject PodcastFeedInfo::toJson() const
{
    return QJsonObject{{"title", title}, {"author", author}, {"feedUrl", feedUrl}, {"artworkUrl", artworkUrl},
                       {"genre", genre}, {"lastChecked", QString::number(lastCheckedMs)}, {"new", newEpisodes}};
}

PodcastFeedInfo PodcastFeedInfo::fromJson(const QJsonObject& o)
{
    PodcastFeedInfo f;
    f.title = o.value("title").toString();
    f.author = o.value("author").toString();
    f.feedUrl = o.value("feedUrl").toString();
    f.artworkUrl = o.value("artworkUrl").toString();
    f.genre = o.value("genre").toString();
    f.lastCheckedMs = o.value("lastChecked").toString().toLongLong();
    f.newEpisodes = o.value("new").toInt();
    return f;
}

QVector<FeedEpisode> ParsePodcastFeed(const QByteArray& xml, PodcastFeedInfo* info)
{
    QVector<FeedEpisode> episodes;
    QXmlStreamReader reader(xml);
    bool inItem = false;
    bool inChannelImage = false;
    FeedEpisode episode;
    while (!reader.atEnd())
    {
        reader.readNext();
        const QString name = reader.qualifiedName().toString();
        if (reader.isStartElement())
        {
            if (name == QLatin1String("item"))
            {
                inItem = true;
                episode = FeedEpisode();
            }
            else if (name == QLatin1String("image") && !inItem)
            {
                inChannelImage = true;
            }
            else if (inItem)
            {
                if (name == QLatin1String("title"))
                    episode.title = reader.readElementText().trimmed();
                else if (name == QLatin1String("enclosure"))
                    episode.url = reader.attributes().value("url").toString();
                else if (name == QLatin1String("pubDate"))
                    episode.published = ParseFeedDate(reader.readElementText());
                else if (name == QLatin1String("itunes:duration"))
                    episode.seconds = ParseDuration(reader.readElementText());
                else if ((name == QLatin1String("description") || name == QLatin1String("itunes:summary")) && episode.description.isEmpty())
                    episode.description = PlainText(reader.readElementText(QXmlStreamReader::IncludeChildElements)).left(600);
            }
            else if (info)
            {
                if (name == QLatin1String("title") && !inChannelImage && info->title.isEmpty())
                    info->title = reader.readElementText().trimmed();
                else if (name == QLatin1String("itunes:author") && info->author.isEmpty())
                    info->author = reader.readElementText().trimmed();
                else if (name == QLatin1String("itunes:image") && info->artworkUrl.isEmpty())
                    info->artworkUrl = reader.attributes().value("href").toString();
                else if (name == QLatin1String("url") && inChannelImage && info->artworkUrl.isEmpty())
                    info->artworkUrl = reader.readElementText().trimmed();
            }
        }
        else if (reader.isEndElement())
        {
            if (name == QLatin1String("item"))
            {
                inItem = false;
                if (!episode.url.isEmpty())
                {
                    if (episode.title.isEmpty())
                    {
                        episode.title = QFileInfo(QUrl(episode.url).path()).completeBaseName();
                    }
                    episodes << episode;
                }
            }
            else if (name == QLatin1String("image") && !inItem)
            {
                inChannelImage = false;
            }
        }
    }
    std::stable_sort(episodes.begin(), episodes.end(),
                     [](const FeedEpisode& a, const FeedEpisode& b) { return a.published > b.published; });
    return episodes;
}

QString PodcastsRoot()
{
    return QDir(QStandardPaths::writableLocation(QStandardPaths::HomeLocation)).filePath(QStringLiteral("Podcasts"));
}

// --- The page ----------------------------------------------------------------------

PodcastDirectoryPage::PodcastDirectoryPage(QNetworkAccessManager* network, WebImageCache* images, QWidget* parent)
    : QWidget(parent), m_network(network), m_images(images)
{
    for (const QJsonValue& value : ReadJsonFile(SubscriptionsFile()).value("subscriptions").toArray())
    {
        m_subscriptions << PodcastFeedInfo::fromJson(value.toObject());
    }

    auto* l = new QVBoxLayout(this);
    l->setContentsMargins(0, 0, 0, 0);
    m_stack = new QStackedWidget();
    l->addWidget(m_stack);
    m_delegate = new OnlineRowDelegate(images, this);

    auto makeList = [this, images]() {
        auto* list = new QListWidget();
        list->setItemDelegate(m_delegate);
        list->setMouseTracking(true);
        list->setUniformItemSizes(true);
        list->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
        list->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
        list->viewport()->installEventFilter(this);
        connect(images, &WebImageCache::loaded, list->viewport(), qOverload<>(&QWidget::update));
        return list;
    };
    auto makeStatus = []() {
        auto* label = new QLabel();
        label->setObjectName("PageSubtitle");
        label->setAlignment(Qt::AlignCenter);
        label->setWordWrap(true);
        label->hide();
        return label;
    };

    // Page 0: search results or subscriptions.
    auto* listPage = new QWidget();
    auto* listLayout = new QVBoxLayout(listPage);
    listLayout->setContentsMargins(0, 0, 0, 0);
    listLayout->setSpacing(8);
    m_listTitle = new QLabel();
    m_listTitle->setObjectName("SettingsSectionTitle");
    listLayout->addWidget(m_listTitle);
    m_feedList = makeList();
    m_feedList->setCursor(Qt::PointingHandCursor);
    listLayout->addWidget(m_feedList, 1);
    m_feedStatus = makeStatus();
    listLayout->addWidget(m_feedStatus, 1);
    m_stack->addWidget(listPage);

    // Page 1: a show -- artwork, title, Subscribe; its episodes.
    auto* showPage = new QWidget();
    auto* showLayout = new QVBoxLayout(showPage);
    showLayout->setContentsMargins(0, 0, 0, 0);
    showLayout->setSpacing(12);
    auto* header = new QHBoxLayout();
    header->setSpacing(16);
    auto* back = OnlineButton(QString(), "IconButton");
    back->setFixedSize(32, 32);
    back->setIconSize(QSize(18, 18));
    back->setToolTip(tr("Back"));
    back->setAccessibleName(tr("Back"));
    back->setIcon(theme::icon(theme::Icon::Back, theme::tokens(true).text));
    connect(back, &QPushButton::clicked, this, [this]() { m_stack->setCurrentIndex(0); });
    header->addWidget(back, 0, Qt::AlignTop);
    m_showArt = new QLabel();
    m_showArt->setFixedSize(88, 88);
    header->addWidget(m_showArt);
    auto* showText = new QVBoxLayout();
    showText->setSpacing(4);
    showText->addStretch();
    m_showTitle = new QLabel();
    m_showTitle->setObjectName("EmptyTitle");
    m_showTitle->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
    showText->addWidget(m_showTitle);
    m_showDetails = new QLabel();
    m_showDetails->setObjectName("PageSubtitle");
    m_showDetails->setSizePolicy(QSizePolicy::Ignored, QSizePolicy::Preferred);
    showText->addWidget(m_showDetails);
    showText->addSpacing(6);
    m_subscribeButton = OnlineButton(QString(), "PrimaryButton");
    connect(m_subscribeButton, &QPushButton::clicked, this, [this]() { toggleSubscription(m_show); });
    auto* buttons = new QHBoxLayout();
    buttons->addWidget(m_subscribeButton);
    buttons->addStretch();
    showText->addLayout(buttons);
    showText->addStretch();
    header->addLayout(showText, 1);
    showLayout->addLayout(header);
    m_episodeList = makeList();
    showLayout->addWidget(m_episodeList, 1);
    m_episodeStatus = makeStatus();
    showLayout->addWidget(m_episodeStatus, 1);
    m_stack->addWidget(showPage);

    m_feedList->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_feedList, &QListWidget::customContextMenuRequested, this, [this](const QPoint& pos) {
        QListWidgetItem* item = m_feedList->itemAt(pos);
        if (!item)
        {
            return;
        }
        const PodcastFeedInfo feed = m_listed.value(item->data(online_role::Data).toInt());
        QMenu menu(this);
        menu.addAction(tr("Show Episodes"), this, [this, feed]() { openShow(feed); });
        menu.addAction(isSubscribed(feed.feedUrl) ? tr("Unsubscribe") : tr("Subscribe"), this,
                       [this, feed]() { toggleSubscription(feed); });
        menu.exec(m_feedList->viewport()->mapToGlobal(pos));
    });
}

void PodcastDirectoryPage::setColors(const theme::Tokens& tokens)
{
    m_delegate->setColors(tokens);
    m_feedList->viewport()->update();
    m_episodeList->viewport()->update();
}

void PodcastDirectoryPage::search(const QString& text)
{
    const QString term = text.trimmed();
    if (term.isEmpty())
    {
        showSubscriptions();
        return;
    }
    const int generation = ++m_generation;
    m_stack->setCurrentIndex(0);
    m_listTitle->setText(tr("Results for “%1”").arg(term));
    m_feedList->clear();
    m_feedList->hide();
    m_feedStatus->setText(tr("Searching…"));
    m_feedStatus->show();
    QUrl url(QStringLiteral("https://itunes.apple.com/search"));
    QUrlQuery query;
    query.addQueryItem("media", "podcast");
    query.addQueryItem("entity", "podcast");
    query.addQueryItem("limit", "50");
    query.addQueryItem("term", term);
    url.setQuery(query);
    QNetworkReply* reply = NetworkGet(m_network, url);
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation]() {
        reply->deleteLater();
        if (generation != m_generation)
        {
            return;
        }
        if (reply->error() != QNetworkReply::NoError)
        {
            m_feedStatus->setText(tr("The podcast directory can't be reached right now. Check your connection and try again."));
            return;
        }
        QVector<PodcastFeedInfo> feeds;
        for (const QJsonValue& value : QJsonDocument::fromJson(reply->readAll()).object().value("results").toArray())
        {
            const QJsonObject o = value.toObject();
            PodcastFeedInfo feed;
            feed.title = o.value("collectionName").toString();
            feed.author = o.value("artistName").toString();
            feed.feedUrl = o.value("feedUrl").toString();
            feed.artworkUrl = o.value("artworkUrl600").toString();
            if (feed.artworkUrl.isEmpty())
            {
                feed.artworkUrl = o.value("artworkUrl100").toString();
            }
            feed.genre = o.value("primaryGenreName").toString();
            if (!feed.feedUrl.isEmpty())
            {
                feeds << feed;
            }
        }
        showFeedList(feeds, tr("No podcasts found."));
    });
}

void PodcastDirectoryPage::showSubscriptions()
{
    ++m_generation;
    m_stack->setCurrentIndex(0);
    m_listTitle->setText(tr("Subscriptions"));
    showFeedList(m_subscriptions, tr("No subscriptions yet. Search for a show and subscribe to it."));
}

void PodcastDirectoryPage::showTopPodcasts()
{
    ++m_generation;
    m_stack->setCurrentIndex(0);
    m_listTitle->setText(tr("Top podcasts today"));
    const QJsonObject saved = ReadJsonFile(TopPodcastsFile());
    QVector<PodcastFeedInfo> feeds;
    for (const QJsonValue& value : saved.value("feeds").toArray())
    {
        feeds << PodcastFeedInfo::fromJson(value.toObject());
    }
    if (!feeds.isEmpty())
    {
        showFeedList(feeds, QString(), true);
    }
    if (feeds.isEmpty() || saved.value("day").toString() != QDate::currentDate().toString(Qt::ISODate))
    {
        // Nothing saved from today: Apple's chart, for this PC's region (else the US one).
        QString country = QLocale::territoryToCode(QLocale::system().territory()).toLower();
        if (country.size() != 2)
        {
            country = QStringLiteral("us");
        }
        if (feeds.isEmpty())
        {
            m_feedList->hide();
            m_feedStatus->setText(tr("Loading today's top podcasts…"));
            m_feedStatus->show();
        }
        fetchTopPodcasts(country);
    }
}

void PodcastDirectoryPage::fetchTopPodcasts(const QString& country)
{
    const int generation = m_generation;
    const QUrl chart(QStringLiteral("https://rss.applemarketingtools.com/api/v2/%1/podcasts/top/50/podcasts.json").arg(country));
    QNetworkReply* reply = NetworkGet(m_network, chart);
    connect(reply, &QNetworkReply::finished, this, [this, reply, country, generation]() {
        reply->deleteLater();
        const QJsonArray results = QJsonDocument::fromJson(reply->readAll()).object().value("feed").toObject().value("results").toArray();
        if (results.isEmpty())
        {
            if (country != QLatin1String("us"))
            {
                fetchTopPodcasts(QStringLiteral("us")); // no chart for this region
            }
            else if (generation == m_generation && m_feedList->count() == 0)
            {
                m_feedStatus->setText(tr("Today's top podcasts can't be loaded right now."));
            }
            return;
        }
        // The chart has no feed addresses: one lookup for all of them.
        QStringList ids;
        QVector<PodcastFeedInfo> chart;
        for (const QJsonValue& value : results)
        {
            const QJsonObject r = value.toObject();
            ids << r.value("id").toString();
            PodcastFeedInfo feed;
            feed.title = r.value("name").toString();
            feed.author = r.value("artistName").toString();
            feed.artworkUrl = r.value("artworkUrl100").toString().replace(QStringLiteral("100x100bb"), QStringLiteral("600x600bb"));
            const QJsonArray genres = r.value("genres").toArray();
            feed.genre = genres.isEmpty() ? QString() : genres.first().toObject().value("name").toString();
            chart << feed;
        }
        QUrl lookup(QStringLiteral("https://itunes.apple.com/lookup"));
        lookup.setQuery(QStringLiteral("id=%1&entity=podcast").arg(ids.join(',')));
        QNetworkReply* details = NetworkGet(m_network, lookup);
        connect(details, &QNetworkReply::finished, this, [this, details, ids, chart, generation]() mutable {
            details->deleteLater();
            QHash<QString, QString> feedUrls;
            for (const QJsonValue& value : QJsonDocument::fromJson(details->readAll()).object().value("results").toArray())
            {
                const QJsonObject r = value.toObject();
                feedUrls.insert(QString::number(static_cast<qint64>(r.value("collectionId").toDouble())), r.value("feedUrl").toString());
            }
            QVector<PodcastFeedInfo> feeds;
            QJsonArray saved;
            for (int i = 0; i < chart.size(); ++i)
            {
                chart[i].feedUrl = feedUrls.value(ids.value(i));
                if (!chart[i].feedUrl.isEmpty())
                {
                    feeds << chart[i];
                    saved.append(chart[i].toJson());
                }
            }
            if (feeds.isEmpty())
            {
                return;
            }
            WriteJsonFile(TopPodcastsFile(), QJsonObject{{"day", QDate::currentDate().toString(Qt::ISODate)}, {"feeds", saved}});
            if (generation == m_generation)
            {
                showFeedList(feeds, QString(), true);
            }
        });
    });
}

void PodcastDirectoryPage::showFeedList(const QVector<PodcastFeedInfo>& feeds, const QString& emptyText, bool ranked)
{
    m_listed = feeds;
    m_feedList->clear();
    for (int i = 0; i < feeds.size(); ++i)
    {
        const PodcastFeedInfo& feed = feeds[i];
        auto* item = new QListWidgetItem(feed.title);
        QStringList details;
        if (!feed.author.isEmpty())
        {
            details << feed.author;
        }
        if (!feed.genre.isEmpty())
        {
            details << feed.genre;
        }
        item->setData(online_role::Subtitle, details.join(QStringLiteral(" · ")));
        item->setData(online_role::ImageUrl, feed.artworkUrl);
        item->setData(online_role::Mark, static_cast<int>(theme::Icon::Podcast));
        item->setData(online_role::Button, isSubscribed(feed.feedUrl) ? tr("Subscribed") : tr("Subscribe"));
        const int index = subscriptionIndex(feed.feedUrl);
        if (ranked)
        {
            item->setData(online_role::Trailing, QStringLiteral("#%1").arg(i + 1));
        }
        else if (index >= 0 && m_subscriptions[index].newEpisodes > 0)
        {
            item->setData(online_role::Trailing, tr("%1 new").arg(m_subscriptions[index].newEpisodes));
        }
        item->setData(online_role::Data, i);
        item->setToolTip(feed.title);
        m_feedList->addItem(item);
    }
    m_feedStatus->setText(emptyText);
    m_feedStatus->setVisible(feeds.isEmpty());
    m_feedList->setVisible(!feeds.isEmpty());
}

void PodcastDirectoryPage::openShow(const PodcastFeedInfo& feed)
{
    const int generation = ++m_generation;
    m_show = feed;
    m_episodes.clear();
    m_stack->setCurrentIndex(1);
    m_showTitle->setText(feed.title);
    m_showTitle->setToolTip(feed.title);
    m_showDetails->setText(feed.author);
    m_showArt->setPixmap(m_images->image(feed.artworkUrl, 88, 10, feed.title, theme::Icon::Podcast));
    connect(m_images, &WebImageCache::loaded, m_showArt, [this]() {
        m_showArt->setPixmap(m_images->image(m_show.artworkUrl, 88, 10, m_show.title, theme::Icon::Podcast));
    }, Qt::UniqueConnection);
    updateSubscribeButton();
    m_episodeList->clear();
    m_episodeList->hide();
    m_episodeStatus->setText(tr("Loading episodes…"));
    m_episodeStatus->show();

    // Opening it counts as having seen what's new.
    if (const int index = subscriptionIndex(feed.feedUrl); index >= 0 && m_subscriptions[index].newEpisodes > 0)
    {
        m_subscriptions[index].newEpisodes = 0;
        saveSubscriptions();
    }

    QNetworkReply* reply = NetworkGet(m_network, QUrl(feed.feedUrl));
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation]() {
        reply->deleteLater();
        if (generation != m_generation)
        {
            return;
        }
        if (reply->error() != QNetworkReply::NoError)
        {
            m_episodeStatus->setText(tr("This show's episodes can't be loaded right now."));
            return;
        }
        PodcastFeedInfo info;
        m_episodes = ParsePodcastFeed(reply->readAll(), &info);
        if (m_show.artworkUrl.isEmpty())
        {
            m_show.artworkUrl = info.artworkUrl;
        }
        if (m_show.author.isEmpty())
        {
            m_show.author = info.author;
        }
        m_showDetails->setText(QStringList({m_show.author, tr("%1 episodes").arg(m_episodes.size())}).filter(QRegularExpression(".")).join(QStringLiteral(" · ")));
        showEpisodes();
    });
}

void PodcastDirectoryPage::showEpisodes()
{
    m_episodeList->clear();
    for (int i = 0; i < m_episodes.size(); ++i)
    {
        const FeedEpisode& episode = m_episodes[i];
        auto* item = new QListWidgetItem(episode.title);
        item->setData(online_role::Subtitle, EpisodeDetails(episode));
        item->setData(online_role::ImageUrl, m_show.artworkUrl);
        item->setData(online_role::Seed, m_show.title);
        item->setData(online_role::Mark, static_cast<int>(theme::Icon::Podcast));
        item->setData(online_role::Data, i);
        item->setToolTip(episode.description.isEmpty() ? episode.title : episode.title + QStringLiteral("\n\n") + episode.description);
        m_episodeList->addItem(item);
        updateEpisodeRow(i);
    }
    m_episodeStatus->setText(tr("This show has no episodes to download."));
    m_episodeStatus->setVisible(m_episodes.isEmpty());
    m_episodeList->setVisible(!m_episodes.isEmpty());
}

void PodcastDirectoryPage::updateEpisodeRow(int index)
{
    QListWidgetItem* item = m_episodeList->item(index);
    if (!item)
    {
        return;
    }
    const FeedEpisode& episode = m_episodes[index];
    if (m_downloads.contains(episode.url))
    {
        item->setData(online_role::Progress, m_downloads.value(episode.url));
        item->setData(online_role::Button, QString());
    }
    else
    {
        item->setData(online_role::Progress, QVariant());
        item->setData(online_role::Button, QFileInfo::exists(episodePath(episode)) ? tr("Play") : tr("Download"));
    }
}

QString PodcastDirectoryPage::episodePath(const FeedEpisode& episode) const
{
    return QDir(PodcastsRoot()).filePath(SafeFileName(m_show.title) + '/' + SafeFileName(episode.title) + '.'
                                         + EpisodeExtension(episode.url));
}

void PodcastDirectoryPage::download(const FeedEpisode& episode)
{
    const QString path = episodePath(episode);
    const QString show = m_show.title;
    const QString artworkUrl = m_show.artworkUrl;
    if (m_downloads.contains(episode.url) || !QDir().mkpath(QFileInfo(path).absolutePath()))
    {
        return;
    }
    // The show's artwork next to its episodes: the player shows it for any without their own.
    const QString cover = QDir(QFileInfo(path).absolutePath()).filePath(QStringLiteral("cover.jpg"));
    if (!QFileInfo::exists(cover) && !m_images->bytes(artworkUrl).isEmpty())
    {
        QFile file(cover);
        if (file.open(QIODevice::WriteOnly))
        {
            file.write(SquareJpeg(m_images->bytes(artworkUrl)));
        }
    }

    auto file = std::make_shared<QFile>(path + QStringLiteral(".part"));
    if (!file->open(QIODevice::WriteOnly))
    {
        emit message(tr("Couldn't save to %1.").arg(QDir::toNativeSeparators(QFileInfo(path).absolutePath())));
        return;
    }
    m_downloads.insert(episode.url, 0.0);
    for (int i = 0; i < m_episodes.size(); ++i)
    {
        if (m_episodes[i].url == episode.url)
        {
            updateEpisodeRow(i);
        }
    }
    QNetworkReply* reply = NetworkGet(m_network, QUrl(episode.url));
    reply->setReadBufferSize(1024 * 1024);
    const QString url = episode.url;
    const QDateTime published = episode.published;
    auto refreshRow = [this, url]() {
        for (int i = 0; i < m_episodes.size(); ++i)
        {
            if (m_episodes[i].url == url)
            {
                updateEpisodeRow(i);
            }
        }
    };
    connect(reply, &QNetworkReply::readyRead, this, [reply, file]() { file->write(reply->readAll()); });
    connect(reply, &QNetworkReply::downloadProgress, this, [this, url, refreshRow](qint64 received, qint64 total) {
        if (total > 0)
        {
            m_downloads[url] = static_cast<double>(received) / total;
            refreshRow();
        }
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply, file, path, url, show, published, refreshRow]() {
        reply->deleteLater();
        file->write(reply->readAll());
        file->close();
        m_downloads.remove(url);
        if (reply->error() != QNetworkReply::NoError)
        {
            file->remove();
            emit message(tr("An episode of %1 couldn't be downloaded.").arg(show));
        }
        else
        {
            QFile::remove(path);
            file->rename(path);
            // The episode's own date, so shows list their episodes newest first.
            if (published.isValid())
            {
                QFile done(path);
                if (done.open(QIODevice::ReadWrite))
                {
                    done.setFileTime(published, QFileDevice::FileBirthTime);
                    done.setFileTime(published, QFileDevice::FileModificationTime);
                }
            }
            emit episodeDownloaded(path);
        }
        refreshRow();
    });
}

bool PodcastDirectoryPage::isSubscribed(const QString& feedUrl) const
{
    return subscriptionIndex(feedUrl) >= 0;
}

int PodcastDirectoryPage::subscriptionIndex(const QString& feedUrl) const
{
    for (int i = 0; i < m_subscriptions.size(); ++i)
    {
        if (m_subscriptions[i].feedUrl == feedUrl)
        {
            return i;
        }
    }
    return -1;
}

void PodcastDirectoryPage::toggleSubscription(const PodcastFeedInfo& feed)
{
    const int index = subscriptionIndex(feed.feedUrl);
    if (index >= 0)
    {
        m_subscriptions.removeAt(index);
        emit message(tr("Unsubscribed from %1.").arg(feed.title));
    }
    else
    {
        PodcastFeedInfo subscribed = feed;
        subscribed.lastCheckedMs = QDateTime::currentMSecsSinceEpoch(); // "new" counts from now
        subscribed.newEpisodes = 0;
        m_subscriptions.prepend(subscribed);
        emit message(tr("Subscribed to %1.").arg(feed.title));
    }
    saveSubscriptions();
    updateSubscribeButton();
    // The list's buttons (or the list itself, when it's the subscriptions).
    if (m_listTitle->text() == tr("Subscriptions"))
    {
        showFeedList(m_subscriptions, tr("No subscriptions yet. Search for a show and subscribe to it."));
    }
    else
    {
        showFeedList(m_listed, tr("No podcasts found."), m_listTitle->text() == tr("Top podcasts today"));
    }
}

void PodcastDirectoryPage::updateSubscribeButton()
{
    const bool subscribed = isSubscribed(m_show.feedUrl);
    m_subscribeButton->setText(subscribed ? tr("Subscribed") : tr("Subscribe"));
    m_subscribeButton->setObjectName(subscribed ? QString() : QStringLiteral("PrimaryButton"));
    m_subscribeButton->style()->unpolish(m_subscribeButton);
    m_subscribeButton->style()->polish(m_subscribeButton);
}

void PodcastDirectoryPage::saveSubscriptions() const
{
    QJsonArray array;
    for (const PodcastFeedInfo& feed : m_subscriptions)
    {
        array.append(feed.toJson());
    }
    WriteJsonFile(SubscriptionsFile(), QJsonObject{{"subscriptions", array}});
}

void PodcastDirectoryPage::checkForNewEpisodes()
{
    if (m_subscriptions.isEmpty())
    {
        emit message(tr("Subscribe to a show to check it for new episodes."));
        showSubscriptions();
        return;
    }
    emit message(tr("Checking %1 podcasts for new episodes…").arg(m_subscriptions.size()));
    struct Check
    {
        int left = 0;
        int newEpisodes = 0;
        int showsWithNew = 0;
    };
    auto check = std::make_shared<Check>();
    check->left = m_subscriptions.size();
    const qint64 now = QDateTime::currentMSecsSinceEpoch();
    for (const PodcastFeedInfo& feed : m_subscriptions)
    {
        const QString feedUrl = feed.feedUrl;
        const qint64 since = feed.lastCheckedMs;
        QNetworkReply* reply = NetworkGet(m_network, QUrl(feedUrl));
        connect(reply, &QNetworkReply::finished, this, [this, reply, check, feedUrl, since, now]() {
            reply->deleteLater();
            if (reply->error() == QNetworkReply::NoError)
            {
                int fresh = 0;
                for (const FeedEpisode& episode : ParsePodcastFeed(reply->readAll(), nullptr))
                {
                    if (episode.published.isValid() && episode.published.toMSecsSinceEpoch() > since)
                    {
                        ++fresh;
                    }
                }
                if (const int index = subscriptionIndex(feedUrl); index >= 0)
                {
                    m_subscriptions[index].newEpisodes += fresh;
                    m_subscriptions[index].lastCheckedMs = now;
                }
                check->newEpisodes += fresh;
                check->showsWithNew += fresh > 0 ? 1 : 0;
            }
            if (--check->left > 0)
            {
                return;
            }
            saveSubscriptions();
            emit message(check->newEpisodes == 0 ? tr("No new episodes.")
                                                 : tr("%1 new episodes in %2 podcasts.").arg(check->newEpisodes).arg(check->showsWithNew));
            showSubscriptions();
        });
    }
}

bool PodcastDirectoryPage::eventFilter(QObject* watched, QEvent* event)
{
    if (event->type() != QEvent::MouseButtonRelease)
    {
        return QWidget::eventFilter(watched, event);
    }
    const bool feeds = watched == m_feedList->viewport();
    const bool episodes = watched == m_episodeList->viewport();
    auto* mouse = static_cast<QMouseEvent*>(event);
    if ((!feeds && !episodes) || mouse->button() != Qt::LeftButton)
    {
        return QWidget::eventFilter(watched, event);
    }
    QListWidget* list = feeds ? m_feedList : m_episodeList;
    const QPoint pos = mouse->position().toPoint();
    const QModelIndex index = list->indexAt(pos);
    if (!index.isValid())
    {
        return QWidget::eventFilter(watched, event);
    }
    QFont font = list->font();
    font.setPixelSize(13);
    const bool onButton = OnlineRowDelegate::trailingRect(list->visualRect(index).adjusted(0, 1, 0, -1), index, QFontMetrics(font)).contains(pos);
    const int row = index.data(online_role::Data).toInt();
    if (feeds)
    {
        const PodcastFeedInfo feed = m_listed.value(row);
        if (onButton)
        {
            toggleSubscription(feed);
        }
        else
        {
            openShow(feed);
        }
    }
    else if (row >= 0 && row < m_episodes.size())
    {
        const FeedEpisode episode = m_episodes[row];
        const QString path = episodePath(episode);
        if (QFileInfo::exists(path))
        {
            emit playFile(path, m_show.title); // a downloaded one plays from anywhere on its row
        }
        else if (onButton)
        {
            download(episode);
        }
    }
    return QWidget::eventFilter(watched, event);
}

} // namespace audioforge
