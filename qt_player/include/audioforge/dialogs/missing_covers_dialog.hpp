#pragma once

#include <QDialog>
#include <QStringList>
#include <QVector>

#include <functional>

class QLabel;
class QListWidget;
class QNetworkAccessManager;
class QPushButton;
class QTimer;

namespace audioforge {

// Settings > Library > Find missing covers: finds which albums (and songs
// without an album) have no cover, looks each up on Apple Music, then
// Deezer, and shows what it found. The files change only on Save, and only
// for the ones left ticked.
class MissingCoversDialog : public QDialog
{
    Q_OBJECT

public:
    struct Group
    {
        QString album;     // empty: a single song without an album
        QString artist;
        QString title;     // the song's, for a single song
        QStringList paths; // the files that would get the cover
    };

    MissingCoversDialog(QNetworkAccessManager* network, const QVector<Group>& groups, QWidget* parent = nullptr);

    std::function<bool(const QString& path, const QByteArray& jpeg)> saveCover; // writes one file

signals:
    void coversSaved(const QStringList& paths);

private:
    void checkSome();   // which groups have no cover yet (a few per tick)
    void lookUpNext();
    void save();
    void updateButtons();

    QNetworkAccessManager* m_network;
    QVector<Group> m_groups;
    QVector<int> m_missing;          // indexes into m_groups, one per list row
    QVector<QByteArray> m_found;     // per row: the cover found (empty: none yet / not found)
    int m_checked = 0;               // groups checked for a cover so far
    int m_nextLookup = 0;            // the next row to look up
    bool m_stopped = false;
    QLabel* m_status;
    QListWidget* m_list;
    QPushButton* m_stopButton;
    QPushButton* m_saveButton;
    QTimer* m_checkTimer;
};

} // namespace audioforge
