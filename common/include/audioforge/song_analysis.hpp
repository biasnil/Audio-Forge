#pragma once

#include <QHash>
#include <QString>
#include <QVector>

#include <optional>

#include "audioforge/track_info.hpp"

namespace audioforge {

// What "Analyze Your Songs' Key and BPM" found in a song's audio -- a port of
// the iOS app's SongAnalysis (ios/AudioForge/SongData.swift), same fields and
// meaning, so both apps make the same decisions about the same file.
struct SongAnalysis
{
    static constexpr int kCurrentVersion = 1;

    int version = kCurrentVersion;
    double duration = 0.0;     // length of the analyzed audio; a changed file gets re-analyzed
    double bpm = 0.0;          // 0 = no steady beat
    double bpmConfidence = 0.0; // 0..1
    double firstBeat = 0.0;    // beat grid = firstBeat + n * 60/bpm
    int keyIndex = -1;         // 0-11 = C..B major, 12-23 = C..B minor, -1 = unclear
    double keyConfidence = 0.0;
    double audioStart = 0.0;   // first / last moments that aren't silence
    double audioEnd = 0.0;
    double outroStart = 0.0;   // where the ending fades out, <= audioEnd

    bool hasBpm() const { return bpm > 0.0; }
    double beatPeriod() const { return hasBpm() ? 60.0 / bpm : 0.0; }
    bool isBeatReliable() const { return hasBpm() && bpmConfidence >= 0.5; } // steady enough to line beats up
};

// A musical key with its Camelot-wheel code (8A, 8B...) used for harmonic mixing.
struct MusicKey
{
    int pitchClass = 0; // 0 = C ... 11 = B
    bool isMinor = false;

    static MusicKey fromIndex(int index);
    int camelotNumber() const;   // 1-12 (C major = 8B, A minor = 8A)
    QString camelot() const;     // "8A" / "8B"
    QString shortName() const;   // "Am", "E♭"
    bool isCompatibleWith(const MusicKey& other) const; // same, relative, or one step around the wheel
    bool operator==(const MusicKey& o) const { return pitchClass == o.pitchClass && isMinor == o.isMinor; }
};

// Decodes the file (first 10 minutes at most) and analyzes it. Slow -- seconds
// per song -- so call it off the UI thread. Thread-safe (no shared state).
std::optional<SongAnalysis> AnalyzeSongFile(const QString& path);

// How well two songs follow each other: key and tempo agreement, 0..5.
double SongCompatibility(const SongAnalysis* a, const SongAnalysis* b);

// Harmonic shuffle: a greedy DJ-style order starting at `start` -- each next
// song is the best match among a few random candidates, so it's still a
// shuffle, just a smooth one. Songs without analysis count as neutral.
QVector<int> HarmonicOrder(const QVector<TrackInfo>& items, int start,
                           const QHash<QString, SongAnalysis>& analysis);

} // namespace audioforge
