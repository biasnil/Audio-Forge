#include "audioforge/widgets/click_seek_slider.hpp"

#include <QMouseEvent>
#include <QPainter>
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

void ClickSeekSlider::setRangeMarkers(int a, int b, const QColor& color)
{
    if (a == m_markerA && b == m_markerB && color == m_markerColor)
    {
        return;
    }
    m_markerA = a;
    m_markerB = b;
    m_markerColor = color;
    update();
}

void ClickSeekSlider::paintEvent(QPaintEvent* event)
{
    QSlider::paintEvent(event);
    if (m_markerA < 0 || orientation() != Qt::Horizontal || maximum() <= minimum())
    {
        return;
    }
    QStyleOptionSlider opt;
    initStyleOption(&opt);
    const QRect groove = style()->subControlRect(QStyle::CC_Slider, &opt, QStyle::SC_SliderGroove, this);
    const QRect handle = style()->subControlRect(QStyle::CC_Slider, &opt, QStyle::SC_SliderHandle, this);
    // Same mapping the style uses for the handle's center.
    const int span = qMax(1, groove.width() - handle.width());
    auto xOf = [&](int value) {
        return groove.x() + handle.width() / 2
            + QStyle::sliderPositionFromValue(minimum(), maximum(), value, span, opt.upsideDown);
    };
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    p.setPen(Qt::NoPen);
    const int centerY = groove.center().y();
    const int a = xOf(m_markerA);
    if (m_markerB >= 0)
    {
        const int b = xOf(m_markerB);
        QColor span = m_markerColor;
        span.setAlphaF(0.45);
        p.setBrush(span);
        p.drawRoundedRect(QRectF(a, centerY - 2, b - a, 4), 2, 2);
        p.setBrush(m_markerColor);
        p.drawRect(QRectF(b - 1, centerY - 6, 2, 12));
    }
    p.setBrush(m_markerColor);
    p.drawRect(QRectF(a - 1, centerY - 6, 2, 12));
}

} // namespace audioforge
