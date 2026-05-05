// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.speedcamera

import android.os.Bundle
import android.view.*
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nineggps.R
import com.nineggps.data.db.entity.SpeedCameraEntity
import com.nineggps.data.prefs.UserPreferences
import com.nineggps.data.repository.SpeedCameraRepository
import com.nineggps.databinding.FragmentSpeedCameraBinding
import com.nineggps.databinding.ItemSpeedCameraBinding
import com.nineggps.service.GpsTrackingService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class SpeedCameraViewModel @Inject constructor(
    private val repository: SpeedCameraRepository,
    private val prefs: UserPreferences
) : ViewModel() {

    val cameras: StateFlow<List<SpeedCameraEntity>> = repository.getAllCameras()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val showOnMap: StateFlow<Boolean> = prefs.showSpeedCameras
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Selected type filter; null = show all */
    private val _filterType = MutableStateFlow<String?>(null)
    val filterType: StateFlow<String?> = _filterType

    val filteredCameras: StateFlow<List<SpeedCameraEntity>> =
        combine(cameras, _filterType) { list, filter ->
            if (filter == null) list else list.filter { it.type == filter }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilterType(type: String?) { _filterType.value = type }

    fun toggleShowOnMap() {
        viewModelScope.launch {
            prefs.setShowSpeedCameras(!showOnMap.value)
        }
    }

    fun addCamera(
        lat: Double, lon: Double,
        speedLimit: Int, type: String,
        bearing: Double, road: String
    ) {
        viewModelScope.launch {
            repository.insertCamera(
                SpeedCameraEntity(
                    latitude = lat, longitude = lon,
                    speedLimit = speedLimit, type = type,
                    bearing = bearing, road = road,
                    isVerified = false
                )
            )
            _message.value = "Speed camera added"
        }
    }

    fun deleteCamera(camera: SpeedCameraEntity) {
        viewModelScope.launch {
            repository.deleteCamera(camera)
            _message.value = "Camera removed"
        }
    }

    fun deleteAll() {
        viewModelScope.launch {
            repository.deleteAll()
            _message.value = "All cameras cleared"
        }
    }

    /**
     * Fetches nearby speed cameras from OpenStreetMap Overpass API and
     * saves them to the local database.
     */
    fun fetchNearby() {
        val loc = GpsTrackingService.gpsState.value.location
        if (loc == null) {
            _message.value = "No GPS fix yet — cannot fetch nearby cameras"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val count = repository.fetchFromOverpass(loc.latitude, loc.longitude, radiusKm = 10)
                _message.value = if (count > 0) "Imported $count cameras from OpenStreetMap"
                                 else "No new cameras found nearby"
            } catch (e: Exception) {
                _message.value = "Import failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearMessage() { _message.value = null }
}

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class SpeedCameraFragment : Fragment(R.layout.fragment_speed_camera) {

    private var _binding: FragmentSpeedCameraBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SpeedCameraViewModel by viewModels()

    private lateinit var adapter: SpeedCameraAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentSpeedCameraBinding.bind(view)

        setupToolbar()
        setupRecycler()
        setupFilters()
        setupFab()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        // "Show on map" toggle in the toolbar menu
        binding.toolbar.inflateMenu(R.menu.menu_speed_camera)
        val switchItem = binding.toolbar.menu.findItem(R.id.action_show_on_map)
        val sw = switchItem?.actionView?.findViewById<SwitchCompat>(R.id.switchShowOnMap)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.showOnMap.collect { enabled ->
                    sw?.isChecked = enabled
                }
            }
        }
        sw?.setOnClickListener { viewModel.toggleShowOnMap() }

        // Overflow menu
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import_osm -> { viewModel.fetchNearby(); true }
                R.id.action_clear_all  -> { confirmClearAll(); true }
                else -> false
            }
        }
    }

    private fun setupRecycler() {
        adapter = SpeedCameraAdapter(
            onDelete = { camera -> viewModel.deleteCamera(camera) }
        )
        binding.recyclerCameras.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCameras.adapter = adapter
    }

    private fun setupFilters() {
        // Chip group filter
        binding.chipAll.setOnClickListener    { viewModel.setFilterType(null) }
        binding.chipFixed.setOnClickListener  { viewModel.setFilterType("FIXED") }
        binding.chipMobile.setOnClickListener { viewModel.setFilterType("MOBILE") }
        binding.chipAverage.setOnClickListener{ viewModel.setFilterType("AVERAGE") }
        binding.chipRedlight.setOnClickListener{ viewModel.setFilterType("REDLIGHT") }
    }

    private fun setupFab() {
        binding.fabAddCamera.setOnClickListener { showAddCameraDialog() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.filteredCameras.collect { list ->
                        adapter.submitList(list)
                        binding.tvEmpty.isVisible = list.isEmpty()
                        binding.tvCount.text = "${viewModel.cameras.value.size} cameras total"
                    }
                }

                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressBar.isVisible = loading
                        binding.fabAddCamera.isEnabled = !loading
                    }
                }

                launch {
                    viewModel.message.collect { msg ->
                        msg ?: return@collect
                        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                        viewModel.clearMessage()
                    }
                }

                launch {
                    viewModel.filterType.collect { type ->
                        // Keep chip group in sync
                        val chipId = when (type) {
                            "FIXED"    -> R.id.chipFixed
                            "MOBILE"   -> R.id.chipMobile
                            "AVERAGE"  -> R.id.chipAverage
                            "REDLIGHT" -> R.id.chipRedlight
                            else       -> R.id.chipAll
                        }
                        binding.chipGroup.check(chipId)
                    }
                }
            }
        }
    }

    // ─── Add Camera Dialog ────────────────────────────────────────────────────

    private fun showAddCameraDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_speed_camera, null)

        val etLat   = dialogView.findViewById<TextInputEditText>(R.id.etLat)
        val etLon   = dialogView.findViewById<TextInputEditText>(R.id.etLon)
        val etLimit = dialogView.findViewById<TextInputEditText>(R.id.etSpeedLimit)
        val etRoad  = dialogView.findViewById<TextInputEditText>(R.id.etRoad)
        val spinnerType = dialogView.findViewById<Spinner>(R.id.spinnerCameraType)
        val btnUseCurrentLocation = dialogView.findViewById<Button>(R.id.btnUseCurrentLocation)

        // Pre-fill with current GPS location
        val loc = GpsTrackingService.gpsState.value.location
        loc?.let {
            etLat.setText(String.format("%.6f", it.latitude))
            etLon.setText(String.format("%.6f", it.longitude))
        }

        // Camera type spinner
        val types = arrayOf("FIXED", "MOBILE", "AVERAGE", "REDLIGHT")
        spinnerType.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, types)

        btnUseCurrentLocation.setOnClickListener {
            GpsTrackingService.gpsState.value.location?.let { l ->
                etLat.setText(String.format("%.6f", l.latitude))
                etLon.setText(String.format("%.6f", l.longitude))
            } ?: Snackbar.make(binding.root, "No GPS fix available", Snackbar.LENGTH_SHORT).show()
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Speed Camera")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val lat   = etLat.text.toString().toDoubleOrNull()
                val lon   = etLon.text.toString().toDoubleOrNull()
                val limit = etLimit.text.toString().toIntOrNull() ?: 0
                val road  = etRoad.text.toString().trim()
                val type  = types[spinnerType.selectedItemPosition]

                if (lat == null || lon == null) {
                    Snackbar.make(binding.root, "Invalid coordinates", Snackbar.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.addCamera(lat, lon, limit, type, -1.0, road)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear All Cameras?")
            .setMessage("This will permanently delete all ${viewModel.cameras.value.size} speed cameras from the local database.")
            .setPositiveButton("Delete All") { _, _ -> viewModel.deleteAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ─── RecyclerView Adapter ─────────────────────────────────────────────────────

private class SpeedCameraAdapter(
    private val onDelete: (SpeedCameraEntity) -> Unit
) : ListAdapter<SpeedCameraEntity, SpeedCameraAdapter.VH>(DiffCallback) {

    inner class VH(private val binding: ItemSpeedCameraBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(camera: SpeedCameraEntity) {
            // Speed limit
            binding.tvSpeedLimit.text = if (camera.speedLimit > 0)
                "${camera.speedLimit} km/h" else "Unknown limit"

            // Type badge
            binding.tvType.text = camera.type
            binding.tvType.setBackgroundResource(typeBackground(camera.type))

            // Location
            binding.tvCoordinates.text =
                "${String.format("%.5f", camera.latitude)}, " +
                "${String.format("%.5f", camera.longitude)}"

            // Road name
            binding.tvRoad.text = camera.road.ifBlank { "Road unknown" }
            binding.tvRoad.isVisible = true

            // Direction
            binding.tvBearing.text = if (camera.bearing >= 0)
                "Direction: ${camera.bearing.toInt()}°"
            else "All directions"

            // Verified badge
            binding.tvVerified.isVisible = camera.isVerified

            // Delete
            binding.btnDelete.setOnClickListener { onDelete(camera) }
        }

        private fun typeBackground(type: String) = when (type) {
            "MOBILE"   -> R.drawable.bg_badge_selected
            "AVERAGE"  -> R.drawable.bg_chip
            "REDLIGHT" -> R.drawable.bg_badge_selected
            else       -> R.drawable.bg_badge_unselected  // FIXED
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSpeedCameraBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    private companion object DiffCallback : DiffUtil.ItemCallback<SpeedCameraEntity>() {
        override fun areItemsTheSame(a: SpeedCameraEntity, b: SpeedCameraEntity) = a.id == b.id
        override fun areContentsTheSame(a: SpeedCameraEntity, b: SpeedCameraEntity) = a == b
    }
}
