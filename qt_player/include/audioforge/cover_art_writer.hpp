#pragma once

#include <QString>
#include <QByteArray>

namespace audioforge {

// Replaces a track's embedded cover art (ID3v2 APIC frame) with
// `imageData`, dropping whatever picture (if any) was there before -- a
// full replace, not an add, so the file doesn't end up with two "front
// cover" pictures. `mimeType` should describe imageData's actual format
// ("image/jpeg" or "image/png"). Returns false if the file couldn't be
// opened or saved.
bool WriteCoverArt(const QString& path, const QByteArray& imageData, const QString& mimeType);

} // namespace audioforge
