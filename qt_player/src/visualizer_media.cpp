#include "audioforge/visualizer_media.hpp"
#include "audioforge/app_files.hpp"

#include <QFileInfo>
#include <QJsonArray>
#include <QJsonObject>
#include <QUuid>

namespace audioforge {

namespace {

QString MediaPath()
{
    return AppDataFolder() + "/visualizer_media.json";
}

} // namespace

bool VisualizerMediaEntry::isVideo() const
{
    static const QStringList videos = {"mp4", "m4v", "mov", "webm", "mkv", "avi", "wmv"};
    return videos.contains(QFileInfo(path).suffix().toLower());
}

void VisualizerMediaLibrary::load()
{
    m_entries.clear();
    for (const QJsonValue& value : ReadJsonFile(MediaPath())["entries"].toArray())
    {
        const QJsonObject o = value.toObject();
        VisualizerMediaEntry e;
        e.id = o["id"].toString();
        e.path = o["path"].toString();
        e.round = o["round"].toBool(true);
        e.spin = o["spin"].toBool(true);
        e.global = o["global"].toBool();
        for (const QJsonValue& track : o["trackPaths"].toArray())
        {
            e.trackPaths << track.toString();
        }
        if (!e.path.isEmpty())
        {
            if (e.id.isEmpty())
            {
                e.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
            }
            m_entries << e;
        }
    }
}

void VisualizerMediaLibrary::save() const
{
    QJsonArray array;
    for (const VisualizerMediaEntry& e : m_entries)
    {
        array << QJsonObject{{"id", e.id}, {"path", e.path}, {"round", e.round}, {"spin", e.spin},
                             {"global", e.global}, {"trackPaths", QJsonArray::fromStringList(e.trackPaths)}};
    }
    WriteJsonFile(MediaPath(), QJsonObject{{"entries", array}});
}

const VisualizerMediaEntry* VisualizerMediaLibrary::resolveFor(const QString& trackPath) const
{
    for (const VisualizerMediaEntry& e : m_entries)
    {
        if (!e.global && e.trackPaths.contains(trackPath))
        {
            return &e;
        }
    }
    for (const VisualizerMediaEntry& e : m_entries)
    {
        if (e.global)
        {
            return &e;
        }
    }
    return nullptr;
}

VisualizerMediaEntry* VisualizerMediaLibrary::globalEntry()
{
    for (VisualizerMediaEntry& e : m_entries)
    {
        if (e.global)
        {
            return &e;
        }
    }
    return nullptr;
}

void VisualizerMediaLibrary::setGlobalPath(const QString& path)
{
    if (VisualizerMediaEntry* existing = globalEntry())
    {
        existing->path = path;
        return;
    }
    VisualizerMediaEntry e;
    e.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    e.path = path;
    e.global = true;
    m_entries << e;
}

void VisualizerMediaLibrary::clearGlobal()
{
    m_entries.removeIf([](const VisualizerMediaEntry& e) { return e.global; });
}

VisualizerMediaEntry& VisualizerMediaLibrary::addEntry(const QString& path, const QStringList& trackPaths)
{
    VisualizerMediaEntry e;
    e.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    e.path = path;
    e.trackPaths = trackPaths;
    m_entries << e;
    return m_entries.last();
}

void VisualizerMediaLibrary::removeEntry(const QString& id)
{
    m_entries.removeIf([&id](const VisualizerMediaEntry& e) { return e.id == id; });
}

} // namespace audioforge
