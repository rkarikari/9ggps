// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.map

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nineggps.data.db.entity.TrackEntity
import com.nineggps.data.db.entity.WaypointEntity
import com.nineggps.data.db.entity.SpeedCameraEntity
import com.nineggps.data.model.*
import com.nineggps.data.prefs.UserPreferences
import com.nineggps.data.repository.NavigationRepository
import com.nineggps.data.repository.SpeedCameraRepository
import com.nineggps.data.repository.TrackRepository
import com.nineggps.service.GpsTrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trackRepository: TrackRepository,
    private val navigationRepository: NavigationRepository,
    private val userPreferences: UserPreferences,
    private val speedCameraRepository: SpeedCameraRepository
) : ViewModel() {

    // ─── Live GPS / Service State ──────────────────────────────────────────────

    val gpsState: StateFlow<GpsState> = GpsTrackingService.gpsState
    val recordingState: StateFlow<RecordingState> = GpsTrackingService.recordingState
    val trackStats: StateFlow<TrackStats> = GpsTrackingService.trackStats
    val navigationState: StateFlow<NavigationState> = GpsTrackingService.navigationState
    val compassBearing: StateFlow<Float> = GpsTrackingService.compassBearing
    val pressure: StateFlow<Float?> = GpsTrackingService.pressure

    // ─── Map Settings ─────────────────────────────────────────────────────────

    private val _mapSettings = MutableStateFlow(MapSettings())
    val mapSettings: StateFlow<MapSettings> = _mapSettings

    val speedUnit: Flow<String> = userPreferences.speedUnit
    val distanceUnit: Flow<String> = userPreferences.distanceUnit
    val showWeather: Flow<Boolean> = userPreferences.showWeather
    val mapOrientation: Flow<String> = userPreferences.mapOrientation

    // ─── Persisted map layer & zoom ───────────────────────────────────────────
    /** Flow of the last-selected tile-source id; restores across sessions. */
    val mapLayerId: Flow<String> = userPreferences.mapLayerId
    /** Flow of the last zoom level; restores across sessions. */
    val mapZoomLevel: Flow<Double> = userPreferences.mapZoomLevel
    val offlineMode: Flow<Boolean> = userPreferences.offlineMode

    /** Whether the home auto-record feature is enabled in Settings. */
    val homeAutoRecord: Flow<Boolean> = userPreferences.homeAutoRecord

    /**
     * True while the current recording was started automatically by the
     * home auto-record feature.  Drives the auto-record info panel on the
     * map screen so the user always knows a trip is being tracked on their
     * behalf, even when they didn't tap Record manually.
     */
    val autoRecordActive: StateFlow<Boolean> = GpsTrackingService.autoRecordActive


    // ─── UI State ─────────────────────────────────────────────────────────────

    private val _isFollowMode = MutableStateFlow(true)
    val isFollowMode: StateFlow<Boolean> = _isFollowMode

    private val _showSearchBar = MutableStateFlow(false)
    val showSearchBar: StateFlow<Boolean> = _showSearchBar

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching

    private val _weatherData = MutableStateFlow<WeatherData?>(null)
    val weatherData: StateFlow<WeatherData?> = _weatherData

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage

    private val _pendingRoute = MutableStateFlow<NavigationState?>(null)
    val pendingRoute: StateFlow<NavigationState?> = _pendingRoute

    private val _currentTrackId = MutableStateFlow<Long>(-1L)

    // Saved waypoints — used by the "navigate via waypoints" flow.
    // SharingStarted.Eagerly keeps the DB query live from ViewModel creation so
    // .value is always populated when showWaypointSelectionDialog reads it
    // synchronously. WhileSubscribed leaves it empty until a collector is active,
    // causing the dialog to fall back to a direct route on every tap.
    val savedWaypoints: StateFlow<List<WaypointEntity>> = trackRepository
        .getAllWaypoints()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ─── Speed Cameras ────────────────────────────────────────────────────────

    /** All locally-stored speed cameras — drives the map overlay. */
    val speedCameras: StateFlow<List<SpeedCameraEntity>> = speedCameraRepository
        .getAllCameras()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Whether the camera overlay is toggled on by the user. */
    val showSpeedCameras: StateFlow<Boolean> = userPreferences.showSpeedCameras
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    // ─── Search ───────────────────────────────────────────────────────────────

    private var searchJob: Job? = null

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            try {
                val loc = gpsState.value.location
                val result = navigationRepository.search(query, loc?.latitude, loc?.longitude)
                when (result) {
                    is com.nineggps.data.repository.Result.Success -> {
                        _searchResults.value = result.data
                    }
                    is com.nineggps.data.repository.Result.Error -> {
                        _errorMessage.value = result.message
                    }
                    else -> {}
                }
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
        _showSearchBar.value = false
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    fun navigateTo(destination: SearchResult) {
        viewModelScope.launch {
            val origin = gpsState.value.location ?: return@launch
            val profile = userPreferences.routingProfile.first()
            _snackbarMessage.value = "Calculating route..."

            val result = navigationRepository.getRouteWithAlternates(
                origin      = LatLngPoint(origin.latitude, origin.longitude),
                destination = LatLngPoint(destination.latitude, destination.longitude),
                profile     = profile
            )
            when (result) {
                is com.nineggps.data.repository.Result.Success -> {
                    _pendingRoute.value = result.data
                    _snackbarMessage.value = null
                }
                is com.nineggps.data.repository.Result.Error -> {
                    _errorMessage.value = result.message
                }
                else -> {}
            }
        }
    }

    /**
     * Navigate directly to a lat/lng point (e.g. a saved waypoint).
     * Converts the coordinates into a [SearchResult] and delegates to [navigateTo].
     * Called from WaypointsFragment when the user taps the navigate arrow on a saved waypoint.
     */
    fun navigateToPoint(name: String, lat: Double, lon: Double) {
        navigateTo(SearchResult(displayName = name, latitude = lat, longitude = lon))
    }

    /**
     * Like [navigateTo] but routes through [viaWaypoints] as ordered intermediate
     * stops before reaching [destination].  Waypoints are sent to OSRM in the
     * order they appear in the list, so the caller should present them in a
     * meaningful sequence (e.g. by distance from origin, or user-ordered).
     */
    fun navigateToViaWaypoints(destination: SearchResult, viaWaypoints: List<WaypointEntity>) {
        viewModelScope.launch {
            val origin = gpsState.value.location ?: return@launch
            val profile = userPreferences.routingProfile.first()
            val label = if (viaWaypoints.size == 1) "1 waypoint" else "${viaWaypoints.size} waypoints"
            _snackbarMessage.value = "Calculating route via $label..."

            val waypoints = viaWaypoints.map { LatLngPoint(it.latitude, it.longitude) }

            val result = navigationRepository.getRouteWithAlternates(
                origin      = LatLngPoint(origin.latitude, origin.longitude),
                destination = LatLngPoint(destination.latitude, destination.longitude),
                waypoints   = waypoints,
                profile     = profile
            )
            when (result) {
                is com.nineggps.data.repository.Result.Success -> {
                    _pendingRoute.value = result.data
                    _snackbarMessage.value = null
                }
                is com.nineggps.data.repository.Result.Error -> {
                    _errorMessage.value = result.message
                }
                else -> {}
            }
        }
    }

    fun selectAlternate(index: Int) {
        val current = _pendingRoute.value ?: return
        if (index !in current.alternateRoutes.indices) return
        val chosen  = current.alternateRoutes[index]
        val updated = current.alternateRoutes.mapIndexed { i, r -> r.copy(isActive = i == index) }
        _pendingRoute.value = current.copy(
            route                  = chosen.route,
            steps                  = chosen.steps,
            activeRouteIndex       = index,
            alternateRoutes        = updated,
            totalDistanceRemaining = chosen.totalDistance,
            estimatedTimeRemaining = chosen.effectiveDuration,
            currentInstruction     = chosen.steps.firstOrNull()?.instruction ?: "",
            nextInstruction        = chosen.steps.getOrNull(1)?.instruction ?: "Destination"
        )
    }

    fun startPendingNavigation() {
        val route = _pendingRoute.value ?: return
        viewModelScope.launch {
            // Write the route into the companion StateFlow BEFORE sending the Intent.
            // onStartCommand reads it, so navigation starts correctly even when the
            // binder connection is not yet established at the time the Intent arrives.
            GpsTrackingService.pendingNavigationState.value = route

            val serviceIntent = Intent(context, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_START_NAVIGATION
            }
            context.startForegroundService(serviceIntent)

            // Fast path: if the binder is already connected, apply the state
            // immediately via binder and clear the companion slot.
            getService()?.let { svc ->
                svc.startNavigation(route)
                GpsTrackingService.pendingNavigationState.value = null
            }

            _pendingRoute.value = null
            _snackbarMessage.value = "Navigation started"
        }
    }

    fun cancelPendingRoute() {
        _pendingRoute.value = null
    }

    /**
     * Switches the active route during live navigation without re-fetching
     * from OSRM.  Mirrors [selectAlternate] which only works on [pendingRoute].
     */
    fun switchActiveRoute(index: Int) {
        viewModelScope.launch {
            getService()?.switchAlternateRoute(index)
        }
    }

    fun stopNavigation() {
        val intent = Intent(context, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_STOP_NAVIGATION
        }
        context.startService(intent)
    }

    // ─── Track Recording ──────────────────────────────────────────────────────

    /**
     * Begins a new track recording session.
     *
     * @param activityTypeOverride When non-null this value is used as the track's
     *   activity type for this session only, without touching the persistent
     *   Settings preference.  When null the value from [UserPreferences.activityType]
     *   is used (the Settings default).
     */
    fun startRecording(activityTypeOverride: String? = null) {
        viewModelScope.launch {
            val activityType = activityTypeOverride ?: userPreferences.activityType.first()
            val trackId = trackRepository.insertTrack(
                TrackEntity(
                    name = "Track ${System.currentTimeMillis()}",
                    activityType = activityType,
                    startTime = System.currentTimeMillis()
                )
            )
            _currentTrackId.value = trackId
            // Deliver via binder when already connected (fast path).
            getService()?.setCurrentTrackId(trackId)

            val intent = Intent(context, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_START_TRACKING
                // Also embed the ID in the Intent so onStartCommand can set it
                // even when the binder connection has not yet been established.
                putExtra(GpsTrackingService.EXTRA_TRACK_ID, trackId)
            }
            context.startForegroundService(intent)
        }
    }

    fun pauseRecording() {
        val intent = Intent(context, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_PAUSE_TRACKING
        }
        context.startService(intent)
    }

    fun resumeRecording() {
        val intent = Intent(context, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_RESUME_TRACKING
        }
        context.startService(intent)
    }

    fun stopRecording() {
        val intent = Intent(context, GpsTrackingService::class.java).apply {
            action = GpsTrackingService.ACTION_STOP_TRACKING
        }
        context.startService(intent)
        _currentTrackId.value = -1L
    }

    // ─── Map Controls ─────────────────────────────────────────────────────────

    fun setFollowMode(enabled: Boolean) { _isFollowMode.value = enabled }
    fun toggleFollowMode() { _isFollowMode.value = !_isFollowMode.value }
    fun setShowSearchBar(show: Boolean) { _showSearchBar.value = show }

    // Survives fragment view destruction so the map can restore to the last
    // viewed position instead of flashing at (0, 0) and panning to the GPS fix.

    /** Last centre the user had on screen; null until the first view is created. */
    private var _savedMapCenterLat: Double? = null
    private var _savedMapCenterLon: Double? = null
    /** Last zoom level the user had on screen; initialised from DataStore so it survives process death. */
    private var _savedMapZoom: Double = 15.0

    init {
        // Seed the in-memory zoom from DataStore so a fresh process also restores
        // the previous zoom level, not just within-session navigation.
        viewModelScope.launch {
            _savedMapZoom = userPreferences.mapZoomLevel.first()
        }
    }

    val savedMapCenterLat: Double? get() = _savedMapCenterLat
    val savedMapCenterLon: Double? get() = _savedMapCenterLon
    val savedMapZoom: Double        get() = _savedMapZoom

    /** Called by MapFragment just before it destroys its view. */
    fun saveMapCamera(lat: Double, lon: Double, zoom: Double) {
        _savedMapCenterLat = lat
        _savedMapCenterLon = lon
        _savedMapZoom      = zoom
        // Also persist to DataStore so it survives process death.
        viewModelScope.launch { userPreferences.setMapZoomLevel(zoom) }
    }

    /** Persist the selected tile-source layer by its stable id. */
    fun saveMapLayer(layerId: String) {
        viewModelScope.launch { userPreferences.setMapLayerId(layerId) }
    }

    fun setMapType(type: MapType) {
        _mapSettings.update { it.copy(mapType = type) }
        viewModelScope.launch { userPreferences.setMapType(type.name) }
    }

    /** Persist the map orientation mode ("NORTH" or "DIRECTION"). */
    fun setMapOrientationMode(mode: String) {
        viewModelScope.launch { userPreferences.setMapOrientation(mode) }
    }


    // ─── Weather ──────────────────────────────────────────────────────────────

    private var weatherJob: Job? = null

    fun fetchWeather(lat: Double, lon: Double) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            val apiKey = userPreferences.openWeatherKey.first()
            val result = navigationRepository.getWeather(lat, lon, apiKey)
            if (result is com.nineggps.data.repository.Result.Success) {
                _weatherData.value = result.data
            }
        }
    }

    // ─── Waypoint Quick Add ───────────────────────────────────────────────────

    fun addWaypointAtCurrentLocation(name: String) {
        val loc = gpsState.value.location ?: return
        viewModelScope.launch {
            trackRepository.insertWaypoint(
                com.nineggps.data.db.entity.WaypointEntity(
                    name = name,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitude = loc.altitude
                )
            )
            _snackbarMessage.value = "Waypoint '$name' saved"
        }
    }

    /**
     * Save a waypoint at an explicit map coordinate (e.g. a long-press drop-pin).
     * Unlike [addWaypointAtCurrentLocation] this uses the supplied lat/lon rather
     * than the current GPS fix, so the saved location matches where the user tapped.
     */
    fun addWaypointAt(name: String, lat: Double, lon: Double, alt: Double = 0.0) {
        viewModelScope.launch {
            trackRepository.insertWaypoint(
                com.nineggps.data.db.entity.WaypointEntity(
                    name     = name,
                    latitude = lat,
                    longitude = lon,
                    altitude = alt
                )
            )
            _snackbarMessage.value = "Waypoint '$name' saved"
        }
    }

    // ─── Service Binding ──────────────────────────────────────────────────────

    private var boundService: GpsTrackingService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boundService = (binder as GpsTrackingService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            boundService = null
        }
    }

    fun bindService() {
        Intent(context, GpsTrackingService::class.java).also { intent ->
            context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbindService() {
        try { context.unbindService(serviceConnection) } catch (_: Exception) {}
    }

    private fun getService() = boundService

    fun clearError() { _errorMessage.value = null }
    fun clearSnackbar() { _snackbarMessage.value = null }

    /** Returns the currently saved activity-type preference value (e.g. "DRIVING"). */
    suspend fun currentActivityType(): String = userPreferences.activityType.first()

    /** Persists the offline-mode flag so MapFragment's observer applies it to the MapView. */
    fun setOfflineMode(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setOfflineMode(enabled) }
    }

    // ─── Start GPS Service ────────────────────────────────────────────────────

    fun startGpsService() {
        val intent = Intent(context, GpsTrackingService::class.java)
        context.startForegroundService(intent)
        bindService()
    }

    override fun onCleared() {
        unbindService()
        super.onCleared()
    }
}
