#include "audioforge/app_files.hpp"
#include "audioforge/warnings.hpp"

#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QProcess>
#include <QSaveFile>
#include <QSettings>
#include <QUrl>

namespace audioforge {

QString AppDataFolder()
{
    QSettings settings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge");
    return QFileInfo(settings.fileName()).absolutePath();
}

QJsonObject ReadJsonFile(const QString& path)
{
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly))
    {
        return QJsonObject();
    }
    return QJsonDocument::fromJson(file.readAll()).object();
}

void WriteJsonFile(const QString& path, const QJsonObject& object)
{
    QDir().mkpath(QFileInfo(path).absolutePath()); // first run: the folder may not exist yet
    QSaveFile file(path);
    if (!file.open(QIODevice::WriteOnly) || file.write(QJsonDocument(object).toJson(QJsonDocument::Compact)) < 0
        || !file.commit())
    {
        ErrorReporter::logOnly("Could not save " + path);
    }
}

void ShowInFileManager(const QString& path)
{
#ifdef Q_OS_WIN
    QProcess::startDetached("explorer.exe", {"/select,", QDir::toNativeSeparators(path)});
#else
    QDesktopServices::openUrl(QUrl::fromLocalFile(QFileInfo(path).absolutePath()));
#endif
}

} // namespace audioforge
