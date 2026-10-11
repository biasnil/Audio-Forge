#pragma once

#include <QJsonObject>
#include <QString>

namespace audioforge {

// The folder AudioForge.ini lives in (%APPDATA%\audioforge on Windows) --
// every other data file (library cache, analysis, queue...) sits next to it.
QString AppDataFolder();

// Reads a JSON object file; empty if it's missing or unreadable.
QJsonObject ReadJsonFile(const QString& path);

// Writes `object` to `path` via a temp file + rename, so a crash mid-write
// can't leave half a file. Creates the folder if needed; logs a failure.
void WriteJsonFile(const QString& path, const QJsonObject& object);

// Opens Explorer with the file selected (the containing folder elsewhere).
void ShowInFileManager(const QString& path);

} // namespace audioforge
