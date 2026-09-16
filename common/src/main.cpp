#include <QApplication>
#include <QIcon>
#include "audioforge/player_window.hpp"

int main(int argc, char** argv)
{
    QApplication app(argc, argv);

    // "Fusion" is the Qt style that actually honors a custom QPalette --
    // the native Windows style mostly ignores palette colors, so theme
    // switching would silently do nothing without this.
    app.setStyle("Fusion");
    app.setWindowIcon(QIcon(":/icons/audioforge.png"));

    audioforge::PlayerWindow window;
    window.show();

    return app.exec();
}