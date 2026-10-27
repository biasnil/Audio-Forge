#pragma once

#include "audioforge/theme.hpp"

#include <QJsonObject>
#include <QPointer>
#include <QVector>
#include <QWidget>

#include <functional>

class QLabel;
class QLineEdit;
class QListWidget;
class QNetworkAccessManager;
class QNetworkReply;
class QPushButton;
class QScrollArea;
class QStackedWidget;

namespace audioforge {

class WebImageCache;
class OnlineRowDelegate;

// Where audiobooks live: an Audiobooks folder in the user's home. Books
// there play with resume and chapters, and stay out of Tracks.
QString AudiobooksRoot();
bool IsInAudiobooks(const QString& path);

// A LibriVox book (an Internet Archive item).
struct FreeBook
{
    QString id;     // the Internet Archive identifier
    QString title;
    QString author;
    QString description;
    QString coverUrl;
    struct Part
    {
        QString url;
        QString title;
        double seconds = 0.0;
    };
    QVector<Part> parts; // its chapters, one file each (filled by the book's page)

    double seconds() const;
};

// The Audiobooks tab: the books on this PC; "Find free audiobooks"
// searches LibriVox, a book's page downloads it. A download carries on the
// next time the app runs if it's closed midway, and failed parts can be
// tried again; the finished book is one .m4b with the cover and chapters.
class AudiobooksPage : public QWidget
{
    Q_OBJECT

public:
    AudiobooksPage(QNetworkAccessManager* network, WebImageCache* images, QWidget* parent = nullptr);
    ~AudiobooksPage() override;

    void activate();                 // the tab was opened: refreshes the books
    void refreshBooks();             // e.g. after "Download from link" saved one
    void setColors(const theme::Tokens& tokens);
    void setPlayingPath(const QString& path);
    std::function<double(const QString&)> resumePosition; // seconds into a book, -1 = not started

signals:
    void playFile(const QString& path);
    void message(const QString& text);

protected:
    bool eventFilter(QObject* watched, QEvent* event) override;

private:
    // A book downloading part by part into AudiobooksRoot()/.downloads/<id>/.
    struct Job
    {
        FreeBook book;
        QVector<bool> done;
        QVector<int> attempts;
        int current = -1;          // the part downloading now
        double partProgress = 0.0;
        bool assembling = false;
        bool failed = false;       // some parts failed: waiting for "Try again"
        QPointer<QNetworkReply> reply;
        QString folder() const;
        double progress() const;
    };

    void showLibrary();
    void search(const QString& text);
    void openBook(const FreeBook& book);
    void showBookPage();
    void updateBookButton();
    void startDownload(const FreeBook& book);
    void resumeSavedDownloads();
    void saveJob(const Job& job) const;
    void downloadNextPart(Job* job);
    void assemble(Job* job);
    Job* jobFor(const QString& id);
    QString bookOutputPath(const FreeBook& book) const;

    QNetworkAccessManager* m_network;
    WebImageCache* m_images;
    OnlineRowDelegate* m_delegate;
    QStackedWidget* m_stack;  // my books (0), search (1), a book (2)
    QLabel* m_subtitle;
    QPushButton* m_findButton;
    QPushButton* m_backButton;
    QListWidget* m_books;
    QLabel* m_booksStatus;
    QLineEdit* m_search;
    QListWidget* m_results;
    QLabel* m_resultsStatus;
    QLabel* m_bookCover;
    QLabel* m_bookTitle;
    QLabel* m_bookDetails;
    QLabel* m_bookDescription;
    QPushButton* m_downloadButton;
    QListWidget* m_chapters;

    QStringList m_bookPaths;     // the books list's files, in order
    QVector<FreeBook> m_found;
    FreeBook m_book;             // the open book page
    QVector<Job*> m_jobs;
    QString m_playingPath;
    int m_generation = 0;
    bool m_resumed = false;
};

} // namespace audioforge
