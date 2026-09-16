#pragma once

#include <QString>

class QWidget;

namespace audioforge {

// Every warning/error/confirmation the app shows the user should go through
// here rather than a raw QMessageBox call -- keeps the wording/behavior
// consistent, and every one of them also gets appended (with a timestamp)
// to %APPDATA%\AudioForge\warnings.log, so failures leave a trail even if
// a popup gets missed or dismissed too fast to read.
class ErrorReporter
{
public:
    // A problem the user should know about right now (network failure,
    // couldn't save a file, etc.). Shows a modal warning dialog + logs it.
    static void warn(QWidget* parent, const QString& title, const QString& message);

    // Non-error information (e.g. "pick a result first"). Shows an info
    // dialog + logs it.
    static void info(QWidget* parent, const QString& title, const QString& message);

    // Yes/No confirmation for a destructive action (e.g. deleting a
    // playlist). Logs the question either way; returns true only if the
    // user picked Yes.
    static bool confirm(QWidget* parent, const QString& title, const QString& message);

    // For things that don't need a popup (aren't acute enough to interrupt
    // the user) but are still worth a record: engine init failure, a saved
    // folder that's gone missing, a crossfade preload that silently failed.
    static void logOnly(const QString& message);

private:
    static void appendToLog(const QString& level, const QString& title, const QString& message);
};

} // namespace audioforge
