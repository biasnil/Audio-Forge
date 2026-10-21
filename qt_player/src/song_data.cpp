#include "audioforge/song_data.hpp"
#include "audioforge/app_files.hpp"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonObject>
#include <QThread>
#include <QThreadPool>
#include <QTimer>

#include <cmath>

namespace audioforge {

namespace {

QJsonObject ToJson(const SongAnalysis& a)
{
    QJsonObject o;
    o["version"] = a.version;
    o["duration"] = a.duration;
    o["bpm"] = a.bpm;
    o["bpmConfidence"] = a.bpmConfidence;
    o["firstBeat"] = a.firstBeat;
    o["keyIndex"] = a.keyIndex;
    o["keyConfidence"] = a.keyConfidence;
    o["audioStart"] = a.audioStart;
    o["audioEnd"] = a.audioEnd;
    o["outroStart"] = a.outroStart;
    return o;
}

const char* const kGenreStatusNames[] = {"OK", "UNKNOWN", "NOT_MUSIC", "TOO_SHORT", "FAILED"}; // Android's names

QJsonObject GenreToJson(const DetectedGenre& g)
{
    QJsonObject o;
    o["model"] = g.model;
    o["duration"] = g.duration;
    o["status"] = QString::fromLatin1(kGenreStatusNames[static_cast<int>(g.status)]);
    if (!g.broad.isEmpty()) o["broad"] = g.broad;
    if (!g.subgenre.isEmpty()) o["subgenre"] = g.subgenre;
    o["confidence"] = g.confidence;
    QJsonArray top;
    for (const auto& [label, score] : g.top)
    {
        top.append(QJsonArray{label, static_cast<double>(score)});
    }
    o["top"] = top;
    return o;
}

std::optional<DetectedGenre> GenreFromJson(const QJsonObject& o)
{
    DetectedGenre g;
    const QString status = o["status"].toString();
    int index = -1;
    for (int i = 0; i < 5; ++i)
    {
        if (status == QLatin1String(kGenreStatusNames[i]))
        {
            index = i;
        }
    }
    if (index < 0)
    {
        return std::nullopt;
    }
    g.status = static_cast<DetectedGenre::Status>(index);
    g.model = o["model"].toString();
    g.duration = o["duration"].toDouble();
    g.broad = o["broad"].toString();
    g.subgenre = o["subgenre"].toString();
    g.confidence = o["confidence"].toDouble();
    for (const QJsonValue& entry : o["top"].toArray())
    {
        const QJsonArray pair = entry.toArray();
        g.top.append({pair.at(0).toString(), static_cast<float>(pair.at(1).toDouble())});
    }
    return g;
}

SongAnalysis FromJson(const QJsonObject& o)
{
    SongAnalysis a;
    a.version = o["version"].toInt();
    a.duration = o["duration"].toDouble();
    a.bpm = o["bpm"].toDouble();
    a.bpmConfidence = o["bpmConfidence"].toDouble();
    a.firstBeat = o["firstBeat"].toDouble();
    a.keyIndex = o["keyIndex"].toInt(-1);
    a.keyConfidence = o["keyConfidence"].toDouble();
    a.audioStart = o["audioStart"].toDouble();
    a.audioEnd = o["audioEnd"].toDouble();
    a.outroStart = o["outroStart"].toDouble();
    return a;
}

} // namespace


SongDataStore::SongDataStore(QObject* parent) : QObject(parent)
{
    m_folder = AppDataFolder(); // next to AudioForge.ini


    m_saveTimer = new QTimer(this);
    m_saveTimer->setSingleShot(true);
    m_saveTimer->setInterval(2000);
    connect(m_saveTimer, &QTimer::timeout, this, &SongDataStore::saveNow);
}

SongDataStore::~SongDataStore()
{
    saveNow();
}

void SongDataStore::load()
{
    const QJsonObject songs = ReadJsonFile(m_folder + "/analysis.json")["songs"].toObject();
    for (auto it = songs.begin(); it != songs.end(); ++it)
    {
        SongAnalysis a = FromJson(it.value().toObject());
        if (a.version == SongAnalysis::kCurrentVersion)
        {
            m_analysis.insert(it.key(), a);
        }
    }

    const QJsonObject statsFile = ReadJsonFile(m_folder + "/songstats.json");
    const QJsonObject resume = statsFile["resumePositions"].toObject();
    for (auto it = resume.begin(); it != resume.end(); ++it)
    {
        m_resume.insert(it.key(), it.value().toDouble());
    }
    const QJsonObject stats = statsFile["songs"].toObject();
    for (auto it = stats.begin(); it != stats.end(); ++it)
    {
        const QJsonObject o = it.value().toObject();
        SongStats s;
        s.playCount = o["playCount"].toInt();
        s.lastPlayedMs = static_cast<qint64>(o["lastPlayed"].toDouble());
        s.loved = o["loved"].toBool();
        s.lyricsOffsetMs = o["lyricsOffsetMs"].toInt();
        s.played = o["played"].toBool();
        if (!s.isEmpty())
        {
            m_stats.insert(it.key(), s);
        }
    }

    const QJsonObject genres = ReadJsonFile(m_folder + "/genre.json")["songs"].toObject();
    for (auto it = genres.begin(); it != genres.end(); ++it)
    {
        if (std::optional<DetectedGenre> g = GenreFromJson(it.value().toObject()))
        {
            m_genres.insert(it.key(), *g);
        }
    }
}

void SongDataStore::saveNow()
{
    m_saveTimer->stop();
    if (m_analysisDirty)
    {
        QJsonObject songs;
        for (auto it = m_analysis.constBegin(); it != m_analysis.constEnd(); ++it)
        {
            songs[it.key()] = ToJson(it.value());
        }
        WriteJsonFile(m_folder + "/analysis.json", QJsonObject{{"songs", songs}});
        m_analysisDirty = false;
    }
    if (m_statsDirty)
    {
        QJsonObject resume;
        for (auto it = m_resume.constBegin(); it != m_resume.constEnd(); ++it)
        {
            resume[it.key()] = it.value();
        }
        QJsonObject songs;
        for (auto it = m_stats.constBegin(); it != m_stats.constEnd(); ++it)
        {
            const SongStats& s = it.value();
            QJsonObject o;
            if (s.playCount > 0) o["playCount"] = s.playCount;
            if (s.lastPlayedMs > 0) o["lastPlayed"] = static_cast<double>(s.lastPlayedMs);
            if (s.loved) o["loved"] = true;
            if (s.lyricsOffsetMs != 0) o["lyricsOffsetMs"] = s.lyricsOffsetMs;
            if (s.played) o["played"] = true;
            songs[it.key()] = o;
        }
        WriteJsonFile(m_folder + "/songstats.json", QJsonObject{{"resumePositions", resume}, {"songs", songs}});
        m_statsDirty = false;
    }
    if (m_genresDirty)
    {
        QJsonObject songs;
        for (auto it = m_genres.constBegin(); it != m_genres.constEnd(); ++it)
        {
            songs[it.key()] = GenreToJson(it.value());
        }
        WriteJsonFile(m_folder + "/genre.json", QJsonObject{{"songs", songs}});
        m_genresDirty = false;
    }
}

const DetectedGenre* SongDataStore::genreFor(const TrackInfo& track) const
{
    auto it = m_genres.constFind(track.path);
    if (it == m_genres.constEnd() || it->model != QLatin1String(kGenreModelId))
    {
        return nullptr;
    }
    // Tag edits don't change the audio's length; replacing the file usually does.
    if (track.durationSeconds > 0.0 && it->duration > 0.0 && std::abs(it->duration - track.durationSeconds) > 1.5)
    {
        return nullptr;
    }
    return &it.value();
}

void SongDataStore::storeGenre(const QString& path, const DetectedGenre& genre)
{
    m_genres.insert(path, genre);
    m_genresDirty = true;
    scheduleSave();
}

int SongDataStore::detectedGenreCount(const QVector<TrackInfo>& tracks) const
{
    int count = 0;
    for (const TrackInfo& t : tracks)
    {
        if (genreFor(t))
        {
            ++count;
        }
    }
    return count;
}

const SongAnalysis* SongDataStore::analysisFor(const TrackInfo& track) const
{
    auto it = m_analysis.constFind(track.path);
    if (it == m_analysis.constEnd())
    {
        return nullptr;
    }
    // Tag edits don't change the audio's length; replacing the file usually does.
    if (track.durationSeconds > 0.0 && std::abs(it->duration - track.durationSeconds) > 1.0)
    {
        return nullptr;
    }
    return &it.value();
}

void SongDataStore::storeAnalysis(const QString& path, const SongAnalysis& analysis)
{
    m_analysis.insert(path, analysis);
    m_analysisDirty = true;
    scheduleSave();
}

int SongDataStore::analyzedCount(const QVector<TrackInfo>& tracks) const
{
    int count = 0;
    for (const TrackInfo& t : tracks)
    {
        if (analysisFor(t))
        {
            ++count;
        }
    }
    return count;
}

void SongDataStore::setResumePosition(const QString& path, double seconds)
{
    const double old = m_resume.value(path, -1.0);
    if (seconds < 0.0)
    {
        if (old < 0.0)
        {
            return;
        }
        m_resume.remove(path);
    }
    else
    {
        if (std::abs(old - seconds) < 0.5)
        {
            return;
        }
        m_resume.insert(path, seconds);
    }
    m_statsDirty = true;
    scheduleSave();
}

void SongDataStore::recordPlay(const QString& path)
{
    SongStats s = m_stats.value(path);
    ++s.playCount;
    s.lastPlayedMs = QDateTime::currentMSecsSinceEpoch();
    setStats(path, s);
}

void SongDataStore::setLoved(const QString& path, bool loved)
{
    SongStats s = m_stats.value(path);
    if (s.loved == loved)
    {
        return;
    }
    s.loved = loved;
    setStats(path, s);
}

void SongDataStore::setLyricsOffset(const QString& path, int offsetMs)
{
    SongStats s = m_stats.value(path);
    if (s.lyricsOffsetMs == offsetMs)
    {
        return;
    }
    s.lyricsOffsetMs = offsetMs;
    setStats(path, s);
}

void SongDataStore::setPlayed(const QString& path, bool played)
{
    if (played)
    {
        setResumePosition(path, -1.0); // listened to: starts from the top next time
    }
    SongStats s = m_stats.value(path);
    if (s.played == played)
    {
        return;
    }
    s.played = played;
    setStats(path, s);
}

void SongDataStore::setStats(const QString& path, const SongStats& stats)
{
    if (stats.isEmpty())
    {
        m_stats.remove(path);
    }
    else
    {
        m_stats.insert(path, stats);
    }
    m_statsDirty = true;
    scheduleSave();
}

void SongDataStore::scheduleSave()
{
    m_saveTimer->start(); // restarts -- the latest state is what gets written
}

// --- LibraryAnalyzer ----------------------------------------------------------

LibraryAnalyzer::LibraryAnalyzer(SongDataStore* store, QObject* parent)
    : QObject(parent), m_store(store), m_pool(new QThreadPool(this))
{
    // Two at a time on a typical PC, one on a 2-core laptop -- always leaving
    // a core for the UI and audio. Low priority so playback never stutters
    // while a big library is analyzed.
    m_pool->setMaxThreadCount(qBound(1, QThread::idealThreadCount() / 2, 2));
    m_pool->setThreadPriority(QThread::LowPriority);
}

LibraryAnalyzer::~LibraryAnalyzer()
{
    cancel();
    m_pool->waitForDone(); // workers only touch their own data, but must not outlive the pool
}

void LibraryAnalyzer::start(const QVector<TrackInfo>& tracks)
{
    if (isRunning())
    {
        return;
    }

    QVector<TrackInfo> todo;
    for (const TrackInfo& t : tracks)
    {
        if (!m_store->analysisFor(t))
        {
            todo << t;
        }
    }
    if (todo.isEmpty())
    {
        emit finished(true);
        return;
    }

    ++m_generation;
    m_cancelled = std::make_shared<std::atomic<bool>>(false);
    m_done = 0;
    m_total = todo.size();
    emit progressChanged(m_done, m_total);

    const int generation = m_generation;
    auto cancelled = m_cancelled;
    for (const TrackInfo& t : todo)
    {
        const QString path = t.path;
        m_pool->start([this, generation, cancelled, path]() {
            std::optional<SongAnalysis> result;
            if (!cancelled->load())
            {
                result = AnalyzeSongFile(path);
            }
            QMetaObject::invokeMethod(this, [this, generation, path, result]() { onResult(generation, path, result); },
                                      Qt::QueuedConnection);
        });
    }
}

void LibraryAnalyzer::cancel()
{
    if (!isRunning())
    {
        return;
    }
    m_cancelled->store(true);
    m_pool->clear(); // drops the songs that haven't started; the two in progress finish and are kept
    const bool wasRunning = m_total > 0;
    m_total = 0;
    m_done = 0;
    ++m_generation; // results still in flight are stored, but no longer counted
    m_store->saveNow();
    if (wasRunning)
    {
        emit finished(false);
    }
}

void LibraryAnalyzer::onResult(int generation, const QString& path, const std::optional<SongAnalysis>& result)
{
    if (result)
    {
        m_store->storeAnalysis(path, *result);
    }
    if (generation != m_generation)
    {
        return; // from a cancelled run
    }
    ++m_done;
    emit progressChanged(m_done, m_total);
    if (m_done >= m_total)
    {
        m_total = 0;
        m_done = 0;
        m_store->saveNow();
        emit finished(true);
    }
}

// --- GenreAnalyzer --------------------------------------------------------------

GenreAnalyzer::GenreAnalyzer(SongDataStore* store, QObject* parent)
    : QObject(parent), m_store(store), m_pool(new QThreadPool(this))
{
    m_pool->setMaxThreadCount(1); // one song at a time; the classifier isn't shared between threads
    m_pool->setThreadPriority(QThread::LowPriority);
}

GenreAnalyzer::~GenreAnalyzer()
{
    cancel();
    m_pool->waitForDone();
}

void GenreAnalyzer::start(const QVector<TrackInfo>& tracks)
{
    if (isRunning())
    {
        return;
    }
    QVector<TrackInfo> todo;
    for (const TrackInfo& t : tracks)
    {
        if (!m_store->genreFor(t))
        {
            todo << t;
        }
    }
    if (todo.isEmpty())
    {
        emit finished(true);
        return;
    }

    ++m_generation;
    m_cancelled = std::make_shared<std::atomic<bool>>(false);
    m_done = 0;
    m_total = todo.size();
    emit progressChanged(m_done, m_total);

    // One model for the whole run, loaded by the first song (on the worker thread).
    auto classifier = std::make_shared<GenreClassifier>();
    const int generation = m_generation;
    auto cancelled = m_cancelled;
    for (const TrackInfo& t : todo)
    {
        const QString path = t.path;
        const double duration = t.durationSeconds;
        m_pool->start([this, generation, cancelled, classifier, path, duration]() {
            if (cancelled->load())
            {
                return;
            }
            const DetectedGenre result = classifier->detect(path, duration);
            QMetaObject::invokeMethod(this, [this, generation, path, result]() { onResult(generation, path, result); },
                                      Qt::QueuedConnection);
        });
    }
}

void GenreAnalyzer::cancel()
{
    if (!isRunning())
    {
        return;
    }
    m_cancelled->store(true);
    m_pool->clear();
    m_total = 0;
    m_done = 0;
    ++m_generation;
    m_store->saveNow();
    emit finished(false);
}

void GenreAnalyzer::onResult(int generation, const QString& path, const DetectedGenre& result)
{
    m_store->storeGenre(path, result); // kept even from a stopped run
    if (generation != m_generation)
    {
        return;
    }
    ++m_done;
    emit progressChanged(m_done, m_total);
    if (m_done >= m_total)
    {
        m_total = 0;
        m_done = 0;
        m_store->saveNow();
        emit finished(true);
    }
}

} // namespace audioforge
