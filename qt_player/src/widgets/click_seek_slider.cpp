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
            // Map the click onto the groove the way the style itself does:
            // the handle's center travels from grooveStart + handleLength/2
            // to grooveEnd - handleLength/2, so that's the span the value
            // range is spread over -- not the widget's full width/height.
            const QRect grooveRect = style()->subControlRect(QStyle::CC_Slider, &opt, QStyle::SC_SliderGroove, this);
            const bool horizontal = orientation() == Qt::Horizontal;
            const int handleLength = horizontal ? handleRect.width() : handleRect.height();
            const int grooveStart = horizontal ? grooveRect.x() : grooveRect.y();
            const int grooveLength = horizontal ? grooveRect.width() : grooveRect.height();
            const int clickPos = (horizontal ? event->pos().x() : event->pos().y()) - grooveStart - handleLength / 2;
            const int span = qMax(1, grooveLength - handleLength);
            const int value = QStyle::sliderValueFromPosition(minimum(), maximum(), clickPos, span, opt.upsideDown);
            setValue(value); // emits valueChanged immediately, same as a completed drag would

            // The handle is now under the cursor, so the base class's own
            // press handling below (which builds a fresh style option from
            // the new value) treats this as grabbing the handle -- a
            // click-then-drag continues smoothly from here.
        }
    }
    QSlider::mousePressEvent(event);
}

} // namespace audioforge