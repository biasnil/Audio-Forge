#include "audioforge/cover_art_writer.hpp"

#include <taglib/mpegfile.h>
#include <taglib/id3v2tag.h>
#include <taglib/attachedpictureframe.h>

namespace audioforge {

bool WriteCoverArt(const QString& path, const QByteArray& imageData, const QString& mimeType)
{
#ifdef _WIN32
    std::wstring pathW = path.toStdWString();
    TagLib::MPEG::File file(pathW.c_str());
#else
    TagLib::MPEG::File file(path.toUtf8().constData());
#endif

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
    picFrame->setPicture(TagLib::ByteVector(imageData.constData(), static_cast<unsigned int>(imageData.size())));
    id3v2->addFrame(picFrame); // the tag takes ownership of picFrame

    return file.save();
}

} // namespace audioforge
