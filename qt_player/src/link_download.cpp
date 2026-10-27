#include "audioforge/link_download.hpp"
#include "audioforge/app_files.hpp"
#include "audioforge/audiobooks_page.hpp"
#include "audioforge/media_mux.hpp"
#include "audioforge/track_info.hpp"
#include "audioforge/widgets/online_views.hpp"

#include <QApplication>
#include <QButtonGroup>
#include <QCheckBox>
#include <QClipboard>
#include <QDate>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QMessageBox>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QProcess>
#include <QProgressBar>
#include <QPlainTextEdit>
#include <QPushButton>
#include <QRadioButton>
#include <QRegularExpression>
#include <QSettings>
#include <QStandardPaths>
#include <QThread>
#include <QVBoxLayout>

#include <memory>

namespace audioforge {

namespace {

const char kYtDlpDownload[] = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";

QString YtDlpPath()
{
    return QDir(AppDataFolder()).filePath(QStringLiteral("tools/yt-dlp.exe"));
}

QProcess* StartYtDlp(QObject* parent, const QStringList& arguments)
{
    auto* process = new QProcess(parent);
    QProcessEnvironment environment = QProcessEnvironment::systemEnvironment();
    environment.insert("PYTHONIOENCODING", "utf-8");
    environment.insert("PYTHONUTF8", "1");
    process->setProcessEnvironment(environment);
    process->setProgram(YtDlpPath());
    process->setArguments(QStringList{"--ignore-config", "--no-warnings"} + arguments);
    process->start();
    return process;
}

QString WithoutExtras(const QString& text)
{
    return WithoutTitleExtras(text);
}

const char kDropWordsKey[] = "titleDropWords";

QString CleanArtist(QString artist)
{
    artist.remove(QRegularExpression(QStringLiteral("\\s*-\\s*Topic$"), QRegularExpression::CaseInsensitiveOption));
    artist.remove(QRegularExpression(QStringLiteral("VEVO$")));
    artist.remove(QRegularExpression(QStringLiteral("\\s*(Official|Music)$"), QRegularExpression::CaseInsensitiveOption));
    return artist.simplified();
}

// The biggest JPEG thumbnail (the cover), else whatever it has.
QString ThumbnailUrl(const QJsonObject& info)
{
    QString best;
    int bestWidth = -1;
    for (const QJsonValue& value : info.value("thumbnails").toArray())
    {
        const QJsonObject thumbnail = value.toObject();
        const QString url = thumbnail.value("url").toString();
        const int width = thumbnail.value("width").toInt(thumbnail.value("preference").toInt() + 1000);
        if (QUrl(url).path().endsWith(QLatin1String(".jpg"), Qt::CaseInsensitive) && width > bestWidth)
        {
            best = url;
            bestWidth = width;
        }
    }
    return best.isEmpty() ? info.value("thumbnail").toString() : best;
}

int YearOf(const QJsonObject& info)
{
    if (const int year = info.value("release_year").toInt(); year > 0)
    {
        return year;
    }
    return info.value("upload_date").toString().left(4).toInt(); // "20240131"
}

} // namespace

QStringList DefaultTitleDropWords()
{
    return {"Official Video", "Official Music Video", "Official Audio", "Official Lyric Video", "Official Visualizer",
            "Music Video", "Lyric Video", "Music Visualization", "Visualizer", "Visualiser", "Lyrics", "Lyric",
            "Explicit", "Clean", "Audio", "Video", "HD", "HQ", "4K", "MV", "M/V", "Full Album", "Topic"};
}

QStringList TitleDropWords()
{
    const QSettings settings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge");
    return settings.contains(kDropWordsKey) ? settings.value(kDropWordsKey).toStringList() : DefaultTitleDropWords();
}

void SetTitleDropWords(const QStringList& words)
{
    QSettings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge").setValue(kDropWordsKey, words);
}

QString WithoutTitleExtras(const QString& title)
{
    QString text = title;
    QStringList alternatives;
    for (const QString& word : TitleDropWords())
    {
        if (!word.trimmed().isEmpty())
        {
            // "Official  Video" matches too: any run of spaces.
            alternatives << QRegularExpression::escape(word.simplified()).replace(QStringLiteral("\\ "), QStringLiteral("\\s+"));
        }
    }
    if (!alternatives.isEmpty())
    {
        const QString any = QStringLiteral("(?<![\\p{L}\\p{N}])(?:") + alternatives.join('|') + QStringLiteral(")(?![\\p{L}\\p{N}])");
        const auto options = QRegularExpression::CaseInsensitiveOption | QRegularExpression::UseUnicodePropertiesOption;
        // A bracketed part -- (), [], {} or 【】 alike -- holding one of the words.
        const QRegularExpression bracketed(QStringLiteral("\\s*[\\(\\[\\{【]([^\\)\\]\\}】]*") + any
                                               + QStringLiteral("[^\\)\\]\\}】]*)[\\)\\]\\}】]"), options);
        text.remove(bracketed);
        // ...or a whole " - Official Video" at the end.
        const QRegularExpression dashed(QStringLiteral("\\s+[-–—]\\s+") + any + QStringLiteral("\\s*$"), options);
        text.remove(dashed);
    }
    static const QRegularExpression kTrailing(QStringLiteral("\\s+[|｜].*$")); // "Song | Channel"
    text.remove(kTrailing);
    return text.simplified();
}

bool EditTitleDropWords(QWidget* parent)
{
    QDialog dialog(parent);
    dialog.setWindowTitle(QObject::tr("Words to drop from titles"));
    dialog.resize(440, 480);
    auto* l = new QVBoxLayout(&dialog);
    auto* note = new QLabel(QObject::tr("When a video's title has a part in brackets -- (), [], {} -- that holds one of these, "
                                        "it's left out of the song's name: \"Juice WRLD - Robbery (Official Video)\" is saved as "
                                        "Robbery by Juice WRLD. One per line."));
    note->setWordWrap(true);
    note->setObjectName("PageSubtitle");
    l->addWidget(note);
    auto* edit = new QPlainTextEdit(TitleDropWords().join('\n'));
    l->addWidget(edit, 1);
    auto* buttons = new QHBoxLayout();
    auto* reset = OnlineButton(QObject::tr("Reset to defaults"));
    QObject::connect(reset, &QPushButton::clicked, edit, [edit]() { edit->setPlainText(DefaultTitleDropWords().join('\n')); });
    buttons->addWidget(reset);
    buttons->addStretch();
    auto* cancel = OnlineButton(QObject::tr("Cancel"));
    QObject::connect(cancel, &QPushButton::clicked, &dialog, &QDialog::reject);
    buttons->addWidget(cancel);
    auto* save = OnlineButton(QObject::tr("Save"), "PrimaryButton");
    QObject::connect(save, &QPushButton::clicked, &dialog, &QDialog::accept);
    buttons->addWidget(save);
    l->addLayout(buttons);
    if (dialog.exec() != QDialog::Accepted)
    {
        return false;
    }
    QStringList words;
    for (const QString& line : edit->toPlainText().split('\n'))
    {
        if (!line.trimmed().isEmpty() && !words.contains(line.trimmed(), Qt::CaseInsensitive))
        {
            words << line.trimmed();
        }
    }
    SetTitleDropWords(words);
    return true;
}

SongName SongNameFromVideo(const QString& videoTitle, const QString& uploader, const QString& track, const QString& artist)
{
    if (!track.isEmpty() && !artist.isEmpty())
    {
        return {artist.section(',', 0, 0).trimmed(), WithoutExtras(track)}; // the site knows (YouTube Music)
    }
    const QString title = WithoutExtras(videoTitle);
    static const QRegularExpression kDash(QStringLiteral("\\s+[-–—]\\s+"));
    const QRegularExpressionMatch dash = kDash.match(title);
    if (dash.hasMatch() && dash.capturedStart() > 0)
    {
        return {title.left(dash.capturedStart()).trimmed(), WithoutExtras(title.mid(dash.capturedEnd()))};
    }
    return {CleanArtist(uploader), title};
}

LinkDownloadDialog::LinkDownloadDialog(QNetworkAccessManager* network, QWidget* parent) : QDialog(parent), m_network(network)
{
    setWindowTitle(tr("Download from link"));
    setWindowFlag(Qt::WindowContextHelpButtonHint, false);
    resize(620, 560);

    auto* l = new QVBoxLayout(this);
    l->setContentsMargins(22, 20, 22, 18);
    l->setSpacing(12);
    auto* heading = new QLabel(tr("Download from link"));
    heading->setObjectName("PanelTitle");
    l->addWidget(heading);
    auto* note = new QLabel(tr("Paste a link to a video or playlist (YouTube and many other sites). "
                               "Only download what you have the right to."));
    note->setObjectName("PageSubtitle");
    note->setWordWrap(true);
    l->addWidget(note);

    auto* linkRow = new QHBoxLayout();
    m_link = new QLineEdit();
    m_link->setPlaceholderText(QStringLiteral("https://"));
    m_link->setClearButtonEnabled(true);
    m_link->setAccessibleName(tr("Link"));
    linkRow->addWidget(m_link, 1);
    m_lookUpButton = OnlineButton(tr("Look up"));
    linkRow->addWidget(m_lookUpButton);
    l->addLayout(linkRow);
    connect(m_lookUpButton, &QPushButton::clicked, this, &LinkDownloadDialog::lookUp);
    connect(m_link, &QLineEdit::returnPressed, this, &LinkDownloadDialog::lookUp);

    m_sourceLabel = new QLabel();
    m_sourceLabel->setObjectName("SoftLabel");
    m_sourceLabel->setWordWrap(true);
    m_sourceLabel->hide();
    l->addWidget(m_sourceLabel);

    auto* saveRow = new QHBoxLayout();
    saveRow->setSpacing(16);
    saveRow->addWidget(new QLabel(tr("Save as:")));
    m_saveAs = new QButtonGroup(this);
    auto* songs = new QRadioButton(tr("Songs, into Music"));
    auto* book = new QRadioButton(tr("An audiobook, into Audiobooks"));
    songs->setChecked(true);
    m_saveAs->addButton(songs, 0);
    m_saveAs->addButton(book, 1);
    saveRow->addWidget(songs);
    saveRow->addWidget(book);
    saveRow->addStretch();
    l->addLayout(saveRow);
    m_oneBook = new QCheckBox(tr("Save the playlist as one book (its videos as chapters)"));
    m_oneBook->setChecked(true);
    m_oneBook->hide();
    l->addWidget(m_oneBook);
    connect(m_saveAs, &QButtonGroup::idClicked, this, [this]() { m_oneBook->setVisible(m_saveAs->checkedId() == 1 && m_found.size() > 1); });

    m_items = new QListWidget();
    m_items->setAccessibleName(tr("Items to download"));
    l->addWidget(m_items, 1);

    m_status = new QLabel();
    m_status->setObjectName("PageSubtitle");
    m_status->setWordWrap(true);
    l->addWidget(m_status);
    m_progress = new QProgressBar();
    m_progress->setTextVisible(false);
    m_progress->setRange(0, 1000);
    m_progress->hide();
    l->addWidget(m_progress);

    auto* buttons = new QHBoxLayout();
    buttons->addStretch();
    m_stopButton = OnlineButton(tr("Stop"));
    m_stopButton->hide();
    connect(m_stopButton, &QPushButton::clicked, this, &LinkDownloadDialog::stop);
    buttons->addWidget(m_stopButton);
    auto* close = OnlineButton(tr("Close"));
    close->setToolTip(tr("Downloads carry on while this is closed."));
    connect(close, &QPushButton::clicked, this, &QDialog::hide);
    buttons->addWidget(close);
    m_downloadButton = OnlineButton(tr("Download"), "PrimaryButton");
    m_downloadButton->setEnabled(false);
    connect(m_downloadButton, &QPushButton::clicked, this, &LinkDownloadDialog::startDownloads);
    buttons->addWidget(m_downloadButton);
    l->addLayout(buttons);
    setState(State::Idle);
}

LinkDownloadDialog::~LinkDownloadDialog()
{
    if (m_process)
    {
        m_process->disconnect(this);
        m_process->kill();
        m_process->waitForFinished(2000);
    }
}

void LinkDownloadDialog::setLink(const QString& url)
{
    if (m_state == State::Idle || m_state == State::Ready)
    {
        m_link->setText(url);
    }
}

QString LinkDownloadDialog::tempFolder() const
{
    return QDir(QStandardPaths::writableLocation(QStandardPaths::TempLocation)).filePath(QStringLiteral("AudioForge/downloads"));
}

void LinkDownloadDialog::setState(State state)
{
    m_state = state;
    const bool busy = state == State::Installing || state == State::LookingUp || state == State::Downloading;
    m_lookUpButton->setEnabled(!busy);
    m_link->setEnabled(!busy);
    m_downloadButton->setEnabled(state == State::Ready && !m_found.isEmpty());
    m_stopButton->setVisible(state == State::Downloading);
    m_progress->setVisible(busy);
    for (QAbstractButton* button : m_saveAs->buttons())
    {
        button->setEnabled(!busy);
    }
    m_oneBook->setEnabled(!busy);
}

void LinkDownloadDialog::setStatus(const QString& text, double progress)
{
    m_status->setText(text);
    if (progress < 0)
    {
        m_progress->setRange(0, 0); // busy
    }
    else
    {
        m_progress->setRange(0, 1000);
        m_progress->setValue(static_cast<int>(progress * 1000));
    }
}

void LinkDownloadDialog::lookUp()
{
    const QString url = m_link->text().trimmed();
    if (!url.startsWith(QLatin1String("http://"), Qt::CaseInsensitive) && !url.startsWith(QLatin1String("https://"), Qt::CaseInsensitive))
    {
        m_status->setText(tr("Paste a link that starts with https://"));
        return;
    }
    ensureYtDlp([this]() { updateYtDlp([this]() { probe(); }); });
}

void LinkDownloadDialog::ensureYtDlp(const std::function<void()>& then)
{
    if (QFileInfo::exists(YtDlpPath()))
    {
        then();
        return;
    }
    const auto answer = QMessageBox::question(
        this, tr("Download yt-dlp?"),
        tr("Download from link uses yt-dlp, a free open-source downloader (about 18 MB), to read links. "
           "AudioForge downloads it from its GitHub page and keeps it up to date.\n\nDownload it now?"));
    if (answer != QMessageBox::Yes)
    {
        return;
    }
    setState(State::Installing);
    setStatus(tr("Downloading yt-dlp…"), 0.0);
    QDir().mkpath(QFileInfo(YtDlpPath()).absolutePath());
    auto file = std::make_shared<QFile>(YtDlpPath() + QStringLiteral(".part"));
    if (!file->open(QIODevice::WriteOnly))
    {
        setState(State::Idle);
        m_status->setText(tr("Couldn't save yt-dlp in %1.").arg(QDir::toNativeSeparators(QFileInfo(YtDlpPath()).absolutePath())));
        return;
    }
    QNetworkReply* reply = NetworkGet(m_network, QUrl(QString::fromLatin1(kYtDlpDownload)));
    reply->setReadBufferSize(1024 * 1024);
    connect(reply, &QNetworkReply::readyRead, this, [reply, file]() { file->write(reply->readAll()); });
    connect(reply, &QNetworkReply::downloadProgress, this, [this](qint64 received, qint64 total) {
        if (total > 0)
        {
            setStatus(tr("Downloading yt-dlp…"), static_cast<double>(received) / total);
        }
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply, file, then]() {
        reply->deleteLater();
        file->write(reply->readAll());
        file->close();
        if (reply->error() != QNetworkReply::NoError || file->size() < 1000000)
        {
            file->remove();
            setState(State::Idle);
            m_status->setText(tr("yt-dlp couldn't be downloaded. Check your connection and try again."));
            return;
        }
        QFile::remove(YtDlpPath());
        file->rename(YtDlpPath());
        QSettings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge")
            .setValue("ytdlpCheckedOn", QDate::currentDate().toString(Qt::ISODate)); // just downloaded: the latest
        setState(State::Idle);
        then();
    });
}

void LinkDownloadDialog::updateYtDlp(const std::function<void()>& then)
{
    // Sites change; yt-dlp keeps up. Once a day it updates itself first.
    QSettings settings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge");
    const QString today = QDate::currentDate().toString(Qt::ISODate);
    if (settings.value("ytdlpCheckedOn").toString() == today)
    {
        then();
        return;
    }
    settings.setValue("ytdlpCheckedOn", today);
    setState(State::Installing);
    setStatus(tr("Updating yt-dlp…"));
    QProcess* process = StartYtDlp(this, {"-U"});
    connect(process, &QProcess::finished, this, [this, process, then]() {
        process->deleteLater();
        setState(State::Idle);
        then(); // updated or not, it's usable
    });
}

void LinkDownloadDialog::probe()
{
    setState(State::LookingUp);
    setStatus(tr("Looking up the link…"));
    m_found.clear();
    m_items->clear();
    m_sourceLabel->hide();
    QProcess* process = StartYtDlp(this, {"--flat-playlist", "-J", m_link->text().trimmed()});
    m_process = process;
    connect(process, &QProcess::finished, this, [this, process](int code) {
        process->deleteLater();
        const QByteArray output = process->readAllStandardOutput();
        const QString errors = QString::fromUtf8(process->readAllStandardError()).trimmed();
        setState(State::Idle);
        const QJsonObject info = QJsonDocument::fromJson(output).object();
        if (code != 0 || info.isEmpty())
        {
            m_status->setText(errors.isEmpty() ? tr("Nothing to download was found at this link.")
                                               : tr("Nothing to download: %1").arg(errors.section('\n', -1).remove(QStringLiteral("ERROR: "))));
            return;
        }
        if (info.value("_type").toString() == QLatin1String("playlist"))
        {
            m_playlistTitle = info.value("title").toString();
            m_playlistUploader = info.value("uploader").toString(info.value("channel").toString());
            for (const QJsonValue& value : info.value("entries").toArray())
            {
                const QJsonObject entry = value.toObject();
                QString url = entry.value("url").toString();
                if (!url.startsWith(QLatin1String("http")))
                {
                    url = entry.value("webpage_url").toString(url);
                }
                m_found.append({url, entry.value("title").toString(), entry.value("duration").toInt()});
            }
        }
        else
        {
            m_playlistTitle.clear();
            m_playlistUploader = info.value("uploader").toString(info.value("channel").toString());
            m_found.append({info.value("webpage_url").toString(m_link->text().trimmed()), info.value("title").toString(),
                            info.value("duration").toInt()});
        }
        showItems();
    });
}

void LinkDownloadDialog::showItems()
{
    m_items->clear();
    for (const Item& item : m_found)
    {
        auto* row = new QListWidgetItem(item.seconds > 0 ? QStringLiteral("%1   ·   %2").arg(item.title, FormatDuration(item.seconds)) : item.title);
        row->setFlags(row->flags() | Qt::ItemIsUserCheckable);
        row->setCheckState(Qt::Checked); // a playlist: untick what you don't want
        m_items->addItem(row);
    }
    m_sourceLabel->setText(m_playlistTitle.isEmpty() ? m_playlistUploader
                                                     : tr("Playlist “%1” · %2 items").arg(m_playlistTitle).arg(m_found.size()));
    m_sourceLabel->setVisible(!m_sourceLabel->text().isEmpty());
    m_oneBook->setVisible(m_saveAs->checkedId() == 1 && m_found.size() > 1);
    m_status->setText(m_found.isEmpty() ? tr("This playlist is empty.")
                                        : m_found.size() == 1 ? tr("Ready to download.") : tr("Pick the items to download."));
    setState(State::Ready);
}

void LinkDownloadDialog::startDownloads()
{
    m_queue.clear();
    for (int i = 0; i < m_items->count(); ++i)
    {
        if (m_items->item(i)->checkState() == Qt::Checked)
        {
            m_queue << i;
        }
    }
    if (m_queue.isEmpty())
    {
        m_status->setText(tr("Tick at least one item."));
        return;
    }
    m_asBook = m_saveAs->checkedId() == 1;
    m_asOneBook = m_asBook && m_found.size() > 1 && m_oneBook->isChecked() && m_queue.size() > 1;
    m_total = m_queue.size();
    m_saved = 0;
    m_failed = 0;
    m_bookParts.clear();
    m_bookCover.clear();
    QDir(tempFolder()).removeRecursively();
    QDir().mkpath(tempFolder());
    setState(State::Downloading);
    downloadNext();
}

void LinkDownloadDialog::downloadNext()
{
    if (m_state != State::Downloading)
    {
        return;
    }
    if (m_queue.isEmpty())
    {
        if (m_asOneBook && !m_bookParts.isEmpty())
        {
            saveBook();
            return;
        }
        setState(State::Ready);
        const QString done = m_failed == 0 ? tr("Done: %1 saved.").arg(m_saved) : tr("Done: %1 saved, %2 couldn't be downloaded.").arg(m_saved).arg(m_failed);
        m_status->setText(done);
        emit message(done);
        QDir(tempFolder()).removeRecursively();
        return;
    }
    const int index = m_queue.takeFirst();
    const Item item = m_found[index];
    const int number = m_total - m_queue.size();
    const QString label = m_total > 1 ? tr("Downloading %1 of %2: %3").arg(number).arg(m_total).arg(item.title)
                                      : tr("Downloading %1").arg(item.title);
    setStatus(label, 0.0);
    // The best audio that's already AAC (M4A) -- it goes in as it is.
    QProcess* process = StartYtDlp(this, {"--no-playlist", "--newline", "--no-mtime", "--dump-json", "--no-simulate",
                                          "-f", "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio",
                                          "-o", QDir(tempFolder()).filePath(QStringLiteral("%(id)s.%(ext)s")), item.url});
    m_process = process;
    auto info = std::make_shared<QJsonObject>();
    connect(process, &QProcess::readyReadStandardOutput, this, [this, process, info, label]() {
        while (process->canReadLine())
        {
            const QByteArray line = process->readLine().trimmed();
            if (line.startsWith('{'))
            {
                *info = QJsonDocument::fromJson(line).object();
                continue;
            }
            static const QRegularExpression kPercent(QStringLiteral("\\[download\\]\\s+([\\d.]+)%"));
            const QRegularExpressionMatch match = kPercent.match(QString::fromUtf8(line));
            if (match.hasMatch())
            {
                setStatus(label, match.captured(1).toDouble() / 100.0 * 0.95);
            }
        }
    });
    connect(process, &QProcess::finished, this, [this, process, info, item](int code) {
        process->deleteLater();
        if (m_state != State::Downloading)
        {
            return; // stopped
        }
        const QString id = info->value("id").toString();
        QString file;
        if (code == 0 && !id.isEmpty())
        {
            for (const QFileInfo& entry : QDir(tempFolder()).entryInfoList({id + QStringLiteral(".*")}, QDir::Files))
            {
                if (entry.suffix() != QLatin1String("part") && entry.suffix() != QLatin1String("json"))
                {
                    file = entry.absoluteFilePath();
                }
            }
        }
        if (file.isEmpty())
        {
            ++m_failed;
            emit message(tr("“%1” couldn't be downloaded.").arg(item.title));
            downloadNext();
            return;
        }
        finishItem(*info, file);
    });
}

void LinkDownloadDialog::finishItem(const QJsonObject& info, const QString& file)
{
    // The cover first (from the thumbnail), then the file is written.
    const QString thumbnail = ThumbnailUrl(info);
    auto save = [this, info, file](const QByteArray& thumbnailBytes) {
        if (m_state != State::Downloading)
        {
            return;
        }
        const QByteArray cover = SquareJpeg(thumbnailBytes);
        if (m_asOneBook)
        {
            m_bookParts.append({info, file});
            if (m_bookCover.isEmpty())
            {
                m_bookCover = cover;
            }
            downloadNext();
            return;
        }
        JoinRequest request;
        request.inputs = {file};
        request.coverJpeg = cover;
        request.year = YearOf(info);
        const QString title = info.value("title").toString();
        const QString uploader = info.value("uploader").toString(info.value("channel").toString());
        if (m_asBook)
        {
            // An audiobook keeps the video's chapters.
            for (const QJsonValue& value : info.value("chapters").toArray())
            {
                const QJsonObject chapter = value.toObject();
                request.chapters.append({chapter.value("start_time").toDouble(), chapter.value("title").toString()});
            }
            request.title = title;
            request.album = title;
            request.artist = CleanArtist(uploader);
            request.genre = QStringLiteral("Audiobook");
            request.comment = info.value("webpage_url").toString();
            request.output = QDir(AudiobooksRoot()).filePath(SafeFileName(title) + QStringLiteral(".m4b"));
            QDir().mkpath(AudiobooksRoot());
        }
        else
        {
            const SongName name = SongNameFromVideo(title, uploader, info.value("track").toString(), info.value("artist").toString());
            request.title = name.title;
            request.artist = name.artist;
            request.album = info.value("album").toString();
            request.comment = info.value("webpage_url").toString();
            const QString folder = musicFolder ? musicFolder() : QStandardPaths::writableLocation(QStandardPaths::MusicLocation);
            const QString base = name.artist.isEmpty() ? name.title : name.artist + QStringLiteral(" - ") + name.title;
            request.output = QDir(folder).filePath(SafeFileName(base) + QStringLiteral(".m4a"));
        }
        setStatus(tr("Saving %1…").arg(request.title), 0.97);
        const bool book = m_asBook;
        QThread* thread = QThread::create([this, request, book, file]() {
            const QString error = JoinAudioToMp4(request);
            QFile::remove(file);
            QMetaObject::invokeMethod(this, [this, error, request, book]() {
                if (error.isEmpty())
                {
                    ++m_saved;
                    book ? emit bookSaved(request.output) : emit songSaved(request.output);
                }
                else
                {
                    ++m_failed;
                    emit message(tr("“%1” couldn't be saved: %2").arg(request.title, error));
                }
                downloadNext();
            }, Qt::QueuedConnection);
        });
        connect(thread, &QThread::finished, thread, &QObject::deleteLater);
        thread->start(QThread::LowPriority);
    };
    if (thumbnail.isEmpty())
    {
        save(QByteArray());
        return;
    }
    QNetworkReply* reply = NetworkGet(m_network, QUrl(thumbnail));
    connect(reply, &QNetworkReply::finished, this, [reply, save]() {
        reply->deleteLater();
        save(reply->error() == QNetworkReply::NoError ? reply->readAll() : QByteArray());
    });
}

void LinkDownloadDialog::saveBook()
{
    // The playlist as one book: its videos in order, each a chapter.
    JoinRequest request;
    for (const Downloaded& part : m_bookParts)
    {
        request.inputs << part.file;
        request.partTitles << part.info.value("title").toString();
    }
    const QString title = m_playlistTitle.isEmpty() ? m_bookParts.first().info.value("title").toString() : m_playlistTitle;
    request.title = title;
    request.album = title;
    request.artist = CleanArtist(m_playlistUploader);
    request.genre = QStringLiteral("Audiobook");
    request.coverJpeg = m_bookCover;
    request.output = QDir(AudiobooksRoot()).filePath(SafeFileName(title) + QStringLiteral(".m4b"));
    QDir().mkpath(AudiobooksRoot());
    setStatus(tr("Putting the book together…"));
    const QStringList files = request.inputs;
    QThread* thread = QThread::create([this, request, files]() {
        const QString error = JoinAudioToMp4(request);
        for (const QString& file : files)
        {
            QFile::remove(file);
        }
        QMetaObject::invokeMethod(this, [this, error, request]() {
            m_bookParts.clear();
            if (error.isEmpty())
            {
                m_saved = 1;
                emit bookSaved(request.output);
            }
            else
            {
                emit message(tr("“%1” couldn't be saved: %2").arg(request.title, error));
            }
            downloadNext(); // (the queue is empty: wraps up)
        }, Qt::QueuedConnection);
    });
    connect(thread, &QThread::finished, thread, &QObject::deleteLater);
    thread->start(QThread::LowPriority);
}

void LinkDownloadDialog::stop()
{
    m_queue.clear();
    m_bookParts.clear();
    setState(State::Ready);
    if (m_process)
    {
        m_process->kill();
    }
    m_status->setText(m_saved > 0 ? tr("Stopped. %1 saved.").arg(m_saved) : tr("Stopped."));
}

} // namespace audioforge
