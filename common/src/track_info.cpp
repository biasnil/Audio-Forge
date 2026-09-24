#include "audioforge/track_info.hpp"

#include <QFileInfo>

namespace audioforge {

QString FormatAudioInfo(const TrackInfo& t)
{
    if (t.bitrateKbps <= 0)
    {
        return QString();
    }
    QString channelsText = t.channels == 1 ? "Mono" : t.channels == 2 ? "Stereo" : QString("%1ch").arg(t.channels);
    QString format = QFileInfo(t.path).suffix().toUpper();
    if (format.isEmpty())
    {
        format = "Audio";
    }
    return QString("%1  •  %2 kb/s  •  %3 kHz  •  %4")
        .arg(format)
        .arg(t.bitrateKbps)
        .arg(t.sampleRateHz / 1000.0, 0, 'f', 1)
        .arg(channelsText);
}

} // namespace audioforge
