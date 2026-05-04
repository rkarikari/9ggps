// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.hud

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.nineggps.R
import com.nineggps.data.model.NavigationState
import com.nineggps.data.model.RecordingState
import com.nineggps.data.prefs.UserPreferences
import com.nineggps.databinding.ActivityHudBinding
import com.nineggps.service.GpsTrackingService
import com.nineggps.utils.NineGUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class HudViewModel @Inject constructor(
    private val prefs: UserPreferences
) : ViewModel() {
    val gpsState = GpsTrackingService.gpsState
    val navigationState = GpsTrackingService.navigationState
    val recordingState = GpsTrackingService.recordingState
    val trackStats = GpsTrackingService.trackStats
    val compassBearing = GpsTrackingService.compassBearing
    val pressure = GpsTrackingService.pressure

    val speedUnit: Flow<String> = prefs.speedUnit
    val distanceUnit: Flow<String> = prefs.distanceUnit
}

// ─── Activity ─────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class HudActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHudBinding
    private val viewModel: HudViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on, show over lock screen
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Full screen immersive
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        binding = ActivityHudBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeViewModel()
    }

    private fun setupUI() {
        binding.btnClose.setOnClickListener { finish() }

        binding.btnRecord.setOnClickListener {
            val state = viewModel.recordingState.value
            val intent = android.content.Intent(this, GpsTrackingService::class.java).apply {
                action = when (state) {
                    RecordingState.IDLE      -> GpsTrackingService.ACTION_START_TRACKING
                    RecordingState.RECORDING -> GpsTrackingService.ACTION_PAUSE_TRACKING
                    RecordingState.PAUSED    -> GpsTrackingService.ACTION_RESUME_TRACKING
                }
            }
            startService(intent)
        }

        binding.btnStopNav.setOnClickListener {
            val intent = android.content.Intent(this, GpsTrackingService::class.java).apply {
                action = GpsTrackingService.ACTION_STOP_NAVIGATION
            }
            startService(intent)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                launch {
                    viewModel.gpsState.collectLatest { state ->
                        // Big speed display
                        binding.tvBigSpeed.text = NineGUtils.formatSpeed(state.speed)
                        binding.tvSpeedUnit.text = "km/h"
                        binding.tvAltitude.text = NineGUtils.formatAltitude(state.altitude)
                        binding.tvAccuracy.text = "±${state.accuracy.toInt()}m"
                        binding.tvBearing.text = NineGUtils.formatBearing(state.bearing)

                        // Speed bar fill
                        val fraction = (state.speedKmh / 200f).coerceIn(0f, 1f)
                        binding.speedBarFill.scaleX = fraction
                    }
                }

                launch {
                    viewModel.navigationState.collectLatest { nav ->
                        updateNavigationPanel(nav)
                    }
                }

                launch {
                    viewModel.trackStats.collectLatest { stats ->
                        binding.tvRecordDistance.text = NineGUtils.formatDistance(stats.distance)
                        binding.tvRecordTime.text = NineGUtils.formatDuration(stats.duration)
                        binding.tvAvgSpeed.text = "${stats.avgSpeed.toInt()} avg"
                        binding.tvMaxSpeed.text = "${stats.maxSpeed.toInt()} max"
                    }
                }

                launch {
                    viewModel.recordingState.collectLatest { state ->
                        binding.btnRecord.setImageResource(
                            when (state) {
                                RecordingState.IDLE      -> R.drawable.ic_record
                                RecordingState.RECORDING -> R.drawable.ic_pause
                                RecordingState.PAUSED    -> R.drawable.ic_play
                            }
                        )
                        binding.tvRecordingStatus.text = when (state) {
                            RecordingState.IDLE      -> "Not recording"
                            RecordingState.RECORDING -> "● REC"
                            RecordingState.PAUSED    -> "⏸ Paused"
                        }
                    }
                }

                launch {
                    viewModel.compassBearing.collectLatest { bearing ->
                        binding.ivCompassNeedle.rotation = -bearing
                        binding.tvCompassDir.text = NineGUtils.formatBearing(bearing)
                    }
                }

                launch {
                    viewModel.pressure.collectLatest { hPa ->
                        hPa?.let {
                            val altFromPressure = NineGUtils.pressureToAltitude(it)
                            binding.tvPressure.text = "${it.toInt()} hPa"
                            binding.tvBaroAlt.text = NineGUtils.formatAltitude(altFromPressure)
                        }
                    }
                }
            }
        }
    }

    private fun updateNavigationPanel(nav: NavigationState) {
        binding.navPanel.visibility = if (nav.isNavigating) View.VISIBLE else View.GONE
        binding.btnStopNav.visibility = if (nav.isNavigating) View.VISIBLE else View.GONE

        if (nav.isNavigating) {
            binding.tvNavInstruction.text = nav.currentInstruction
            binding.tvNavDistance.text = NineGUtils.formatDistanceShort(nav.distanceToNextTurn)
            binding.tvNavEta.text = NineGUtils.formatEta(nav.estimatedTimeRemaining)
            binding.tvNavRemaining.text = NineGUtils.formatDistance(nav.totalDistanceRemaining)
            binding.tvNextInstruction.text = nav.nextInstruction

            val icon = NineGUtils.getManeuverIcon(
                nav.steps.getOrNull(nav.currentStepIndex)?.maneuver ?: "continue",
                null
            )
            binding.ivManeuver.setImageResource(icon)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }
}
