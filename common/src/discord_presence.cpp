#include "audioforge/discord_presence.hpp"
#include "audioforge/warnings.hpp"

#include <QLocalSocket>
#include <QTimer>
#include <QJsonDocument>
#include <QJsonObject>
#include <QCoreApplication>
#include <QDateTime>
#include <QUuid>
#include <cstring>

namespace audioforge {

namespace {
constexpr int kOpHandshake = 0;
constexpr int kOpFrame = 1;
constexpr int kReconnectIntervalMs = 15000; // Discord not running yet, or was just closed -- keep trying quietly
}

DiscordPresence::DiscordPresence(QObject* parent) : QObject(parent)
{
    m_socket = new QLocalSocket(this);
    connect(m_socket, &QLocalSocket::connected, this, &DiscordPresence::onConnected);
    connect(m_socket, &QLocalSocket::disconnected, this, &DiscordPresence::onDisconnected);
    connect(m_socket, &QLocalSocket::readyRead, this, &DiscordPresence::onReadyRead);
    // Connection failures (Discord not running, etc.) are expected and
    // frequent, so this is deliberately a no-op -- the reconnect timer
    // just quietly tries again later.
    connect(m_socket, &QLocalSocket::errorOccurred, this, [](QLocalSocket::LocalSocketError) {});

    m_reconnectTimer = new QTimer(this);
    m_reconnectTimer->setInterval(kReconnectIntervalMs);
    connect(m_reconnectTimer, &QTimer::timeout, this, &DiscordPresence::attemptConnect);
}

DiscordPresence::~DiscordPresence()
{
    clearPresence();
}

void DiscordPresence::init(const QString& clientId)
{
    if (m_clientId == clientId)
    {
        return;
    }
    m_clientId = clientId;

    if (m_enabled)
    {
        // A client ID change needs a fresh handshake to take effect (the
        // name/icon Discord shows come from the Application tied to the ID
        // at handshake time), so drop the current connection and let
        // attemptConnect() redo it.
        m_socket->abort();
        resetConnectionState();
        attemptConnect();
    }
}

void DiscordPresence::setEnabled(bool enabled)
{
    if (m_enabled == enabled)
    {
        return;
    }
    m_enabled = enabled;

    if (!enabled)
    {
        clearPresence(); // best-effort; harmless if we weren't connected
        m_reconnectTimer->stop();
        m_socket->abort();
        resetConnectionState();
    }
    else
    {
        m_reconnectTimer->start();
        attemptConnect();
    }
}

void DiscordPresence::resetConnectionState()
{
    m_connected = false;
    m_handshakeComplete = false;
    m_readBuffer.clear();
}

void DiscordPresence::attemptConnect()
{
    if (!m_enabled || m_clientId.isEmpty())
    {
        return;
    }
    if (m_socket->state() != QLocalSocket::UnconnectedState)
    {
        return; // already connected or a connection attempt is in flight
    }

#ifdef Q_OS_WIN
    // QLocalSocket maps a bare name straight onto \\.\pipe\<name> on
    // Windows, which is exactly where Discord listens.
    m_socket->connectToServer(QStringLiteral("discord-ipc-0"));
#else
    // On Linux/macOS, Discord creates a Unix domain socket file directly
    // under one of these directories (checked in the order Discord itself
    // checks) rather than through Qt's own local-server naming, so we need
    // the fully-qualified path ourselves. Only slot 0 is tried -- fine for
    // a single running Discord client, which covers the normal case.
    QString base = qEnvironmentVariable("XDG_RUNTIME_DIR");
    if (base.isEmpty()) base = qEnvironmentVariable("TMPDIR");
    if (base.isEmpty()) base = qEnvironmentVariable("TMP");
    if (base.isEmpty()) base = qEnvironmentVariable("TEMP");
    if (base.isEmpty()) base = QStringLiteral("/tmp");
    m_socket->connectToServer(base + QStringLiteral("/discord-ipc-0"));
#endif
}

void DiscordPresence::onConnected()
{
    m_connected = true;
    m_handshakeComplete = false;
    sendHandshake();
}

void DiscordPresence::onDisconnected()
{
    resetConnectionState();
    // m_reconnectTimer is still running (if enabled), so the next tick
    // retries on its own -- Discord restarting mid-session is the normal
    // way this happens.
}

void DiscordPresence::onReadyRead()
{
    m_readBuffer.append(m_socket->readAll());

    // Frames are 8-byte header (uint32 opcode, uint32 length, both
    // little-endian) followed by that many bytes of UTF-8 JSON.
    while (m_readBuffer.size() >= 8)
    {
        quint32 opcode = 0;
        quint32 length = 0;
        std::memcpy(&opcode, m_readBuffer.constData(), 4);
        std::memcpy(&length, m_readBuffer.constData() + 4, 4);

        if (m_readBuffer.size() < static_cast<int>(8 + length))
        {
            break; // rest of this frame hasn't arrived yet
        }

        QByteArray payload = m_readBuffer.mid(8, static_cast<int>(length));
        m_readBuffer.remove(0, static_cast<int>(8 + length));

        if (!m_handshakeComplete)
        {
            QJsonObject obj = QJsonDocument::fromJson(payload).object();
            const QString evt = obj.value(QStringLiteral("evt")).toString();
            if (evt == QStringLiteral("READY"))
            {
                m_handshakeComplete = true;
                if (m_hasPendingActivity)
                {
                    sendActivity();
                }
            }
            else if (evt == QStringLiteral("ERROR"))
            {
                // Most commonly a bad/unregistered client ID -- Discord's
                // own error message (e.g. "Invalid Client ID") comes
                // through in obj["data"]["message"].
                const QString errMsg = obj.value(QStringLiteral("data")).toObject()
                                           .value(QStringLiteral("message")).toString();
                ErrorReporter::logOnly("DiscordPresence: handshake rejected by Discord -- " + errMsg);
            }
        }
    }
}

void DiscordPresence::sendFrame(int opcode, const QByteArray& jsonPayload)
{
    QByteArray header;
    header.reserve(8);
    quint32 op = static_cast<quint32>(opcode);
    quint32 len = static_cast<quint32>(jsonPayload.size());
    header.append(reinterpret_cast<const char*>(&op), 4);
    header.append(reinterpret_cast<const char*>(&len), 4);
    m_socket->write(header);
    m_socket->write(jsonPayload);
}

void DiscordPresence::sendHandshake()
{
    QJsonObject obj;
    obj[QStringLiteral("v")] = 1;
    obj[QStringLiteral("client_id")] = m_clientId;
    sendFrame(kOpHandshake, QJsonDocument(obj).toJson(QJsonDocument::Compact));
}

void DiscordPresence::setNowPlaying(const QString& title, const QString& artist, int elapsedSeconds, int totalSeconds)
{
    m_title = title;
    m_artist = artist;
    const qint64 now = QDateTime::currentSecsSinceEpoch();
    m_startEpochSeconds = now - elapsedSeconds;
    m_endEpochSeconds = totalSeconds > 0 ? m_startEpochSeconds + totalSeconds : 0;
    m_hasPendingActivity = true;

    if (!m_enabled)
    {
        return;
    }
    if (m_connected && m_handshakeComplete)
    {
        sendActivity();
    }
    else
    {
        attemptConnect(); // in case Discord just started, or we haven't tried yet
    }
}

void DiscordPresence::sendActivity()
{
    QJsonObject timestamps;
    timestamps[QStringLiteral("start")] = m_startEpochSeconds;
    if (m_endEpochSeconds > 0)
    {
        timestamps[QStringLiteral("end")] = m_endEpochSeconds;
    }

    QJsonObject activity;
    activity[QStringLiteral("details")] = m_title;
    activity[QStringLiteral("state")] = m_artist.isEmpty() ? QStringLiteral("Unknown artist") : m_artist;
    activity[QStringLiteral("timestamps")] = timestamps;

    // Matches the asset key uploaded under Rich Presence -> Art Assets in
    // the Discord Developer Portal for this application -- must be typed
    // there exactly as "audioforge_logo" (asset keys are case-sensitive).
    QJsonObject assets;
    assets[QStringLiteral("large_image")] = QStringLiteral("audioforge_logo");
    assets[QStringLiteral("large_text")] = QStringLiteral("AudioForge");
    activity[QStringLiteral("assets")] = assets;

    QJsonObject args;
    args[QStringLiteral("pid")] = QCoreApplication::applicationPid();
    args[QStringLiteral("activity")] = activity;

    QJsonObject frame;
    frame[QStringLiteral("cmd")] = QStringLiteral("SET_ACTIVITY");
    frame[QStringLiteral("args")] = args;
    frame[QStringLiteral("nonce")] = QUuid::createUuid().toString(QUuid::WithoutBraces);

    sendFrame(kOpFrame, QJsonDocument(frame).toJson(QJsonDocument::Compact));
    m_hasPendingActivity = false;
}

void DiscordPresence::clearPresence()
{
    m_hasPendingActivity = false;
    if (!m_enabled || !m_connected || !m_handshakeComplete)
    {
        return;
    }

    QJsonObject args;
    args[QStringLiteral("pid")] = QCoreApplication::applicationPid();
    // Omitting "activity" entirely is how you clear the current one.

    QJsonObject frame;
    frame[QStringLiteral("cmd")] = QStringLiteral("SET_ACTIVITY");
    frame[QStringLiteral("args")] = args;
    frame[QStringLiteral("nonce")] = QUuid::createUuid().toString(QUuid::WithoutBraces);

    sendFrame(kOpFrame, QJsonDocument(frame).toJson(QJsonDocument::Compact));
}

} // namespace audioforge