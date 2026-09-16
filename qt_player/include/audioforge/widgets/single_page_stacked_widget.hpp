#pragma once

#include <QStackedWidget>

namespace audioforge {

// A QStackedWidget whose size hints reflect only the CURRENTLY visible
// page -- not the largest of every page it holds, which is QStackedWidget's
// own default. That default means a page that's never even shown can still
// inflate the whole window's minimum size. Concretely: the Now Playing
// page's lyrics list starts near-empty, but the first time a track plays
// and real lyrics text populates it, that page's own minimum size jumps --
// and with the stock QStackedWidget behavior, that silently grew the
// window's overall minimum height while the Library page was the one on
// screen, squeezing its own mini player bar (Volume slider and all) toward
// the bottom edge even though nothing on the Library page itself changed.
class SinglePageStackedWidget : public QStackedWidget
{
    Q_OBJECT

public:
    explicit SinglePageStackedWidget(QWidget* parent = nullptr);

    QSize sizeHint() const override;
    QSize minimumSizeHint() const override;
};

} // namespace audioforge
