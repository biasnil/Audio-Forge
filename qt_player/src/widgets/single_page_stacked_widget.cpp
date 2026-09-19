#include "audioforge/widgets/single_page_stacked_widget.hpp"

namespace audioforge {

SinglePageStackedWidget::SinglePageStackedWidget(QWidget* parent) : QStackedWidget(parent)
{
    // The values sizeHint()/minimumSizeHint() below return change whenever
    // the visible page changes -- tell the layout system to re-check them
    // instead of trusting whatever it cached from before the switch.
    connect(this, &QStackedWidget::currentChanged, this, [this](int) {
        updateGeometry();
    });
}

QSize SinglePageStackedWidget::sizeHint() const
{
    return currentWidget() ? currentWidget()->sizeHint() : QStackedWidget::sizeHint();
}

QSize SinglePageStackedWidget::minimumSizeHint() const
{
    return currentWidget() ? currentWidget()->minimumSizeHint() : QStackedWidget::minimumSizeHint();
}

} // namespace audioforge
