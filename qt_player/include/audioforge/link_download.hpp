#pragma once

#include <QDialog>
#include <QJsonObject>
#include <QPointer>
#include <QVector>

#include <functional>

class QButtonGroup;
class QCheckBox;
class QLabel;
class QLineEdit;
class QListWidget;
class QNetworkAccessManager;
class QProcess;
class QProgressBar;
class QPushButton;

namespace audioforge {

// "Artist - Title (Official Video)" -> {"Artist", "Title"}: a video's name as
// a song's tags. `track` / `artist` are what the site says, when it says.
struct SongName
{
    QString artist;
    QString title;
};
SongName SongNameFromVideo(const QString& videoTitle, const QString& uploader, const QString& track, const QString& artist);

// The words that mark a bracketed part of a video title as an extra to drop:
// "(Official Video)", "[Lyrics]", "{Explicit}"... -- (), [], {} and 【】 all
// count the same, and a part goes if it holds one of the words. The user
// can edit the list (Settings > Download from link).
QStringList DefaultTitleDropWords();
QStringList TitleDropWords();
void SetTitleDropWords(const QStringList& words);
QString WithoutTitleExtras(const QString& title);
bool EditTitleDropWords(QWidget* parent); // the editor dialog; true if the list changed

// Download from link: a video or playlist (YouTube and the many sites
// yt-dlp knows) saved as songs into Music (M4A with title, artist, cover)
// or as an audiobook into Audiobooks (keeping the video's chapters; a
// playlist can be one book). One item at a time, with Stop; it carries on
// while the dialog is closed. yt-dlp is fetched from GitHub on first use
// and keeps itself up to date.
class LinkDownloadDialog : public QDialog
{
    Q_OBJECT

public:
    LinkDownloadDialog(QNetworkAccessManager* network, QWidget* parent = nullptr);
    ~LinkDownloadDialog() override;

    std::function<QString()> musicFolder;  // where songs go (the library's)
    void setLink(const QString& url);      // e.g. a link that was on the clipboard

signals:
    void songSaved(const QString& path);
    void bookSaved(const QString& path);
    void message(const QString& text);

private:
    struct Item
    {
        QString url;
        QString title;
        int seconds = 0;
    };
    enum class State { Idle, Installing, LookingUp, Ready, Downloading };

    void lookUp();
    void ensureYtDlp(const std::function<void()>& then);
    void updateYtDlp(const std::function<void()>& then);
    void probe();
    void showItems();
    void startDownloads();
    void downloadNext();
    void finishItem(const QJsonObject& info, const QString& file);
    void saveBook(); // a playlist as one book, once every item is in
    void stop();
    void setState(State state);
    void setStatus(const QString& text, double progress = -1.0);
    QString tempFolder() const;

    QNetworkAccessManager* m_network;
    QLineEdit* m_link;
    QPushButton* m_lookUpButton;
    QLabel* m_sourceLabel;
    QButtonGroup* m_saveAs;     // 0 = songs, 1 = audiobook
    QCheckBox* m_oneBook;
    QListWidget* m_items;
    QLabel* m_status;
    QProgressBar* m_progress;
    QPushButton* m_downloadButton;
    QPushButton* m_stopButton;

    State m_state = State::Idle;
    QPointer<QProcess> m_process;
    QString m_playlistTitle;
    QString m_playlistUploader;
    QVector<Item> m_found;
    QVector<int> m_queue;     // indexes into m_found still to download
    int m_total = 0;
    int m_saved = 0;
    int m_failed = 0;
    bool m_asBook = false;
    bool m_asOneBook = false;
    struct Downloaded
    {
        QJsonObject info;
        QString file;
    };
    QVector<Downloaded> m_bookParts; // a one-book playlist's items, until all are in
    QByteArray m_bookCover;          // ...and its cover (the first item's)
};

} // namespace audioforge
