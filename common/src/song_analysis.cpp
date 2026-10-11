#include "audioforge/song_analysis.hpp"

#include "miniaudio.h" // implementation lives in audio_engine.cpp

#include <QRandomGenerator>

#include <algorithm>
#include <cmath>
#include <complex>
#include <vector>

// A C++ port of the iOS AudioAnalyzer (ios/AudioForge/AudioAnalyzer.swift):
//
// - Tempo: spectral-flux onset envelope -> autocorrelation (preferring ~120
//   BPM) -> refined by a comb search that also gives the beat phase.
// - Key: chromagram correlated with the Krumhansl major/minor profiles.
// - Silence / outro: loudness envelope against the song's typical loudness.
//
// The algorithm and constants are kept identical so desktop and iPhone agree.

namespace audioforge {

namespace {

constexpr double kSampleRate = 11025.0;      // mono, enough for beats and notes up to ~5 kHz
constexpr double kMaxAnalyzedSeconds = 600.0; // long files: first 10 minutes only
constexpr double kPi = 3.14159265358979323846;

struct Decoded
{
    std::vector<float> samples;
    double duration = 0.0;
    bool isComplete = false;
};

std::optional<Decoded> DecodeMono(const QString& path)
{
    ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 1, static_cast<ma_uint32>(kSampleRate));
    ma_decoder decoder;
#ifdef _WIN32
    const std::wstring pathW = path.toStdWString();
    if (ma_decoder_init_file_w(pathW.c_str(), &config, &decoder) != MA_SUCCESS)
#else
    if (ma_decoder_init_file(path.toUtf8().constData(), &config, &decoder) != MA_SUCCESS)
#endif
    {
        return std::nullopt;
    }

    Decoded result;
    ma_uint64 totalFrames = 0;
    if (ma_decoder_get_length_in_pcm_frames(&decoder, &totalFrames) == MA_SUCCESS && totalFrames > 0)
    {
        result.duration = static_cast<double>(totalFrames) / kSampleRate;
    }

    const size_t limit = static_cast<size_t>(kMaxAnalyzedSeconds * kSampleRate);
    result.samples.reserve(std::min<size_t>(limit, totalFrames > 0 ? static_cast<size_t>(totalFrames) : limit));
    std::vector<float> chunk(16384);
    while (result.samples.size() < limit)
    {
        ma_uint64 read = 0;
        ma_result r = ma_decoder_read_pcm_frames(&decoder, chunk.data(), chunk.size(), &read);
        if (read > 0)
        {
            result.samples.insert(result.samples.end(), chunk.begin(), chunk.begin() + static_cast<ptrdiff_t>(read));
        }
        if (r != MA_SUCCESS || read < chunk.size())
        {
            break;
        }
    }
    ma_decoder_uninit(&decoder);

    if (result.samples.size() > limit)
    {
        result.samples.resize(limit);
    }
    if (result.duration <= 0.0)
    {
        result.duration = static_cast<double>(result.samples.size()) / kSampleRate;
    }
    result.isComplete = result.duration <= kMaxAnalyzedSeconds;
    return result;
}

// --- Spectra ----------------------------------------------------------------

// Radix-2 FFT with precomputed twiddles and bit reversal, for one frame size.
class Fft
{
public:
    explicit Fft(int size) : m_size(size), m_bitReverse(size), m_twiddles(size / 2), m_window(size)
    {
        int bits = 0;
        while ((1 << bits) < size)
        {
            ++bits;
        }
        for (int i = 0; i < size; ++i)
        {
            int reversed = 0;
            for (int b = 0; b < bits; ++b)
            {
                if (i & (1 << b))
                {
                    reversed |= 1 << (bits - 1 - b);
                }
            }
            m_bitReverse[i] = reversed;
        }
        for (int i = 0; i < size / 2; ++i)
        {
            m_twiddles[i] = std::polar(1.0f, static_cast<float>(-2.0 * kPi * i / size));
        }
        for (int i = 0; i < size; ++i)
        {
            m_window[i] = static_cast<float>(0.5 * (1.0 - std::cos(2.0 * kPi * i / size))); // Hann
        }
        m_buffer.resize(size);
    }

    // Magnitudes of bins 0..size/2-1 of the Hann-windowed frame at `input`.
    // Scaled x2 like Accelerate's real FFT, so log-compression constants match iOS.
    void magnitudes(const float* input, float* out)
    {
        for (int i = 0; i < m_size; ++i)
        {
            m_buffer[m_bitReverse[i]] = std::complex<float>(input[i] * m_window[i], 0.0f);
        }
        for (int len = 2; len <= m_size; len <<= 1)
        {
            const int half = len / 2;
            const int step = m_size / len;
            for (int start = 0; start < m_size; start += len)
            {
                for (int k = 0; k < half; ++k)
                {
                    const std::complex<float> t = m_twiddles[k * step] * m_buffer[start + k + half];
                    m_buffer[start + k + half] = m_buffer[start + k] - t;
                    m_buffer[start + k] += t;
                }
            }
        }
        for (int i = 0; i < m_size / 2; ++i)
        {
            out[i] = 2.0f * std::abs(m_buffer[i]);
        }
    }

private:
    int m_size;
    std::vector<int> m_bitReverse;
    std::vector<std::complex<float>> m_twiddles;
    std::vector<float> m_window;
    std::vector<std::complex<float>> m_buffer;
};

template <typename Body>
void ForEachSpectrum(const std::vector<float>& samples, int size, int hop, Body body)
{
    if (static_cast<int>(samples.size()) < size)
    {
        return;
    }
    Fft fft(size);
    std::vector<float> magnitudes(size / 2);
    int index = 0;
    for (size_t start = 0; start + size <= samples.size(); start += hop)
    {
        fft.magnitudes(samples.data() + start, magnitudes.data());
        body(index++, magnitudes);
    }
}

// --- Tempo --------------------------------------------------------------------

constexpr int kOnsetFrameSize = 1024;
constexpr int kOnsetHop = 128;
constexpr double kOnsetHopTime = kOnsetHop / kSampleRate;

struct Tempo
{
    double bpm = 0.0;
    double confidence = 0.0;
    double firstBeat = 0.0;
};

Tempo TempoAnalysis(const std::vector<float>& samples)
{
    // 1. Onset strength: how much the (log-compressed) spectrum grows frame to frame.
    const int bins = kOnsetFrameSize / 2;
    std::vector<float> previous(bins, 0.0f), current(bins, 0.0f);
    std::vector<float> envelope;
    envelope.reserve(samples.size() / kOnsetHop + 1);
    ForEachSpectrum(samples, kOnsetFrameSize, kOnsetHop, [&](int index, const std::vector<float>& mags) {
        float flux = 0.0f;
        for (int bin = 0; bin < bins; ++bin)
        {
            const float value = std::log1p(100.0f * mags[bin]);
            current[bin] = value;
            if (index > 0)
            {
                flux += std::max(0.0f, value - previous[bin]);
            }
        }
        std::swap(previous, current);
        envelope.push_back(flux);
    });
    if (envelope.size() <= 400)
    {
        return {};
    }

    // 2. Keep only the peaks: subtract a ~0.5 s moving average, clip, normalize.
    const int window = static_cast<int>(0.5 / kOnsetHopTime);
    const int n = static_cast<int>(envelope.size());
    std::vector<float> smoothed(n, 0.0f);
    float sum = 0.0f;
    for (int i = 0; i < n; ++i)
    {
        sum += envelope[i];
        if (i >= window)
        {
            sum -= envelope[i - window];
        }
        smoothed[i] = std::max(0.0f, envelope[i] - sum / static_cast<float>(std::min(i + 1, window)));
    }
    double mean = 0.0;
    for (float v : smoothed)
    {
        mean += v;
    }
    mean /= n;
    if (mean <= 0.0)
    {
        return {};
    }
    for (float& v : smoothed)
    {
        v = static_cast<float>(v / mean);
    }

    // 3. Autocorrelation over 60-200 BPM, weighted towards ~120 BPM.
    const int minLag = static_cast<int>((60.0 / 200.0) / kOnsetHopTime);
    const int maxLag = static_cast<int>((60.0 / 60.0) / kOnsetHopTime);
    if (n <= maxLag * 4)
    {
        return {};
    }
    std::vector<double> correlation(maxLag + 2, 0.0);
    for (int lag = std::max(1, minLag - 1); lag <= maxLag + 1; ++lag)
    {
        double dot = 0.0;
        const int count = n - lag;
        for (int i = 0; i < count; ++i)
        {
            dot += static_cast<double>(smoothed[i]) * smoothed[i + lag];
        }
        correlation[lag] = dot / count;
    }
    int bestLag = minLag;
    double bestScore = -1e300;
    double scoreSum = 0.0;
    for (int lag = minLag; lag <= maxLag; ++lag)
    {
        const double bpm = 60.0 / (lag * kOnsetHopTime);
        const double octaves = std::log2(bpm / 120.0);
        const double score = correlation[lag] * std::exp(-0.5 * octaves * octaves);
        scoreSum += correlation[lag];
        if (score > bestScore)
        {
            bestScore = score;
            bestLag = lag;
        }
    }
    const double average = scoreSum / (maxLag - minLag + 1);
    const double peakRatio = average > 0.0 ? correlation[bestLag] / average : 0.0;
    // Parabolic interpolation around the peak for a sub-frame period.
    const double a = correlation[bestLag - 1], b = correlation[bestLag], c = correlation[bestLag + 1];
    const double denominator = a - 2.0 * b + c;
    const double offset = denominator != 0.0 ? std::clamp(0.5 * (a - c) / denominator, -0.5, 0.5) : 0.0;
    const double roughPeriod = bestLag + offset;

    // 4. Comb search: the period (+-2%) and phase whose beats land on the most onsets.
    double bestPeriod = roughPeriod;
    double bestPhase = 0.0;
    double bestComb = -1e300;
    for (int step = -40; step <= 40; ++step)
    {
        const double period = roughPeriod * (1.0 + step * 0.0005);
        const int phases = std::max(static_cast<int>(period), 1);
        for (int phase = 0; phase < phases; ++phase)
        {
            double position = phase;
            double total = 0.0;
            int count = 0;
            while (position < n - 1)
            {
                const int i = static_cast<int>(position);
                const float fraction = static_cast<float>(position - i);
                total += smoothed[i] * (1.0f - fraction) + smoothed[i + 1] * fraction;
                ++count;
                position += period;
            }
            const double score = count > 0 ? total / count : 0.0;
            if (score > bestComb)
            {
                bestComb = score;
                bestPeriod = period;
                bestPhase = phase;
            }
        }
    }

    Tempo tempo;
    const double bpm = 60.0 / (bestPeriod * kOnsetHopTime);
    // How much the beat stands out: ~1 for no pulse, 2+ for a steady drum beat.
    tempo.confidence = std::clamp((peakRatio - 1.15) / 0.85, 0.0, 1.0);
    if (tempo.confidence <= 0.1 || !std::isfinite(bpm))
    {
        return tempo; // bpm stays 0
    }
    tempo.bpm = std::round(bpm * 10.0) / 10.0;
    // Onset frame i is centred at (i * hop + frameSize / 2) samples.
    tempo.firstBeat = (bestPhase * kOnsetHop + kOnsetFrameSize / 2.0) / kSampleRate;
    return tempo;
}

// --- Key ----------------------------------------------------------------------

// Krumhansl-Kessler key profiles (how much each scale degree is heard in a key).
const double kMajorProfile[12] = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
const double kMinorProfile[12] = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

double Correlation(const double* x, const double* y)
{
    double meanX = 0.0, meanY = 0.0;
    for (int i = 0; i < 12; ++i)
    {
        meanX += x[i];
        meanY += y[i];
    }
    meanX /= 12.0;
    meanY /= 12.0;
    double covariance = 0.0, varianceX = 0.0, varianceY = 0.0;
    for (int i = 0; i < 12; ++i)
    {
        const double dx = x[i] - meanX, dy = y[i] - meanY;
        covariance += dx * dy;
        varianceX += dx * dx;
        varianceY += dy * dy;
    }
    const double denominator = std::sqrt(varianceX * varianceY);
    return denominator > 0.0 ? covariance / denominator : 0.0;
}

void KeyAnalysis(const std::vector<float>& samples, int* indexOut, double* confidenceOut)
{
    *indexOut = -1;
    *confidenceOut = 0.0;
    const int size = 4096;
    const double binWidth = kSampleRate / size;
    // Which note each FFT bin belongs to, from C2 (65 Hz) to ~C7 (2.1 kHz).
    std::vector<int> pitchOfBin(size / 2, -1);
    for (int bin = 1; bin < size / 2; ++bin)
    {
        const double frequency = bin * binWidth;
        if (frequency < 65.0 || frequency > 2100.0)
        {
            continue;
        }
        const double midi = 69.0 + 12.0 * std::log2(frequency / 440.0);
        pitchOfBin[bin] = ((static_cast<int>(std::lround(midi)) % 12) + 12) % 12;
    }

    double chroma[12] = {0};
    ForEachSpectrum(samples, size, size, [&](int, const std::vector<float>& mags) {
        double frame[12] = {0};
        double energy = 0.0;
        for (int bin = 1; bin < size / 2; ++bin)
        {
            if (pitchOfBin[bin] < 0)
            {
                continue;
            }
            frame[pitchOfBin[bin]] += mags[bin];
            energy += mags[bin];
        }
        if (energy <= 0.0)
        {
            return; // silence
        }
        for (int p = 0; p < 12; ++p)
        {
            chroma[p] += frame[p] / energy;
        }
    });
    if (std::none_of(std::begin(chroma), std::end(chroma), [](double v) { return v > 0.0; }))
    {
        return;
    }

    struct Score { int index; double score; };
    std::vector<Score> scores;
    for (int tonic = 0; tonic < 12; ++tonic)
    {
        double major[12], minor[12];
        for (int i = 0; i < 12; ++i)
        {
            major[i] = kMajorProfile[((i - tonic) % 12 + 12) % 12];
            minor[i] = kMinorProfile[((i - tonic) % 12 + 12) % 12];
        }
        scores.push_back({tonic, Correlation(chroma, major)});
        scores.push_back({12 + tonic, Correlation(chroma, minor)});
    }
    std::sort(scores.begin(), scores.end(), [](const Score& l, const Score& r) { return l.score > r.score; });
    const double margin = scores[0].score - scores[1].score;
    *confidenceOut = std::clamp(margin * 6.0 + std::max(0.0, scores[0].score - 0.5), 0.0, 1.0);
    if (scores[0].score > 0.3)
    {
        *indexOut = scores[0].index;
    }
}

// --- Silence and outro ----------------------------------------------------------

void LoudnessAnalysis(const std::vector<float>& samples, double fullDuration, bool analyzedWholeFile,
                      double* start, double* end, double* outroStart)
{
    const int hop = 512;
    const double hopTime = hop / kSampleRate;
    const int frames = static_cast<int>(samples.size() / hop);
    *start = 0.0;
    *end = fullDuration;
    *outroStart = fullDuration;
    if (frames <= 20)
    {
        return;
    }

    std::vector<float> levels(frames);
    for (int i = 0; i < frames; ++i)
    {
        double sumSquares = 0.0;
        for (int j = 0; j < hop; ++j)
        {
            const float s = samples[static_cast<size_t>(i) * hop + j];
            sumSquares += static_cast<double>(s) * s;
        }
        const float rms = static_cast<float>(std::sqrt(sumSquares / hop));
        levels[i] = 20.0f * std::log10(std::max(rms, 1e-7f));
    }
    const float peak = *std::max_element(levels.begin(), levels.end());
    const float threshold = std::max(peak - 45.0f, -60.0f); // quieter than this counts as silence
    int first = 0;
    while (first < frames && levels[first] <= threshold)
    {
        ++first;
    }
    if (first == frames)
    {
        first = 0;
    }
    int last = frames - 1;
    while (last >= 0 && levels[last] <= threshold)
    {
        --last;
    }
    if (last < 0)
    {
        last = frames - 1;
    }
    *start = first * hopTime;

    // Only the analyzed part is known; for long files the end stays the file's end.
    if (!analyzedWholeFile || last < first)
    {
        return;
    }
    *end = std::min((last + 1) * hopTime, fullDuration);

    // Typical loudness of the song (median of the non-silent part).
    std::vector<float> body(levels.begin() + first, levels.begin() + last + 1);
    std::sort(body.begin(), body.end());
    const float typical = body[body.size() / 2];

    // Outro: walking back from the end, the last moment the 1-second average
    // loudness is still within 6 dB of typical. Limited to the last 20 s.
    const int averageWindow = std::max(1, static_cast<int>(1.0 / hopTime));
    int outroFrame = last;
    const int earliest = std::max(first, last - static_cast<int>(20.0 / hopTime));
    for (int index = last; index >= earliest; --index)
    {
        const int lower = std::max(first, index - averageWindow + 1);
        float total = 0.0f;
        for (int k = lower; k <= index; ++k)
        {
            total += levels[k];
        }
        if (total / (index - lower + 1) >= typical - 6.0f)
        {
            break;
        }
        outroFrame = index;
    }
    *outroStart = std::min(outroFrame * hopTime, *end);
}

} // namespace

std::optional<SongAnalysis> AnalyzeSongFile(const QString& path)
{
    std::optional<Decoded> decoded = DecodeMono(path);
    if (!decoded || decoded->samples.size() <= static_cast<size_t>(kSampleRate * 5))
    {
        return std::nullopt; // unreadable, or under 5 s: nothing useful
    }

    SongAnalysis result;
    result.duration = decoded->duration;
    LoudnessAnalysis(decoded->samples, decoded->duration, decoded->isComplete,
                     &result.audioStart, &result.audioEnd, &result.outroStart);
    const Tempo tempo = TempoAnalysis(decoded->samples);
    result.bpm = tempo.bpm;
    result.bpmConfidence = tempo.confidence;
    result.firstBeat = tempo.firstBeat;
    KeyAnalysis(decoded->samples, &result.keyIndex, &result.keyConfidence);
    return result;
}

// --- Keys -------------------------------------------------------------------------

MusicKey MusicKey::fromIndex(int index)
{
    MusicKey key;
    key.pitchClass = ((index % 12) + 12) % 12;
    key.isMinor = index >= 12;
    return key;
}

int MusicKey::camelotNumber() const
{
    const int majorPitch = isMinor ? (pitchClass + 3) % 12 : pitchClass; // relative major
    return (majorPitch * 7 % 12 + 7) % 12 + 1;
}

QString MusicKey::camelot() const
{
    return QString::number(camelotNumber()) + (isMinor ? "A" : "B");
}

QString MusicKey::shortName() const
{
    static const char* names[] = {"C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B"};
    return QString::fromUtf8(names[pitchClass]) + (isMinor ? "m" : "");
}

bool MusicKey::isCompatibleWith(const MusicKey& other) const
{
    if (camelotNumber() == other.camelotNumber())
    {
        return true;
    }
    if (isMinor != other.isMinor)
    {
        return false;
    }
    const int difference = std::abs(camelotNumber() - other.camelotNumber());
    return difference == 1 || difference == 11;
}

double SongCompatibility(const SongAnalysis* a, const SongAnalysis* b)
{
    if (!a || !b)
    {
        return 0.0;
    }
    double score = 0.0;
    if (a->keyIndex >= 0 && b->keyIndex >= 0)
    {
        const MusicKey keyA = MusicKey::fromIndex(a->keyIndex);
        const MusicKey keyB = MusicKey::fromIndex(b->keyIndex);
        if (keyA == keyB)
        {
            score += 3.0;
        }
        else if (keyA.isCompatibleWith(keyB))
        {
            score += 2.0;
        }
    }
    if (a->hasBpm() && b->hasBpm())
    {
        double ratio = 2.0;
        for (double r : {a->bpm / b->bpm, a->bpm * 2.0 / b->bpm, a->bpm / (b->bpm * 2.0)})
        {
            ratio = std::min(ratio, std::max(r, 1.0 / r));
        }
        if (ratio <= 1.06)
        {
            score += 2.0;
        }
        else if (ratio <= 1.12)
        {
            score += 1.0;
        }
    }
    return score;
}

QVector<int> HarmonicOrder(const QVector<TrackInfo>& items, int start, const QHash<QString, SongAnalysis>& analysis)
{
    QVector<int> order;
    if (start < 0 || start >= items.size())
    {
        for (int i = 0; i < items.size(); ++i)
        {
            order << i;
        }
        return order;
    }

    auto lookup = [&](int index) -> const SongAnalysis* {
        auto it = analysis.constFind(items[index].path);
        return it == analysis.constEnd() ? nullptr : &it.value();
    };

    QVector<int> pool;
    for (int i = 0; i < items.size(); ++i)
    {
        if (i != start)
        {
            pool << i;
        }
    }
    QRandomGenerator* rng = QRandomGenerator::global();
    order << start;
    int current = start;
    while (!pool.isEmpty())
    {
        int bestPosition = static_cast<int>(rng->bounded(pool.size()));
        double bestScore = -1e300;
        const int candidates = std::min<int>(10, pool.size());
        for (int c = 0; c < candidates; ++c)
        {
            const int position = static_cast<int>(rng->bounded(pool.size()));
            const double score = SongCompatibility(lookup(current), lookup(pool[position])) + rng->generateDouble() * 0.5;
            if (score > bestScore)
            {
                bestScore = score;
                bestPosition = position;
            }
        }
        current = pool[bestPosition];
        order << current;
        pool.swapItemsAt(bestPosition, pool.size() - 1);
        pool.removeLast();
    }
    return order;
}

} // namespace audioforge
