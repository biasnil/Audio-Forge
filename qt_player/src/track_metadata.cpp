#include "audioforge/track_metadata.hpp"

#include <QFileInfo>
#include <QDir>
#include <QFile>

#include <taglib/fileref.h>
#include <taglib/tag.h>
#include <taglib/audioproperties.h>
#include <taglib/mpegfile.h>
#include <taglib/id3v2tag.h>
#include <taglib/id3v2frame.h>
#include <taglib/textidentificationframe.h>
#include <taglib/attachedpictureframe.h>

namespace audioforge {

// Some downloaded MP3s have no embedded cover art at all -- players like
// VLC and Explorer fall back to a same-folder image file in that case
// (cover.jpg, folder.jpg, etc.), so we do the same thing here.
static QByteArray ReadFolderCoverArt(const QString& filePath)
{
    static const QStringList candidateNames = {
        "cover.jpg", "cover.jpeg", "cover.png",
        "folder.jpg", "folder.jpeg", "folder.png",
        "album.jpg", "album.jpeg", "album.png",
        "front.jpg", "front.jpeg", "front.png"
    };

    QDir dir = QFileInfo(filePath).dir();
    QStringList existingFiles = dir.entryList(QDir::Files);

    for (const QString& candidate : candidateNames)
    {
        for (const QString& existingName : existingFiles)
        {
            if (existingName.compare(candidate, Qt::CaseInsensitive) == 0)
            {
                QFile imageFile(dir.filePath(existingName));
                if (imageFile.open(QIODevice::ReadOnly))
                {
                    return imageFile.readAll();
                }
            }
        }
    }

    return QByteArray();
}

// Opens the file's ID3v2 tag once and pulls out both the ReplayGain TXXX
// frame and any embedded cover art (APIC frame) -- combined into one pass
// since both need the same TagLib::MPEG::File to be opened. Either output
// may be null to skip it.
static void ReadMpegExtras(const QString& path, float* replayGainDbOut, QByteArray* coverArtOut)
{
    if (replayGainDbOut)
    {
        *replayGainDbOut = 0.0f;
    }
    if (coverArtOut)
    {
        coverArtOut->clear();
    }

#ifdef _WIN32
    std::wstring pathW = path.toStdWString();
    TagLib::MPEG::File file(pathW.c_str());
#else
    TagLib::MPEG::File file(path.toUtf8().constData());
#endif

    if (!file.isValid() || !file.hasID3v2Tag())
    {
        return;
    }

    TagLib::ID3v2::Tag* id3v2 = file.ID3v2Tag();
    const auto& frameMap = id3v2->frameListMap();

    auto txxxIt = frameMap.find("TXXX");
    if (replayGainDbOut && txxxIt != frameMap.end())
    {
        for (TagLib::ID3v2::Frame* frame : txxxIt->second)
        {
            auto* userFrame = dynamic_cast<TagLib::ID3v2::UserTextIdentificationFrame*>(frame);
            if (!userFrame)
            {
                continue;
            }
            QString description = QString::fromStdWString(userFrame->description().toWString()).toUpper();
            if (description == "REPLAYGAIN_TRACK_GAIN")
            {
                TagLib::StringList fields = userFrame->fieldList();
                if (!fields.isEmpty())
                {
                    QString valueStr = QString::fromStdWString(fields.back().toWString());
                    valueStr.remove("dB", Qt::CaseInsensitive);
                    valueStr = valueStr.trimmed();
                    bool ok = false;
                    float db = valueStr.toFloat(&ok);
                    if (ok)
                    {
                        *replayGainDbOut = db;
                    }
                }
            }
        }
    }

    auto apicIt = frameMap.find("APIC");
    if (coverArtOut && apicIt != frameMap.end() && !apicIt->second.isEmpty())
    {
        auto* picFrame = dynamic_cast<TagLib::ID3v2::AttachedPictureFrame*>(apicIt->second.front());
        if (picFrame)
        {
            TagLib::ByteVector data = picFrame->picture();
            *coverArtOut = QByteArray(data.data(), static_cast<int>(data.size()));
        }
    }
}

TrackInfo ReadTrackInfo(const QString& path, bool includeCoverArt)
{
    TrackInfo info;
    info.path = path;
    info.title = QFileInfo(path).fileName();

    // Scoped in its own block so this handle is CLOSED before ReadMpegExtras
    // opens a second one below -- two simultaneous TagLib opens on the same
    // file can cause the second one to silently fail on Windows, which is
    // exactly what was breaking cover art (title/artist still worked
    // because they come from this first, successful open).
    {
        // TagLib's wchar_t* constructor is the Windows-Unicode-safe path --
        // same reasoning as AudioEngine's use of ma_sound_init_from_file_w:
        // the plain char* overload would assume the legacy codepage and
        // break on non-ASCII (e.g. Chinese) names.
#ifdef _WIN32
        std::wstring pathW = path.toStdWString();
        TagLib::FileRef file(pathW.c_str());
#else
        TagLib::FileRef file(path.toUtf8().constData());
#endif

        if (!file.isNull() && file.tag())
        {
            TagLib::Tag* tag = file.tag();
            QString title = QString::fromStdWString(tag->title().toWString());
            if (!title.isEmpty())
            {
                info.title = title;
            }
            info.artist = QString::fromStdWString(tag->artist().toWString());
            info.album = QString::fromStdWString(tag->album().toWString());
            info.genre = QString::fromStdWString(tag->genre().toWString());
            info.year = tag->year();
        }

        if (!file.isNull() && file.audioProperties())
        {
            TagLib::AudioProperties* props = file.audioProperties();
            info.bitrateKbps = props->bitrate();
            info.sampleRateHz = props->sampleRate();
            info.channels = props->channels();
        }
    } // <-- `file` destructs here, releasing its handle on the file

    ReadMpegExtras(path, &info.replayGainDb, includeCoverArt ? &info.coverArt : nullptr);
    if (includeCoverArt && info.coverArt.isEmpty())
    {
        info.coverArt = ReadFolderCoverArt(path);
    }

    return info;
}

QByteArray ReadCoverArt(const QString& path)
{
    QByteArray coverArt;
    ReadMpegExtras(path, nullptr, &coverArt);
    if (coverArt.isEmpty())
    {
        coverArt = ReadFolderCoverArt(path);
    }
    return coverArt;
}

bool WriteBasicTags(const QString& path, const QString& title, const QString& artist,
                     const QString& album, unsigned int year)
{
#ifdef _WIN32
    std::wstring pathW = path.toStdWString();
    TagLib::FileRef file(pathW.c_str());
#else
    TagLib::FileRef file(path.toUtf8().constData());
#endif

    if (file.isNull() || !file.tag())
    {
        return false;
    }

    TagLib::Tag* tag = file.tag();
    if (!title.isEmpty())
    {
        tag->setTitle(TagLib::String(title.toStdWString()));
    }
    if (!artist.isEmpty())
    {
        tag->setArtist(TagLib::String(artist.toStdWString()));
    }
    if (!album.isEmpty())
    {
        tag->setAlbum(TagLib::String(album.toStdWString()));
    }
    if (year > 0)
    {
        tag->setYear(year);
    }

    return file.save();
}

} // namespace audioforge
