#pragma once

#include <QByteArray>
#include <QMap>
#include <QString>
#include <QStringList>
#include <QVector>

#include <functional>

// Writing finished audio files for the downloaders (audiobooks, podcasts,
// "Download from link"): parts joined into one MP4 audio file (.m4a/.m4b)
// without re-encoding, with chapters, tags and a cover -- through the FFmpeg
// libraries the app already uses for playback, so no ffmpeg.exe is needed.
namespace audioforge {

struct ChapterMark
{
    double startSeconds = 0.0;
    QString title;
};

struct JoinRequest
{
    QStringList inputs;            // played one after another, all the same codec (MP3 or AAC, usually)
    QStringList partTitles;        // one chapter per input (when there's one title per input)...
    QVector<ChapterMark> chapters; // ...or these, for a single input that has its own chapters
    QString output;                // .m4a or .m4b

    // Tags (any can be empty).
    QString title;
    QString artist;
    QString album;
    QString genre;
    QString comment;
    int year = 0;
    QByteArray coverJpeg;
};

// Blocking (minutes for a long book on a slow disk): run it off the UI
// thread. `progress(0..1)` can return false to cancel. Returns an empty
// string on success, else what went wrong; nothing is left at `output` then.
QString JoinAudioToMp4(const JoinRequest& request, const std::function<bool(double)>& progress = {});

// An MP4 / M4B file's chapters (empty if it has none or isn't one).
QVector<ChapterMark> ReadChapters(const QString& path);

// Any image the app can decode, cropped to a centered square, as JPEG (at
// most `maxSize` across) -- covers from video thumbnails are 16:9.
QByteArray SquareJpeg(const QByteArray& image, int maxSize = 1000);

// "AC/DC: Back in Black?" -> "AC-DC - Back in Black" -- safe as a Windows file name.
QString SafeFileName(const QString& name);

} // namespace audioforge
