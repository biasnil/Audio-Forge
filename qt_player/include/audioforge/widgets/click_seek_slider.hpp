#pragma once

#include <QSlider>

namespace audioforge {

// A QSlider whose groove behaves like a normal seek/volume bar: clicking
// anywhere on it jumps the handle straight to that position. Plain QSlider
// only does this when you drag the handle itself -- clicking the groove
// instead nudges the value by one page step, which is why the seek bar
// looked unresponsive to clicks (seekToSliderValue() only runs on release,
// and a page-step nudge rarely lands near the click) and the volume slider
// looked "weird" (valueChanged applies live, so you'd see a small stepped
// jump instead of landing on the clicked volume).
//
// Clicking the handle itself is left untouched -- that still behaves as a
// normal drag start, same as before.
class ClickSeekSlider : public QSlider
{
    Q_OBJECT

public:
    explicit ClickSeekSlider(Qt::Orientation orientation, QWidget* parent = nullptr);

protected:
    void mousePressEvent(QMouseEvent* event) override;
};

} // namespace audioforge