#include "audioforge/widgets/video_background_widget.hpp"

#include <QAudioOutput>
#include <QGraphicsVideoItem>
#include <QMediaPlayer>
#include <QResizeEvent>
#include <QUrl>

namespace audioforge {

VideoBackgroundWidget::VideoBackgroundWidget(QWidget* parent) : QGraphicsView(parent)
{
    // This widget is the opaque, bottom-most layer -- it needs to paint
    // normally. Translucency belongs on the *overlay* content that sits on
    // top of it (the Now Playing page's info box, see
    // PlayerWindow::buildNowPlayingPage()), not here.
    setStyleSheet("border: none; background: transparent;");
    setFrameShape(QFrame::NoFrame);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setVerticalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setRenderHint(QPainter::SmoothPixmapTransform);

    setScene(&m_scene);

    m_videoItem = new QGraphicsVideoItem();
    m_scene.addItem(m_videoItem); // scene takes ownership

    m_player = new QMediaPlayer(this);
    m_audioOutput = new QAudioOutput(this);
    m_audioOutput->setVolume(0.0f); // background layer -- never audible
    m_player->setAudioOutput(m_audioOutput);
    m_player->setVideoOutput(m_videoItem);
    m_player->setLoops(QMediaPlayer::Infinite);

    connect(m_videoItem, &QGraphicsVideoItem::nativeSizeChanged,
            this, &VideoBackgroundWidget::onVideoNativeSizeChanged);
}

void VideoBackgroundWidget::setVideoPath(const QString& path)
{
    if (path.isEmpty())
    {
        m_player->stop();
        m_player->setSource(QUrl());
        return;
    }

    m_player->setSource(QUrl::fromLocalFile(path));
    m_player->play();
}

void VideoBackgroundWidget::setPaused(bool paused)
{
    if (paused)
    {
        m_player->pause();
    }
    else if (m_player->mediaStatus() != QMediaPlayer::NoMedia)
    {
        m_player->play();
    }
}

void VideoBackgroundWidget::setOpacity(qreal opacity)
{
    m_videoItem->setOpacity(opacity); // QGraphicsItem's own opacity -- no repaint plumbing needed
}

void VideoBackgroundWidget::resizeEvent(QResizeEvent* event)
{
    QGraphicsView::resizeEvent(event);
    m_scene.setSceneRect(0, 0, width(), height());
    updateTransform();
}

void VideoBackgroundWidget::onVideoNativeSizeChanged()
{
    updateTransform();
}

void VideoBackgroundWidget::updateTransform()
{
    QSizeF nativeSize = m_videoItem->nativeSize();
    if (nativeSize.isEmpty() || width() <= 0 || height() <= 0)
    {
        return;
    }

    // QGraphicsVideoItem keeps a fixed default bounding rect (320x240)
    // until setSize() is called explicitly -- it does NOT auto-track
    // nativeSize() on its own. Without this, the scale/position math below
    // would be computed against the video's real resolution while the item
    // itself stayed sized at the default box, so nothing would land where
    // expected.
    m_videoItem->setSize(nativeSize);

    // Same "expand to cover, then crop the overflow" principle as
    // ScaledCoverArt() (Qt::KeepAspectRatioByExpanding): scale by whichever
    // axis needs to grow more to fully cover the viewport.
    qreal scale = qMax(width() / nativeSize.width(), height() / nativeSize.height());
    m_videoItem->setScale(scale);

    // Center the now-overhanging item -- the QGraphicsView clips anything
    // outside its viewport, so this crops the overflow symmetrically, the
    // same effect as ScaledCoverArt()'s centered QPixmap::copy().
    qreal scaledWidth = nativeSize.width() * scale;
    qreal scaledHeight = nativeSize.height() * scale;
    m_videoItem->setPos((width() - scaledWidth) / 2.0, (height() - scaledHeight) / 2.0);
}

} // namespace audioforge