#pragma once

#include <QString>
#include <QStringList>
#include <QVector>

namespace audioforge {

// Settings > Visualizer > Custom GIFs and Videos: a GIF or short looping
// video the visualizer shows instead of its style -- for the songs in
// trackPaths, or for every song without its own when global. Like the
// Android app's CustomMedia, and kept apart from the video wallpapers
// (which play behind the page, not in the cover's spot).
struct VisualizerMediaEntry
{
    QString id;
    QString path;
    bool round = true; // cropped to a circle
    bool spin = true;  // turns while music plays, like a record
    QStringList trackPaths;
    bool global = false;

    bool isVideo() const; // by extension; anything else is an image (GIF, WebP, PNG...)
};

// visualizer_media.json next to AudioForge.ini.
class VisualizerMediaLibrary
{
public:
    void load();
    void save() const;

    QVector<VisualizerMediaEntry>& entries() { return m_entries; }
    const QVector<VisualizerMediaEntry>& entries() const { return m_entries; }

    // The song's own item (the first that lists it), else the global one; null = show the style.
    const VisualizerMediaEntry* resolveFor(const QString& trackPath) const;

    VisualizerMediaEntry* globalEntry();
    void setGlobalPath(const QString& path); // keeps the old global's shape and spin
    void clearGlobal();
    VisualizerMediaEntry& addEntry(const QString& path, const QStringList& trackPaths);
    void removeEntry(const QString& id);

private:
    QVector<VisualizerMediaEntry> m_entries;
};

} // namespace audioforge
