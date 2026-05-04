// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.service

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.hardware.*
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.nineggps.NineGApp
import com.nineggps.MainActivity
import com.nineggps.R
import com.nineggps.data.db.dao.TrackDao
import com.nineggps.data.db.dao.GeofenceDao
import com.nineggps.data.db.dao.SpeedCameraDao
import com.nineggps.data.db.entity.GeofenceEntity
import com.nineggps.data.db.entity.GeofenceEventEntity
import com.nineggps.data.db.entity.TrackPointEntity
import com.nineggps.data.model.*
import com.nineggps.data.prefs.UserPreferences
import com.nineggps.data.repository.NavigationRepository
import com.nineggps.data.repository.Result
import com.nineggps.routing.RouteManager
import com.nineggps.utils.NineGUtils
import com.nineggps.utils.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.Locale
import javax.inject.Inject
import kotlin.math.*

@AndroidEntryPoint
class GpsTrackingService : LifecycleService(), SensorEventListener {

    @Inject lateinit var fusedLocationClient: FusedLocationProviderClient
    @Inject lateinit var trackDao: TrackDao
    @Inject lateinit var userPreferences: UserPreferences
    @Inject lateinit var navigationRepository: NavigationRepository
    @Inject lateinit var geofenceDao: GeofenceDao
    @Inject lateinit var speedCameraDao: SpeedCameraDao

    // ─── State ────────────────────────────────────────────────────────────────

    private val _gpsState = MutableStateFlow(GpsState())
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    private val _trackStats = MutableStateFlow(TrackStats())
    private val _navigationState = MutableStateFlow(NavigationState())

    // ─── Hardware sensors ─────────────────────────────────────────────────────

    private var sensorManager: SensorManager? = null
    private var compassSensor: Sensor? = null
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var pressureSensor: Sensor? = null

    // ─── Internal state ───────────────────────────────────────────────────────

    private var currentTrackId: Long = -1L
    private var trackingStartTime: Long = 0L
    private var totalDistance: Double = 0.0
    private var lastLocation: Location? = null
    private var pointCount: Int = 0
    private var maxSpeed: Float = 0f
    private var totalSpeed: Float = 0f
    private var speedSamples: Int = 0
    private var minAlt: Double = Double.MAX_VALUE
    private var maxAlt: Double = -Double.MAX_VALUE
    private var elevationGain: Double = 0.0
    private var elevationLoss: Double = 0.0
    private var lastAlt: Double? = null

    /** Cached user weight (kg) — read from preferences at [startTracking]. */
    private var cachedWeightKg: Double = 75.0

    /** Cached activity type — read from preferences at [startTracking]. */
    private var cachedActivityType: String = "DRIVING"

    /**
     * Cached minimum accuracy threshold (metres) — read from preferences at
     * [startTracking].  Fixes with reported accuracy worse than this value are
     * discarded before any further processing to prevent noisy points from
     * degrading track quality.  Defaults to 30 m.
     */
    private var cachedMinAccuracyM: Float = 30f

    /**
     * The last location that was actually *persisted* to the database.
     * Kept separate from [lastLocation] (last *received* fix) so the spatial
     * deduplication filter measures distance from the last saved point rather
     * than from the last raw fix.  Reset to null at each [startTracking] and
     * [resumeTracking] so the first qualifying fix after a start/resume is
     * always saved.
     */
    private var lastSavedLocation: Location? = null

    // ─── Batch write buffer ───────────────────────────────────────────────────

    /**
     * In-memory queue of track points waiting to be flushed to the database.
     * Points are added by [enqueueTrackPoint] and written in a single
     * [TrackDao.insertTrackPoints] call by [flushPointBuffer].
     *
     * Flushing is triggered when:
     *   the buffer reaches [FLUSH_BATCH_SIZE] entries, or
     *   [FLUSH_INTERVAL_MS] have elapsed since the last flush, or
     *   [stopTracking] or [pauseTracking] is called (ensures no points are
     *   lost on stop or when the process might be killed while paused).
     *
     * Only ever accessed from [processLocation] which runs on the main-thread
     * looper; no additional synchronisation is needed.
     */
    private val pointBuffer = ArrayDeque<TrackPointEntity>()
    private var lastFlushTimeMs = 0L

    // ─── Stats rate-limiting ──────────────────────────────────────────────────

    /**
     * Timestamp of the last [updateTrackStats] call.  Stats are recomputed at
     * most once per [STATS_UPDATE_INTERVAL_MS] to avoid allocating a
     * [TrackStats] object and pushing two StateFlow updates on every GPS fix.
     */
    private var lastStatsUpdateTimeMs = 0L

    // ─── Geofence cache ───────────────────────────────────────────────────────

    /**
     * In-memory snapshot of active geofences, refreshed at most once every
     * [GEOFENCE_CACHE_TTL_MS].  Avoids a database round-trip on every GPS fix
     * inside [checkGeofences].
     */
    private var cachedGeofences: List<GeofenceEntity> = emptyList()
    private var lastGeofenceCacheTimeMs = 0L

    /**
     * In-memory map of geofence ID to whether the user was inside the
     * geofence on the previous GPS fix.  Used by the supplementary
     * [checkGeofences] implementation to detect enter/exit transitions that
     * the system GeofencingClient may debounce or delay.
     * null entry means the geofence has not yet been observed.
     */
    private val geofenceInsideState = mutableMapOf<Long, Boolean>()

    // ─── Home prefs cache ─────────────────────────────────────────────────────

    /**
     * Snapshot of the home auto-record preferences, refreshed at most once
     * every [HOME_PREFS_CACHE_TTL_MS] (30 s).  Replaces the 6+ DataStore
     * Flow.first() calls that were previously issued on every GPS fix inside
     * [checkHomeProximity].
     */
    private data class CachedHomePrefs(
        val enabled: Boolean,
        val homeSet: Boolean,
        val lat: Double,
        val lon: Double,
        val departureRadius: Float,
        val arrivalRadius: Float
    )
    private var cachedHomePrefs: CachedHomePrefs? = null
    private var lastHomePrefsCacheTimeMs = 0L

    private var currentBearing: Float = 0f
    private var currentPressure: Float? = null

    // Compass smoothing
    private val gravity = FloatArray(3)
    private val magnetic = FloatArray(3)
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var lastBearingUpdate = 0L

    private var tts: TextToSpeech? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var locationCallback: LocationCallback? = null
    private var notificationUpdateJob: Job? = null
    private var rerouteJob: Job? = null
    private var gnssCallback: GnssStatus.Callback? = null

    // ─── Home Auto-Record state ───────────────────────────────────────────────

    /**
     * Whether the device was last observed to be within the home arrival radius.
     * null = not yet determined (first fix after home prefs loaded).
     */
    private var wasAtHome: Boolean? = null

    /** Counter of consecutive slow-speed fixes seen while inside the arrival radius. */
    private var arrivalConfirmCount: Int = 0

    /** Whether the current recording was started automatically by home auto-record. */
    private var autoRecordTriggered: Boolean = false

    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {
        const val ACTION_START_TRACKING = "ACTION_START_TRACKING"
        const val ACTION_STOP_TRACKING = "ACTION_STOP_TRACKING"
        const val ACTION_PAUSE_TRACKING = "ACTION_PAUSE_TRACKING"
        const val ACTION_RESUME_TRACKING = "ACTION_RESUME_TRACKING"
        const val ACTION_START_NAVIGATION = "ACTION_START_NAVIGATION"
        const val ACTION_STOP_NAVIGATION = "ACTION_STOP_NAVIGATION"
        const val EXTRA_TRACK_ID = "EXTRA_TRACK_ID"

        /** Metres from a speed camera at which an alert is triggered. */
        const val SPEED_CAMERA_ALERT_RADIUS_M = 300.0

        /** Minimum milliseconds between successive speed-camera alerts. */
        const val SPEED_ALERT_COOLDOWN_MS = 30_000L

        // ─── Home Auto-Record constants ────────────────────────────────────────
        /** Speed threshold (km/h) below which we consider the device stationary for arrival detection. */
        private const val HOME_STATIONARY_SPEED_KMH = 8f

        /** How many consecutive low-speed fixes must be seen before confirming arrival. */
        private const val HOME_ARRIVAL_CONFIRM_FIXES = 5

        /** Minimum speed (km/h) to confirm a departure is intentional travel. */
        private const val HOME_DEPARTURE_SPEED_KMH = 5f

        // ─── Route recording optimisation constants ───────────────────────────

        /**
         * Batch-write buffer size.  A single TrackDao.insertTrackPoints() call
         * is issued when the buffer contains this many points.
         */
        private const val FLUSH_BATCH_SIZE = 10

        /**
         * Maximum interval (ms) between buffer flushes even when the buffer has
         * not yet reached [FLUSH_BATCH_SIZE].  Ensures low-activity recordings
         * (e.g. slow walking) are still persisted regularly.
         */
        private const val FLUSH_INTERVAL_MS = 10_000L

        /**
         * How often (ms) [updateTrackStats] is allowed to recompute and push
         * a new TrackStats value.  The UI only needs 2-second granularity
         * for distance/duration/speed displays.
         */
        private const val STATS_UPDATE_INTERVAL_MS = 2_000L

        /**
         * TTL (ms) for the in-memory active-geofence cache.  The geofence list
         * changes rarely; re-querying the database once per minute is sufficient
         * and far cheaper than a query on every GPS fix.
         */
        private const val GEOFENCE_CACHE_TTL_MS = 60_000L

        /**
         * TTL (ms) for the cached home auto-record preferences.  Home location
         * and radii are set manually and almost never change mid-trip.
         */
        private const val HOME_PREFS_CACHE_TTL_MS = 30_000L

        // ─── Spatial deduplication thresholds ────────────────────────────────

        /**
         * Minimum distance (m) between saved points at pedestrian / slow speeds
         * (at or below 20 km/h).
         */
        private const val MIN_DIST_WALKING_M = 3f

        /**
         * Minimum distance (m) between saved points in urban / city driving
         * (20 to 60 km/h).
         */
        private const val MIN_DIST_CITY_M = 8f

        /**
         * Minimum distance (m) between saved points at motorway speeds
         * (above 60 km/h).  20 m is approximately 0.7 s at 100 km/h — more
         * than sufficient to reconstruct a smooth polyline while cutting
         * storage by roughly 4x versus raw fixes.
         */
        private const val MIN_DIST_HIGHWAY_M = 20f

        /**
         * Bearing-change threshold (degrees) below which a fix is considered
         * collinear with the previous saved fix.  When the bearing delta meets
         * or exceeds this value AND the device has moved at least 2 m, the
         * point is saved regardless of the distance filter so that turns are
         * captured with full resolution.
         */
        private const val BEARING_CHANGE_THRESHOLD_DEG = 5f

        // Binder key for accessing service state flows
        val gpsState = MutableStateFlow(GpsState())
        val recordingState = MutableStateFlow(RecordingState.IDLE)
        val trackStats = MutableStateFlow(TrackStats())
        val navigationState = MutableStateFlow(NavigationState())
        val compassBearing = MutableStateFlow(0f)
        val pressure = MutableStateFlow<Float?>(null)
        val satelliteState = MutableStateFlow(SatelliteState())

        /**
         * True while a trip recording was started automatically by the home
         * auto-record feature.  Observed by MapFragment to drive the
         * auto-record info panel on the map screen.
         */
        val autoRecordActive = MutableStateFlow(false)

        /**
         * Holds a [NavigationState] set by the ViewModel just before sending
         * [ACTION_START_NAVIGATION].  Read in [onStartCommand] so navigation
         * starts correctly even when the binder connection has not yet been
         * established (avoids the race between bindService and startForegroundService).
         * Cleared immediately after consumption.
         */
        val pendingNavigationState = MutableStateFlow<NavigationState?>(null)
    }

    // ─── Binder ───────────────────────────────────────────────────────────────

    inner class LocalBinder : Binder() {
        fun getService() = this@GpsTrackingService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent) = binder

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        initSensors()
        initTts()
        initVibrator()
        initWakeLock()
        startForeground(NineGApp.NOTIFICATION_ID_TRACKING, buildTrackingNotification())
        startLocationUpdates()
        startGnssStatusUpdates()
        startNotificationUpdateLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_TRACKING  -> {
                // Fix: read the track ID from the Intent so recording works even
                // when the binder connection has not yet been established.
                val trackId = intent.getLongExtra(EXTRA_TRACK_ID, -1L)
                if (trackId > 0L) currentTrackId = trackId
                startTracking()
            }
            ACTION_STOP_TRACKING   -> stopTracking()
            ACTION_PAUSE_TRACKING  -> pauseTracking()
            ACTION_RESUME_TRACKING -> resumeTracking()
            ACTION_START_NAVIGATION -> {
                // Fix: consume the pending state written by MapViewModel before the
                // Intent was dispatched; binder may not be connected yet.
                pendingNavigationState.value?.let { state ->
                    startNavigation(state)
                    pendingNavigationState.value = null
                }
            }
            ACTION_STOP_NAVIGATION -> stopNavigation()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopGnssStatusUpdates()
        sensorManager?.unregisterListener(this)
        tts?.shutdown()
        notificationUpdateJob?.cancel()
        rerouteJob?.cancel()
        wakeLock?.release()
        super.onDestroy()
    }

    // ─── Initialization ───────────────────────────────────────────────────────

    private fun initSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        compassSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        pressureSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

        val delay = SensorManager.SENSOR_DELAY_UI
        accelerometer?.let { sensorManager?.registerListener(this, it, delay) }
        compassSensor?.let { sensorManager?.registerListener(this, it, delay) }
        pressureSensor?.let { sensorManager?.registerListener(this, it, delay) }
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun initWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NineGGPS::TrackingWakeLock"
        )
    }

    // ─── Location Updates ─────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val (interval, batterySaver) = runBlocking {
            Pair(
                userPreferences.gpsUpdateInterval.first(),
                userPreferences.batterySaver.first()
            )
        }

        // Battery-saver mode keeps PRIORITY_HIGH_ACCURACY so the GNSS chip
        // remains active regardless of WiFi or cell coverage — critical for
        // trip recording on rural roads and motorways where network positioning
        // is unavailable.  Power savings come from a longer update interval
        // (2x the user preference) and a 10 m minimum-distance filter that
        // suppresses redundant stationary fixes without touching chip state.
        // PRIORITY_BALANCED_POWER_ACCURACY is intentionally avoided: it hands
        // control to the fused provider's WiFi/cell hierarchy, which degrades
        // to ~100-500 m accuracy and unreliable delivery when off-network.
        val effectiveInterval    = if (batterySaver) interval * 2 else interval
        val effectiveMinDistance = if (batterySaver) 10f else 0f

        val request = LocationRequest.Builder(effectiveInterval)
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(effectiveInterval / 2)
            .setMinUpdateDistanceMeters(effectiveMinDistance)
            .setMaxUpdateDelayMillis(effectiveInterval * 2)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { processLocation(it) }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                // Do not derive isFixed from LocationAvailability — it reflects the
                // fused-provider's internal state and fires false transiently even during
                // a solid GNSS lock.  Fix state is managed via processLocation() timeout.
            }
        }

        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback!!,
            Looper.getMainLooper()
        )
    }

    private fun stopLocationUpdates() {
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
    }

    // ─── GNSS Satellite Status ────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun startGnssStatusUpdates() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val sats = mutableListOf<SatelliteInfo>()
                for (i in 0 until status.satelliteCount) {
                    val cn0 = status.getCn0DbHz(i)
                    val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        status.hasCarrierFrequencyHz(i))
                        status.getCarrierFrequencyHz(i) else 0f
                    sats += SatelliteInfo(
                        svid               = status.getSvid(i),
                        constellation      = constellationName(status.getConstellationType(i)),
                        constellationCode  = status.getConstellationType(i),
                        cn0DbHz            = cn0,
                        elevationDegrees   = status.getElevationDegrees(i),
                        azimuthDegrees     = status.getAzimuthDegrees(i),
                        hasAlmanac         = status.hasAlmanacData(i),
                        hasEphemeris       = status.hasEphemerisData(i),
                        usedInFix          = status.usedInFix(i),
                        carrierFrequencyHz = freq
                    )
                }
                val used = sats.count { it.usedInFix }
                val avg  = if (sats.isNotEmpty()) sats.map { it.cn0DbHz }.average().toFloat() else 0f
                val max  = sats.maxOfOrNull { it.cn0DbHz } ?: 0f
                satelliteState.value = SatelliteState(
                    satellites      = sats.sortedByDescending { it.cn0DbHz },
                    totalCount      = sats.size,
                    usedInFixCount  = used,
                    avgCn0          = avg,
                    maxCn0          = max
                )
                // Derive fix status from satellite data — this is the authoritative source.
                // isFixed = true when 4+ satellites are used in fix (minimum for 3-D position).
                val hasfix = used >= 4
                _gpsState.update { it.copy(satellites = sats.size, isFixed = hasfix) }
                gpsState.update  { it.copy(satellites = sats.size, isFixed = hasfix) }
            }
        }
        lm.registerGnssStatusCallback(gnssCallback!!, Handler(Looper.getMainLooper()))
    }

    private fun stopGnssStatusUpdates() {
        gnssCallback?.let {
            val lm = getSystemService(LOCATION_SERVICE) as LocationManager
            lm.unregisterGnssStatusCallback(it)
        }
        gnssCallback = null
    }

    private fun constellationName(type: Int): String = when (type) {
        GnssStatus.CONSTELLATION_GPS     -> "GPS"
        GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
        GnssStatus.CONSTELLATION_BEIDOU  -> "BeiDou"
        GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
        GnssStatus.CONSTELLATION_QZSS    -> "QZSS"
        GnssStatus.CONSTELLATION_SBAS    -> "SBAS"
        GnssStatus.CONSTELLATION_IRNSS   -> "NavIC"
        else                             -> "Unknown"
    }

    private fun processLocation(location: Location) {
        val speedMs = location.speed
        val speedKmh = speedMs * 3.6f
        val alt = location.altitude

        // Update max speed
        if (speedKmh > maxSpeed) maxSpeed = speedKmh

        // Speed samples for average
        if (speedKmh > 0.5f) {
            totalSpeed += speedKmh
            speedSamples++
        }

        // Altitude tracking
        if (alt < minAlt) minAlt = alt
        if (alt > maxAlt) maxAlt = alt
        lastAlt?.let { prev ->
            val diff = alt - prev
            if (diff > 0.5) elevationGain += diff
            else if (diff < -0.5) elevationLoss += abs(diff)
        }
        lastAlt = alt

        // Distance calculation
        if (lastLocation != null && _recordingState.value == RecordingState.RECORDING) {
            val dist = lastLocation!!.distanceTo(location).toDouble()
            if (dist < 500) { // Filter GPS jumps > 500m
                totalDistance += dist
            }
        }

        val newState = GpsState(
            location = location,
            speed = speedMs,
            speedKmh = speedKmh,
            bearing = location.bearing,
            altitude = alt,
            accuracy = location.accuracy,
            isFixed = true,
            provider = location.provider ?: "gps"
        )

        _gpsState.value = newState
        gpsState.value = newState

        // Update stats and record point if active — stats are rate-limited to
        // avoid a new TrackStats allocation and two StateFlow pushes every fix.
        if (_recordingState.value == RecordingState.RECORDING) {
            val now = System.currentTimeMillis()
            if (now - lastStatsUpdateTimeMs >= STATS_UPDATE_INTERVAL_MS) {
                lastStatsUpdateTimeMs = now
                updateTrackStats()
            }
            saveTrackPoint(location)
        }

        // Check geofences
        checkGeofences(location)

        // Check speed alerts
        checkSpeedAlert(speedKmh)

        // Check home proximity for auto-record
        checkHomeProximity(location, speedKmh)

        // Update navigation
        updateNavigation(location)

        lastLocation = location
    }

    // ─── Track Recording ──────────────────────────────────────────────────────

    private fun startTracking() {
        lifecycleScope.launch {
            trackingStartTime = System.currentTimeMillis()
            totalDistance = 0.0
            pointCount = 0
            maxSpeed = 0f
            totalSpeed = 0f
            speedSamples = 0
            minAlt = Double.MAX_VALUE
            maxAlt = -Double.MAX_VALUE
            elevationGain = 0.0
            elevationLoss = 0.0
            lastAlt = null
            lastLocation = null
            lastSavedLocation = null
            pointBuffer.clear()
            lastFlushTimeMs = System.currentTimeMillis()
            lastStatsUpdateTimeMs = 0L

            // Cache preferences so hot-path callbacks never need to suspend.
            cachedWeightKg     = userPreferences.weightKg.first().toDouble()
            cachedActivityType = userPreferences.activityType.first()
            cachedMinAccuracyM = userPreferences.recordingMinAccuracy.first()

            _recordingState.value = RecordingState.RECORDING
            recordingState.value = RecordingState.RECORDING

            wakeLock?.acquire(12 * 60 * 60 * 1000L) // 12 hours max

            vibrate(longArrayOf(0, 100, 50, 100))
            speakText("Track recording started")
            updateNotification()
        }
    }

    private fun stopTracking() {
        lifecycleScope.launch {
            // Flush any buffered points before finalising so the persisted
            // point count matches the in-memory counter.
            flushPointBuffer()

            if (currentTrackId > 0) {
                val duration = System.currentTimeMillis() - trackingStartTime
                val avgSpeed = if (speedSamples > 0) totalSpeed / speedSamples else 0f
                trackDao.updateTrack(
                    trackDao.getTrackById(currentTrackId)!!.copy(
                        endTime = System.currentTimeMillis(),
                        distance = totalDistance,
                        duration = duration,
                        avgSpeed = avgSpeed,
                        maxSpeed = maxSpeed,
                        minAltitude = if (minAlt == Double.MAX_VALUE) 0.0 else minAlt,
                        maxAltitude = if (maxAlt == -Double.MAX_VALUE) 0.0 else maxAlt,
                        elevationGain = elevationGain,
                        elevationLoss = elevationLoss,
                        pointCount = pointCount
                    )
                )
            }

            _recordingState.value = RecordingState.IDLE
            recordingState.value = RecordingState.IDLE
            currentTrackId = -1L
            wakeLock?.release()
            vibrate(longArrayOf(0, 200, 100, 200))
            speakText("Track recording stopped")
            updateNotification()
        }
    }

    private fun pauseTracking() {
        // Flush the buffer so no points are lost if the process is killed while paused.
        flushPointBuffer()
        _recordingState.value = RecordingState.PAUSED
        recordingState.value = RecordingState.PAUSED
        vibrate(longArrayOf(0, 100))
        updateNotification()
    }

    private fun resumeTracking() {
        _recordingState.value = RecordingState.RECORDING
        recordingState.value = RecordingState.RECORDING
        // Reset both raw and saved location references to prevent a distance
        // or bearing-change spike from the pause gap.
        lastLocation = null
        lastSavedLocation = null
        vibrate(longArrayOf(0, 100, 50, 100))
        updateNotification()
    }

    /**
     * Decides whether [location] should be persisted to the database.
     *
     * A fix is accepted when all of the following hold:
     *   1. Reported accuracy is at or within [cachedMinAccuracyM] (noise gate).
     *   2. One of:
     *      a. No previous saved fix exists (first point of the track / after resume).
     *      b. Distance from [lastSavedLocation] is at or above the speed-adaptive minimum.
     *      c. The bearing has changed by at least [BEARING_CHANGE_THRESHOLD_DEG] AND
     *         the device has moved at least 2 m — turn capture at full resolution.
     *
     * The speed-adaptive distance thresholds ([MIN_DIST_WALKING_M],
     * [MIN_DIST_CITY_M], [MIN_DIST_HIGHWAY_M]) ensure that collinear points on
     * straight segments are deduplicated while corners and direction changes are
     * stored at native resolution, preserving track accuracy regardless of speed.
     */
    private fun shouldSavePoint(location: Location): Boolean {
        // 1. Accuracy gate — reject noisy fixes before any geometry check.
        if (location.accuracy > cachedMinAccuracyM) return false

        val prev = lastSavedLocation ?: return true   // always save the first qualifying point

        val dist = prev.distanceTo(location)

        // 2c. Bearing-change override — capture turns at full resolution.
        //     Guard dist >= 2 m to ignore compass jitter on stationary fixes.
        val bearingDelta = abs((location.bearing - prev.bearing + 540f) % 360f - 180f)
        if (bearingDelta >= BEARING_CHANGE_THRESHOLD_DEG && dist >= 2f) return true

        // 2b. Distance threshold — speed-adaptive deduplication on straight segments.
        return dist >= adaptiveMinDistanceM(location.speed * 3.6f)
    }

    /**
     * Returns the minimum inter-point distance for the given speed.  Used to
     * deduplicate collinear fixes on straight segments without affecting turn
     * resolution, which is handled separately by the bearing-change check in
     * [shouldSavePoint].
     */
    private fun adaptiveMinDistanceM(speedKmh: Float): Float = when {
        speedKmh <= 20f -> MIN_DIST_WALKING_M
        speedKmh <= 60f -> MIN_DIST_CITY_M
        else            -> MIN_DIST_HIGHWAY_M
    }

    /**
     * Applies the spatial deduplication and accuracy gate via [shouldSavePoint],
     * then adds the qualifying point to [pointBuffer] and triggers a flush when
     * the buffer is full or [FLUSH_INTERVAL_MS] has elapsed.
     *
     * [pointCount] is incremented here (on the main thread, before any async
     * work) so it always reflects the number of points that will be stored,
     * even before the next flush completes.
     *
     * [lastSavedLocation] is updated before enqueue so the next call to
     * [shouldSavePoint] correctly measures distance from this newly accepted fix.
     */
    private fun saveTrackPoint(location: Location) {
        if (currentTrackId < 0) return
        if (!shouldSavePoint(location)) return

        val point = TrackPointEntity(
            trackId   = currentTrackId,
            latitude  = location.latitude,
            longitude = location.longitude,
            altitude  = location.altitude,
            speed     = location.speed,
            bearing   = location.bearing,
            accuracy  = location.accuracy,
            timestamp = location.time
        )

        lastSavedLocation = location
        pointCount++
        enqueueTrackPoint(point)
    }

    /**
     * Adds [point] to [pointBuffer] and flushes to the database when the batch
     * size or elapsed-time threshold is reached.
     */
    private fun enqueueTrackPoint(point: TrackPointEntity) {
        pointBuffer.addLast(point)
        val now = System.currentTimeMillis()
        if (pointBuffer.size >= FLUSH_BATCH_SIZE || now - lastFlushTimeMs >= FLUSH_INTERVAL_MS) {
            flushPointBuffer()
        }
    }

    /**
     * Drains [pointBuffer] to the database in a single batch insert.
     * Dispatched to [Dispatchers.IO]; safe to call from the main thread.
     * No-op when the buffer is empty.
     */
    private fun flushPointBuffer() {
        if (pointBuffer.isEmpty()) return
        val batch = ArrayList(pointBuffer)
        pointBuffer.clear()
        lastFlushTimeMs = System.currentTimeMillis()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                trackDao.insertTrackPoints(batch)
            } catch (_: Exception) {
                // Non-fatal: a missed flush risks losing a small batch of
                // points but must never crash the foreground service.
            }
        }
    }

    fun setCurrentTrackId(id: Long) { currentTrackId = id }

    private fun updateTrackStats() {
        val duration = System.currentTimeMillis() - trackingStartTime
        val avgSpeed = if (speedSamples > 0) totalSpeed / speedSamples else 0f
        val calories = NineGUtils.estimateCalories(
            distanceMeters = totalDistance,
            durationMs = duration,
            weightKg = cachedWeightKg,
            activityType = cachedActivityType
        )

        val stats = TrackStats(
            distance = totalDistance,
            duration = duration,
            avgSpeed = avgSpeed,
            maxSpeed = maxSpeed,
            minAltitude = if (minAlt == Double.MAX_VALUE) 0.0 else minAlt,
            maxAltitude = if (maxAlt == -Double.MAX_VALUE) 0.0 else maxAlt,
            elevationGain = elevationGain,
            elevationLoss = elevationLoss,
            calories = calories,
            pointCount = pointCount
        )

        _trackStats.value = stats
        trackStats.value = stats
    }

    // ─── Navigation ───────────────────────────────────────────────────────────

    /**
     * Called on every GPS fix while navigation is active.
     *
     * Uses [RouteManager.computeRouteUpdate] to:
     *  - advance the step index when the driver passes a turn,
     *  - compute remaining distance / ETA,
     *  - detect off-route deviation,
     *  - detect when a traffic-triggered reroute should fire.
     */
    private fun updateNavigation(location: Location) {
        val navState = _navigationState.value
        if (!navState.isNavigating || navState.steps.isEmpty()) return

        // Skip updates while a reroute fetch is already in flight.
        if (navState.isRerouting) return

        val currentStep = navState.steps.getOrNull(navState.currentStepIndex) ?: return

        val update = RouteManager.computeRouteUpdate(
            location  = location,
            state     = navState,
            speedKmh  = _gpsState.value.speedKmh
        )

        // ── Arrival ───────────────────────────────────────────────────────────
        if (update.arrived) {
            stopNavigation()
            speakText("You have arrived at your destination")
            return
        }

        // ── Voice announcements ───────────────────────────────────────────────
        val distToTurn = update.distToTurn
        if (distToTurn < 200 && distToTurn > 150) {
            speakText("In 200 meters, ${currentStep.instruction}")
        } else if (distToTurn < 50 && distToTurn > 30) {
            speakText(currentStep.instruction)
        }

        // ── Step advancement ──────────────────────────────────────────────────
        val stepAdvanced = update.nextStepIndex != navState.currentStepIndex

        // ── Off-route → reroute ───────────────────────────────────────────────
        if (update.isOffRoute && !navState.isRerouting) {
            // Reflect isOffRoute=true in the state BEFORE launching the reroute
            // so the UI off-route indicator is shown immediately.  triggerReroute
            // will set isRerouting=true in the same update cycle.
            _navigationState.update { it.copy(isOffRoute = true) }
            navigationState.update { _navigationState.value }
            triggerReroute(location, RerouteReason.OFF_ROUTE)
            return
        }

        // ── Traffic → reroute ─────────────────────────────────────────────────
        if (update.triggerTrafficReroute && !navState.isRerouting) {
            triggerReroute(location, RerouteReason.TRAFFIC)
            return
        }

        // ── Normal state update ───────────────────────────────────────────────
        val nextStep = navState.steps.getOrNull(update.nextStepIndex)
        val afterNext = navState.steps.getOrNull(update.nextStepIndex + 1)

        _navigationState.update { state ->
            state.copy(
                currentStepIndex       = update.nextStepIndex,
                distanceToNextTurn     = update.distToTurn,
                totalDistanceRemaining = update.remaining,
                estimatedTimeRemaining = update.timeRemaining,
                currentInstruction     = nextStep?.instruction ?: state.currentInstruction,
                nextInstruction        = afterNext?.instruction ?: "Destination",
                isOffRoute             = false
            )
        }
        navigationState.update { _navigationState.value }

        if (stepAdvanced) {
            nextStep?.let { speakText(it.instruction) }
        }
    }

    /**
     * Launches a background reroute fetch.  While in flight the nav state is
     * marked NavigationState.isRerouting = true so duplicate triggers are
     * suppressed.
     */
    private fun triggerReroute(location: Location, reason: RerouteReason) {
        val destination = _navigationState.value.destination ?: return
        val profile = _navigationState.value.let {
            // Infer the profile from the step modes; fall back to "car".
            "car"
        }

        // Mark rerouting in progress immediately so the UI can show a spinner.
        _navigationState.update { it.copy(isRerouting = true, rerouteReason = reason) }
        navigationState.update { _navigationState.value }

        rerouteJob?.cancel()
        rerouteJob = lifecycleScope.launch(Dispatchers.IO) {
            val origin = LatLngPoint(location.latitude, location.longitude)
            // Pass the stored intermediate waypoints so the rerouted path
            // still visits the same stops the user originally requested.
            val viaWaypoints = _navigationState.value.viaWaypoints
            when (val result = navigationRepository.getRouteWithAlternates(
                origin       = origin,
                destination  = destination,
                waypoints    = viaWaypoints,
                profile      = profile
            )) {
                is Result.Success -> {
                    val newState = RouteManager.applyReroute(
                        state          = _navigationState.value,
                        alternates     = result.data.alternateRoutes,
                        newActiveIndex = RouteManager.bestRouteIndex(result.data.alternateRoutes),
                        reason         = reason
                    )
                    _navigationState.value = newState
                    navigationState.value  = newState
                    val reasonLabel = if (reason == RerouteReason.TRAFFIC) "due to traffic" else ""
                    speakText("Rerouting $reasonLabel. ${newState.currentInstruction}")
                }
                is Result.Error -> {
                    // Reroute failed — clear the flag so it can retry next cycle.
                    _navigationState.update { it.copy(isRerouting = false) }
                    navigationState.update { _navigationState.value }
                }
                else -> {}
            }
        }
    }

    fun startNavigation(state: NavigationState) {
        _navigationState.value = state
        navigationState.value  = state
        speakText("Navigation started. ${state.currentInstruction}")
        updateNotification()
    }

    /**
     * Switches the active route to [alternateIndex] without re-fetching from
     * OSRM.  Called from the UI when the user taps an alternate route chip.
     */
    fun switchAlternateRoute(alternateIndex: Int) {
        val newState = RouteManager.switchToAlternate(_navigationState.value, alternateIndex)
        _navigationState.value = newState
        navigationState.value  = newState
        val chosen = newState.alternateRoutes.getOrNull(alternateIndex)
        speakText("Switching to ${chosen?.label ?: "alternate route"}. ${newState.currentInstruction}")
    }

    private fun stopNavigation() {
        rerouteJob?.cancel()
        _navigationState.value = NavigationState()
        navigationState.value  = NavigationState()
        updateNotification()
    }

    // ─── Geofences ────────────────────────────────────────────────────────────

    /**
     * Supplementary fine-grained geofence checking on every GPS fix.
     *
     * Google's GeofencingClient debounces transitions and may delay them by
     * several minutes.  This secondary check detects enter/exit transitions
     * immediately by comparing the GPS distance to each active geofence radius.
     * Active geofences are cached in [cachedGeofences] and refreshed at most
     * once per [GEOFENCE_CACHE_TTL_MS] (60 s) to eliminate the previous
     * per-fix database round-trip.
     *
     * State is kept in [geofenceInsideState]: a null entry means the geofence
     * has not yet been observed and no notification is sent on first sight (to
     * avoid spurious "entered" alerts when the service starts while already
     * inside a fence).
     */
    private fun checkGeofences(location: Location) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Refresh cache when expired.
                val now = System.currentTimeMillis()
                if (now - lastGeofenceCacheTimeMs > GEOFENCE_CACHE_TTL_MS) {
                    cachedGeofences = geofenceDao.getActiveGeofencesList()
                    lastGeofenceCacheTimeMs = now
                }

                cachedGeofences.forEach { geo ->
                    val distResult = FloatArray(1)
                    Location.distanceBetween(
                        location.latitude, location.longitude,
                        geo.latitude, geo.longitude, distResult
                    )
                    val inside    = distResult[0] <= geo.radius
                    val wasInside = geofenceInsideState[geo.id]

                    // First observation — record state only; do not fire.
                    if (wasInside == null) {
                        geofenceInsideState[geo.id] = inside
                        return@forEach
                    }

                    when {
                        inside && !wasInside && geo.triggerOnEnter -> {
                            geofenceInsideState[geo.id] = true
                            geofenceDao.incrementEnterCount(geo.id, System.currentTimeMillis())
                            geofenceDao.insertGeofenceEvent(
                                GeofenceEventEntity(
                                    geofenceId = geo.id,
                                    eventType  = "ENTER",
                                    latitude   = location.latitude,
                                    longitude  = location.longitude
                                )
                            )
                            withContext(Dispatchers.Main) {
                                speakText("Entering ${geo.name}")
                            }
                        }
                        !inside && wasInside && geo.triggerOnExit -> {
                            geofenceInsideState[geo.id] = false
                            geofenceDao.incrementExitCount(geo.id, System.currentTimeMillis())
                            geofenceDao.insertGeofenceEvent(
                                GeofenceEventEntity(
                                    geofenceId = geo.id,
                                    eventType  = "EXIT",
                                    latitude   = location.latitude,
                                    longitude  = location.longitude
                                )
                            )
                            withContext(Dispatchers.Main) {
                                speakText("Leaving ${geo.name}")
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Supplementary check — never crash the service on a DB error.
            }
        }
    }

    // ─── Home Auto-Record ─────────────────────────────────────────────────────

    /**
     * Evaluates every GPS fix against the saved home location and triggers
     * automatic recording start / stop when the feature is enabled.
     *
     * Home preferences are loaded into [cachedHomePrefs] and refreshed at most
     * once every [HOME_PREFS_CACHE_TTL_MS] (30 s), replacing the previous six
     * DataStore Flow.first() suspensions issued on every GPS fix.
     *
     * State machine
     * AT HOME to DEPARTED: distance > departureRadius AND speed > threshold
     *   => auto-start recording, set [autoRecordTriggered] = true.
     * AWAY to ARRIVED: distance < arrivalRadius AND speed low for
     *   [HOME_ARRIVAL_CONFIRM_FIXES] consecutive fixes
     *   => auto-stop recording; keep track only when it meets the minimum
     *     distance + duration thresholds, otherwise delete it silently.
     */
    private fun checkHomeProximity(location: Location, speedKmh: Float) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Refresh home prefs cache when expired.
                val now = System.currentTimeMillis()
                val prefs = if (cachedHomePrefs == null ||
                    now - lastHomePrefsCacheTimeMs > HOME_PREFS_CACHE_TTL_MS
                ) {
                    CachedHomePrefs(
                        enabled         = userPreferences.homeAutoRecord.first(),
                        homeSet         = userPreferences.homeIsSet.first(),
                        lat             = userPreferences.homeLat.first(),
                        lon             = userPreferences.homeLon.first(),
                        departureRadius = userPreferences.homeDepartureRadiusM.first(),
                        arrivalRadius   = userPreferences.homeArrivalRadiusM.first()
                    ).also {
                        cachedHomePrefs = it
                        lastHomePrefsCacheTimeMs = now
                    }
                } else {
                    cachedHomePrefs!!
                }

                if (!prefs.enabled || !prefs.homeSet) return@launch

                val distResults = FloatArray(1)
                Location.distanceBetween(
                    location.latitude, location.longitude,
                    prefs.lat, prefs.lon,
                    distResults
                )
                val distFromHome = distResults[0]
                val atHome = distFromHome <= prefs.arrivalRadius

                val prevAtHome = wasAtHome

                // ── First fix: just record baseline, no trigger ────────────────
                if (prevAtHome == null) {
                    wasAtHome = atHome
                    if (!atHome) arrivalConfirmCount = 0
                    return@launch
                }

                val recording = _recordingState.value

                when {
                    // ── Departure: was at home, now outside departure radius ────
                    prevAtHome && distFromHome > prefs.departureRadius
                            && speedKmh >= HOME_DEPARTURE_SPEED_KMH
                            && recording == RecordingState.IDLE -> {
                        wasAtHome = false
                        arrivalConfirmCount = 0
                        withContext(Dispatchers.Main) {
                            autoStartRecording()
                        }
                    }

                    // ── Arrival confirmation: inside arrival radius, slow speed ─
                    !prevAtHome && atHome
                            && speedKmh < HOME_STATIONARY_SPEED_KMH
                            && (recording == RecordingState.RECORDING || recording == RecordingState.PAUSED)
                            && autoRecordTriggered -> {
                        arrivalConfirmCount++
                        if (arrivalConfirmCount >= HOME_ARRIVAL_CONFIRM_FIXES) {
                            wasAtHome = true
                            arrivalConfirmCount = 0
                            withContext(Dispatchers.Main) {
                                autoStopRecording()
                            }
                        }
                    }

                    // ── Reset arrival counter if device moves away again ────────
                    !atHome || speedKmh >= HOME_STATIONARY_SPEED_KMH -> {
                        if (!atHome) wasAtHome = false
                        arrivalConfirmCount = 0
                    }
                }
            } catch (_: Exception) {
                // Auto-record must never crash the service.
            }
        }
    }

    /**
     * Creates a new TrackEntity in the database and starts recording.
     * Must be called on the main thread (mirrors the MapViewModel flow).
     */
    private fun autoStartRecording() {
        lifecycleScope.launch {
            try {
                val activityType = userPreferences.activityType.first()
                val trackId = trackDao.insertTrack(
                    com.nineggps.data.db.entity.TrackEntity(
                        name = "Auto Trip ${
                            java.text.SimpleDateFormat("dd MMM HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date())
                        }",
                        activityType = activityType,
                        startTime = System.currentTimeMillis()
                    )
                )
                currentTrackId = trackId
                autoRecordTriggered = true
                autoRecordActive.value = true
                startTracking()
                speakText("Auto-record started. Have a safe trip.")
            } catch (_: Exception) {}
        }
    }

    /**
     * Stops the auto-triggered recording and either keeps or discards the track
     * based on the configured minimum distance and duration thresholds.
     */
    private fun autoStopRecording() {
        lifecycleScope.launch {
            try {
                val minDistM  = userPreferences.homeMinTripDistanceM.first().toDouble()
                val minDurS   = userPreferences.homeMinTripDurationS.first()
                val durationS = (System.currentTimeMillis() - trackingStartTime) / 1000L

                val keep = totalDistance >= minDistM && durationS >= minDurS

                if (keep) {
                    stopTracking()
                    autoRecordActive.value = false
                    speakText("Welcome home. Trip saved.")
                } else {
                    // Flush before deleting so no in-flight IO jobs reference a
                    // track that is about to be removed.
                    flushPointBuffer()
                    _recordingState.value = RecordingState.IDLE
                    recordingState.value  = RecordingState.IDLE
                    wakeLock?.release()
                    if (currentTrackId > 0) {
                        trackDao.deleteTrackById(currentTrackId)
                    }
                    currentTrackId = -1L
                    autoRecordActive.value = false
                    speakText("Welcome home. Short trip discarded.")
                }
                autoRecordTriggered = false
            } catch (_: Exception) {
                autoRecordActive.value = false
                autoRecordTriggered = false
            }
        }
    }

    // ─── Speed Alerts ─────────────────────────────────────────────────────────

    private var lastSpeedAlertTime = 0L

    /**
     * Checks whether the user is approaching a speed camera with a known limit
     * that they are already exceeding.
     *
     * Implementation
     * 1. Throttled to at most one alert per 30 seconds to prevent flooding.
     * 2. Queries SpeedCameraDao for cameras within a ±0.003 degree bounding box
     *    (~300 m at the equator; fast index-backed query).
     * 3. Filters to cameras actually within [SPEED_CAMERA_ALERT_RADIUS_M] and
     *    whose posted limit is exceeded by the current speed.
     * 4. On a match: fires a high-priority notification via NotificationHelper,
     *    triggers a double-vibration pattern, and speaks a TTS alert.
     *
     * All DB and preference reads happen on Dispatchers.IO; UI/TTS work is
     * dispatched back to Dispatchers.Main.
     */
    private fun checkSpeedAlert(speedKmh: Float) {
        val now = System.currentTimeMillis()
        if (now - lastSpeedAlertTime < SPEED_ALERT_COOLDOWN_MS) return

        val loc = _gpsState.value.location ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val enabled = userPreferences.speedLimitAlerts.first()
                if (!enabled) return@launch

                // Bounding-box pre-filter: ±0.003° ≈ ±300 m
                val delta = SPEED_CAMERA_ALERT_RADIUS_M / 111_111.0
                val nearbyCameras = speedCameraDao.getCamerasInBounds(
                    minLat = loc.latitude  - delta,
                    maxLat = loc.latitude  + delta,
                    minLon = loc.longitude - delta,
                    maxLon = loc.longitude + delta
                )

                // Fine-grained distance + speed-limit filter
                val alertCamera = nearbyCameras.firstOrNull { cam ->
                    val distResult = FloatArray(1)
                    Location.distanceBetween(
                        loc.latitude, loc.longitude,
                        cam.latitude, cam.longitude, distResult
                    )
                    val withinRadius = distResult[0] <= SPEED_CAMERA_ALERT_RADIUS_M
                    val overLimit    = cam.speedLimit > 0 && speedKmh > cam.speedLimit
                    withinRadius && overLimit
                } ?: return@launch

                withContext(Dispatchers.Main) {
                    lastSpeedAlertTime = System.currentTimeMillis()
                    val notification = NotificationHelper.buildSpeedAlert(
                        this@GpsTrackingService,
                        speedKmh,
                        alertCamera.speedLimit
                    )
                    NotificationHelper.notify(
                        this@GpsTrackingService,
                        NineGApp.NOTIFICATION_ID_SPEED_ALERT,
                        notification
                    )
                    vibrate(longArrayOf(0, 300, 100, 300))
                    speakText(
                        "Speed camera ahead. " +
                        "Limit ${alertCamera.speedLimit} km/h. " +
                        "Current speed ${speedKmh.toInt()} km/h."
                    )
                }
            } catch (_: Exception) {
                // Alert check must never crash the service.
            }
        }
    }

    // ─── Sensor Events ────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
                updateCompass()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetic, 0, 3)
                updateCompass()
            }
            Sensor.TYPE_PRESSURE -> {
                currentPressure = event.values[0]
                pressure.value = event.values[0]
            }
        }
    }

    private fun updateCompass() {
        val now = System.currentTimeMillis()
        if (now - lastBearingUpdate < 100) return
        lastBearingUpdate = now

        if (SensorManager.getRotationMatrix(rotation, null, gravity, magnetic)) {
            SensorManager.getOrientation(rotation, orientation)
            val azimuthRad = orientation[0]
            val azimuthDeg = Math.toDegrees(azimuthRad.toDouble()).toFloat()
            val bearing = (azimuthDeg + 360) % 360

            // Smooth compass — wrap diff to [-180, 180] so the filter always takes
            // the SHORT arc through the 0°/360° boundary instead of spinning
            // the long way around (e.g. 355° → 5° should step through 0°, not 350°).
            val current = compassBearing.value
            val diff = ((bearing - current + 540f) % 360f) - 180f
            val smoothed = current + diff * 0.3f

            compassBearing.value = (smoothed + 360) % 360
            currentBearing = compassBearing.value
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun startNotificationUpdateLoop() {
        notificationUpdateJob = lifecycleScope.launch {
            while (isActive) {
                delay(3000)
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NineGApp.NOTIFICATION_ID_TRACKING, buildTrackingNotification())
    }

    private fun buildTrackingNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val state = _recordingState.value
        val gps = _gpsState.value
        val stats = _trackStats.value
        val nav = _navigationState.value

        val title = when {
            nav.isNavigating -> "Navigating"
            state == RecordingState.RECORDING -> "Recording Track"
            state == RecordingState.PAUSED -> "Track Paused"
            else -> "GPS Active"
        }

        val speed = String.format("%.0f km/h", gps.speedKmh)
        val text = when {
            nav.isNavigating -> "${nav.currentInstruction} • $speed"
            state == RecordingState.RECORDING ->
                "$speed • ${NineGUtils.formatDistance(stats.distance)} • ${NineGUtils.formatDuration(stats.duration)}"
            else -> "$speed • Accuracy: ${gps.accuracy.toInt()}m"
        }

        val stopAction = if (state == RecordingState.RECORDING) {
            val stopIntent = PendingIntent.getService(
                this, 1,
                Intent(this, GpsTrackingService::class.java).apply { action = ACTION_PAUSE_TRACKING },
                PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", stopIntent)
        } else null

        return NotificationCompat.Builder(this, NineGApp.CHANNEL_GPS_TRACKING)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_navigation)
            .setContentIntent(intent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .apply { stopAction?.let { addAction(it) } }
            .build()
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun speakText(text: String) {
        lifecycleScope.launch {
            val enabled = userPreferences.voiceGuidance.first()
            if (enabled) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "gps_tts_${System.currentTimeMillis()}")
            }
        }
    }

    private fun vibrate(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }
}
