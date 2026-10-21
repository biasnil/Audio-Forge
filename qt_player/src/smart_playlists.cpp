#include "audioforge/smart_playlists.hpp"
#include "audioforge/app_files.hpp"
#include "audioforge/translations.hpp"

#include <QJsonArray>
#include <QJsonObject>
#include <QRandomGenerator>
#include <QRegularExpression>
#include <QUuid>

#include <algorithm>
#include <cmath>

namespace audioforge {

namespace {

constexpr double kDayMs = 86400000.0;

// Stable names for smartplaylists.json (and Android's, so they read the same).
const char* const kFieldIds[] = {"Title", "Artist", "Album", "Genre", "Year", "PlayCount", "LastPlayedDays",
                                 "AddedDays", "Loved", "Bpm", "Key", "Minutes"};
const char* const kOperatorIds[] = {"Contains", "Is", "IsNot", "MoreThan", "LessThan", "Yes", "No"};
const char* const kSortIds[] = {"Title", "MostPlayed", "RecentlyPlayed", "RecentlyAdded", "Random", "Bpm"};

template <typename Enum, size_t N>
Enum FromId(const QString& id, const char* const (&ids)[N], Enum fallback)
{
    for (size_t i = 0; i < N; ++i)
    {
        if (id == QLatin1String(ids[i]))
        {
            return static_cast<Enum>(i);
        }
    }
    return fallback;
}

QString KeyText(const MusicKey& key)
{
    return key.camelot() + ' ' + key.shortName();
}

bool Matches(const SmartRule& rule, const TrackInfo& track, const SongStats& stats, const SongAnalysis* analysis,
             const QString& genre, qint64 nowMs)
{
    const QString value = rule.value.trimmed();
    switch (KindOf(rule.field))
    {
        case SmartFieldKind::Bool:
            return (rule.op == SmartOperator::Yes) == stats.loved;

        case SmartFieldKind::Text:
        {
            const std::optional<MusicKey> songKey =
                analysis && analysis->keyIndex >= 0 ? std::optional<MusicKey>(MusicKey::fromIndex(analysis->keyIndex))
                                                     : std::nullopt;
            if (rule.field == SmartField::Key)
            {
                if (!songKey)
                {
                    return rule.op == SmartOperator::IsNot;
                }
                // Compare keys, not spellings: "8A" matches "Am".
                if (const std::optional<MusicKey> wanted = ParseMusicKey(value))
                {
                    if (rule.op == SmartOperator::Is || rule.op == SmartOperator::IsNot)
                    {
                        return (*songKey == *wanted) != (rule.op == SmartOperator::IsNot);
                    }
                }
            }
            QString text;
            switch (rule.field)
            {
                case SmartField::Title:  text = track.title; break;
                case SmartField::Artist: text = track.artist; break;
                case SmartField::Album:  text = track.album; break;
                case SmartField::Genre:  text = genre; break;
                case SmartField::Key:    text = songKey ? KeyText(*songKey) : QString(); break;
                default: break;
            }
            switch (rule.op)
            {
                case SmartOperator::Contains: return value.isEmpty() || text.contains(value, Qt::CaseInsensitive);
                case SmartOperator::Is:       return text.compare(value, Qt::CaseInsensitive) == 0;
                case SmartOperator::IsNot:    return text.compare(value, Qt::CaseInsensitive) != 0;
                default:                      return false;
            }
        }

        case SmartFieldKind::Number:
        {
            bool ok = false;
            const double target = value.toDouble(&ok);
            if (!ok)
            {
                return true; // an unfinished rule doesn't filter
            }
            double number = 0.0;
            switch (rule.field)
            {
                case SmartField::Year:
                    if (track.year == 0) return false;
                    number = track.year;
                    break;
                case SmartField::PlayCount:
                    number = stats.playCount;
                    break;
                case SmartField::LastPlayedDays:
                    if (stats.lastPlayedMs <= 0) return false;
                    number = (nowMs - stats.lastPlayedMs) / kDayMs;
                    break;
                case SmartField::AddedDays:
                    if (track.addedMs <= 0) return false;
                    number = (nowMs - track.addedMs) / kDayMs;
                    break;
                case SmartField::Minutes:
                    number = track.durationSeconds / 60.0;
                    break;
                case SmartField::Bpm:
                    if (!analysis || !analysis->hasBpm()) return false;
                    number = analysis->bpm;
                    break;
                default:
                    return false;
            }
            switch (rule.op)
            {
                case SmartOperator::Is:       return std::abs(number - target) < 0.5;
                case SmartOperator::MoreThan: return number > target;
                case SmartOperator::LessThan: return number < target;
                default:                      return false;
            }
        }
    }
    return false;
}

SmartRule Rule(SmartField field, SmartOperator op, const QString& value = QString())
{
    return SmartRule{field, op, value};
}

} // namespace

SmartFieldKind KindOf(SmartField field)
{
    switch (field)
    {
        case SmartField::Title:
        case SmartField::Artist:
        case SmartField::Album:
        case SmartField::Genre:
        case SmartField::Key:
            return SmartFieldKind::Text;
        case SmartField::Loved:
            return SmartFieldKind::Bool;
        default:
            return SmartFieldKind::Number;
    }
}

QVector<SmartOperator> OperatorsFor(SmartField field)
{
    switch (KindOf(field))
    {
        case SmartFieldKind::Text:   return {SmartOperator::Contains, SmartOperator::Is, SmartOperator::IsNot};
        case SmartFieldKind::Number: return {SmartOperator::Is, SmartOperator::MoreThan, SmartOperator::LessThan};
        case SmartFieldKind::Bool:   return {SmartOperator::Yes, SmartOperator::No};
    }
    return {};
}

QString SmartFieldName(SmartField field)
{
    switch (field)
    {
        case SmartField::Title:          return Tr("Title");
        case SmartField::Artist:         return Tr("Artist");
        case SmartField::Album:          return Tr("Album");
        case SmartField::Genre:          return Tr("Genre");
        case SmartField::Year:           return Tr("Year");
        case SmartField::PlayCount:      return Tr("Play Count");
        case SmartField::LastPlayedDays: return Tr("Last Played (days ago)");
        case SmartField::AddedDays:      return Tr("Added (days ago)");
        case SmartField::Loved:          return Tr("Loved");
        case SmartField::Bpm:            return Tr("BPM");
        case SmartField::Key:            return Tr("Key (e.g. 8A or Am)");
        case SmartField::Minutes:        return Tr("Length (minutes)");
    }
    return QString();
}

QString SmartOperatorName(SmartOperator op)
{
    switch (op)
    {
        case SmartOperator::Contains: return Tr("contains");
        case SmartOperator::Is:       return Tr("is");
        case SmartOperator::IsNot:    return Tr("is not");
        case SmartOperator::MoreThan: return Tr("is more than");
        case SmartOperator::LessThan: return Tr("is less than");
        case SmartOperator::Yes:      return Tr("yes");
        case SmartOperator::No:       return Tr("no");
    }
    return QString();
}

QString SmartSortName(SmartSort sort)
{
    switch (sort)
    {
        case SmartSort::Title:          return Tr("Title");
        case SmartSort::MostPlayed:     return Tr("Most Played");
        case SmartSort::RecentlyPlayed: return Tr("Recently Played");
        case SmartSort::RecentlyAdded:  return Tr("Recently Added");
        case SmartSort::Random:         return Tr("Random");
        case SmartSort::Bpm:            return Tr("BPM");
    }
    return QString();
}

QString SmartPlaylist::displayName() const
{
    // The built-in names are English data, shown translated.
    return builtIn ? Tr(name.toUtf8().constData()) : name;
}

QVector<TrackInfo> SmartPlaylist::tracks(const QVector<TrackInfo>& library, const QHash<QString, SongStats>& stats,
                                         const std::function<const SongAnalysis*(const TrackInfo&)>& analysisFor,
                                         const std::function<QString(const TrackInfo&)>& genreOf,
                                         qint64 nowMs) const
{
    QVector<TrackInfo> matching;
    for (const TrackInfo& track : library)
    {
        if (rules.isEmpty())
        {
            matching << track;
            continue;
        }
        const SongStats songStats = stats.value(track.path);
        const SongAnalysis* analysis = analysisFor ? analysisFor(track) : nullptr;
        const QString genre = genreOf ? genreOf(track) : track.genre;
        bool matches = matchAll;
        for (const SmartRule& rule : rules)
        {
            const bool ruleMatches = Matches(rule, track, songStats, analysis, genre, nowMs);
            if (matchAll && !ruleMatches)
            {
                matches = false;
                break;
            }
            if (!matchAll && ruleMatches)
            {
                matches = true;
                break;
            }
        }
        if (matches)
        {
            matching << track;
        }
    }

    auto statOf = [&stats](const TrackInfo& t) { return stats.value(t.path); };
    switch (sort)
    {
        case SmartSort::Title:
            std::stable_sort(matching.begin(), matching.end(), [](const TrackInfo& a, const TrackInfo& b) {
                return QString::compare(a.title, b.title, Qt::CaseInsensitive) < 0;
            });
            break;
        case SmartSort::MostPlayed:
            std::stable_sort(matching.begin(), matching.end(), [&](const TrackInfo& a, const TrackInfo& b) {
                return statOf(a).playCount > statOf(b).playCount;
            });
            break;
        case SmartSort::RecentlyPlayed:
            std::stable_sort(matching.begin(), matching.end(), [&](const TrackInfo& a, const TrackInfo& b) {
                return statOf(a).lastPlayedMs > statOf(b).lastPlayedMs;
            });
            break;
        case SmartSort::RecentlyAdded:
            std::stable_sort(matching.begin(), matching.end(), [](const TrackInfo& a, const TrackInfo& b) {
                return a.addedMs > b.addedMs;
            });
            break;
        case SmartSort::Random:
            std::shuffle(matching.begin(), matching.end(), *QRandomGenerator::global());
            break;
        case SmartSort::Bpm:
            std::stable_sort(matching.begin(), matching.end(), [&](const TrackInfo& a, const TrackInfo& b) {
                const SongAnalysis* x = analysisFor ? analysisFor(a) : nullptr;
                const SongAnalysis* y = analysisFor ? analysisFor(b) : nullptr;
                const double bx = x && x->hasBpm() ? x->bpm : 1e9; // songs without a tempo last
                const double by = y && y->hasBpm() ? y->bpm : 1e9;
                return bx < by;
            });
            break;
    }
    if (limit > 0 && matching.size() > limit)
    {
        matching.resize(limit);
    }
    return matching;
}

const QVector<SmartPlaylist>& BuiltInSmartPlaylists()
{
    static const QVector<SmartPlaylist> lists = [] {
        auto make = [](const char* id, const char* name, QVector<SmartRule> rules, int limit, SmartSort sort) {
            SmartPlaylist p;
            p.id = QString::fromLatin1(id);
            p.name = QString::fromLatin1(name);
            p.rules = std::move(rules);
            p.limit = limit;
            p.sort = sort;
            p.builtIn = true;
            return p;
        };
        return QVector<SmartPlaylist>{
            make("builtin:loved", QT_TR_NOOP("Loved"),
                 {Rule(SmartField::Loved, SmartOperator::Yes)}, 0, SmartSort::Title),
            make("builtin:most-played", QT_TR_NOOP("Most Played"),
                 {Rule(SmartField::PlayCount, SmartOperator::MoreThan, "0")}, 100, SmartSort::MostPlayed),
            make("builtin:recently-played", QT_TR_NOOP("Recently Played"),
                 {Rule(SmartField::PlayCount, SmartOperator::MoreThan, "0")}, 100, SmartSort::RecentlyPlayed),
            make("builtin:recently-added", QT_TR_NOOP("Recently Added"),
                 {}, 100, SmartSort::RecentlyAdded),
            make("builtin:never-played", QT_TR_NOOP("Never Played"),
                 {Rule(SmartField::PlayCount, SmartOperator::Is, "0")}, 0, SmartSort::RecentlyAdded),
        };
    }();
    return lists;
}

QVector<SmartPlaylist> LoadSmartPlaylists()
{
    QVector<SmartPlaylist> result;
    for (const QJsonValue& value : ReadJsonFile(AppDataFolder() + "/smartplaylists.json")["playlists"].toArray())
    {
        const QJsonObject o = value.toObject();
        SmartPlaylist p;
        p.id = o["id"].toString();
        p.name = o["name"].toString();
        p.matchAll = o["matchAll"].toBool(true);
        p.limit = o["limit"].toInt();
        p.sort = FromId(o["sort"].toString(), kSortIds, SmartSort::Title);
        for (const QJsonValue& r : o["rules"].toArray())
        {
            const QJsonObject ro = r.toObject();
            SmartRule rule;
            rule.field = FromId(ro["field"].toString(), kFieldIds, SmartField::Genre);
            rule.op = FromId(ro["op"].toString(), kOperatorIds, SmartOperator::Contains);
            if (!OperatorsFor(rule.field).contains(rule.op))
            {
                rule.op = OperatorsFor(rule.field).first();
            }
            rule.value = ro["value"].toString();
            p.rules << rule;
        }
        if (p.id.isEmpty())
        {
            p.id = QUuid::createUuid().toString(QUuid::WithoutBraces);
        }
        if (!p.name.isEmpty())
        {
            result << p;
        }
    }
    return result;
}

void SaveSmartPlaylists(const QVector<SmartPlaylist>& playlists)
{
    QJsonArray array;
    for (const SmartPlaylist& p : playlists)
    {
        QJsonArray rules;
        for (const SmartRule& r : p.rules)
        {
            rules << QJsonObject{{"field", QLatin1String(kFieldIds[static_cast<int>(r.field)])},
                                 {"op", QLatin1String(kOperatorIds[static_cast<int>(r.op)])},
                                 {"value", r.value}};
        }
        array << QJsonObject{{"id", p.id}, {"name", p.name}, {"matchAll", p.matchAll}, {"limit", p.limit},
                             {"sort", QLatin1String(kSortIds[static_cast<int>(p.sort)])}, {"rules", rules}};
    }
    WriteJsonFile(AppDataFolder() + "/smartplaylists.json", QJsonObject{{"playlists", array}});
}

std::optional<MusicKey> ParseMusicKey(const QString& input)
{
    const QString text = input.trimmed();
    if (text.isEmpty())
    {
        return std::nullopt;
    }
    // Camelot: "8A", "11b".
    static const QRegularExpression camelot(QStringLiteral(R"(^(\d{1,2})\s*([AaBb])$)"));
    if (const QRegularExpressionMatch m = camelot.match(text); m.hasMatch())
    {
        const QString code = m.captured(1).toInt() == 0 ? QString() : QString::number(m.captured(1).toInt()) + m.captured(2).toUpper();
        for (int index = 0; index < 24; ++index)
        {
            if (MusicKey::fromIndex(index).camelot() == code)
            {
                return MusicKey::fromIndex(index);
            }
        }
        return std::nullopt;
    }
    // A note name: "A", "Am", "F#m", "E♭", "Bb minor", "C major".
    static const QRegularExpression note(QStringLiteral(R"(^([A-Ga-g])\s*([#♯b♭]?)\s*(m|min|minor|maj|major)?$)"),
                                         QRegularExpression::CaseInsensitiveOption);
    const QRegularExpressionMatch m = note.match(text);
    if (!m.hasMatch())
    {
        return std::nullopt;
    }
    static const int kNaturals[] = {9, 11, 0, 2, 4, 5, 7}; // A B C D E F G
    int pitch = kNaturals[m.captured(1).toUpper().at(0).unicode() - 'A'];
    const QString accidental = m.captured(2);
    if (accidental == "#" || accidental == QStringLiteral("♯"))
    {
        pitch += 1;
    }
    else if (accidental == "b" || accidental == QStringLiteral("♭"))
    {
        pitch -= 1;
    }
    MusicKey key;
    key.pitchClass = (pitch + 12) % 12;
    key.isMinor = m.captured(3).toLower().startsWith("m") && !m.captured(3).toLower().startsWith("maj");
    return key;
}

} // namespace audioforge
