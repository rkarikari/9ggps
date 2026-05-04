# 9GGPS v2.0 — Upgrade Notes
**Copyright © R.N.K 9G5AR RadioZport**

---

## What Changed vs v1.x

### New Features

#### Navigation Engine
- **Lane guidance** — per-step lane arrows extracted from OSRM intersection data
- **Alternative routes** — up to 3 route options with traffic condition labels, CO₂ estimates, and highlights (e.g. "Via highway", "Avoids tolls")
- **Smart rerouting** — 8-second off-route grace period, 30-second reroute cooldown, TTS announcement before rerouting
- **Speed limit display** — extracted from OSRM `maxspeed` annotations and shown on the HUD
- **Tunnel detection** — heuristic based on GPS accuracy degradation + consistent speed
- **Waypoint support** — add via-stops to any navigation session
- **Route history** — every navigation is logged; favourite routes surfaced first in search

#### GPS & Sensors
- **Barometric altitude fusion** — pressure sensor + GPS altitude blended (70/30) via exponential moving average, reducing GPS altitude noise
- **Sensor-fused bearing** — rotation vector sensor blended with GPS bearing; low-speed bearing uses sensor; high-speed uses GPS
- **Dead reckoning** — if GPS is lost for >5 s, position is estimated forward using last known speed + bearing (displayed on map with confidence radius)
- **Multi-constellation GNSS status** — per-satellite C/N₀, elevation, azimuth, L1/L5 multiband detection, constellation breakdown (GPS, GLONASS, Galileo, BeiDou, NavIC)

#### Track Recording & Analytics
- **Grade computation** — road slope % computed per point using altitude diff / horizontal dist after smoothing
- **Speed zone analysis** — 5-zone distribution (0-30, 30-60, 60-90, 90-120, 120+ km/h)
- **Moving time vs stopped time** split
- **Training load** score (TSS-like: MET × hours × intensity)
- **Calorie estimation** — MET-based formula per activity type (running, cycling, walking, driving…)
- **Bounding box** stored per track for spatial queries
- **Track favorites** and star rating
- **GPX export v1.1** — includes `<gpxtpx:TrackPointExtension>` heart rate, cadence, grade; full metadata

#### POI Search
- **Overpass API integration** — live OSM POI search: restaurants, fuel, hospitals, pharmacies, parking, hotels, banks, charging stations, toilets, camping, viewpoints, police (15 categories)
- Results sorted by distance, with address, phone, website extracted from OSM tags

#### Trip Planner
- Multi-stop trip planning with reorder drag-and-drop
- **Nearest-neighbour TSP optimisation** — automatically reorders stops to minimise total route distance
- Duration + distance matrix via OSRM `/table` API
- Save / load / delete trip plans, persisted in Room

#### Offline Maps
- Estimate tile count and cache size before downloading
- Per-region download progress (`StateFlow<Map<Long, DownloadProgress>>`)
- Cancel in-progress downloads
- Multiple tile sources: Mapnik, OpenTopoMap, CyclOSM, Humanitarian HOT
- OSMDroid `SqlTileWriter` for compressed on-device tile storage

#### Weather
- OpenWeatherMap One Call API 3.0 support (hourly, daily forecast, UV index, dew point)
- 8-point forecast list displayed on map bottom sheet

#### Database
- Room **version 2** with schema migration (`MIGRATION_1_2`) — zero data loss upgrade from v1
- New tables: `route_history`, `trip_plans`, `geofence_events`
- New columns: grade, altitudeFused, cadence, power (track points); bounding box, isFavorite, trainingLoad, weatherTemp (tracks); scheduledDays, linkedAction (geofences); isActive, source, reportCount (speed cameras)

#### Architecture
- `AnalyticsEngine` — weekly/monthly/all-time summaries, personal records, speed zone analysis, grade profiles
- `TrackRepository` — Ramer–Douglas–Peucker polyline simplification, full stat recomputation, GPX import
- `TripPlannerViewModel` — dedicated VM for trip planner fragment
- `OfflineMapManager` — standalone singleton for tile download lifecycle
- Overpass API interface + `OverpassApi` Retrofit service
- OSRM `getDurationMatrix` endpoint for TSP
- `AppModule` — Overpass Retrofit provider added; 20 MB OkHttp disk cache; User-Agent header injection

### Dependency Upgrades
| Library | v1 | v2 |
|---------|----|----|
| AGP | 8.x | 8.4.0 |
| Kotlin | 1.9.x | 2.0.0 |
| Room | 2.5.x | 2.6.1 |
| Retrofit | 2.9 | 2.11.0 |
| OkHttp | 4.10 | 4.12.0 |
| Lifecycle | 2.6 | 2.8.1 |
| Hilt | 2.48 | 2.51.1 |
| Coroutines | 1.7 | 1.8.1 |

### Files Modified
- `data/model/Models.kt` — extended with 20+ new model classes
- `data/db/entity/Entities.kt` — new columns + 2 new entities
- `data/db/dao/Daos.kt` — new DAOs + extended queries
- `data/db/AppDatabase.kt` — version 2 + MIGRATION_1_2
- `data/network/ApiModels.kt` — OSRM annotations, OneCall weather, Overpass API
- `data/repository/NavigationRepository.kt` — Overpass POI, matrix API, richer route building
- `data/repository/TrackRepository.kt` — simplify, recompute, import
- `data/prefs/UserPreferences.kt` — 15+ new preference keys with Flows
- `service/GpsTrackingService.kt` — sensor fusion, dead reckoning, TTS, lane guidance, tunnel detection
- `ui/map/MapViewModel.kt` — full feature surface covering all new capabilities
- `routing/RouteManager.kt` — off-route detection, step advance, speed limit, tunnel heuristic
- `utils/NineGUtils.kt` — dead reckoning, polyline simplify (RDP), sun times, speed zones, TSP optimizer, grade computation
- `utils/NineGpxExporter.kt` — GPX 1.1 with TrackPointExtension
- `utils/OfflineMapManager.kt` — download lifecycle, tile math, multi-source
- `di/AppModule.kt` — Overpass + OkHttp cache + User-Agent
- `analytics/AnalyticsEngine.kt` — NEW
- `ui/tripplanner/TripPlannerViewModel.kt` — NEW
- `app/build.gradle.kts` — updated deps, SDK 35, desugar, Room schema export
- `gradle/libs.versions.toml` — version catalog updated
- `AndroidManifest.xml` — barometer feature, POST_NOTIFICATIONS, BootReceiver, network_security_config

