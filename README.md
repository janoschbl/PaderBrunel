<div align="center">

<img src="assets/icon.svg" width="96" height="96" alt="Brunel icon">

# PaderBrunel

**A modern Android client for the PaderSprinter timetable**

Kotlin · Jetpack Compose · Material 3 Expressive

![Android 12+](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)

</div>

---

The app is called Brunel. It talks to the ivanto SRS backend of PaderSprinter, the same one the original app uses, and the UI is German.

## Features

- **Abfahrten.** Live departures at the stops around you.
- **Verbindungen.** Trip search from stop or address to stop or address, ranked by realtime. A delayed train counts at the time it really leaves.
- **Meine Fahrt.** Saved trips with a live view, broken-transfer warnings, and a button to swap any ride that has not started for another connection.
- **Fahrtbegleitung.** Spoken announcements through headphones while you ride.
- **Karte.** Stops on an OpenStreetMap map with the departures of each platform.
- **Störungen.** Disruptions grouped by notice, and the network maps as PDFs.

## Modules

| Module | Purpose |
|---|---|
| `app` | The client. |
| `capture` | Separate debug app that records Bluetooth bus advertisements, see [the radar notes](docs/bluetooth-radar.md). |
| `tools` | `api_check.sh` calls every backend endpoint the app uses. `ui.sh` drives the app over adb. |

## Build

```sh
export JAVA_HOME=~/.local/share/mise/installs/java/temurin-21
./gradlew installDebug        # needs SDK platform android-37.0
./gradlew testDebugUnitTest
```

## How it works

Requests go to `https://service.ivanto.de/srs/api/v1/` with the tenant and app identifiers of the original app (`SrsApi.kt`). Ticketing, login, and push are not used.

- **Trip search** (`TripSearch.kt`) resolves addresses to nearby stops and routes stop to stop in parallel with at most one change. A slower pass with two changes follows in the background.
- **Saved trips** (`LiveTrip.kt`) refresh realtime data per leg and flag transfers that no longer work.
- **Settings** under Profil, Einstellungen set the search style, the minimum transfer time, and the maximum number of changes.

## License

[The Unlicense](LICENSE). Public domain, everyone may do anything with it.

## Disclaimer

PaderBrunel is an unofficial project. It is not affiliated with PaderSprinter, Nahverkehrsverbund Paderborn/Höxter, or ivanto.
