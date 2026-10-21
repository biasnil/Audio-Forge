#pragma once

#include <QSet>
#include <QString>
#include <QVector>

#include "audioforge/track_info.hpp"

namespace audioforge {

// The duplicate finder (Settings > Find Duplicates): copies of the same song,
// even when their names differ a little ("01 - Yellow" / "Yellow", "Don't
// Stop Me Now - 2011 Remaster" / "Dont Stop Me Now"). A port of the Android
// app's DuplicateFinder.kt, with the same rules and thresholds.

// Copies of one song. `similar`: at least one pair only has similar names
// (rather than the same title and artist). `best`: the copy to keep if one
// stands out (highest bitrate, then lossless), else empty.
struct DuplicateGroup
{
    QVector<TrackInfo> tracks;
    bool similar = false;
    QString best;
};

struct DuplicateResult
{
    QVector<DuplicateGroup> groups;    // "Same song" first, then "Similar names"; A-Z within each
    QVector<DuplicateGroup> dismissed; // the pairs the user marked "Not duplicates", grouped
};

// The key for one pair of songs, the same whichever way round.
QString DuplicatePairKey(const QString& a, const QString& b);
// Every pair among `paths`.
QSet<QString> PairsAmong(const QStringList& paths);
// "These songs aren't copies of the rest": the pairs among `selected`, and
// between `selected` and the group's other songs.
QSet<QString> PairsForSelection(const QStringList& selected, const QStringList& group);

// Finds groups of copies. Pairs in `dismissed` are never joined (a dismissed
// group falls apart); they form the result's dismissed groups instead.
DuplicateResult FindDuplicates(const QVector<TrackInfo>& tracks, const QSet<QString>& dismissed);

// duplicate_dismissals.json next to AudioForge.ini. Pairs rather than groups:
// when a new copy turns up it's compared again, and only the pairs the user
// dismissed stay dismissed. Pairs whose songs are gone are dropped on load.
QSet<QString> LoadDuplicateDismissals(const QVector<TrackInfo>& library);
void SaveDuplicateDismissals(const QSet<QString>& pairs);

// For tests: how alike two names are (0..1) and the name normalization.
double NameSimilarity(const QString& a, const QString& b, double atLeast = 0.0);
QString NormalizeSongName(const QString& text, bool isTitle);

} // namespace audioforge
