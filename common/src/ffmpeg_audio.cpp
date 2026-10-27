#include "audioforge/ffmpeg_audio.hpp"
#include "audioforge/app_info.hpp"
#include "audioforge/warnings.hpp"

#include <QFileInfo>
#include <QRegularExpression>
#include <QStringList>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

namespace audioforge {

namespace {

struct FfmpegSource
{
    ma_data_source_base base; // first: miniaudio treats the struct as a ma_data_source
    AVFormatContext* format = nullptr;
    AVCodecContext* codec = nullptr;
    SwrContext* resampler = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    int stream = -1;
    int channels = 2;
    int sampleRate = 44100;
    ma_uint64 length = 0;      // in frames; 0 = unknown
    ma_uint64 cursor = 0;      // the next frame read() returns
    std::vector<float> pending; // decoded, not yet read (interleaved)
    size_t pendingFrame = 0;    // the first unread frame in `pending`
    ma_uint64 seekTarget = 0;   // after a seek: drop decoded frames before this one
    bool aligning = false;
    bool flushed = false;       // the decoder was told the file ended
    bool finished = false;

    // Live streams (internet radio): a thread decodes ahead into `buffer`;
    // read() only ever takes from it, so a slow network can't stall the audio thread.
    bool live = false;
    std::atomic<bool> stop{false};  // also interrupts FFmpeg's network waits
    std::thread decoder;
    std::mutex mutex;               // guards everything below
    std::condition_variable spaceFree;
    std::deque<float> buffer;       // interleaved
    bool primed = false;            // enough buffered to (re)start
    bool streamEnded = false;
    QString title;                  // ICY StreamTitle
    QByteArray lastIcyPacket;       // (decoder thread only)
};

// Seconds of audio held ahead, and what's needed before playback starts or
// resumes after the buffer ran dry.
constexpr double kLiveBufferSeconds = 20.0;
constexpr double kLivePrebufferSeconds = 1.5;

int Interrupted(void* opaque)
{
    return static_cast<FfmpegSource*>(opaque)->stop.load() ? 1 : 0;
}

void Release(FfmpegSource* s)
{
    swr_free(&s->resampler);
    av_frame_free(&s->frame);
    av_packet_free(&s->packet);
    avcodec_free_context(&s->codec);
    avformat_close_input(&s->format);
}

// Decodes the next frame into `pending`. False once the file has ended (or on an error).
bool DecodeMore(FfmpegSource* s)
{
    while (true)
    {
        const int received = avcodec_receive_frame(s->codec, s->frame);
        if (received == 0)
        {
            const int count = s->frame->nb_samples;
            std::vector<float> converted(static_cast<size_t>(count + 32) * s->channels);
            uint8_t* out[] = {reinterpret_cast<uint8_t*>(converted.data())};
            const int made = swr_convert(s->resampler, out, count + 32,
                                         const_cast<const uint8_t**>(s->frame->extended_data), count);
            int start = 0;
            if (s->aligning)
            {
                // After a seek: decoded frames before the target (the preroll) are dropped,
                // then the frame holding it is cut to start exactly there.
                const int64_t pts = s->frame->best_effort_timestamp;
                if (pts != AV_NOPTS_VALUE)
                {
                    const AVRational timeBase = s->format->streams[s->stream]->time_base;
                    const int64_t framePos = av_rescale_q(pts, timeBase, AVRational{1, s->sampleRate});
                    const int64_t skip = static_cast<int64_t>(s->seekTarget) - framePos;
                    if (skip >= made)
                    {
                        av_frame_unref(s->frame);
                        continue; // entirely before the target
                    }
                    start = static_cast<int>(std::max<int64_t>(skip, 0));
                }
                s->aligning = false;
            }
            av_frame_unref(s->frame);
            if (made > start)
            {
                s->pending.assign(converted.begin() + static_cast<size_t>(start) * s->channels,
                                  converted.begin() + static_cast<size_t>(made) * s->channels);
                s->pendingFrame = 0;
                return true;
            }
            continue;
        }
        if (received == AVERROR_EOF)
        {
            return false;
        }
        if (received != AVERROR(EAGAIN))
        {
            return false; // a decoding error: treat as the end
        }
        // The decoder wants more input.
        if (s->flushed)
        {
            return false;
        }
        const int read = av_read_frame(s->format, s->packet);
        if (read < 0)
        {
            avcodec_send_packet(s->codec, nullptr); // drain what's left
            s->flushed = true;
            continue;
        }
        if (s->packet->stream_index == s->stream)
        {
            avcodec_send_packet(s->codec, s->packet);
        }
        av_packet_unref(s->packet);
    }
}

// Live: whatever's buffered, silence while (re)buffering -- the sound keeps
// running, so a network hiccup is a gap, not the end of the station.
ma_result ReadLive(FfmpegSource* s, float* out, ma_uint64 frameCount, ma_uint64* framesRead)
{
    std::lock_guard<std::mutex> lock(s->mutex);
    const size_t available = s->buffer.size() / s->channels;
    if (!s->primed && (available >= static_cast<size_t>(kLivePrebufferSeconds * s->sampleRate) || s->streamEnded))
    {
        s->primed = true;
    }
    size_t take = 0;
    if (s->primed)
    {
        take = static_cast<size_t>(std::min<ma_uint64>(available, frameCount));
        const auto samples = static_cast<std::ptrdiff_t>(take * s->channels);
        std::copy(s->buffer.begin(), s->buffer.begin() + samples, out);
        s->buffer.erase(s->buffer.begin(), s->buffer.begin() + samples);
        s->spaceFree.notify_one();
    }
    if (take == 0 && s->streamEnded)
    {
        if (framesRead)
        {
            *framesRead = 0;
        }
        return MA_AT_END; // the station hung up for good
    }
    if (take < frameCount)
    {
        std::memset(out + take * s->channels, 0, static_cast<size_t>(frameCount - take) * s->channels * sizeof(float));
        if (!s->streamEnded)
        {
            s->primed = false; // ran dry: wait for a little to build up again
        }
    }
    s->cursor += take;
    if (framesRead)
    {
        *framesRead = frameCount;
    }
    return MA_SUCCESS;
}

// "StreamTitle='Artist - Song';StreamUrl='...';" -> "Artist - Song".
QString ParseIcyTitle(const QByteArray& packet)
{
    QString text = QString::fromUtf8(packet);
    if (text.contains(QChar::ReplacementCharacter))
    {
        text = QString::fromLatin1(packet); // older Shoutcast servers send Latin-1
    }
    static const QRegularExpression kTitle(QStringLiteral("StreamTitle='(.*?)';"));
    const QRegularExpressionMatch match = kTitle.match(text);
    return match.hasMatch() ? match.captured(1).trimmed() : QString();
}

void DecodeLive(FfmpegSource* s)
{
    const size_t capacity = static_cast<size_t>(kLiveBufferSeconds * s->sampleRate) * s->channels;
    while (!s->stop.load())
    {
        const bool more = DecodeMore(s);

        // The song the station says it's playing (sent between audio blocks).
        uint8_t* icy = nullptr;
        if (av_opt_get(s->format, "icy_metadata_packet", AV_OPT_SEARCH_CHILDREN, &icy) >= 0 && icy)
        {
            const QByteArray packet(reinterpret_cast<const char*>(icy));
            av_free(icy);
            if (packet != s->lastIcyPacket)
            {
                s->lastIcyPacket = packet;
                const QString title = ParseIcyTitle(packet);
                std::lock_guard<std::mutex> lock(s->mutex);
                s->title = title;
            }
        }

        std::unique_lock<std::mutex> lock(s->mutex);
        if (!more)
        {
            s->streamEnded = true;
            return;
        }
        s->spaceFree.wait(lock, [&]() { return s->stop.load() || s->buffer.size() < capacity; });
        s->buffer.insert(s->buffer.end(), s->pending.begin() + static_cast<std::ptrdiff_t>(s->pendingFrame * s->channels),
                         s->pending.end());
        s->pending.clear();
        s->pendingFrame = 0;
    }
}

ma_result OnRead(ma_data_source* source, void* framesOut, ma_uint64 frameCount, ma_uint64* framesRead)
{
    auto* s = reinterpret_cast<FfmpegSource*>(source);
    auto* out = static_cast<float*>(framesOut);
    if (s->live)
    {
        return ReadLive(s, out, frameCount, framesRead);
    }
    if (s->length > 0)
    {
        // The encoder's padding after the real end (a few hundred samples) isn't played.
        frameCount = std::min(frameCount, s->length > s->cursor ? s->length - s->cursor : 0);
    }
    ma_uint64 done = 0;
    while (done < frameCount)
    {
        const size_t available = s->pending.size() / s->channels - s->pendingFrame;
        if (available == 0)
        {
            if (s->finished || !DecodeMore(s))
            {
                s->finished = true;
                break;
            }
            continue;
        }
        const size_t take = static_cast<size_t>(std::min<ma_uint64>(available, frameCount - done));
        std::memcpy(out + done * s->channels, s->pending.data() + s->pendingFrame * s->channels,
                    take * s->channels * sizeof(float));
        s->pendingFrame += take;
        done += take;
    }
    s->cursor += done;
    if (framesRead)
    {
        *framesRead = done;
    }
    return done == 0 ? MA_AT_END : MA_SUCCESS;
}

ma_result OnSeek(ma_data_source* source, ma_uint64 frameIndex)
{
    auto* s = reinterpret_cast<FfmpegSource*>(source);
    if (s->live)
    {
        return MA_ERROR; // a live stream has no positions to go to
    }
    AVStream* stream = s->format->streams[s->stream];
    // AAC's first frame after a seek comes out garbled (each frame overlaps the
    // one before it), so decoding starts two frames early and those are
    // dropped. Before 0 is fine: it reaches the encoder's priming frame, which
    // FFmpeg then discards the same way it does when a file is opened.
    constexpr int64_t kPrerollFrames = 2048;
    const int64_t timestamp = av_rescale_q(static_cast<int64_t>(frameIndex) - kPrerollFrames,
                                           AVRational{1, s->sampleRate}, stream->time_base);
    if (av_seek_frame(s->format, s->stream, timestamp, AVSEEK_FLAG_BACKWARD) < 0)
    {
        return MA_ERROR;
    }
    avcodec_flush_buffers(s->codec);
    s->pending.clear();
    s->pendingFrame = 0;
    s->seekTarget = frameIndex;
    s->aligning = true;
    s->flushed = false;
    s->finished = false;
    s->cursor = frameIndex;
    return MA_SUCCESS;
}

ma_result OnGetDataFormat(ma_data_source* source, ma_format* format, ma_uint32* channels, ma_uint32* sampleRate,
                          ma_channel* channelMap, size_t channelMapCap)
{
    auto* s = reinterpret_cast<FfmpegSource*>(source);
    if (format) *format = ma_format_f32;
    if (channels) *channels = static_cast<ma_uint32>(s->channels);
    if (sampleRate) *sampleRate = static_cast<ma_uint32>(s->sampleRate);
    if (channelMap)
    {
        ma_channel_map_init_standard(ma_standard_channel_map_default, channelMap, channelMapCap, s->channels);
    }
    return MA_SUCCESS;
}

ma_result OnGetCursor(ma_data_source* source, ma_uint64* cursor)
{
    *cursor = reinterpret_cast<FfmpegSource*>(source)->cursor;
    return MA_SUCCESS;
}

ma_result OnGetLength(ma_data_source* source, ma_uint64* length)
{
    auto* s = reinterpret_cast<FfmpegSource*>(source);
    *length = s->length;
    return s->length > 0 ? MA_SUCCESS : MA_NOT_IMPLEMENTED;
}

ma_data_source_vtable g_vtable = {OnRead, OnSeek, OnGetDataFormat, OnGetCursor, OnGetLength, nullptr, 0};

} // namespace

bool IsFfmpegAudioFile(const QString& path)
{
    static const QStringList extensions = {"m4a", "m4b", "aac", "mp4"};
    return extensions.contains(QFileInfo(path).suffix().toLower());
}

bool IsStreamUrl(const QString& path)
{
    return path.startsWith(QLatin1String("http://"), Qt::CaseInsensitive)
        || path.startsWith(QLatin1String("https://"), Qt::CaseInsensitive);
}

ma_data_source* OpenFfmpegAudio(const QString& path)
{
    auto* s = new FfmpegSource();
    auto fail = [&](const char* why) -> ma_data_source* {
        ErrorReporter::logOnly(QString("Couldn't open %1 with FFmpeg: %2").arg(path, QString::fromLatin1(why)));
        Release(s);
        delete s;
        return nullptr;
    };

    s->live = IsStreamUrl(path);
    AVDictionary* options = nullptr;
    if (s->live)
    {
        static std::once_flag networkReady;
        std::call_once(networkReady, []() { avformat_network_init(); });
        s->format = avformat_alloc_context();
        s->format->interrupt_callback = {&Interrupted, s}; // closing doesn't wait out a stalled connection
        av_dict_set(&options, "icy", "1", 0);                 // ask for the song titles
        av_dict_set(&options, "reconnect", "1", 0);
        av_dict_set(&options, "reconnect_streamed", "1", 0);
        av_dict_set(&options, "reconnect_on_network_error", "1", 0);
        av_dict_set(&options, "reconnect_delay_max", "4", 0);
        av_dict_set(&options, "rw_timeout", "15000000", 0);   // 15 s without data: give up
        av_dict_set(&options, "user_agent", app::UserAgent().constData(), 0);
    }
    // FFmpeg takes UTF-8 paths on Windows too.
    const int opened = avformat_open_input(&s->format, path.toUtf8().constData(), nullptr, &options);
    av_dict_free(&options);
    if (opened < 0)
    {
        return fail("not readable");
    }
    if (avformat_find_stream_info(s->format, nullptr) < 0)
    {
        return fail("no stream info");
    }
    const AVCodec* decoder = nullptr;
    s->stream = av_find_best_stream(s->format, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
    if (s->stream < 0 || !decoder)
    {
        return fail("no audio stream");
    }
    AVStream* stream = s->format->streams[s->stream];
    s->codec = avcodec_alloc_context3(decoder);
    if (!s->codec || avcodec_parameters_to_context(s->codec, stream->codecpar) < 0
        || avcodec_open2(s->codec, decoder, nullptr) < 0)
    {
        return fail("the codec couldn't be opened");
    }
    s->sampleRate = s->codec->sample_rate > 0 ? s->codec->sample_rate : 44100;
    s->channels = std::clamp(s->codec->ch_layout.nb_channels, 1, 2);

    AVChannelLayout outLayout;
    av_channel_layout_default(&outLayout, s->channels);
    if (swr_alloc_set_opts2(&s->resampler, &outLayout, AV_SAMPLE_FMT_FLT, s->sampleRate, &s->codec->ch_layout,
                            s->codec->sample_fmt, s->sampleRate, 0, nullptr) < 0
        || swr_init(s->resampler) < 0)
    {
        av_channel_layout_uninit(&outLayout);
        return fail("no sample converter");
    }
    av_channel_layout_uninit(&outLayout);

    if (s->live)
    {
        // No length: a station plays until it's stopped.
    }
    else if (stream->duration != AV_NOPTS_VALUE && stream->duration > 0)
    {
        s->length = static_cast<ma_uint64>(av_rescale_q(stream->duration, stream->time_base, AVRational{1, s->sampleRate}));
    }
    else if (s->format->duration > 0)
    {
        s->length = static_cast<ma_uint64>(av_rescale(s->format->duration, s->sampleRate, AV_TIME_BASE));
    }
    s->packet = av_packet_alloc();
    s->frame = av_frame_alloc();

    ma_data_source_config config = ma_data_source_config_init();
    config.vtable = &g_vtable;
    if (ma_data_source_init(&config, &s->base) != MA_SUCCESS)
    {
        return fail("no data source");
    }
    if (s->live)
    {
        s->decoder = std::thread(&DecodeLive, s);
    }
    return reinterpret_cast<ma_data_source*>(s);
}

void CloseFfmpegAudio(ma_data_source* source)
{
    if (!source)
    {
        return;
    }
    auto* s = reinterpret_cast<FfmpegSource*>(source);
    if (s->decoder.joinable())
    {
        s->stop.store(true);
        s->spaceFree.notify_all();
        s->decoder.join();
    }
    ma_data_source_uninit(&s->base);
    Release(s);
    delete s;
}

bool IsFfmpegLive(const ma_data_source* source)
{
    return source && reinterpret_cast<const FfmpegSource*>(source)->live;
}

QString FfmpegStreamTitle(const ma_data_source* source)
{
    if (!IsFfmpegLive(source))
    {
        return QString();
    }
    auto* s = const_cast<FfmpegSource*>(reinterpret_cast<const FfmpegSource*>(source));
    std::lock_guard<std::mutex> lock(s->mutex);
    return s->title;
}

} // namespace audioforge
