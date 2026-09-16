#pragma once

#include <QDialog>
#include <QVector>
#include "audioforge/track_info.hpp"

class QListWidget;

namespace audioforge {

// Read-only browse dialog for a set of tracks (used by Albums and Artists)
// -- double-click a track to play it (and queue the rest of the group).
class TrackListDialog : public QDialog
{
    Q_OBJECT

public:
    TrackListDialog(const QString& title, const QVector<TrackInfo>& tracks, QWidget* parent = nullptr);

    QString chosenPath() const { return m_chosenPath; }

private:
    QString m_chosenPath;
};

} // namespace audioforge
