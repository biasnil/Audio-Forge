# AudioForge Android: roadmap and handoff

Written 2026-10-21 for the next Claude session (and any developer) picking up
the Android app. Read the whole file before starting.

## Working rules (from the owner)

- **Ask before anything outward-facing.** Commit on `main` (no new branches),
  then ask the owner before every `git push`, tag or release.
- One feature per commit. The owner builds and tests each one in Android
  Studio before the next starts.
- Build both flavors before saying a feature is done:
  `./gradlew assembleGithubDebug testGithubDebugUnitTest lintPlayRelease`.
  If you can't build (no Android SDK in the environment), say so plainly.
- Match the surrounding code: KDoc on classes and non-obvious functions,
  plain Kotlin logic in testable files (see `data/Podcasts.kt` and
  `test/.../PodcastsTest.kt`), Compose for UI.
- Every new user-visible string goes into `res/values/strings.xml` **and**
  the four translations: `values-fr`, `values-ms`, `values-b+zh+Hans`,
  `values-b+zh+Hant`.
- Anything that sends data off the phone must be added to the privacy policy
  (`res/raw/privacy.txt` and its four translations in `raw-*`), and to the
  Data safety notes in `PLAY_STORE.md`.
- New libraries go in `gradle/libs.versions.toml` and the in-app credits in
  `data/Acknowledgements.kt`. Only permissive or LGPL licenses; no paid SDKs,
  no Google Play Services (the `github` flavor must work without them).
- Never use trademarked names in the UI or store listing: no "Dolby",
  "Atmos", "DTS", "Wrapped" (Spotify), etc.
- Every feature is free. There is no paid tier.

## Done

| What | Where |
|---|---|
| Play Store readiness review (target API 36, permissions, 16 KB pages, scoped storage) | passed; notes in `PLAY_STORE.md` |
| `github` / `play` build flavors: the GitHub update check is off in `play` (Play forbids pointing users at updates from elsewhere) | `app/build.gradle.kts` (`productFlavors`) |
| Optional release signing from `android/keystore.properties` (gitignored) | `app/build.gradle.kts`, `keystore.properties.example` |
| Play Console guide: signing, privacy URL, Data safety answers, background-playback form, Android Auto review, 12-tester rule, store listing | `android/PLAY_STORE.md` |
| README release steps for both flavors | `README.md`, "Android app" section |

Commit `7ad35cd` on `main` (pushed). The owner's Play developer account is
still being verified; until then releases go to GitHub only.

Not yet verified: the flavor change has not been built (the session that made
it had no Android SDK). Build it first.

## Next release: Androidv1.4.2 (GitHub)

Set `versionName = "1.4.2"` and raise `versionCode` (currently 6) in
`app/build.gradle.kts`; tag `Androidv1.4.2`; attach the `githubRelease` APK.
The version jumps from 1.0.2 to line up with the desktop 1.4.x.

Features in order (smallest and most self-contained first).

### 1. Widget gallery (all styles free)

A **Settings → Widgets** page listing every style with a live preview and an
**Add** button that calls `AppWidgetManager.requestPinAppWidget` (fall back to
"long-press the home screen → Widgets" text when the launcher doesn't
support pinning). Each style is also its own entry in the launcher's widget
picker.

| Style | Size | Contents |
|---|---|---|
| Mini | 3×1 | previous / play-pause / next |
| Lite | 4×1 | + shuffle and Loved heart |
| Classic | 4×1 | cover + controls |
| Simple | 3×2 | title, progress bar, controls |
| Standard | 4×2 | round cover + controls |
| Stylish | 4×2 | cover left half, controls right |
| Practical | 4×3 | blurred cover background, progress, full controls |
| Feature-Rich | 4×3 | Practical + next 2 songs + current lyric line |

Per-widget background option: cover colours (already computed for Now
Playing) / solid black / translucent.

Notes:
- Recommended: Jetpack Glance (`androidx.glance:glance-appwidget`) for
  Compose-style layouts; RemoteViews is fine too.
- Controls send commands to `playback/PlaybackService.kt` (the
  `MediaLibraryService`); widgets update from the player state, not by polling.
  Progress bars update on play/pause/seek/track change only (widget updates
  are rate-limited), with the elapsed time computed from the last known
  position.
- Tapping the widget body opens `MainActivity` (it is `singleTop`).
- Design original artwork and layouts; the owner showed another app's
  widget page as inspiration only, so don't copy its images.
- README says Android lacks only the home-screen widget vs iOS; update that line.

### 2. Listening stats

A **Settings → Listening stats** page: top songs, artists, albums, genres,
total hours, per month and per year, plus a shareable year-in-review image
(call it "AudioForge Year in Review", not "Wrapped").

Notes:
- Today only per-song totals exist (`data/SongData.kt`: `SongStats.playCount`,
  `lastPlayedMs`). Add a play log: one entry per play `{uri, startedAtMs,
  listenedMs}`, appended where `recordPlay` is called, stored in its own file
  in `filesDir` (keep `SongDataStore` small). Count a play only after ~30 s or
  half the track, whichever is shorter.
- Stats start from the release that adds the log; say so on the page.
- Aggregation in a plain-Kotlin file with JVM unit tests.
- Genres come from the existing genre detection (`data/Genre.kt`).
- Podcasts and radio (feature 4) are counted separately.
- Stays on the phone; no privacy policy change.

### 3. Podcast search and download

The Podcasts tab (`ui/PodcastsTab.kt`, `data/Podcasts.kt`) today only shows
files in folders marked Podcast. Add: search shows, subscribe, episode list,
download, "Check for new episodes".

Notes:
- Search: Apple's iTunes Search API
  (`https://itunes.apple.com/search?media=podcast&term=...`), no key needed.
  Skip Podcast Index for now (its secret key would have to ship in the app).
- Episodes: parse the show's public RSS feed (enclosure URL, title, date,
  duration, artwork). Only RSS enclosures; never Spotify/YouTube or paid feeds.
- Download into `Music/Podcasts/<Show>/` through `MediaStore` with
  `IS_PODCAST = 1`, so the existing `isPodcast()` (`track.systemPodcast`)
  picks them up with no other changes. Use a foreground download with a
  progress notification (WorkManager is fine). Don't change the audio
  (keep the show's ads, tags and artwork).
- Subscriptions saved locally. No automatic background downloads in this
  release.
- Do not reuse code from github.com/utlandingur/podcast-downloader (its
  license forbids commercial use; it's a web app anyway). The idea is fine.
- Privacy policy: add Apple (search terms) and podcast hosts (feed and file
  downloads see the IP).

### 4. Internet radio

On-demand streaming: nothing runs unless the user presses play. Port the
ideas from the owner's desktop app github.com/biasnil/World_Radio
(`core/radio_browser.py`).

- Data: Radio Browser API (radio-browser.info). Pick a mirror (resolve
  `all.api.radio-browser.info` or try a list of mirrors), retry on a
  different mirror on failure, send a `User-Agent: AudioForge/<version>`
  header, use `hidebroken=true`, and call `/json/url/<stationuuid>` when a
  station starts playing (their requested click counting).
- Browse: search by name / country (by ISO code, as the desktop does) / tag,
  Top stations, Favorites, Recently played. Page results (the desktop pages
  with `limit`/`offset`, 500 first then 1000). A world map can come later.
- Playback through the existing ExoPlayer pipeline so EQ, effects, sleep
  timer, notification and Android Auto work. Add
  `androidx.media3:media3-exoplayer-hls` for HLS stations. Show the ICY
  "now playing" title from stream metadata on Now Playing and the lock screen.
  Radio is a live stream, not a `Track`: no crossfade, seeking, play counts,
  lyrics or gapless; decide in `playback/PlaybackManager.kt` how a live item
  sits in the queue (simplest: playing a station replaces the queue).
- Android Auto: a Radio node (Favorites, Recently played) in
  `PlaybackService.LibraryCallback`.
- Many streams are plain `http://`: add a network security config allowing
  cleartext for playback only.
- Play Store: no recording feature; a "Report broken station" action (Radio
  Browser vote/report endpoints); content rating must say the app streams
  third-party content.
- Privacy policy: Radio Browser (searches), station servers (IP address).

## Later (free "premium" features, not yet scheduled)

The owner wants these considered; schedule with them before starting.

**Sound** (all DSP goes in `audio/AudioEffectsProcessor.kt`, which already
runs the 10-band EQ inside each player's pipeline, so effects behave the same
on every phone)
- **3D Surround**: headphone virtualization with HRTF data (freely licensed
  sets exist, e.g. MIT KEMAR), presets Room / Hall / Stadium; **Crossfeed**
  (libbs2b, MIT); **Stereo width**; **Reverb** presets; a switch for Android's
  `Spatializer` (Android 13+, head tracking) shown only on supported devices.
- **Headphone correction**: AutoEq database (MIT), pick a model and apply its
  EQ.
- **Per-device EQ**: different EQ per Bluetooth device / speaker / car,
  switched on connect.
- **Bass boost, loudness, limiter** (the file says there's no limiter yet:
  output is hard-clipped above 100% volume).
- **Karaoke**: vocal removal (centre-channel cancel) and pitch shift without
  speed change.
- **Bit-perfect USB DAC** output (Android 14 `AudioMixerAttributes`).
- **Skip silence**, **mono**, **left/right balance**.

**Library and lyrics**
- **Lyrics maker**: tap along to create synced `.lrc`.
- **Auto-fill missing covers/tags** from MusicBrainz + Cover Art Archive (the
  desktop already does MusicBrainz).
- **Audiobook mode**: `.m4b` chapters, bookmarks, per-book resume.
- **CUE sheets**: split one-file albums into tracks.
- **Backup and restore**: playlists, Loved, play counts, settings to one
  file. Important for users moving from the GitHub APK to the Play build
  (different signing keys, so they can't update in place).
- **Scrobbling**: Last.fm / ListenBrainz, opt-in.

**Looks**
- **Themes**: pure black, Material You, custom accent, Now Playing layouts.
- **Gestures**: swipe the cover to skip, shake to shuffle.

**Formats**
- **APE (Monkey's Audio)**: Android and Media3 have no decoder. Skipped for
  1.4.2. Best option when it's wanted: the official Monkey's Audio SDK (BSD)
  via the NDK, a Media3 extractor that outputs PCM, and the same path in
  `audio/AudioDecoder.kt` for Key/BPM and genre analysis. Tag editing
  (jaudiotagger) can't write APE tags.

## Play Store (when the developer account is ready)

Follow `PLAY_STORE.md`. Things that affect future features:
- Always upload `bundlePlayRelease`; never the `github` flavor.
- No in-app downloading from YouTube, Spotify, SoundCloud or similar, and no
  "free MP3 download" wording. Podcasts (RSS) and radio streaming are fine.
- New personal accounts need 12+ testers opted in for 14 straight days
  (recruit ~15 so a drop-out doesn't restart the clock).
- Android developer verification rolls out worldwide in 2027: register the
  GitHub APK's signing key in the developer verification too, or sideloaded
  installs may be blocked.
- Keep the Data safety form in step with the privacy policy as features 3
  and 4 add network use.
