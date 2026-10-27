#include "audioforge/radio_page.hpp"
#include "audioforge/app_files.hpp"
#include "audioforge/widgets/online_views.hpp"

#include <QButtonGroup>
#include <QComboBox>
#include <QDir>
#include <QEvent>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QJsonDocument>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QLocale>
#include <QMenu>
#include <QMouseEvent>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QPushButton>
#include <QSet>
#include <QTimer>
#include <QVBoxLayout>

#include <algorithm>
#include <memory>

namespace audioforge {

namespace {

// Radio Browser's mirrors: the next one is tried when one doesn't answer.
const char* const kServers[] = {"de1.api.radio-browser.info", "nl1.api.radio-browser.info", "at1.api.radio-browser.info"};
constexpr int kServerCount = 3;
constexpr int kListLimit = 100;
constexpr int kRecentLimit = 30;

QString RadioFile()
{
    return QDir(AppDataFolder()).filePath(QStringLiteral("radio.json"));
}

QJsonArray ToJson(const QVector<RadioStation>& stations)
{
    QJsonArray array;
    for (const RadioStation& station : stations)
    {
        array.append(station.toJson());
    }
    return array;
}

QVector<RadioStation> FromJson(const QJsonArray& array)
{
    QVector<RadioStation> stations;
    for (const QJsonValue& value : array)
    {
        const RadioStation station = RadioStation::fromJson(value.toObject());
        if (station.isValid())
        {
            stations << station;
        }
    }
    return stations;
}

} // namespace

QString RadioStation::details() const
{
    QStringList parts;
    if (!country.isEmpty())
    {
        parts << country;
    }
    const QStringList tagList = tags.split(',', Qt::SkipEmptyParts);
    if (!tagList.isEmpty())
    {
        parts << tagList.mid(0, 3).join(QStringLiteral(", "));
    }
    QString format = hls ? QStringLiteral("HLS") : codec;
    if (bitrate > 0)
    {
        format += (format.isEmpty() ? QString() : QStringLiteral(" ")) + QObject::tr("%1 kbps").arg(bitrate);
    }
    if (!format.isEmpty())
    {
        parts << format;
    }
    return parts.join(QStringLiteral(" · "));
}

QJsonObject RadioStation::toJson() const
{
    return QJsonObject{{"stationuuid", uuid}, {"name", name},     {"url_resolved", url}, {"favicon", favicon},
                       {"tags", tags},        {"country", country}, {"countrycode", countryCode},
                       {"codec", codec},      {"bitrate", bitrate}, {"hls", hls ? 1 : 0}};
}

RadioStation RadioStation::fromJson(const QJsonObject& o)
{
    RadioStation s;
    s.uuid = o.value("stationuuid").toString();
    s.name = o.value("name").toString().trimmed();
    s.url = o.value("url_resolved").toString();
    if (s.url.isEmpty())
    {
        s.url = o.value("url").toString();
    }
    s.favicon = o.value("favicon").toString();
    s.tags = o.value("tags").toString();
    s.country = o.value("country").toString();
    s.countryCode = o.value("countrycode").toString();
    s.codec = o.value("codec").toString();
    s.bitrate = o.value("bitrate").toInt();
    s.hls = o.value("hls").toInt() != 0;
    if (s.name.isEmpty())
    {
        s.name = QObject::tr("Unnamed station");
    }
    return s;
}

RadioPage::RadioPage(QNetworkAccessManager* network, WebImageCache* images, QWidget* parent)
    : QWidget(parent), m_network(network), m_images(images)
{
    const QJsonObject saved = ReadJsonFile(RadioFile());
    m_favorites = FromJson(saved.value("favorites").toArray());
    m_recent = FromJson(saved.value("recent").toArray());

    auto* l = new QVBoxLayout(this);
    l->setContentsMargins(28, 24, 28, 0);
    l->setSpacing(0);
    l->addLayout(OnlinePageTitle(tr("Radio"), &m_subtitle));
    m_subtitle->setText(tr("Stations from the Radio Browser directory"));

    // Search and country.
    auto* searchRow = new QHBoxLayout();
    searchRow->setContentsMargins(0, 18, 0, 0);
    searchRow->setSpacing(8);
    m_search = new QLineEdit();
    m_search->setPlaceholderText(tr("Search stations by name or tag"));
    m_search->setClearButtonEnabled(true);
    m_search->setAccessibleName(tr("Search stations"));
    searchRow->addWidget(m_search, 1);
    m_country = new QComboBox();
    m_country->addItem(tr("All countries"), QString());
    m_country->setMinimumWidth(200);
    m_country->setAccessibleName(tr("Country"));
    searchRow->addWidget(m_country);
    l->addLayout(searchRow);

    // Top / Favorites / Recently played.
    auto* modes = new QHBoxLayout();
    modes->setContentsMargins(0, 14, 0, 10);
    modes->setSpacing(6);
    m_modes = new QButtonGroup(this);
    m_modes->setExclusive(true);
    const QStringList names = {tr("Top stations"), tr("Favorites"), tr("Recently played")};
    for (int i = 0; i < names.size(); ++i)
    {
        QPushButton* pill = OnlinePill(names[i]);
        m_modes->addButton(pill, i);
        modes->addWidget(pill);
    }
    m_modes->button(0)->setChecked(true);
    modes->addStretch();
    l->addLayout(modes);

    m_list = new QListWidget();
    m_delegate = new OnlineRowDelegate(images, m_list);
    m_list->setItemDelegate(m_delegate);
    m_list->setMouseTracking(true);
    m_list->setUniformItemSizes(true);
    m_list->setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    m_list->setVerticalScrollMode(QAbstractItemView::ScrollPerPixel);
    m_list->setCursor(Qt::PointingHandCursor);
    m_list->setContextMenuPolicy(Qt::CustomContextMenu);
    m_list->viewport()->installEventFilter(this);
    l->addWidget(m_list, 1);
    m_status = new QLabel();
    m_status->setObjectName("PageSubtitle");
    m_status->setAlignment(Qt::AlignCenter);
    m_status->setWordWrap(true);
    m_status->hide();
    l->addWidget(m_status, 1);
    connect(images, &WebImageCache::loaded, m_list->viewport(), qOverload<>(&QWidget::update));

    m_searchDelay = new QTimer(this);
    m_searchDelay->setSingleShot(true);
    m_searchDelay->setInterval(450); // after typing stops
    connect(m_searchDelay, &QTimer::timeout, this, &RadioPage::search);
    connect(m_search, &QLineEdit::textChanged, this, [this](const QString& text) {
        if (text.trimmed().isEmpty())
        {
            m_searchDelay->stop();
            showMode(m_modes->checkedId() < 0 ? Mode::Top : static_cast<Mode>(m_modes->checkedId()));
        }
        else
        {
            m_searchDelay->start();
        }
    });
    connect(m_modes, &QButtonGroup::idClicked, this, [this](int id) {
        m_search->blockSignals(true);
        m_search->clear(); // a list, not the search results
        m_search->blockSignals(false);
        showMode(static_cast<Mode>(id));
    });
    connect(m_country, &QComboBox::currentIndexChanged, this, [this]() {
        if (m_mode == Mode::Search)
        {
            search();
        }
        else if (m_mode == Mode::Top)
        {
            showMode(Mode::Top);
        }
    });
    connect(m_list, &QListWidget::customContextMenuRequested, this, [this](const QPoint& pos) {
        QListWidgetItem* item = m_list->itemAt(pos);
        if (!item)
        {
            return;
        }
        const RadioStation station = m_shown.value(item->data(online_role::Data).toInt());
        QMenu menu(this);
        menu.addAction(tr("Play"), this, [this, station]() { emit playRequested(station); });
        menu.addAction(isFavorite(station.uuid) ? tr("Remove from Favorites") : tr("Add to Favorites"), this,
                       [this, station]() { toggleFavorite(station); });
        menu.exec(m_list->viewport()->mapToGlobal(pos));
    });
    connect(m_list, &QListWidget::itemActivated, this, [this](QListWidgetItem* item) { // Enter
        emit playRequested(m_shown.value(item->data(online_role::Data).toInt()));
    });
}

void RadioPage::activate()
{
    if (m_activated)
    {
        return;
    }
    m_activated = true;
    loadCountries();
    showMode(Mode::Top);
}

void RadioPage::setColors(const theme::Tokens& tokens)
{
    m_delegate->setColors(tokens);
    m_list->viewport()->update();
}

void RadioPage::setPlayingUrl(const QString& url)
{
    m_playingUrl = url;
    for (int i = 0; i < m_list->count(); ++i)
    {
        QListWidgetItem* item = m_list->item(i);
        item->setData(online_role::Accent, m_shown.value(item->data(online_role::Data).toInt()).url == url);
    }
}

QUrl RadioPage::apiUrl(const QString& path, const QUrlQuery& query) const
{
    QUrl url(QStringLiteral("https://%1%2").arg(QString::fromLatin1(kServers[m_server % kServerCount]), path));
    url.setQuery(query);
    return url;
}

QNetworkReply* RadioPage::apiGet(const QString& path, const QUrlQuery& query)
{
    return NetworkGet(m_network, apiUrl(path, query));
}

void RadioPage::showMode(Mode mode)
{
    m_mode = mode;
    ++m_generation; // anything still loading is for another list now
    switch (mode)
    {
        case Mode::Top:
        {
            QUrlQuery query;
            query.addQueryItem("order", "clickcount");
            query.addQueryItem("reverse", "true");
            query.addQueryItem("hidebroken", "true");
            query.addQueryItem("limit", QString::number(kListLimit));
            if (const QString code = m_country->currentData().toString(); !code.isEmpty())
            {
                query.addQueryItem("countrycode", code);
            }
            requestStations({query});
            break;
        }
        case Mode::Favorites:
            showStations(m_favorites, tr("No favorites yet. Click a station's star to add it here."));
            break;
        case Mode::Recent:
            showStations(m_recent, tr("Stations you play show up here."));
            break;
        case Mode::Search:
            search();
            break;
    }
}

void RadioPage::search()
{
    const QString text = m_search->text().trimmed();
    if (text.isEmpty())
    {
        return;
    }
    m_mode = Mode::Search;
    for (QAbstractButton* button : m_modes->buttons())
    {
        m_modes->setExclusive(false);
        button->setChecked(false);
        m_modes->setExclusive(true);
    }
    ++m_generation;
    // By name or by tag: both searches, the name matches first.
    QVector<QUrlQuery> queries;
    for (const char* field : {"name", "tag"})
    {
        QUrlQuery query;
        query.addQueryItem(QString::fromLatin1(field), text);
        query.addQueryItem("order", "clickcount");
        query.addQueryItem("reverse", "true");
        query.addQueryItem("hidebroken", "true");
        query.addQueryItem("limit", QString::number(kListLimit));
        if (const QString code = m_country->currentData().toString(); !code.isEmpty())
        {
            query.addQueryItem("countrycode", code);
        }
        queries << query;
    }
    requestStations(queries);
}

void RadioPage::requestStations(const QVector<QUrlQuery>& queries)
{
    const int generation = m_generation;
    m_list->clear();
    m_status->setText(tr("Loading stations…"));
    m_status->show();
    m_list->hide();

    struct Pending
    {
        int left = 0;
        bool failed = false;
        QVector<QVector<RadioStation>> results;
    };
    auto pending = std::make_shared<Pending>();
    pending->left = queries.size();
    pending->results.resize(queries.size());
    for (int i = 0; i < queries.size(); ++i)
    {
        QNetworkReply* reply = apiGet(QStringLiteral("/json/stations/search"), queries[i]);
        connect(reply, &QNetworkReply::finished, this, [this, reply, pending, i, generation, queries]() {
            reply->deleteLater();
            if (reply->error() != QNetworkReply::NoError)
            {
                pending->failed = true;
            }
            else
            {
                pending->results[i] = FromJson(QJsonDocument::fromJson(reply->readAll()).array());
            }
            if (--pending->left > 0 || generation != m_generation)
            {
                return;
            }
            if (pending->failed && pending->results.at(0).isEmpty())
            {
                // This mirror didn't answer: the next one, once around.
                if (++m_server % kServerCount != 0)
                {
                    requestStations(queries);
                    return;
                }
                m_status->setText(tr("Radio Browser can't be reached right now. Check your connection and try again."));
                return;
            }
            QVector<RadioStation> merged;
            QSet<QString> seen;
            for (const QVector<RadioStation>& result : pending->results)
            {
                for (const RadioStation& station : result)
                {
                    if (!seen.contains(station.uuid))
                    {
                        seen.insert(station.uuid);
                        merged << station;
                    }
                }
            }
            showStations(merged, m_mode == Mode::Search ? tr("No stations found.") : tr("No stations here."));
        });
    }
}

void RadioPage::showStations(const QVector<RadioStation>& stations, const QString& emptyText)
{
    m_shown = stations;
    m_list->clear();
    for (int i = 0; i < stations.size(); ++i)
    {
        const RadioStation& station = stations[i];
        auto* item = new QListWidgetItem(station.name);
        item->setData(online_role::Subtitle, station.details());
        item->setData(online_role::ImageUrl, station.favicon);
        item->setData(online_role::Mark, static_cast<int>(theme::Icon::Radio));
        item->setData(online_role::Star, isFavorite(station.uuid));
        item->setData(online_role::Accent, station.url == m_playingUrl);
        item->setData(online_role::Data, i);
        item->setToolTip(station.name);
        m_list->addItem(item);
    }
    m_status->setText(emptyText);
    m_status->setVisible(stations.isEmpty());
    m_list->setVisible(!stations.isEmpty());
    m_list->scrollToTop();
}

void RadioPage::loadCountries()
{
    QUrlQuery query;
    query.addQueryItem("order", "name");
    query.addQueryItem("hidebroken", "true");
    QNetworkReply* reply = apiGet(QStringLiteral("/json/countries"), query);
    connect(reply, &QNetworkReply::finished, this, [this, reply]() {
        reply->deleteLater();
        if (reply->error() != QNetworkReply::NoError)
        {
            return; // "All countries" still works
        }
        int total = 0;
        m_country->blockSignals(true);
        for (const QJsonValue& value : QJsonDocument::fromJson(reply->readAll()).array())
        {
            const QJsonObject country = value.toObject();
            const QString code = country.value("iso_3166_1").toString();
            const int count = country.value("stationcount").toInt();
            total += count;
            if (!code.isEmpty() && count > 0)
            {
                m_country->addItem(QStringLiteral("%1 (%2)").arg(country.value("name").toString(), QLocale().toString(count)), code);
            }
        }
        m_country->blockSignals(false);
        if (total > 0)
        {
            m_subtitle->setText(tr("%1 stations from the Radio Browser directory").arg(QLocale().toString(total)));
        }
    });
}

bool RadioPage::isFavorite(const QString& uuid) const
{
    return std::any_of(m_favorites.begin(), m_favorites.end(), [&uuid](const RadioStation& s) { return s.uuid == uuid; });
}

void RadioPage::toggleFavorite(const RadioStation& station)
{
    if (isFavorite(station.uuid))
    {
        m_favorites.erase(std::remove_if(m_favorites.begin(), m_favorites.end(),
                                         [&station](const RadioStation& s) { return s.uuid == station.uuid; }),
                          m_favorites.end());
    }
    else
    {
        m_favorites.prepend(station);
    }
    save();
    if (m_mode == Mode::Favorites)
    {
        showStations(m_favorites, tr("No favorites yet. Click a station's star to add it here."));
    }
    else
    {
        refreshStars();
    }
}

void RadioPage::refreshStars()
{
    for (int i = 0; i < m_list->count(); ++i)
    {
        QListWidgetItem* item = m_list->item(i);
        item->setData(online_role::Star, isFavorite(m_shown.value(item->data(online_role::Data).toInt()).uuid));
    }
}

void RadioPage::stationPlayed(const RadioStation& station)
{
    m_recent.erase(std::remove_if(m_recent.begin(), m_recent.end(), [&station](const RadioStation& s) { return s.uuid == station.uuid; }),
                   m_recent.end());
    m_recent.prepend(station);
    m_recent.resize(qMin(kRecentLimit, static_cast<int>(m_recent.size())));
    save();
    if (m_mode == Mode::Recent)
    {
        showStations(m_recent, QString());
    }
    // Radio Browser ranks stations by plays; it asks apps to report them.
    if (!station.uuid.isEmpty())
    {
        QNetworkReply* reply = apiGet(QStringLiteral("/json/url/") + station.uuid);
        connect(reply, &QNetworkReply::finished, reply, &QObject::deleteLater);
    }
    setPlayingUrl(station.url);
}

QByteArray RadioPage::logo(const RadioStation& station) const
{
    return m_images->bytes(station.favicon);
}

void RadioPage::save() const
{
    WriteJsonFile(RadioFile(), QJsonObject{{"favorites", ToJson(m_favorites)}, {"recent", ToJson(m_recent)}});
}

bool RadioPage::eventFilter(QObject* watched, QEvent* event)
{
    if (watched == m_list->viewport() && event->type() == QEvent::MouseButtonRelease)
    {
        auto* mouse = static_cast<QMouseEvent*>(event);
        const QPoint pos = mouse->position().toPoint();
        const QModelIndex index = m_list->indexAt(pos);
        if (mouse->button() == Qt::LeftButton && index.isValid())
        {
            const RadioStation station = m_shown.value(index.data(online_role::Data).toInt());
            QFont font = m_list->font();
            font.setPixelSize(13);
            if (OnlineRowDelegate::trailingRect(m_list->visualRect(index).adjusted(0, 1, 0, -1), index, QFontMetrics(font)).contains(pos))
            {
                toggleFavorite(station); // the star
            }
            else
            {
                emit playRequested(station); // anywhere else: play it
            }
        }
    }
    return QWidget::eventFilter(watched, event);
}

} // namespace audioforge
