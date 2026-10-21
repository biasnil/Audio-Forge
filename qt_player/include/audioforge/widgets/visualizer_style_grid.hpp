#pragma once

#include <QColor>
#include <QElapsedTimer>
#include <QPixmap>
#include <QWidget>

#include <vector>

#include "audioforge/visualizer_tap.hpp"

class QLabel;
class QTimer;

namespace audioforge {

class VisualizerWidget;

// Settings > Visualizer: every style as a small live preview, moving with
// the song that's playing (or gently by itself when nothing plays), the
// chosen one outlined; click one to choose it. Like the Android app's
// StyleGrid. As many tiles to a row as fit, up to five.
class VisualizerStyleGrid : public QWidget
{
    Q_OBJECT

public:
    explicit VisualizerStyleGrid(VisualizerTap* tap, QWidget* parent = nullptr);
    ~VisualizerStyleGrid() override;

    void setSelected(int style);
    void setColor(const QColor& color);  // the song's cover colour (invalid = the accent)
    void setCover(const QPixmap& cover); // the record label for "Spinning record"
    void setPlaying(bool playing);
    void setThemeColors(const QColor& accent, const QColor& border, const QColor& text);

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;
    bool eventFilter(QObject* watched, QEvent* event) override;

signals:
    void styleChosen(int style);

protected:
    void resizeEvent(QResizeEvent* event) override;
    void showEvent(QShowEvent* event) override;
    void hideEvent(QHideEvent* event) override;

private:
    struct Tile
    {
        QWidget* frame;            // the rounded box, outlined
        VisualizerWidget* preview; // inside it
        QLabel* label;             // the style's name, below
    };

    void setRunning(bool running);
    void tick();
    void layoutTiles();
    void updateTileLooks();

    VisualizerTap* m_tap;
    QTimer* m_timer;
    QElapsedTimer m_clock;
    bool m_running = false;
    std::vector<Tile> m_tiles;
    int m_selected = 0;
    QColor m_accent = QColor(240, 195, 164);
    QColor m_border = QColor(44, 37, 38);
    QColor m_text = QColor(244, 235, 229);
};

} // namespace audioforge
