#pragma once

#include "audioforge/theme.hpp"

#include <QJsonObject>
#include <QPointer>
#include <QUrlQuery>
#include <QVector>
#include <QWidget>

class QComboBox;
class QLabel;
class QLineEdit;
class QListWidget;
class QButtonGroup;
class QNetworkAccessManager;
class QNetworkReply;
class QTimer;

namespace audioforge {

class WebImageCache;
class OnlineRowDelegate;

// A station from the Radio Browser directory (radio-browser.info).
struct RadioStation
{
    QString uuid;
    QString name;
    QString url;         // the stream (Radio Browser's url_resolved)
    QString favicon;
    QString tags;        // "pop,rock,80s"
    QString country;
    QString countryCode;
    QString codec;
    int bitrate = 0;
    bool hls = false;

    bool isValid() const { return !url.isEmpty(); }
    QString details() const; // "Germany · pop, rock · MP3 128 kbps"
    QJsonObject toJson() const;
    static RadioStation fromJson(const QJsonObject& object); // ours, or Radio Browser's own fields
};

// The Radio tab: search by name or tag, a country filter, Top stations,
// Favorites and Recently played. Nothing is fetched until it's first shown.
class RadioPage : public QWidget
{
    Q_OBJECT

public:
    RadioPage(QNetworkAccessManager* network, WebImageCache* images, QWidget* parent = nullptr);

    void activate(); // the tab was opened: the first time, it loads the countries and Top
    void setColors(const theme::Tokens& tokens);
    void setPlayingUrl(const QString& url); // the station playing now, in the accent color
    void stationPlayed(const RadioStation& station); // into Recently played; Radio Browser counts the click
    QByteArray logo(const RadioStation& station) const; // its downloaded logo, if any (Now Playing's cover)

signals:
    void playRequested(const RadioStation& station);

protected:
    bool eventFilter(QObject* watched, QEvent* event) override; // a click: the star, or play

private:
    enum class Mode { Top, Favorites, Recent, Search };
    void showMode(Mode mode);
    void search();
    void requestStations(const QVector<QUrlQuery>& queries);
    void showStations(const QVector<RadioStation>& stations, const QString& emptyText);
    void loadCountries();
    void toggleFavorite(const RadioStation& station);
    bool isFavorite(const QString& uuid) const;
    void refreshStars();
    void save() const;
    QUrl apiUrl(const QString& path, const QUrlQuery& query = QUrlQuery()) const;
    QNetworkReply* apiGet(const QString& path, const QUrlQuery& query = QUrlQuery());

    QNetworkAccessManager* m_network;
    WebImageCache* m_images;
    OnlineRowDelegate* m_delegate;
    QLabel* m_subtitle;
    QLineEdit* m_search;
    QComboBox* m_country;
    QButtonGroup* m_modes;
    QListWidget* m_list;
    QLabel* m_status;
    QTimer* m_searchDelay;

    Mode m_mode = Mode::Top;
    bool m_activated = false;
    int m_server = 0;         // which Radio Browser mirror (the next one after a failure)
    int m_generation = 0;     // replies for an older request are dropped
    QVector<RadioStation> m_shown;
    QVector<RadioStation> m_favorites;
    QVector<RadioStation> m_recent; // newest first
    QString m_playingUrl;
};

} // namespace audioforge
