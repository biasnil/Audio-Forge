#pragma once

#include <QCoreApplication>
#include <QString>
#include <QVector>

namespace audioforge {

// Settings > Appearance > Language. The interface text is English in the
// source, wrapped in tr() / Tr(); the other languages are resources/i18n/<code>.json
// files mapping each English string to its translation. A string missing
// from a file just shows in English. scripts/i18n_check.py lists what's
// missing or no longer used.
//
// The language is chosen once, at startup (main.cpp) -- changing it in
// Settings asks to restart the app.

struct LanguageOption
{
    QString code;       // "system", "en", "fr", "zh_Hans", "zh_Hant", "ms"
    QString nativeName; // shown in the picker as-is, never translated ("Français")
};

const QVector<LanguageOption>& LanguageOptions(); // "system" first

QString SavedLanguage();                    // the setting ("system" if never set)
void SaveLanguage(const QString& code);
QString ResolveLanguage(const QString& setting); // "system" -> the Windows display language, if it's one we have

// Loads the translation for `language` (an already-resolved code) into the
// app. Call after QApplication exists and before any window is built.
void InstallTranslation(const QString& language);
QString ActiveLanguage(); // what InstallTranslation() set ("en" until then)

// A bundled text resource in the active language, e.g. ":/about/terms" ->
// ":/about/terms_fr.md", falling back to the English ":/about/terms_en.md".
QString ReadLocalizedText(const QString& base);

// For code outside a QObject, where tr() isn't available.
inline QString Tr(const char* text)
{
    return QCoreApplication::translate("AudioForge", text);
}

} // namespace audioforge
