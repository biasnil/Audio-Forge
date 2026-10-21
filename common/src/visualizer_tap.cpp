#include "audioforge/visualizer_tap.hpp"

#include <algorithm>
#include <cmath>
#include <utility>

namespace audioforge {

namespace {

constexpr double kPi = 3.14159265358979323846;

// In-place radix-2 FFT (size a power of two).
void Fft(std::vector<double>& re, std::vector<double>& im)
{
    const size_t n = re.size();
    for (size_t i = 1, j = 0; i < n; ++i)
    {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1)
        {
            j ^= bit;
        }
        j ^= bit;
        if (i < j)
        {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }
    for (size_t length = 2; length <= n; length <<= 1)
    {
        const double angle = -2.0 * kPi / static_cast<double>(length);
        const double wRe = std::cos(angle);
        const double wIm = std::sin(angle);
        for (size_t start = 0; start < n; start += length)
        {
            double curRe = 1.0;
            double curIm = 0.0;
            for (size_t k = 0; k < length / 2; ++k)
            {
                const size_t a = start + k;
                const size_t b = a + length / 2;
                const double tRe = re[b] * curRe - im[b] * curIm;
                const double tIm = re[b] * curIm + im[b] * curRe;
                re[b] = re[a] - tRe;
                im[b] = im[a] - tIm;
                re[a] += tRe;
                im[a] += tIm;
                const double nextRe = curRe * wRe - curIm * wIm;
                curIm = curRe * wIm + curIm * wRe;
                curRe = nextRe;
            }
        }
    }
}

} // namespace

VisualizerTap::VisualizerTap() : m_ring(kRing, 0.0f), m_window(kSize), m_re(kSize), m_im(kSize)
{
    for (int i = 0; i < kSize; ++i)
    {
        m_window[i] = 0.5 - 0.5 * std::cos(2.0 * kPi * i / kSize); // Hann
    }
}

void VisualizerTap::push(const float* frames, unsigned long long frameCount, unsigned int channels)
{
    if (!isEnabled() || channels == 0)
    {
        return;
    }
    unsigned long long index = m_written.load(std::memory_order_relaxed);
    for (unsigned long long f = 0; f < frameCount; ++f)
    {
        float sum = 0.0f;
        for (unsigned int c = 0; c < channels; ++c)
        {
            sum += frames[f * channels + c];
        }
        m_ring[index & (kRing - 1)] = sum / static_cast<float>(channels);
        ++index;
    }
    m_written.store(index, std::memory_order_release);
}

std::array<float, VisualizerTap::kBands> VisualizerTap::levels()
{
    const unsigned long long end = m_written.load(std::memory_order_acquire);
    if (end == m_lastWritten || end < static_cast<unsigned long long>(kSize))
    {
        // Paused: let the bars fall.
        for (float& level : m_smoothed)
        {
            level *= 0.85f;
        }
        return m_smoothed;
    }
    m_lastWritten = end;
    const unsigned long long start = end - kSize;
    for (int i = 0; i < kSize; ++i)
    {
        m_re[i] = m_ring[(start + i) & (kRing - 1)] * m_window[i];
        m_im[i] = 0.0;
    }
    Fft(m_re, m_im);

    // Logarithmically spaced bands from ~40 Hz to ~16 kHz, in dB, mapped to 0..1.
    const double binWidth = m_sampleRate / 2.0 / (kSize / 2);
    for (int band = 0; band < kBands; ++band)
    {
        const double low = 40.0 * std::pow(400.0, static_cast<double>(band) / kBands);
        const double high = 40.0 * std::pow(400.0, static_cast<double>(band + 1) / kBands);
        const int first = std::max(1, static_cast<int>(low / binWidth));
        const int last = std::min(kSize / 2 - 1, std::max(first, static_cast<int>(high / binWidth)));
        double peak = 0.0;
        for (int bin = first; bin <= last; ++bin)
        {
            peak = std::max(peak, std::sqrt(m_re[bin] * m_re[bin] + m_im[bin] * m_im[bin]));
        }
        const double db = 20.0 * std::log10(std::max(peak / kSize * 2.0, 1e-6)); // x2: the iOS FFT's scale
        const float level = static_cast<float>(std::clamp((db + 70.0) / 60.0, 0.0, 1.0));
        // Rise quickly, fall slowly.
        float& smoothed = m_smoothed[band];
        smoothed = level > smoothed ? level : smoothed * 0.85f + level * 0.15f;
    }
    return m_smoothed;
}

} // namespace audioforge
