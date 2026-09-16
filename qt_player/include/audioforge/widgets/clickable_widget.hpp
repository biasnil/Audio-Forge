#pragma once

#include <QWidget>

namespace audioforge {

// A plain QWidget with a clickable area (used for the mini player bar's
// cover/title section -- clicking it opens the full Now Playing page).
class ClickableWidget : public QWidget
{
    Q_OBJECT

public:
    explicit ClickableWidget(QWidget* parent = nullptr);

signals:
    void clicked();

protected:
    void mousePressEvent(QMouseEvent* event) override;
};

} // namespace audioforge
