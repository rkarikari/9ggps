// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nineggps.R
import com.nineggps.data.db.entity.WaypointEntity
import com.nineggps.data.model.*
import com.nineggps.databinding.FragmentMapBinding
import com.nineggps.utils.NineGUtils
import com.nineggps.utils.NineGUtils.toGeoPoint
import com.nineggps.utils.OfflineMapManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.*
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.Polyline

@AndroidEntryPoint
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MapViewModel by viewModels()

    // Map overlays
    private lateinit var mapView: MapView
    private var myLocationOverlay: MyLocationNewOverlay? = null
    private var scaleBarOverlay: ScaleBarOverlay? = null
    private var rotationGestureOverlay: RotationGestureOverlay? = null
    private var routeOverlay: Polyline? = null
    private val alternateOverlays = mutableListOf<Polyline>()
    private var destinationMarker: Marker? = null
    private val waypointMarkers = mutableListOf<Marker>()
    private val viaWaypointMarkers = mutableListOf<Marker>()
    private var speedometerOverlay: SpeedometerOverlay? = null

    // ── Map orientation & compass rotation state ───────────────────────────────
    private var currentOrientMode = "NORTH"
    /** Animator for smooth map rotation reset to north. */
    private var rotationAnimator: ValueAnimator? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            onPermissionsGranted()
        } else {
            showPermissionRationale()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mapCentredOnce = false   // will be set to true once the first real position is applied

        // Push overlay cards below the status bar (edge-to-edge window)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = statusBarHeight
            }
            insets
        }

        setupMap()
        setupUI()
        observeViewModel()
        checkPermissionsAndStart()

        // Bug fix: handle navigate-to-waypoint args sent by WaypointsFragment.
        // When the user taps the arrow button on a saved waypoint, we pop back to
        // this fragment with waypointLat/waypointLon/waypointName in the arguments.
        // A non-zero lat or lon means a real destination was requested.
        val waypointLat  = arguments?.getFloat("waypointLat",  0f) ?: 0f
        val waypointLon  = arguments?.getFloat("waypointLon",  0f) ?: 0f
        val waypointName = arguments?.getString("waypointName", "") ?: ""
        if (waypointLat != 0f || waypointLon != 0f) {
            viewModel.navigateToPoint(
                name = waypointName.ifBlank { "Waypoint" },
                lat  = waypointLat.toDouble(),
                lon  = waypointLon.toDouble()
            )
            // Clear the args so a fragment recreation doesn't re-trigger routing.
            arguments?.remove("waypointLat")
            arguments?.remove("waypointLon")
            arguments?.remove("waypointName")
        }
    }

    // ─── Map Setup ────────────────────────────────────────────────────────────

    // Flag used to distinguish the very first GPS delivery after (re)creation so
    // we can snap instead of animate.  Reset each time onViewCreated runs.
    private var mapCentredOnce = false

    private fun setupMap() {
        mapView = binding.mapView
        // OSM Standard (Mapnik) — the original 9ggps v1 default, restored here.
        mapView.setTileSource(com.nineggps.utils.MapLayers.OSM_STANDARD)
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.setMultiTouchControls(true)
        mapView.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        mapView.isTilesScaledToDpi = true

        // Restore the camera to wherever the user was before navigating away.
        // Priority order:
        //   1. ViewModel saved position  (best — exact last view)
        //   2. Last known GPS fix        (good — at least not (0,0))
        //   3. Zoom-only at (0,0)        (last resort — first cold launch with no fix yet)
        val savedLat = viewModel.savedMapCenterLat
        val savedLon = viewModel.savedMapCenterLon
        mapView.controller.setZoom(viewModel.savedMapZoom)
        when {
            savedLat != null && savedLon != null -> {
                mapView.controller.setCenter(org.osmdroid.util.GeoPoint(savedLat, savedLon))
                mapCentredOnce = true   // no "first snap" needed; we already have a good position
            }
            else -> {
                // No saved camera yet (first ever launch). If there's already a GPS fix
                // in the shared service state, snap there immediately so the map never
                // shows (0,0) even on a cold start with a warm GPS cache.
                val loc = viewModel.gpsState.value.location
                if (loc != null) {
                    mapView.controller.setCenter(org.osmdroid.util.GeoPoint(loc.latitude, loc.longitude))
                    mapCentredOnce = true
                }
                // If loc is null here the map will sit at (0,0) briefly until the
                // first GPS event fires and snaps it — unavoidable on a true cold start.
            }
        }

        // Rotation gesture (two-finger twist)
        rotationGestureOverlay = RotationGestureOverlay(mapView).apply {
            isEnabled = true
        }
        mapView.overlays.add(rotationGestureOverlay)


        // Scale bar
        scaleBarOverlay = ScaleBarOverlay(mapView).apply {
            setCentred(false)
            setScaleBarOffset(10, 10)
        }
        mapView.overlays.add(scaleBarOverlay)

        // Map tap receiver
        val tapReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (viewModel.showSearchBar.value) return false
                showMapTapOptions(p)
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean {
                showDropPinDialog(p)
                return true
            }
        }
        mapView.overlays.add(MapEventsOverlay(tapReceiver))

        // Map scroll listener — disable follow mode on manual scroll
        mapView.addMapListener(object : org.osmdroid.events.MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                viewModel.setFollowMode(false)
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean = false
        })
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationOverlay() {
        myLocationOverlay = MyLocationNewOverlay(
            GpsMyLocationProvider(requireContext()), mapView
        ).apply {
            enableMyLocation()
            enableFollowLocation()
            setPersonIcon(createLocationPersonBitmap())
            setPersonHotspot(24f, 24f)   // center of the 48×48 person bitmap
            setDirectionIcon(createLocationArrowBitmap())
            setDirectionAnchor(0.5f, 0.5f)  // centre of the 48×48 arrow bitmap
        }
        mapView.overlays.add(myLocationOverlay)
    }

    /** Dark navy filled upward-pointing arrow for the moving-location indicator. */
    private fun createLocationArrowBitmap(): Bitmap {
        val size = 48   // match person bitmap so setPersonHotspot(24,24) centres both
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A237E")
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val path = Path().apply {
            moveTo(size / 2f, 2f)                      // tip (top centre)
            lineTo(size - 4f, size - 4f)                // bottom-right
            lineTo(size / 2f, size * 0.68f)             // inner notch
            lineTo(4f, size - 4f)                       // bottom-left
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
        return bmp
    }

    /** Dark navy filled circle for the stationary-location indicator. */
    private fun createLocationPersonBitmap(): Bitmap {
        val size = 48
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val r  = size / 2f - 3f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1A237E")
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val innerDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, stroke)
        canvas.drawCircle(cx, cy, r * 0.3f, innerDot)
        return bmp
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupUI() {
        // FAB controls
        binding.fabMyLocation.setOnClickListener {
            viewModel.setFollowMode(true)
            myLocationOverlay?.enableFollowLocation()
        }

        binding.fabRecordToggle.setOnClickListener {
            when (viewModel.recordingState.value) {
                RecordingState.IDLE      -> showActivityTypePicker()
                RecordingState.RECORDING -> viewModel.pauseRecording()
                RecordingState.PAUSED    -> viewModel.resumeRecording()
            }
        }

        binding.fabRecordStop.setOnClickListener {
            showStopRecordingConfirmation()
        }

        binding.fabSearch.setOnClickListener {
            viewModel.setShowSearchBar(!viewModel.showSearchBar.value)
        }

        binding.fabLayers.setOnClickListener {
            showLayerSelector()
        }

        binding.fabSatellite.setOnClickListener {
            findNavController().navigate(R.id.action_map_to_satellite)
        }

        binding.fabMenu.setOnClickListener {
            findNavController().navigate(R.id.action_map_to_dashboard)
        }

        // Search results list
        binding.searchResultsList.layoutManager = LinearLayoutManager(requireContext())

        // Alternate routes preview list
        binding.rvPreviewAlternates.layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvPreviewAlternates.adapter = com.nineggps.ui.navigation.AlternateRouteAdapter { index ->
            viewModel.selectAlternate(index)
        }

        // Alternates during active navigation — wired to the service via switchActiveRoute
        binding.rvNavAlternates.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(requireContext(),
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        binding.rvNavAlternates.adapter = com.nineggps.ui.navigation.AlternateRouteAdapter { index ->
            viewModel.switchActiveRoute(index)
        }

        binding.btnStartNav.setOnClickListener { viewModel.startPendingNavigation() }
        binding.btnCancelRoute.setOnClickListener {
            viewModel.cancelPendingRoute()
            clearAlternateOverlays()
        }

        // Search bar
        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                query?.let { viewModel.search(it) }
                return true
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                if ((newText?.length ?: 0) >= 3) {
                    viewModel.search(newText ?: "")
                }
                return true
            }
        })

        binding.btnCloseSearch.setOnClickListener {
            viewModel.clearSearch()
        }

        binding.ibStopNav.setOnClickListener {
            viewModel.stopNavigation()
            clearRouteOverlay()
            clearDestinationMarker()
            clearViaWaypointMarkers()
        }

        // Speed card tap for speedometer mode
        binding.cardSpeed.setOnClickListener {
            findNavController().navigate(R.id.action_map_to_hud)
        }

        // Compass tap: reset rotation if rotated; otherwise cycle NORTH ↔ DIRECTION
        binding.ivCompass.setOnClickListener {
            if (Math.abs(mapView.mapOrientation) > 2f) {
                animateMapRotationReset()
            } else {
                val newMode = if (currentOrientMode == "NORTH") "DIRECTION" else "NORTH"
                viewModel.setMapOrientationMode(newMode)
                val msg = if (newMode == "DIRECTION") "Heading-up: map follows your bearing"
                          else "North-up: map always faces north"
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            }
        }
        // Compass long-press: force toggle NORTH ↔ DIRECTION regardless of rotation
        binding.ivCompass.setOnLongClickListener {
            val newMode = if (currentOrientMode == "NORTH") "DIRECTION" else "NORTH"
            viewModel.setMapOrientationMode(newMode)
            if (newMode == "NORTH") animateMapRotationReset()
            val msg = if (newMode == "DIRECTION") "Heading-up activated"
                      else "North-up activated"
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
            true
        }
    }

    // ─── Observers ────────────────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                // GPS State
                launch {
                    viewModel.gpsState.collectLatest { state ->
                        updateGpsUI(state)
                        if (viewModel.isFollowMode.value && state.location != null) {
                            val gp = GeoPoint(state.location.latitude, state.location.longitude)
                            if (!mapCentredOnce) {
                                // First GPS fix after this view was created: snap instantly so
                                // the map never visibly slides in from (0, 0).
                                mapView.controller.setCenter(gp)
                                mapCentredOnce = true
                            } else {
                                mapView.controller.animateTo(gp)
                            }
                        }
                    }
                }

                // Recording state
                launch {
                    viewModel.recordingState.collectLatest { state ->
                        updateRecordingUI(state)
                    }
                }

                // Track stats
                launch {
                    viewModel.trackStats.collectLatest { stats ->
                        updateStatsUI(stats)
                    }
                }

                // Navigation state
                launch {
                    viewModel.navigationState.collectLatest { nav ->
                        updateNavigationUI(nav)
                    }
                }

                // Pending route preview (before navigation starts)
                launch {
                    viewModel.pendingRoute.collectLatest { pending ->
                        updateRoutePreviewUI(pending)
                    }
                }

                // Compass bearing + orientation mode (combined to avoid two separate collectors)
                launch {
                    combine(viewModel.compassBearing, viewModel.mapOrientation) { bearing, mode ->
                        Pair(bearing, mode)
                    }.collectLatest { (bearing, mode) ->
                        currentOrientMode = mode
                        // Rotate the compass rose to point north
                        binding.ivCompass.rotation = -bearing
                        // Update mode label: "N↑" = north-up, "▶↑" = heading-up
                        binding.tvOrientMode.text = if (mode == "DIRECTION") "▶↑" else "N↑"
                        // Drive map rotation in heading-up mode
                        if (mode == "DIRECTION") {
                            mapView.mapOrientation = -bearing
                        }
                    }
                }

                // Search results
                launch {
                    viewModel.searchResults.collectLatest { results ->
                        updateSearchResults(results)
                    }
                }

                // Search bar visibility
                launch {
                    viewModel.showSearchBar.collectLatest { show ->
                        binding.cardSearch.isVisible = show
                        if (!show) binding.searchResultsList.isVisible = false
                    }
                }

                // Weather
                launch {
                    viewModel.weatherData.collectLatest { weather ->
                        weather?.let { updateWeatherUI(it) }
                    }
                }

                // Snackbar messages
                launch {
                    viewModel.snackbarMessage.collectLatest { msg ->
                        msg?.let {
                            Snackbar.make(binding.root, it, Snackbar.LENGTH_SHORT).show()
                            viewModel.clearSnackbar()
                        }
                    }
                }

                // Error
                launch {
                    viewModel.errorMessage.collectLatest { err ->
                        err?.let {
                            Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG)
                                .setBackgroundTint(Color.RED)
                                .show()
                            viewModel.clearError()
                        }
                    }
                }

                // Auto weather fetch
                launch {
                    viewModel.showWeather.collectLatest { show ->
                        binding.cardWeather.isVisible = show
                        if (show) {
                            viewModel.gpsState.value.location?.let {
                                viewModel.fetchWeather(it.latitude, it.longitude)
                            }
                        }
                    }
                }

                // Offline mode — disable network tile downloads when enabled so the
                // map is served exclusively from the pre-downloaded local cache.
                launch {
                    viewModel.offlineMode.collectLatest { offline ->
                        OfflineMapManager.applyToMapView(mapView, offline)
                    }
                }

                // Auto-Record panel — combine the feature-enabled flag with the
                // active-recording flag and the live stats so the panel always
                // shows current distance / duration while auto-recording.
                launch {
                    combine(
                        viewModel.homeAutoRecord,
                        viewModel.autoRecordActive,
                        viewModel.trackStats
                    ) { enabled, active, stats -> Triple(enabled, active, stats) }
                        .collectLatest { (enabled, active, stats) ->
                            updateAutoRecordUI(enabled, active, stats)
                        }
                }
            }
        }
    }

    // ─── UI Updates ───────────────────────────────────────────────────────────

    private fun updateGpsUI(state: GpsState) {
        with(binding) {
            tvSpeed.text = NineGUtils.formatSpeed(state.speed)
            tvSpeedUnit.text = "km/h"
            tvAltitude.text = NineGUtils.formatAltitude(state.altitude)
            tvAccuracy.text = NineGUtils.formatAccuracy(state.accuracy)
            tvBearing.text = NineGUtils.formatBearing(state.bearing)
            tvSatellites.text = state.satellites.toString()

            // GPS fix indicator
            val fixColor = if (state.isFixed)
                ContextCompat.getColor(requireContext(), R.color.gps_fix_good)
            else
                ContextCompat.getColor(requireContext(), R.color.gps_fix_bad)
            ivGpsSignal.setColorFilter(fixColor)

            // Signal quality text
            tvSignalQuality.text = NineGUtils.getSignalQuality(state.accuracy, state.isFixed)
        }
    }

    /**
     * Drives the compact auto-record info panel.
     *
     *  • Hidden when [enabled] is false (feature turned off in Settings).
     *  • Amber dot + "Monitoring" when enabled but no auto-trip is in progress.
     *  • Red dot + "distance · duration" when an auto-trip is actively recording,
     *    giving the same at-a-glance info as the manual stats card without
     *    duplicating its full six-column layout.
     */
    private fun updateAutoRecordUI(enabled: Boolean, active: Boolean, stats: TrackStats) {
        with(binding) {
            if (!enabled) {
                cardAutoRecord.isVisible = false
                return
            }
            cardAutoRecord.isVisible = true

            if (active) {
                // Red dot + live trip distance + elapsed time
                tvAutoRecordDot.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.accent_red)
                )
                tvAutoRecordLabel.text = "Auto-Recording"
                tvAutoRecordStatus.text =
                    "${NineGUtils.formatDistance(stats.distance)} · ${NineGUtils.formatDuration(stats.duration)}"
            } else {
                // Amber dot + idle state
                tvAutoRecordDot.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.accent_orange)
                )
                tvAutoRecordLabel.text = "Auto-Record"
                tvAutoRecordStatus.text = "Monitoring"
            }
        }
    }

    private fun updateRecordingUI(state: RecordingState) {
        with(binding) {
            when (state) {
                RecordingState.IDLE -> {
                    fabRecordToggle.setImageResource(R.drawable.ic_record)
                    fabRecordToggle.backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.record_idle)
                    fabRecordStop.isVisible = false
                    cardTrackStats.isVisible = false
                    tvRecordingIndicator.isVisible = false
                }
                RecordingState.RECORDING -> {
                    fabRecordToggle.setImageResource(R.drawable.ic_pause)
                    fabRecordToggle.backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.record_active)
                    fabRecordStop.isVisible = true
                    cardTrackStats.isVisible = true
                    tvRecordingIndicator.isVisible = true
                    startBlinkAnimation()
                }
                RecordingState.PAUSED -> {
                    fabRecordToggle.setImageResource(R.drawable.ic_play)
                    fabRecordToggle.backgroundTintList =
                        ContextCompat.getColorStateList(requireContext(), R.color.record_paused)
                    fabRecordStop.isVisible = true
                    cardTrackStats.isVisible = true
                    tvRecordingIndicator.isVisible = false
                    stopBlinkAnimation()
                }
            }
        }
    }

    private fun updateStatsUI(stats: TrackStats) {
        with(binding) {
            tvTrackDistance.text = NineGUtils.formatDistance(stats.distance)
            tvTrackDuration.text = NineGUtils.formatDuration(stats.duration)
            tvTrackAvgSpeed.text = "${NineGUtils.formatSpeed(stats.avgSpeed / 3.6f)} km/h"
            tvTrackMaxSpeed.text = "${NineGUtils.formatSpeed(stats.maxSpeed / 3.6f)} km/h"
            tvTrackElevation.text = "+${NineGUtils.formatAltitude(stats.elevationGain)}"
            tvTrackCalories.text = "${stats.calories} kcal"
        }
    }

    private fun updateNavigationUI(nav: NavigationState) {
        with(binding) {
            cardNavigation.isVisible = nav.isNavigating

            if (nav.isNavigating) {
                tvNavInstruction.text = nav.currentInstruction
                tvNavNextInstruction.text = "Then: ${nav.nextInstruction}"
                tvNavDistance.text = NineGUtils.formatDistanceShort(nav.distanceToNextTurn)
                tvNavEta.text = NineGUtils.formatEta(nav.estimatedTimeRemaining)
                tvNavRemaining.text = NineGUtils.formatDistance(nav.totalDistanceRemaining)

                // Always show the alternate-routes bubble during navigation so the user
                // can switch routes at any time. For waypoint routes OSRM cannot return
                // alternatives, so the list may contain only 1 entry — we still show the
                // bubble so the user knows which route is active.
                val alternates = nav.alternateRoutes
                val hasAlternates = alternates.isNotEmpty()
                scrollNavAlternates.isVisible = hasAlternates
                if (hasAlternates) {
                    (rvNavAlternates.adapter as? com.nineggps.ui.navigation.AlternateRouteAdapter)
                        ?.submitList(alternates)
                }

                if (nav.route.isNotEmpty()) drawRoute(nav.route)
                nav.destination?.let { drawDestinationMarker(it) }
                // Draw intermediate waypoint markers so the driver can see upcoming stops.
                drawViaWaypointMarkers(nav.viaWaypoints)
            } else {
                scrollNavAlternates.isVisible = false
                clearRouteOverlay()
                clearDestinationMarker()
                clearViaWaypointMarkers()
            }
        }
    }

    private fun updateWeatherUI(weather: WeatherData) {
        with(binding) {
            tvWeatherTemp.text = String.format("%.0f°", weather.temperature)
            tvWeatherDesc.text = weather.description
            tvWeatherWind.text = "${weather.windSpeed.toInt()} m/s"
            tvWeatherHumidity.text = "${weather.humidity}%"
        }
    }

    private fun updateSearchResults(results: List<SearchResult>) {
        binding.searchResultsList.isVisible = results.isNotEmpty()
        val adapter = SearchResultAdapter(results) { result ->
            viewModel.clearSearch()
            showNavigationPrompt(result)
        }
        binding.searchResultsList.adapter = adapter
    }

    // ─── Route Drawing ────────────────────────────────────────────────────────

    private fun drawRoute(points: List<LatLngPoint>) {
        routeOverlay?.let { mapView.overlays.remove(it) }
        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        routeOverlay = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.parseColor("#2196F3")
            outlinePaint.strokeWidth = 12f
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        }
        mapView.overlays.add(0, routeOverlay)
        mapView.invalidate()
    }

    private fun drawDestinationMarker(dest: LatLngPoint) {
        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = Marker(mapView).apply {
            position = GeoPoint(dest.latitude, dest.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_destination_marker)
            title = "Destination"
        }
        mapView.overlays.add(destinationMarker)
        mapView.invalidate()
    }

    private fun clearRouteOverlay() {
        routeOverlay?.let { mapView.overlays.remove(it) }
        routeOverlay = null
        mapView.invalidate()
    }

    private fun clearDestinationMarker() {
        destinationMarker?.let { mapView.overlays.remove(it) }
        destinationMarker = null
        mapView.invalidate()
    }

    /** Draws numbered stop markers for each intermediate waypoint in the active route. */
    private fun drawViaWaypointMarkers(waypoints: List<LatLngPoint>) {
        clearViaWaypointMarkers()
        waypoints.forEachIndexed { idx, pt ->
            val marker = Marker(mapView).apply {
                position = GeoPoint(pt.latitude, pt.longitude)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_waypoint)
                title = "Stop ${idx + 1}"
            }
            viaWaypointMarkers.add(marker)
            mapView.overlays.add(marker)
        }
        if (waypoints.isNotEmpty()) mapView.invalidate()
    }

    private fun clearViaWaypointMarkers() {
        viaWaypointMarkers.forEach { mapView.overlays.remove(it) }
        viaWaypointMarkers.clear()
    }

    // ─── Route Preview ────────────────────────────────────────────────────────

    private fun updateRoutePreviewUI(pending: NavigationState?) {
        if (pending == null) {
            binding.cardRoutePreview.isVisible = false
            clearAlternateOverlays()
            return
        }

        binding.cardRoutePreview.isVisible = true
        binding.tvPreviewDistance.text = NineGUtils.formatDistance(pending.totalDistanceRemaining)
        binding.tvPreviewEta.text      = NineGUtils.formatEta(pending.estimatedTimeRemaining)

        val alternates = pending.alternateRoutes
        val hasMultiple = alternates.size > 1
        binding.tvAltRoutesLabel.isVisible        = hasMultiple
        binding.scrollPreviewAlternates.isVisible = hasMultiple
        if (hasMultiple) {
            (binding.rvPreviewAlternates.adapter as? com.nineggps.ui.navigation.AlternateRouteAdapter)
                ?.submitList(alternates)
        }

        drawAlternateRoutes(alternates)
    }

    private fun drawAlternateRoutes(routes: List<AlternateRoute>) {
        clearAlternateOverlays()
        if (routes.isEmpty()) return

        routes.forEach { alt ->
            val color = if (alt.isActive) Color.parseColor("#2196F3")
                        else Color.parseColor("#78909C")  // blue-grey for inactive
            val width = if (alt.isActive) 12f else 7f
            val poly  = Polyline().apply {
                setPoints(alt.route.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.color       = color
                outlinePaint.strokeWidth = width
                outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
                outlinePaint.strokeJoin  = android.graphics.Paint.Join.ROUND
                outlinePaint.alpha       = if (alt.isActive) 255 else 140
            }
            alternateOverlays.add(poly)
        }
        // Add inactive first (drawn under), then active on top
        alternateOverlays.sortedBy { it.outlinePaint.alpha }.forEach {
            mapView.overlays.add(0, it)
        }
        mapView.invalidate()
    }

    private fun clearAlternateOverlays() {
        alternateOverlays.forEach { mapView.overlays.remove(it) }
        alternateOverlays.clear()
        mapView.invalidate()
    }

    // ─── Dialogs ──────────────────────────────────────────────────────────────

    private fun showNavigationPrompt(result: SearchResult) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(result.displayName.take(60))
            .setItems(
                arrayOf("Navigate directly", "Navigate via waypoints", "Pin here")
            ) { _, which ->
                when (which) {
                    0 -> viewModel.navigateTo(result)
                    1 -> showWaypointSelectionDialog(result)
                    2 -> addWaypointPin(result)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Multi-select dialog that lets the user pick saved waypoints as ordered
     * intermediate stops, then kicks off routing via [MapViewModel.navigateToViaWaypoints].
     * Falls back to a direct route if no waypoints are saved.
     */
    private fun showWaypointSelectionDialog(destination: SearchResult) {
        val waypoints = viewModel.savedWaypoints.value
        if (waypoints.isEmpty()) {
            Snackbar.make(binding.root, "No saved waypoints — navigating directly", Snackbar.LENGTH_SHORT).show()
            viewModel.navigateTo(destination)
            return
        }

        // Inflate the custom ordered-selection view.
        val dialogView = layoutInflater.inflate(R.layout.dialog_waypoint_order, null)
        val tvSummary  = dialogView.findViewById<android.widget.TextView>(R.id.tvOrderSummary)
        val rv         = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvWaypointOrder)

        val orderAdapter = WaypointOrderAdapter { ordered ->
            // Refresh the live "Stop 1 -> Stop 2 -> ..." summary strip.
            tvSummary.text = if (ordered.isEmpty())
                "Tap waypoints below in the order to visit them."
            else
                ordered.joinToString(" \u2192 ") { it.name }
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = orderAdapter
        orderAdapter.submitList(waypoints)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Route via waypoints")
            .setView(dialogView)
            .setPositiveButton("Navigate") { _, _ ->
                val ordered = orderAdapter.getOrderedSelection()
                if (ordered.isEmpty()) {
                    // No stops chosen — navigate directly to destination.
                    viewModel.navigateTo(destination)
                } else {
                    // Stops passed in the exact tap order the user specified.
                    viewModel.navigateToViaWaypoints(destination, ordered)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMapTapOptions(point: GeoPoint) {
        // Dismissed silently if nothing important nearby
    }

    private fun showDropPinDialog(point: GeoPoint) {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Waypoint name"
        }

        // Draw a temporary "drop preview" marker immediately so the user sees
        // exactly where the pin will land before they confirm.
        val previewMarker = Marker(mapView).apply {
            position = point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_waypoint)
            title = "Drop Pin"
        }
        mapView.overlays.add(previewMarker)
        mapView.invalidate()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Drop Pin")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { "Pin ${System.currentTimeMillis()}" }
                // Fix: use the tapped GeoPoint, NOT the current GPS location.
                viewModel.addWaypointAt(name, point.latitude, point.longitude)
                // Keep the marker on the map and update its title to the chosen name.
                previewMarker.title = name
                mapView.invalidate()
                waypointMarkers.add(previewMarker)
            }
            .setNegativeButton("Navigate Here") { _, _ ->
                // Remove the preview — navigation will draw its own destination marker.
                mapView.overlays.remove(previewMarker)
                mapView.invalidate()
                val dest = SearchResult(
                    displayName = "Pinned Location",
                    latitude = point.latitude,
                    longitude = point.longitude
                )
                viewModel.navigateTo(dest)
            }
            .setNeutralButton("Cancel") { _, _ ->
                // User cancelled — remove the preview marker.
                mapView.overlays.remove(previewMarker)
                mapView.invalidate()
            }
            .setOnCancelListener {
                mapView.overlays.remove(previewMarker)
                mapView.invalidate()
            }
            .show()
    }

    private fun showStopRecordingConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Stop Recording")
            .setMessage("Stop and save the current track?")
            .setPositiveButton("Stop & Save") { _, _ -> viewModel.stopRecording() }
            .setNegativeButton("Keep Recording", null)
            .show()
    }

    /**
     * Shows a single-choice dialog listing all activity types before a recording
     * session begins.  The currently saved default is pre-selected so a quick tap
     * of "Start" uses the same type as last time without any extra interaction.
     *
     * The selected type is passed directly to [MapViewModel.startRecording] and is
     * used only for this session — it does **not** overwrite the global Settings
     * default, so the user's persistent preference is preserved.
     */
    private fun showActivityTypePicker() {
        val labels = resources.getStringArray(R.array.activity_types)
        val values = resources.getStringArray(R.array.activity_type_values)

        viewLifecycleOwner.lifecycleScope.launch {
            val currentType = viewModel.currentActivityType()
            val preSelected = values.indexOf(currentType).coerceAtLeast(0)
            var selected = preSelected

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Start Recording")
                .setSingleChoiceItems(labels, preSelected) { _, which ->
                    selected = which
                }
                .setPositiveButton("Start") { _, _ ->
                    viewModel.startRecording(activityTypeOverride = values[selected])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun showLayerSelector() {
        // Organic Maps-style crisp layers + offline toggle
        viewLifecycleOwner.lifecycleScope.launch {
            val isOffline = viewModel.offlineMode.first()
            val offlineLabel = if (isOffline) "✓ Offline mode (ON)" else "Offline mode (OFF)"

            val layerLabels = com.nineggps.utils.MapLayers.ALL.map { it.label }
            val items = (layerLabels + offlineLabel).toTypedArray()

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Map Layer")
                .setItems(items) { _, which ->
                    if (which < com.nineggps.utils.MapLayers.ALL.size) {
                        mapView.setTileSource(com.nineggps.utils.MapLayers.ALL[which].source)
                        mapView.invalidate()
                    } else {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val newState = !viewModel.offlineMode.first()
                            viewModel.setOfflineMode(newState)
                            val msg = if (newState)
                                "Offline mode ON — using cached tiles only"
                            else
                                "Offline mode OFF — downloading tiles normally"
                            Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                .show()
        }
    }

    private fun addWaypointPin(result: SearchResult) {
        val marker = Marker(mapView).apply {
            position = GeoPoint(result.latitude, result.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = result.displayName.take(40)
        }
        waypointMarkers.add(marker)
        mapView.overlays.add(marker)
        mapView.invalidate()
    }

    /**
     * Smoothly reset the map compass heading back to 0° (north-up).
     * Fires on compass single-tap when the map is currently rotated.
     */
    private fun animateMapRotationReset() {
        rotationAnimator?.cancel()
        val startAngle = mapView.mapOrientation
        rotationAnimator = ValueAnimator.ofFloat(startAngle, 0f).apply {
            duration = 300
            addUpdateListener { mapView.mapOrientation = it.animatedValue as Float }
            start()
        }
    }

    // ─── Animation ────────────────────────────────────────────────────────────

    private var blinkJob: kotlinx.coroutines.Job? = null

    private fun startBlinkAnimation() {
        blinkJob?.cancel()
        blinkJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                binding.tvRecordingIndicator.alpha = 1f
                delay(600)
                binding.tvRecordingIndicator.alpha = 0f
                delay(400)
            }
        }
    }

    private fun stopBlinkAnimation() {
        blinkJob?.cancel()
        binding.tvRecordingIndicator.alpha = 0f
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        val fineLocation = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (fineLocation == PackageManager.PERMISSION_GRANTED) {
            onPermissionsGranted()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS
                )
            )
        }
    }

    private fun onPermissionsGranted() {
        setupLocationOverlay()
        viewModel.startGpsService()
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Location Permission Required")
            .setMessage("9G GPS needs location access to track your position, record routes, and provide navigation.")
            .setPositiveButton("Grant") { _, _ -> checkPermissionsAndStart() }
            .setNegativeButton("Exit") { _, _ -> requireActivity().finish() }
            .setCancelable(false)
            .show()
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        viewModel.bindService()
    }

    override fun onStop() {
        super.onStop()
        viewModel.unbindService()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        myLocationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rotationAnimator?.cancel()
        // Persist the current camera so the map reopens at the same position
        // instead of flashing at (0, 0) and panning to the GPS fix.
        val centre = mapView.mapCenter
        viewModel.saveMapCamera(centre.latitude, centre.longitude, mapView.zoomLevelDouble)
        myLocationOverlay?.disableMyLocation()
        mapView.overlays.clear()
        mapView.onDetach()
        _binding = null
    }
}
