<img src="assets/icon.svg" width="64" height="64" alt="">

# Brunel

Android client for the PaderSprinter timetable backend (ivanto SRS), written in Kotlin with Jetpack Compose and Material 3 Expressive. The UI is German.

## Modules

- `app` is the client: departures near you, trip search, station boards, a map (osmdroid with OpenStreetMap tiles), disruptions, saved trips with a live view, and a spoken trip companion for headphones.
- `capture` is a separate debug app that records Bluetooth bus advertisements. See [docs](docs/bluetooth-radar.md).
- `tools` has `api_check.sh`, which calls every backend endpoint the app uses, and `ui.sh`, which drives the app over adb.

## Build

```sh
export JAVA_HOME=~/.local/share/mise/installs/java/temurin-21
./gradlew installDebug      # needs SDK platform android-37.0
./gradlew testDebugUnitTest
```

## How it works

Requests go to `https://service.ivanto.de/srs/api/v1/` with the tenant and app identifiers of the original app (`SrsApi.kt`). Ticketing, login and push are not used.

- Trip search (`TripSearch.kt`) resolves addresses to nearby stops, routes stop to stop in parallel with at most one change, then runs a slower pass with two changes. It ranks by realtime, so a delayed train counts at the time it leaves.
- Saved trips (`LiveTrip.kt`) refresh realtime data per leg, flag broken transfers, and let you swap any ride that has not started for another connection.
- Search style, minimum transfer time and maximum changes are set under Profil, Einstellungen.
