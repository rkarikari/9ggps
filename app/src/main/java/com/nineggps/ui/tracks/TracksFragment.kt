// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.tracks

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nineggps.R
import com.nineggps.data.db.entity.TrackEntity
import com.nineggps.data.db.entity.TrackPointEntity
import com.nineggps.data.repository.TrackRepository
import com.nineggps.databinding.FragmentTracksBinding
import com.nineggps.databinding.ItemTrackBinding
import com.nineggps.utils.NineGUtils
import com.nineggps.utils.NineGpxExporter
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.location.Location

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class TracksViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    val tracks: StateFlow<List<TrackEntity>> = repository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _totalStats = MutableStateFlow(Triple(0, 0.0, 0L))
    val totalStats: StateFlow<Triple<Int, Double, Long>> = _totalStats

    sealed class ImportState {
        object Idle : ImportState()
        object Importing : ImportState()
        data class Success(val trackName: String) : ImportState()
        data class Error(val message: String) : ImportState()
    }

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState

    init {
        viewModelScope.launch {
            _totalStats.value = repository.getTotalStats()
        }
    }

    fun deleteTrack(track: TrackEntity) = viewModelScope.launch {
        repository.deleteTrack(track)
        _totalStats.value = repository.getTotalStats()
    }

    fun importTrack(context: Context, uri: Uri) = viewModelScope.launch {
        _importState.value = ImportState.Importing
        try {
            val result = NineGpxExporter.importGpx(context, uri)
                .takeIf { it != null && it.points.isNotEmpty() }
                ?: NineGpxExporter.importKml(context, uri)

            if (result == null || result.points.isEmpty()) {
                _importState.value = ImportState.Error("No track points found in file")
                return@launch
            }

            // ── Build TrackEntity from parsed data ────────────────────────────
            val pts = result.points
            var dist = 0.0
            var minAlt = pts.firstOrNull()?.alt ?: 0.0
            var maxAlt = minAlt
            var elevGain = 0.0
            var elevLoss = 0.0
            var maxSpeed = 0f
            val startTime = if (pts.first().timestamp > 0) pts.first().timestamp
                            else System.currentTimeMillis()
            val endTime   = if (pts.last().timestamp > 0) pts.last().timestamp
                            else startTime

            pts.forEachIndexed { i, pt ->
                minAlt = minOf(minAlt, pt.alt)
                maxAlt = maxOf(maxAlt, pt.alt)
                maxSpeed = maxOf(maxSpeed, pt.speed)
                if (i > 0) {
                    val prev = pts[i - 1]
                    val res = FloatArray(1)
                    Location.distanceBetween(prev.lat, prev.lon, pt.lat, pt.lon, res)
                    dist += res[0]
                    val dElev = pt.alt - prev.alt
                    if (dElev > 0) elevGain += dElev else elevLoss += -dElev
                }
            }

            val duration = if (endTime > startTime) endTime - startTime else 0L
            val avgSpeedKmh = if (duration > 0) (dist / (duration / 1000.0)) * 3.6f else 0f

            val track = TrackEntity(
                name          = result.trackName,
                startTime     = startTime,
                endTime       = endTime,
                distance      = dist,
                duration      = duration,
                avgSpeed      = avgSpeedKmh.toFloat(),
                // Per-point maxSpeed (m/s → km/h); fall back to track-level value from <extensions>
                // for files that omit per-point speed data (e.g. KML, third-party GPX).
                maxSpeed      = if (maxSpeed > 0f) maxSpeed * 3.6f else result.trackMaxSpeed,
                minAltitude   = minAlt,
                maxAltitude   = maxAlt,
                elevationGain = elevGain,
                elevationLoss = elevLoss,
                calories      = result.calories,
                pointCount    = pts.size,
                activityType  = "IMPORTED"
            )

            val trackId = repository.insertTrack(track)

            // ── Insert all track points ───────────────────────────────────────
            val entities = pts.map { pt ->
                TrackPointEntity(
                    trackId   = trackId,
                    latitude  = pt.lat,
                    longitude = pt.lon,
                    altitude  = pt.alt,
                    speed     = pt.speed,          // already in m/s from <trkpt><extensions><speed>
                    bearing   = 0f,
                    accuracy  = 5f,
                    timestamp = pt.timestamp
                )
            }
            repository.insertTrackPoints(entities)

            _totalStats.value = repository.getTotalStats()
            _importState.value = ImportState.Success(result.trackName)

        } catch (e: Exception) {
            _importState.value = ImportState.Error(e.localizedMessage ?: "Import failed")
        }
    }

    fun resetImportState() { _importState.value = ImportState.Idle }
}

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class TracksFragment : Fragment(R.layout.fragment_tracks) {

    private var _binding: FragmentTracksBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TracksViewModel by viewModels()
    private lateinit var adapter: TracksAdapter

    // GPX / KML file picker — accepts common GPS file MIME types + wildcard fallback
    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            viewModel.importTrack(requireContext(), uri)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentTracksBinding.bind(view)

        adapter = TracksAdapter(
            onTrackClick = { track ->
                val action = TracksFragmentDirections.actionTracksToTrackDetail(track.id)
                findNavController().navigate(action)
            },
            onTrackDelete = { track ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Track")
                    .setMessage("Delete '${track.name}'? This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteTrack(track) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.recyclerTracks.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerTracks.adapter = adapter

        // Shrink FAB on scroll, extend when scrolling back to top
        binding.recyclerTracks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) binding.fabImportTrack.shrink() else binding.fabImportTrack.extend()
            }
        })

        // Import FAB
        binding.fabImportTrack.setOnClickListener { showImportOptions() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.tracks.collectLatest { tracks ->
                        adapter.submitList(tracks)
                        binding.tvEmptyState.isVisible = tracks.isEmpty()
                        binding.recyclerTracks.isVisible = tracks.isNotEmpty()
                    }
                }

                launch {
                    viewModel.totalStats.collectLatest { (count, dist, dur) ->
                        binding.tvTotalTracks.text = "$count tracks"
                        binding.tvTotalDistance.text = NineGUtils.formatDistance(dist)
                        binding.tvTotalTime.text = NineGUtils.formatDuration(dur)
                    }
                }

                launch {
                    viewModel.importState.collectLatest { state ->
                        when (state) {
                            is TracksViewModel.ImportState.Importing ->
                                Snackbar.make(binding.root, "Importing track…", Snackbar.LENGTH_INDEFINITE).show()
                            is TracksViewModel.ImportState.Success -> {
                                Snackbar.make(binding.root, "✓ Imported: ${state.trackName}", Snackbar.LENGTH_LONG).show()
                                viewModel.resetImportState()
                            }
                            is TracksViewModel.ImportState.Error -> {
                                Snackbar.make(binding.root, "Import failed: ${state.message}", Snackbar.LENGTH_LONG).show()
                                viewModel.resetImportState()
                            }
                            else -> { /* Idle */ }
                        }
                    }
                }
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun showImportOptions() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Import Track")
            .setMessage("Pick a GPX or KML file from any GPS app, file manager, or cloud storage.")
            .setPositiveButton("Choose file") { _, _ ->
                importLauncher.launch(
                    arrayOf(
                        "application/gpx+xml",
                        "application/vnd.google-earth.kml+xml",
                        "text/xml",
                        "application/xml",
                        "*/*"
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─── Adapter ──────────────────────────────────────────────────────────────────

class TracksAdapter(
    private val onTrackClick: (TrackEntity) -> Unit,
    private val onTrackDelete: (TrackEntity) -> Unit
) : ListAdapter<TrackEntity, TracksAdapter.TrackViewHolder>(TrackDiffCallback()) {

    inner class TrackViewHolder(private val binding: ItemTrackBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(track: TrackEntity) {
            binding.tvTrackName.text = track.name
            binding.tvTrackDate.text = java.text.SimpleDateFormat(
                "MMM dd, yyyy HH:mm", java.util.Locale.getDefault()
            ).format(java.util.Date(track.startTime))
            binding.tvTrackDistance.text = NineGUtils.formatDistance(track.distance)
            binding.tvTrackDuration.text = NineGUtils.formatDuration(track.duration)
            binding.tvTrackAvgSpeed.text = "${track.avgSpeed.toInt()} km/h avg"
            binding.tvTrackMaxSpeed.text = "${track.maxSpeed.toInt()} km/h max"
            binding.tvTrackElevation.text = "+${NineGUtils.formatAltitude(track.elevationGain)}"
            binding.tvTrackType.text = track.activityType
            binding.tvTrackPoints.text = "${track.pointCount} pts"

            binding.root.setOnClickListener { onTrackClick(track) }
            binding.btnDelete.setOnClickListener { onTrackDelete(track) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val binding = ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

class TrackDiffCallback : DiffUtil.ItemCallback<TrackEntity>() {
    override fun areItemsTheSame(oldItem: TrackEntity, newItem: TrackEntity) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: TrackEntity, newItem: TrackEntity) = oldItem == newItem
}
