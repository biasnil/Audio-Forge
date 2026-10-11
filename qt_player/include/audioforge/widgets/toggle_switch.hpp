#pragma once

#include <QAbstractButton>

namespace audioforge {

// iOS-style on/off switch. A checkable QAbstractButton, so it's a drop-in for
// a QCheckBox (toggled(), setChecked(), isChecked()). Colors come from the
// palette: Highlight when on, Mid-ish when off.
class ToggleSwitch : public QAbstractButton
{
public:
    explicit ToggleSwitch(QWidget* parent = nullptr);

    QSize sizeHint() const override { return QSize(46, 26); }

protected:
    void paintEvent(QPaintEvent* event) override;
};

} // namespace audioforge
