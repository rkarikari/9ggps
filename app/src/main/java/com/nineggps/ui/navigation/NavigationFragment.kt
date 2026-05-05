// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.navigation

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nineggps.R
import com.nineggps.data.model.*
import com.nineggps.data.prefs.UserPreferences
import com.nineggps.data.repository.NavigationRepository
import com.nineggps.data.repository.Result
import com.nineggps.databinding.FragmentNavigationBinding
import com.nineggps.service.GpsTrackingService
import com.nineggps.utils.NineGUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class NavigationViewModel @Inject constructor(
    application: Application,
    private val repo: NavigationRepository,
    private val prefs: UserPreferences
) : AndroidViewModel(application) {

    private val context: Context get() = getApplication()

    val gpsState        = GpsTrackingService.gpsState
    val navigationState = GpsTrackingService.navigationState

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _calculatedRoute = MutableStateFlow<NavigationState?>(null)
    val calculatedRoute: StateFlow<NavigationState?> = _calculatedRoute

    /** Mirrors calculatedRoute.alternateRoutes for the adapter. */
    val alternateRoutes: StateFlow<List<AlternateRoute>> =
        _calculatedRoute.map { it?.alternateRoutes ?: emptyList() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val routingProfile = prefs.routingProfile
    private var searchJob: Job? = null

    // ─── Service Binding ──────────────────────────────────────────────────────

    private var boundService: GpsTrackingService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boundService = (binder as GpsTrackingService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(name: ComponentName) { boundService = null }
    }

    fun bindService() {
        Intent(context, GpsTrackingService::class.java).also {
            context.bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    fun unbindService() {
        try { context.unbindService(serviceConnection) } catch (_: Exception) {}
    }

    private fun getService() = boundService

    // ─── Search ───────────────────────────────────────────────────────────────

    fun search(query: String) {
        if (query.length < 2) { _searchResults.value = emptyList(); return }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400)
            _isLoading.value = true
            val loc = gpsState.value.location
            when (val r = repo.search(query, loc?.latitude, loc?.longitude)) {
                is Result.Success -> _searchResults.value = r.data
                is Result.Error   -> _error.value = r.message
                else -> {}
            }
            _isLoading.value = false
        }
    }

    // ─── Route calculation ────────────────────────────────────────────────────

    /**
     * Fetches the primary route plus up to 2 alternates, applies traffic
     * scoring, and exposes the result via [calculatedRoute].
     */
    fun calculateRoute(destination: SearchResult, profile: String) {
        viewModelScope.launch {
            val loc = gpsState.value.location ?: run { _error.value = "No GPS fix"; return@launch }
            _isLoading.value = true
            when (val r = repo.getRouteWithAlternates(
                origin      = LatLngPoint(loc.latitude, loc.longitude),
                destination = LatLngPoint(destination.latitude, destination.longitude),
                profile     = profile
            )) {
                is Result.Success -> _calculatedRoute.value = r.data
                is Result.Error   -> _error.value = r.message
                else -> {}
            }
            _isLoading.value = false
        }
    }

    /**
     * User picked an alternate in the preview panel — swap routes locally
     * without a new network call.
     */
    fun selectAlternate(index: Int) {
        val current = _calculatedRoute.value ?: return
        if (index !in current.alternateRoutes.indices) return
        val chosen  = current.alternateRoutes[index]
        val updated = current.alternateRoutes.mapIndexed { i, r -> r.copy(isActive = i == index) }
        _calculatedRoute.value = current.copy(
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

    /**
     * User tapped an alternate while actively navigating — delegates to the
     * service so the map polyline and TTS update immediately.
     */
    fun switchActiveAlternate(index: Int) {
        getService()?.switchAlternateRoute(index)
    }

    // ─── Navigation start / stop ──────────────────────────────────────────────

    fun startNavigation(route: NavigationState) {
        context.startForegroundService(
            Intent(context, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_START_NAVIGATION
            }
        )
        val svc = getService()
        if (svc != null) {
            svc.startNavigation(route)
        } else {
            viewModelScope.launch {
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        (binder as GpsTrackingService.LocalBinder).getService().startNavigation(route)
                        try { context.unbindService(this) } catch (_: Exception) {}
                    }
                    override fun onServiceDisconnected(name: ComponentName) {}
                }
                context.bindService(Intent(context, GpsTrackingService::class.java), conn, Context.BIND_AUTO_CREATE)
            }
        }
    }

    fun clearError() { _error.value = null }
    fun clearRoute() { _calculatedRoute.value = null }

    /** Persists [profile] ("car" / "bike" / "foot") as the active routing preference. */
    fun setRoutingProfile(profile: String) {
        viewModelScope.launch { prefs.setRoutingProfile(profile) }
    }

    override fun onCleared() { unbindService(); super.onCleared() }
}

// ─── Fragment ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class NavigationFragment : Fragment(R.layout.fragment_navigation) {

    private var _binding: FragmentNavigationBinding? = null
    private val binding get() = _binding!!
    private val viewModel: NavigationViewModel by viewModels()

    private lateinit var alternateAdapter: AlternateRouteAdapter
    private lateinit var searchResultsAdapter: com.nineggps.ui.map.SearchResultAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentNavigationBinding.bind(view)

        setupAlternateRoutesList()
        setupSearch()
        setupObservers()
        setupRoutingOptions()

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.btnStopNavigation.setOnClickListener {
            requireContext().startService(
                Intent(requireContext(), GpsTrackingService::class.java).apply {
                    action = GpsTrackingService.ACTION_STOP_NAVIGATION
                }
            )
        }
    }

    override fun onStart() { super.onStart(); viewModel.bindService() }
    override fun onStop()  { super.onStop();  viewModel.unbindService() }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupAlternateRoutesList() {
        alternateAdapter = AlternateRouteAdapter { index ->
            if (viewModel.navigationState.value.isNavigating) {
                viewModel.switchActiveAlternate(index)
            } else {
                viewModel.selectAlternate(index)
            }
        }
        binding.rvAlternateRoutes.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = alternateAdapter
        }
    }

    private fun setupSearch() {
        // A LayoutManager is mandatory — without one the RecyclerView renders nothing.
        searchResultsAdapter = com.nineggps.ui.map.SearchResultAdapter { showRouteOptions(it) }
        binding.searchResultsList.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchResultsAdapter
        }

        binding.searchView.setOnQueryTextListener(
            object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?): Boolean { q?.let { viewModel.search(it) }; return true }
                override fun onQueryTextChange(q: String?): Boolean { viewModel.search(q ?: ""); return true }
            }
        )
    }

    private fun setupRoutingOptions() {
        // Each chip writes the selected profile to DataStore immediately so the
        // next route calculation in this screen (or any other) uses the new value.
        binding.chipCar.setOnClickListener  { viewModel.setRoutingProfile("car") }
        binding.chipBike.setOnClickListener { viewModel.setRoutingProfile("bike") }
        binding.chipWalk.setOnClickListener { viewModel.setRoutingProfile("foot") }
    }

    /** Syncs chip checked state to the currently persisted routing profile. */
    private fun applyProfileToChips(profile: String) {
        binding.chipCar.isChecked  = (profile == "car")
        binding.chipBike.isChecked = (profile == "bike")
        binding.chipWalk.isChecked = (profile == "foot")
    }

    // ─── Observers ────────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.gpsState.collectLatest { state ->
                        binding.tvCurrentLocation.text = state.location?.let {
                            "${String.format("%.5f", it.latitude)}, ${String.format("%.5f", it.longitude)}"
                        } ?: "Acquiring GPS…"
                        binding.tvCurrentSpeed.text = "${NineGUtils.formatSpeed(state.speed)} km/h"
                    }
                }

                launch {
                    viewModel.searchResults.collectLatest { results ->
                        binding.searchResultsList.isVisible = results.isNotEmpty()
                        searchResultsAdapter.updateResults(results)
                    }
                }

                launch { viewModel.isLoading.collectLatest { binding.progressBar.isVisible = it } }

                launch {
                    viewModel.error.collectLatest { err ->
                        err?.let {
                            Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                            viewModel.clearError()
                        }
                    }
                }

                launch { viewModel.navigationState.collectLatest { updateNavigationPanel(it) } }

                launch { viewModel.calculatedRoute.collectLatest { it?.let { showRoutePreview(it) } } }

                // Keep chips in sync with the persisted routing profile (e.g. if the
                // user changes it in Settings and returns here).
                launch {
                    viewModel.routingProfile.collectLatest { profile ->
                        applyProfileToChips(profile)
                    }
                }

                // Alternate route cards — drive both the preview and live-nav panels.
                launch {
                    viewModel.alternateRoutes.collectLatest { alternates ->
                        val show = alternates.size > 1
                        binding.tvAlternatesLabel.isVisible = show
                        binding.scrollAlternates.isVisible  = show
                        if (show) alternateAdapter.submitList(alternates)
                    }
                }
            }
        }
    }

    // ─── Route options dialog ─────────────────────────────────────────────────

    private fun showRouteOptions(result: SearchResult) {
        binding.searchResultsList.isVisible = false
        // Pre-highlight whichever mode the chips currently reflect.
        val currentSelection = when {
            binding.chipBike.isChecked -> 1
            binding.chipWalk.isChecked -> 2
            else                       -> 0
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(result.displayName.take(60))
            .setSingleChoiceItems(
                arrayOf("🚗 Car", "🚲 Bike", "🚶 Walk"),
                currentSelection
            ) { dialog, which ->
                val profile = when (which) { 0 -> "car"; 1 -> "bike"; else -> "foot" }
                // Persist + sync chips so the UI stays consistent.
                viewModel.setRoutingProfile(profile)
                viewModel.calculateRoute(result, profile)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    // ─── Route preview ────────────────────────────────────────────────────────

    private fun showRoutePreview(route: NavigationState) {
        binding.cardRoutePreview.isVisible = true
        binding.tvRouteDistance.text       = NineGUtils.formatDistance(route.totalDistanceRemaining)
        binding.tvRouteEta.text            = NineGUtils.formatEta(route.estimatedTimeRemaining)
        binding.tvFirstInstruction.text    = route.currentInstruction

        binding.btnStartNavigation.setOnClickListener {
            viewModel.startNavigation(route)
            binding.cardRoutePreview.isVisible = false
            Snackbar.make(binding.root, "Navigation started!", Snackbar.LENGTH_SHORT).show()
        }
        binding.btnCancelRoute.setOnClickListener {
            viewModel.clearRoute()
            binding.cardRoutePreview.isVisible = false
        }
    }

    // ─── Active navigation panel ──────────────────────────────────────────────

    private fun updateNavigationPanel(nav: NavigationState) {
        binding.cardActiveNav.isVisible     = nav.isNavigating
        binding.btnStopNavigation.isVisible = nav.isNavigating
        if (!nav.isNavigating) return

        binding.tvActiveInstruction.text = nav.currentInstruction
        binding.tvActiveDistance.text    = NineGUtils.formatDistanceShort(nav.distanceToNextTurn)
        binding.tvActiveEta.text         = "ETA ${NineGUtils.formatEta(nav.estimatedTimeRemaining)}"
        binding.tvActiveRemaining.text   = "${NineGUtils.formatDistance(nav.totalDistanceRemaining)} remaining"

        // ── Rerouting / off-route / traffic status row ─────────────────────────
        val hasStatus = nav.isRerouting || nav.isOffRoute ||
                        nav.trafficCondition != TrafficCondition.UNKNOWN
        binding.layoutRerouteStatus.isVisible = hasStatus

        if (hasStatus) {
            binding.pbRerouting.isVisible = nav.isRerouting || nav.isOffRoute
            binding.tvRerouteStatus.text  = when {
                nav.isRerouting && nav.rerouteReason == RerouteReason.TRAFFIC ->
                    "Recalculating — better route found…"
                nav.isRerouting -> "Recalculating route…"
                nav.isOffRoute  -> "Off route — recalculating…"
                else            -> ""
            }
            binding.tvTrafficBadge.text = when (nav.trafficCondition) {
                TrafficCondition.FREE_FLOW  -> "🟢 Free"
                TrafficCondition.MODERATE   -> "🟡 Moderate traffic"
                TrafficCondition.HEAVY      -> "🟠 Heavy traffic"
                TrafficCondition.STANDSTILL -> "🔴 Standstill"
                TrafficCondition.UNKNOWN    -> ""
            }
            binding.tvTrafficBadge.isVisible = nav.trafficCondition != TrafficCondition.UNKNOWN
        }

        // Live alternate route chips while navigating.
        val alternates = nav.alternateRoutes
        if (alternates.size > 1) {
            binding.tvAlternatesLabel.isVisible = true
            binding.scrollAlternates.isVisible  = true
            alternateAdapter.submitList(alternates)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
