#pragma once

#include <QDialog>

namespace audioforge {

// Settings > About: Terms & Conditions, Privacy and Acknowledgements -- a
// read-only Markdown page with clickable links (opened in the browser).
class TextPageDialog : public QDialog
{
public:
    TextPageDialog(const QString& title, const QString& markdown, QWidget* parent = nullptr);
};

} // namespace audioforge
