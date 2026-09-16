#pragma once

#include <QVector>
#include "audioforge/wallpaper_data.hpp"

namespace audioforge {

// Owns the live video wallpaper configuration: the one global fallback
// video, plus the set of per-track WallpaperEntry assignments. Persisted
// to %APPDATA%\AudioForge\wallpapers.json (not QSettings -- too much
// nested data for INI key-value pairs). Pairs with MusicLibrary; no Qt
// widget dependency of its own.
class WallpaperLibrary
{
public:
    // Reads wallpapers.json from the AudioForge AppData folder. A missing
    // file (first run) is not an error -- leaves globalVideoPath empty and
    // entries() empty, same as a fresh MusicLibrary with no folders added.
    // Returns false only in that "nothing to load yet" case.
    bool load();

    // Writes wallpapers.json. Returns false if the file couldn't be opened
    // for writing -- caller decides whether that's worth surfacing via
    // ErrorReporter.
    bool save() const;

    QString globalVideoPath() const { return m_globalVideoPath; }
    void setGlobalVideoPath(const QString& path) { m_globalVideoPath = path; }

    // Entries are edited in place by the Wallpapers tab's dialogs, hence
    // the mutable accessor (same pattern as MusicLibrary::playlists()).
    QVector<WallpaperEntry>& entries() { return m_entries; }
    const QVector<WallpaperEntry>& entries() const { return m_entries; }

    // Generates a fresh id, appends a new entry, and returns a reference to
    // it so the caller (the "Add Wallpaper..." flow) can fill in videoPath
    // and trackPaths.
    WallpaperEntry& addEntry();
    void removeEntryAt(int index);

    // Resolution logic from the spec: the first entry (in entries() order)
    // whose trackPaths contains trackPath wins; otherwise fall back to
    // globalVideoPath() (which may itself be empty -- "no wallpaper").
    QString resolveVideoFor(const QString& trackPath) const;

private:
    static QString wallpapersJsonPath();

    QString m_globalVideoPath;
    QVector<WallpaperEntry> m_entries;
};

} // namespace audioforge
