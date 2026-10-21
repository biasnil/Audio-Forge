#pragma once

#include <QListWidget>

namespace audioforge {

// A QListWidget showing its items as a wrapping grid of cards (icon above,
// text below) that is exactly as tall as its rows, never scrolling itself --
// for several card sections on one scrolling page (the Playlists tab).
class CardListWidget : public QListWidget
{
public:
    // iconSize: the card's picture; gridSize: the whole cell, text included.
    CardListWidget(const QSize& iconSize, const QSize& gridSize, QWidget* parent = nullptr);

    // Adds a card: picture, then two lines (name, details) under it.
    void addCard(QListWidgetItem* item);

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;

protected:
    void updateGeometries() override;
};

} // namespace audioforge
