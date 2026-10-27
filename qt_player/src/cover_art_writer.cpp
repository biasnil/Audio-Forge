#include "audioforge/cover_art_writer.hpp"

#include <QFileInfo>

#include <taglib/attachedpictureframe.h>
#include <taglib/flacfile.h>
#include <taglib/flacpicture.h>
#include <taglib/id3v2tag.h>
#include <taglib/mp4coverart.h>
#include <taglib/mp4file.h>
#include <taglib/mp4tag.h>
#include <taglib/mpegfile.h>

namespace audioforge {

namespace {

#ifdef _WIN32
#define AF_TAGLIB_PATH(path) (path).toStdWString().c_str()
#else
#define AF_TAGLIB_PATH(path) (path).toUtf8().constData()
#endif

TagLib::ByteVector Bytes(const QByteArray& data)
{
    return TagLib::ByteVector(data.constData(), static_cast<unsigned int>(data.size()));
}

// M4A / M4B: the "covr" atom.
bool WriteMp4Cover(const QString& path, const QByteArray& imageData, const QString& mimeType)
{
    TagLib::MP4::File file(AF_TAGLIB_PATH(path));
    if (!file.isValid() || !file.tag())
    {
        return false;
    }
    TagLib::MP4::CoverArtList covers;
    covers.append(TagLib::MP4::CoverArt(mimeType.contains(QLatin1String("png")) ? TagLib::MP4::CoverArt::PNG : TagLib::MP4::CoverArt::JPEG,
                                        Bytes(imageData)));
    file.tag()->setItem("covr", TagLib::MP4::Item(covers));
    return file.save();
}

// FLAC: a front-cover picture block.
bool WriteFlacCover(const QString& path, const QByteArray& imageData, const QString& mimeType)
{
    TagLib::FLAC::File file(AF_TAGLIB_PATH(path));
    if (!file.isValid())
    {
        return false;
    }
    file.removePictures();
    auto* picture = new TagLib::FLAC::Picture();
    picture->setType(TagLib::FLAC::Picture::FrontCover);
    picture->setMimeType(TagLib::String(mimeType.toStdString()));
    picture->setData(Bytes(imageData));
    file.addPicture(picture); // the file takes ownership
    return file.save();
}

} // namespace

bool WriteCoverArt(const QString& path, const QByteArray& imageData, const QString& mimeType)
{
    const QString suffix = QFileInfo(path).suffix().toLower();
    if (suffix == QLatin1String("m4a") || suffix == QLatin1String("m4b") || suffix == QLatin1String("mp4") || suffix == QLatin1String("aac"))
    {
        return WriteMp4Cover(path, imageData, mimeType);
    }
    if (suffix == QLatin1String("flac"))
    {
        return WriteFlacCover(path, imageData, mimeType);
    }

    TagLib::MPEG::File file(AF_TAGLIB_PATH(path));
    if (!file.isValid())
    {
        return false;
    }

    TagLib::ID3v2::Tag* id3v2 = file.ID3v2Tag(true); // true = create the tag if the file doesn't have one yet
    if (!id3v2)
    {
        return false;
    }

    id3v2->removeFrames("APIC"); // full replace, not an add -- see header comment

    auto* picFrame = new TagLib::ID3v2::AttachedPictureFrame();
    picFrame->setMimeType(TagLib::String(mimeType.toStdString()));
    picFrame->setType(TagLib::ID3v2::AttachedPictureFrame::FrontCover);
    picFrame->setPicture(Bytes(imageData));
    id3v2->addFrame(picFrame); // the tag takes ownership of picFrame

    return file.save();
}

} // namespace audioforge
