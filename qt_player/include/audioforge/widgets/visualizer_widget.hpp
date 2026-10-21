#pragma once

#include <QColor>
#include <QElapsedTimer>
#include <QImage>
#include <QPixmap>
#include <QWidget>

#include <array>
#include <random>
#include <vector>

#include "audioforge/visualizer_media.hpp"
#include "audioforge/visualizer_tap.hpp"

class QMediaPlayer;
class QMovie;
class QTimer;
class QVideoSink;

namespace audioforge {

// Settings > Visualizer: the ten looks, the same as the Android app's.
enum class VisualizerStyle
{
    ClassicBars, Spectrum, NeonBars,
    LineWave, RetroWave, FlowingWave,
    Radial, Particles, Geometric,
    Disc,
};
constexpr int kVisualizerStyleCount = 10;
QString VisualizerStyleName(VisualizerStyle style); // translated

// The cover's most characteristic colour (saturated pixels count more),
// toned to work on dark backgrounds -- the Android/iOS themeColor(). An
// invalid QColor if there's no cover.
QColor CoverThemeColor(const QByteArray& imageBytes);

// The Now Playing visualizer, in place of the cover: ~30 frames a second of
// the spectrum drawn in one of the styles, in the song's cover colour -- or
// the song's own GIF / looping video (VisualizerMediaEntry), cropped round
// or square and turning while music plays if it spins. A port of the
// Android app's Visualizers.kt.
class VisualizerWidget : public QWidget
{
    Q_OBJECT

public:
    explicit VisualizerWidget(VisualizerTap* tap, QWidget* parent = nullptr);
    ~VisualizerWidget() override;

    void setStyle(VisualizerStyle style);
    void setColor(const QColor& color);
    void setCover(const QPixmap& cover);  // the record label for Disc
    void setPlaying(bool playing);        // the record (and spinning media) only turns while music plays
    void setMedia(const VisualizerMediaEntry* entry); // null = draw the style

    // On screen: frames run and the tap is fed. Off: everything stops.
    void setActive(bool active);

    // Without a tap (Settings' previews): the next frame, drawn with these levels.
    void showFrame(const std::array<float, VisualizerTap::kBands>& levels);

protected:
    void paintEvent(QPaintEvent* event) override;

private:
    struct Particle
    {
        float x, y, vx, vy, life, size;
    };

    void tick();
    void advance(const std::array<float, VisualizerTap::kBands>& levels);
    void stopMedia();
    void onMediaFailed();
    void drawMedia(QPainter& p);
    void drawStyle(QPainter& p);

    void classicBars(QPainter& p);
    void spectrum(QPainter& p);
    void neonBars(QPainter& p);
    void lineWave(QPainter& p);
    void retroWave(QPainter& p);
    void flowingWave(QPainter& p);
    void radial(QPainter& p);
    void particles(QPainter& p);
    void geometric(QPainter& p);
    void disc(QPainter& p);

    float at(float x) const; // the spectrum at x (0 = lowest band, 1 = highest), interpolated
    float bass() const;
    float energy() const;

    VisualizerTap* m_tap;
    QTimer* m_timer;
    QElapsedTimer m_clock;
    qint64 m_lastTickMs = 0;
    bool m_active = false;
    bool m_playing = false;
    VisualizerStyle m_style = VisualizerStyle::ClassicBars;
    QColor m_color = QColor(240, 195, 164);
    QPixmap m_cover;
    std::array<float, VisualizerTap::kBands> m_levels{};
    float m_spinDegrees = 0.0f;
    float m_timeSeconds = 0.0f;

    std::vector<Particle> m_particles;
    float m_particleTime = -1.0f; // m_timeSeconds when they last moved
    std::mt19937 m_random{7};

    // Custom GIF / video.
    QString m_mediaPath;
    bool m_mediaRound = true;
    bool m_mediaSpin = true;
    bool m_mediaFailed = false;
    QMovie* m_movie = nullptr;
    QMediaPlayer* m_player = nullptr;
    QVideoSink* m_sink = nullptr;
    QImage m_mediaFrame;
};

} // namespace audioforge
