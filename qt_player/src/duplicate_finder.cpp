#include "audioforge/duplicate_finder.hpp"
#include "audioforge/app_files.hpp"

#include <QFileInfo>
#include <QHash>
#include <QJsonArray>
#include <QJsonObject>
#include <QRegularExpression>

#include <algorithm>
#include <cmath>
#include <numeric>
#include <optional>

namespace audioforge {

namespace {

constexpr double kExactMaxLengthDiff = 2.0;   // seconds: exact, like the finder always did
constexpr double kSimilarMaxLengthDiff = 3.0; // similar
constexpr double kTitleMin = 0.85;            // similar titles: at least this alike (1 = identical)
constexpr double kArtistMin = 0.80;           // ...and artists at least this alike,
constexpr double kTitleMinNoArtist = 0.92;    // ...or, if one artist is missing, titles at least this alike
const QChar kPairSeparator(0);

// --- Names ------------------------------------------------------------------------

// The finder's original key: lowercase, bracketed parts removed, letters and digits only.
// Only (round) and [square] brackets: in "Artist【Title】Official Video" names
// (common for downloaded music videos) the 【】 part IS the song.
QString LegacyNormalized(const QString& text)
{
    static const QRegularExpression bracketed(QStringLiteral(u"\\s*[(\\[（][^)\\]）]*[)\\]）]"));
    QString stripped = text.toLower();
    stripped.remove(bracketed);
    QString result;
    for (const QChar c : stripped)
    {
        if (c.isLetterOrNumber())
        {
            result += c;
        }
    }
    return result;
}

QString SortedWords(const QString& name)
{
    QStringList words = name.split(' ', Qt::SkipEmptyParts);
    words.sort();
    return words.join(' ');
}

// The words in a title that mean a different recording ("live", "remix"...).
// "Radio edit", "album/single version" don't count.
QSet<QString> VersionMarkers(const QString& title)
{
    static const QRegularExpression remasterVersion(QStringLiteral("remaster(ed)?\\s+version"));
    static const QRegularExpression latin(QStringLiteral("\\b(live|remix|mix|acoustic|instrumental|karaoke|demo|cover|edit|version|ver)\\b"));
    QString s = title.normalized(QString::NormalizationForm_KC).toLower();
    s.replace("radio edit", " ").replace("album version", " ").replace("single version", " ");
    s.replace(remasterVersion, " ");
    QSet<QString> markers;
    QRegularExpressionMatchIterator it = latin.globalMatch(s);
    while (it.hasNext())
    {
        const QString word = it.next().captured(1);
        markers.insert(word == "ver" ? QStringLiteral("version") : word);
    }
    static const QPair<QString, QString> cjk[] = {
        {QStringLiteral("伴奏"), QStringLiteral("instrumental")}, {QStringLiteral("現場"), QStringLiteral("live")},
        {QStringLiteral("现场"), QStringLiteral("live")}, {QStringLiteral("翻唱"), QStringLiteral("cover")},
    };
    for (const auto& [word, marker] : cjk)
    {
        if (s.contains(word))
        {
            markers.insert(marker);
        }
    }
    return markers;
}

bool IsCjk(QChar c)
{
    const QChar::Script script = c.script();
    return script == QChar::Script_Han || script == QChar::Script_Hiragana || script == QChar::Script_Katakana
           || script == QChar::Script_Hangul;
}

struct SongKey
{
    TrackInfo track;
    QString legacyKey;
    QSet<QString> markers;
    QString title;
    QString titleWords;
    QString artist;
    QString artistWords;
    QString cjk; // the title's Chinese / Japanese / Korean characters
    bool artistUnknown = false;

    explicit SongKey(const TrackInfo& t)
        : track(t),
          legacyKey(LegacyNormalized(t.title) + '|' + LegacyNormalized(t.artist)),
          markers(VersionMarkers(t.title)),
          artist(NormalizeSongName(t.artist, false)),
          artistWords(SortedWords(artist))
    {
        // The title without the artist's name in it ("Coldplay - Yellow"), if anything's left.
        title = NormalizeSongName(t.title, true);
        if (artist.size() >= 2)
        {
            QString withoutArtist = QString(title).replace(artist, " ").simplified();
            if (withoutArtist == title)
            {
                // "Joker Xue" in the title, "JokerXue" in the tag: compare without spaces.
                const QString spacelessArtist = QString(artist).remove(' ');
                const QString spacelessTitle = QString(title).remove(' ');
                if (spacelessArtist.size() >= 2 && spacelessTitle.contains(spacelessArtist))
                {
                    withoutArtist = QString(spacelessTitle).remove(spacelessArtist);
                }
            }
            if (!withoutArtist.isEmpty())
            {
                title = withoutArtist;
            }
        }
        titleWords = SortedWords(title);
        for (const QChar c : title)
        {
            if (IsCjk(c))
            {
                cjk += c;
            }
        }
        // "<unknown>" is "unknown" once normalized.
        artistUnknown = artist.isEmpty() || artist == "unknown" || artist == "unknown artist";
    }
};

// Edit distance between a and b, or nullopt if it's more than `limit` (only a band of the table is filled).
std::optional<int> BoundedLevenshtein(const QList<uint>& a, const QList<uint>& b, int limit)
{
    const int big = limit + 1;
    std::vector<int> previous(b.size() + 1), current(b.size() + 1);
    for (int j = 0; j <= b.size(); ++j)
    {
        previous[j] = j <= limit ? j : big;
    }
    for (int i = 1; i <= a.size(); ++i)
    {
        const int from = std::max(1, i - limit);
        const int to = std::min<int>(b.size(), i + limit);
        std::fill(current.begin(), current.end(), big);
        current[0] = i <= limit ? i : big;
        int rowMin = current[0];
        for (int j = from; j <= to; ++j)
        {
            const int cost = a[i - 1] == b[j - 1] ? 0 : 1;
            const int value = std::min({previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost});
            current[j] = std::min(value, big);
            rowMin = std::min(rowMin, current[j]);
        }
        if (rowMin > limit)
        {
            return std::nullopt;
        }
        std::swap(previous, current);
    }
    return previous[b.size()] <= limit ? std::optional<int>(previous[b.size()]) : std::nullopt;
}

// The higher of: the names as they are, and with their words sorted (word order doesn't matter).
double Similarity(const QString& a, const QString& aWords, const QString& b, const QString& bWords, double atLeast)
{
    if (a == b || aWords == bWords)
    {
        return 1.0;
    }
    const double direct = NameSimilarity(a, b, atLeast);
    if (direct >= atLeast)
    {
        return direct;
    }
    return std::max(direct, NameSimilarity(aWords, bWords, atLeast));
}

bool IsExact(const SongKey& a, const SongKey& b)
{
    return a.legacyKey == b.legacyKey && std::abs(a.track.durationSeconds - b.track.durationSeconds) <= kExactMaxLengthDiff
           && a.markers == b.markers;
}

bool IsSimilar(const SongKey& a, const SongKey& b)
{
    if (a.markers != b.markers || a.title.isEmpty() || b.title.isEmpty())
    {
        return false;
    }
    const double titleSimilarity = Similarity(a.title, a.titleWords, b.title, b.titleWords, kTitleMin);
    if (titleSimilarity < kTitleMin)
    {
        return false;
    }
    // Each Chinese / Japanese / Korean character carries a lot of meaning: two
    // characters apart in a long title can be a different song. Those
    // characters must be alike on their own too.
    if (!a.cjk.isEmpty() && !b.cjk.isEmpty() && NameSimilarity(a.cjk, b.cjk, kTitleMin) < kTitleMin)
    {
        return false;
    }
    if (a.artistUnknown || b.artistUnknown)
    {
        return titleSimilarity >= kTitleMinNoArtist;
    }
    return Similarity(a.artist, a.artistWords, b.artist, b.artistWords, kArtistMin) >= kArtistMin;
}

struct UnionFind
{
    std::vector<int> parent;
    explicit UnionFind(int size) : parent(size) { std::iota(parent.begin(), parent.end(), 0); }
    int find(int i)
    {
        int root = i;
        while (parent[root] != root)
        {
            root = parent[root];
        }
        while (parent[i] != root)
        {
            const int next = parent[i];
            parent[i] = root;
            i = next;
        }
        return root;
    }
    void unite(int a, int b)
    {
        const int ra = find(a), rb = find(b);
        if (ra != rb)
        {
            parent[std::max(ra, rb)] = std::min(ra, rb);
        }
    }
};

bool IsLossless(const TrackInfo& t)
{
    static const QStringList lossless = {"flac", "wav", "aiff", "aif", "alac", "ape", "wv"};
    return lossless.contains(QFileInfo(t.path).suffix().toLower());
}

// The copy with the highest bitrate, then lossless before lossy; empty if none stands out.
QString BestCopy(QVector<TrackInfo> tracks)
{
    auto better = [](const TrackInfo& a, const TrackInfo& b) {
        if (a.bitrateKbps != b.bitrateKbps)
        {
            return a.bitrateKbps > b.bitrateKbps;
        }
        return IsLossless(a) && !IsLossless(b);
    };
    std::stable_sort(tracks.begin(), tracks.end(), better);
    return better(tracks[0], tracks[1]) ? tracks[0].path : QString();
}

QVector<DuplicateGroup> CollectGroups(const std::vector<SongKey>& songs, UnionFind& sets,
                                      const std::vector<bool>& similarRoot)
{
    QHash<int, QVector<int>> members;
    for (int i = 0; i < static_cast<int>(songs.size()); ++i)
    {
        members[sets.find(i)] << i;
    }
    QVector<DuplicateGroup> groups;
    for (auto it = members.begin(); it != members.end(); ++it)
    {
        if (it.value().size() < 2)
        {
            continue;
        }
        DuplicateGroup group;
        for (int i : it.value())
        {
            group.tracks << songs[i].track;
        }
        group.similar = !similarRoot.empty() && similarRoot[it.key()];
        group.best = BestCopy(group.tracks);
        groups << group;
    }
    std::sort(groups.begin(), groups.end(), [](const DuplicateGroup& a, const DuplicateGroup& b) {
        if (a.similar != b.similar)
        {
            return !a.similar; // "Same song" first
        }
        return QString::compare(a.tracks.first().title, b.tracks.first().title, Qt::CaseInsensitive) < 0;
    });
    return groups;
}

QString DismissalsPath()
{
    return AppDataFolder() + "/duplicate_dismissals.json";
}

} // namespace

QString NormalizeSongName(const QString& text, bool isTitle)
{
    static const QRegularExpression leadingNumber(QStringLiteral("^\\d{1,3}\\s*[-._)]\\s*"));
    // (Live), [Remastered]: extras, removed. 【Title】《Title》「Title」: the song itself, kept.
    static const QRegularExpression bracketed(QStringLiteral(u"[(\\[（][^)\\]）]*[)\\]）]"));
    static const QRegularExpression titleBrackets(QStringLiteral(u"[【】《》「」『』〈〉]"));
    // Downloaded-video filler that says nothing about which song it is.
    static const QRegularExpression filler(QStringLiteral(
        u"\\b(official\\s+(music\\s+)?(video|audio|mv|lyric\\s+video|visualizer)|(music|lyric|lyrics)\\s+video"
        u"|official|m/?v|hd|hq|4k)\\b|官方|高清|完整版|動態歌詞|动态歌词|歌詞版|歌词版"),
        QRegularExpression::UseUnicodePropertiesOption);
    static const QRegularExpression spaces(QStringLiteral("\\s+"));
    static const QRegularExpression editionSuffix(QStringLiteral(
        "\\s+[-–—]\\s+(\\d{4}\\s+)?(remaster(ed)?(\\s+\\d{4})?(\\s+version)?|\\d{4}\\s+remaster(ed)?|deluxe(\\s+edition)?"
        "|single version|radio edit|album version|mono|stereo)\\s*$"));
    static const QRegularExpression featuring(QStringLiteral("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$"));
    static const QRegularExpression notLetterOrDigit(QStringLiteral("[^\\p{L}\\p{N}]+"));

    // Full-width letters made normal, lowercase, Latin accents removed.
    QString decomposed = text.normalized(QString::NormalizationForm_KC).toLower().normalized(QString::NormalizationForm_D);
    QString s;
    for (const QChar c : decomposed)
    {
        if (c.category() != QChar::Mark_NonSpacing)
        {
            s += c;
        }
    }
    s = s.normalized(QString::NormalizationForm_C);
    s.replace("&", " and ").replace('_', ' ');
    s = s.trimmed();
    if (isTitle)
    {
        s.remove(leadingNumber);
        s.replace(filler, " ");
        s.replace(titleBrackets, " ");
    }
    s.replace(bracketed, " ");
    s = s.replace(spaces, " ").trimmed();
    s.remove(editionSuffix);
    s.remove(featuring);
    s.remove('\'').remove(QChar(0x2019)); // "don't" = "dont"
    return s.replace(notLetterOrDigit, " ").trimmed();
}

double NameSimilarity(const QString& a, const QString& b, double atLeast)
{
    const QList<uint> x = a.toUcs4();
    const QList<uint> y = b.toUcs4();
    const int longer = std::max(x.size(), y.size());
    if (longer == 0)
    {
        return 1.0;
    }
    // The most edits that still reach atLeast; the length difference alone may already be too many.
    const int allowed = static_cast<int>((1.0 - atLeast) * longer + 1e-9);
    if (std::abs(x.size() - y.size()) > allowed)
    {
        return 0.0;
    }
    const std::optional<int> distance = BoundedLevenshtein(x, y, allowed);
    return distance ? 1.0 - static_cast<double>(*distance) / longer : 0.0;
}

QString DuplicatePairKey(const QString& a, const QString& b)
{
    return a < b ? a + kPairSeparator + b : b + kPairSeparator + a;
}

QSet<QString> PairsAmong(const QStringList& paths)
{
    QStringList list = paths;
    list.removeDuplicates();
    QSet<QString> pairs;
    for (int i = 0; i < list.size(); ++i)
    {
        for (int j = i + 1; j < list.size(); ++j)
        {
            pairs.insert(DuplicatePairKey(list[i], list[j]));
        }
    }
    return pairs;
}

QSet<QString> PairsForSelection(const QStringList& selected, const QStringList& group)
{
    QSet<QString> pairs = PairsAmong(selected);
    for (const QString& a : selected)
    {
        for (const QString& b : group)
        {
            if (!selected.contains(b))
            {
                pairs.insert(DuplicatePairKey(a, b));
            }
        }
    }
    return pairs;
}

DuplicateResult FindDuplicates(const QVector<TrackInfo>& tracks, const QSet<QString>& dismissed)
{
    std::vector<SongKey> songs;
    songs.reserve(tracks.size());
    for (const TrackInfo& t : tracks)
    {
        songs.emplace_back(t);
    }
    const int count = static_cast<int>(songs.size());
    UnionFind kept(count);
    UnionFind hidden(count);
    std::vector<std::pair<int, int>> similarEdges;

    auto join = [&](int i, int j, bool similar) {
        if (dismissed.contains(DuplicatePairKey(songs[i].track.path, songs[j].track.path)))
        {
            hidden.unite(i, j);
        }
        else
        {
            kept.unite(i, j);
            if (similar)
            {
                similarEdges.emplace_back(i, j);
            }
        }
    };

    // Exact: the finder's original key, any length (unknown lengths too).
    QHash<QString, QVector<int>> byLegacyKey;
    for (int i = 0; i < count; ++i)
    {
        byLegacyKey[songs[i].legacyKey] << i;
    }
    for (const QVector<int>& indices : byLegacyKey)
    {
        for (int a = 0; a < indices.size(); ++a)
        {
            for (int b = a + 1; b < indices.size(); ++b)
            {
                if (IsExact(songs[indices[a]], songs[indices[b]]))
                {
                    join(indices[a], indices[b], false);
                }
            }
        }
    }

    // Similar: only songs with a known length, each compared with the next 3 s of songs.
    std::vector<int> byLength;
    for (int i = 0; i < count; ++i)
    {
        if (songs[i].track.durationSeconds > 0.0)
        {
            byLength.push_back(i);
        }
    }
    std::sort(byLength.begin(), byLength.end(), [&](int x, int y) {
        return songs[x].track.durationSeconds < songs[y].track.durationSeconds;
    });
    for (size_t a = 0; a < byLength.size(); ++a)
    {
        const int i = byLength[a];
        for (size_t b = a + 1; b < byLength.size(); ++b)
        {
            const int j = byLength[b];
            if (songs[j].track.durationSeconds - songs[i].track.durationSeconds > kSimilarMaxLengthDiff)
            {
                break;
            }
            if ((songs[i].legacyKey != songs[j].legacyKey || !IsExact(songs[i], songs[j])) && IsSimilar(songs[i], songs[j]))
            {
                join(i, j, true);
            }
        }
    }

    std::vector<bool> similarRoot(count, false);
    for (const auto& [i, j] : similarEdges)
    {
        similarRoot[kept.find(i)] = true;
    }
    DuplicateResult result;
    result.groups = CollectGroups(songs, kept, similarRoot);
    result.dismissed = CollectGroups(songs, hidden, {});
    return result;
}

QSet<QString> LoadDuplicateDismissals(const QVector<TrackInfo>& library)
{
    QSet<QString> paths;
    for (const TrackInfo& t : library)
    {
        paths.insert(t.path);
    }
    QSet<QString> pairs;
    for (const QJsonValue& value : ReadJsonFile(DismissalsPath())["pairs"].toArray())
    {
        const QJsonArray pair = value.toArray();
        const QString a = pair.at(0).toString();
        const QString b = pair.at(1).toString();
        if (paths.contains(a) && paths.contains(b)) // both still in the library
        {
            pairs.insert(DuplicatePairKey(a, b));
        }
    }
    return pairs;
}

void SaveDuplicateDismissals(const QSet<QString>& pairs)
{
    QStringList sorted(pairs.begin(), pairs.end());
    sorted.sort();
    QJsonArray array;
    for (const QString& pair : sorted)
    {
        const int split = pair.indexOf(kPairSeparator);
        array << QJsonArray{pair.left(split), pair.mid(split + 1)};
    }
    WriteJsonFile(DismissalsPath(), QJsonObject{{"pairs", array}});
}

} // namespace audioforge
