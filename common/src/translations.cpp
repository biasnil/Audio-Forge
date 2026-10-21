#include "audioforge/translations.hpp"
#include "audioforge/warnings.hpp"

#include <QFile>
#include <QHash>
#include <QJsonDocument>
#include <QJsonObject>
#include <QLocale>
#include <QSettings>
#include <QTranslator>

namespace audioforge {

namespace {

QString g_activeLanguage = QStringLiteral("en");

// Looks each string up by its English text alone, whatever class it's
// translated in -- which also covers Qt's own buttons (OK, Cancel, Yes, No...)
// when the JSON file has them.
class JsonTranslator : public QTranslator
{
public:
    bool load(const QString& path)
    {
        QFile file(path);
        if (!file.open(QIODevice::ReadOnly))
        {
            return false;
        }
        QJsonParseError error;
        const QJsonDocument doc = QJsonDocument::fromJson(file.readAll(), &error);
        if (error.error != QJsonParseError::NoError || !doc.isObject())
        {
            ErrorReporter::logOnly(QString("Translation %1 isn't valid JSON: %2").arg(path, error.errorString()));
            return false;
        }
        const QJsonObject object = doc.object();
        for (auto it = object.begin(); it != object.end(); ++it)
        {
            const QString text = it.value().toString();
            if (!text.isEmpty())
            {
                m_strings.insert(it.key(), text);
            }
        }
        return true;
    }

    QString translate(const char*, const char* sourceText, const char*, int) const override
    {
        return sourceText ? m_strings.value(QString::fromUtf8(sourceText)) : QString();
    }

    bool isEmpty() const override { return m_strings.isEmpty(); }

private:
    QHash<QString, QString> m_strings;
};

QSettings AppSettings()
{
    return QSettings(QSettings::IniFormat, QSettings::UserScope, "AudioForge", "AudioForge");
}

bool IsKnownLanguage(const QString& code)
{
    for (const LanguageOption& option : LanguageOptions())
    {
        if (option.code == code && code != QLatin1String("system"))
        {
            return true;
        }
    }
    return false;
}

} // namespace

const QVector<LanguageOption>& LanguageOptions()
{
    static const QVector<LanguageOption> options = {
        {QStringLiteral("system"), QString()}, // label is tr("System default"), set by the picker
        {QStringLiteral("en"), QStringLiteral("English")},
        {QStringLiteral("fr"), QStringLiteral("Français")},
        {QStringLiteral("zh_Hans"), QStringLiteral("简体中文")},
        {QStringLiteral("zh_Hant"), QStringLiteral("繁體中文")},
        {QStringLiteral("ms"), QStringLiteral("Bahasa Melayu")},
    };
    return options;
}

QString SavedLanguage()
{
    const QString code = AppSettings().value("language", "system").toString();
    return IsKnownLanguage(code) ? code : QStringLiteral("system");
}

void SaveLanguage(const QString& code)
{
    AppSettings().setValue("language", code);
}

QString ResolveLanguage(const QString& setting)
{
    if (setting != QLatin1String("system"))
    {
        return IsKnownLanguage(setting) ? setting : QStringLiteral("en");
    }
    // Windows' display languages, most preferred first: "zh-Hant-TW", "fr-FR", ...
    for (const QString& tag : QLocale::system().uiLanguages())
    {
        const QString lower = tag.toLower();
        if (lower.startsWith("en"))
        {
            return QStringLiteral("en");
        }
        if (lower.startsWith("fr"))
        {
            return QStringLiteral("fr");
        }
        if (lower.startsWith("ms"))
        {
            return QStringLiteral("ms");
        }
        if (lower.startsWith("zh"))
        {
            const bool traditional = lower.contains("hant") || lower.contains("-tw") || lower.contains("-hk")
                                     || lower.contains("-mo");
            return traditional ? QStringLiteral("zh_Hant") : QStringLiteral("zh_Hans");
        }
    }
    return QStringLiteral("en");
}

void InstallTranslation(const QString& language)
{
    g_activeLanguage = IsKnownLanguage(language) ? language : QStringLiteral("en");
    if (g_activeLanguage == QLatin1String("en"))
    {
        return; // the source text is English
    }
    auto* translator = new JsonTranslator(); // lives as long as the app
    translator->setParent(QCoreApplication::instance());
    if (!translator->load(QString(":/i18n/%1.json").arg(g_activeLanguage)))
    {
        ErrorReporter::logOnly("Couldn't load the translation for " + g_activeLanguage);
        delete translator;
        g_activeLanguage = QStringLiteral("en");
        return;
    }
    QCoreApplication::installTranslator(translator);
}

QString ActiveLanguage()
{
    return g_activeLanguage;
}

QString ReadLocalizedText(const QString& base)
{
    for (const QString& language : {g_activeLanguage, QStringLiteral("en")})
    {
        QFile file(QString("%1_%2.md").arg(base, language));
        if (file.open(QIODevice::ReadOnly))
        {
            return QString::fromUtf8(file.readAll());
        }
    }
    return QString();
}

} // namespace audioforge
