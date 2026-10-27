#pragma once

#include <QColor>
#include <QIcon>
#include <QPalette>
#include <QPixmap>
#include <QString>

// Visual theme for the Qt player: color tokens, the app-wide stylesheet,
// and the interface icons -- SVGs in resources/ui/, recolored at
// load time so they follow the theme and checked/disabled states.
namespace audioforge::theme {

struct Tokens
{
    QColor bg;          // main content background
    QColor sidebar;     // left navigation rail
    QColor surface;     // cards, inputs, hover fills
    QColor surfaceHi;   // raised surfaces, selected rows, slider grooves
    QColor border;      // hairline separators
    QColor text;
    QColor muted;       // secondary text, inactive icons
    QColor accent;      // warm peach -- play button, active states, progress
    QColor accentHover;
    QColor accentText;  // text/icons drawn on top of the accent color
    QColor textSoft;    // 35% text + 65% muted: nav labels, table cells, quiet icons
    QColor textStrong;  // 75% text + 25% muted: emphasized secondary text
};

const Tokens& tokens(bool dark);
QPalette palette(bool dark);
QString styleSheet(bool dark);

enum class Icon
{
    Play, Pause, Previous, Next, Stop,
    Shuffle, ShuffleSmart, Repeat, RepeatOne,
    Volume, VolumeMute,
    Back, FullScreen, Search, Plus, OpenFile,
    Album, Track, Artist, Folder, Playlist, Equalizer, Wallpaper, Settings,
    Sun, Moon,
    Wand, Metronome, Dial, Lock, Timer, Chevron,
    Queue, More, Close,
    Heart, HeartFilled, Visualizer, SmartPlaylist, Podcast,
    Trash, CheckCircle,
    Radio, Book, Download, Star, StarFilled, Chapters,
};

// `checked` is used for the QIcon::On state (checkable buttons), `disabled`
// for QIcon::Disabled -- either may be left invalid to reuse `normal`.
QIcon icon(Icon kind, const QColor& normal, const QColor& checked = QColor(),
           const QColor& disabled = QColor(), int logicalSize = 20);

// Same pixmap with antialiased rounded corners (cover art thumbnails).
QPixmap roundedPixmap(const QPixmap& source, qreal radius);

// Art for something without a cover (a song, a playlist): a soft two-colour
// gradient whose hue comes from `seed` (so each one looks different, and the
// same one always looks the same), with `mark` drawn large and faint in the
// middle. Square, `logicalSize` across, rounded by `radius`; rendered at 2x.
// `hue` (0..359) picks the colour instead of the seed.
QPixmap generatedArt(const QString& seed, int logicalSize, qreal radius, Icon mark = Icon::Track, int hue = -1);

// The hue generatedArt() uses for `seed` -- its colour for accents (the
// visualizer, the Now Playing background).
QColor generatedArtColor(const QString& seed);

} // namespace audioforge::theme
