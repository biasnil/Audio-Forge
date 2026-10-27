#pragma once

#include <QString>

#include "miniaudio.h"

namespace audioforge {

// AAC / M4A audio (.m4a, .m4b, .aac -- podcasts, mostly) for the audio
// engine. miniaudio only decodes MP3, FLAC and WAV, so these files are
// decoded with FFmpeg (the same libraries Qt Multimedia already ships for
// the video wallpapers) into a miniaudio data source, which the engine plays
// like any other sound: EQ, crossfades, ReplayGain and the visualizer all
// still apply.

// By extension.
bool IsFfmpegAudioFile(const QString& path);

// An http(s) address: an internet radio stream (Icecast/SHOUTcast or HLS).
bool IsStreamUrl(const QString& path);

// Opens the file; nullptr if it can't be (no audio stream, unsupported codec).
// Float samples at the file's own rate, mono or stereo (more channels are
// mixed down). Close it after the sound using it has been uninitialized.
// A stream URL opens "live": decoded ahead on its own thread into a buffer
// (the audio thread never waits on the network), with no length and no
// seeking. Connecting blocks, so open URLs off the UI thread.
ma_data_source* OpenFfmpegAudio(const QString& path);
void CloseFfmpegAudio(ma_data_source* source); // null is fine

bool IsFfmpegLive(const ma_data_source* source); // null (not an FFmpeg source) -> false
QString FfmpegStreamTitle(const ma_data_source* source); // the ICY StreamTitle, if the station sends one

} // namespace audioforge
