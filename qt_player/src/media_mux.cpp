#include "audioforge/media_mux.hpp"

#include <QBuffer>
#include <QFile>
#include <QImage>
#include <QObject>
#include <QRegularExpression>

#include <taglib/mp4coverart.h>
#include <taglib/mp4file.h>
#include <taglib/mp4tag.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/mem.h>
}

#include <algorithm>
#include <memory>

namespace audioforge {

namespace {

struct InputPart
{
    AVFormatContext* format = nullptr;
    int stream = -1;
    double seconds = 0.0;
};

void CloseParts(QVector<InputPart>& parts)
{
    for (InputPart& part : parts)
    {
        avformat_close_input(&part.format);
    }
}

bool WriteTags(const JoinRequest& r)
{
#ifdef _WIN32
    TagLib::MP4::File file(r.output.toStdWString().c_str());
#else
    TagLib::MP4::File file(r.output.toUtf8().constData());
#endif
    if (!file.isValid() || !file.tag())
    {
        return false;
    }
    TagLib::MP4::Tag* tag = file.tag();
    auto text = [](const QString& value) { return TagLib::String(value.toStdWString()); };
    if (!r.title.isEmpty()) tag->setTitle(text(r.title));
    if (!r.artist.isEmpty()) tag->setArtist(text(r.artist));
    if (!r.album.isEmpty()) tag->setAlbum(text(r.album));
    if (!r.genre.isEmpty()) tag->setGenre(text(r.genre));
    if (!r.comment.isEmpty()) tag->setComment(text(r.comment));
    if (r.year > 0) tag->setYear(static_cast<unsigned int>(r.year));
    if (!r.artist.isEmpty())
    {
        tag->setItem("aART", TagLib::MP4::Item(TagLib::StringList(text(r.artist))));
    }
    if (!r.coverJpeg.isEmpty())
    {
        TagLib::MP4::CoverArtList covers;
        covers.append(TagLib::MP4::CoverArt(TagLib::MP4::CoverArt::JPEG,
                                            TagLib::ByteVector(r.coverJpeg.constData(), static_cast<unsigned int>(r.coverJpeg.size()))));
        tag->setItem("covr", TagLib::MP4::Item(covers));
    }
    return file.save();
}

} // namespace

QString JoinAudioToMp4(const JoinRequest& r, const std::function<bool(double)>& progress)
{
    if (r.inputs.isEmpty())
    {
        return QObject::tr("Nothing was downloaded.");
    }

    // Open every part first: their lengths place the chapters, which the
    // MP4 muxer needs before it writes anything.
    QVector<InputPart> parts;
    for (const QString& path : r.inputs)
    {
        InputPart part;
        if (avformat_open_input(&part.format, path.toUtf8().constData(), nullptr, nullptr) < 0
            || avformat_find_stream_info(part.format, nullptr) < 0)
        {
            CloseParts(parts);
            avformat_close_input(&part.format);
            return QObject::tr("A downloaded part can't be read.");
        }
        part.stream = av_find_best_stream(part.format, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
        if (part.stream < 0)
        {
            parts << part;
            CloseParts(parts);
            return QObject::tr("A downloaded part has no audio.");
        }
        const AVStream* stream = part.format->streams[part.stream];
        if (stream->duration > 0 && stream->duration != AV_NOPTS_VALUE)
        {
            part.seconds = stream->duration * av_q2d(stream->time_base);
        }
        else if (part.format->duration > 0)
        {
            part.seconds = part.format->duration / static_cast<double>(AV_TIME_BASE);
        }
        if (!parts.isEmpty() && stream->codecpar->codec_id != parts.first().format->streams[parts.first().stream]->codecpar->codec_id)
        {
            parts << part;
            CloseParts(parts);
            return QObject::tr("The parts aren't all in the same format, so they can't be joined.");
        }
        parts << part;
    }
    const AVCodecParameters* params = parts.first().format->streams[parts.first().stream]->codecpar;

    const QString temp = r.output + QStringLiteral(".part");
    AVFormatContext* out = nullptr;
    if (avformat_alloc_output_context2(&out, nullptr, "mp4", temp.toUtf8().constData()) < 0 || !out)
    {
        CloseParts(parts);
        return QObject::tr("The file couldn't be created.");
    }
    auto fail = [&](const QString& why) {
        if (out->pb)
        {
            avio_closep(&out->pb);
        }
        avformat_free_context(out);
        CloseParts(parts);
        QFile::remove(temp);
        return why;
    };
    if (avformat_query_codec(out->oformat, params->codec_id, FF_COMPLIANCE_NORMAL) != 1)
    {
        return fail(QObject::tr("This audio format can't be saved as M4A."));
    }
    AVStream* outStream = avformat_new_stream(out, nullptr);
    avcodec_parameters_copy(outStream->codecpar, params);
    outStream->codecpar->codec_tag = 0;
    outStream->time_base = AVRational{1, params->sample_rate > 0 ? params->sample_rate : 44100};

    // Chapters: one per part, or the ones given.
    QVector<ChapterMark> marks;
    double total = 0.0;
    for (const InputPart& part : parts)
    {
        total += part.seconds;
    }
    if (r.partTitles.size() == parts.size() && parts.size() > 1)
    {
        double start = 0.0;
        for (int i = 0; i < parts.size(); ++i)
        {
            marks << ChapterMark{start, r.partTitles[i]};
            start += parts[i].seconds;
        }
    }
    else
    {
        marks = r.chapters;
    }
    for (int i = 0; i < marks.size(); ++i)
    {
        auto* chapter = static_cast<AVChapter*>(av_mallocz(sizeof(AVChapter)));
        chapter->id = i;
        chapter->time_base = AVRational{1, 1000};
        chapter->start = static_cast<int64_t>(marks[i].startSeconds * 1000.0);
        chapter->end = static_cast<int64_t>((i + 1 < marks.size() ? marks[i + 1].startSeconds : qMax(total, marks[i].startSeconds + 1.0)) * 1000.0);
        av_dict_set(&chapter->metadata, "title", marks[i].title.toUtf8().constData(), 0);
        av_dynarray_add(&out->chapters, reinterpret_cast<int*>(&out->nb_chapters), chapter);
    }

    if (avio_open(&out->pb, temp.toUtf8().constData(), AVIO_FLAG_WRITE) < 0)
    {
        return fail(QObject::tr("The file couldn't be created."));
    }
    AVDictionary* options = nullptr;
    av_dict_set(&options, "movflags", "+faststart", 0); // the index up front: players can seek at once
    const int header = avformat_write_header(out, &options);
    av_dict_free(&options);
    if (header < 0)
    {
        return fail(QObject::tr("The file couldn't be written."));
    }

    // The packets, part after part, each part's times moved on past the last one.
    const AVRational outBase = outStream->time_base; // (the muxer may have changed it)
    const int64_t frameDuration = params->frame_size > 0 ? av_rescale_q(params->frame_size, AVRational{1, params->sample_rate}, outBase) : 0;
    AVPacket* packet = av_packet_alloc();
    int64_t offset = 0;
    int64_t lastDts = AV_NOPTS_VALUE;
    for (int i = 0; i < parts.size(); ++i)
    {
        InputPart& part = parts[i];
        const AVRational inBase = part.format->streams[part.stream]->time_base;
        int64_t first = AV_NOPTS_VALUE;
        int64_t end = offset;
        int count = 0;
        while (av_read_frame(part.format, packet) >= 0)
        {
            if (packet->stream_index != part.stream)
            {
                av_packet_unref(packet);
                continue;
            }
            const int64_t dts = packet->dts != AV_NOPTS_VALUE ? packet->dts : packet->pts;
            if (first == AV_NOPTS_VALUE)
            {
                first = dts != AV_NOPTS_VALUE ? dts : 0;
            }
            int64_t outDts = dts != AV_NOPTS_VALUE ? av_rescale_q(dts - first, inBase, outBase) + offset : end;
            const int64_t outPts = packet->pts != AV_NOPTS_VALUE ? av_rescale_q(packet->pts - first, inBase, outBase) + offset : outDts;
            const int64_t duration = packet->duration > 0 ? av_rescale_q(packet->duration, inBase, outBase) : frameDuration;
            if (lastDts != AV_NOPTS_VALUE && outDts <= lastDts)
            {
                outDts = lastDts + 1; // the muxer needs them strictly increasing
            }
            packet->dts = outDts;
            packet->pts = qMax(outPts, outDts);
            packet->duration = duration;
            packet->stream_index = 0;
            packet->pos = -1;
            lastDts = outDts;
            end = qMax(end, outDts + duration);
            if (av_interleaved_write_frame(out, packet) < 0)
            {
                av_packet_free(&packet);
                return fail(QObject::tr("The file couldn't be written (is the disk full?)."));
            }
            if (progress && ++count % 200 == 0)
            {
                const double within = part.seconds > 0 ? qBound(0.0, (end - offset) * av_q2d(outBase) / part.seconds, 1.0) : 0.0;
                if (!progress((i + within) / parts.size()))
                {
                    av_packet_free(&packet);
                    return fail(QObject::tr("Stopped."));
                }
            }
        }
        offset = end;
    }
    av_packet_free(&packet);
    if (av_write_trailer(out) < 0)
    {
        return fail(QObject::tr("The file couldn't be finished."));
    }
    avio_closep(&out->pb);
    avformat_free_context(out);
    CloseParts(parts);

    QFile::remove(r.output);
    if (!QFile::rename(temp, r.output))
    {
        QFile::remove(temp);
        return QObject::tr("The file couldn't be saved.");
    }
    WriteTags(r); // a file without tags still plays: not a failure
    if (progress)
    {
        progress(1.0);
    }
    return QString();
}

QVector<ChapterMark> ReadChapters(const QString& path)
{
    QVector<ChapterMark> chapters;
    AVFormatContext* format = nullptr;
    if (avformat_open_input(&format, path.toUtf8().constData(), nullptr, nullptr) < 0)
    {
        return chapters;
    }
    for (unsigned int i = 0; i < format->nb_chapters; ++i)
    {
        const AVChapter* chapter = format->chapters[i];
        const AVDictionaryEntry* title = av_dict_get(chapter->metadata, "title", nullptr, 0);
        chapters << ChapterMark{chapter->start * av_q2d(chapter->time_base),
                                title ? QString::fromUtf8(title->value) : QObject::tr("Chapter %1").arg(i + 1)};
    }
    avformat_close_input(&format);
    return chapters;
}

QByteArray SquareJpeg(const QByteArray& image, int maxSize)
{
    QImage source;
    if (image.isEmpty() || !source.loadFromData(image))
    {
        return QByteArray();
    }
    const int side = qMin(source.width(), source.height());
    QImage square = source.copy((source.width() - side) / 2, (source.height() - side) / 2, side, side);
    if (side > maxSize)
    {
        square = square.scaled(maxSize, maxSize, Qt::IgnoreAspectRatio, Qt::SmoothTransformation);
    }
    QByteArray jpeg;
    QBuffer buffer(&jpeg);
    buffer.open(QIODevice::WriteOnly);
    square.convertToFormat(QImage::Format_RGB32).save(&buffer, "JPG", 90);
    return jpeg;
}

QString SafeFileName(const QString& name)
{
    QString safe = name;
    safe.replace(QRegularExpression(QStringLiteral("[\\\\/]")), QStringLiteral("-"));
    safe.replace(QRegularExpression(QStringLiteral("[:*?\"<>|\\x00-\\x1f]")), QString());
    safe = safe.simplified();
    while (safe.endsWith('.'))
    {
        safe.chop(1); // Windows drops trailing dots
    }
    if (safe.size() > 120)
    {
        safe = safe.left(120).trimmed();
    }
    return safe.isEmpty() ? QStringLiteral("Untitled") : safe;
}

} // namespace audioforge
