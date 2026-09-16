#include "audioforge/widgets/click_seek_slider.hpp"

#include <QMouseEvent>
#include <QStyle>
#include <QStyleOptionSlider>

namespace audioforge {

ClickSeekSlider::ClickSeekSlider(Qt::Orientation orientation, QWidget* parent)
    : QSlider(orientation, parent)
{
}

void ClickSeekSlider::mousePressEvent(QMouseEvent* event)
{
    if (event->button() == Qt::LeftButton)
    {
        QStyleOptionSlider opt;
        initStyleOption(&opt);
        const QRect handleRect = style()->subControlRect(QStyle::CC_Slider, &opt, QStyle::SC_SliderHandle, this);

        // Only override the default when the click landed on the groove --
        // a click on the handle itself should still behave like a normal
        // drag start, unchanged.
        if (!handleRect.contains(event->pos()))
        {
            const bool horizontal = orientation() == Qt::Horizontal;
            const int clickPos = horizontal ? event->pos().x() : event->pos().y();
            const int span = horizontal ? width() : height();
            const int value = QStyle::sliderValueFromPosition(minimum(), maximum(), clickPos, span);
            setValue(value); // emits valueChanged immediately, same as a completed drag would

            // Re-sync the style option now that the value moved, so the base
            // class's own press handling (called below) computes the handle
            // grab offset from where the handle now is, not where it was --
            // that's what lets a click-then-drag continue smoothly from here.
            event->accept();
        }
    }
    QSlider::mousePressEvent(event);
}

} // namespace audioforge
