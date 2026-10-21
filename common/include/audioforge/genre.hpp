#pragma once

#include <QHash>
#include <QPair>
#include <QString>
#include <QVector>

#include <memory>

namespace audioforge {

// Genre detection (Settings > Library > Detect Genres), a port of the
// Android app's: Google's YAMNet sound classifier (resources/models, run
// with ONNX Runtime on this PC -- nothing is uploaded) listens to 30 s of a
// song, and its label scores are turned into a broad genre and, where one
// stands out, a subgenre ("Electronic · House").

// The model and genre table a result came from -- the same id as Android's,
// because it's the same model and table. Change it when either changes, so
// every song is detected again once.
inline constexpr const char* kGenreModelId = "yamnet-1/map-1";

struct DetectedGenre
{
    enum class Status { Ok, Unknown, NotMusic, TooShort, Failed };

    double duration = 0.0; // the file's length when analyzed; a different length means a different file
    Status status = Status::Failed;
    // English data names (they can go into the GENRE tag), never translated.
    QString broad;
    QString subgenre;
    double confidence = 0.0;           // the broad genre's share of all genre evidence, 0..1
    QVector<QPair<QString, float>> top; // the model's top labels and scores, for checking and tuning
    QString model = QString::fromLatin1(kGenreModelId);

    // "Electronic · House", or just "Electronic"; empty without a genre.
    QString label() const;
};

// A GENRE tag that isn't really a genre: YouTube's video categories ("Music",
// "Gaming", "People & Blogs", ...), which download tools write into the file,
// and filler like "Other" or "Unknown".
bool IsPlaceholderGenre(const QString& tagGenre);

// The genre AudioForge shows and sorts by: the file's own GENRE tag, unless
// it's empty or a placeholder -- then the detected one ("Pop · Dance"), if any.
// The file itself isn't changed.
QString ShownGenre(const QString& tagGenre, const DetectedGenre* detected);

// The result for one song from YAMNet's label -> score (averaged over its frames).
DetectedGenre DecideGenre(const QHash<QString, float>& scores, double durationSeconds);

// Runs the model. Loading it takes a moment and ~100 MB, so one is created
// per Detect Genres run and used for every song, one at a time. Not for use
// from two threads at once.
class GenreClassifier
{
public:
    GenreClassifier();
    ~GenreClassifier();

    // Never throws; problems come back as Status::Failed (and in warnings.log).
    DetectedGenre detect(const QString& path, double durationSeconds);

private:
    struct Model;
    bool loadModel();
    std::unique_ptr<Model> m_model;
    bool m_loadFailed = false;
};

} // namespace audioforge
