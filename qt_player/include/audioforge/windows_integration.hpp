#pragma once

#include <QByteArray>
#include <QObject>
#include <QString>

#include <memory>

class QWidget;

namespace audioforge {

// Windows' own playback surfaces, for the main window:
//  - the system media controls (the volume / media overlay, the lock screen,
//    and the keyboard's media keys while AudioForge is in the background):
//    the song, its cover, and Play / Pause / Next / Previous;
//  - the taskbar: Previous / Play-Pause / Next buttons under the thumbnail
//    preview, and the song's progress on the taskbar icon.
// Elsewhere (Linux, macOS) everything here does nothing.
class WindowsMediaControls : public QObject
{
    Q_OBJECT

public:
    explicit WindowsMediaControls(QWidget* window);
    ~WindowsMediaControls() override;

    void setTrack(const QString& title, const QString& artist, const QString& album, const QByteArray& cover);
    void setPlaybackState(bool hasTrack, bool playing);
    void setProgress(double positionSeconds, double lengthSeconds); // taskbar icon; cheap to call often

    // From the window's nativeEvent(): the taskbar's "button created" message
    // and its thumbnail buttons' clicks. True when handled.
    bool handleNativeEvent(void* message, qintptr* result);

signals:
    void playPauseRequested();
    void playRequested();
    void pauseRequested();
    void nextRequested();
    void previousRequested();

private:
    struct Impl;
    std::unique_ptr<Impl> d;
};

} // namespace audioforge
