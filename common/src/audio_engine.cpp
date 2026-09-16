#define MINIAUDIO_IMPLEMENTATION
#include "audioforge/audio_engine.hpp"

namespace audioforge {

// Standard 10-band graphic EQ spacing (roughly one octave apart, 31Hz-16kHz).
static const double kEqBandFrequencies[AudioEngine::kEqBandCount] =
{
    31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0
};

AudioEngine::~AudioEngine()
{
    for (int i = 0; i < 2; ++i)
    {
        if (m_slotLoaded[i])
        {
            ma_sound_uninit(&m_soundSlots[i]);
        }
    }
    if (m_eqBandsInitialized)
    {
        for (int i = 0; i < kEqBandCount; ++i)
        {
            ma_peak_node_uninit(&m_eqBands[i], NULL);
        }
    }
    if (m_engineInitialized)
    {
        ma_engine_uninit(&m_engine);
    }
}

bool AudioEngine::init()
{
    m_engineInitialized = (ma_engine_init(NULL, &m_engine) == MA_SUCCESS);
    if (m_engineInitialized)
    {
        initEqualizerChain();
    }
    return m_engineInitialized;
}

void AudioEngine::initEqualizerChain()
{
    ma_node_graph* graph = ma_engine_get_node_graph(&m_engine);
    ma_uint32 channels = ma_engine_get_channels(&m_engine);
    ma_uint32 sampleRate = ma_engine_get_sample_rate(&m_engine);

    for (int i = 0; i < kEqBandCount; ++i)
    {
        ma_peak_node_config config = ma_peak_node_config_init(channels, sampleRate, 0.0, 1.0, kEqBandFrequencies[i]);
        if (ma_peak_node_init(graph, &config, NULL, &m_eqBands[i]) != MA_SUCCESS)
        {
            // Leave m_eqBandsInitialized false -- the equalizer just won't
            // be available (isEqualizerAvailable() reports this), rather
            // than crashing on a half-built node chain.
            for (int j = 0; j < i; ++j)
            {
                ma_peak_node_uninit(&m_eqBands[j], NULL);
            }
            return;
        }
    }

    // Chain them in series: band[0] -> band[1] -> ... -> band[9] -> the
    // engine's own output. Sounds attach to band[0]'s input when the EQ is
    // enabled (see routeSoundThroughEqualizer) -- multiple sounds attached
    // to the same input bus mix together automatically, same as they'd mix
    // at the engine endpoint directly, so this doesn't change how the
    // dual-slot crossfade mixing works.
    for (int i = 0; i < kEqBandCount - 1; ++i)
    {
        ma_node_attach_output_bus(&m_eqBands[i], 0, &m_eqBands[i + 1], 0);
    }
    ma_node_attach_output_bus(&m_eqBands[kEqBandCount - 1], 0, ma_node_graph_get_endpoint(graph), 0);

    m_eqBandsInitialized = true;
}

bool AudioEngine::loadAndPlay(const QString& path)
{
    if (m_slotLoaded[m_activeSlot])
    {
        ma_sound_uninit(&ActiveSound());
        m_slotLoaded[m_activeSlot] = false;
    }

    // QString stores UTF-16 internally; toStdWString() hands us a wchar_t*
    // path that plugs straight into miniaudio's wide-char API, avoiding the
    // legacy-codepage issues that break non-ASCII (e.g. Chinese) filenames
    // with the plain char* overload.
    std::wstring pathW = path.toStdWString();
    ma_result result = ma_sound_init_from_file_w(&m_engine, pathW.c_str(), MA_SOUND_FLAG_STREAM, NULL, NULL, &ActiveSound());

    if (result != MA_SUCCESS)
    {
        return false;
    }

    routeSoundThroughEqualizer(ActiveSound());

    m_slotLoaded[m_activeSlot] = true;
    ma_sound_start(&ActiveSound());
    return true;
}

void AudioEngine::play()
{
    if (isLoaded())
    {
        ma_sound_start(&ActiveSound());
    }
}

void AudioEngine::pause()
{
    if (isLoaded())
    {
        ma_sound_stop(&ActiveSound());
    }
}

void AudioEngine::stop()
{
    abortCrossfade();
    if (isLoaded())
    {
        ma_sound_stop(&ActiveSound());
        ma_sound_seek_to_pcm_frame(&ActiveSound(), 0);
    }
}

bool AudioEngine::isLoaded() const
{
    return m_slotLoaded[m_activeSlot];
}

bool AudioEngine::isAtEnd() const
{
    return isLoaded() && ma_sound_at_end(&ActiveSound());
}

bool AudioEngine::isPlaying() const
{
    return isLoaded() && ma_sound_is_playing(&ActiveSound());
}

float AudioEngine::cursorSeconds() const
{
    float cursor = 0.0f;
    if (isLoaded())
    {
        ma_sound_get_cursor_in_seconds(&ActiveSound(), &cursor);
    }
    return cursor;
}

float AudioEngine::lengthSeconds() const
{
    float length = 0.0f;
    if (isLoaded())
    {
        ma_sound_get_length_in_seconds(&ActiveSound(), &length);
    }
    return length;
}

void AudioEngine::setVolume(float linear)
{
    if (isLoaded())
    {
        ma_sound_set_volume(&ActiveSound(), linear);
    }
}

void AudioEngine::seekToSeconds(float seconds)
{
    if (isLoaded())
    {
        ma_sound_seek_to_second(&ActiveSound(), seconds);
    }
}

bool AudioEngine::startCrossfadeTo(const QString& nextPath)
{
    int inactive = InactiveSlot();
    if (m_slotLoaded[inactive])
    {
        ma_sound_uninit(&m_soundSlots[inactive]);
        m_slotLoaded[inactive] = false;
    }

    std::wstring pathW = nextPath.toStdWString();
    if (ma_sound_init_from_file_w(&m_engine, pathW.c_str(), MA_SOUND_FLAG_STREAM, NULL, NULL, &m_soundSlots[inactive]) != MA_SUCCESS)
    {
        return false;
    }

    routeSoundThroughEqualizer(m_soundSlots[inactive]);

    m_slotLoaded[inactive] = true;
    ma_sound_set_volume(&m_soundSlots[inactive], 0.0f);
    ma_sound_start(&m_soundSlots[inactive]);
    m_crossfading = true;
    return true;
}

void AudioEngine::updateCrossfadeVolumes(float activeVolume, float nextVolume)
{
    if (!m_crossfading)
    {
        return;
    }
    ma_sound_set_volume(&ActiveSound(), activeVolume);
    ma_sound_set_volume(&m_soundSlots[InactiveSlot()], nextVolume);
}

void AudioEngine::finalizeCrossfade()
{
    ma_sound_uninit(&ActiveSound());
    m_slotLoaded[m_activeSlot] = false;
    m_activeSlot = InactiveSlot(); // the preloaded slot is now active
    m_crossfading = false;
}

void AudioEngine::abortCrossfade()
{
    if (!m_crossfading)
    {
        return;
    }
    int inactive = InactiveSlot();
    if (m_slotLoaded[inactive])
    {
        ma_sound_uninit(&m_soundSlots[inactive]);
        m_slotLoaded[inactive] = false;
    }
    m_crossfading = false;
}

void AudioEngine::routeSoundThroughEqualizer(ma_sound& sound)
{
    if (m_eqBandsInitialized && m_eqEnabled)
    {
        ma_node_attach_output_bus(&sound, 0, &m_eqBands[0], 0);
    }
    else
    {
        // Straight to the engine's own output, bypassing the EQ chain
        // entirely -- this is also where a freshly-loaded sound already
        // ends up by default, so this branch is really only exercised
        // when re-routing an already-loaded sound after the EQ is
        // switched off (see setEqualizerEnabled).
        ma_node_attach_output_bus(&sound, 0, ma_node_graph_get_endpoint(ma_engine_get_node_graph(&m_engine)), 0);
    }
}

void AudioEngine::setEqualizerEnabled(bool enabled)
{
    if (!m_eqBandsInitialized || m_eqEnabled == enabled)
    {
        return;
    }
    m_eqEnabled = enabled;

    // Re-route whichever slot(s) are currently loaded immediately, so
    // toggling the EQ takes effect on the track that's already playing
    // rather than only on the next track.
    for (int i = 0; i < 2; ++i)
    {
        if (m_slotLoaded[i])
        {
            routeSoundThroughEqualizer(m_soundSlots[i]);
        }
    }
}

float AudioEngine::equalizerBandFrequency(int bandIndex)
{
    if (bandIndex < 0 || bandIndex >= kEqBandCount)
    {
        return 0.0f;
    }
    return static_cast<float>(kEqBandFrequencies[bandIndex]);
}

float AudioEngine::equalizerBandGain(int bandIndex) const
{
    if (bandIndex < 0 || bandIndex >= kEqBandCount)
    {
        return 0.0f;
    }
    return m_eqBandGainsDb[bandIndex];
}

void AudioEngine::setEqualizerBandGain(int bandIndex, float gainDb)
{
    if (!m_eqBandsInitialized || bandIndex < 0 || bandIndex >= kEqBandCount)
    {
        return;
    }
    m_eqBandGainsDb[bandIndex] = gainDb;

    ma_uint32 channels = ma_engine_get_channels(&m_engine);
    ma_uint32 sampleRate = ma_engine_get_sample_rate(&m_engine);

    // ma_peak_node_reinit() takes the underlying biquad filter's config
    // (ma_peak_config, aka ma_peak2_config) -- NOT the ma_peak_node_config
    // that ma_peak_node_init() takes. Two different config types for the
    // node-level API vs. the filter-level API underneath it.
    ma_peak_config filterConfig = ma_peak2_config_init(ma_format_f32, channels, sampleRate, gainDb, 1.0, kEqBandFrequencies[bandIndex]);
    ma_peak_node_reinit(&filterConfig, &m_eqBands[bandIndex]); // live update -- no reload of the playing track needed
}

} // namespace audioforge