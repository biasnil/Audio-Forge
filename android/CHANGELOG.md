# AudioForge for Android: changelog

## 1.4.3

Everything here is free. AudioForge is now open source under the GPL-3.0
licence: use it, change it and share it, as long as what you share stays
open too.

### New

- **Free audiobooks.** Audiobooks → Find free audiobooks searches about
  20,000 public-domain books read by LibriVox volunteers (from the Internet
  Archive). A book's page shows its cover, length, description and chapters;
  **Download** saves it in your phone's Audiobooks folder with the cover and
  chapter names, as one book with bookmarks and resume. It carries on if you
  close the app, and parts that fail can be tried again.
- **Download from link (GitHub version only).** Paste a link, or share one
  from YouTube or another app to AudioForge, and download it:
  - **As songs** into Music: M4A with the title, artist and cover.
    "Artist - Title" names are split, and extras like "(Official Video)"
    are left out.
  - **As an audiobook** into Audiobooks, keeping the video's chapters. A
    playlist can be saved as one book.
  - Playlists: pick which items to download. One at a time, with progress in
    the notification and Stop; it carries on with the screen off.
  - Built on yt-dlp (adapted from Media Downloader Toolkit), which updates
    itself from GitHub so it keeps working when sites change. Only download
    what you have the right to. The Play Store version doesn't have this.

- **Edit this song.** Long-press a song (in Songs, Now Playing and other
  lists) → **Edit this song**:
  - **Audio:** drag across the waveform to select a part, then **Cut** it
    (the rest closes up) or **Keep only this** (trims the start and end).
    Change the volume or **Normalize**, undo, and listen before saving.
    **Save** asks each time: a new copy next to the original, or replace
    the original. M4A and WAV songs can be replaced; Android can't write
    MP3, FLAC or OGG, so those are saved as an M4A copy. Tags and cover are
    kept.
  - **Lyric:** the synced lyrics against a close-up of the waveform, with a
    mark where each line starts. Tap a line, then **Set to now** as it's
    sung; nudge a line, or all of them, by 0.05 s. **Save** keeps the new
    timing for that song; **Export .lrc** saves a file other players can
    read, wherever you choose (it starts in the song's folder).

### Better

- **Less text on screen.** Long explanations under settings now sit behind
  a small (i) next to each one; tap it to read.

### Privacy

- The privacy policy now covers free audiobooks (your search words and the
  books you open go to archive.org) and, in the GitHub version, Download from
  link (the link goes to the site it points to; yt-dlp checks GitHub for
  updates).

### Notes

- The GitHub APK is bigger (about 220 MB), because Download from link
  brings its own Python, yt-dlp and FFmpeg.
- Translations of the new text are machine-drafted.

## 1.4.2

Everything here is free.

### New

- **Effects tab.** Sound effects now have their own tab: **3D Surround**
  (with presets), **stereo width**, **reverb**, and **Android spatial audio**
  (with head tracking on headphones that support it, Android 13+, when your
  phone has it).
- **Headphone correction.** Pick your headphones from about 8,800 models and
  the app corrects their sound (AutoEq data, built in, works offline).
- **A separate EQ for each device.** Bluetooth headphones, the phone's
  speaker, your car: each keeps its own EQ and switches automatically when you
  connect.
- **Bass boost, Loudness and a Limiter.** Loudness adds bass and treble when
  the volume is low; the limiter turns loud peaks down smoothly instead of
  crackling.
- **Karaoke.** Remove the singer's voice, and change the key in semitones
  without changing the speed.
- **Bit-perfect USB DAC output** (Android 14+). Songs reach your DAC
  untouched, at their own sample rate.
- **Mono and left/right balance.**
- **Skip silence, separately for podcasts and songs.** Podcasts skip every
  pause; songs only skip silence at the start and end, never in the middle.
- **Lyrics sync slider.** Fine-tune lyrics timing up to ±10 s in 0.05 s
  steps, instead of the ±0.5 s buttons.
- **Themes.** Pure black, Material You (your wallpaper's colours), or your
  own accent colour.
- **Audiobooks.** Chapters (.m4b), bookmarks, and resume where you left off.
  A book split into many files is one book.
- **Find missing tags and covers.** "Find missing info" in the tag editor
  fills empty fields; "Find missing covers" in Settings → Library finds covers
  for albums, and for songs without an album. Both use Apple, then Deezer, no
  account needed, and never change your music files unless you tap Save.
- **Choose your tabs.** Settings → Tab bar: drag any 4 tabs (Effects,
  Podcasts, Radio…) onto the tab bar; the rest go under More.

### Better

- **Clearer Settings.** Grouped into categories like your phone's Settings,
  with bigger, easier-to-read text; Language is easier to find.
- **Big libraries are much faster.** Covers load in the background and are
  kept small; added folders only re-read new or changed files (800 files:
  16 s → 1 s); search, sorting and grouping no longer slow the screen; typing
  in the Songs search stays smooth with thousands of songs.
- **Analyze and Detect Genres work on several songs at once**, side by side,
  and stay out of the way of scrolling and playback.

### Privacy

- The privacy policy now covers Find missing info and Find missing covers:
  the song's title and artist (or an album's name and artist) are sent to
  Apple, and to Deezer if Apple has nothing. Only when you tap them.
- The app now asks to change audio settings (MODIFY_AUDIO_SETTINGS), used
  only for bit-perfect USB output.

### Notes

- Translations (French, Malay, Simplified and Traditional Chinese) of the new
  text are machine-drafted.
- Bit-perfect output hasn't been tested with a real USB DAC yet.

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
