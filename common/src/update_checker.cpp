#include "audioforge/update_checker.hpp"
#include "audioforge/app_info.hpp"
#include "audioforge/warnings.hpp"

#include <QDateTime>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkAccessManager>
#include <QNetworkReply>
#include <QNetworkRequest>
#include <QRegularExpression>
#include <QSettings>
#include <QTimer>
#include <QUrl>

#include <array>

namespace audioforge {

namespace {

constexpr qint64 kAutoCheckIntervalMs = 24LL * 60 * 60 * 1000;

QSettings AppSettings()
{
    return QSettings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge");
}

// "1.4.0" / "v1.4" -> {1, 4, 0}; a trailing letter ("1.1.1a") is ignored.
std::optional<std::array<int, 3>> ParseVersion(const QString& text)
{
    static const QRegularExpression pattern(QStringLiteral(R"(^[vV]?(\d+)(?:\.(\d+))?(?:\.(\d+))?[A-Za-z]?$)"));
    const QRegularExpressionMatch match = pattern.match(text.trimmed());
    if (!match.hasMatch())
    {
        return std::nullopt;
    }
    std::array<int, 3> parts{};
    for (int i = 0; i < 3; ++i)
    {
        parts[i] = match.captured(i + 1).isEmpty() ? 0 : match.captured(i + 1).toInt();
    }
    return parts;
}

QString ReleasePage(const QString& tag)
{
    return QString("%1/releases/tag/%2").arg(app::kRepoUrl, tag);
}

} // namespace

std::optional<int> CompareVersions(const QString& a, const QString& b)
{
    const auto first = ParseVersion(a);
    const auto second = ParseVersion(b);
    if (!first || !second)
    {
        return std::nullopt;
    }
    for (int i = 0; i < 3; ++i)
    {
        if ((*first)[i] != (*second)[i])
        {
            return (*first)[i] < (*second)[i] ? -1 : 1;
        }
    }
    return 0;
}

std::optional<ReleaseInfo> NewestDesktopRelease(const QByteArray& json, const QString& current, bool* valid)
{
    const QJsonDocument doc = QJsonDocument::fromJson(json);
    if (valid)
    {
        *valid = doc.isArray();
    }
    std::optional<ReleaseInfo> best;
    for (const QJsonValue& value : doc.array())
    {
        const QJsonObject release = value.toObject();
        if (release.value("draft").toBool() || release.value("prerelease").toBool())
        {
            continue;
        }
        const QString tag = release.value("tag_name").toString();
        if (!ParseVersion(tag))
        {
            continue; // "Androidv1.4.0" and anything else that isn't a plain version
        }
        const QString version = tag.startsWith('v', Qt::CaseInsensitive) ? tag.mid(1) : tag;
        if (!best || CompareVersions(version, best->version).value_or(0) > 0)
        {
            const QString url = release.value("html_url").toString();
            best = ReleaseInfo{version, url.isEmpty() ? ReleasePage(tag) : url};
        }
    }
    if (best && CompareVersions(best->version, current).value_or(1) > 0)
    {
        return best;
    }
    return std::nullopt;
}

UpdateChecker::UpdateChecker(QNetworkAccessManager* network, QObject* parent)
    : QObject(parent), m_network(network), m_dueTimer(new QTimer(this))
{
    // What the last successful check found, so the Version row says
    // "available" straight away -- unless this is that version (or newer).
    QSettings settings = AppSettings();
    const QString known = settings.value("latestKnownVersion").toString();
    if (!known.isEmpty() && CompareVersions(known, app::Version()).value_or(0) > 0)
    {
        m_state = State::Available;
        m_availableVersion = known;
        m_releaseUrl = settings.value("latestKnownUrl", ReleasePage(known)).toString();
    }

    // Shortly after startup, then hourly for an app left open for days; each
    // only asks GitHub once 24 hours have passed since the last check.
    connect(m_dueTimer, &QTimer::timeout, this, &UpdateChecker::checkIfDue);
    m_dueTimer->start(60 * 60 * 1000);
    QTimer::singleShot(8000, this, &UpdateChecker::checkIfDue);
}

bool UpdateChecker::autoCheckEnabled() const
{
    return AppSettings().value("autoUpdateCheck", false).toBool();
}

void UpdateChecker::setAutoCheckEnabled(bool enabled)
{
    AppSettings().setValue("autoUpdateCheck", enabled);
    if (enabled)
    {
        checkIfDue();
    }
}

void UpdateChecker::check()
{
    runCheck(false);
}

void UpdateChecker::checkIfDue()
{
    if (!autoCheckEnabled() || m_state == State::Checking)
    {
        return;
    }
    const qint64 last = AppSettings().value("lastUpdateCheckMs", 0).toLongLong();
    if (QDateTime::currentMSecsSinceEpoch() - last < kAutoCheckIntervalMs)
    {
        return;
    }
    runCheck(true);
}

void UpdateChecker::runCheck(bool automatic)
{
    if (m_state == State::Checking)
    {
        return;
    }
    const State before = m_state;
    setState(State::Checking);

    QNetworkRequest request{QUrl(QString::fromLatin1(app::kReleasesApi))};
    request.setHeader(QNetworkRequest::UserAgentHeader, app::UserAgent());
    request.setRawHeader("Accept", "application/vnd.github+json");
    request.setTransferTimeout(10000);

    QNetworkReply* reply = m_network->get(request);
    connect(reply, &QNetworkReply::finished, this, [this, reply, automatic, before]() {
        reply->deleteLater();
        bool valid = false;
        std::optional<ReleaseInfo> newer;
        if (reply->error() == QNetworkReply::NoError)
        {
            newer = NewestDesktopRelease(reply->readAll(), app::Version(), &valid);
        }
        if (!valid)
        {
            ErrorReporter::logOnly("Update check failed: " + reply->errorString());
            // An automatic check that fails stays quiet: the row keeps what it showed.
            setState(automatic ? before : State::Failed);
            return;
        }

        QSettings settings = AppSettings();
        settings.setValue("lastUpdateCheckMs", QDateTime::currentMSecsSinceEpoch());
        settings.setValue("latestKnownVersion", newer ? newer->version : QString());
        settings.setValue("latestKnownUrl", newer ? newer->url : QString());
        m_availableVersion = newer ? newer->version : QString();
        m_releaseUrl = newer ? newer->url : QString();
        setState(newer ? State::Available : State::UpToDate);

        // The automatic check tells about each new version once.
        if (newer && automatic && settings.value("updateNotifiedVersion").toString() != newer->version)
        {
            settings.setValue("updateNotifiedVersion", newer->version);
            emit updateNoticed(newer->version, newer->url);
        }
    });
}

void UpdateChecker::setState(State state)
{
    m_state = state;
    emit stateChanged();
}

} // namespace audioforge
