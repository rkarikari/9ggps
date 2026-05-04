// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.dashboard

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.nineggps.R
import com.nineggps.data.prefs.UserPreferences
import com.nineggps.data.repository.TrackRepository
import com.nineggps.databinding.FragmentDashboardBinding
import com.nineggps.service.GpsTrackingService
import com.nineggps.utils.NineGUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val prefs: UserPreferences
) : ViewModel() {

    val gpsState = GpsTrackingService.gpsState
    val recordingState = GpsTrackingService.recordingState
    val trackStats = GpsTrackingService.trackStats
    val compassBearing = GpsTrackingService.compassBearing

    private val _totalStats = MutableStateFlow(Triple(0, 0.0, 0L))
    val totalStats: StateFlow<Triple<Int, Double, Long>> = _totalStats

    val userName: Flow<String> = prefs.userName

    init {
        viewModelScope.launch {
            _totalStats.value = repository.getTotalStats()
        }
    }
}

@AndroidEntryPoint
class DashboardFragment : Fragment(R.layout.fragment_dashboard) {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentDashboardBinding.bind(view)

        setupNavigation()
        observeViewModel()
    }

    private fun setupNavigation() {
        binding.cardMap.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_map)
        }
        binding.cardTracks.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_tracks)
        }
        binding.cardWaypoints.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_waypoints)
        }
        binding.cardGeofences.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_geofence)
        }
        binding.cardHud.setOnClickListener {
            startActivity(android.content.Intent(requireContext(),
                com.nineggps.ui.hud.HudActivity::class.java))
        }
        binding.cardSettings.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_settings)
        }
        binding.cardOfflineMaps.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_offline)
        }
        binding.cardSatellite.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_satellite)
        }
        binding.cardNavigation.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_map)
        }
        binding.cardSpeedCamera.setOnClickListener {
            findNavController().navigate(R.id.action_dashboard_to_map)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.gpsState.collectLatest { state ->
                        binding.tvCurrentSpeed.text = "${NineGUtils.formatSpeed(state.speed)} km/h"
                        binding.tvSignalQuality.text = NineGUtils.getSignalQuality(state.accuracy)
                        binding.tvAltitude.text = NineGUtils.formatAltitude(state.altitude)
                        binding.tvAccuracy.text = "±${state.accuracy.toInt()} m"
                        binding.tvCoordinates.text = state.location?.let {
                            "${String.format("%.5f", it.latitude)}, ${String.format("%.5f", it.longitude)}"
                        } ?: "Acquiring fix..."
                    }
                }

                launch {
                    viewModel.recordingState.collectLatest { state ->
                        binding.tvRecordingStatus.text = state.name.replace("_", " ")
                    }
                }

                launch {
                    viewModel.trackStats.collectLatest { stats ->
                        binding.tvLiveDistance.text = NineGUtils.formatDistance(stats.distance)
                        binding.tvLiveDuration.text = NineGUtils.formatDuration(stats.duration)
                    }
                }

                launch {
                    viewModel.totalStats.collectLatest { (count, dist, dur) ->
                        binding.tvTotalTracks.text = "$count"
                        binding.tvTotalDistance.text = NineGUtils.formatDistance(dist)
                        binding.tvTotalTime.text = NineGUtils.formatDuration(dur)
                    }
                }

                launch {
                    viewModel.userName.collectLatest { name ->
                        binding.tvGreeting.text = "Welcome, $name"
                    }
                }

                launch {
                    viewModel.compassBearing.collectLatest { bearing ->
                        binding.ivDashCompass.rotation = -bearing
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
