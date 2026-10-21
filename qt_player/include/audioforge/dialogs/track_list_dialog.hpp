#pragma once

#include <QDialog>
#include <QVector>
#include "audioforge/track_info.hpp"

class QListWidget;

namespace audioforge {

// Read-only browse dialog for a set of tracks (used by Albums, Artists and
// smart playlists) -- double-click a track to play it (and queue the rest of
// the group). `emptyText` is shown instead of an empty list.
class TrackListDialog : public QDialog
{
    Q_OBJECT

public:
    TrackListDialog(const QString& title, const QVector<TrackInfo>& tracks, QWidget* parent = nullptr,
                    const QString& emptyText = QString());

    QString chosenPath() const { return m_chosenPath; }

private:
    QString m_chosenPath;
};

} // namespace audioforge
