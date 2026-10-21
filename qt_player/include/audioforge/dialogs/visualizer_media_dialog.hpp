#pragma once

#include <QDialog>
#include <QVector>

#include "audioforge/track_info.hpp"
#include "audioforge/visualizer_media.hpp"

class QFrame;
class QVBoxLayout;

namespace audioforge {

// Settings > Visualizer > Custom GIFs and Videos: one for every song, plus
// ones for chosen songs. Each is a card with a moving preview (round or
// square, spinning or still, exactly as Now Playing shows it), its file name,
// which songs use it, its own options and buttons. Edits the library in
// place; the caller saves it afterwards.
class VisualizerMediaDialog : public QDialog
{
public:
    VisualizerMediaDialog(VisualizerMediaLibrary& library, const QVector<TrackInfo>& allTracks, QWidget* parent = nullptr);

private:
    void refresh();
    QFrame* makeCard(const VisualizerMediaEntry& entry);
    VisualizerMediaEntry* entryById(const QString& id);
    void chooseGlobal();
    void addEntry();
    void editSongs(const QString& id);
    void removeEntry(const QString& id);
    QString chooseFile();

    VisualizerMediaLibrary& m_library;
    QVector<TrackInfo> m_allTracks;
    QVBoxLayout* m_globalBox = nullptr;
    QVBoxLayout* m_entriesBox = nullptr;
};

} // namespace audioforge
