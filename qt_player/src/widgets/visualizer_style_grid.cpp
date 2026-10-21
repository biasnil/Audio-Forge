#include "audioforge/widgets/visualizer_style_grid.hpp"
#include "audioforge/widgets/visualizer_widget.hpp"

#include <QEvent>
#include <QFrame>
#include <QLabel>
#include <QMouseEvent>
#include <QTimer>

#include <algorithm>
#include <cmath>

namespace audioforge {

namespace {

constexpr int kFrameMs = 30;
constexpr int kGap = 10;
constexpr int kMinTile = 96;
constexpr int kMaxColumns = 5;
constexpr int kInset = 8; // between the box's edge and the drawing

// A slow made-up spectrum (louder lows), for when nothing plays -- the
// Android app's idleLevels().
std::array<float, VisualizerTap::kBands> IdleLevels(qint64 timeMs)
{
    const double t = timeMs / 1000.0;
    std::array<float, VisualizerTap::kBands> levels{};
    for (int band = 0; band < VisualizerTap::kBands; ++band)
    {
        const double x = static_cast<double>(band) / VisualizerTap::kBands;
        const double level = 0.25 + 0.35 * (1.0 - x) + 0.2 * std::sin(t * 2.1 + band * 0.7) + 0.1 * std::sin(t * 3.7 + band * 1.9);
        levels[band] = static_cast<float>(std::clamp(level, 0.05, 1.0));
    }
    return levels;
}

} // namespace

VisualizerStyleGrid::VisualizerStyleGrid(VisualizerTap* tap, QWidget* parent)
    : QWidget(parent), m_tap(tap), m_timer(new QTimer(this))
{
    m_timer->setInterval(kFrameMs);
    connect(m_timer, &QTimer::timeout, this, &VisualizerStyleGrid::tick);
    m_clock.start();

    for (int i = 0; i < kVisualizerStyleCount; ++i)
    {
        Tile tile;
        tile.frame = new QFrame(this);
        tile.frame->setCursor(Qt::PointingHandCursor);
        tile.frame->installEventFilter(this);
        tile.preview = new VisualizerWidget(nullptr, tile.frame); // driven by tick()
        tile.preview->setStyle(static_cast<VisualizerStyle>(i));
        tile.label = new QLabel(VisualizerStyleName(static_cast<VisualizerStyle>(i)), this);
        tile.label->setAlignment(Qt::AlignHCenter | Qt::AlignTop);
        tile.label->setWordWrap(true);
        tile.label->setCursor(Qt::PointingHandCursor);
        tile.label->installEventFilter(this);
        m_tiles.push_back(tile);
    }
    updateTileLooks();
}

VisualizerStyleGrid::~VisualizerStyleGrid()
{
    setRunning(false);
}

void VisualizerStyleGrid::setSelected(int style)
{
    m_selected = style;
    updateTileLooks();
}

void VisualizerStyleGrid::setColor(const QColor& color)
{
    for (Tile& tile : m_tiles)
    {
        tile.preview->setColor(color);
    }
}

void VisualizerStyleGrid::setCover(const QPixmap& cover)
{
    for (Tile& tile : m_tiles)
    {
        tile.preview->setCover(cover);
    }
}

void VisualizerStyleGrid::setPlaying(bool playing)
{
    for (Tile& tile : m_tiles)
    {
        tile.preview->setPlaying(playing);
    }
}

void VisualizerStyleGrid::setThemeColors(const QColor& accent, const QColor& border, const QColor& text)
{
    m_accent = accent;
    m_border = border;
    m_text = text;
    updateTileLooks();
}

void VisualizerStyleGrid::updateTileLooks()
{
    for (int i = 0; i < static_cast<int>(m_tiles.size()); ++i)
    {
        const bool chosen = i == m_selected;
        // Always dark, like the Android tiles: the styles are drawn for a dark background.
        m_tiles[i].frame->setStyleSheet(QString("QFrame { background: #0b0b0f; border: %1px solid %2; border-radius: 12px; }")
                                            .arg(chosen ? 3 : 1)
                                            .arg((chosen ? m_accent : m_border).name()));
        m_tiles[i].label->setStyleSheet(QString("QLabel { color: %1; font-size: 12px; %2 }")
                                            .arg((chosen ? m_accent : m_text).name(), chosen ? "font-weight: 600;" : ""));
    }
}

bool VisualizerStyleGrid::eventFilter(QObject* watched, QEvent* event)
{
    if (event->type() == QEvent::MouseButtonPress && static_cast<QMouseEvent*>(event)->button() == Qt::LeftButton)
    {
        for (int i = 0; i < static_cast<int>(m_tiles.size()); ++i)
        {
            if (watched == m_tiles[i].frame || watched == m_tiles[i].label)
            {
                setSelected(i);
                emit styleChosen(i);
                return true;
            }
        }
    }
    return QWidget::eventFilter(watched, event);
}

// --- Layout ------------------------------------------------------------------

namespace {

int LabelHeight(const QWidget* widget)
{
    return widget->fontMetrics().lineSpacing() * 2 + 6; // room for a two-line name
}

int Columns(int width)
{
    return std::clamp((width + kGap) / (kMinTile + kGap), 2, kMaxColumns);
}

int HeightFor(int width, int labelHeight)
{
    const int columns = Columns(width);
    const int tile = (width - (columns - 1) * kGap) / columns;
    const int rows = (kVisualizerStyleCount + columns - 1) / columns;
    return rows * (tile + labelHeight) + (rows - 1) * kGap;
}

} // namespace

QSize VisualizerStyleGrid::sizeHint() const
{
    const int width = kMaxColumns * (kMinTile + 30) + (kMaxColumns - 1) * kGap;
    return QSize(width, HeightFor(width, LabelHeight(this)));
}

QSize VisualizerStyleGrid::minimumSizeHint() const
{
    // Narrow: two to a row. The real height follows the width (resizeEvent).
    return QSize(2 * kMinTile + kGap, 0);
}

void VisualizerStyleGrid::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    layoutTiles();
}

void VisualizerStyleGrid::layoutTiles()
{
    const int labelHeight = LabelHeight(this);
    const int columns = Columns(width());
    const int tile = std::max(1, (width() - (columns - 1) * kGap) / columns);
    for (int i = 0; i < static_cast<int>(m_tiles.size()); ++i)
    {
        const int x = (i % columns) * (tile + kGap);
        const int y = (i / columns) * (tile + labelHeight + kGap);
        m_tiles[i].frame->setGeometry(x, y, tile, tile);
        m_tiles[i].preview->setGeometry(kInset, kInset, tile - 2 * kInset, tile - 2 * kInset);
        m_tiles[i].label->setGeometry(x, y + tile + 4, tile, labelHeight - 4);
    }
    const int height = HeightFor(width(), labelHeight);
    if (this->height() != height)
    {
        setFixedHeight(height); // only the height changes, so this settles at once
    }
}

// --- Frames ------------------------------------------------------------------

void VisualizerStyleGrid::showEvent(QShowEvent* event)
{
    QWidget::showEvent(event);
    setRunning(true);
}

void VisualizerStyleGrid::hideEvent(QHideEvent* event)
{
    QWidget::hideEvent(event);
    setRunning(false);
}

void VisualizerStyleGrid::setRunning(bool running)
{
    if (m_running == running)
    {
        return;
    }
    m_running = running;
    if (m_tap)
    {
        running ? m_tap->acquire() : m_tap->release();
    }
    running ? m_timer->start() : m_timer->stop();
}

void VisualizerStyleGrid::tick()
{
    // Scrolled out of view or minimized: nothing to draw.
    if (window()->isMinimized() || visibleRegion().isEmpty())
    {
        return;
    }
    std::array<float, VisualizerTap::kBands> levels{};
    if (m_tap)
    {
        levels = m_tap->levels();
    }
    if (std::all_of(levels.begin(), levels.end(), [](float level) { return level < 0.02f; }))
    {
        levels = IdleLevels(m_clock.elapsed());
    }
    for (Tile& tile : m_tiles)
    {
        tile.preview->showFrame(levels);
    }
}

} // namespace audioforge
