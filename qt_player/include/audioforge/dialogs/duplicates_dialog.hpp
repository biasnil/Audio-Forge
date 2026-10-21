#pragma once

#include <QDialog>
#include <QHash>
#include <QSet>
#include <QVector>

#include <functional>

#include "audioforge/duplicate_finder.hpp"
#include "audioforge/track_info.hpp"

class QCheckBox;
class QFrame;
class QLabel;
class QPushButton;
class QVBoxLayout;
class QWidget;

namespace audioforge {

// Settings > Find Duplicates: copies of the same song (see duplicate_finder),
// "Same song" first, then "Similar names". Each song is a card with one row
// per copy: tick box, cover, folder, length, bitrate, size, the best-quality
// copy marked, and play / show in folder / Recycle Bin buttons. Tick several
// and move them to the Recycle Bin at once; "Not duplicates" (with Undo)
// keeps a false match from showing again, and "Show dismissed" lists those to
// restore.
class DuplicatesDialog : public QDialog
{
    Q_OBJECT

public:
    using PlayFn = std::function<void(const QVector<TrackInfo>& group, int index)>;
    using DeletedFn = std::function<void(const QString& path)>;

    DuplicatesDialog(const QVector<TrackInfo>& tracks, PlayFn play, DeletedFn deleted, QWidget* parent = nullptr);

private:
    struct CopyRow
    {
        int group; // index in m_result.groups (or .dismissed)
        int copy;
        bool dismissed;
    };
    struct Tick
    {
        QCheckBox* box;
        QString path;
        int group; // index in m_result.groups
    };

    bool eventFilter(QObject* watched, QEvent* event) override; // double-click a copy: play it
    void refind();  // runs the finder again (after a dismissal, a restore or a delete)
    void rebuild(); // the cards, from m_result
    QFrame* makeCard(const DuplicateGroup& group, int index, bool dismissed);
    QWidget* makeEmptyState();
    void updateButtons();
    QStringList checkedPaths() const;
    void trash(const QStringList& paths);
    void markNotDuplicates(int groupIndex);
    void restoreDismissed(int groupIndex);
    void setDismissed(const QSet<QString>& pairs, bool offerUndo);

    QVector<TrackInfo> m_tracks;
    QSet<QString> m_dismissed;
    QSet<QString> m_beforeLastDismiss; // for Undo
    DuplicateResult m_result;
    PlayFn m_play;
    DeletedFn m_deleted;
    bool m_dark = true;

    QLabel* m_summary = nullptr;
    QVBoxLayout* m_cards = nullptr;
    QVector<Tick> m_ticks;
    QHash<QObject*, CopyRow> m_copyRows;
    QCheckBox* m_showDismissed = nullptr;
    QPushButton* m_trashButton = nullptr;
    QWidget* m_undoBar = nullptr;
};

} // namespace audioforge
