#include "audioforge/m3u.hpp"

#include <QDir>
#include <QFileInfo>
#include <QHash>
#include <QSet>
#include <QStringDecoder>
#include <QUrl>

#include <optional>

namespace audioforge {

namespace {

// Lower-case, forward slashes -- Windows paths are case-insensitive.
QString Normalize(const QString& path)
{
    return QDir::fromNativeSeparators(path).toLower();
}

QStringList PathParts(const QString& normalized)
{
    return normalized.split('/', Qt::SkipEmptyParts);
}

} // namespace

QString DecodeM3u(const QByteArray& bytes)
{
    if (std::optional<QStringConverter::Encoding> bom = QStringConverter::encodingForData(bytes))
    {
        QStringDecoder decoder(*bom);
        return decoder(bytes);
    }
    QStringDecoder utf8(QStringConverter::Utf8);
    const QString text = utf8(bytes);
    return utf8.hasError() ? QString::fromLocal8Bit(bytes) : text;
}

QVector<M3uEntry> ParseM3u(const QString& text)
{
    QVector<M3uEntry> entries;
    QString pendingLabel;
    for (QString line : text.split('\n'))
    {
        line = line.trimmed();
        if (line.startsWith(QChar(0xFEFF)))
        {
            line.remove(0, 1); // a byte-order mark left in the text
        }
        if (line.isEmpty())
        {
            continue;
        }
        if (line.startsWith("#EXTINF:", Qt::CaseInsensitive))
        {
            const int comma = line.indexOf(',');
            pendingLabel = comma >= 0 ? line.mid(comma + 1).trimmed() : QString();
            continue;
        }
        if (line.startsWith('#'))
        {
            continue;
        }
        entries.append({line, pendingLabel});
        pendingLabel.clear();
    }
    return entries;
}

QStringList MatchM3u(const QVector<M3uEntry>& entries, const QVector<TrackInfo>& library, const QString& baseDir)
{
    QHash<QString, QString> byPath;    // every ending of every library path -> path
    QHash<QString, QString> byName;
    QHash<QString, QString> byLabel;
    for (const TrackInfo& track : library)
    {
        const QStringList parts = PathParts(Normalize(track.path));
        for (int start = 0; start < parts.size(); ++start)
        {
            const QString ending = parts.mid(start).join('/');
            if (!byPath.contains(ending))
            {
                byPath.insert(ending, track.path);
            }
        }
        const QString name = QFileInfo(track.path).fileName().toLower();
        if (!byName.contains(name))
        {
            byName.insert(name, track.path);
        }
        const QString label = (track.artist + " - " + track.title).toLower();
        if (!byLabel.contains(label))
        {
            byLabel.insert(label, track.path);
        }
    }

    QSet<QString> seen;
    QStringList result;
    for (const M3uEntry& entry : entries)
    {
        QString path = entry.path;
        if (path.startsWith("file:", Qt::CaseInsensitive))
        {
            path = QUrl(path).toLocalFile(); // file:///C:/Music/a%20b.mp3
        }
        if (QFileInfo(path).isRelative())
        {
            path = QDir(baseDir).absoluteFilePath(path);
        }

        QString match;
        const QStringList parts = PathParts(Normalize(QDir::cleanPath(path)));
        for (int start = 0; start < parts.size() && match.isEmpty(); ++start)
        {
            match = byPath.value(parts.mid(start).join('/'));
        }
        if (match.isEmpty() && !parts.isEmpty())
        {
            match = byName.value(parts.last());
        }
        if (match.isEmpty() && !entry.label.isEmpty())
        {
            match = byLabel.value(entry.label.toLower());
        }
        if (!match.isEmpty() && !seen.contains(match))
        {
            seen.insert(match);
            result << match;
        }
    }
    return result;
}

QString WriteM3u(const QVector<TrackInfo>& tracks)
{
    QString text = "#EXTM3U\n";
    for (const TrackInfo& t : tracks)
    {
        const QString title = t.title.isEmpty() ? QFileInfo(t.path).completeBaseName() : t.title;
        text += QString("#EXTINF:%1,%2\n")
                    .arg(static_cast<int>(t.durationSeconds + 0.5))
                    .arg(t.artist.isEmpty() ? title : t.artist + " - " + title);
        text += QDir::toNativeSeparators(t.path) + '\n';
    }
    return text;
}

} // namespace audioforge
