#include "audioforge/dialogs/manual_tag_dialog.hpp"
#include "audioforge/track_metadata_tags.hpp"

#include <QVBoxLayout>
#include <QHBoxLayout>
#include <QScrollArea>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QFrame>
#include <QPixmap>
#include <QInputDialog>
#include <QMessageBox>

namespace audioforge {

namespace {

// Always shown, in this order, whether or not the file actually has them --
// keeps the editor looking the same from file to file instead of varying
// with whatever a given file's encoder happened to write. Anything else the
// file has (or the user adds via "+ Add tag") shows up below these, and can
// be deleted; these can't.
const QStringList& CoreTagKeys()
{
    static const QStringList keys = {
        "TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "GENRE", "DATE", "COMPOSER", "COMMENT"
    };
    return keys;
}

// Small dark card matching the "Key: ALBUM" / value-box look in the
// screenshot -- label on top, value field below, a X button to drop the
// row entirely.
QFrame* MakeRowFrame()
{
    auto* frame = new QFrame;
    frame->setStyleSheet(
        "QFrame { background: #1a1a1a; border-bottom: 1px solid #2a2a2a; }"
        "QLabel { color: #9a9a9a; font-size: 11px; }"
        "QLineEdit { background: #141414; color: #eeeeee; border: none;"
        "            padding: 6px 4px; font-size: 14px; }"
        "QPushButton#deleteTag { background: transparent; color: #888;"
        "                        border: none; font-size: 14px; }"
        "QPushButton#deleteTag:hover { color: #eee; }");
    return frame;
}

} // namespace

ManualTagDialog::ManualTagDialog(const QString& path, const QMap<QString, QString>& tags,
                                  const QByteArray& coverArt, QWidget* parent)
    : QDialog(parent)
    , m_path(path)
{
    setWindowTitle("Edit tags (Manual)");
    resize(760, 640);
    setStyleSheet("QDialog { background: #101010; }");

    auto* outer = new QVBoxLayout(this);

    // --- Header row: title + "Fill from internet" -------------------------
    auto* header = new QHBoxLayout;
    auto* titleLabel = new QLabel("Edit tags (Manual)");
    titleLabel->setStyleSheet("color: white; font-size: 22px; font-weight: 600;");
    header->addWidget(titleLabel);
    header->addStretch();

    auto* fillButton = new QPushButton("Fill from internet");
    fillButton->setStyleSheet(
        "QPushButton { background: #2a2a2a; color: white; border-radius: 14px;"
        "              padding: 6px 14px; }"
        "QPushButton:hover { background: #3a3a3a; }");
    connect(fillButton, &QPushButton::clicked, this, &ManualTagDialog::fillFromInternetRequested);
    header->addWidget(fillButton);
    outer->addLayout(header);

    // --- Body: cover art on the left, scrollable tag rows on the right ----
    auto* body = new QHBoxLayout;

    auto* coverLabel = new QLabel;
    coverLabel->setFixedSize(220, 220);
    coverLabel->setAlignment(Qt::AlignCenter);
    coverLabel->setStyleSheet("background: #1e1e1e; border: 1px solid #2a2a2a;");
    if (!coverArt.isEmpty())
    {
        QPixmap pix;
        if (pix.loadFromData(coverArt))
        {
            coverLabel->setPixmap(pix.scaled(220, 220, Qt::KeepAspectRatio, Qt::SmoothTransformation));
        }
    }
    body->addWidget(coverLabel, 0, Qt::AlignTop);

    auto* scroll = new QScrollArea;
    scroll->setWidgetResizable(true);
    scroll->setStyleSheet("QScrollArea { border: none; }");
    auto* rowsHost = new QWidget;
    m_rowsLayout = new QVBoxLayout(rowsHost);
    m_rowsLayout->setSpacing(0);
    m_rowsLayout->setContentsMargins(0, 0, 0, 0);
    m_rowsLayout->addStretch(); // rows get inserted before this stretch
    scroll->setWidget(rowsHost);
    body->addWidget(scroll, 1);

    outer->addLayout(body, 1);

    for (const QString& key : CoreTagKeys())
    {
        addTagRow(key, tags.value(key), /*isCore=*/true);
    }
    QStringList extraKeys = tags.keys();
    for (const QString& coreKey : CoreTagKeys())
    {
        extraKeys.removeAll(coreKey);
    }
    extraKeys.sort(Qt::CaseInsensitive);
    for (const QString& key : extraKeys)
    {
        addTagRow(key, tags.value(key));
    }

    // --- Footer: + Add tag / Cancel / Save --------------------------------
    auto* footer = new QHBoxLayout;
    auto* addTagButton = new QPushButton("+ Add tag");
    addTagButton->setStyleSheet("QPushButton { color: #ccc; background: transparent; border: none; }"
                                "QPushButton:hover { color: white; }");
    connect(addTagButton, &QPushButton::clicked, this, &ManualTagDialog::promptForNewTag);
    footer->addWidget(addTagButton);
    footer->addStretch();

    auto* cancelButton = new QPushButton("Cancel");
    cancelButton->setStyleSheet("QPushButton { color: #ccc; background: transparent; border: none; padding: 8px 16px; }");
    connect(cancelButton, &QPushButton::clicked, this, &QDialog::reject);
    footer->addWidget(cancelButton);

    auto* saveButton = new QPushButton("Save");
    saveButton->setStyleSheet("QPushButton { background: #6c3ce9; color: white; border-radius: 6px; padding: 8px 20px; }"
                              "QPushButton:hover { background: #7d4ef0; }");
    connect(saveButton, &QPushButton::clicked, this, &ManualTagDialog::trySave);
    footer->addWidget(saveButton);

    outer->addLayout(footer);
}

void ManualTagDialog::addTagRow(const QString& key, const QString& value, bool isCore)
{
    auto* frame = MakeRowFrame();
    auto* frameLayout = new QVBoxLayout(frame);
    frameLayout->setContentsMargins(12, 10, 12, 10);
    frameLayout->setSpacing(4);

    auto* topRow = new QHBoxLayout;
    auto* keyLabel = new QLabel(QString("Key: %1").arg(key));
    topRow->addWidget(keyLabel);
    topRow->addStretch();

    if (!isCore)
    {
        auto* deleteButton = new QPushButton(QChar(0x2715)); // ×
        deleteButton->setObjectName("deleteTag");
        deleteButton->setFixedSize(20, 20);
        connect(deleteButton, &QPushButton::clicked, this, [this, frame]() { removeTagRow(frame); });
        topRow->addWidget(deleteButton);
    }
    frameLayout->addLayout(topRow);

    auto* valueEdit = new QLineEdit(value);
    frameLayout->addWidget(valueEdit);

    // Insert before the trailing stretch so new rows keep landing at the
    // bottom of the list rather than after the stretch (where they'd be
    // invisible/squashed).
    m_rowsLayout->insertWidget(m_rowsLayout->count() - 1, frame);
    m_rows.append({frame, key, valueEdit, isCore});
}

void ManualTagDialog::removeTagRow(QWidget* container)
{
    for (int i = 0; i < m_rows.size(); ++i)
    {
        if (m_rows[i].container == container)
        {
            if (m_rows[i].isCore)
            {
                return; // default fields can't be removed -- no delete button reaches here anyway
            }
            m_rows.removeAt(i);
            break;
        }
    }
    container->deleteLater();
}

void ManualTagDialog::setOrAddTag(const QString& key, const QString& value)
{
    for (auto& row : m_rows)
    {
        if (row.key.compare(key, Qt::CaseInsensitive) == 0)
        {
            row.valueEdit->setText(value);
            return;
        }
    }
    addTagRow(key, value, CoreTagKeys().contains(key, Qt::CaseInsensitive));
}

void ManualTagDialog::promptForNewTag()
{
    bool ok = false;
    QString key = QInputDialog::getText(this, "Add tag", "Tag key (e.g. COMPOSER):",
                                         QLineEdit::Normal, QString(), &ok);
    key = key.trimmed().toUpper();
    if (!ok || key.isEmpty())
    {
        return;
    }
    for (const auto& row : m_rows)
    {
        if (row.key.compare(key, Qt::CaseInsensitive) == 0)
        {
            QMessageBox::information(this, "Tag exists", QString("\"%1\" is already in the list below.").arg(key));
            return;
        }
    }
    addTagRow(key, QString());
}

void ManualTagDialog::applyLookupResult(const QString& title, const QString& artist,
                                         const QString& album, unsigned int year)
{
    if (!title.isEmpty())  setOrAddTag("TITLE", title);
    if (!artist.isEmpty())
    {
        setOrAddTag("ARTIST", artist);
        setOrAddTag("ALBUMARTIST", artist);
    }
    if (!album.isEmpty())  setOrAddTag("ALBUM", album);
    if (year > 0)          setOrAddTag("DATE", QString::number(year));
}

void ManualTagDialog::trySave()
{
    QMap<QString, QString> tags;
    for (const auto& row : m_rows)
    {
        QString value = row.valueEdit->text().trimmed();
        if (!value.isEmpty())
        {
            tags[row.key] = value;
        }
    }

    const bool saved = m_saveFunction ? m_saveFunction(tags) : WriteAllTags(m_path, tags);
    if (!saved)
    {
        QMessageBox::warning(this, "Couldn't save tags",
                              "The file's tags couldn't be written. It may be read-only or in use.");
        return;
    }
    accept();
}

} // namespace audioforge