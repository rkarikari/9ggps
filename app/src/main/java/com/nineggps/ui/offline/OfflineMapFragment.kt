// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.offline

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.ListAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nineggps.R
import com.nineggps.data.db.dao.OfflineRegionDao
import com.nineggps.data.db.entity.OfflineRegionEntity
import com.nineggps.databinding.FragmentOfflineMapBinding
import com.nineggps.databinding.ItemOfflineRegionBinding
import com.nineggps.utils.OfflineMapManager
import com.nineggps.utils.OrganicMapRegions
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class OfflineMapViewModel @Inject constructor(
    private val offlineRegionDao: OfflineRegionDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(val progress: OfflineMapManager.DownloadProgress) : DownloadState()
        data class Done(val region: OfflineRegionEntity) : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    val regions: StateFlow<List<OfflineRegionEntity>> = offlineRegionDao.getAllRegions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private var downloadJob: Job? = null

    fun downloadRegion(
        name: String,
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double,
        minZoom: Int, maxZoom: Int
    ) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            try {
                val box = BoundingBox(maxLat, maxLon, minLat, minLon)
                _downloadState.value = DownloadState.Downloading(OfflineMapManager.DownloadProgress(0, 1))
                OfflineMapManager.downloadRegion(
                    context = context,
                    box = box,
                    minZoom = minZoom,
                    maxZoom = maxZoom,
                    onProgress = { progress ->
                        _downloadState.value = DownloadState.Downloading(progress)
                    },
                    onComplete = { region ->
                        val namedRegion = region.copy(name = name)
                        viewModelScope.launch {
                            offlineRegionDao.insertRegion(namedRegion)
                            _downloadState.value = DownloadState.Done(namedRegion)
                        }
                    }
                )
            } catch (e: Exception) {
                _downloadState.value = DownloadState.Error(e.message ?: "Download failed")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Idle
    }

    fun deleteRegion(region: OfflineRegionEntity) {
        viewModelScope.launch { offlineRegionDao.deleteRegion(region) }
    }

    fun resetState() {
        _downloadState.value = DownloadState.Idle
    }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────

class OfflineRegionAdapter(
    private val onDelete: (OfflineRegionEntity) -> Unit
) : ListAdapter<OfflineRegionEntity, OfflineRegionAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemOfflineRegionBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOfflineRegionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val region = getItem(position)
        with(holder.binding) {
            tvName.text = region.name
            tvBounds.text = "%.3f,%.3f – %.3f,%.3f".format(
                region.minLat, region.minLon, region.maxLat, region.maxLon
            )
            tvZoom.text = "Zoom %d – %d".format(region.minZoom, region.maxZoom)
            tvTiles.text = "${region.tileCount} tiles"
            val fmt = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
            tvDate.text = fmt.format(Date(region.downloadedAt))
            val ctx = holder.itemView.context
            if (region.isComplete) {
                tvStatus.text = ctx.getString(R.string.offline_status_complete)
                tvStatus.setTextColor(ctx.getColor(R.color.accent_green))
            } else {
                tvStatus.text = ctx.getString(R.string.offline_status_incomplete)
                tvStatus.setTextColor(ctx.getColor(R.color.accent_orange))
            }
            btnDelete.setOnClickListener { onDelete(region) }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<OfflineRegionEntity>() {
            override fun areItemsTheSame(a: OfflineRegionEntity, b: OfflineRegionEntity) = a.id == b.id
            override fun areContentsTheSame(a: OfflineRegionEntity, b: OfflineRegionEntity) = a == b
        }
    }
}

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class OfflineMapFragment : Fragment() {

    private var _binding: FragmentOfflineMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OfflineMapViewModel by viewModels()
    private lateinit var regionAdapter: OfflineRegionAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOfflineMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupMap()
        setupZoomControls()
        setupDownloadButton()
        setupOrganicMapsButton()
        setupRegionsList()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
    }

    private fun setupMap() {
        Configuration.getInstance().load(
            requireContext(),
            requireContext().getSharedPreferences("osmdroid", 0)
        )
        with(binding.mapView) {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
            controller.setZoom(10.0)
            controller.setCenter(GeoPoint(0.0, 20.0))
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    updateTileEstimate(); return false
                }
                override fun onZoom(event: ZoomEvent?): Boolean {
                    updateTileEstimate(); return false
                }
            })
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN,
                    MotionEvent.ACTION_MOVE ->
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_CANCEL ->
                        v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        updateTileEstimate()
    }

    private fun setupZoomControls() {
        binding.seekMinZoom.max = 18
        binding.seekMinZoom.progress = 9
        binding.seekMaxZoom.max = 18
        binding.seekMaxZoom.progress = 15
        updateZoomLabels()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    if (seekBar === binding.seekMinZoom && getMinZoom() > getMaxZoom()) {
                        binding.seekMaxZoom.progress = binding.seekMinZoom.progress
                    } else if (seekBar === binding.seekMaxZoom && getMaxZoom() < getMinZoom()) {
                        binding.seekMinZoom.progress = binding.seekMaxZoom.progress
                    }
                }
                updateZoomLabels()
                updateTileEstimate()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        binding.seekMinZoom.setOnSeekBarChangeListener(listener)
        binding.seekMaxZoom.setOnSeekBarChangeListener(listener)
    }

    private fun getMinZoom() = binding.seekMinZoom.progress + 1
    private fun getMaxZoom() = binding.seekMaxZoom.progress + 1

    private fun updateZoomLabels() {
        binding.tvMinZoom.text = getMinZoom().toString()
        binding.tvMaxZoom.text = getMaxZoom().toString()
    }

    private fun updateTileEstimate() {
        val box = binding.mapView.boundingBox ?: return
        val count = OfflineMapManager.estimateTileCount(box, getMinZoom(), getMaxZoom())
        binding.tvTileEstimate.text = getString(R.string.offline_tile_estimate, count)
    }

    private fun setupDownloadButton() {
        binding.btnDownload.setOnClickListener {
            val name = binding.etRegionName.text.toString().trim()
                .ifBlank { getString(R.string.offline_region_default_name, System.currentTimeMillis()) }
            val box = binding.mapView.boundingBox
            val minZoom = getMinZoom()
            val maxZoom = getMaxZoom()
            val estimate = OfflineMapManager.estimateTileCount(box, minZoom, maxZoom)
            if (estimate > 5000) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.offline_large_area_title))
                    .setMessage(getString(R.string.offline_large_area_message, estimate))
                    .setPositiveButton(getString(R.string.offline_btn_download)) { _, _ ->
                        startDownload(name, box, minZoom, maxZoom)
                    }
                    .setNegativeButton(getString(android.R.string.cancel), null)
                    .show()
            } else {
                startDownload(name, box, minZoom, maxZoom)
            }
        }
        binding.btnCancel.setOnClickListener { viewModel.cancelDownload() }
    }

    // ─── Organic Maps Region Browser ─────────────────────────────────────────

    /**
     * Two-step continent → region picker that mirrors the Organic Maps offline
     * download UX. Selecting a region zooms the preview map to its bounding box
     * and offers an immediate download or lets the user adjust zoom levels first.
     */
    private fun setupOrganicMapsButton() {
        binding.btnBrowseRegions.setOnClickListener { showContinentPicker() }
    }

    private fun showContinentPicker() {
        val continents = OrganicMapRegions.continents
        val labels = continents.map { "${it.emoji}  ${it.name}" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.offline_organic_select_continent))
            .setItems(labels) { _, which -> showRegionPicker(continents[which].name) }
            .show()
    }

    private fun showRegionPicker(continent: String) {
        val regions = OrganicMapRegions.countriesFor(continent)
        if (regions.isEmpty()) {
            Snackbar.make(binding.root, "No regions available for $continent", Snackbar.LENGTH_SHORT).show()
            return
        }
        val labels = regions.map { r ->
            "  ${r.name}   (${OrganicMapRegions.sizeLabel(r.tileSizeHint)})"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("$continent – select region")
            .setNegativeButton("← Back") { _, _ -> showContinentPicker() }
            .setItems(labels) { _, which -> loadRegionOnMap(regions[which]) }
            .show()
    }

    /**
     * Pans the preview map to the chosen region's bounding box, pre-fills the
     * region-name field and offers an immediate download confirmation dialog.
     */
    private fun loadRegionOnMap(region: OrganicMapRegions.Region) {
        binding.mapView.post {
            binding.mapView.zoomToBoundingBox(region.box, true, 32)
        }
        binding.etRegionName.setText(region.name)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.offline_organic_region_title, region.name))
            .setMessage(
                getString(
                    R.string.offline_organic_region_message,
                    OrganicMapRegions.sizeLabel(region.tileSizeHint),
                    getMinZoom(), getMaxZoom()
                )
            )
            .setPositiveButton(getString(R.string.offline_btn_download)) { _, _ ->
                val estimate = OfflineMapManager.estimateTileCount(region.box, getMinZoom(), getMaxZoom())
                if (estimate > 5000) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.offline_large_area_title))
                        .setMessage(getString(R.string.offline_large_area_message, estimate))
                        .setPositiveButton(getString(R.string.offline_btn_download)) { _, _ ->
                            startDownload(region.name, region.box, getMinZoom(), getMaxZoom())
                        }
                        .setNegativeButton(getString(android.R.string.cancel), null)
                        .show()
                } else {
                    startDownload(region.name, region.box, getMinZoom(), getMaxZoom())
                }
            }
            .setNegativeButton(getString(R.string.offline_organic_adjust_zoom), null)
            .show()
    }

    // ─── Download helpers ─────────────────────────────────────────────────────

    private fun startDownload(name: String, box: BoundingBox, minZoom: Int, maxZoom: Int) {
        viewModel.downloadRegion(
            name = name,
            minLat = box.latSouth, maxLat = box.latNorth,
            minLon = box.lonWest,  maxLon = box.lonEast,
            minZoom = minZoom,     maxZoom = maxZoom
        )
    }

    // ─── Saved regions list ───────────────────────────────────────────────────

    private fun setupRegionsList() {
        regionAdapter = OfflineRegionAdapter { region ->
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.offline_delete_title))
                .setMessage(getString(R.string.offline_delete_message, region.name))
                .setPositiveButton(getString(R.string.offline_btn_delete)) { _, _ ->
                    viewModel.deleteRegion(region)
                }
                .setNegativeButton(getString(android.R.string.cancel), null)
                .show()
        }
        binding.recyclerRegions.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = regionAdapter
            isNestedScrollingEnabled = false
        }
    }

    // ─── ViewModel observation ────────────────────────────────────────────────

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.regions.collect { regions ->
                        regionAdapter.submitList(regions)
                        binding.tvNoRegions.isVisible = regions.isEmpty()
                        binding.recyclerRegions.isVisible = regions.isNotEmpty()
                    }
                }
                launch {
                    viewModel.downloadState.collect { state ->
                        when (state) {
                            is OfflineMapViewModel.DownloadState.Idle -> setIdleUi()
                            is OfflineMapViewModel.DownloadState.Downloading -> setDownloadingUi(state.progress)
                            is OfflineMapViewModel.DownloadState.Done -> {
                                setIdleUi()
                                Snackbar.make(
                                    binding.root,
                                    getString(R.string.offline_download_complete, state.region.name),
                                    Snackbar.LENGTH_SHORT
                                ).show()
                                viewModel.resetState()
                            }
                            is OfflineMapViewModel.DownloadState.Error -> {
                                setIdleUi()
                                Snackbar.make(
                                    binding.root,
                                    getString(R.string.offline_download_error, state.message),
                                    Snackbar.LENGTH_LONG
                                ).show()
                                viewModel.resetState()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setIdleUi() {
        binding.progressBar.isVisible = false
        binding.tvProgress.isVisible = false
        binding.btnDownload.isEnabled = true
        binding.btnCancel.isVisible = false
        binding.downloadControls.isVisible = true
    }

    private fun setDownloadingUi(progress: OfflineMapManager.DownloadProgress) {
        binding.downloadControls.isVisible = false
        binding.progressBar.isVisible = true
        binding.tvProgress.isVisible = true
        binding.btnDownload.isEnabled = false
        binding.btnCancel.isVisible = true
        binding.progressBar.max = maxOf(progress.total, 1)
        binding.progressBar.progress = progress.downloaded
        binding.tvProgress.text = getString(
            R.string.offline_download_progress,
            progress.downloaded, progress.total
        )
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
