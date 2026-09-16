#include "audioforge/music_library.hpp"
#include "audioforge/track_metadata.hpp"

#include <QDir>
#include <QDirIterator>
#include <QFileInfo>
#include <QSet>

namespace audioforge {

bool MusicLibrary::addFolder(const QString& dir)
{
    if (m_musicFolders.contains(dir))
    {
        return false;
    }
    m_musicFolders << dir;
    scanFolder(dir);
    return true;
}

void MusicLibrary::removeFolder(const QString& dir)
{
    m_musicFolders.removeAll(dir);

    QString prefix = QDir(dir).absolutePath();
    for (int i = m_tracks.size() - 1; i >= 0; --i)
    {
        if (QFileInfo(m_tracks[i].path).absoluteFilePath().startsWith(prefix))
        {
            m_tracks.removeAt(i);
        }
    }
}

void MusicLibrary::refreshAll()
{
    m_tracks.clear();
    for (const QString& dir : m_musicFolders)
    {
        scanFolder(dir);
    }
}

void MusicLibrary::refreshTrack(const QString& path)
{
    for (TrackInfo& t : m_tracks)
    {
        if (t.path == path)
        {
            t = ReadTrackInfo(path);
            return;
        }
    }
}

void MusicLibrary::scanFolder(const QString& dir)
{
    QDirIterator it(dir, QStringList() << "*.mp3", QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext())
    {
        QString path = it.next();
        bool alreadyKnown = false;
        for (const TrackInfo& t : m_tracks)
        {
            if (t.path == path)
            {
                alreadyKnown = true;
                break;
            }
        }
        if (!alreadyKnown)
        {
            m_tracks << ReadTrackInfo(path);
        }
    }
}

TrackInfo MusicLibrary::findTrackInfo(const QString& path) const
{
    for (const TrackInfo& t : m_tracks)
    {
        if (t.path == path)
        {
            return t;
        }
    }
    return ReadTrackInfo(path); // not in the library -- read fresh
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
        QString album = t.album.isEmpty() ? "Unknown Album" : t.album;
        result[album] << t.path;
    }
    return result;
}

QMap<QString, QStringList> MusicLibrary::artistGroups() const
{
    QMap<QString, QStringList> result;
    for (const TrackInfo& t : m_tracks)
    {
        QString artist = t.artist.isEmpty() ? "Unknown Artist" : t.artist;
        result[artist] << t.path;
    }
    return result;
}

MusicLibrary::Stats MusicLibrary::stats() const
{
    QSet<QString> albums, artists, genres;
    for (const TrackInfo& t : m_tracks)
    {
        albums.insert(t.album.isEmpty() ? "Unknown Album" : t.album);
        artists.insert(t.artist.isEmpty() ? "Unknown Artist" : t.artist);
        if (!t.genre.isEmpty())
        {
            genres.insert(t.genre);
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
