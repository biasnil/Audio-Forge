#include "audioforge/widgets/card_list_widget.hpp"

namespace audioforge {

CardListWidget::CardListWidget(const QSize& iconSize, const QSize& gridSize, QWidget* parent) : QListWidget(parent)
{
    setObjectName("CardList");
    setViewMode(QListView::IconMode);
    setMovement(QListView::Static);
    setResizeMode(QListView::Adjust);
    setWrapping(true);
    setWordWrap(true);
    setUniformItemSizes(true);
    setTextElideMode(Qt::ElideRight);
    setIconSize(iconSize);
    setGridSize(gridSize);
    setSpacing(0);
    setFrameShape(QFrame::NoFrame);
    setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setSizePolicy(QSizePolicy::Expanding, QSizePolicy::Fixed);
    setSelectionMode(QAbstractItemView::SingleSelection);
}

void CardListWidget::addCard(QListWidgetItem* item)
{
    // Sized to the whole cell: left to the delegate, the text got one line and "..."
    item->setSizeHint(gridSize() - QSize(8, 8));
    item->setTextAlignment(Qt::AlignHCenter | Qt::AlignTop);
    addItem(item);
}

QSize CardListWidget::sizeHint() const
{
    // contentsSize() is the laid-out grid; at least one row before the first layout.
    const int height = qMax(contentsSize().height(), count() > 0 ? gridSize().height() : 0);
    return QSize(QListWidget::sizeHint().width(), height + 2 * frameWidth() + 4);
}

QSize CardListWidget::minimumSizeHint() const
{
    return QSize(gridSize().width(), sizeHint().height());
}

void CardListWidget::updateGeometries()
{
    QListWidget::updateGeometries();
    // The rows changed (items, or the width): the page re-lays out around the new height.
    if (height() != sizeHint().height())
    {
        setFixedHeight(sizeHint().height());
    }
}

} // namespace audioforge
