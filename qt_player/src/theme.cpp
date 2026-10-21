#include "audioforge/theme.hpp"

#include <QFile>
#include <QHash>
#include <QLinearGradient>
#include <QRadialGradient>
#include <QPainter>
#include <QSvgRenderer>

#include <initializer_list>

namespace audioforge::theme {

const Tokens& tokens(bool dark)
{
    // "Midnight" -- near-black warm neutrals with a soft peach accent.
    static const Tokens darkTokens{
        QColor(15, 12, 13),    // bg
        QColor(11, 9, 10),     // sidebar
        QColor(24, 20, 21),    // surface
        QColor(36, 30, 31),    // surfaceHi
        QColor(44, 37, 38),    // border
        QColor(244, 235, 229), // text
        QColor(150, 138, 132), // muted
        QColor(240, 195, 164), // accent
        QColor(246, 211, 186), // accentHover
        QColor(27, 18, 16),    // accentText
    };
    // Warm cream counterpart -- accent darkened so it still reads on white.
    static const Tokens lightTokens{
        QColor(248, 243, 239),
        QColor(240, 233, 227),
        QColor(255, 255, 255),
        QColor(238, 229, 222),
        QColor(226, 215, 207),
        QColor(31, 24, 22),
        QColor(122, 110, 104),
        QColor(196, 122, 84),
        QColor(178, 106, 70),
        QColor(255, 255, 255),
    };
    return dark ? darkTokens : lightTokens;
}

// Fusion honors the palette for everything the stylesheet doesn't cover
// (checkbox indicators, dialogs' default backgrounds, disabled text, ...).
QPalette palette(bool dark)
{
    const Tokens& t = tokens(dark);
    QPalette p;
    p.setColor(QPalette::Window, t.bg);
    p.setColor(QPalette::WindowText, t.text);
    p.setColor(QPalette::Base, t.surface);
    p.setColor(QPalette::AlternateBase, t.surfaceHi);
    p.setColor(QPalette::ToolTipBase, t.surfaceHi);
    p.setColor(QPalette::ToolTipText, t.text);
    p.setColor(QPalette::PlaceholderText, t.muted);
    p.setColor(QPalette::Text, t.text);
    p.setColor(QPalette::Button, t.surfaceHi);
    p.setColor(QPalette::ButtonText, t.text);
    p.setColor(QPalette::BrightText, Qt::red);
    p.setColor(QPalette::Link, t.accent);
    p.setColor(QPalette::Highlight, t.accent);
    p.setColor(QPalette::HighlightedText, t.accentText);
    p.setColor(QPalette::Disabled, QPalette::Text, t.muted);
    p.setColor(QPalette::Disabled, QPalette::ButtonText, t.muted);
    p.setColor(QPalette::Disabled, QPalette::WindowText, t.muted);
    return p;
}

QString styleSheet(bool dark)
{
    const Tokens& t = tokens(dark);

    QString css = R"(
QToolTip { color: @text; background: @surfaceHi; border: 1px solid @border; padding: 4px 8px; border-radius: 6px; }

QFrame#Sidebar { background: @sidebar; border: none; border-right: 1px solid @border; }
QLabel#BrandLabel { color: @text; font-size: 15px; font-weight: 600; }
QLabel#SectionCaption { color: @muted; font-size: 10px; font-weight: 600; }
QLabel#SectionHeading { color: @text; font-size: 17px; font-weight: 600; padding: 10px 2px 4px 6px; }
QListWidget#CardList { background: transparent; }
QListWidget#CardList::item { padding: 8px 6px; border-radius: 12px; color: @text; }
QListWidget#CardList::item:hover { background: @surface; }
QListWidget#CardList::item:selected { background: @surfaceHi; color: @accent; }
QLabel#PageTitle { color: @text; font-family: "Playfair Display", Georgia, serif; font-size: 30px; font-weight: 500; }
QLabel#MutedLabel { color: @muted; }
QLabel#TimeLabel { color: @muted; font-size: 11px; }
QLabel#MiniTitle { color: @text; font-weight: 600; }
QLabel#CoverArt { background: @surfaceHi; border-radius: 8px; }
QLabel#NowPlayingCaption { color: @accent; font-size: 11px; font-weight: 600; }

QPushButton { background: @surfaceHi; color: @text; border: 1px solid @border; border-radius: 15px; padding: 7px 16px; }
QPushButton:hover { border-color: @accent; }
QPushButton:pressed { background: @surface; }
QPushButton:disabled { color: @muted; background: @surface; border-color: @border; }

QPushButton#NavButton { text-align: left; padding: 9px 12px; border: none; border-radius: 10px; color: @muted; background: transparent; font-size: 13px; }
QPushButton#NavButton:hover { color: @text; background: @surface; }
QPushButton#NavButton:checked { color: @text; background: @surfaceHi; }

QPushButton#IconButton { background: transparent; border: none; border-radius: 18px; padding: 0; }
QPushButton#IconButton:hover { background: @surfaceHi; }
QPushButton#IconButton:pressed { background: @surface; }
QPushButton#IconButton:disabled { background: transparent; }

QPushButton#PlayButton, QPushButton#BigPlayButton { background: @accent; border: none; padding: 0; }
QPushButton#PlayButton { border-radius: 22px; }
QPushButton#BigPlayButton { border-radius: 32px; }
QPushButton#PlayButton:hover, QPushButton#BigPlayButton:hover { background: @accentHover; }
QPushButton#PlayButton:disabled, QPushButton#BigPlayButton:disabled { background: @surfaceHi; }

QPushButton#PillToggle { border-radius: 11px; padding: 3px 10px; font-size: 11px; color: @muted; background: transparent; border: 1px solid @border; }
QPushButton#PillToggle:hover { color: @text; }
QPushButton#PillToggle:checked { color: @accentText; background: @accent; border-color: @accent; }

QPushButton#PrimaryButton { background: @accent; color: @accentText; border: none; font-weight: 600; }
QPushButton#PrimaryButton:hover { background: @accentHover; }
QPushButton#PrimaryButton:disabled { background: @surfaceHi; color: @muted; }

QFrame#PlayerBar { background: @surface; border: 1px solid @border; border-radius: 16px; }

QLineEdit { background: @surface; color: @text; border: 1px solid @border; border-radius: 10px; padding: 7px 12px; selection-background-color: @accent; selection-color: @accentText; }
QLineEdit:focus { border-color: @accent; }
QLineEdit#SearchBar { border-radius: 16px; padding: 6px 14px 6px 6px; }

QTableView, QListView { background: transparent; color: @text; border: none; outline: 0; selection-background-color: @surfaceHi; selection-color: @accent; }
QTableView::item { padding: 6px 8px; border: none; border-bottom: 1px solid @border; }
QTableView::item:hover { background: @surface; }
QTableView::item:selected { background: @surfaceHi; color: @accent; }
QListView::item { padding: 9px 12px; border-radius: 8px; }
QListView::item:hover { background: @surface; }
QListView::item:selected { background: @surfaceHi; color: @accent; }
QListWidget#SidebarList::item { padding: 6px 10px; color: @muted; }
QListWidget#FoldersList::item { padding: 0; } /* rows are widgets: item padding would squash them */
QListWidget#SidebarList::item:hover { color: @text; background: @surface; }

QHeaderView { background: transparent; border: none; }
QHeaderView::section { background: transparent; color: @muted; border: none; border-bottom: 1px solid @border; padding: 6px 8px; font-size: 11px; font-weight: 600; }
QHeaderView::section:vertical { padding: 0 10px; font-weight: 400; }
QLabel#EqValue { color: @accent; font-size: 11px; font-weight: 600; }

QFrame#SettingsCard { background: @surface; border: 1px solid @border; border-radius: 14px; }
QFrame#SettingsDivider { background: @border; border: none; min-height: 1px; max-height: 1px; }
QLabel#SettingsSectionTitle { color: @muted; font-size: 13px; font-weight: 600; padding: 18px 0 6px 6px; }
QLabel#SettingsFooter { color: @muted; font-size: 11px; padding: 6px 6px 0 6px; }
QLabel#SettingsValue { color: @muted; }
QLabel#SettingsSubtitle { color: @muted; font-size: 11px; }
QPushButton#LinkButton { background: transparent; border: none; border-radius: 0; color: @accent; text-align: left; padding: 0; }
QPushButton#LinkButton:hover { color: @accentHover; }
QPushButton#LinkButton:disabled { color: @muted; }
QPushButton#RowButton { background: transparent; border: none; border-radius: 0; color: @text; text-align: left; padding: 0; }
QProgressBar { background: @surfaceHi; border: none; border-radius: 3px; min-height: 6px; max-height: 6px; }
QProgressBar::chunk { background: @accent; border-radius: 3px; }

QFrame#QueuePanel { background: @surface; border: 1px solid @border; border-radius: 16px; }
QLabel#PanelTitle { color: @text; font-family: "Playfair Display", Georgia, serif; font-size: 24px; font-weight: 500; }
QLabel#Toast { background: @surfaceHi; color: @text; border: 1px solid @border; border-radius: 12px; padding: 9px 18px; }
QTableCornerButton::section { background: transparent; border: none; }

QTabWidget::pane { border: none; }
QScrollArea { background: transparent; border: none; }

QScrollBar:vertical { background: transparent; width: 10px; margin: 2px; }
QScrollBar:horizontal { background: transparent; height: 10px; margin: 2px; }
QScrollBar::handle:vertical { background: @surfaceHi; border-radius: 3px; min-height: 32px; }
QScrollBar::handle:horizontal { background: @surfaceHi; border-radius: 3px; min-width: 32px; }
QScrollBar::handle:hover { background: @muted; }
QScrollBar::add-line, QScrollBar::sub-line { width: 0; height: 0; }
QScrollBar::add-page, QScrollBar::sub-page { background: none; }

QSlider::groove:horizontal { height: 4px; background: @surfaceHi; border-radius: 2px; }
QSlider::sub-page:horizontal { background: @accent; border-radius: 2px; }
QSlider::add-page:horizontal { background: @surfaceHi; border-radius: 2px; }
QSlider::handle:horizontal { background: @text; width: 12px; height: 12px; margin: -4px 0; border-radius: 6px; }
QSlider::handle:horizontal:hover { background: @accent; }
QSlider::groove:vertical { width: 4px; background: @surfaceHi; border-radius: 2px; }
QSlider::add-page:vertical { background: @accent; border-radius: 2px; }
QSlider::sub-page:vertical { background: @surfaceHi; border-radius: 2px; }
QSlider::handle:vertical { background: @text; width: 14px; height: 14px; margin: 0 -5px; border-radius: 7px; }
QSlider::handle:vertical:hover { background: @accent; }
QSlider::sub-page:horizontal:disabled, QSlider::add-page:vertical:disabled { background: @border; }
QSlider::handle:disabled { background: @muted; }

QCheckBox { color: @text; spacing: 8px; }
QComboBox { background: @surface; color: @text; border: 1px solid @border; border-radius: 10px; padding: 6px 30px 6px 12px; }
QComboBox:hover { border-color: @accent; }
QComboBox::drop-down { subcontrol-origin: padding; subcontrol-position: center right; width: 26px; border: none; background: transparent; }
QComboBox::down-arrow { image: url(@comboArrow); width: 14px; height: 14px; }
QComboBox::down-arrow:on { top: 1px; }
QComboBox QAbstractItemView { background: @surfaceHi; color: @text; border: 1px solid @border; selection-background-color: @accent; selection-color: @accentText; outline: 0; }

QMenu { background: @surfaceHi; color: @text; border: 1px solid @border; border-radius: 10px; padding: 6px; }
QMenu::item { padding: 6px 18px; border-radius: 6px; }
QMenu::item:selected { background: @accent; color: @accentText; }
)";

    // Longest names first so "@accentHover" isn't eaten by "@accent".
    const std::initializer_list<std::pair<const char*, QColor>> replacements = {
        {"@accentHover", t.accentHover}, {"@accentText", t.accentText}, {"@accent", t.accent},
        {"@surfaceHi", t.surfaceHi},     {"@surface", t.surface},       {"@sidebar", t.sidebar},
        {"@border", t.border},           {"@muted", t.muted},           {"@text", t.text},
        {"@bg", t.bg},
    };
    // Qt stylesheets can't recolour an image, so each theme has its own arrow.
    css.replace(QLatin1String("@comboArrow"), dark ? QLatin1String(":/icons/ui/combo-arrow-dark.svg") : QLatin1String(":/icons/ui/combo-arrow-light.svg"));
    for (const auto& [name, color] : replacements)
    {
        css.replace(QLatin1String(name), color.name());
    }
    return css;
}

namespace {

QString IconFileName(Icon kind)
{
    switch (kind)
    {
        case Icon::Play:         return "play";
        case Icon::Pause:        return "pause";
        case Icon::Previous:     return "previous";
        case Icon::Next:         return "next";
        case Icon::Stop:         return "stop";
        case Icon::Shuffle:      return "shuffle";
        case Icon::ShuffleSmart: return "shuffle-smart";
        case Icon::Repeat:       return "repeat";
        case Icon::RepeatOne:    return "repeat-one";
        case Icon::Volume:       return "volume";
        case Icon::VolumeMute:   return "volume-mute";
        case Icon::Back:         return "back";
        case Icon::FullScreen:   return "fullscreen";
        case Icon::Search:       return "search";
        case Icon::Plus:         return "plus";
        case Icon::OpenFile:     return "open-file";
        case Icon::Album:        return "album";
        case Icon::Track:        return "track";
        case Icon::Artist:       return "artist";
        case Icon::Folder:       return "folder";
        case Icon::Playlist:     return "playlist";
        case Icon::Equalizer:    return "equalizer";
        case Icon::Wallpaper:    return "wallpaper";
        case Icon::Settings:     return "settings";
        case Icon::Sun:          return "sun";
        case Icon::Moon:         return "moon";
        case Icon::Wand:         return "wand";
        case Icon::Metronome:    return "metronome";
        case Icon::Dial:         return "dial";
        case Icon::Lock:         return "lock";
        case Icon::Timer:        return "timer";
        case Icon::Chevron:      return "chevron";
        case Icon::Queue:        return "queue";
        case Icon::More:         return "more";
        case Icon::Close:        return "close";
        case Icon::Heart:        return "heart";
        case Icon::HeartFilled:  return "heart-filled";
        case Icon::Visualizer:   return "visualizer";
        case Icon::SmartPlaylist: return "smart-playlist";
        case Icon::Podcast:      return "podcast";
        case Icon::Trash:        return "trash";
        case Icon::CheckCircle:  return "check-circle";
    }
    return QString();
}

// The SVGs (resources/ui/) are drawn in "currentColor"; that keyword
// is swapped for a concrete color before rendering, so one file serves
// every theme and button state.
QPixmap RenderIcon(Icon kind, const QColor& color, int logicalSize, qreal dpr)
{
    static QHash<int, QByteArray> sources; // raw SVG text, read from the resource once per icon
    auto it = sources.find(static_cast<int>(kind));
    if (it == sources.end())
    {
        QFile file(QStringLiteral(":/icons/ui/%1.svg").arg(IconFileName(kind)));
        it = sources.insert(static_cast<int>(kind), file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray());
    }

    const int px = qRound(logicalSize * dpr);
    QPixmap pixmap(px, px);
    pixmap.fill(Qt::transparent);

    QByteArray svg = it.value();
    svg.replace("currentColor", color.name(QColor::HexRgb).toLatin1());
    QSvgRenderer renderer(svg);
    if (renderer.isValid())
    {
        QPainter p(&pixmap);
        p.setRenderHint(QPainter::Antialiasing);
        renderer.render(&p, QRectF(0, 0, px, px));
    }

    // QIcon's alpha comes from the color itself (e.g. disabled states),
    // so apply it here -- hex names above drop the alpha channel.
    if (color.alpha() < 255)
    {
        QPainter p(&pixmap);
        p.setCompositionMode(QPainter::CompositionMode_DestinationIn);
        p.fillRect(pixmap.rect(), QColor(0, 0, 0, color.alpha()));
    }

    pixmap.setDevicePixelRatio(dpr);
    return pixmap;
}
} // namespace

QIcon icon(Icon kind, const QColor& normal, const QColor& checked, const QColor& disabled, int logicalSize)
{
    // Rendered at 2x so they stay crisp on HiDPI screens; Qt scales down on 1x.
    constexpr qreal kDpr = 2.0;
    const QColor on = checked.isValid() ? checked : normal;
    const QColor off = disabled.isValid() ? disabled : normal;

    QIcon result;
    result.addPixmap(RenderIcon(kind, normal, logicalSize, kDpr), QIcon::Normal, QIcon::Off);
    result.addPixmap(RenderIcon(kind, on, logicalSize, kDpr), QIcon::Normal, QIcon::On);
    result.addPixmap(RenderIcon(kind, off, logicalSize, kDpr), QIcon::Disabled, QIcon::Off);
    result.addPixmap(RenderIcon(kind, off, logicalSize, kDpr), QIcon::Disabled, QIcon::On);
    return result;
}

QColor generatedArtColor(const QString& seed)
{
    const int hue = static_cast<int>(qHash(seed, 0x5eed) % 360u);
    return QColor::fromHsv(hue, 150, 215);
}

QPixmap generatedArt(const QString& seed, int logicalSize, qreal radius, Icon mark, int hue)
{
    constexpr qreal kDpr = 2.0;
    const int px = qRound(logicalSize * kDpr);
    const QColor base = hue >= 0 ? QColor::fromHsv(hue % 360, 150, 215) : generatedArtColor(seed);
    // A second, shifted hue for the far corner, both deep enough for white on top.
    const QColor from = QColor::fromHsv(base.hue(), 170, 150);
    const QColor to = QColor::fromHsv((base.hue() + 40) % 360, 190, 70);

    QPixmap pixmap(px, px);
    pixmap.fill(Qt::transparent);
    QPainter p(&pixmap);
    p.setRenderHint(QPainter::Antialiasing);
    QLinearGradient gradient(0, 0, px, px);
    gradient.setColorAt(0.0, from);
    gradient.setColorAt(1.0, to);
    p.setPen(Qt::NoPen);
    p.setBrush(gradient);
    p.drawRoundedRect(QRectF(0, 0, px, px), radius * kDpr, radius * kDpr);
    // A soft glow in the upper left.
    QRadialGradient glow(px * 0.25, px * 0.2, px * 0.7);
    glow.setColorAt(0.0, QColor(255, 255, 255, 60));
    glow.setColorAt(1.0, QColor(255, 255, 255, 0));
    p.setBrush(glow);
    p.drawRoundedRect(QRectF(0, 0, px, px), radius * kDpr, radius * kDpr);

    const int markPx = px * 2 / 5;
    const QPixmap markPixmap = RenderIcon(mark, QColor(255, 255, 255, 150), markPx, 1.0);
    p.drawPixmap((px - markPx) / 2, (px - markPx) / 2, markPixmap);
    p.end();
    pixmap.setDevicePixelRatio(kDpr);
    return pixmap;
}

QPixmap roundedPixmap(const QPixmap& source, qreal radius)
{
    if (source.isNull())
    {
        return source;
    }

    QPixmap result(source.size());
    result.fill(Qt::transparent);

    // Filling a rounded rect with the image as its brush (rather than
    // clipping) keeps the corners antialiased.
    QPainter p(&result);
    p.setRenderHint(QPainter::Antialiasing);
    p.setPen(Qt::NoPen);
    p.setBrush(QBrush(source));
    const qreal r = radius * source.devicePixelRatio();
    p.drawRoundedRect(QRectF(QPointF(0, 0), QSizeF(source.size())), r, r);
    p.end();

    result.setDevicePixelRatio(source.devicePixelRatio());
    return result;
}

} // namespace audioforge::theme
