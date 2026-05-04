// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.waypoints

import android.os.Bundle
import android.view.*
import android.widget.EditText
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nineggps.R
import com.nineggps.data.db.entity.WaypointEntity
import com.nineggps.data.repository.TrackRepository
import com.nineggps.databinding.FragmentWaypointsBinding
import com.nineggps.databinding.ItemWaypointBinding
import com.nineggps.service.GpsTrackingService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class WaypointsViewModel @Inject constructor(
    private val repository: TrackRepository
) : ViewModel() {

    private val _filterFavorites = MutableStateFlow(false)
    val filterFavorites: StateFlow<Boolean> = _filterFavorites

    private val _searchQuery = MutableStateFlow("")

    val waypoints: StateFlow<List<WaypointEntity>> = combine(
        repository.getAllWaypoints(),
        _filterFavorites,
        _searchQuery
    ) { all, favOnly, query ->
        var result = if (favOnly) all.filter { it.isFavorite } else all
        if (query.isNotBlank()) {
            result = result.filter {
                it.name.contains(query, ignoreCase = true) ||
                it.description.contains(query, ignoreCase = true)
            }
        }
        result
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilterFavorites(value: Boolean) { _filterFavorites.value = value }
    fun setSearchQuery(q: String) { _searchQuery.value = q }

    fun addWaypoint(name: String, desc: String = "", category: String = "GENERAL") {
        viewModelScope.launch {
            val loc = GpsTrackingService.gpsState.value.location ?: return@launch
            repository.insertWaypoint(
                WaypointEntity(
                    name = name,
                    description = desc,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitude = loc.altitude,
                    category = category
                )
            )
        }
    }

    fun addWaypointAt(
        name: String, lat: Double, lon: Double,
        alt: Double = 0.0, desc: String = "", category: String = "GENERAL"
    ) {
        viewModelScope.launch {
            repository.insertWaypoint(
                WaypointEntity(
                    name = name, description = desc,
                    latitude = lat, longitude = lon, altitude = alt, category = category
                )
            )
        }
    }

    fun toggleFavorite(waypoint: WaypointEntity) = viewModelScope.launch {
        repository.setFavorite(waypoint.id, !waypoint.isFavorite)
    }

    fun deleteWaypoint(waypoint: WaypointEntity) = viewModelScope.launch {
        repository.deleteWaypoint(waypoint)
    }

    fun updateWaypoint(waypoint: WaypointEntity) = viewModelScope.launch {
        repository.updateWaypoint(waypoint)
    }
}

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class WaypointsFragment : Fragment(R.layout.fragment_waypoints) {

    private var _binding: FragmentWaypointsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: WaypointsViewModel by viewModels()
    private lateinit var adapter: WaypointsAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentWaypointsBinding.bind(view)

        adapter = WaypointsAdapter(
            onFavoriteClick = { viewModel.toggleFavorite(it) },
            onDeleteClick = { wp ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Waypoint")
                    .setMessage("Delete '${wp.name}'?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteWaypoint(wp) }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onNavigateClick = { wp ->
                // Bug fix: was a TODO stub — now navigates back to the map and
                // triggers routing to the selected waypoint.
                val args = android.os.Bundle().apply {
                    putString("waypointName", wp.name)
                    putFloat("waypointLat",  wp.latitude.toFloat())
                    putFloat("waypointLon",  wp.longitude.toFloat())
                }
                findNavController().navigate(R.id.action_waypoints_to_map, args)
            }
        )

        binding.recyclerWaypoints.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerWaypoints.adapter = adapter

        binding.fabAddWaypoint.setOnClickListener { showAddWaypointDialog() }

        binding.chipFavorites.setOnCheckedChangeListener { _, checked ->
            viewModel.setFilterFavorites(checked)
        }

        binding.searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                viewModel.setSearchQuery(q ?: "")
                return true
            }
        })

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.waypoints.collectLatest { wps ->
                    adapter.submitList(wps)
                    binding.tvEmpty.isVisible = wps.isEmpty()
                    binding.tvCount.text = "${wps.size} waypoints"
                }
            }
        }
    }

    private fun showAddWaypointDialog() {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_waypoint, null)
        val etName = dialogView.findViewById<EditText>(R.id.etName)
        val etDesc = dialogView.findViewById<EditText>(R.id.etDescription)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Waypoint")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().ifBlank { "Waypoint" }
                val desc = etDesc.text.toString()
                viewModel.addWaypoint(name, desc)
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

class WaypointsAdapter(
    private val onFavoriteClick: (WaypointEntity) -> Unit,
    private val onDeleteClick: (WaypointEntity) -> Unit,
    private val onNavigateClick: (WaypointEntity) -> Unit
) : ListAdapter<WaypointEntity, WaypointsAdapter.WaypointViewHolder>(WaypointDiff()) {

    inner class WaypointViewHolder(private val binding: ItemWaypointBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(wp: WaypointEntity) {
            binding.tvName.text = wp.name
            binding.tvDescription.text = wp.description.ifBlank { "${String.format("%.5f", wp.latitude)}, ${String.format("%.5f", wp.longitude)}" }
            binding.tvCategory.text = wp.category
            binding.tvAlt.text = "${wp.altitude.toInt()} m"
            binding.ivFavorite.setImageResource(
                if (wp.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline
            )
            binding.ivFavorite.setOnClickListener { onFavoriteClick(wp) }
            binding.btnDelete.setOnClickListener { onDeleteClick(wp) }
            binding.btnNavigate.setOnClickListener { onNavigateClick(wp) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WaypointViewHolder {
        val binding = ItemWaypointBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return WaypointViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WaypointViewHolder, position: Int) = holder.bind(getItem(position))
}

class WaypointDiff : DiffUtil.ItemCallback<WaypointEntity>() {
    override fun areItemsTheSame(a: WaypointEntity, b: WaypointEntity) = a.id == b.id
    override fun areContentsTheSame(a: WaypointEntity, b: WaypointEntity) = a == b
}
