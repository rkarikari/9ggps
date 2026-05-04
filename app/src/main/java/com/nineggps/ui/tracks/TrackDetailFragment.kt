// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.tracks

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import com.nineggps.R
import com.nineggps.data.db.entity.TrackEntity
import com.nineggps.data.db.entity.TrackPointEntity
import com.nineggps.data.repository.TrackRepository
import com.nineggps.databinding.FragmentTrackDetailBinding
import com.nineggps.data.prefs.UserPreferences
import com.nineggps.utils.NineGUtils
import com.nineggps.utils.NineGpxExporter
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.nineggps.utils.MapLayers
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import javax.inject.Inject
import kotlin.math.abs

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class TrackDetailViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val userPreferences: UserPreferences
) : ViewModel() {

    private val _track = MutableStateFlow<TrackEntity?>(null)
    val track: StateFlow<TrackEntity?> = _track

    private val _points = MutableStateFlow<List<TrackPointEntity>>(emptyList())
    val points: StateFlow<List<TrackPointEntity>> = _points

    // ─── Map layer persistence ─────────────────────────────────────────────────
    /** The stable id of the last tile-source selected across the whole app. */
    val mapLayerId: Flow<String> = userPreferences.mapLayerId

    /** Persist the tile-source selection so it's restored on the next visit. */
    fun saveMapLayer(layerId: String) {
        viewModelScope.launch { userPreferences.setMapLayerId(layerId) }
    }

    fun loadTrack(id: Long) {
        viewModelScope.launch {
            _track.value = repository.getTrackById(id)
            _points.value = repository.getTrackPoints(id)
        }
    }

    fun updateTrackName(id: Long, name: String) {
        viewModelScope.launch {
            val track = repository.getTrackById(id) ?: return@launch
            repository.updateTrack(track.copy(name = name))
            _track.value = track.copy(name = name)
        }
    }

    fun getGpxContent(track: TrackEntity, points: List<TrackPointEntity>): String {
        return NineGpxExporter.exportTrack(track, points)
    }

    fun getKmlContent(track: TrackEntity, points: List<TrackPointEntity>): String {
        return NineGpxExporter.exportTrackKml(track, points)
    }
}

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class TrackDetailFragment : Fragment(R.layout.fragment_track_detail) {

    private var _binding: FragmentTrackDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TrackDetailViewModel by viewModels()
    private val args: TrackDetailFragmentArgs by navArgs()

    // ─── Synchronized cursor state ────────────────────────────────────────────

    /**
     * Each entry pairs a track point with its cumulative distance from the
     * track start, expressed in kilometres (matches the chart X-axis unit).
     */
    private var pointsWithDist: List<Pair<TrackPointEntity, Float>> = emptyList()

    /** OSMDroid marker that moves over the mini-map in sync with the charts. */
    private var cursorMarker: Marker? = null

    /** Cached cursor icon so we only build the bitmap once. */
    private var cursorIconCache: BitmapDrawable? = null

    /** Track start time, used to compute elapsed time at the cursor position. */
    private var trackStartTime: Long = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentTrackDetailBinding.bind(view)
        viewModel.loadTrack(args.trackId)

        setupCharts()
        observeViewModel()

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.btnExportGpx.setOnClickListener { exportGpx() }
        binding.btnExportKml.setOnClickListener { exportKml() }
        binding.btnShare.setOnClickListener { shareTrack() }

        binding.tvTrackName.setOnClickListener { showRenameDialog() }

        // Full-screen map
        binding.btnFullScreenMap.setOnClickListener {
            val action = TrackDetailFragmentDirections.actionTrackDetailToTrackMap(args.trackId)
            findNavController().navigate(action)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.track, viewModel.points) { track, points ->
                        Pair(track, points)
                    }.collectLatest { (track, points) ->
                        track ?: return@collectLatest
                        trackStartTime = track.startTime
                        updateUI(track, points)
                        drawRouteOnMap(points)
                        buildDistanceIndex(points)
                        updateElevationChart(points)
                        updateSpeedChart(points)
                    }
                }
            }
        }
    }

    private fun updateUI(track: TrackEntity, points: List<TrackPointEntity>) {
        with(binding) {
            tvTrackName.text = track.name
            tvDate.text = java.text.SimpleDateFormat(
                "EEEE, MMM dd yyyy • HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date(track.startTime))
            tvDistance.text = NineGUtils.formatDistance(track.distance)
            tvDuration.text = NineGUtils.formatDuration(track.duration)
            tvAvgSpeed.text = "${track.avgSpeed.toInt()} km/h"
            tvMaxSpeed.text = "${track.maxSpeed.toInt()} km/h"
            tvMinAlt.text = NineGUtils.formatAltitude(track.minAltitude)
            tvMaxAlt.text = NineGUtils.formatAltitude(track.maxAltitude)
            tvElevGain.text = "+${NineGUtils.formatAltitude(track.elevationGain)}"
            tvElevLoss.text = "-${NineGUtils.formatAltitude(track.elevationLoss)}"
            tvCalories.text = "${track.calories} kcal"
            tvActivityType.text = track.activityType
            tvPointCount.text = "${points.size} GPS points"

            // Pace
            val paceMinKm = if (track.distance > 0 && track.avgSpeed > 0) {
                60f / track.avgSpeed
            } else 0f
            val paceMin = paceMinKm.toInt()
            val paceSec = ((paceMinKm - paceMin) * 60).toInt()
            tvPace.text = "${paceMin}:${paceSec.toString().padStart(2,'0')} /km"
        }
    }

    // ─── Mini Map ─────────────────────────────────────────────────────────────

    private fun drawRouteOnMap(points: List<TrackPointEntity>) {
        if (points.isEmpty()) return
        val map = binding.miniMap
        map.setTileSource(MapLayers.CARTO_VOYAGER)
        map.setMultiTouchControls(true)
        map.isClickable = true
        map.isTilesScaledToDpi = true
        map.zoomController.setVisibility(
            org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
        )

        // Allow the map to consume touch events without the NestedScrollView stealing them
        @Suppress("ClickableViewAccessibility")
        map.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false // let osmdroid handle the actual event
        }

        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }

        val polyline = Polyline().apply {
            setPoints(geoPoints)
            outlinePaint.color = Color.parseColor("#2196F3")
            outlinePaint.strokeWidth = 6f
            outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        }
        map.overlays.clear()
        map.overlays.add(polyline)

        val box = BoundingBox.fromGeoPoints(geoPoints)
        map.post {
            map.zoomToBoundingBox(box, true, 40)
        }
        map.invalidate()
    }

    // ─── Charts ───────────────────────────────────────────────────────────────

    private fun setupCharts() {
        setupChart(binding.chartElevation, "Elevation (m)")
        setupChart(binding.chartSpeed, "Speed (km/h)")
        setupCursorSync()
    }

    private fun setupChart(chart: LineChart, label: String) {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            legend.isEnabled = false
            axisRight.isEnabled = false
            isHighlightPerDragEnabled = true
            isHighlightPerTapEnabled = true

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(false)
                textColor = Color.GRAY
                valueFormatter = object : ValueFormatter() {
                    override fun getFormattedValue(value: Float): String {
                        // X axis is in km — multiply by 1000 for formatDistance
                        return NineGUtils.formatDistance(value.toDouble() * 1000.0)
                    }
                }
            }
            axisLeft.apply {
                setDrawGridLines(true)
                gridColor = Color.argb(30, 128, 128, 128)
                textColor = Color.GRAY
            }
        }
    }

    private fun updateElevationChart(points: List<TrackPointEntity>) {
        if (points.size < 2) return
        var distAcc = 0.0
        val entries = mutableListOf<Entry>()
        points.forEachIndexed { i, pt ->
            if (i > 0) {
                val prev = points[i - 1]
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    prev.latitude, prev.longitude, pt.latitude, pt.longitude, results
                )
                distAcc += results[0]
            }
            entries.add(Entry((distAcc / 1000).toFloat(), pt.altitude.toFloat()))
        }

        val dataSet = LineDataSet(entries, "Elevation").apply {
            color = Color.parseColor("#FF9800")
            setDrawCircles(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillColor = Color.parseColor("#FFCC80")
            fillAlpha = 100
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawValues(false)
            highLightColor = Color.parseColor("#FF5722")
            highlightLineWidth = 1.5f
        }

        binding.chartElevation.data = LineData(dataSet)
        binding.chartElevation.animateX(800)
    }

    private fun updateSpeedChart(points: List<TrackPointEntity>) {
        if (points.size < 2) return
        var distAcc = 0.0
        val entries = mutableListOf<Entry>()
        points.forEachIndexed { i, pt ->
            if (i > 0) {
                val prev = points[i - 1]
                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    prev.latitude, prev.longitude, pt.latitude, pt.longitude, results
                )
                distAcc += results[0]
            }
            entries.add(Entry((distAcc / 1000).toFloat(), pt.speed * 3.6f))
        }

        val dataSet = LineDataSet(entries, "Speed").apply {
            color = Color.parseColor("#2196F3")
            setDrawCircles(false)
            lineWidth = 2f
            setDrawFilled(true)
            fillColor = Color.parseColor("#90CAF9")
            fillAlpha = 100
            mode = LineDataSet.Mode.CUBIC_BEZIER
            setDrawValues(false)
            highLightColor = Color.parseColor("#FF5722")
            highlightLineWidth = 1.5f
        }

        binding.chartSpeed.data = LineData(dataSet)
        binding.chartSpeed.animateX(800)
    }

    // ─── Synchronized Cursor ──────────────────────────────────────────────────

    /**
     * Pre-compute the cumulative distance (in km) for every track point so
     * that touch events on either chart can instantly look up the nearest GPS
     * position and keep the map marker in sync.
     */
    private fun buildDistanceIndex(points: List<TrackPointEntity>) {
        var distKm = 0.0
        pointsWithDist = points.mapIndexed { i, pt ->
            if (i > 0) {
                val prev = points[i - 1]
                val r = FloatArray(1)
                android.location.Location.distanceBetween(
                    prev.latitude, prev.longitude, pt.latitude, pt.longitude, r
                )
                distKm += r[0] / 1000.0
            }
            Pair(pt, distKm.toFloat())
        }
    }

    /**
     * Wire up both charts so that any touch (tap or drag) on one chart
     * immediately mirrors the cursor position and info bubble on the other
     * chart and on the mini-map.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupCursorSync() {
        val makeTouchListener = { chart: LineChart ->
            View.OnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_MOVE -> {
                        // Prevent the NestedScrollView from stealing vertical drags
                        chart.parent?.requestDisallowInterceptTouchEvent(true)
                        val h = chart.getHighlightByTouchPoint(event.x, event.y)
                        if (h != null && pointsWithDist.isNotEmpty()) {
                            syncCursorAtDistance(h.x)
                        }
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        chart.parent?.requestDisallowInterceptTouchEvent(false)
                        clearCursor()
                    }
                }
                false // still let MPAndroidChart handle zoom / pan
            }
        }
        binding.chartElevation.setOnTouchListener(makeTouchListener(binding.chartElevation))
        binding.chartSpeed.setOnTouchListener(makeTouchListener(binding.chartSpeed))
    }

    /**
     * Move the cursor to the track position closest to [xKm] (distance from
     * start, in kilometres), update both chart highlights and the mini-map
     * marker, and populate the floating info bubble.
     */
    private fun syncCursorAtDistance(xKm: Float) {
        if (pointsWithDist.isEmpty()) return

        // Nearest point by distance
        var nearestIdx = 0
        var minDiff = Float.MAX_VALUE
        pointsWithDist.forEachIndexed { i, (_, dist) ->
            val diff = abs(dist - xKm)
            if (diff < minDiff) { minDiff = diff; nearestIdx = i }
        }

        val (point, dist) = pointsWithDist[nearestIdx]
        val totalDist = pointsWithDist.last().second.coerceAtLeast(0.001f)
        val progressPct = (dist / totalDist * 100).toInt()

        // ── Synchronize chart highlights ──────────────────────────────────────
        // callListener = false avoids re-triggering our own touch handler
        binding.chartElevation.highlightValue(dist, 0, false)
        binding.chartSpeed.highlightValue(dist, 0, false)

        // ── Move mini-map cursor marker ───────────────────────────────────────
        placeCursorOnMap(point)

        // ── Populate the info bubble ──────────────────────────────────────────
        val elapsedMs = point.timestamp - trackStartTime
        binding.cardCursorInfo.isVisible = true
        binding.tvCursorDist.text    = NineGUtils.formatDistance(dist.toDouble() * 1000.0)
        binding.tvCursorSpeed.text   = "${(point.speed * 3.6f).toInt()} km/h"
        binding.tvCursorElev.text    = NineGUtils.formatAltitude(point.altitude)
        binding.tvCursorTime.text    = NineGUtils.formatDuration(elapsedMs.coerceAtLeast(0L))
        binding.tvCursorProgress.text = "$progressPct%"
    }

    /** Place (or move) the orange dot marker on the mini-map. */
    private fun placeCursorOnMap(point: TrackPointEntity) {
        val map = binding.miniMap
        if (cursorMarker == null) {
            cursorMarker = Marker(map).apply {
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = getCursorIcon()
                infoWindow = null
            }
            map.overlays.add(cursorMarker)
        }
        cursorMarker!!.position = GeoPoint(point.latitude, point.longitude)
        map.invalidate()
    }

    /**
     * Dismiss the cursor: clear both chart highlights, remove the map marker,
     * and hide the info bubble.
     */
    private fun clearCursor() {
        binding.cardCursorInfo.isVisible = false
        binding.chartElevation.highlightValue(null)
        binding.chartSpeed.highlightValue(null)
        cursorMarker?.let { binding.miniMap.overlays.remove(it) }
        cursorMarker = null
        binding.miniMap.invalidate()
    }

    /**
     * Build (and cache) a crisp circular bitmap drawable for the map cursor.
     * The circle is drawn entirely in code so no extra drawable resource is
     * needed.
     */
    private fun getCursorIcon(): BitmapDrawable {
        cursorIconCache?.let { return it }
        val dp    = resources.displayMetrics.density
        val size  = (24 * dp).toInt()
        val bmp   = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF5722")
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3 * dp
        }
        val r = size / 2f
        canvas.drawCircle(r, r, r - 2 * dp, fillPaint)
        canvas.drawCircle(r, r, r - 2 * dp, strokePaint)

        return BitmapDrawable(resources, bmp).also { cursorIconCache = it }
    }

    // ─── Export ───────────────────────────────────────────────────────────────

    private fun exportGpx() {
        val track = viewModel.track.value ?: return
        val points = viewModel.points.value
        val content = viewModel.getGpxContent(track, points)
        val file = NineGpxExporter.saveToFile(requireContext(), content, "${track.name}.gpx")
        shareFile(file, "application/gpx+xml")
    }

    private fun exportKml() {
        val track = viewModel.track.value ?: return
        val points = viewModel.points.value
        val content = viewModel.getKmlContent(track, points)
        val file = NineGpxExporter.saveToFile(requireContext(), content, "${track.name}.kml")
        shareFile(file, "application/vnd.google-earth.kml+xml")
    }

    private fun shareTrack() {
        val track = viewModel.track.value ?: return
        val text = """
            🗺️ Track: ${track.name}
            📍 Distance: ${NineGUtils.formatDistance(track.distance)}
            ⏱️ Duration: ${NineGUtils.formatDuration(track.duration)}
            🚀 Avg Speed: ${track.avgSpeed.toInt()} km/h
            ⬆️ Elevation Gain: +${NineGUtils.formatAltitude(track.elevationGain)}
            
            Shared from 9G GPS
        """.trimIndent()

        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, text)
        }
        startActivity(android.content.Intent.createChooser(intent, "Share Track"))
    }

    private fun shareFile(file: java.io.File, mimeType: String) {
        val uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(android.content.Intent.createChooser(intent, "Export Track"))
    }

    private fun showRenameDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            setText(viewModel.track.value?.name ?: "")
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Rename Track")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().ifBlank { return@setPositiveButton }
                viewModel.updateTrackName(args.trackId, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
