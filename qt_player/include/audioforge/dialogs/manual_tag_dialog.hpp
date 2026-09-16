#pragma once

#include <QDialog>
#include <QMap>
#include <QString>
#include <QByteArray>

class QVBoxLayout;
class QScrollArea;
class QLabel;
class QLineEdit;
class QWidget;

namespace audioforge {

// "Manual tagging" side of the Tag Music menu (the other side being the
// existing MusicBrainz lookup). One row per tag key -- a small "Key: X"
// label, an editable value field, and a X button that drops the tag
// entirely on save. "+ Add tag" introduces a new key; "Fill from internet"
// re-runs the MusicBrainz flow and, if the user picks a result, feeds it
// back into this dialog's fields via applyLookupResult() rather than
// writing to disk itself (PlayerWindow still owns that network flow).
class ManualTagDialog : public QDialog
{
    Q_OBJECT

public:
    // `coverArt` is shown at the top-left the way the album art thumbnail
    // is in the Harmonoid screenshot; pass an empty QByteArray for none.
    ManualTagDialog(const QString& path, const QMap<QString, QString>& tags,
                     const QByteArray& coverArt, QWidget* parent = nullptr);

    // Call after the user picks a MusicBrainz candidate in response to
    // fillFromInternetRequested(), to push those values into the matching
    // rows (creating ALBUM/ALBUMARTIST/ARTIST/DATE rows if they don't
    // already exist).
    void applyLookupResult(const QString& title, const QString& artist,
                            const QString& album, unsigned int year);

signals:
    // Emitted when the user clicks "Fill from internet". PlayerWindow
    // connects this to its existing lookupSelectedTrackOnMusicBrainz()
    // flow and calls applyLookupResult() once a candidate is chosen.
    void fillFromInternetRequested();

private:
    struct TagRow
    {
        QWidget* container = nullptr;
        QString key;
        QLineEdit* valueEdit = nullptr;
        bool isCore = false; // default field (Title/Artist/Album/...) -- shown even when
                              // empty, and can't be deleted, so the editor looks the same
                              // across every file instead of varying with whatever tags
                              // that particular file happens to already have.
    };

    void addTagRow(const QString& key, const QString& value, bool isCore = false);
    void removeTagRow(QWidget* container);
    void setOrAddTag(const QString& key, const QString& value);
    void promptForNewTag();
    void trySave();

    QString m_path;
    QVBoxLayout* m_rowsLayout = nullptr;
    QVector<TagRow> m_rows;
};

} // namespace audioforge