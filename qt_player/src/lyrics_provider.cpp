#include "audioforge/lyrics_provider.hpp"

#include <QFile>
#include <QFileInfo>
#include <QDir>
#include <QStandardPaths>
#include <QCryptographicHash>
#include <QRegularExpression>
#include <QStringList>

#include <algorithm>

namespace audioforge {

namespace {

QString ReadWholeFile(const QString& path)
{
    QFile file(path);
    if (!file.exists() || !file.open(QIODevice::ReadOnly | QIODevice::Text))
    {
        return QString();
    }
    return QString::fromUtf8(file.readAll());
}

// %TEMP%/AudioForge/lyrics -- created on first use.
QString CacheDir()
{
    QString dir = QDir(QStandardPaths::writableLocation(QStandardPaths::TempLocation)).filePath("AudioForge/lyrics");
    QDir().mkpath(dir);
    return dir;
}

// The track's own path, hashed, so the cache filename doesn't have to
// wrestle with unicode/special characters in artist or title names.
QString CacheKeyFor(const QString& trackPath)
{
    return QString::fromLatin1(QCryptographicHash::hash(trackPath.toUtf8(), QCryptographicHash::Md5).toHex());
}

} // namespace

QVector<SyncedLyricLine> ParseSyncedLyrics(const QString& lrcText)
{
    // Matches one [mm:ss] or [mm:ss.xx] tag; a line can have several run
    // together (e.g. "[00:12.00][00:45.30]word repeats here").
    static const QRegularExpression tagPattern(R"(\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\])");

    QVector<SyncedLyricLine> lines;
    for (const QString& rawLine : lrcText.split('\n'))
    {
        QVector<float> timestamps;
        int consumedUpTo = 0;

        QRegularExpressionMatchIterator it = tagPattern.globalMatch(rawLine);
        while (it.hasNext())
        {
            QRegularExpressionMatch match = it.next();
            if (match.capturedStart() != consumedUpTo)
            {
                break; // tags must run contiguously from the start of the line
            }

            int minutes = match.captured(1).toInt();
            int seconds = match.captured(2).toInt();
            QString fraction = match.captured(3);

            float frac = 0.0f;
            if (!fraction.isEmpty())
            {
                int divisor = 1;
                for (int i = 0; i < fraction.length(); ++i)
                {
                    divisor *= 10;
                }
                frac = static_cast<float>(fraction.toInt()) / divisor;
            }

            timestamps << (minutes * 60.0f + seconds + frac);
            consumedUpTo = match.capturedEnd();
        }

        if (timestamps.isEmpty())
        {
            continue; // plain text, or a non-time metadata tag like [ar:...]
        }

        QString text = rawLine.mid(consumedUpTo).trimmed();
        for (float seconds : timestamps)
        {
            lines.append({seconds, text});
        }
    }

    std::sort(lines.begin(), lines.end(), [](const SyncedLyricLine& a, const SyncedLyricLine& b) {
        return a.seconds < b.seconds;
    });
    return lines;
}

LyricsResult ReadSidecarLyrics(const QString& trackPath)
{
    QFileInfo info(trackPath);
    QString base = info.dir().filePath(info.completeBaseName());

    QString lrcText = ReadWholeFile(base + ".lrc");
    if (!lrcText.isEmpty())
    {
        return {lrcText, true};
    }

    QString txtText = ReadWholeFile(base + ".txt");
    if (!txtText.isEmpty())
    {
        return {txtText.trimmed(), false};
    }
    return {};
}

LyricsResult ReadCachedLyrics(const QString& trackPath)
{
    QString key = CacheKeyFor(trackPath);
    QString dir = CacheDir();

    QString lrcText = ReadWholeFile(dir + "/" + key + ".lrc");
    if (!lrcText.isEmpty())
    {
        return {lrcText, true};
    }

    QString txtText = ReadWholeFile(dir + "/" + key + ".txt");
    if (!txtText.isEmpty())
    {
        return {txtText.trimmed(), false};
    }
    return {};
}

void WriteCachedLyrics(const QString& trackPath, const QString& lyricsText, bool synced)
{
    if (lyricsText.trimmed().isEmpty())
    {
        return;
    }

    QString path = CacheDir() + "/" + CacheKeyFor(trackPath) + (synced ? ".lrc" : ".txt");
    QFile file(path);
    if (file.open(QIODevice::WriteOnly | QIODevice::Text))
    {
        file.write(lyricsText.toUtf8());
    }
    // If the write fails (disk full, permissions, whatever) there's simply
    // no cache for next time -- not worth surfacing to the user over a
    // background convenience like this.
}

} // namespace audioforge