#include "audioforge/widgets/clickable_widget.hpp"

#include <QMouseEvent>

namespace audioforge {

ClickableWidget::ClickableWidget(QWidget* parent) : QWidget(parent)
{
    setCursor(Qt::PointingHandCursor);
}

void ClickableWidget::mousePressEvent(QMouseEvent* event)
{
    if (event->button() == Qt::LeftButton)
    {
        emit clicked();
    }
    QWidget::mousePressEvent(event);
}

} // namespace audioforge
