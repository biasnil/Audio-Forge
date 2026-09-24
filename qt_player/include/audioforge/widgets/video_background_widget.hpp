#pragma once

#include <QGraphicsScene>
#include <QGraphicsView>

class QGraphicsVideoItem;
class QMediaPlayer;
class QAudioOutput;

namespace audioforge {

// Background video layer for the live wallpaper feature (see
// WallpaperLibrary). Muted, looping, no transport controls -- a
// QGraphicsVideoItem inside a QGraphicsView rather than a plain
// QVideoWidget, because the fill-and-crop scaling needs a transform
// computed manually (same principle as ScaledCoverArt() in
// player_window.cpp -- KeepAspectRatioByExpanding, then a centered crop --
// just applied to this widget's size instead of a fixed QLabel size).
class VideoBackgroundWidget : public QGraphicsView
{
    Q_OBJECT

public:
    explicit VideoBackgroundWidget(QWidget* parent = nullptr);

    // Empty path stops playback and clears the item -- used when
    // resolution comes back with nothing to show.
    void setVideoPath(const QString& path);

    // Hook for PlayerWindow::changeEvent's minimize/restore handling.
    void setPaused(bool paused);

    // 0.0 (invisible) to 1.0 (fully visible, the default). Lets the Settings
    // tab's opacity slider fade the video without touching playback state.
    void setOpacity(qreal opacity);

protected:
    void resizeEvent(QResizeEvent* event) override;

private slots:
    // The video's native resolution isn't known until playback starts
    // decoding, which can arrive after resizeEvent already fired once with
    // nativeSize() still empty -- this recomputes the transform once it's
    // actually known.
    void onVideoNativeSizeChanged();

private:
    void updateTransform();

    QGraphicsScene m_scene;
    QGraphicsVideoItem* m_videoItem;
    QMediaPlayer* m_player;
    QAudioOutput* m_audioOutput; // exists only so QMediaPlayer has an output to bind; volume forced to 0 -- there is no audio track expected, but Qt Multimedia wants an output object regardless
};

} // namespace audioforge