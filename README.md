# NOVA IPTV (Android)

A native Android IPTV player (phone, tablet, Android TV / Google TV) built with
Kotlin, Jetpack Compose (Material 3), Media3/ExoPlayer, Room, DataStore, Hilt,
Retrofit/OkHttp, WorkManager and the Android Keystore.

NOVA ships **no channels or subscriptions**. Users add their own legal
Xtream Codes or M3U subscriptions. DRM/content protection is never bypassed.

## Direct download

After the GitHub Actions workflow has run successfully on `main`:

```
https://github.com/<OWNER>/<REPO>/releases/latest/download/NOVA-IPTV.apk
```

## Build locally

Requirements: Android Studio (Ladybug or newer) or JDK 17 + Android SDK 35.

```bash
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
```

### Signed release (optional)

Add these repository secrets and the workflow also builds a signed, minified release:

| Secret | Value |
|---|---|
| `NOVA_KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `NOVA_KEYSTORE_PASSWORD` | keystore password |
| `NOVA_KEY_ALIAS` | key alias |
| `NOVA_KEY_PASSWORD` | key password |

Without secrets, `NOVA-IPTV.apk` in the release is the debug-signed build (installable for testing).

## Architecture

```
data/
  db/         Room entities + DAOs (subscriptions, categories, content, epg, favorites, history)
  network/    Retrofit API (dynamic URLs), streaming Xtream JSON parser, URL builders
  parser/     Streaming M3U parser, streaming XMLTV parser (gzip aware)
  repo/       Subscription sync, content queries, settings (DataStore), encrypted backup
  security/   Keystore-backed envelope encryption, passphrase backup crypto
player/       Media3 PlayerActivity (PiP, reconnect, quality, aspect, zapping, catch-up)
ui/           Compose screens: onboarding, home (Live/Movies/Series/Favorites/Search),
              details, EPG grid, settings, hidden categories
work/         WorkManager periodic refresh
```

## Security notes

* Passwords, M3U URLs, file URIs and EPG URLs are encrypted (AES-GCM, key wrapped by Android Keystore).
* Stream URLs with credentials are built in memory at play time and never logged; no HTTP logging interceptor.
* `allowBackup=false`; user-added CAs are not trusted; HTTPS uses platform validation.
* Cleartext HTTP is allowed because most IPTV providers only serve HTTP.
