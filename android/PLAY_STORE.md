# Publishing on Google Play

Steps for the Play build (`play` flavor). The GitHub APK (`github` flavor) is
unaffected.

## 1. Build

1. Create an upload key once: Android Studio → **Build → Generate Signed App
   Bundle or APK → Create new**. Keep the `.jks` file and its passwords outside
   the repository, with a backup.
2. Copy `keystore.properties.example` to `keystore.properties` and fill it in.
3. Raise `versionCode` in `app/build.gradle.kts` (every upload needs a higher one).
4. `./gradlew bundlePlayRelease` → `app/build/outputs/bundle/playRelease/app-play-release.aab`.
5. On the first upload, accept **Play App Signing**.

## 2. Play Console

- **Privacy policy URL:**
  `https://github.com/biasnil/Audio-Forge/blob/main/android/app/src/main/res/raw/privacy.txt`
- **App access:** all functionality is available without special access.
- **Ads:** no ads.
- **Content rating:** music player; no user-to-user communication, no
  gambling or violence. Lyrics come from third parties and may be explicit.
- **Target audience:** 13+ (avoids the Families policy).
- **Foreground service (media playback):** used for music that keeps playing
  with the screen off or another app open. Attach a short video: start a song,
  go to the home screen, show the notification controls.
- **Android Auto:** the manifest declares it, so turn it on under
  **Advanced settings → Form factors**. It is reviewed separately against the
  car app quality guidelines. Leave it off for the first release to avoid
  delays.
- **New personal developer accounts:** a closed test with at least 12 testers
  opted in for 14 days in a row is needed before production access.

## 3. Data safety

- **Does the app collect or share user data?** No. Nothing goes to a server
  run by the developer. Song title and artist are sent to LRCLIB (and
  Musixmatch, only with the user's own key) to fetch lyrics, on request and
  not stored. Under Play's definitions this is not "collection".
  (If you prefer to declare it: **App activity → Other user-generated
  content / Music files**, not shared, not stored, processed ephemerally, for
  app functionality.)
- **Is data encrypted in transit?** Yes (HTTPS).
- **Can users request deletion?** Data stays on the phone; clearing the app's
  storage or uninstalling deletes it.

## 4. Store listing

- App name: AudioForge
- Short description (80 characters max), for example:
  "A music player for your own files: lyrics, EQ, crossfade, tag editing."
- Full description: the feature list from the README's "Android app" section.
- Graphics: 512×512 icon, 1024×500 feature graphic, at least 2 phone
  screenshots (8 max).
- Category: Music & Audio. Contact email: workacckwanrong@gmail.com.
