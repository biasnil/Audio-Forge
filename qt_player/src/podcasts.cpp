#include "audioforge/podcasts.hpp"

#include <QDir>
#include <QFileInfo>
#include <QMap>

#include <algorithm>

namespace audioforge {

QVector<PodcastShow> PodcastShows(const MusicLibrary& library)
{
    QMap<QString, QVector<TrackInfo>> byShow; // lower-case name -> episodes (case-insensitive A-Z)
    QMap<QString, QString> displayName;
    for (const TrackInfo& episode : library.podcasts())
    {
        const QString root = library.podcastFolderOf(episode.path);
        const QString relative = QDir(root).relativeFilePath(episode.path);
        const int slash = relative.indexOf('/');
        QString name = slash > 0 ? relative.left(slash) : episode.album.trimmed();
        if (name.isEmpty())
        {
            name = QFileInfo(root).fileName().isEmpty() ? root : QFileInfo(root).fileName();
        }
        const QString key = name.toLower();
        byShow[key] << episode;
        displayName.insert(key, name);
    }

    QVector<PodcastShow> shows;
    for (auto it = byShow.begin(); it != byShow.end(); ++it)
    {
        QVector<TrackInfo> episodes = it.value();
        std::stable_sort(episodes.begin(), episodes.end(), [](const TrackInfo& a, const TrackInfo& b) {
            if (a.addedMs != b.addedMs)
            {
                return a.addedMs > b.addedMs;
            }
            return QString::compare(a.title, b.title, Qt::CaseInsensitive) < 0;
        });
        shows.append({displayName.value(it.key()), episodes});
    }
    return shows;
}

} // namespace audioforge
