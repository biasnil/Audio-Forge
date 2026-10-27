#include "audioforge/audiobooks_page.hpp"
#include "audioforge/media_mux.hpp"
#include "audioforge/track_metadata.hpp"
#include "audioforge/app_files.hpp"
#include "audioforge/widgets/online_views.hpp"

#include <QDir>
#include <QDirIterator>
#include <QEvent>
#include <QFile>
#include <QFileInfo>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QLocale>
#include <QMenu>
#include <QMouseEvent>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QPushButton>
#include <QRegularExpression>
#include <QScrollArea>
#include <QStackedWidget>
#include <QStandardPaths>
#include <QTextDocumentFragment>
#include <QThread>
#include <QTimer>
#include <QUrlQuery>
#include <QVBoxLayout>

#include <algorithm>
#include <memory>

namespace audioforge {

namespace {

constexpr int kPartAttempts = 3;

// "1234.5" (seconds) or "20:34" / "1:02:03" -> seconds.
double ParseLength(const QString& text)
{
    if (!text.contains(':'))
    {
        return text.toDouble();
    }
    double seconds = 0.0;
    for (const QString& part : text.split(':'))
    {
        seconds = seconds * 60.0 + part.toDouble();
    }
    return seconds;
}

QString Hours(double seconds)
{
    const int total = static_cast<int>(seconds);
    return total >= 3600 ? QObject::tr("%1 h %2 min").arg(total / 3600).arg(total % 3600 / 60)
                         : QObject::tr("%1 min").arg(qMax(1, total / 60));
}

// Internet Archive fields are a string, or a list of them.
QString JoinedField(const QJsonValue& value)
{
    if (value.isArray())
    {
        QStringList parts;
        for (const QJsonValue& v : value.toArray())
        {
            parts << v.toString();
        }
        return parts.join(QStringLiteral(", "));
    }
    return value.toString();
}

QJsonObject BookToJson(const FreeBook& book)
{
    QJsonArray parts;
    for (const FreeBook::Part& part : book.parts)
    {
        parts.append(QJsonObject{{"url", part.url}, {"title", part.title}, {"seconds", part.seconds}});
    }
    return QJsonObject{{"id", book.id},           {"title", book.title},       {"author", book.author},
                       {"description", book.description}, {"coverUrl", book.coverUrl}, {"parts", parts}};
}

FreeBook BookFromJson(const QJsonObject& o)
{
    FreeBook book;
    book.id = o.value("id").toString();
    book.title = o.value("title").toString();
    book.author = o.value("author").toString();
    book.description = o.value("description").toString();
    book.coverUrl = o.value("coverUrl").toString();
    for (const QJsonValue& value : o.value("parts").toArray())
    {
        const QJsonObject part = value.toObject();
        book.parts.append({part.value("url").toString(), part.value("title").toString(), part.value("seconds").toDouble()});
    }
    return book;
}

QString PartFile(const QString& folder, int index)
{
    return QDir(folder).filePath(QStringLiteral("%1.mp3").arg(index + 1, 3, 10, QChar('0')));
}

} // namespace

QString AudiobooksRoot()
{
    return QDir(QStandardPaths::writableLocation(QStandardPaths::HomeLocation)).filePath(QStringLiteral("Audiobooks"));
}

bool IsInAudiobooks(const QString& path)
{
    return path.startsWith(AudiobooksRoot() + '/', Qt::CaseInsensitive);
}

double FreeBook::seconds() const
{
    double total = 0.0;
    for (const Part& part : parts)
    {
        total += part.seconds;
    }
    return total;
}

QString AudiobooksPage::Job::folder() const
{
    return QDir(AudiobooksRoot()).filePath(QStringLiteral(".downloads/") + SafeFileName(book.id));
}

double AudiobooksPage::Job::progress() const
{
    if (book.parts.isEmpty())
    {
        return 0.0;
    }
    const int finished = static_cast<int>(std::count(done.begin(), done.end(), true));
    return qBound(0.0, (finished + (current >= 0 ? partProgress : 0.0)) / book.parts.size(), 1.0);
}

AudiobooksPage::AudiobooksPage(QNetworkAccessManager* network, WebImageCache* images, QWidget* parent)
    : QWidget(parent), m_network(network), m_images(images)
{
    auto* l = new QVBoxLayout(this);
    l->setContentsMargins(28, 24, 28, 0);
    l->setSpacing(0);

    auto* header = new QHBoxLayout();
    header->setSpacing(10);
    m_backButton = OnlineButton(QString(), "IconButton");
    m_backButton->setFixedSize(34, 34);
    m_backButton->setIconSize(QSize(18, 18));
    m_backButton->setToolTip(tr("Back"));
    m_backButton->setAccessibleName(tr("Back"));
    m_backButton->hide();
    connect(m_backButton, &QPushButton::clicked, this, [this]() {
        if (m_stack->currentIndex() == 2 && !m_found.isEmpty())
        {
            m_stack->setCurrentIndex(1); // the book's page -> the search it came from
            m_backButton->show();
        }
        else
        {
            showLibrary();
        }
    });
    header->addWidget(m_backButton, 0, Qt::AlignTop);
    header->addLayout(OnlinePageTitle(tr("Audiobooks"), &m_subtitle));
    header->addStretch();
    m_findButton = OnlineButton(tr("Find free audiobooks"), "PrimaryButton");
    connect(m_findButton, &QPushButton::clicked, this, [this]() {
        m_stack->setCurrentIndex(1);
        m_backButton->show();
        m_findButton->hide();
        m_subtitle->setText(tr("Public-domain books read by LibriVox volunteers, from the Internet Archive"));
        m_search->setFocus();
        if (m_found.isEmpty())
        {
            search(QString()); // the most listened to, to start with
        }
    });
    header->addWidget(m_findButton, 0, Qt::AlignVCenter);
    l->addLayout(header);
    l->addSpacing(18);

    m_stack = new QStackedWidget();
    l->addWidget(m_stack, 1);
    m_delegate = new OnlineRowDelegate(images, this);
    auto makeList = [this, images]() {
        auto* list = new QListWidget();
        list->setItemDelegate(m_delegate);
        list->setMouseTracking(true);
        list->setUniformItemSizes(true);
        list->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
        list->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
        list->setCursor(Qt::PointingHandCursor);
        list->viewport()->installEventFilter(this);
        connect(images, &WebImageCache::loaded, list->viewport(), qOverload<>(&QWidget::update));
        return list;
    };
    auto makeStatus = []() {
        auto* label = new QLabel();
        label->setObjectName("PageSubtitle");
        label->setAlignment(Qt::AlignCenter);
        label->setWordWrap(true);
        label->hide();
        return label;
    };

    // 0: the books on this PC (downloads in progress first).
    auto* booksPage = new QWidget();
    auto* booksLayout = new QVBoxLayout(booksPage);
    booksLayout->setContentsMargins(0, 0, 0, 0);
    m_books = makeList();
    booksLayout->addWidget(m_books, 1);
    m_booksStatus = makeStatus();
    booksLayout->addWidget(m_booksStatus, 1);
    m_stack->addWidget(booksPage);
    m_books->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_books, &QListWidget::customContextMenuRequested, this, [this](const QPoint& pos) {
        QListWidgetItem* item = m_books->itemAt(pos);
        const int index = item ? item->data(online_role::Data).toInt() : -1;
        if (index < 0 || index >= m_bookPaths.size())
        {
            return;
        }
        const QString path = m_bookPaths[index];
        QMenu menu(this);
        menu.addAction(tr("Play"), this, [this, path]() { emit playFile(path); });
        menu.addAction(tr("Show in Folder"), this, [path]() { ShowInFileManager(path); });
        menu.exec(m_books->viewport()->mapToGlobal(pos));
    });

    // 1: LibriVox search.
    auto* searchPage = new QWidget();
    auto* searchLayout = new QVBoxLayout(searchPage);
    searchLayout->setContentsMargins(0, 0, 0, 0);
    searchLayout->setSpacing(12);
    m_search = new QLineEdit();
    m_search->setPlaceholderText(tr("Search by title or author"));
    m_search->setClearButtonEnabled(true);
    m_search->setAccessibleName(tr("Search free audiobooks"));
    connect(m_search, &QLineEdit::returnPressed, this, [this]() { search(m_search->text()); });
    searchLayout->addWidget(m_search);
    m_results = makeList();
    searchLayout->addWidget(m_results, 1);
    m_resultsStatus = makeStatus();
    searchLayout->addWidget(m_resultsStatus, 1);
    m_stack->addWidget(searchPage);

    // 2: a book -- cover, details, Download, description, chapters.
    auto* bookPage = new QWidget();
    auto* bookLayout = new QVBoxLayout(bookPage);
    bookLayout->setContentsMargins(0, 0, 12, 24);
    bookLayout->setSpacing(14);
    auto* top = new QHBoxLayout();
    top->setSpacing(22);
    m_bookCover = new QLabel();
    m_bookCover->setFixedSize(160, 160);
    top->addWidget(m_bookCover, 0, Qt::AlignTop);
    auto* info = new QVBoxLayout();
    info->setSpacing(6);
    m_bookTitle = new QLabel();
    m_bookTitle->setObjectName("EmptyTitle");
    m_bookTitle->setWordWrap(true);
    info->addWidget(m_bookTitle);
    m_bookDetails = new QLabel();
    m_bookDetails->setObjectName("SoftLabel");
    m_bookDetails->setWordWrap(true);
    info->addWidget(m_bookDetails);
    info->addSpacing(8);
    m_downloadButton = OnlineButton(QString(), "PrimaryButton");
    connect(m_downloadButton, &QPushButton::clicked, this, [this]() {
        const QString path = bookOutputPath(m_book);
        if (QFileInfo::exists(path))
        {
            emit playFile(path);
        }
        else if (Job* job = jobFor(m_book.id); job && job->failed)
        {
            job->failed = false;
            std::fill(job->attempts.begin(), job->attempts.end(), 0);
            downloadNextPart(job);
        }
        else if (!job && !m_book.parts.isEmpty())
        {
            startDownload(m_book);
        }
    });
    auto* buttonRow = new QHBoxLayout();
    buttonRow->addWidget(m_downloadButton);
    buttonRow->addStretch();
    info->addLayout(buttonRow);
    info->addStretch();
    top->addLayout(info, 1);
    bookLayout->addLayout(top);
    m_bookDescription = new QLabel();
    m_bookDescription->setObjectName("SoftLabel");
    m_bookDescription->setWordWrap(true);
    m_bookDescription->setTextInteractionFlags(Qt::TextSelectableByMouse);
    bookLayout->addWidget(m_bookDescription);
    auto* chaptersCaption = new QLabel(tr("CHAPTERS"));
    chaptersCaption->setObjectName("Caption");
    bookLayout->addWidget(chaptersCaption);
    m_chapters = new QListWidget();
    m_chapters->setSelectionMode(QAbstractItemView::NoSelection);
    m_chapters->setFocusPolicy(Qt::NoFocus);
    m_chapters->setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_chapters->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    bookLayout->addWidget(m_chapters);
    bookLayout->addStretch();
    auto* bookScroll = new QScrollArea();
    bookScroll->setWidgetResizable(true);
    bookScroll->setFrameShape(QFrame::NoFrame);
    bookScroll->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    bookScroll->setWidget(bookPage);
    m_stack->addWidget(bookScroll);

    connect(images, &WebImageCache::loaded, this, [this]() {
        if (m_stack->currentIndex() == 2)
        {
            m_bookCover->setPixmap(m_images->image(m_book.coverUrl, 160, 8, m_book.title, theme::Icon::Book));
        }
    });

    // A download the app was closed during carries on.
    QTimer::singleShot(4000, this, &AudiobooksPage::resumeSavedDownloads);
}

AudiobooksPage::~AudiobooksPage()
{
    for (Job* job : m_jobs)
    {
        if (job->reply)
        {
            job->reply->disconnect(this);
            job->reply->abort(); // the part stays a .part; the next run starts it again
        }
        delete job;
    }
}

void AudiobooksPage::activate()
{
    refreshBooks();
}

void AudiobooksPage::setColors(const theme::Tokens& tokens)
{
    m_delegate->setColors(tokens);
    m_backButton->setIcon(theme::icon(theme::Icon::Back, tokens.text));
    m_books->viewport()->update();
    m_results->viewport()->update();
}

void AudiobooksPage::setPlayingPath(const QString& path)
{
    m_playingPath = path;
    for (int i = 0; i < m_books->count(); ++i)
    {
        QListWidgetItem* item = m_books->item(i);
        const int index = item->data(online_role::Data).toInt();
        item->setData(online_role::Accent, index >= 0 && m_bookPaths.value(index) == path);
    }
}

void AudiobooksPage::showLibrary()
{
    ++m_generation;
    m_stack->setCurrentIndex(0);
    m_backButton->hide();
    m_findButton->show();
    refreshBooks();
}

void AudiobooksPage::refreshBooks()
{
    if (m_stack->currentIndex() == 0)
    {
        m_subtitle->setText(QDir::toNativeSeparators(AudiobooksRoot()));
    }
    m_bookPaths.clear();
    QDirIterator it(AudiobooksRoot(), {"*.m4b", "*.m4a", "*.mp3", "*.aac"}, QDir::Files, QDirIterator::Subdirectories);
    while (it.hasNext())
    {
        const QString path = it.next();
        if (!path.contains(QLatin1String("/.downloads/")))
        {
            m_bookPaths << path;
        }
    }
    std::sort(m_bookPaths.begin(), m_bookPaths.end(), [](const QString& a, const QString& b) {
        return QString::localeAwareCompare(QFileInfo(a).completeBaseName(), QFileInfo(b).completeBaseName()) < 0;
    });

    m_books->clear();
    // Downloads still going (or waiting for "Try again") first.
    for (int j = 0; j < m_jobs.size(); ++j)
    {
        const Job* job = m_jobs[j];
        auto* item = new QListWidgetItem(job->book.title);
        const int finished = static_cast<int>(std::count(job->done.begin(), job->done.end(), true));
        const int failed = job->book.parts.size() - finished;
        item->setData(online_role::Subtitle,
                      job->assembling ? tr("Putting the book together…")
                      : job->failed   ? tr("%1 parts couldn't be downloaded").arg(failed)
                                      : tr("Downloading part %1 of %2").arg(qMin(finished + 1, static_cast<int>(job->book.parts.size()))).arg(job->book.parts.size()));
        item->setData(online_role::ImageUrl, job->book.coverUrl);
        item->setData(online_role::Mark, static_cast<int>(theme::Icon::Book));
        if (job->failed)
        {
            item->setData(online_role::Button, tr("Try again"));
        }
        else
        {
            item->setData(online_role::Progress, job->progress());
        }
        item->setData(online_role::Data, -1 - j);
        m_books->addItem(item);
    }
    for (int i = 0; i < m_bookPaths.size(); ++i)
    {
        const QString& path = m_bookPaths[i];
        const TrackInfo info = ReadTrackInfo(path, false);
        auto* item = new QListWidgetItem(info.title.isEmpty() ? QFileInfo(path).completeBaseName() : info.title);
        QStringList details;
        if (!info.artist.isEmpty())
        {
            details << info.artist;
        }
        if (info.durationSeconds > 0)
        {
            details << Hours(info.durationSeconds);
            const double position = resumePosition ? resumePosition(path) : -1.0;
            if (position > 0)
            {
                details << tr("%1% listened").arg(qBound(1, qRound(100.0 * position / info.durationSeconds), 100));
            }
        }
        item->setData(online_role::Subtitle, details.join(QStringLiteral(" · ")));
        item->setData(online_role::ImageUrl, QStringLiteral("cover:") + path);
        item->setData(online_role::Seed, item->text());
        item->setData(online_role::Mark, static_cast<int>(theme::Icon::Book));
        item->setData(online_role::Accent, path == m_playingPath);
        item->setData(online_role::Data, i);
        item->setToolTip(QDir::toNativeSeparators(path));
        m_books->addItem(item);
    }
    m_booksStatus->setText(tr("No audiobooks yet. Find free ones from LibriVox, or put .m4b or .mp3 books in %1.")
                               .arg(QDir::toNativeSeparators(AudiobooksRoot())));
    m_booksStatus->setVisible(m_books->count() == 0);
    m_books->setVisible(m_books->count() > 0);
}

void AudiobooksPage::search(const QString& text)
{
    const int generation = ++m_generation;
    m_results->clear();
    m_results->hide();
    m_resultsStatus->setText(tr("Searching LibriVox…"));
    m_resultsStatus->show();
    QString query = QStringLiteral("collection:(librivoxaudio) AND mediatype:(audio)");
    QString term = text.trimmed();
    term.remove(QRegularExpression(QStringLiteral("[\"():]")));
    if (!term.isEmpty())
    {
        query += QStringLiteral(" AND (title:(%1) OR creator:(%1))").arg(term);
    }
    QUrl url(QStringLiteral("https://archive.org/advancedsearch.php"));
    QUrlQuery params;
    params.addQueryItem("q", query);
    for (const char* field : {"identifier", "title", "creator"})
    {
        params.addQueryItem("fl[]", QString::fromLatin1(field));
    }
    params.addQueryItem("sort[]", "downloads desc");
    params.addQueryItem("rows", "60");
    params.addQueryItem("output", "json");
    url.setQuery(params);
    QNetworkReply* reply = NetworkGet(m_network, url);
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation, term]() {
        reply->deleteLater();
        if (generation != m_generation)
        {
            return;
        }
        if (reply->error() != QNetworkReply::NoError)
        {
            m_resultsStatus->setText(tr("The Internet Archive can't be reached right now. Check your connection and try again."));
            return;
        }
        const QJsonObject response = QJsonDocument::fromJson(reply->readAll()).object().value("response").toObject();
        if (term.isEmpty())
        {
            m_subtitle->setText(tr("%1 free audiobooks read by LibriVox volunteers, from the Internet Archive")
                                    .arg(QLocale().toString(response.value("numFound").toInt())));
        }
        m_found.clear();
        for (const QJsonValue& value : response.value("docs").toArray())
        {
            const QJsonObject doc = value.toObject();
            FreeBook book;
            book.id = doc.value("identifier").toString();
            book.title = JoinedField(doc.value("title"));
            book.author = JoinedField(doc.value("creator"));
            book.coverUrl = QStringLiteral("https://archive.org/services/img/") + book.id;
            if (!book.id.isEmpty())
            {
                m_found << book;
            }
        }
        for (int i = 0; i < m_found.size(); ++i)
        {
            auto* item = new QListWidgetItem(m_found[i].title);
            item->setData(online_role::Subtitle, m_found[i].author);
            item->setData(online_role::ImageUrl, m_found[i].coverUrl);
            item->setData(online_role::Mark, static_cast<int>(theme::Icon::Book));
            item->setData(online_role::Data, i);
            item->setToolTip(m_found[i].title);
            m_results->addItem(item);
        }
        m_resultsStatus->setText(tr("No books found."));
        m_resultsStatus->setVisible(m_found.isEmpty());
        m_results->setVisible(!m_found.isEmpty());
    });
}

void AudiobooksPage::openBook(const FreeBook& found)
{
    const int generation = ++m_generation;
    m_book = found;
    m_stack->setCurrentIndex(2);
    m_backButton->show();
    m_findButton->hide();
    m_bookTitle->setText(found.title);
    m_bookDetails->setText(found.author);
    m_bookDescription->setText(tr("Loading…"));
    m_bookCover->setPixmap(m_images->image(found.coverUrl, 160, 8, found.title, theme::Icon::Book));
    m_chapters->clear();
    m_chapters->setFixedHeight(0);
    updateBookButton();

    QNetworkReply* reply = NetworkGet(m_network, QUrl(QStringLiteral("https://archive.org/metadata/") + found.id));
    connect(reply, &QNetworkReply::finished, this, [this, reply, generation]() {
        reply->deleteLater();
        if (generation != m_generation)
        {
            return;
        }
        if (reply->error() != QNetworkReply::NoError)
        {
            m_bookDescription->setText(tr("This book's details can't be loaded right now."));
            return;
        }
        const QJsonObject item = QJsonDocument::fromJson(reply->readAll()).object();
        const QJsonObject metadata = item.value("metadata").toObject();
        m_book.description = QTextDocumentFragment::fromHtml(JoinedField(metadata.value("description"))).toPlainText().trimmed();
        if (m_book.author.isEmpty())
        {
            m_book.author = JoinedField(metadata.value("creator"));
        }

        // The chapters: the item's MP3s in the best of LibriVox's usual
        // formats, in track order. A JPEG in the item is its cover.
        struct File
        {
            QString name, title;
            double seconds;
            int track;
        };
        QHash<QString, QVector<File>> byFormat;
        QString coverFile;
        for (const QJsonValue& value : item.value("files").toArray())
        {
            const QJsonObject file = value.toObject();
            const QString format = file.value("format").toString();
            const QString name = file.value("name").toString();
            if (format == QLatin1String("JPEG") && coverFile.isEmpty())
            {
                coverFile = name;
            }
            if (!format.contains(QLatin1String("MP3")))
            {
                continue;
            }
            byFormat[format].append({name, file.value("title").toString(), ParseLength(file.value("length").toString()),
                                     file.value("track").toString().section('/', 0, 0).toInt()});
        }
        QVector<File> files;
        for (const char* format : {"64Kbps MP3", "128Kbps MP3", "VBR MP3", "MP3"})
        {
            if (byFormat.contains(QString::fromLatin1(format)))
            {
                files = byFormat.value(QString::fromLatin1(format));
                break;
            }
        }
        std::stable_sort(files.begin(), files.end(), [](const File& a, const File& b) {
            return a.track != b.track ? a.track < b.track : QString::compare(a.name, b.name, Qt::CaseInsensitive) < 0;
        });
        const QString base = QStringLiteral("https://archive.org/download/") + m_book.id + '/';
        m_book.parts.clear();
        for (const File& file : files)
        {
            m_book.parts.append({base + QString::fromUtf8(QUrl::toPercentEncoding(file.name, "/")),
                                 file.title.isEmpty() ? QFileInfo(file.name).completeBaseName() : file.title, file.seconds});
        }
        if (!coverFile.isEmpty())
        {
            m_book.coverUrl = base + QString::fromUtf8(QUrl::toPercentEncoding(coverFile, "/"));
            m_bookCover->setPixmap(m_images->image(m_book.coverUrl, 160, 8, m_book.title, theme::Icon::Book));
        }
        showBookPage();
    });
}

void AudiobooksPage::showBookPage()
{
    QStringList details;
    if (!m_book.author.isEmpty())
    {
        details << m_book.author;
    }
    details << (m_book.parts.size() == 1 ? tr("1 chapter") : tr("%1 chapters").arg(m_book.parts.size()));
    if (m_book.seconds() > 0)
    {
        details << Hours(m_book.seconds());
    }
    m_bookDetails->setText(details.join(QStringLiteral(" · ")));
    m_bookDescription->setText(m_book.description.isEmpty() ? tr("No description.") : m_book.description);
    m_chapters->clear();
    for (int i = 0; i < m_book.parts.size(); ++i)
    {
        const FreeBook::Part& part = m_book.parts[i];
        auto* item = new QListWidgetItem(QStringLiteral("%1.  %2   ·   %3").arg(i + 1).arg(part.title,
                                         part.seconds > 0 ? FormatDuration(static_cast<int>(part.seconds)) : QString()));
        m_chapters->addItem(item);
    }
    m_chapters->setFixedHeight(m_chapters->count() > 0 ? m_chapters->count() * m_chapters->sizeHintForRow(0) + 4 : 0);
    updateBookButton();
}

void AudiobooksPage::updateBookButton()
{
    const Job* job = jobFor(m_book.id);
    if (QFileInfo::exists(bookOutputPath(m_book)))
    {
        m_downloadButton->setText(tr("Play"));
        m_downloadButton->setEnabled(true);
    }
    else if (job && job->failed)
    {
        m_downloadButton->setText(tr("Try again"));
        m_downloadButton->setEnabled(true);
    }
    else if (job)
    {
        m_downloadButton->setText(job->assembling ? tr("Putting it together…") : tr("Downloading %1%").arg(qRound(job->progress() * 100)));
        m_downloadButton->setEnabled(false);
    }
    else
    {
        m_downloadButton->setText(tr("Download"));
        m_downloadButton->setEnabled(!m_book.parts.isEmpty());
    }
}

QString AudiobooksPage::bookOutputPath(const FreeBook& book) const
{
    const QString name = book.author.isEmpty() ? book.title : book.author.section(',', 0, 0) + QStringLiteral(" - ") + book.title;
    return QDir(AudiobooksRoot()).filePath(SafeFileName(name) + QStringLiteral(".m4b"));
}

AudiobooksPage::Job* AudiobooksPage::jobFor(const QString& id)
{
    for (Job* job : m_jobs)
    {
        if (job->book.id == id)
        {
            return job;
        }
    }
    return nullptr;
}

void AudiobooksPage::startDownload(const FreeBook& book)
{
    auto* job = new Job();
    job->book = book;
    job->done.fill(false, book.parts.size());
    job->attempts.fill(0, book.parts.size());
    if (!QDir().mkpath(job->folder()))
    {
        emit message(tr("Couldn't create %1.").arg(QDir::toNativeSeparators(job->folder())));
        delete job;
        return;
    }
    saveJob(*job);
    m_jobs << job;
    // The cover first, while it's at hand.
    if (!book.coverUrl.isEmpty())
    {
        QNetworkReply* cover = NetworkGet(m_network, QUrl(book.coverUrl));
        const QString coverPath = QDir(job->folder()).filePath(QStringLiteral("cover.jpg"));
        connect(cover, &QNetworkReply::finished, this, [cover, coverPath]() {
            cover->deleteLater();
            QFile file(coverPath);
            if (cover->error() == QNetworkReply::NoError && file.open(QIODevice::WriteOnly))
            {
                file.write(SquareJpeg(cover->readAll()));
            }
        });
    }
    emit message(tr("Downloading “%1”. You can keep using AudioForge meanwhile.").arg(book.title));
    downloadNextPart(job);
    updateBookButton();
}

void AudiobooksPage::saveJob(const Job& job) const
{
    WriteJsonFile(QDir(job.folder()).filePath(QStringLiteral("book.json")), BookToJson(job.book));
}

void AudiobooksPage::resumeSavedDownloads()
{
    if (m_resumed)
    {
        return;
    }
    m_resumed = true;
    const QDir downloads(QDir(AudiobooksRoot()).filePath(QStringLiteral(".downloads")));
    for (const QString& folder : downloads.entryList(QDir::Dirs | QDir::NoDotAndDotDot))
    {
        const FreeBook book = BookFromJson(ReadJsonFile(downloads.filePath(folder + QStringLiteral("/book.json"))));
        if (book.id.isEmpty() || book.parts.isEmpty() || jobFor(book.id))
        {
            continue;
        }
        auto* job = new Job();
        job->book = book;
        job->attempts.fill(0, book.parts.size());
        for (int i = 0; i < book.parts.size(); ++i)
        {
            job->done << QFileInfo::exists(PartFile(job->folder(), i));
        }
        m_jobs << job;
        downloadNextPart(job);
    }
    if (m_stack->currentIndex() == 0)
    {
        refreshBooks();
    }
}

void AudiobooksPage::downloadNextPart(Job* job)
{
    int next = -1;
    for (int i = 0; i < job->book.parts.size(); ++i)
    {
        if (!job->done[i] && job->attempts[i] < kPartAttempts)
        {
            next = i;
            break;
        }
    }
    job->current = next;
    job->partProgress = 0.0;
    auto refresh = [this, job]() {
        if (m_stack->currentIndex() == 0)
        {
            refreshBooks();
        }
        if (m_stack->currentIndex() == 2 && m_book.id == job->book.id)
        {
            updateBookButton();
        }
    };
    if (next < 0)
    {
        if (std::all_of(job->done.begin(), job->done.end(), [](bool d) { return d; }))
        {
            assemble(job);
        }
        else
        {
            job->failed = true;
            emit message(tr("Some parts of “%1” couldn't be downloaded. Try again from Audiobooks.").arg(job->book.title));
        }
        refresh();
        return;
    }

    const QString target = PartFile(job->folder(), next);
    auto file = std::make_shared<QFile>(target + QStringLiteral(".part"));
    if (!file->open(QIODevice::WriteOnly))
    {
        job->attempts[next] = kPartAttempts;
        QTimer::singleShot(0, this, [this, job]() { downloadNextPart(job); });
        return;
    }
    QNetworkReply* reply = NetworkGet(m_network, QUrl(job->book.parts[next].url));
    reply->setReadBufferSize(1024 * 1024);
    job->reply = reply;
    connect(reply, &QNetworkReply::readyRead, this, [reply, file]() { file->write(reply->readAll()); });
    connect(reply, &QNetworkReply::downloadProgress, this, [job, refresh](qint64 received, qint64 total) {
        if (total > 0)
        {
            job->partProgress = static_cast<double>(received) / total;
            refresh();
        }
    });
    connect(reply, &QNetworkReply::finished, this, [this, job, reply, file, target, next]() {
        reply->deleteLater();
        file->write(reply->readAll());
        file->close();
        if (reply->error() == QNetworkReply::NoError)
        {
            QFile::remove(target);
            file->rename(target);
            job->done[next] = true;
            downloadNextPart(job);
        }
        else
        {
            file->remove();
            ++job->attempts[next];
            QTimer::singleShot(2000, this, [this, job]() { downloadNextPart(job); }); // a moment, then again (or the next)
        }
    });
    refresh();
}

void AudiobooksPage::assemble(Job* job)
{
    job->assembling = true;
    job->current = -1;
    JoinRequest request;
    for (int i = 0; i < job->book.parts.size(); ++i)
    {
        request.inputs << PartFile(job->folder(), i);
        request.partTitles << job->book.parts[i].title;
    }
    request.output = bookOutputPath(job->book);
    request.title = job->book.title;
    request.album = job->book.title;
    request.artist = job->book.author;
    request.genre = QStringLiteral("Audiobook");
    request.comment = job->book.description.left(1500);
    QFile cover(QDir(job->folder()).filePath(QStringLiteral("cover.jpg")));
    if (cover.open(QIODevice::ReadOnly))
    {
        request.coverJpeg = cover.readAll();
    }
    QDir().mkpath(AudiobooksRoot());
    const QString id = job->book.id;
    QThread* thread = QThread::create([this, request, id]() {
        const QString error = JoinAudioToMp4(request);
        QMetaObject::invokeMethod(this, [this, error, id, request]() {
            Job* job = jobFor(id);
            if (!job)
            {
                return;
            }
            job->assembling = false;
            if (error.isEmpty())
            {
                QDir(job->folder()).removeRecursively();
                m_jobs.removeOne(job);
                delete job;
                emit message(tr("“%1” is in your audiobooks.").arg(request.title));
            }
            else
            {
                job->failed = true;
                emit message(tr("“%1” couldn't be saved: %2").arg(request.title, error));
            }
            if (m_stack->currentIndex() == 0)
            {
                refreshBooks();
            }
            updateBookButton();
        }, Qt::QueuedConnection);
    });
    connect(thread, &QThread::finished, thread, &QObject::deleteLater);
    thread->start(QThread::LowPriority);
}

bool AudiobooksPage::eventFilter(QObject* watched, QEvent* event)
{
    if (event->type() != QEvent::MouseButtonRelease || static_cast<QMouseEvent*>(event)->button() != Qt::LeftButton)
    {
        return QWidget::eventFilter(watched, event);
    }
    const QPoint pos = static_cast<QMouseEvent*>(event)->position().toPoint();
    if (watched == m_books->viewport())
    {
        const QModelIndex index = m_books->indexAt(pos);
        if (!index.isValid())
        {
            return false;
        }
        const int data = index.data(online_role::Data).toInt();
        if (data >= 0)
        {
            emit playFile(m_bookPaths.value(data));
        }
        else if (Job* job = m_jobs.value(-1 - data); job && job->failed)
        {
            job->failed = false;
            std::fill(job->attempts.begin(), job->attempts.end(), 0);
            downloadNextPart(job);
        }
        else if (job)
        {
            openBook(job->book);
        }
    }
    else if (watched == m_results->viewport())
    {
        const QModelIndex index = m_results->indexAt(pos);
        if (index.isValid())
        {
            openBook(m_found.value(index.data(online_role::Data).toInt()));
        }
    }
    return QWidget::eventFilter(watched, event);
}

} // namespace audioforge
