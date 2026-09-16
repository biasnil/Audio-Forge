#pragma once

#include <QMap>
#include "audioforge/track_info.hpp"

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

    // Adds a folder (no-op if already added), scans it, and appends any new
    // tracks found. Returns false if the folder was already present.
    bool addFolder(const QString& dir);

    // Stops tracking a folder and drops any tracks whose path is under it.
    void removeFolder(const QString& dir);

    // Full rescan of every added folder: picks up new files AND drops ones
    // that no longer exist (since only files QDirIterator still finds get
    // re-added).
    void refreshAll();

    // Re-reads one file's tags in place (used after a MusicBrainz tag write
    // so the in-memory copy matches what was actually saved to disk).
    void refreshTrack(const QString& path);

    const QStringList& folders() const { return m_musicFolders; }
    const QVector<TrackInfo>& tracks() const { return m_tracks; }

    // Returns the cached TrackInfo for a path if it's in the library,
    // otherwise reads it fresh from disk (e.g. a file opened via "Open
    // File..." that was never part of a scanned folder).
    TrackInfo findTrackInfo(const QString& path) const;
    QVector<TrackInfo> buildQueueFromPaths(const QStringList& paths) const;

    // Album/artist name -> track paths. Untagged tracks are grouped under
    // "Unknown Album" / "Unknown Artist" rather than dropped.
    QMap<QString, QStringList> albumGroups() const;
    QMap<QString, QStringList> artistGroups() const;

    Stats stats() const;

    // Playlists are edited in place by dialogs (PlaylistEditDialog takes a
    // PlaylistData&), hence the mutable accessor.
    QVector<PlaylistData>& playlists() { return m_playlists; }
    const QVector<PlaylistData>& playlists() const { return m_playlists; }
    void addPlaylist(const PlaylistData& playlist) { m_playlists << playlist; }
    void removePlaylistAt(int index);

private:
    void scanFolder(const QString& dir);

    QStringList m_musicFolders;
    QVector<TrackInfo> m_tracks;
    QVector<PlaylistData> m_playlists;
};

} // namespace audioforge
