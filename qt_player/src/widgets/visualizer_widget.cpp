#include "audioforge/widgets/visualizer_widget.hpp"
#include "audioforge/translations.hpp"
#include "audioforge/warnings.hpp"

#include <QConicalGradient>
#include <QFileInfo>
#include <QImage>
#include <QLinearGradient>
#include <QMediaPlayer>
#include <QMovie>
#include <QPainter>
#include <QPainterPath>
#include <QTimer>
#include <QVideoFrame>
#include <QVideoSink>

#include <algorithm>
#include <cmath>

namespace audioforge {

namespace {

constexpr float kPi = 3.14159265f;
constexpr int kFrameMs = 30;
constexpr float kDiscDegreesPerMs = 360.0f * 33.333f / 60000.0f; // 33 1/3 rpm
constexpr size_t kMaxParticles = 600;

QColor WithAlpha(QColor color, float alpha)
{
    color.setAlphaF(std::clamp(alpha, 0.0f, 1.0f) * color.alphaF());
    return color;
}

QColor Lerp(const QColor& a, const QColor& b, float t)
{
    return QColor::fromRgbF(a.redF() + (b.redF() - a.redF()) * t, a.greenF() + (b.greenF() - a.greenF()) * t,
                            a.blueF() + (b.blueF() - a.blueF()) * t, a.alphaF() + (b.alphaF() - a.alphaF()) * t);
}

QColor Lighter(const QColor& color, float amount)
{
    return Lerp(color, QColor(255, 255, 255, color.alpha()), amount);
}

// The colour with its hue turned (two-colour styles from one cover colour).
QColor ShiftHue(const QColor& color, float degrees)
{
    float h, s, v, a;
    color.getHsvF(&h, &s, &v, &a);
    if (h < 0.0f)
    {
        h = 0.0f; // achromatic
    }
    h = std::fmod(h + degrees / 360.0f + 1.0f, 1.0f);
    return QColor::fromHsvF(h, std::max(s, 0.45f), v, a);
}

QPen RoundPen(const QColor& color, qreal width)
{
    QPen pen(color, width);
    pen.setCapStyle(Qt::RoundCap);
    pen.setJoinStyle(Qt::RoundJoin);
    return pen;
}

template <typename YOf>
QPainterPath WavePath(int points, qreal width, YOf y)
{
    QPainterPath path;
    for (int i = 0; i <= points; ++i)
    {
        const float x = static_cast<float>(i) / points;
        if (i == 0)
        {
            path.moveTo(0, y(x));
        }
        else
        {
            path.lineTo(x * width, y(x));
        }
    }
    return path;
}

// The image scaled to fill `target` exactly, the overflow cropped (like the covers).
QImage FillCrop(const QImage& image, const QSize& target)
{
    const QImage scaled = image.scaled(target, Qt::KeepAspectRatioByExpanding, Qt::SmoothTransformation);
    return scaled.copy((scaled.width() - target.width()) / 2, (scaled.height() - target.height()) / 2,
                       target.width(), target.height());
}

} // namespace

QString VisualizerStyleName(VisualizerStyle style)
{
    switch (style)
    {
        case VisualizerStyle::ClassicBars: return Tr("Classic bars");
        case VisualizerStyle::Spectrum:    return Tr("Spectrum");
        case VisualizerStyle::NeonBars:    return Tr("Neon bars");
        case VisualizerStyle::LineWave:    return Tr("Line wave");
        case VisualizerStyle::RetroWave:   return Tr("Retro wave");
        case VisualizerStyle::FlowingWave: return Tr("Flowing wave");
        case VisualizerStyle::Radial:      return Tr("Circular");
        case VisualizerStyle::Particles:   return Tr("Particles");
        case VisualizerStyle::Geometric:   return Tr("Geometric");
        case VisualizerStyle::Disc:        return Tr("Spinning record");
    }
    return QString();
}

QColor CoverThemeColor(const QByteArray& imageBytes)
{
    QImage image;
    if (imageBytes.isEmpty() || !image.loadFromData(imageBytes))
    {
        return QColor();
    }
    const QImage small = image.scaled(32, 32, Qt::IgnoreAspectRatio, Qt::SmoothTransformation).convertToFormat(QImage::Format_RGB32);
    double red = 0.0, green = 0.0, blue = 0.0, total = 0.0;
    for (int y = 0; y < small.height(); ++y)
    {
        const QRgb* line = reinterpret_cast<const QRgb*>(small.constScanLine(y));
        for (int x = 0; x < small.width(); ++x)
        {
            const double r = qRed(line[x]) / 255.0;
            const double g = qGreen(line[x]) / 255.0;
            const double b = qBlue(line[x]) / 255.0;
            const double max = std::max({r, g, b});
            const double min = std::min({r, g, b});
            const double saturation = max > 0.0 ? (max - min) / max : 0.0;
            const double weight = saturation * saturation * max + 0.02; // saturated pixels count more
            red += r * weight;
            green += g * weight;
            blue += b * weight;
            total += weight;
        }
    }
    if (total <= 0.0)
    {
        return QColor();
    }
    QColor average = QColor::fromRgbF(red / total, green / total, blue / total);
    float h, s, v;
    average.getHsvF(&h, &s, &v);
    return QColor::fromHsvF(std::max(h, 0.0f), std::clamp(s, 0.45f, 0.85f), std::clamp(v, 0.6f, 0.9f));
}

VisualizerWidget::VisualizerWidget(VisualizerTap* tap, QWidget* parent)
    : QWidget(parent), m_tap(tap), m_timer(new QTimer(this))
{
    setAttribute(Qt::WA_TranslucentBackground);
    setAttribute(Qt::WA_NoSystemBackground);
    m_timer->setInterval(kFrameMs);
    connect(m_timer, &QTimer::timeout, this, &VisualizerWidget::tick);
    m_clock.start();
}

VisualizerWidget::~VisualizerWidget()
{
    if (m_tap && m_active)
    {
        m_tap->release();
    }
}

void VisualizerWidget::setStyle(VisualizerStyle style)
{
    m_style = style;
    m_particles.clear();
    m_particleTime = -1.0f;
    update();
}

void VisualizerWidget::setColor(const QColor& color)
{
    m_color = color.isValid() ? color : QColor(240, 195, 164);
    update();
}

void VisualizerWidget::setCover(const QPixmap& cover)
{
    m_cover = cover;
    update();
}

void VisualizerWidget::setPlaying(bool playing)
{
    m_playing = playing;
}

void VisualizerWidget::setActive(bool active)
{
    if (m_active == active)
    {
        return;
    }
    m_active = active;
    if (m_tap)
    {
        active ? m_tap->acquire() : m_tap->release();
    }
    if (active)
    {
        m_lastTickMs = m_clock.elapsed();
        m_timer->start();
    }
    else
    {
        m_timer->stop();
    }
    if (m_movie)
    {
        m_movie->setPaused(!active);
    }
    if (m_player)
    {
        active ? m_player->play() : m_player->pause();
    }
}

void VisualizerWidget::setMedia(const VisualizerMediaEntry* entry)
{
    m_mediaRound = entry ? entry->round : true;
    m_mediaSpin = entry ? entry->spin : true;
    const QString path = entry ? entry->path : QString();
    if (path == m_mediaPath)
    {
        update();
        return; // the same file for the next song: keep it running
    }
    stopMedia();
    m_mediaPath = path;
    m_mediaFailed = false;
    if (path.isEmpty())
    {
        update();
        return;
    }
    if (!QFileInfo::exists(path))
    {
        onMediaFailed();
        return;
    }

    if (entry->isVideo())
    {
        m_player = new QMediaPlayer(this); // no audio output: it plays muted
        m_sink = new QVideoSink(this);
        m_player->setVideoOutput(m_sink);
        m_player->setLoops(QMediaPlayer::Infinite);
        connect(m_sink, &QVideoSink::videoFrameChanged, this, [this](const QVideoFrame& frame) {
            m_mediaFrame = frame.toImage();
            update();
        });
        connect(m_player, &QMediaPlayer::errorOccurred, this, [this]() { onMediaFailed(); });
        m_player->setSource(QUrl::fromLocalFile(path));
        if (m_active)
        {
            m_player->play();
        }
    }
    else
    {
        m_movie = new QMovie(path, QByteArray(), this);
        if (m_movie->isValid() && m_movie->frameCount() != 1)
        {
            connect(m_movie, &QMovie::frameChanged, this, [this]() {
                m_mediaFrame = m_movie->currentImage();
                update();
            });
            m_movie->start();
            m_movie->setPaused(!m_active);
        }
        else
        {
            delete m_movie; // a still picture
            m_movie = nullptr;
            m_mediaFrame = QImage(path);
            if (m_mediaFrame.isNull())
            {
                onMediaFailed();
                return;
            }
        }
    }
    update();
}

void VisualizerWidget::stopMedia()
{
    if (m_player)
    {
        m_player->stop();
        m_player->deleteLater();
        m_player = nullptr;
    }
    if (m_sink)
    {
        m_sink->deleteLater();
        m_sink = nullptr;
    }
    if (m_movie)
    {
        m_movie->stop();
        m_movie->deleteLater();
        m_movie = nullptr;
    }
    m_mediaFrame = QImage();
}

void VisualizerWidget::onMediaFailed()
{
    ErrorReporter::logOnly("Visualizer GIF/video couldn't be shown: " + m_mediaPath);
    stopMedia();
    m_mediaFailed = true; // the style shows instead
    update();
}

void VisualizerWidget::tick()
{
    // Without a tap (a GIF / video preview), frames still run: the media spins.
    advance(m_tap ? m_tap->levels() : std::array<float, VisualizerTap::kBands>{});
}

void VisualizerWidget::showFrame(const std::array<float, VisualizerTap::kBands>& levels)
{
    advance(levels);
}

void VisualizerWidget::advance(const std::array<float, VisualizerTap::kBands>& levels)
{
    const qint64 now = m_clock.elapsed();
    const qint64 dt = now - m_lastTickMs;
    m_lastTickMs = now;
    m_timeSeconds = now / 1000.0f;
    if (m_playing)
    {
        m_spinDegrees = std::fmod(m_spinDegrees + dt * kDiscDegreesPerMs, 360.0f);
    }
    m_levels = levels;
    update();
}

float VisualizerWidget::at(float x) const
{
    const float position = std::clamp(x, 0.0f, 1.0f) * (m_levels.size() - 1);
    const int i = std::min(static_cast<int>(position), static_cast<int>(m_levels.size()) - 2);
    const float f = position - i;
    return m_levels[i] * (1.0f - f) + m_levels[i + 1] * f;
}

float VisualizerWidget::bass() const
{
    float sum = 0.0f;
    for (int i = 0; i < 5; ++i)
    {
        sum += m_levels[i];
    }
    return sum / 5.0f;
}

float VisualizerWidget::energy() const
{
    float sum = 0.0f;
    for (float level : m_levels)
    {
        sum += level;
    }
    return sum / m_levels.size();
}

void VisualizerWidget::paintEvent(QPaintEvent*)
{
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    if (!m_mediaPath.isEmpty() && !m_mediaFailed && !m_mediaFrame.isNull())
    {
        drawMedia(p);
    }
    else
    {
        drawStyle(p);
    }
}

void VisualizerWidget::drawMedia(QPainter& p)
{
    const int side = std::min(width(), height());
    const QRectF square((width() - side) / 2.0, (height() - side) / 2.0, side, side);
    const qreal dpr = devicePixelRatioF();
    QImage frame = FillCrop(m_mediaFrame, QSize(side, side) * dpr);
    frame.setDevicePixelRatio(dpr);

    p.save();
    p.translate(square.center());
    if (m_mediaSpin)
    {
        p.rotate(m_spinDegrees);
    }
    p.translate(-square.center());
    QPainterPath clip;
    if (m_mediaRound)
    {
        clip.addEllipse(square);
    }
    else
    {
        clip.addRoundedRect(square, side * 0.1, side * 0.1);
    }
    p.setClipPath(clip);
    p.drawImage(square, frame);
    p.restore();
}

void VisualizerWidget::drawStyle(QPainter& p)
{
    switch (m_style)
    {
        case VisualizerStyle::ClassicBars: classicBars(p); break;
        case VisualizerStyle::Spectrum:    spectrum(p); break;
        case VisualizerStyle::NeonBars:    neonBars(p); break;
        case VisualizerStyle::LineWave:    lineWave(p); break;
        case VisualizerStyle::RetroWave:   retroWave(p); break;
        case VisualizerStyle::FlowingWave: flowingWave(p); break;
        case VisualizerStyle::Radial:      radial(p); break;
        case VisualizerStyle::Particles:   particles(p); break;
        case VisualizerStyle::Geometric:   geometric(p); break;
        case VisualizerStyle::Disc:        disc(p); break;
    }
}

// --- Bars ----------------------------------------------------------------------

void VisualizerWidget::classicBars(QPainter& p)
{
    const qreal w = width(), h = height();
    const int count = 18;
    const qreal slot = w / count;
    const qreal barWidth = slot * 0.7;
    QLinearGradient gradient(0, 0, 0, h);
    gradient.setColorAt(0, Lighter(m_color, 0.25f));
    gradient.setColorAt(1, WithAlpha(m_color, 0.7f));
    p.setPen(Qt::NoPen);
    for (int i = 0; i < count; ++i)
    {
        const qreal height = std::max(h * 0.92 * at(i / (count - 1.0f)), h * 0.03);
        const qreal left = i * slot + (slot - barWidth) / 2;
        // A soft halo behind each bar, then the bar.
        p.setBrush(WithAlpha(m_color, 0.18f));
        p.drawRect(QRectF(left - barWidth * 0.15, h - height - barWidth * 0.15, barWidth * 1.3, height + barWidth * 0.15));
        p.setBrush(gradient);
        p.drawRect(QRectF(left, h - height, barWidth, height));
    }
}

// Many thin columns of LED-like segments, brightest at the bottom.
void VisualizerWidget::spectrum(QPainter& p)
{
    const qreal w = width(), h = height();
    const int columns = 40;
    const int rows = 32;
    const qreal slot = w / columns;
    const qreal segment = h / rows;
    p.setPen(Qt::NoPen);
    for (int c = 0; c < columns; ++c)
    {
        const int lit = std::max(1, static_cast<int>(at(c / (columns - 1.0f)) * rows));
        for (int r = 0; r < lit; ++r)
        {
            p.setBrush(WithAlpha(m_color, 0.95f - 0.6f * r / rows));
            p.drawRect(QRectF(c * slot + slot * 0.12, h - (r + 1) * segment + segment * 0.15, slot * 0.76, segment * 0.7));
        }
    }
}

// A few wide, rounded, glowing bars.
void VisualizerWidget::neonBars(QPainter& p)
{
    const qreal w = width(), h = height();
    const int count = 7;
    const qreal slot = w / count;
    const qreal barWidth = slot * 0.55;
    const QColor core = Lighter(m_color, 0.45f);
    const std::pair<qreal, float> glows[] = {{0.6, 0.08f}, {0.35, 0.16f}, {0.15, 0.3f}};
    p.setPen(Qt::NoPen);
    for (int i = 0; i < count; ++i)
    {
        float level = 0.0f;
        for (int k = 0; k < 3; ++k)
        {
            level += at((i * 3 + k) / (count * 3 - 1.0f));
        }
        level /= 3.0f;
        const qreal height = std::max(h * 0.85 * level, barWidth);
        const qreal left = i * slot + (slot - barWidth) / 2;
        const qreal top = h * 0.95 - height;
        for (const auto& [grow, alpha] : glows)
        {
            const qreal g = barWidth * grow;
            const QRectF rect(left - g, top - g, barWidth + 2 * g, height + 2 * g);
            p.setBrush(WithAlpha(m_color, alpha));
            p.drawRoundedRect(rect, rect.width() / 2, rect.width() / 2);
        }
        p.setBrush(core);
        p.drawRoundedRect(QRectF(left, top, barWidth, height), barWidth / 2, barWidth / 2);
    }
}

// --- Waves ----------------------------------------------------------------------

// A thin, jittery line following the whole spectrum.
void VisualizerWidget::lineWave(QPainter& p)
{
    const qreal w = width(), h = height();
    const float t = m_timeSeconds;
    const qreal mid = h / 2;
    const QPainterPath path = WavePath(120, w, [&](float x) {
        const qreal amplitude = h * (0.06 + 0.38 * at(x));
        return mid + amplitude * std::sin(x * 2 * kPi * 7 - t * 4.0f) * (0.7 + 0.3 * std::sin(x * 23.0f + t * 2.0f));
    });
    const QColor line = Lighter(m_color, 0.7f);
    const qreal minSide = std::min(w, h);
    p.setBrush(Qt::NoBrush);
    p.setPen(RoundPen(WithAlpha(line, 0.18f), minSide * 0.03));
    p.drawPath(path);
    p.setPen(RoundPen(line, minSide * 0.008));
    p.drawPath(path);
}

// A slow, smooth, thick wave driven by the bass, like an old oscilloscope.
void VisualizerWidget::retroWave(QPainter& p)
{
    const qreal w = width(), h = height();
    const float t = m_timeSeconds;
    const qreal mid = h / 2;
    const qreal amplitude = h * (0.08 + 0.32 * bass());
    const QPainterPath path = WavePath(120, w, [&](float x) {
        return mid + amplitude * std::sin(x * 2 * kPi * 2.5f - t * 1.6f) * (0.8 + 0.2 * std::sin(x * 6.0f + t));
    });
    const QColor warm = Lighter(m_color, 0.3f);
    const qreal minSide = std::min(w, h);
    p.setBrush(Qt::NoBrush);
    p.setPen(RoundPen(WithAlpha(warm, 0.15f), minSide * 0.07));
    p.drawPath(path);
    p.setPen(RoundPen(warm, minSide * 0.022));
    p.drawPath(path);
}

// Layers of soft, flowing colour that swell with the music.
void VisualizerWidget::flowingWave(QPainter& p)
{
    const qreal w = width(), h = height();
    const float t = m_timeSeconds;
    const QColor second = ShiftHue(m_color, -50.0f);
    QLinearGradient background(0, 0, w, h);
    background.setColorAt(0, WithAlpha(m_color, 0.55f));
    background.setColorAt(1, WithAlpha(second, 0.55f));
    p.setPen(Qt::NoPen);
    p.setBrush(background);
    p.drawRoundedRect(rect(), 16, 16);
    const float e = energy();
    for (int layer = 0; layer < 5; ++layer)
    {
        const qreal base = h * (0.2 + layer * 0.17);
        const qreal amplitude = h * (0.05 + 0.12 * e + 0.02 * layer);
        const float speed = 0.4f + layer * 0.15f;
        QPainterPath path = WavePath(60, w, [&](float x) {
            return base + amplitude * std::sin(x * 2 * kPi * (0.8f + layer * 0.25f) + t * speed + layer);
        });
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.closeSubpath();
        const QColor shade = Lerp(m_color, second, layer / 4.0f);
        QLinearGradient fill(0, base - amplitude, 0, h);
        fill.setColorAt(0, WithAlpha(Lighter(shade, 0.25f), 0.45f));
        fill.setColorAt(1, WithAlpha(shade, 0.15f));
        p.setBrush(fill);
        p.drawPath(path);
    }
}

// --- Circular, particles, geometric --------------------------------------------------

// Concentric rings that breathe, ringed by a dial of ticks following the spectrum.
void VisualizerWidget::radial(QPainter& p)
{
    const QPointF c = rect().center();
    const qreal r = std::min(width(), height()) / 2.0;
    const float t = m_timeSeconds;
    const float b = bass();
    p.setBrush(Qt::NoBrush);
    for (int ring = 0; ring < 4; ++ring)
    {
        const qreal radius = r * (0.22 + ring * 0.13) * (1 + 0.08 * b);
        p.setPen(QPen(WithAlpha(m_color, 0.12f), r * 0.06));
        p.drawEllipse(c, radius, radius);
        p.setPen(QPen(WithAlpha(Lighter(m_color, 0.3f), 0.85f - ring * 0.15f), r * 0.012));
        p.drawEllipse(c, radius, radius);
    }
    // Rotating dashed arcs.
    p.save();
    p.translate(c);
    p.rotate(t * 20.0f);
    p.setPen(QPen(WithAlpha(m_color, 0.6f), r * 0.02));
    const QRectF arcRect(-r * 0.75, -r * 0.75, r * 1.5, r * 1.5);
    for (int arc = 0; arc < 6; ++arc)
    {
        p.drawArc(arcRect, -arc * 60 * 16, -35 * 16); // clockwise, like Compose
    }
    p.restore();
    const int ticks = 72;
    for (int i = 0; i < ticks; ++i)
    {
        const double angle = i * 2.0 * kPi / ticks - kPi / 2.0;
        const float level = at(std::abs(i - ticks / 2.0f) / (ticks / 2.0f));
        const qreal inner = r * 0.8;
        const qreal outer = inner + r * (0.03 + 0.17 * level);
        p.setPen(RoundPen(WithAlpha(Lighter(m_color, 0.2f), 0.5f + 0.5f * level), r * 0.018));
        p.drawLine(QPointF(c.x() + inner * std::cos(angle), c.y() + inner * std::sin(angle)),
                   QPointF(c.x() + outer * std::cos(angle), c.y() + outer * std::sin(angle)));
    }
}

// Sparks bursting from the middle, more and faster on loud beats.
void VisualizerWidget::particles(QPainter& p)
{
    const qreal w = width(), h = height();
    const QPointF c = rect().center();
    const qreal minSide = std::min(w, h);
    // Moved by the time since the last drawing, like the Android app (the
    // Settings previews are driven from outside, without this widget's timer).
    const float dt = m_particleTime < 0.0f ? 0.0f : std::clamp(m_timeSeconds - m_particleTime, 0.0f, 0.1f);
    m_particleTime = m_timeSeconds;
    const float b = bass();
    const float e = energy();
    std::uniform_real_distribution<float> unit(0.0f, 1.0f);
    const int spawn = 2 + static_cast<int>(b * 14);
    for (int i = 0; i < spawn && m_particles.size() < kMaxParticles; ++i)
    {
        const float angle = unit(m_random) * 2 * kPi;
        const float speed = minSide * (0.15f + unit(m_random) * 0.6f) * (0.6f + e);
        m_particles.push_back({static_cast<float>(c.x()), static_cast<float>(c.y()), std::cos(angle) * speed,
                               std::sin(angle) * speed, 1.0f,
                               static_cast<float>(minSide * (0.004f + unit(m_random) * 0.01f))});
    }
    const QColor spark = Lighter(m_color, 0.3f);
    for (Particle& particle : m_particles)
    {
        particle.x += particle.vx * dt;
        particle.y += particle.vy * dt;
        particle.life -= dt * 0.7f;
    }
    m_particles.erase(std::remove_if(m_particles.begin(), m_particles.end(), [w, h](const Particle& pt) {
        return pt.life <= 0.0f || pt.x < 0 || pt.y < 0 || pt.x > w || pt.y > h;
    }), m_particles.end());
    for (const Particle& pt : m_particles)
    {
        // A short streak behind fast sparks.
        p.setPen(QPen(WithAlpha(spark, pt.life * 0.35f), pt.size));
        p.drawLine(QPointF(pt.x - pt.vx * 0.05f, pt.y - pt.vy * 0.05f), QPointF(pt.x, pt.y));
        p.setPen(Qt::NoPen);
        p.setBrush(WithAlpha(spark, pt.life));
        p.drawEllipse(QPointF(pt.x, pt.y), pt.size, pt.size);
    }
    p.setPen(Qt::NoPen);
    p.setBrush(WithAlpha(m_color, 0.25f + 0.4f * b));
    const qreal core = minSide * (0.03 + 0.05 * b);
    p.drawEllipse(c, core, core);
}

// A band of sharp triangles whose points jump with the spectrum.
void VisualizerWidget::geometric(QPainter& p)
{
    const qreal w = width(), h = height();
    const float t = m_timeSeconds;
    const int count = 7;
    const qreal step = w / count;
    const qreal mid = h / 2;
    const QColor edge = Lighter(m_color, 0.35f);
    for (int i = 0; i < count; ++i)
    {
        const float level = at(i / (count - 1.0f));
        const int up = i % 2 == 0 ? -1 : 1;
        const qreal height = h * (0.12 + 0.33 * level) * up;
        const qreal x0 = i * step + step * 0.08 * std::sin(t + i);
        QPainterPath path;
        path.moveTo(x0, mid - height * 0.3);
        path.lineTo(x0 + step * 1.15, mid + height * 0.25);
        path.lineTo(x0 + step * 0.55, mid + height);
        path.closeSubpath();
        QLinearGradient fill(x0, mid, x0 + step, mid + height);
        fill.setColorAt(0, WithAlpha(m_color, 0.75f));
        fill.setColorAt(1, WithAlpha(m_color, 0.2f));
        p.setPen(QPen(edge, std::min(w, h) * 0.006));
        p.setBrush(fill);
        p.drawPath(path);
    }
}

// --- Record --------------------------------------------------------------------------

// A record turning while music plays, the song's cover on its label.
void VisualizerWidget::disc(QPainter& p)
{
    const QPointF c = rect().center();
    const qreal r = std::min(width(), height()) * 0.47;
    // A glow on the beat, behind the record.
    p.setPen(Qt::NoPen);
    p.setBrush(WithAlpha(m_color, 0.12f + 0.25f * bass()));
    p.drawEllipse(c, r * 1.04, r * 1.04);
    p.setBrush(QColor(0x11, 0x11, 0x14));
    p.drawEllipse(c, r, r);
    p.setBrush(Qt::NoBrush);
    for (int groove = 0; groove < 18; ++groove)
    {
        p.setPen(QPen(QColor(255, 255, 255, groove % 3 == 0 ? 18 : 9), r * 0.006));
        const qreal radius = r * (0.42 + groove * 0.031);
        p.drawEllipse(c, radius, radius);
    }

    p.save();
    p.translate(c);
    p.rotate(m_spinDegrees);
    // The light catching the grooves turns with the record.
    p.setPen(Qt::NoPen);
    for (int side = 0; side < 2; ++side)
    {
        QConicalGradient sheen(QPointF(0, 0), -side * 180.0);
        sheen.setColorAt(0.0, QColor(255, 255, 255, side == 0 ? 30 : 20));
        sheen.setColorAt(0.06, QColor(255, 255, 255, 0));
        sheen.setColorAt(0.94, QColor(255, 255, 255, 0));
        sheen.setColorAt(1.0, QColor(255, 255, 255, side == 0 ? 30 : 20));
        p.setBrush(sheen);
        p.drawEllipse(QPointF(0, 0), r, r);
    }
    const qreal label = r * 0.36;
    QPainterPath circle;
    circle.addEllipse(QPointF(0, 0), label, label);
    p.setClipPath(circle);
    if (!m_cover.isNull())
    {
        p.drawPixmap(QRectF(-label, -label, label * 2, label * 2), m_cover, QRectF(m_cover.rect()));
    }
    else
    {
        p.setBrush(m_color);
        p.drawEllipse(QPointF(0, 0), label, label);
    }
    p.restore();
    p.setPen(Qt::NoPen);
    p.setBrush(QColor(0, 0, 0, 153));
    p.drawEllipse(c, r * 0.03, r * 0.03);
}

} // namespace audioforge
