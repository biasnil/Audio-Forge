#pragma once

#include <QHash>
#include <QObject>
#include <QString>
#include <QVector>

#include <atomic>
#include <memory>

#include "audioforge/song_analysis.hpp"
#include "audioforge/track_info.hpp"

class QThreadPool;
class QTimer;

namespace audioforge {

// Per-song data the app learns over time, keyed by file path: the key/BPM
// analysis and the resume position of long tracks. Saved as JSON next to
// AudioForge.ini (analysis.json, songstats.json) a moment after each change.
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

private:
    void scheduleSave();

    QString m_folder;
    QHash<QString, SongAnalysis> m_analysis;
    QHash<QString, double> m_resume;
    bool m_analysisDirty = false;
    bool m_statsDirty = false;
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

} // namespace audioforge
