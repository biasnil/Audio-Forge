#include "audioforge/app_info.hpp"

#ifndef AUDIOFORGE_VERSION
#define AUDIOFORGE_VERSION "0.0.0" // set by CMakeLists.txt
#endif

namespace audioforge::app {

QString Version()
{
    return QStringLiteral(AUDIOFORGE_VERSION);
}

QByteArray UserAgent()
{
    return QByteArray("AudioForge/") + AUDIOFORGE_VERSION + " ( " + kRepoUrl + " )";
}

} // namespace audioforge::app
