#include "audioforge/widgets/toggle_switch.hpp"

#include <QPainter>

namespace audioforge {

ToggleSwitch::ToggleSwitch(QWidget* parent) : QAbstractButton(parent)
{
    setCheckable(true);
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::NoFocus); // Space stays the play/pause shortcut
    setFixedSize(sizeHint());
}

void ToggleSwitch::paintEvent(QPaintEvent*)
{
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);

    const QRectF track = QRectF(rect()).adjusted(1, 1, -1, -1);
    const qreal radius = track.height() / 2.0;

    QColor trackColor = isChecked() ? palette().color(QPalette::Highlight) : palette().color(QPalette::AlternateBase);
    QColor knobColor = isChecked() ? palette().color(QPalette::HighlightedText) : palette().color(QPalette::WindowText);
    if (!isChecked())
    {
        knobColor.setAlpha(170);
    }
    if (!isEnabled())
    {
        trackColor.setAlpha(90);
        knobColor.setAlpha(90);
    }

    p.setPen(Qt::NoPen);
    p.setBrush(trackColor);
    p.drawRoundedRect(track, radius, radius);

    const qreal knob = track.height() - 6.0;
    const qreal x = isChecked() ? track.right() - 3.0 - knob : track.left() + 3.0;
    p.setBrush(knobColor);
    p.drawEllipse(QRectF(x, track.top() + 3.0, knob, knob));
}

} // namespace audioforge
