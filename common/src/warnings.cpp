#include "audioforge/warnings.hpp"

#include <QMessageBox>
#include <QStandardPaths>
#include <QDir>
#include <QFile>
#include <QTextStream>
#include <QDateTime>

namespace audioforge {

void ErrorReporter::appendToLog(const QString& level, const QString& title, const QString& message)
{
    QString dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    QDir().mkpath(dir);

    QFile logFile(dir + "/warnings.log");
    if (logFile.open(QIODevice::Append | QIODevice::Text))
    {
        QTextStream stream(&logFile);
        stream << QDateTime::currentDateTime().toString(Qt::ISODate)
               << " [" << level << "] " << title << ": " << message << '\n';
    }
    // If the log itself can't be opened, there's nowhere left to report
    // that failure to -- silently continue rather than cascade further.
}

void ErrorReporter::warn(QWidget* parent, const QString& title, const QString& message)
{
    appendToLog("WARNING", title, message);
    QMessageBox::warning(parent, title, message);
}

void ErrorReporter::info(QWidget* parent, const QString& title, const QString& message)
{
    appendToLog("INFO", title, message);
    QMessageBox::information(parent, title, message);
}

bool ErrorReporter::confirm(QWidget* parent, const QString& title, const QString& message)
{
    auto reply = QMessageBox::question(parent, title, message);
    bool confirmed = (reply == QMessageBox::Yes);
    appendToLog(confirmed ? "CONFIRMED" : "DECLINED", title, message);
    return confirmed;
}

void ErrorReporter::logOnly(const QString& message)
{
    appendToLog("NOTICE", QString(), message);
}

} // namespace audioforge
