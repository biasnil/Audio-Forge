#pragma once

#include <QObject>
#include <QString>
#include <QByteArray>

class QLocalSocket;
class QTimer;

namespace audioforge {

// Reports "now playing" state to the local Discord desktop client over its
// IPC socket (Rich Presence) -- the same "Listening to <title>" status
// Spotify/foobar2000/etc. show on a user's profile. Talks directly to the
// small binary protocol Discord's IPC uses (8-byte opcode+length header,
// then a JSON payload) rather than linking Discord's own SDK, so there's
// no extra vendored dependency.
//
// Silently does nothing if Discord isn't running, retries periodically in
// case it starts later (or restarts), and is a total no-op until
// setEnabled(true) + init() with a real client ID have both happened --
// so it's inert by default, opt-in only.
//
// Requires a free Discord "Application" client ID from
// https://discord.com/developers/applications -- create one (nothing needs
// to be published) and copy its Client ID.
class DiscordPresence : public QObject
{
    Q_OBJECT

public:
    explicit DiscordPresence(QObject* parent = nullptr);
    ~DiscordPresence() override;

    DiscordPresence(const DiscordPresence&) = delete;
    DiscordPresence& operator=(const DiscordPresence&) = delete;

    // Sets which Discord Application to report as. Reconnects immediately
    // if the ID actually changed and we're currently enabled/connected.
    void init(const QString& clientId);

    // Turns reporting on/off. Disabling clears any shown activity first,
    // disconnects, and stops retrying until re-enabled.
    void setEnabled(bool enabled);

    // Sets "Listening to <title>" / "<artist>", with an elapsed-time
    // counter that starts counting from (now - elapsedSeconds). Call again
    // whenever the track or the playback position jumps (new track,
    // resume from pause, seek). No-op if disabled, or if Discord isn't
    // connected yet -- the most recent call is remembered and sent as soon
    // as the handshake completes. totalSeconds <= 0 omits the "ends at"
    // timestamp (shown as a countdown) and just counts up instead.
    void setNowPlaying(const QString& title, const QString& artist, int elapsedSeconds, int totalSeconds);

    // Clears the activity (e.g. on stop/pause). No-op if disabled or not
    // connected.
    void clearPresence();

private slots:
    void attemptConnect();
    void onConnected();
    void onDisconnected();
    void onReadyRead();

private:
    void resetConnectionState();
    void sendFrame(int opcode, const QByteArray& jsonPayload);
    void sendHandshake();
    void sendActivity();

    QString m_clientId;
    bool m_enabled = false;

    QLocalSocket* m_socket = nullptr;
    QTimer* m_reconnectTimer = nullptr;
    QByteArray m_readBuffer;
    bool m_connected = false;
    bool m_handshakeComplete = false;

    QString m_title;
    QString m_artist;
    qint64 m_startEpochSeconds = 0;
    qint64 m_endEpochSeconds = 0; // 0 = no "ends at" timestamp
    bool m_hasPendingActivity = false;
};

} // namespace audioforge
