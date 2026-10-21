#include "audioforge/genre.hpp"
#include "audioforge/warnings.hpp"

#include "miniaudio.h" // implementation lives in audio_engine.cpp

// Windows has its own, older onnxruntime.dll in System32 (for Windows ML),
// which wins over one on PATH. The app ships the right one next to the exe,
// but just in case: the API is set up by hand (InitOnnxRuntime), so a DLL
// that's too old turns genre detection off instead of ending the app.
#define ORT_API_MANUAL_INIT
#include <onnxruntime_cxx_api.h>

#include <QByteArray>
#include <QFile>
#include <QStringList>

#include <algorithm>
#include <vector>

namespace audioforge {

namespace {

// --- What part of the song is listened to (Android's GenreWindow) ------------

constexpr double kWindowSeconds = 30.0;
constexpr double kMinSongSeconds = 5.0;     // shorter songs can't be judged
constexpr double kWholeSongMaxSeconds = 40.0; // songs up to this long are listened to whole
constexpr int kSampleRate = 16000;            // YAMNet's input: 16 kHz mono, -1..1

struct Window
{
    double start = 0.0;
    double length = 0.0;
};

// Unknown length: the first 30 s. 5-40 s: the whole song. Longer: 30 s from
// a quarter of the way in, past most intros.
bool WindowFor(double duration, Window* window)
{
    if (duration <= 0.0)
    {
        *window = {0.0, kWindowSeconds};
    }
    else if (duration < kMinSongSeconds)
    {
        return false;
    }
    else if (duration <= kWholeSongMaxSeconds)
    {
        *window = {0.0, duration};
    }
    else
    {
        *window = {duration / 4.0, kWindowSeconds};
    }
    return true;
}

// --- Labels -> genres (Android's GenreTaxonomy) ------------------------------

struct TaxonomyEntry
{
    const char* label; // YAMNet's display_name
    const char* broad;
    const char* subgenre; // nullptr: counts towards the broad genre only
};

const TaxonomyEntry kTaxonomy[] = {
    {"Pop music", "Pop", nullptr},
    {"Rock music", "Rock", nullptr}, {"Punk rock", "Rock", "Punk"}, {"Grunge", "Rock", "Grunge"},
    {"Progressive rock", "Rock", "Progressive Rock"}, {"Rock and roll", "Rock", "Rock & Roll"},
    {"Psychedelic rock", "Rock", "Psychedelic"}, {"Independent music", "Rock", "Indie"},
    {"Heavy metal", "Metal", nullptr},
    {"Hip hop music", "Hip-Hop", nullptr},
    {"Rhythm and blues", "R&B / Soul", nullptr}, {"Soul music", "R&B / Soul", "Soul"}, {"Funk", "R&B / Soul", "Funk"},
    {"Electronic music", "Electronic", nullptr}, {"Electronica", "Electronic", nullptr},
    {"Electronic dance music", "Electronic", "EDM"}, {"Dance music", "Electronic", "Dance"},
    {"House music", "Electronic", "House"}, {"Techno", "Electronic", "Techno"}, {"Dubstep", "Electronic", "Dubstep"},
    {"Drum and bass", "Electronic", "Drum & Bass"}, {"Trance music", "Electronic", "Trance"},
    {"Disco", "Electronic", "Disco"}, {"Ambient music", "Electronic", "Ambient"}, {"New-age music", "Electronic", "New Age"},
    {"Jazz", "Jazz", nullptr}, {"Swing music", "Jazz", "Swing"},
    {"Blues", "Blues", nullptr},
    {"Classical music", "Classical", nullptr}, {"Opera", "Classical", "Opera"},
    {"Country", "Country", nullptr}, {"Bluegrass", "Country", "Bluegrass"},
    {"Folk music", "Folk", nullptr}, {"Traditional music", "Folk", "Traditional"},
    {"Music of Latin America", "Latin", nullptr}, {"Salsa music", "Latin", "Salsa"}, {"Flamenco", "Latin", "Flamenco"},
    {"Reggae", "Reggae", nullptr}, {"Ska", "Reggae", "Ska"},
    {"Gospel music", "Gospel", nullptr}, {"Christian music", "Gospel", "Christian"},
    {"Middle Eastern music", "World", nullptr}, {"Music of Africa", "World", "African"}, {"Afrobeat", "World", "Afrobeat"},
    {"Music of Asia", "World", "Asian"}, {"Carnatic music", "World", "Carnatic"}, {"Music of Bollywood", "World", "Bollywood"},
    {"Soundtrack music", "Soundtrack", nullptr}, {"Video game music", "Soundtrack", "Video Game"},
    {"Theme music", "Soundtrack", nullptr}, {"Christmas music", "Soundtrack", "Christmas"},
};

constexpr double kNotMusicMax = 0.15;  // below this "Music" score, with more speech than music: not music
constexpr double kMinTotal = 0.05;     // less genre evidence than this in total: unknown
constexpr double kMinConfidence = 0.35; // the broad genre's share of the evidence must be at least this
constexpr double kSubgenreShare = 0.4; // a subgenre must carry at least this share of its broad genre's evidence
constexpr int kTopCount = 5;

void InitOnnxRuntime()
{
    static const OrtApi* api = nullptr;
    if (api)
    {
        return;
    }
    const OrtApiBase* base = OrtGetApiBase();
    api = base ? base->GetApi(ORT_API_VERSION) : nullptr;
    if (!api)
    {
        throw std::runtime_error(std::string("the loaded onnxruntime.dll (") + (base ? base->GetVersionString() : "?")
                                 + ") is too old -- it should be the one next to audioforge.exe");
    }
    Ort::InitApi(api);
}

// The display_name column of a class map line: index,mid,display_name (the name may be quoted).
QString DisplayName(const QString& line)
{
    const int first = line.indexOf(',');
    const int second = first < 0 ? -1 : line.indexOf(',', first + 1);
    QString rest = second < 0 ? QString() : line.mid(second + 1).trimmed();
    if (rest.startsWith('"'))
    {
        rest = rest.mid(1);
        if (rest.endsWith('"'))
        {
            rest.chop(1);
        }
        rest.replace("\"\"", "\"");
    }
    return rest;
}

} // namespace

QString DetectedGenre::label() const
{
    if (broad.isEmpty())
    {
        return QString();
    }
    return subgenre.isEmpty() ? broad : broad + QStringLiteral(" · ") + subgenre;
}

bool IsPlaceholderGenre(const QString& tagGenre)
{
    static const QStringList kPlaceholders = {
        // YouTube's categories (yt-dlp and similar write them as the genre).
        "Music", "Gaming", "Entertainment", "People & Blogs", "Film & Animation", "Comedy", "Education",
        "Howto & Style", "News & Politics", "Science & Technology", "Sports", "Travel & Events",
        "Autos & Vehicles", "Pets & Animals", "Nonprofits & Activism", "Shows", "Trailers", "Movies",
        // Filler.
        "Other", "Unknown", "Genre", "<unknown>", "None", "Misc", "Miscellaneous",
    };
    const QString genre = tagGenre.trimmed();
    for (const QString& placeholder : kPlaceholders)
    {
        if (genre.compare(placeholder, Qt::CaseInsensitive) == 0)
        {
            return true;
        }
    }
    return false;
}

QString ShownGenre(const QString& tagGenre, const DetectedGenre* detected)
{
    if (!tagGenre.trimmed().isEmpty() && !IsPlaceholderGenre(tagGenre))
    {
        return tagGenre;
    }
    return detected && detected->status == DetectedGenre::Status::Ok ? detected->label() : QString();
}

DetectedGenre DecideGenre(const QHash<QString, float>& scores, double durationSeconds)
{
    DetectedGenre result;
    result.duration = durationSeconds;

    QVector<QPair<QString, float>> sorted;
    for (auto it = scores.constBegin(); it != scores.constEnd(); ++it)
    {
        sorted.append({it.key(), it.value()});
    }
    std::sort(sorted.begin(), sorted.end(), [](const auto& a, const auto& b) { return a.second > b.second; });
    result.top = sorted.mid(0, kTopCount);

    const float music = scores.value(QStringLiteral("Music"));
    const float speech = scores.value(QStringLiteral("Speech"));
    if (music < kNotMusicMax && speech > music)
    {
        result.status = DetectedGenre::Status::NotMusic;
        return result;
    }

    // Each broad genre's evidence, in table order (ties go to the first, like Android).
    QStringList order;
    QHash<QString, double> mass;
    double total = 0.0;
    for (const TaxonomyEntry& entry : kTaxonomy)
    {
        const QString broad = QString::fromUtf8(entry.broad);
        if (!mass.contains(broad))
        {
            order << broad;
        }
        const double score = scores.value(QString::fromUtf8(entry.label));
        mass[broad] += score;
        total += score;
    }
    if (total < kMinTotal)
    {
        result.status = DetectedGenre::Status::Unknown;
        return result;
    }
    QString broad = order.first();
    for (const QString& name : order)
    {
        if (mass.value(name) > mass.value(broad))
        {
            broad = name;
        }
    }
    const double broadMass = mass.value(broad);
    result.confidence = broadMass / total;
    if (result.confidence < kMinConfidence)
    {
        result.status = DetectedGenre::Status::Unknown;
        return result;
    }

    const TaxonomyEntry* bestSub = nullptr;
    float bestScore = -1.0f;
    for (const TaxonomyEntry& entry : kTaxonomy)
    {
        if (entry.subgenre && broad == QString::fromUtf8(entry.broad))
        {
            const float score = scores.value(QString::fromUtf8(entry.label));
            if (score > bestScore)
            {
                bestScore = score;
                bestSub = &entry;
            }
        }
    }
    result.status = DetectedGenre::Status::Ok;
    result.broad = broad;
    if (bestSub && bestScore >= kSubgenreShare * broadMass)
    {
        result.subgenre = QString::fromUtf8(bestSub->subgenre);
    }
    return result;
}

// --- The model ------------------------------------------------------------------

struct GenreClassifier::Model
{
    Ort::Env env{ORT_LOGGING_LEVEL_WARNING, "AudioForge"};
    std::unique_ptr<Ort::Session> session;
    std::string inputName;
    std::string scoresName; // the [frames, 521] output, found by shape, not position
    QStringList labels;
};

GenreClassifier::GenreClassifier() = default;
GenreClassifier::~GenreClassifier() = default;

bool GenreClassifier::loadModel()
{
    if (m_model)
    {
        return true;
    }
    if (m_loadFailed)
    {
        return false;
    }
    try
    {
        QFile modelFile(QStringLiteral(":/models/yamnet.onnx"));
        QFile classMap(QStringLiteral(":/models/yamnet_class_map.csv"));
        if (!modelFile.open(QIODevice::ReadOnly) || !classMap.open(QIODevice::ReadOnly | QIODevice::Text))
        {
            throw std::runtime_error("the bundled model files are missing");
        }
        const QByteArray bytes = modelFile.readAll();

        InitOnnxRuntime(); // before any Ort object
        auto model = std::make_unique<Model>();
        const QStringList lines = QString::fromUtf8(classMap.readAll()).split('\n', Qt::SkipEmptyParts);
        for (int i = 1; i < lines.size(); ++i) // line 0 is the header
        {
            model->labels << DisplayName(lines[i]);
        }

        Ort::SessionOptions options;
        options.SetIntraOpNumThreads(2); // leaves cores for the UI and playback
        options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
        model->session = std::make_unique<Ort::Session>(model->env, bytes.constData(), static_cast<size_t>(bytes.size()), options);

        Ort::AllocatorWithDefaultOptions allocator;
        model->inputName = model->session->GetInputNameAllocated(0, allocator).get();
        for (size_t i = 0; i < model->session->GetOutputCount(); ++i)
        {
            const std::vector<int64_t> shape = model->session->GetOutputTypeInfo(i).GetTensorTypeAndShapeInfo().GetShape();
            if (shape.size() == 2 && shape[1] == model->labels.size())
            {
                model->scoresName = model->session->GetOutputNameAllocated(i, allocator).get();
            }
        }
        if (model->scoresName.empty())
        {
            throw std::runtime_error("the model has no score output matching the class map");
        }
        m_model = std::move(model);
        return true;
    }
    catch (const std::exception& e)
    {
        ErrorReporter::logOnly(QString("Genre model couldn't load: %1").arg(QString::fromLocal8Bit(e.what())));
        m_loadFailed = true;
        return false;
    }
}

DetectedGenre GenreClassifier::detect(const QString& path, double durationSeconds)
{
    DetectedGenre failed;
    failed.duration = durationSeconds;
    failed.status = DetectedGenre::Status::Failed;

    Window window;
    if (!WindowFor(durationSeconds, &window))
    {
        DetectedGenre tooShort = failed;
        tooShort.status = DetectedGenre::Status::TooShort;
        return tooShort;
    }
    if (!loadModel())
    {
        return failed;
    }

    // Decode the window at 16 kHz mono (miniaudio's resampler filters out
    // what's above 8 kHz first).
    std::vector<float> samples;
    {
        ma_decoder_config config = ma_decoder_config_init(ma_format_f32, 1, kSampleRate);
        ma_decoder decoder;
#ifdef _WIN32
        const std::wstring pathW = path.toStdWString(); // wide path: non-ASCII file names
        const ma_result opened = ma_decoder_init_file_w(pathW.c_str(), &config, &decoder);
#else
        const ma_result opened = ma_decoder_init_file(path.toUtf8().constData(), &config, &decoder);
#endif
        if (opened != MA_SUCCESS)
        {
            ErrorReporter::logOnly("Genre detection couldn't open " + path);
            return failed;
        }
        ma_decoder_seek_to_pcm_frame(&decoder, static_cast<ma_uint64>(window.start * kSampleRate));
        const size_t wanted = static_cast<size_t>(window.length * kSampleRate);
        samples.resize(wanted);
        ma_uint64 read = 0;
        ma_decoder_read_pcm_frames(&decoder, samples.data(), wanted, &read);
        ma_decoder_uninit(&decoder);
        samples.resize(static_cast<size_t>(read));
    }
    if (samples.size() < static_cast<size_t>(kMinSongSeconds * kSampleRate))
    {
        DetectedGenre tooShort = failed;
        tooShort.status = DetectedGenre::Status::TooShort;
        return tooShort;
    }

    try
    {
        Ort::MemoryInfo memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
        const int64_t shape[] = {static_cast<int64_t>(samples.size())};
        Ort::Value input = Ort::Value::CreateTensor<float>(memory, samples.data(), samples.size(), shape, 1);
        const char* inputNames[] = {m_model->inputName.c_str()};
        const char* outputNames[] = {m_model->scoresName.c_str()};
        std::vector<Ort::Value> outputs = m_model->session->Run(Ort::RunOptions{nullptr}, inputNames, &input, 1, outputNames, 1);

        const std::vector<int64_t> outShape = outputs[0].GetTensorTypeAndShapeInfo().GetShape();
        const int64_t frames = outShape.empty() ? 0 : outShape[0];
        const int64_t classes = outShape.size() > 1 ? outShape[1] : 0;
        if (frames <= 0 || classes != m_model->labels.size())
        {
            throw std::runtime_error("the model returned no frames");
        }
        const float* data = outputs[0].GetTensorData<float>();
        QHash<QString, float> scores;
        scores.reserve(static_cast<int>(classes));
        for (int64_t c = 0; c < classes; ++c)
        {
            double sum = 0.0;
            for (int64_t f = 0; f < frames; ++f)
            {
                sum += data[f * classes + c];
            }
            scores.insert(m_model->labels[static_cast<int>(c)], static_cast<float>(sum / frames));
        }
        return DecideGenre(scores, durationSeconds);
    }
    catch (const std::exception& e)
    {
        ErrorReporter::logOnly(QString("Genre detection failed for %1: %2").arg(path, QString::fromLocal8Bit(e.what())));
        return failed;
    }
}

} // namespace audioforge
