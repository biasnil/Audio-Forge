#include "audioforge/track_info.hpp"
#include "audioforge/translations.hpp"

#include <QDateTime>
#include <QFileInfo>

namespace audioforge {

QString FormatAudioInfo(const TrackInfo& t)
{
    if (t.bitrateKbps <= 0)
    {
        return QString();
    }
    QString channelsText = t.channels == 1 ? Tr("Mono") : t.channels == 2 ? Tr("Stereo") : Tr("%1 channels").arg(t.channels);
    QString format = QFileInfo(t.path).suffix().toUpper();
    if (format.isEmpty())
    {
        format = Tr("Audio");
    }
    return QString("%1  •  %2 kb/s  •  %3 kHz  •  %4")
        .arg(format)
        .arg(t.bitrateKbps)
        .arg(t.sampleRateHz / 1000.0, 0, 'f', 1)
        .arg(channelsText);
}

QString FormatDuration(int seconds)
{
    return QString("%1:%2").arg(seconds / 60).arg(seconds % 60, 2, 10, QChar('0'));
}

qint64 FileAddedMs(const QFileInfo& file)
{
    const QDateTime born = file.birthTime();
    return born.isValid() ? born.toMSecsSinceEpoch() : file.lastModified().toMSecsSinceEpoch();
}

} // namespace audioforge
