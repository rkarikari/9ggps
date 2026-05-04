// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.tracks

import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nineggps.R
import com.nineggps.data.db.entity.TrackPointEntity
import com.nineggps.databinding.FragmentTrackMapBinding
import com.nineggps.utils.MapLayers
import com.nineggps.utils.NineGUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

// ─── Full-Screen Track Map ────────────────────────────────────────────────────

@AndroidEntryPoint
class TrackMapFragment : Fragment(R.layout.fragment_track_map) {

    private var _binding: FragmentTrackMapBinding? = null
    private val binding get() = _binding!!

    // Share the same ViewModel as TrackDetailFragment (same back-stack scope)
    private val viewModel: TrackDetailViewModel by viewModels()
    private val args: TrackMapFragmentArgs by navArgs()

    private var trackBoundingBox: BoundingBox? = null

    /** Currently active layer — persisted within the fragment lifetime. */
    private var currentLayerIndex: Int = 0   // index into MapLayers.ALL

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentTrackMapBinding.bind(view)

        enterFullScreen()
        viewModel.loadTrack(args.trackId)
        setupMap()
        setupControls()
        observeViewModel()
    }

    // ─── Map setup ────────────────────────────────────────────────────────────

    private fun setupMap() {
        binding.trackMapFull.apply {
            // OSM Standard — the original 9ggps v1 default, consistent with MapFragment.
            setTileSource(MapLayers.OSM_STANDARD)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

            // Fluid rotation gesture
            overlays.add(RotationGestureOverlay(this).apply { isEnabled = true })

            // Scale bar
            overlays.add(ScaleBarOverlay(this).apply {
                setCentred(false)
                setScaleBarOffset(20, 20)
            })

            minZoomLevel = 3.0
            maxZoomLevel = 21.0
            isTilesScaledToDpi = true
            controller.setZoom(14.0)
        }
    }

    private fun setupControls() {
        binding.btnMapClose.setOnClickListener { findNavController().navigateUp() }

        binding.btnMapCenter.setOnClickListener {
            trackBoundingBox?.let { box ->
                binding.trackMapFull.zoomToBoundingBox(box, true, 80)
            }
        }

        binding.btnMapLayer.setOnClickListener { showLayerPicker() }
    }

    // ─── ViewModel observation ────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.track.collectLatest { track ->
                        track ?: return@collectLatest
                        binding.tvMapTrackName.text = track.name
                        binding.tvMapDistance.text = NineGUtils.formatDistance(track.distance)
                        binding.tvMapDuration.text = NineGUtils.formatDuration(track.duration)
                        binding.tvMapElevGain.text = "+${NineGUtils.formatAltitude(track.elevationGain)}"
                        binding.tvMapAvgSpeed.text = "${track.avgSpeed.toInt()}"
                    }
                }

                launch {
                    viewModel.points.collectLatest { points ->
                        if (points.isNotEmpty()) drawTrack(points)
                    }
                }
            }
        }
    }

    // ─── Track drawing ────────────────────────────────────────────────────────

    private fun drawTrack(points: List<TrackPointEntity>) {
        val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
        val box = BoundingBox.fromGeoPoints(geoPoints)
        trackBoundingBox = box

        val polyline = Polyline(binding.trackMapFull).apply {
            setPoints(geoPoints)
            outlinePaint.apply {
                color = Color.parseColor("#2196F3")
                strokeWidth = 9f
                isAntiAlias = true
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }
            // Thinner outline/shadow for contrast on light maps
            infoWindow = null
        }

        // Start pin (green dot)
        val startMarker = Marker(binding.trackMapFull).apply {
            position = geoPoints.first()
            title = "Start"
            snippet = "Track start"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = resources.getDrawable(R.drawable.ic_start, null)
        }

        // End pin (flag)
        val endMarker = Marker(binding.trackMapFull).apply {
            position = geoPoints.last()
            title = "End"
            snippet = "Track end"
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = resources.getDrawable(R.drawable.ic_destination_marker, null)
        }

        binding.trackMapFull.overlays.apply {
            clear()
            add(RotationGestureOverlay(binding.trackMapFull).apply { isEnabled = true })
            add(ScaleBarOverlay(binding.trackMapFull).apply {
                setCentred(false)
                setScaleBarOffset(20, 20)
            })
            add(polyline)
            add(startMarker)
            add(endMarker)
        }

        binding.trackMapFull.post {
            binding.trackMapFull.zoomToBoundingBox(box, true, 80)
        }
        binding.trackMapFull.invalidate()
    }

    // ─── Layer picker ─────────────────────────────────────────────────────────

    private fun showLayerPicker() {
        // Build label list; prefix the active layer with a checkmark so the user
        // can tell at a glance which layer is currently loaded.
        val labels = MapLayers.ALL.mapIndexed { index, layer ->
            if (index == currentLayerIndex) "✓ ${layer.label}" else "   ${layer.label}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Map Layer")
            .setItems(labels) { _, which ->
                currentLayerIndex = which
                binding.trackMapFull.setTileSource(MapLayers.ALL[which].source)
                binding.trackMapFull.invalidate()
            }
            .show()
    }

    // ─── Full-screen helpers ──────────────────────────────────────────────────

    private fun enterFullScreen() {
        val window = activity?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitFullScreen() {
        val window = activity?.window ?: return
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onResume() {
        super.onResume()
        binding.trackMapFull.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.trackMapFull.onPause()
    }

    override fun onDestroyView() {
        exitFullScreen()
        super.onDestroyView()
        _binding = null
    }
}
