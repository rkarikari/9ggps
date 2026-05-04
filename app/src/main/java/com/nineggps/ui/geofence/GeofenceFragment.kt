// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.geofence

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.ListAdapter
import com.google.android.gms.location.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nineggps.R
import com.nineggps.data.db.entity.GeofenceEntity
import com.nineggps.data.db.dao.GeofenceDao
import com.nineggps.databinding.FragmentGeofenceBinding
import com.nineggps.databinding.ItemGeofenceBinding
import com.nineggps.receiver.GeofenceReceiver
import com.nineggps.service.GpsTrackingService
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class GeofenceViewModel @Inject constructor(
    private val geofenceDao: GeofenceDao,
    private val geofencingClient: GeofencingClient
) : ViewModel() {

    val geofences: StateFlow<List<GeofenceEntity>> = geofenceDao.getAllGeofences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addGeofence(name: String, lat: Double, lon: Double, radius: Double,
                    onEnter: Boolean, onExit: Boolean) {
        viewModelScope.launch {
            val id = geofenceDao.insertGeofence(
                GeofenceEntity(
                    name = name,
                    latitude = lat,
                    longitude = lon,
                    radius = radius,
                    triggerOnEnter = onEnter,
                    triggerOnExit = onExit
                )
            )
            registerGeofenceWithSystem(id.toString(), lat, lon, radius, onEnter, onExit)
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerGeofenceWithSystem(
        id: String, lat: Double, lon: Double, radius: Double,
        onEnter: Boolean, onExit: Boolean
    ) {
        val transitions = mutableListOf<Int>()
        if (onEnter) transitions.add(Geofence.GEOFENCE_TRANSITION_ENTER)
        if (onExit)  transitions.add(Geofence.GEOFENCE_TRANSITION_EXIT)
        val transitionFlags = transitions.reduceOrNull { a, b -> a or b } ?: return

        val geofence = Geofence.Builder()
            .setRequestId(id)
            .setCircularRegion(lat, lon, radius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(transitionFlags)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        // PendingIntent would be registered here
    }

    fun toggleGeofence(geofence: GeofenceEntity) = viewModelScope.launch {
        geofenceDao.setGeofenceActive(geofence.id, !geofence.isActive)
    }

    fun deleteGeofence(geofence: GeofenceEntity) = viewModelScope.launch {
        geofencingClient.removeGeofences(listOf(geofence.id.toString()))
        geofenceDao.deleteGeofence(geofence)
    }
}

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class GeofenceFragment : Fragment(R.layout.fragment_geofence) {

    private var _binding: FragmentGeofenceBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GeofenceViewModel by viewModels()
    private lateinit var adapter: GeofenceAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentGeofenceBinding.bind(view)

        adapter = GeofenceAdapter(
            onToggle = { viewModel.toggleGeofence(it) },
            onDelete = { gf ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete Geofence")
                    .setMessage("Delete '${gf.name}'?")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteGeofence(gf) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.recyclerGeofences.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerGeofences.adapter = adapter
        binding.fabAddGeofence.setOnClickListener { showAddGeofenceDialog() }
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.geofences.collectLatest { list ->
                    adapter.submitList(list)
                    binding.tvEmpty.isVisible = list.isEmpty()
                }
            }
        }
    }

    private fun showAddGeofenceDialog() {
        val loc = GpsTrackingService.gpsState.value.location
        val inflater = LayoutInflater.from(requireContext())
        val dv = inflater.inflate(R.layout.dialog_add_geofence, null)
        val etName = dv.findViewById<EditText>(R.id.etName)
        val etRadius = dv.findViewById<EditText>(R.id.etRadius)
        val etLat = dv.findViewById<EditText>(R.id.etLat)
        val etLon = dv.findViewById<EditText>(R.id.etLon)
        val cbEnter = dv.findViewById<CheckBox>(R.id.cbOnEnter)
        val cbExit = dv.findViewById<CheckBox>(R.id.cbOnExit)

        loc?.let {
            etLat.setText(it.latitude.toString())
            etLon.setText(it.longitude.toString())
        }
        etRadius.setText("200")
        cbEnter.isChecked = true
        cbExit.isChecked = true

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Geofence")
            .setView(dv)
            .setPositiveButton("Create") { _, _ ->
                val name   = etName.text.toString().ifBlank { "Geofence" }
                val lat    = etLat.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val lon    = etLon.text.toString().toDoubleOrNull() ?: return@setPositiveButton
                val radius = etRadius.text.toString().toDoubleOrNull() ?: 200.0
                viewModel.addGeofence(name, lat, lon, radius, cbEnter.isChecked, cbExit.isChecked)
            }
            .setNeutralButton("Use My Location") { _, _ ->
                loc?.let {
                    val name = etName.text.toString().ifBlank { "My Location" }
                    viewModel.addGeofence(name, it.latitude, it.longitude, 200.0, true, true)
                } ?: Toast.makeText(requireContext(), "No GPS fix", Toast.LENGTH_SHORT).show()
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

class GeofenceAdapter(
    private val onToggle: (GeofenceEntity) -> Unit,
    private val onDelete: (GeofenceEntity) -> Unit
) : ListAdapter<GeofenceEntity, GeofenceAdapter.ViewHolder>(GeofenceDiff()) {

    inner class ViewHolder(private val b: ItemGeofenceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(gf: GeofenceEntity) {
            b.tvName.text = gf.name
            b.tvCoords.text = "${String.format("%.5f", gf.latitude)}, ${String.format("%.5f", gf.longitude)}"
            b.tvRadius.text = "${gf.radius.toInt()} m radius"
            b.tvTrigger.text = buildString {
                if (gf.triggerOnEnter) append("Enter ")
                if (gf.triggerOnExit) append("Exit")
            }
            b.tvStats.text = "Entries: ${gf.enterCount}  Exits: ${gf.exitCount}"
            b.switchActive.isChecked = gf.isActive
            b.switchActive.setOnCheckedChangeListener { _, _ -> onToggle(gf) }
            b.btnDelete.setOnClickListener { onDelete(gf) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val b = ItemGeofenceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(b)
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))
}

class GeofenceDiff : DiffUtil.ItemCallback<GeofenceEntity>() {
    override fun areItemsTheSame(a: GeofenceEntity, b: GeofenceEntity) = a.id == b.id
    override fun areContentsTheSame(a: GeofenceEntity, b: GeofenceEntity) = a == b
}
