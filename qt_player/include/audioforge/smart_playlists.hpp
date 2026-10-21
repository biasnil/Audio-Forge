#pragma once

#include <QHash>
#include <QString>
#include <QVector>

#include <functional>
#include <optional>

#include "audioforge/song_analysis.hpp"
#include "audioforge/song_data.hpp"
#include "audioforge/track_info.hpp"

namespace audioforge {

// Smart playlists: rules like "genre contains Rock and year is more than
// 2010" that update themselves -- a port of the Android app's
// SmartPlaylists.kt, same fields, operators and built-in lists.

enum class SmartField { Title, Artist, Album, Genre, Year, PlayCount, LastPlayedDays, AddedDays, Loved, Bpm, Key, Minutes };
enum class SmartOperator { Contains, Is, IsNot, MoreThan, LessThan, Yes, No };
enum class SmartSort { Title, MostPlayed, RecentlyPlayed, RecentlyAdded, Random, Bpm };

enum class SmartFieldKind { Text, Number, Bool };
SmartFieldKind KindOf(SmartField field);
QVector<SmartOperator> OperatorsFor(SmartField field);

// Translated names for the rule editor.
QString SmartFieldName(SmartField field);
QString SmartOperatorName(SmartOperator op);
QString SmartSortName(SmartSort sort);

struct SmartRule
{
    SmartField field = SmartField::Genre;
    SmartOperator op = SmartOperator::Contains;
    QString value;
};

struct SmartPlaylist
{
    QString id;   // "builtin:..." for the built-in lists
    QString name; // built-in lists: English, shown translated (displayName())
    QVector<SmartRule> rules;
    bool matchAll = true;
    int limit = 0; // 0 = no limit
    SmartSort sort = SmartSort::Title;
    bool builtIn = false; // can't be edited or deleted

    QString displayName() const;

    // The songs that match, sorted and limited.
    QVector<TrackInfo> tracks(const QVector<TrackInfo>& library, const QHash<QString, SongStats>& stats,
                              const std::function<const SongAnalysis*(const TrackInfo&)>& analysisFor,
                              const std::function<QString(const TrackInfo&)>& genreOf, // the shown genre
                              qint64 nowMs) const;
};

// Loved, Most Played, Recently Played, Recently Added, Never Played.
const QVector<SmartPlaylist>& BuiltInSmartPlaylists();

// The user's own, in smartplaylists.json next to AudioForge.ini.
QVector<SmartPlaylist> LoadSmartPlaylists();
void SaveSmartPlaylists(const QVector<SmartPlaylist>& playlists);

// "8A" / "Am" / "A minor" / "E♭" / "Eb major" -> the key.
std::optional<MusicKey> ParseMusicKey(const QString& text);

} // namespace audioforge
