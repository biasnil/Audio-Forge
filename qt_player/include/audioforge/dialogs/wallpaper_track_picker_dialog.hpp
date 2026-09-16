#pragma once

#include <QDialog>
#include <QVector>
#include "audioforge/track_info.hpp"

class QListWidget;

namespace audioforge {

// Checkbox list of every known track, for picking which tracks a wallpaper
// video applies to -- reuses CreatePlaylistDialog's "pick tracks by
// checkbox" pattern, minus the name field. Used for both "Add Wallpaper..."
// (starts with nothing checked) and "Edit Tracks..." (starts pre-checked
// with the entry's current trackPaths).
class WallpaperTrackPickerDialog : public QDialog
{
    Q_OBJECT

public:
    WallpaperTrackPickerDialog(const QVector<TrackInfo>& allTracks,
        const QStringList& initiallyChecked, QWidget* parent = nullptr);

    QStringList resultTrackPaths() const;

private:
    QListWidget* m_trackList = nullptr;
};

} // namespace audioforge
