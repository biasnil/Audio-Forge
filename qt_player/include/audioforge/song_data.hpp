#pragma once

#include <QHash>
#include <QObject>
#include <QString>
#include <QVector>

#include <atomic>
#include <memory>

#include "audioforge/genre.hpp"
#include "audioforge/song_analysis.hpp"
#include "audioforge/track_info.hpp"

class QThreadPool;
class QTimer;

namespace audioforge {

// What the app learns about a song while you use it (the Android/iOS SongStats).
struct SongStats
{
    int playCount = 0;
    qint64 lastPlayedMs = 0; // epoch ms of the last counted play; 0 = never
    bool loved = false;
    int lyricsOffsetMs = 0;  // + shows the synced lines later
    bool played = false;     // podcast episodes: listened to the end, or marked as played

    bool isEmpty() const { return playCount == 0 && lastPlayedMs == 0 && !loved && lyricsOffsetMs == 0 && !played; }
};

// Per-song data the app learns over time, keyed by file path: the key/BPM
// analysis, play counts, Loved songs, lyrics offsets and the resume position
// of long tracks. Saved as JSON next to AudioForge.ini (analysis.json,
// songstats.json) a moment after each change.
class SongDataStore : public QObject
{
public:
    explicit SongDataStore(QObject* parent = nullptr);
    ~SongDataStore() override;

    void load();
    void saveNow(); // writes any pending changes immediately (app closing)

    // The analysis of this track, if there is one for its current audio
    // (replacing the file usually changes its length, which invalidates it).
    const SongAnalysis* analysisFor(const TrackInfo& track) const;
    void storeAnalysis(const QString& path, const SongAnalysis& analysis);
    int analyzedCount(const QVector<TrackInfo>& tracks) const;
    const QHash<QString, SongAnalysis>& allAnalysis() const { return m_analysis; }

    // Long tracks (audiobooks, mixes): where playback stopped, in seconds. -1 = none.
    double resumePosition(const QString& path) const { return m_resume.value(path, -1.0); }
    void setResumePosition(const QString& path, double seconds); // < 0 clears it

    // Play counts, Loved, lyrics offsets.
    SongStats statsFor(const QString& path) const { return m_stats.value(path); }
    const QHash<QString, SongStats>& allStats() const { return m_stats; }
    void recordPlay(const QString& path);
    bool isLoved(const QString& path) const { return m_stats.value(path).loved; }
    void setLoved(const QString& path, bool loved);
    void setLyricsOffset(const QString& path, int offsetMs);
    // Podcasts: marking an episode played also forgets where it was left.
    void setPlayed(const QString& path, bool played);

    // Detected genres (genre.json): the result for this track's current file
    // and the current model, if there is one. Failed songs aren't retried
    // until the file changes.
    const DetectedGenre* genreFor(const TrackInfo& track) const;
    // The genre to show: the tag, or the detected one when the tag is empty or "Music"-like (ShownGenre).
    QString shownGenre(const TrackInfo& track) const { return ShownGenre(track.genre, genreFor(track)); }
    void storeGenre(const QString& path, const DetectedGenre& genre);
    int detectedGenreCount(const QVector<TrackInfo>& tracks) const;

private:
    void scheduleSave();
    void setStats(const QString& path, const SongStats& stats);

    QString m_folder;
    QHash<QString, SongAnalysis> m_analysis;
    QHash<QString, double> m_resume;
    QHash<QString, SongStats> m_stats;
    QHash<QString, DetectedGenre> m_genres;
    bool m_analysisDirty = false;
    bool m_statsDirty = false;
    bool m_genresDirty = false;
    QTimer* m_saveTimer = nullptr;
};

// Runs AnalyzeSongFile over the library in the background, two songs at a
// time (each takes a second or two of CPU), reporting results back on the
// UI thread. A port of the iOS LibraryAnalyzer.
class LibraryAnalyzer : public QObject
{
    Q_OBJECT

public:
    explicit LibraryAnalyzer(SongDataStore* store, QObject* parent = nullptr);
    ~LibraryAnalyzer() override;

    bool isRunning() const { return m_total > 0; }
    int done() const { return m_done; }
    int total() const { return m_total; }

    // Analyzes every track that has no current analysis. Emits finished(true)
    // straight away if there's nothing to do.
    void start(const QVector<TrackInfo>& tracks);
    void cancel();

signals:
    void progressChanged(int done, int total);
    void finished(bool completed); // completed = ran to the end (not cancelled)

private:
    void onResult(int generation, const QString& path, const std::optional<SongAnalysis>& result);

    SongDataStore* m_store;
    QThreadPool* m_pool;
    std::shared_ptr<std::atomic<bool>> m_cancelled;
    int m_generation = 0;
    int m_done = 0;
    int m_total = 0;
};

// Settings > Library > Detect Genres: runs GenreClassifier over the library
// in the background, one song at a time (the model itself uses two cores),
// like the Android app's GenreAnalyzer. Results are kept, so stopping costs
// nothing and the next run only does new or changed songs.
class GenreAnalyzer : public QObject
{
    Q_OBJECT

public:
    explicit GenreAnalyzer(SongDataStore* store, QObject* parent = nullptr);
    ~GenreAnalyzer() override;

    bool isRunning() const { return m_total > 0; }
    int done() const { return m_done; }
    int total() const { return m_total; }

    void start(const QVector<TrackInfo>& tracks); // every track without a result
    void cancel();

signals:
    void progressChanged(int done, int total);
    void finished(bool completed);

private:
    void onResult(int generation, const QString& path, const DetectedGenre& result);

    SongDataStore* m_store;
    QThreadPool* m_pool;
    std::shared_ptr<std::atomic<bool>> m_cancelled;
    int m_generation = 0;
    int m_done = 0;
    int m_total = 0;
};

} // namespace audioforge
