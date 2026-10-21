#pragma once

#include <QHash>
#include <QMap>
#include "audioforge/track_info.hpp"

#include <atomic>
#include <functional>

namespace audioforge {

// Owns the set of scanned folders, the tracks found in them, and the
// playlists built from those tracks. No Qt-widget dependency -- PlayerWindow
// reads from this to populate the Tracks/Albums/Artists/Folders/Playlists
// tabs, and writes to it via addFolder/removeFolder/addPlaylist/etc.
class MusicLibrary
{
public:
    struct Stats
    {
        int trackCount = 0;
        int albumCount = 0;
        int artistCount = 0;
        int genreCount = 0;
    };

    // Records a folder (its songs arrive with the next scan -- see below).
    // Returns false if it was already added.
    bool addFolder(const QString& dir);

    // Stops tracking a folder and drops any tracks whose path is under it.
    void removeFolder(const QString& dir);

    // Re-reads one file's tags in place (used after a MusicBrainz tag write
    // so the in-memory copy matches what was actually saved to disk).
    void refreshTrack(const QString& path);

    // --- Background scanning ---------------------------------------------
    // Reading every file's tags takes a while (seconds for a few hundred
    // songs), so PlayerWindow scans off the UI thread: it sets the folder
    // list, runs ScanFolders() on a worker thread, then hands the result to
    // setTracks().

    void setFolders(const QStringList& folders) { m_musicFolders = folders; }
    void setTracks(const QVector<TrackInfo>& tracks); // music and podcasts; split by the podcast folders

    // --- Podcasts ---------------------------------------------------------------
    // Folders marked Podcast in the Folders tab (a subset of folders()). Their
    // songs are kept apart: tracks() and everything built on it (Tracks,
    // Albums, Artists, smart playlists, duplicates, analysis) never see
    // them -- only the Podcasts tab, through podcasts().
    const QStringList& podcastFolders() const { return m_podcastFolders; }
    void setPodcastFolders(const QStringList& folders); // re-splits the tracks
    bool isPodcastFolder(const QString& dir) const { return m_podcastFolders.contains(dir); }
    bool isPodcast(const QString& path) const;
    const QVector<TrackInfo>& podcasts() const { return m_podcasts; }
    // The podcast folder an episode is in (the deepest one, if they nest).
    QString podcastFolderOf(const QString& path) const;

    // Every .mp3 under `folders` -- plus .m4a/.m4b/.aac in `podcastFolders`,
    // the usual podcast formats. A file in `known` whose modified time
    // hasn't changed is reused as-is; only new or changed files are read.
    // `progress(done, total)` is called from the scanning thread; returns
    // early (partial result) once `cancelled` is set. Thread-safe.
    static QVector<TrackInfo> ScanFolders(const QStringList& folders, const QStringList& podcastFolders,
                                          const QHash<QString, TrackInfo>& known,
                                          const std::function<void(int, int)>& progress,
                                          const std::atomic<bool>& cancelled);

    // library.json next to AudioForge.ini: the last scan's tags, so a launch
    // shows the library straight away and only re-reads changed files.
    static QVector<TrackInfo> LoadCache();
    static void SaveCache(const QVector<TrackInfo>& tracks);

    const QStringList& folders() const { return m_musicFolders; }
    const QVector<TrackInfo>& tracks() const { return m_tracks; }       // music only
    const QVector<TrackInfo>& allTracks() const { return m_allTracks; } // music and podcasts (cache, scanning)

    // Returns the cached TrackInfo for a path if it's in the library,
    // otherwise reads it fresh from disk (e.g. a file opened via "Open
    // File..." that was never part of a scanned folder). Cover art is never
    // loaded here -- use ReadCoverArt() where it's actually displayed.
    TrackInfo findTrackInfo(const QString& path) const;
    QVector<TrackInfo> buildQueueFromPaths(const QStringList& paths) const;

    // Album/artist name -> track paths. Untagged tracks are grouped under
    // "Unknown Album" / "Unknown Artist" rather than dropped.
    QMap<QString, QStringList> albumGroups() const;
    QMap<QString, QStringList> artistGroups() const;

    Stats stats(const std::function<QString(const TrackInfo&)>& genreOf) const; // genreOf: the shown genre

    // Playlists are edited in place by dialogs (PlaylistEditDialog takes a
    // PlaylistData&), hence the mutable accessor.
    QVector<PlaylistData>& playlists() { return m_playlists; }
    const QVector<PlaylistData>& playlists() const { return m_playlists; }
    void addPlaylist(const PlaylistData& playlist) { m_playlists << playlist; }
    void removePlaylistAt(int index);

private:
    void rebuildIndex(); // also re-splits m_allTracks into m_tracks / m_podcasts

    QStringList m_musicFolders;
    QStringList m_podcastFolders;
    QVector<TrackInfo> m_allTracks;
    QVector<TrackInfo> m_tracks;
    QVector<TrackInfo> m_podcasts;
    QHash<QString, int> m_indexByPath; // path -> position in m_allTracks; findTrackInfo() was a linear scan
    QVector<PlaylistData> m_playlists;
};

} // namespace audioforge
