#include "audioforge/online_lookup.hpp"
#include "audioforge/widgets/online_views.hpp"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QPointer>
#include <QRegularExpression>
#include <QUrlQuery>

namespace audioforge {

namespace {

// Fetches `url` and hands the JSON object to `done` (empty on any failure),
// unless `context` is gone by then.
void GetJson(QNetworkAccessManager* network, QObject* context, const QUrl& url, const std::function<void(const QJsonObject&)>& done)
{
    QNetworkReply* reply = NetworkGet(network, url);
    QObject::connect(reply, &QNetworkReply::finished, context, [reply, done]() {
        reply->deleteLater();
        done(reply->error() == QNetworkReply::NoError ? QJsonDocument::fromJson(reply->readAll()).object() : QJsonObject());
    });
}

QUrl AppleSearch(const QString& term, const QString& entity)
{
    QUrl url(QStringLiteral("https://itunes.apple.com/search"));
    QUrlQuery query;
    query.addQueryItem("term", term);
    query.addQueryItem("media", "music");
    query.addQueryItem("entity", entity);
    query.addQueryItem("limit", "15");
    url.setQuery(query);
    return url;
}

QUrl DeezerSearch(const QString& path, const QString& q)
{
    QUrl url(QStringLiteral("https://api.deezer.com") + path);
    QUrlQuery query;
    query.addQueryItem("q", q);
    query.addQueryItem("limit", "15");
    url.setQuery(query);
    return url;
}

// The same name, give or take extras and spelling: one contains the other.
bool SameName(const QString& a, const QString& b)
{
    const QString x = NormalizedName(a);
    const QString y = NormalizedName(b);
    return !x.isEmpty() && !y.isEmpty() && (x == y || x.contains(y) || y.contains(x));
}

// Apple's 100 px artwork URL, as a 600 px one.
QString AppleArtwork(const QJsonObject& result)
{
    QString url = result.value("artworkUrl100").toString();
    url.replace(QStringLiteral("100x100bb"), QStringLiteral("600x600bb"));
    return url;
}

} // namespace

QString NormalizedName(const QString& name)
{
    QString text = name.normalized(QString::NormalizationForm_D).toLower();
    text.remove(QRegularExpression(QStringLiteral("[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]")));
    text.remove(QRegularExpression(QStringLiteral("\\p{M}"))); // accents
    text.replace(QRegularExpression(QStringLiteral("[^\\p{L}\\p{N}]+")), QStringLiteral(" "));
    text.remove(QRegularExpression(QStringLiteral("^the ")));
    return text.simplified();
}

void LookupSongInfo(QNetworkAccessManager* network, QObject* context, const QString& artist, const QString& title,
                    const QString& album, const std::function<void(std::optional<OnlineSongInfo>)>& done)
{
    // Apple: one search gives everything, genre and year included.
    GetJson(network, context, AppleSearch(artist + ' ' + title, QStringLiteral("song")),
            [network, context, artist, title, album, done](const QJsonObject& apple) {
        const QJsonArray results = apple.value("results").toArray();
        std::optional<QJsonObject> best;
        for (const QJsonValue& value : results)
        {
            const QJsonObject r = value.toObject();
            if (!SameName(r.value("trackName").toString(), title) || (!artist.isEmpty() && !SameName(r.value("artistName").toString(), artist)))
            {
                continue;
            }
            if (!best || (!album.isEmpty() && SameName(r.value("collectionName").toString(), album)))
            {
                best = r;
                if (!album.isEmpty() && SameName(r.value("collectionName").toString(), album))
                {
                    break; // the very release
                }
            }
        }
        if (best)
        {
            OnlineSongInfo info;
            info.title = best->value("trackName").toString();
            info.artist = best->value("artistName").toString();
            info.album = best->value("collectionName").toString();
            info.albumArtist = best->value("collectionArtistName").toString(info.artist);
            info.genre = best->value("primaryGenreName").toString();
            info.year = best->value("releaseDate").toString().left(4).toInt();
            info.trackNumber = best->value("trackNumber").toInt();
            info.coverUrl = AppleArtwork(*best);
            info.source = QStringLiteral("Apple Music");
            done(info);
            return;
        }
        // Deezer: the track, then its album for the year and genre.
        GetJson(network, context, DeezerSearch(QStringLiteral("/search"), (artist + ' ' + title).trimmed()),
                [network, context, artist, title, done](const QJsonObject& deezer) {
            for (const QJsonValue& value : deezer.value("data").toArray())
            {
                const QJsonObject track = value.toObject();
                const QJsonObject trackAlbum = track.value("album").toObject();
                if (!SameName(track.value("title").toString(), title)
                    || (!artist.isEmpty() && !SameName(track.value("artist").toObject().value("name").toString(), artist)))
                {
                    continue;
                }
                OnlineSongInfo info;
                info.title = track.value("title").toString();
                info.artist = track.value("artist").toObject().value("name").toString();
                info.album = trackAlbum.value("title").toString();
                info.coverUrl = trackAlbum.value("cover_xl").toString();
                info.source = QStringLiteral("Deezer");
                const qint64 albumId = static_cast<qint64>(trackAlbum.value("id").toDouble());
                GetJson(network, context, QUrl(QStringLiteral("https://api.deezer.com/album/%1").arg(albumId)),
                        [info, done](const QJsonObject& albumInfo) mutable {
                    info.year = albumInfo.value("release_date").toString().left(4).toInt();
                    info.albumArtist = albumInfo.value("artist").toObject().value("name").toString(info.artist);
                    const QJsonArray genres = albumInfo.value("genres").toObject().value("data").toArray();
                    if (!genres.isEmpty())
                    {
                        info.genre = genres.first().toObject().value("name").toString();
                    }
                    done(info);
                });
                return;
            }
            done(std::nullopt);
        });
    });
}

void LookupAlbumCover(QNetworkAccessManager* network, QObject* context, const QString& artist, const QString& album,
                      const std::function<void(const QString&, const QString&)>& done)
{
    GetJson(network, context, AppleSearch(artist + ' ' + album, QStringLiteral("album")),
            [network, context, artist, album, done](const QJsonObject& apple) {
        for (const QJsonValue& value : apple.value("results").toArray())
        {
            const QJsonObject r = value.toObject();
            if (SameName(r.value("collectionName").toString(), album)
                && (artist.isEmpty() || SameName(r.value("artistName").toString(), artist)))
            {
                done(AppleArtwork(r), QStringLiteral("Apple Music"));
                return;
            }
        }
        // Plain words (Deezer's artist:"..." field search misses a lot); the results are matched by name.
        const QString q = (artist + ' ' + album).trimmed();
        GetJson(network, context, DeezerSearch(QStringLiteral("/search/album"), q), [album, artist, done](const QJsonObject& deezer) {
            for (const QJsonValue& value : deezer.value("data").toArray())
            {
                const QJsonObject r = value.toObject();
                if (SameName(r.value("title").toString(), album)
                    && (artist.isEmpty() || SameName(r.value("artist").toObject().value("name").toString(), artist)))
                {
                    done(r.value("cover_xl").toString(), QStringLiteral("Deezer"));
                    return;
                }
            }
            done(QString(), QString());
        });
    });
}

void LookupSongCover(QNetworkAccessManager* network, QObject* context, const QString& artist, const QString& title,
                     const std::function<void(const QString&, const QString&)>& done)
{
    LookupSongInfo(network, context, artist, title, QString(), [done](std::optional<OnlineSongInfo> info) {
        done(info ? info->coverUrl : QString(), info ? info->source : QString());
    });
}

} // namespace audioforge
