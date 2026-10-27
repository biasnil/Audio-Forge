#pragma once

#include <QObject>
#include <QString>

#include <functional>
#include <optional>

class QNetworkAccessManager;

// Song details and covers from the web for "Find missing info" (the tag
// editor) and "Find missing covers" (Settings): Apple's search first, then
// Deezer's. Neither needs an account. Nothing here writes to a file.
namespace audioforge {

struct OnlineSongInfo
{
    QString title;
    QString artist;
    QString album;
    QString albumArtist;
    QString genre;
    int year = 0;
    int trackNumber = 0;
    QString coverUrl; // a large one (600 px from Apple, 1000 px from Deezer)
    QString source;   // "Apple Music" / "Deezer"
};

// A song by artist and title (album helps pick the right one). `done` gets
// nothing when neither service knows it. `context` going away cancels the call.
void LookupSongInfo(QNetworkAccessManager* network, QObject* context, const QString& artist, const QString& title,
                    const QString& album, const std::function<void(std::optional<OnlineSongInfo>)>& done);

// An album's cover: `done(url, source)`, an empty url when there's none.
void LookupAlbumCover(QNetworkAccessManager* network, QObject* context, const QString& artist, const QString& album,
                      const std::function<void(const QString&, const QString&)>& done);

// For a song without an album: the cover of the release it's on.
void LookupSongCover(QNetworkAccessManager* network, QObject* context, const QString& artist, const QString& title,
                     const std::function<void(const QString&, const QString&)>& done);

// For comparing names across services: lower case, accents and punctuation
// gone, "(Remastered)"-style extras dropped.
QString NormalizedName(const QString& name);

} // namespace audioforge
