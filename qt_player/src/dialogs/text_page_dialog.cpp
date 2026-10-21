#include "audioforge/dialogs/text_page_dialog.hpp"

#include <QDialogButtonBox>
#include <QTextBrowser>
#include <QVBoxLayout>

namespace audioforge {

TextPageDialog::TextPageDialog(const QString& title, const QString& markdown, QWidget* parent) : QDialog(parent)
{
    setWindowTitle(title);
    resize(640, 600);

    auto* layout = new QVBoxLayout(this);
    auto* browser = new QTextBrowser();
    browser->setOpenExternalLinks(true);
    browser->setFrameShape(QFrame::NoFrame);
    browser->document()->setDocumentMargin(12);
    browser->setMarkdown(markdown);
    layout->addWidget(browser, 1);

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Close);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    layout->addWidget(buttons);
}

} // namespace audioforge
