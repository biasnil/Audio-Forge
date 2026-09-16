#pragma once

#include <QDialog>
#include <QVector>
#include "audioforge/track_info.hpp"

class QListWidget;

namespace audioforge {

// Editable view of one playlist: remove a track, add more tracks, delete
// the whole playlist, or double-click a track to play it (and queue the
// rest of the playlist). Edits `playlist` in place.
class PlaylistEditDialog : public QDialog
{
    Q_OBJECT

public:
    PlaylistEditDialog(PlaylistData& playlist, const QVector<TrackInfo>& allTracks, QWidget* parent = nullptr);

    QString chosenPath() const { return m_chosenPath; }
    bool wasDeleted() const { return m_deleted; }

private:
    QString labelFor(const QString& path) const;
    void refreshList();
    void removeSelected();
    void addTracks();
    void confirmDelete();

    PlaylistData& m_playlist;
    QVector<TrackInfo> m_allTracks;
    QListWidget* m_list = nullptr;
    QString m_chosenPath;
    bool m_deleted = false;
};

} // namespace audioforge
