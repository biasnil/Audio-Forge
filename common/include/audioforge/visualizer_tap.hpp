#pragma once

#include <array>
#include <atomic>
#include <vector>

namespace audioforge {

// Spectrum levels for the Now Playing visualizer -- a port of the Android
// app's VisualizerTap. The audio engine writes what it's playing here (mixed
// to mono) while enabled; levels() turns the latest 1024 samples into kBands
// bars, 0..1, low to high. One writer (the audio thread), one reader (the
// UI); a torn read only means one slightly odd frame of bars.
class VisualizerTap
{
public:
    static constexpr int kBands = 24;

    VisualizerTap();

    // Measuring costs a little, so the engine only feeds the tap while
    // something shows it: each viewer (Now Playing, Settings' previews)
    // acquires it while on screen and releases it after.
    void acquire() { m_users.fetch_add(1, std::memory_order_relaxed); }
    void release() { m_users.fetch_sub(1, std::memory_order_relaxed); }
    bool isEnabled() const { return m_users.load(std::memory_order_relaxed) > 0; }
    void setSampleRate(int rate) { m_sampleRate = rate; }

    // Audio thread: interleaved frames, mixed down to mono.
    void push(const float* frames, unsigned long long frameCount, unsigned int channels);

    // UI thread, ~30 times a second: the current bars. They fall back to
    // zero when nothing new has played (paused).
    std::array<float, kBands> levels();

private:
    static constexpr int kSize = 1024;
    static constexpr int kRing = 4096; // power of two

    std::atomic<int> m_users{0};
    int m_sampleRate = 44100;
    std::vector<float> m_ring;
    std::atomic<unsigned long long> m_written{0};

    unsigned long long m_lastWritten = ~0ULL;
    std::vector<double> m_window;
    std::vector<double> m_re;
    std::vector<double> m_im;
    std::array<float, kBands> m_smoothed{};
};

} // namespace audioforge
