#include "audioforge/wallpaper_library.hpp"

#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStandardPaths>
#include <QUuid>

namespace audioforge {

QString WallpaperLibrary::wallpapersJsonPath()
{
    // Same %APPDATA%\AudioForge\ folder AudioForge.ini and warnings.log
    // already live in (see ErrorReporter::appendToLog in warnings.cpp).
    QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);
    return dir + "/wallpapers.json";
}

bool WallpaperLibrary::load()
{
    QFile file(wallpapersJsonPath());
    if (!file.open(QIODevice::ReadOnly))
    {
        // No file yet (first run) -- not an error, just start empty.
        return false;
    }

    QJsonObject root = QJsonDocument::fromJson(file.readAll()).object();

    m_globalVideoPath = root.value("globalVideoPath").toString();

    m_entries.clear();
    for (const QJsonValue& entryValue : root.value("entries").toArray())
    {
        QJsonObject obj = entryValue.toObject();

        WallpaperEntry entry;
        entry.id = obj.value("id").toString();
        entry.videoPath = obj.value("videoPath").toString();
        for (const QJsonValue& trackPath : obj.value("trackPaths").toArray())
        {
            entry.trackPaths << trackPath.toString();
        }
        m_entries << entry;
    }

    return true;
}

bool WallpaperLibrary::save() const
{
    QJsonObject root;
    root["globalVideoPath"] = m_globalVideoPath;

    QJsonArray entries;
    for (const WallpaperEntry& entry : m_entries)
    {
        QJsonObject obj;
        obj["id"] = entry.id;
        obj["videoPath"] = entry.videoPath;
        obj["trackPaths"] = QJsonArray::fromStringList(entry.trackPaths);
        entries << obj;
    }
    root["entries"] = entries;

    QFile file(wallpapersJsonPath());
    if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate))
    {
        return false;
    }

    file.write(QJsonDocument(root).toJson());
    return true;
}

WallpaperEntry& WallpaperLibrary::addEntry()
{
    WallpaperEntry entry;
    entry.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
    m_entries << entry;
    return m_entries.last();
}

void WallpaperLibrary::removeEntryAt(int index)
{
    if (index >= 0 && index < m_entries.size())
    {
        m_entries.removeAt(index);
    }
}

QString WallpaperLibrary::resolveVideoFor(const QString& trackPath) const
{
    for (const WallpaperEntry& entry : m_entries)
    {
        if (entry.trackPaths.contains(trackPath))
        {
            return entry.videoPath;
        }
    }
    return m_globalVideoPath; // may be empty -- "no wallpaper" is valid
}

} // namespace audioforge
