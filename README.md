# 9G GPS — Android

A **fully-featured, production-grade GPS application** for Android, built with modern Jetpack
libraries, OSMDroid maps, OSRM offline routing and Material Design 3.

---

## Feature Set

| Category | Features |
|---|---|
| **Map** | OSMDroid tiles (Standard / Satellite / Terrain / Night), rotation gestures, compass overlay, scale bar, pinch-zoom, long-press pin drop, tap info |
| **GPS** | Real-time position, speed, altitude, bearing, accuracy, satellite count via Fused Location Provider |
| **Navigation** | OSRM turn-by-turn routing (car/bike/foot), polyline route display, step-by-step voice guidance (TTS), distance/ETA panel, automatic rerouting detection |
| **Track Recording** | Start/Pause/Resume/Stop recording, live stats (distance, duration, avg/max speed, elevation, calories), background foreground service, Wake-Lock |
| **Track Analysis** | Elevation & speed profile charts (MPAndroidChart), mini-map, GPX + KML export, share intent |
| **Waypoints** | Add at current location or any map point, categories, favorites, search, navigate-to |
| **Geofences** | Create circular geofences, enter/exit/dwell triggers, Google Geofencing API, per-event notification, event history |
| **HUD Activity** | Full-screen landscape driving overlay: big speedometer, navigation panel, compass needle, barometric altitude, recording controls |
| **Statistics** | Weekly/monthly bar charts, activity pie chart, speed distribution histogram, all-time totals |
| **Weather** | OpenWeatherMap overlay (temperature, wind, humidity, description) |
| **Speed Cameras** | Room DB entity ready for POI import |
| **Offline Maps** | `OfflineMapManager` tile pre-caching helper |
| **Sensors** | Compass (accelerometer + magnetometer), barometric pressure, altitude |
| **Export/Import** | GPX 1.1 export (with speed/bearing extensions), KML export, GPX SAX import |
| **Settings** | Speed/distance units, map style, theme (light/dark/system), routing profile, voice guidance, battery saver, keep-screen-on, weather API key |
| **Dark Mode** | Full Material 3 day/night support |
| **Boot Receiver** | Auto-restart GPS service after reboot |
| **DI** | Hilt throughout |
| **Architecture** | MVVM + Repository, StateFlow, Room, DataStore |

---

## Tech Stack

| Library | Purpose |
|---|---|
| OSMDroid 6.1.20 | Offline-capable OpenStreetMap tiles |
| OSRM (HTTP) | Free open-source turn-by-turn routing |
| Nominatim (HTTP) | Free geocoding / reverse geocoding |
| OpenWeatherMap API | Current weather (requires API key) |
| Room 2.7 | Local SQLite database |
| Hilt 2.55 | Dependency injection |
| DataStore Preferences | Settings persistence |
| MPAndroidChart | Elevation, speed, statistics charts |
| Retrofit 2 + OkHttp 4 | HTTP networking |
| Navigation Component | Single-Activity navigation |
| WorkManager | Background tasks |
| Kotlin Coroutines + Flow | Async / reactive data |

---

## Quick Start

### 1. Clone & open

```bash
git clone <repo>
```

Open in **Android Studio Panda 3 (2025.3.3 Patch 1)** or later.

### 2. Sync Gradle

The project uses the **version catalog** at `gradle/libs.versions.toml`.
Hit *Sync Project with Gradle Files*.

### 3. API Keys (optional)

Edit `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "OPENWEATHER_API_KEY", "\"YOUR_KEY_HERE\"")
```

Free key from https://openweathermap.org/api

OSRM and Nominatim are free — no key needed.

### 4. Run

Connect a device with GPS or use an emulator with location mocking.
Min SDK: **26 (Android 8)**, Target: **36 (Android 16)**.

---

## Project Structure

```
app/src/main/java/com.nineggps/
├── NineGApp.kt                      Application class, notification channels
├── MainActivity.kt                Single activity, bottom nav
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt         Room database
│   │   ├── dao/   Daos.kt         Track, Waypoint, Geofence, SpeedCamera DAOs
│   │   └── entity/Entities.kt     Room entities
│   ├── model/  Models.kt          Pure Kotlin data classes
│   ├── network/ApiModels.kt       Retrofit API interfaces + response models
│   ├── prefs/  UserPreferences.kt DataStore wrapper
│   └── repository/
│       ├── TrackRepository.kt     DB operations
│       └── NavigationRepository.kt OSRM + Nominatim + Weather
├── di/
│   └── AppModule.kt               Hilt module (DB, Retrofit, Location)
├── receiver/
│   ├── BootReceiver.kt
│   └── GeofenceReceiver.kt
├── service/
│   ├── GpsTrackingService.kt      Core foreground service (GPS, sensors, TTS)
│   └── GeofenceTransitionService.kt
├── ui/
│   ├── map/        MapFragment, MapViewModel, SpeedometerOverlay, SearchResultAdapter
│   ├── navigation/ NavigationFragment, NavigationViewModel
│   ├── tracks/     TracksFragment, TrackDetailFragment (charts + export)
│   ├── waypoints/  WaypointsFragment
│   ├── geofence/   GeofenceFragment
│   ├── hud/        HudActivity (fullscreen driving overlay)
│   ├── dashboard/  DashboardFragment
│   ├── statistics/ StatisticsFragment (all charts)
│   └── settings/   SettingsFragment (PreferenceFragmentCompat)
└── utils/
    ├── NineGUtils.kt                Formatting, polyline decode, calorie estimate
    ├── NineGpxExporter.kt             GPX 1.1 / KML export, GPX SAX import
    ├── NotificationHelper.kt
    └── OfflineMapManager.kt       Tile pre-cache utility
```

---

## Permissions

| Permission | Reason |
|---|---|
| `ACCESS_FINE_LOCATION` | High-accuracy GPS |
| `ACCESS_BACKGROUND_LOCATION` | Track while screen off |
| `FOREGROUND_SERVICE_LOCATION` | Foreground service type |
| `POST_NOTIFICATIONS` | Android 13+ notification permission |
| `INTERNET` | Map tiles, routing, weather |
| `WAKE_LOCK` | Prevent CPU sleep during recording |
| `RECEIVE_BOOT_COMPLETED` | Auto-start after reboot |
| `VIBRATE` | Alert feedback |

---

## Adding a Real Font

Replace the `@font/roboto_mono` reference with a downloaded `.ttf`:

1. Download [Roboto Mono](https://fonts.google.com/specimen/Roboto+Mono)
2. Copy `RobotoMono-Regular.ttf` → `app/src/main/res/font/roboto_mono_regular.ttf`
3. The `roboto_mono.xml` font family file is already in place.

---

## Adding More Features

### Speed Camera DB Import
Implement `SpeedCameraDao.insertCameras(list)` with data from
[OpenSpeedCameras](https://www.openmobilemap.de/) or similar CSV source.

### Custom Map Tiles
Swap `TileSourceFactory.MAPNIK` for any XYZ tile source:
```kotlin
val customSource = XYZTileSource("Custom", 0, 19, 256, ".png",
    arrayOf("https://tile.example.com/"))
mapView.setTileSource(customSource)
```

### Crash Reporting
Add Firebase Crashlytics to `libs.versions.toml` and initialize in `NineGApp`.

---

## License

MIT — free to use, modify, and distribute.
