#pragma once

#include <QString>
#include <QVector>

namespace audioforge {

// One timed lyric line, in seconds from track start.
struct SyncedLyricLine
{
    float seconds = 0.0f;
    QString text;
};

// A lyrics lookup's result. `rawText` is empty if nothing was found;
// `synced` says whether rawText is still .lrc-formatted (timestamps
// intact) -- the caller decides what to do with each case (build a
// time-synced view, or just display plain text).
struct LyricsResult
{
    QString rawText;
    bool synced = false;
};

// Parses .lrc-formatted text into a time-sorted list of (seconds, text)
// pairs -- one entry per [mm:ss.xx] tag, so a line with several tags (used
// in .lrc for lyrics that repeat at different times) yields one entry per
// tag. Lines with no recognizable timestamp tag (plain text, or .lrc
// metadata tags like [ar:...]/[ti:...]) are skipped. Returns an empty
// vector if `lrcText` has no valid timestamp tags at all -- callers should
// treat that as "not actually synced" and fall back to plain display.
QVector<SyncedLyricLine> ParseSyncedLyrics(const QString& lrcText);

// Looks for a same-name .lrc or .txt file next to `trackPath` (e.g.
// "Song.mp3" -> "Song.lrc" or "Song.txt"). This is the free,
// no-license-required alternative to online lyrics sources: files placed
// here are ones the user already has the rights to (self-authored, or
// downloaded for personal use from a lyrics site), the same trust model
// already used for folder cover art (cover.jpg next to the track).
LyricsResult ReadSidecarLyrics(const QString& trackPath);

// %TEMP%/AudioForge/lyrics/<hash of trackPath>.lrc|.txt -- caches lyrics
// fetched from LRCLIB (which explicitly permits this: it's built for FOSS
// players to store and reuse) so replaying the same track doesn't re-hit
// the network. NOT used for Musixmatch's snippet, which is under a
// separate commercial license that doesn't carry the same permission.
LyricsResult ReadCachedLyrics(const QString& trackPath);
void WriteCachedLyrics(const QString& trackPath, const QString& lyricsText, bool synced);

} // namespace audioforge