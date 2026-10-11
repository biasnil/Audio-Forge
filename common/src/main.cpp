#include <QApplication>
#include <QIcon>
#include <QFont>
#include <QFontDatabase>
#include "audioforge/player_window.hpp"
#include "audioforge/warnings.hpp"

namespace {
\
void qtMessageHandler(QtMsgType type, const QMessageLogContext& context, const QString& message)
{
    const char* levelName = "QT-DEBUG";
    switch (type)
    {
        case QtDebugMsg:    levelName = "QT-DEBUG"; break;
        case QtInfoMsg:     levelName = "QT-INFO"; break;
        case QtWarningMsg:  levelName = "QT-WARNING"; break;
        case QtCriticalMsg: levelName = "QT-CRITICAL"; break;
        case QtFatalMsg:    levelName = "QT-FATAL"; break;
    }

    QString category = context.category ? QString::fromUtf8(context.category) : QString();
    QString formatted = (category.isEmpty() || category == "default")
        ? message
        : "[" + category + "] " + message;

    audioforge::ErrorReporter::logOnly(QString(levelName) + ": " + formatted);

    if (type == QtFatalMsg)
    {
        abort(); // Qt's default handler does this too -- a fatal message means Qt itself considers the process unrecoverable
    }
}

} // namespace

int main(int argc, char** argv)
{
    QApplication app(argc, argv);
    qInstallMessageHandler(qtMessageHandler); // installed after QApplication so ErrorReporter's QStandardPaths lookup is reliable

    // "Fusion" is the Qt style that actually honors a custom QPalette --
    // the native Windows style mostly ignores palette colors, so theme
    // switching would silently do nothing without this.
    app.setStyle("Fusion");
    app.setWindowIcon(QIcon(":/icons/audioforge.png"));

    // Bundled fonts (resources/fonts, SIL Open Font License): Inter for the
    // interface, Playfair Display for big titles. Neither has Chinese/Japanese
    // glyphs, so the family list falls back to YaHei for those characters.
    for (const char* font : {":/fonts/Inter.ttf", ":/fonts/PlayfairDisplay.ttf", ":/fonts/PlayfairDisplay-Italic.ttf"})
    {
        if (QFontDatabase::addApplicationFont(QString::fromLatin1(font)) < 0)
        {
            audioforge::ErrorReporter::logOnly(QString("Couldn't load bundled font %1").arg(font));
        }
    }
    QFont uiFont;
    uiFont.setFamilies({"Inter", "Microsoft YaHei UI", "Segoe UI"});
    uiFont.setPointSizeF(9.5);
    uiFont.setHintingPreference(QFont::PreferNoHinting); // keeps Inter's shapes; Qt's default hinting squashes it on Windows
    app.setFont(uiFont);

    audioforge::PlayerWindow window;
    window.show();

    return app.exec();
}