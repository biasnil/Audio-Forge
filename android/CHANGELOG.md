# AudioForge for Android: changelog

## 1.4.1

Everything here is free.

### New

- **Home-screen widgets.** Eight styles: Mini, Lite, Classic, Simple,
  Standard, Stylish, Practical and Feature-Rich (with the next two songs and
  the lyric line being sung). Each is in your launcher's widget list, and
  **Settings → Widgets** shows them all with live previews and an **Add**
  button. Each widget's background can be the cover's colours (a blurred cover
  on Practical and Feature-Rich), black or translucent; long-press a widget to
  change it. The buttons also work when the app is closed.
- **Listening stats.** **Settings → Listening stats** shows your top songs,
  artists, albums and genres and how long you listened, per month, per year or
  for all time, with podcasts and radio counted separately. Share a year as an
  **AudioForge Year in Review** image. A song counts after 30 seconds (or half
  of a shorter song). Stats start with this version and stay on your phone.
- **Podcast search and downloads.** Search for shows, subscribe, browse
  episodes and download them in the Podcasts tab; **Check for new episodes**
  is in the tab's header. Episodes download with Android's download manager
  into your phone's Podcasts folder, unchanged, and play like any other
  episode (resume, played marks).
- **Internet radio.** A new Radio tab with tens of thousands of stations from
  the Radio Browser directory: search by name or tag, filter by country, Top
  stations, Favorites and Recently played. Stations play through the same
  player as your music (equalizer, effects, sleep timer, notification), show
  the song they're playing on Now Playing and the lock screen, and include HLS
  streams. Nothing plays or loads until you open the tab or tap a station.

### Fixed

- The app's version number now matches the release. 1.4.0 reported itself as
  1.0.2, so Settings → About kept offering an update.

### Privacy

- The privacy policy (Settings → About) now covers podcast search (Apple),
  podcast feeds and downloads (each show's servers), and radio (the Radio
  Browser directory and each station's server). These are only contacted when
  you use those features.
- To play the many stations that still stream over plain http, the app now
  allows http connections; its own requests (lyrics, podcasts, the station
  directory) still use https.

### Notes

- Translations (French, Malay, Simplified and Traditional Chinese) of the new
  text are machine-drafted.
