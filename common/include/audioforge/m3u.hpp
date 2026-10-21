#pragma once

#include <QByteArray>
#include <QString>
#include <QStringList>
#include <QVector>

#include "audioforge/track_info.hpp"

namespace audioforge {

// .m3u / .m3u8 playlists (Playlists > + > Import .m3u Playlist, and Export
// .m3u Playlist on a playlist's right-click menu) -- a port of the Android
// app's parseM3u / matchM3u / writeM3u.

// One entry: the path line, and the #EXTINF "Artist - Title" label if there was one.
struct M3uEntry
{
    QString path;
    QString label;
};

// The file's text: UTF-8 (with or without a BOM; .m3u8 always is), else the
// Windows codepage, which plain .m3u files from older players use.
QString DecodeM3u(const QByteArray& bytes);

QVector<M3uEntry> ParseM3u(const QString& text);

// The library paths the entries point to, each once, in file order: the file
// itself (a relative path is resolved against `baseDir`, the playlist's
// folder), else the longest path ending found in the library ("Rock/a.mp3"
// for "D:/Old PC/Music/Rock/a.mp3"), else the file name, else "Artist - Title".
QStringList MatchM3u(const QVector<M3uEntry>& entries, const QVector<TrackInfo>& library, const QString& baseDir);

// An .m3u8 playlist: #EXTINF lines and each song's full path.
QString WriteM3u(const QVector<TrackInfo>& tracks);

} // namespace audioforge
