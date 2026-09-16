#pragma once

#include <QMap>
#include <QString>

namespace audioforge {

// Generic tag access, additive to track_metadata.hpp's ReadTrackInfo() /
// WriteBasicTags(). Where those two only know about Title/Artist/Album/Year,
// these read and write EVERY key TagLib's PropertyMap exposes for the file
// (ALBUM, ALBUMARTIST, COMMENT, COMPILATION, COMPOSER, DATE, ENCODING,
// GENRE, ... whatever the format/tagging convention supports) -- this is
// what backs the manual tag editor, where the user can edit or delete any
// key, or add a brand new one.
//
// Keys are the upper-case TagLib property names (e.g. "ALBUMARTIST").
// Values are joined with "; " if a key has multiple values (rare in
// practice for these fields); ReadAllTags never fails outright -- a file
// TagLib can't open at all just yields an empty map.
QMap<QString, QString> ReadAllTags(const QString& path);

// Replaces the file's tag block with exactly `tags` (any existing key not
// present in `tags` is removed -- this is what makes the dialog's "X"
// delete-tag button actually delete the tag on save, not just clear it).
// Returns false if the file couldn't be opened/saved.
bool WriteAllTags(const QString& path, const QMap<QString, QString>& tags);

} // namespace audioforge
