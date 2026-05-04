// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "gps_prefs")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    // ─── Keys ─────────────────────────────────────────────────────────────────

    companion object {
        val SPEED_UNIT = stringPreferencesKey("speed_unit")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        val MAP_TYPE = stringPreferencesKey("map_type")
        val THEME_MODE = stringPreferencesKey("theme_mode") // LIGHT, DARK, SYSTEM
        val GPS_UPDATE_INTERVAL = longPreferencesKey("gps_update_interval") // ms
        val GPS_MIN_DISTANCE = floatPreferencesKey("gps_min_distance") // meters
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val AUTO_START_TRACKING = booleanPreferencesKey("auto_start_tracking")
        val SPEED_LIMIT_ALERTS = booleanPreferencesKey("speed_limit_alerts")
        val SPEED_ALERT_THRESHOLD = intPreferencesKey("speed_alert_threshold") // % over limit
        val COMPASS_ENABLED = booleanPreferencesKey("compass_enabled")
        val SCALE_BAR_ENABLED = booleanPreferencesKey("scale_bar_enabled")
        val TRAFFIC_ENABLED = booleanPreferencesKey("traffic_enabled")
        val AUTO_ZOOM = booleanPreferencesKey("auto_zoom")
        val TILT_MAP = booleanPreferencesKey("tilt_map")
        val SHOW_SPEED = booleanPreferencesKey("show_speed")
        val SHOW_ALTITUDE = booleanPreferencesKey("show_altitude")
        val SHOW_ACCURACY = booleanPreferencesKey("show_accuracy")
        val SHOW_BEARING = booleanPreferencesKey("show_bearing")
        val SHOW_SATELLITES = booleanPreferencesKey("show_satellites")
        val RECORDING_INTERVAL = longPreferencesKey("recording_interval") // ms
        val RECORDING_MIN_ACCURACY = floatPreferencesKey("recording_min_accuracy")
        val VOICE_GUIDANCE = booleanPreferencesKey("voice_guidance")
        val NIGHT_MODE_MAP = booleanPreferencesKey("night_mode_map")
        val WEIGHT_KG = floatPreferencesKey("weight_kg")
        val MAX_ZOOM = intPreferencesKey("max_zoom")
        val DEFAULT_ZOOM = floatPreferencesKey("default_zoom")
        val LAST_LAT = doublePreferencesKey("last_lat")
        val LAST_LON = doublePreferencesKey("last_lon")
        val OPENWEATHER_KEY = stringPreferencesKey("openweather_key")
        val SHOW_WEATHER = booleanPreferencesKey("show_weather")
        val SHOW_SPEED_CAMERAS = booleanPreferencesKey("show_speed_cameras")
        val ROUTING_PROFILE = stringPreferencesKey("routing_profile") // car, bike, foot
        val AVOID_TOLLS = booleanPreferencesKey("avoid_tolls")
        val AVOID_HIGHWAYS = booleanPreferencesKey("avoid_highways")
        val AVOID_FERRIES = booleanPreferencesKey("avoid_ferries")
        val ACTIVITY_TYPE = stringPreferencesKey("activity_type")
        val HUD_ENABLED = booleanPreferencesKey("hud_enabled")
        val VIBRATION_ALERTS = booleanPreferencesKey("vibration_alerts")
        val GEOFENCE_NOTIFICATIONS = booleanPreferencesKey("geofence_notifications")
        val MAP_ORIENTATION = stringPreferencesKey("map_orientation") // NORTH, DIRECTION
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val USER_NAME = stringPreferencesKey("user_name")
        val OFFLINE_MODE = booleanPreferencesKey("offline_mode")

        // ─── Map Layer & Zoom Persistence ─────────────────────────────────────
        /** Stable id of the last tile-source selected by the user (matches MapLayers.NamedLayer.id). */
        val MAP_LAYER_ID = stringPreferencesKey("map_layer_id")
        /** Last zoom level the user had on the map view — persisted so it survives process death. */
        val MAP_ZOOM_LEVEL = doublePreferencesKey("map_zoom_level")

        // ─── Home Auto-Record ──────────────────────────────────────────────────
        /** Master toggle: automatically start/stop recording based on home proximity. */
        val HOME_AUTO_RECORD = booleanPreferencesKey("home_auto_record")
        /** Whether a home location has been saved. */
        val HOME_IS_SET = booleanPreferencesKey("home_is_set")
        /** Latitude of the saved home location. */
        val HOME_LAT = doublePreferencesKey("home_lat")
        /** Longitude of the saved home location. */
        val HOME_LON = doublePreferencesKey("home_lon")
        /** Distance in metres beyond which departure is detected and recording starts. */
        val HOME_DEPARTURE_RADIUS_M = floatPreferencesKey("home_departure_radius_m")
        /** Distance in metres within which arrival is detected and recording stops. */
        val HOME_ARRIVAL_RADIUS_M = floatPreferencesKey("home_arrival_radius_m")
        /** Minimum trip distance (metres) required to keep a recording. Shorter trips are discarded. */
        val HOME_MIN_TRIP_DISTANCE_M = floatPreferencesKey("home_min_trip_distance_m")
        /** Minimum trip duration (seconds) required to keep a recording. */
        val HOME_MIN_TRIP_DURATION_S = longPreferencesKey("home_min_trip_duration_s")
    }

    // ─── Flows ────────────────────────────────────────────────────────────────

    val speedUnit: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[SPEED_UNIT] ?: "KMH" }

    val distanceUnit: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[DISTANCE_UNIT] ?: "METRIC" }

    val mapType: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[MAP_TYPE] ?: "STANDARD" }

    val themeMode: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[THEME_MODE] ?: "SYSTEM" }

    val gpsUpdateInterval: Flow<Long> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[GPS_UPDATE_INTERVAL] ?: 1000L }

    val keepScreenOn: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEEP_SCREEN_ON] ?: true }

    val speedLimitAlerts: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[SPEED_LIMIT_ALERTS] ?: true }

    val compassEnabled: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[COMPASS_ENABLED] ?: true }

    val voiceGuidance: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[VOICE_GUIDANCE] ?: true }

    val routingProfile: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[ROUTING_PROFILE] ?: "car" }

    val showWeather: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[SHOW_WEATHER] ?: false }

    val mapOrientation: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[MAP_ORIENTATION] ?: "NORTH" }

    val batterySaver: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[BATTERY_SAVER] ?: false }

    /**
     * Maximum acceptable GPS accuracy (metres) for a fix to be saved as a
     * track point.  Fixes with a reported accuracy worse than this value are
     * discarded by the spatial deduplication filter in GpsTrackingService
     * before any further processing.  Default: 30 m — tight enough to reject
     * multipath / NLOS fixes while accepting typical urban GNSS quality.
     */
    val recordingMinAccuracy: Flow<Float> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[RECORDING_MIN_ACCURACY] ?: 30f }

    val activityType: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[ACTIVITY_TYPE] ?: "DRIVING" }

    val weightKg: Flow<Float> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[WEIGHT_KG] ?: 75f }

    val userName: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[USER_NAME] ?: "Explorer" }

    val openWeatherKey: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[OPENWEATHER_KEY] ?: "" }

    val offlineMode: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[OFFLINE_MODE] ?: false }

    /** The tile-source id last selected by the user; defaults to OSMMapnik (OSM Standard). */
    val mapLayerId: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[MAP_LAYER_ID] ?: "OSMMapnik" }

    /** The zoom level last used by the user on the main map; defaults to 15.0. */
    val mapZoomLevel: Flow<Double> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[MAP_ZOOM_LEVEL] ?: 15.0 }


    // ─── Home Auto-Record Flows ───────────────────────────────────────────────

    val homeAutoRecord: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[HOME_AUTO_RECORD] ?: false }

    val homeIsSet: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[HOME_IS_SET] ?: false }

    val homeLat: Flow<Double> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[HOME_LAT] ?: 0.0 }

    val homeLon: Flow<Double> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[HOME_LON] ?: 0.0 }

    val homeDepartureRadiusM: Flow<Float> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[HOME_DEPARTURE_RADIUS_M] ?: 200f }

    val homeArrivalRadiusM: Flow<Float> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[HOME_ARRIVAL_RADIUS_M] ?: 150f }

    val homeMinTripDistanceM: Flow<Float> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[HOME_MIN_TRIP_DISTANCE_M] ?: 500f }

    val homeMinTripDurationS: Flow<Long> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[HOME_MIN_TRIP_DURATION_S] ?: 60L }

    // ─── Setters ──────────────────────────────────────────────────────────────

    suspend fun setSpeedUnit(value: String) = dataStore.edit { it[SPEED_UNIT] = value }
    suspend fun setDistanceUnit(value: String) = dataStore.edit { it[DISTANCE_UNIT] = value }
    suspend fun setMapType(value: String) = dataStore.edit { it[MAP_TYPE] = value }
    suspend fun setThemeMode(value: String) = dataStore.edit { it[THEME_MODE] = value }
    suspend fun setGpsUpdateInterval(value: Long) = dataStore.edit { it[GPS_UPDATE_INTERVAL] = value }
    suspend fun setKeepScreenOn(value: Boolean) = dataStore.edit { it[KEEP_SCREEN_ON] = value }
    suspend fun setSpeedLimitAlerts(value: Boolean) = dataStore.edit { it[SPEED_LIMIT_ALERTS] = value }
    suspend fun setCompassEnabled(value: Boolean) = dataStore.edit { it[COMPASS_ENABLED] = value }
    suspend fun setVoiceGuidance(value: Boolean) = dataStore.edit { it[VOICE_GUIDANCE] = value }
    suspend fun setRoutingProfile(value: String) = dataStore.edit { it[ROUTING_PROFILE] = value }
    suspend fun setShowWeather(value: Boolean) = dataStore.edit { it[SHOW_WEATHER] = value }
    suspend fun setMapOrientation(value: String) = dataStore.edit { it[MAP_ORIENTATION] = value }
    suspend fun setBatterySaver(value: Boolean) = dataStore.edit { it[BATTERY_SAVER] = value }
    suspend fun setRecordingMinAccuracy(value: Float) = dataStore.edit {
        it[RECORDING_MIN_ACCURACY] = value.coerceIn(5f, 200f)
    }
    suspend fun setActivityType(value: String) = dataStore.edit { it[ACTIVITY_TYPE] = value }
    suspend fun setWeightKg(value: Float) = dataStore.edit { it[WEIGHT_KG] = value }
    suspend fun setLastLocation(lat: Double, lon: Double) = dataStore.edit {
        it[LAST_LAT] = lat
        it[LAST_LON] = lon
    }
    suspend fun setOpenWeatherKey(key: String) = dataStore.edit { it[OPENWEATHER_KEY] = key }
    suspend fun setUserName(name: String) = dataStore.edit { it[USER_NAME] = name }
    suspend fun setAvoidTolls(value: Boolean) = dataStore.edit { it[AVOID_TOLLS] = value }
    suspend fun setAvoidHighways(value: Boolean) = dataStore.edit { it[AVOID_HIGHWAYS] = value }
    suspend fun setOfflineMode(value: Boolean) = dataStore.edit { it[OFFLINE_MODE] = value }
    suspend fun setMapLayerId(id: String) = dataStore.edit { it[MAP_LAYER_ID] = id }
    suspend fun setMapZoomLevel(zoom: Double) = dataStore.edit { it[MAP_ZOOM_LEVEL] = zoom }

    // ─── Home Auto-Record Setters ─────────────────────────────────────────────

    suspend fun setHomeAutoRecord(value: Boolean) = dataStore.edit { it[HOME_AUTO_RECORD] = value }
    suspend fun setHomeLocation(lat: Double, lon: Double) = dataStore.edit {
        it[HOME_LAT] = lat
        it[HOME_LON] = lon
        it[HOME_IS_SET] = true
    }
    suspend fun clearHomeLocation() = dataStore.edit {
        it[HOME_IS_SET] = false
        it[HOME_LAT] = 0.0
        it[HOME_LON] = 0.0
    }
    suspend fun setHomeDepartureRadiusM(value: Float) = dataStore.edit { it[HOME_DEPARTURE_RADIUS_M] = value.coerceAtLeast(50f) }
    suspend fun setHomeArrivalRadiusM(value: Float) = dataStore.edit { it[HOME_ARRIVAL_RADIUS_M] = value.coerceAtLeast(50f) }
    suspend fun setHomeMinTripDistanceM(value: Float) = dataStore.edit { it[HOME_MIN_TRIP_DISTANCE_M] = value.coerceAtLeast(0f) }
    suspend fun setHomeMinTripDurationS(value: Long) = dataStore.edit { it[HOME_MIN_TRIP_DURATION_S] = value.coerceAtLeast(0L) }
}
