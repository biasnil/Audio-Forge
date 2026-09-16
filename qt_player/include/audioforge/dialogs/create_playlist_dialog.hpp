#pragma once

#include <QDialog>
#include <QVector>
#include "audioforge/track_info.hpp"

class QLineEdit;
class QListWidget;

namespace audioforge {

// "Create new playlist" dialog: a name field + a checkbox list of every
// known track.
class CreatePlaylistDialog : public QDialog
{
    Q_OBJECT

public:
    CreatePlaylistDialog(const QVector<TrackInfo>& allTracks, QWidget* parent = nullptr);

    QString resultName() const;
    QStringList resultTrackPaths() const;

private:
    void tryAccept();

    QLineEdit* m_nameEdit = nullptr;
    QListWidget* m_trackList = nullptr;
};

} // namespace audioforge
