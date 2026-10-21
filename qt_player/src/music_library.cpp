#include "audioforge/music_library.hpp"
#include "audioforge/track_metadata.hpp"

#include "audioforge/app_files.hpp"
#include "audioforge/ffmpeg_audio.hpp"
#include "audioforge/translations.hpp"

#include <QDateTime>
#include <QDir>
#include <QDirIterator>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonObject>
#include <QSet>

namespace audioforge {


bool MusicLibrary::addFolder(const QString& dir)
{
    if (m_musicFolders.contains(dir))
    {
        return false;
    }
    m_musicFolders << dir;
    return true;
}

namespace {

// "C:/Music/" -- the trailing slash so "C:/Music" doesn't also match "C:/Music2".
QString FolderPrefix(const QString& dir)
{
    QString prefix = QDir(dir).absolutePath();
    if (!prefix.endsWith('/'))
    {
        prefix += '/';
    }
    return prefix;
}

} // namespace

bool MusicLibrary::isPodcast(const QString& path) const
{
    return !podcastFolderOf(path).isEmpty();
}

QString MusicLibrary::podcastFolderOf(const QString& path) const
{
    QString best;
    for (const QString& dir : m_podcastFolders)
    {
        // Windows paths are case-insensitive.
        if (path.startsWith(FolderPrefix(dir), Qt::CaseInsensitive) && dir.size() > best.size())
        {
            best = dir;
        }
    }
    return best;
}

void MusicLibrary::setPodcastFolders(const QStringList& folders)
{
    m_podcastFolders = folders;
    rebuildIndex();
}

QVector<TrackInfo> MusicLibrary::ScanFolders(const QStringList& folders, const QStringList& podcastFolders,
                                             const QHash<QString, TrackInfo>& known,
                                             const std::function<void(int, int)>& progress,
                                             const std::atomic<bool>& cancelled)
{
    // 1. Find the files (fast -- directory listings only).
    QStringList paths;
    QSet<QString> seen;
    for (const QString& dir : folders)
    {
        QStringList patterns = {"*.mp3"};
        if (podcastFolders.contains(dir))
        {
            patterns << "*.m4a" << "*.m4b" << "*.aac"; // podcasts often aren't MP3 (played through FFmpeg)
        }
        QDirIterator it(dir, patterns, QDir::Files, QDirIterator::Subdirectories);
        while (it.hasNext() && !cancelled.load())
        {
            const QString path = it.next();
            if (!seen.contains(path)) // overlapping folders (one inside another) list a file twice
            {
                seen.insert(path);
                paths << path;
            }
        }
    }

    // 2. Tags: reuse what's known for unchanged files, read the rest (the slow part).
    QVector<TrackInfo> tracks;
    tracks.reserve(paths.size());
    for (int i = 0; i < paths.size() && !cancelled.load(); ++i)
    {
        const QString& path = paths[i];
        auto it = known.constFind(path);
        const QFileInfo file(path);
        if (it != known.constEnd() && it->modifiedMs != 0
            && it->modifiedMs == file.lastModified().toMSecsSinceEpoch())
        {
            tracks << it.value();
            if (tracks.last().addedMs == 0)
            {
                tracks.last().addedMs = FileAddedMs(file); // cached by AudioForge 1.3 or older
            }
        }
        else
        {
            tracks << ReadTrackInfo(path, /*includeCoverArt=*/false);
        }
        if (progress)
        {
            progress(i + 1, paths.size());
        }
    }
    return tracks;
}

namespace {

QString CachePath()
{
    return AppDataFolder() + "/library.json";
}

constexpr int kCacheVersion = 1;

} // namespace

QVector<TrackInfo> MusicLibrary::LoadCache()
{
    const QJsonObject root = ReadJsonFile(CachePath());
    if (root["version"].toInt() != kCacheVersion)
    {
        return {}; // missing, or an older/newer format -- just scan
    }
    QVector<TrackInfo> tracks;
    for (const QJsonValue& value : root["tracks"].toArray())
    {
        const QJsonObject o = value.toObject();
        TrackInfo t;
        t.path = o["path"].toString();
        t.title = o["title"].toString();
        t.artist = o["artist"].toString();
        t.album = o["album"].toString();
        t.genre = o["genre"].toString();
        t.year = static_cast<unsigned int>(o["year"].toInt());
        t.replayGainDb = static_cast<float>(o["replayGainDb"].toDouble());
        t.bitrateKbps = o["bitrate"].toInt();
        t.sampleRateHz = o["sampleRate"].toInt();
        t.channels = o["channels"].toInt();
        t.durationSeconds = o["duration"].toDouble();
        t.modifiedMs = static_cast<qint64>(o["modified"].toDouble());
        t.addedMs = static_cast<qint64>(o["added"].toDouble());
        if (!t.path.isEmpty())
        {
            tracks << t;
        }
    }
    return tracks;
}

void MusicLibrary::SaveCache(const QVector<TrackInfo>& tracks)
{
    QJsonArray array;
    for (const TrackInfo& t : tracks)
    {
        array << QJsonObject{
            {"path", t.path}, {"title", t.title}, {"artist", t.artist}, {"album", t.album}, {"genre", t.genre},
            {"year", static_cast<int>(t.year)}, {"replayGainDb", t.replayGainDb}, {"bitrate", t.bitrateKbps},
            {"sampleRate", t.sampleRateHz}, {"channels", t.channels}, {"duration", t.durationSeconds},
            {"modified", static_cast<double>(t.modifiedMs)}, {"added", static_cast<double>(t.addedMs)},
        };
    }
    WriteJsonFile(CachePath(), QJsonObject{{"version", kCacheVersion}, {"tracks", array}});
}

void MusicLibrary::removeFolder(const QString& dir)
{
    m_musicFolders.removeAll(dir);
    m_podcastFolders.removeAll(dir);

    // Trailing separator so removing "C:/Music" doesn't also drop tracks
    // under a sibling folder like "C:/Music2".
    const QString prefix = FolderPrefix(dir);
    for (int i = m_allTracks.size() - 1; i >= 0; --i)
    {
        if (QFileInfo(m_allTracks[i].path).absoluteFilePath().startsWith(prefix))
        {
            m_allTracks.removeAt(i);
        }
    }
    rebuildIndex();
}

void MusicLibrary::refreshTrack(const QString& path)
{
    auto it = m_indexByPath.constFind(path);
    if (it != m_indexByPath.constEnd())
    {
        m_allTracks[it.value()] = ReadTrackInfo(path, /*includeCoverArt=*/false); // same path, so the index stays right
        rebuildIndex(); // ...but the music / podcast copies need the new tags
    }
}

void MusicLibrary::setTracks(const QVector<TrackInfo>& tracks)
{
    m_allTracks = tracks;
    rebuildIndex();
}

void MusicLibrary::rebuildIndex()
{
    m_indexByPath.clear();
    m_indexByPath.reserve(m_allTracks.size());
    m_tracks.clear();
    m_podcasts.clear();
    for (int i = 0; i < m_allTracks.size(); ++i)
    {
        const TrackInfo& t = m_allTracks[i];
        m_indexByPath.insert(t.path, i);
        if (isPodcast(t.path))
        {
            m_podcasts << t;
        }
        else if (!IsFfmpegAudioFile(t.path)) // .m4a only belongs to podcast folders (left over from one unmarked)
        {
            m_tracks << t;
        }
    }
}

TrackInfo MusicLibrary::findTrackInfo(const QString& path) const
{
    // Called for every row when a list starts playing -- an index, not a
    // scan, or a big library takes seconds per click.
    auto it = m_indexByPath.constFind(path);
    if (it != m_indexByPath.constEnd() && it.value() < m_allTracks.size() && m_allTracks[it.value()].path == path)
    {
        return m_allTracks[it.value()];
    }
    return ReadTrackInfo(path, /*includeCoverArt=*/false); // not in the library -- read fresh
}

QVector<TrackInfo> MusicLibrary::buildQueueFromPaths(const QStringList& paths) const
{
    QVector<TrackInfo> queue;
    for (const QString& path : paths)
    {
        queue << findTrackInfo(path);
    }
    return queue;
}

QMap<QString, QStringList> MusicLibrary::albumGroups() const
{
    QMap<QString, QStringList> result;
    for (const TrackInfo& t : m_tracks)
    {
        QString album = t.album.isEmpty() ? Tr("Unknown Album") : t.album;
        result[album] << t.path;
    }
    return result;
}

QMap<QString, QStringList> MusicLibrary::artistGroups() const
{
    QMap<QString, QStringList> result;
    for (const TrackInfo& t : m_tracks)
    {
        QString artist = t.artist.isEmpty() ? Tr("Unknown Artist") : t.artist;
        result[artist] << t.path;
    }
    return result;
}

MusicLibrary::Stats MusicLibrary::stats(const std::function<QString(const TrackInfo&)>& genreOf) const
{
    QSet<QString> albums, artists, genres;
    for (const TrackInfo& t : m_tracks)
    {
        albums.insert(t.album.isEmpty() ? Tr("Unknown Album") : t.album);
        artists.insert(t.artist.isEmpty() ? Tr("Unknown Artist") : t.artist);
        const QString genre = genreOf(t);
        if (!genre.isEmpty())
        {
            genres.insert(genre);
        }
    }

    Stats s;
    s.trackCount = m_tracks.size();
    s.albumCount = albums.size();
    s.artistCount = artists.size();
    s.genreCount = genres.size();
    return s;
}

void MusicLibrary::removePlaylistAt(int index)
{
    if (index >= 0 && index < m_playlists.size())
    {
        m_playlists.removeAt(index);
    }
}

} // namespace audioforge
