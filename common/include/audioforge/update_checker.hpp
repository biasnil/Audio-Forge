#pragma once

#include <QByteArray>
#include <QObject>
#include <QString>

#include <optional>

class QNetworkAccessManager;
class QTimer;

namespace audioforge {

// Settings > About > Version: asks GitHub whether a newer desktop release is
// out. Only reads a version number -- the app never downloads or installs
// anything itself; an update opens its release page in the browser.
//
// The automatic check (off by default) runs at most once every 24 hours,
// at startup or while the app stays open.
class UpdateChecker : public QObject
{
    Q_OBJECT

public:
    enum class State
    {
        Idle,
        Checking,
        UpToDate,
        Available, // availableVersion() / releaseUrl() say which
        Failed,    // offline, rate-limited, bad reply
    };

    explicit UpdateChecker(QNetworkAccessManager* network, QObject* parent = nullptr);

    State state() const { return m_state; }
    QString availableVersion() const { return m_availableVersion; }
    QString releaseUrl() const { return m_releaseUrl; }

    bool autoCheckEnabled() const;
    void setAutoCheckEnabled(bool enabled); // turning it on checks right away if one is due

    void check(); // now, from the Version row

signals:
    void stateChanged();
    // The automatic check found a version it hasn't told about before.
    void updateNoticed(const QString& version, const QString& url);

private:
    void checkIfDue();
    void runCheck(bool automatic);
    void setState(State state);

    QNetworkAccessManager* m_network;
    QTimer* m_dueTimer;
    State m_state = State::Idle;
    QString m_availableVersion;
    QString m_releaseUrl;
};

struct ReleaseInfo
{
    QString version; // "1.5.0"
    QString url;     // its GitHub release page
};

// The newest published desktop release in GitHub's release list `json`, if
// it's newer than `current`. Desktop releases are tagged with a plain
// version ("1.4.0", a leading "v" is fine); Android's "Androidv1.4.0" and
// other prefixed tags, drafts and prereleases are skipped. nullopt on a
// reply that isn't a release list, too (see `valid`).
std::optional<ReleaseInfo> NewestDesktopRelease(const QByteArray& json, const QString& current, bool* valid = nullptr);

// Positive if `a` is newer than `b`, 0 if the same, nullopt if either isn't a version.
std::optional<int> CompareVersions(const QString& a, const QString& b);

} // namespace audioforge
