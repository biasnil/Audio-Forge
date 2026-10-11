#pragma once

#include <QDialog>
#include <QVector>

#include <functional>

#include "audioforge/track_info.hpp"

class QTreeWidget;
class QPushButton;
class QLabel;

namespace audioforge {

// Same song stored more than once: same title and artist (ignoring case,
// punctuation and "(Remastered)"-style suffixes) and a length within 2 s.
// Port of the iOS DuplicateFinder.
QVector<QVector<TrackInfo>> FindDuplicateGroups(const QVector<TrackInfo>& tracks);

// Lists the duplicate groups. Double-click plays a copy; a copy can be shown
// in Explorer or moved to the Recycle Bin.
class DuplicatesDialog : public QDialog
{
    Q_OBJECT

public:
    using PlayFn = std::function<void(const QVector<TrackInfo>& group, int index)>;
    using DeletedFn = std::function<void(const QString& path)>;

    DuplicatesDialog(const QVector<TrackInfo>& tracks, PlayFn play, DeletedFn deleted, QWidget* parent = nullptr);

private:
    void rebuild();
    void updateButtons();
    void showSelectedInFolder();
    void trashSelected();

    QVector<QVector<TrackInfo>> m_groups;
    PlayFn m_play;
    DeletedFn m_deleted;
    QTreeWidget* m_tree = nullptr;
    QLabel* m_summary = nullptr;
    QPushButton* m_showButton = nullptr;
    QPushButton* m_trashButton = nullptr;
};

} // namespace audioforge
