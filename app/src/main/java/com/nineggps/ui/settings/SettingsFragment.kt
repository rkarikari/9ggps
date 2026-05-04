// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.ui.settings

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.gms.location.LocationServices
import com.nineggps.BuildConfig
import com.nineggps.R
import com.nineggps.data.prefs.UserPreferences
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val prefs: UserPreferences
) : ViewModel() {

    fun setSpeedUnit(v: String)      = viewModelScope.launch { prefs.setSpeedUnit(v) }
    fun setDistanceUnit(v: String)   = viewModelScope.launch { prefs.setDistanceUnit(v) }
    fun setTheme(v: String)          = viewModelScope.launch { prefs.setThemeMode(v) }
    fun setMapType(v: String)        = viewModelScope.launch { prefs.setMapType(v) }
    fun setKeepScreenOn(v: Boolean)  = viewModelScope.launch { prefs.setKeepScreenOn(v) }
    fun setVoiceGuidance(v: Boolean) = viewModelScope.launch { prefs.setVoiceGuidance(v) }
    fun setSpeedAlerts(v: Boolean)   = viewModelScope.launch { prefs.setSpeedLimitAlerts(v) }
    fun setShowWeather(v: Boolean)   = viewModelScope.launch { prefs.setShowWeather(v) }
    fun setMapOrientation(v: String) = viewModelScope.launch { prefs.setMapOrientation(v) }
    fun setBatterySaver(v: Boolean)  = viewModelScope.launch { prefs.setBatterySaver(v) }
    fun setRecordingMinAccuracy(v: Float) = viewModelScope.launch { prefs.setRecordingMinAccuracy(v) }
    fun setRoutingProfile(v: String) = viewModelScope.launch { prefs.setRoutingProfile(v) }
    fun setActivityType(v: String)   = viewModelScope.launch { prefs.setActivityType(v) }
    fun setAvoidTolls(v: Boolean)    = viewModelScope.launch { prefs.setAvoidTolls(v) }
    fun setAvoidHighways(v: Boolean) = viewModelScope.launch { prefs.setAvoidHighways(v) }
    fun setWeatherKey(v: String)     = viewModelScope.launch { prefs.setOpenWeatherKey(v) }
    fun setCompass(v: Boolean)       = viewModelScope.launch { prefs.setCompassEnabled(v) }
    fun setOfflineMode(v: Boolean)   = viewModelScope.launch { prefs.setOfflineMode(v) }

    // ─── Home Auto-Record ──────────────────────────────────────────────────────
    fun setHomeAutoRecord(v: Boolean) = viewModelScope.launch { prefs.setHomeAutoRecord(v) }
    fun setHomeDepartureRadius(v: Float) = viewModelScope.launch { prefs.setHomeDepartureRadiusM(v) }
    fun setHomeArrivalRadius(v: Float)   = viewModelScope.launch { prefs.setHomeArrivalRadiusM(v) }
    fun setHomeMinTripDistance(v: Float) = viewModelScope.launch { prefs.setHomeMinTripDistanceM(v) }
    fun setHomeMinTripDuration(v: Long)  = viewModelScope.launch { prefs.setHomeMinTripDurationS(v) }
    fun setHomeLocation(lat: Double, lon: Double) = viewModelScope.launch { prefs.setHomeLocation(lat, lon) }
    fun clearHomeLocation()              = viewModelScope.launch { prefs.clearHomeLocation() }
}

@AndroidEntryPoint
class SettingsFragment : PreferenceFragmentCompat() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // PreferenceFragmentCompat inflates its own RecyclerView (listView) and
        // never applies window insets. On edge-to-edge builds this causes the
        // first category header ("Display") to render under the status bar.
        // Push the list down by exactly the status-bar height to fix it.
        ViewCompat.setOnApplyWindowInsetsListener(listView) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = statusBarHeight)
            insets
        }

        bindPreferences()
    }

    private fun bindPreferences() {
        lifecycleScope.launch {

            // Speed unit
            findPreference<ListPreference>("speed_unit")?.apply {
                value = viewModel.prefs.speedUnit.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setSpeedUnit(v as String); true }
            }

            // Distance unit
            findPreference<ListPreference>("distance_unit")?.apply {
                value = viewModel.prefs.distanceUnit.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setDistanceUnit(v as String); true }
            }

            // Theme
            findPreference<ListPreference>("theme_mode")?.apply {
                value = viewModel.prefs.themeMode.first()
                setOnPreferenceChangeListener { _, v ->
                    viewModel.setTheme(v as String)
                    applyTheme(v)
                    true
                }
            }

            // Map type
            findPreference<ListPreference>("map_type")?.apply {
                value = viewModel.prefs.mapType.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setMapType(v as String); true }
            }

            // Keep screen on
            findPreference<SwitchPreferenceCompat>("keep_screen_on")?.apply {
                isChecked = viewModel.prefs.keepScreenOn.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setKeepScreenOn(v as Boolean); true }
            }

            // Voice guidance
            findPreference<SwitchPreferenceCompat>("voice_guidance")?.apply {
                isChecked = viewModel.prefs.voiceGuidance.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setVoiceGuidance(v as Boolean); true }
            }

            // Speed alerts
            findPreference<SwitchPreferenceCompat>("speed_limit_alerts")?.apply {
                isChecked = viewModel.prefs.speedLimitAlerts.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setSpeedAlerts(v as Boolean); true }
            }

            // Show weather
            findPreference<SwitchPreferenceCompat>("show_weather")?.apply {
                isChecked = viewModel.prefs.showWeather.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setShowWeather(v as Boolean); true }
            }

            // Map orientation
            findPreference<ListPreference>("map_orientation")?.apply {
                value = viewModel.prefs.mapOrientation.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setMapOrientation(v as String); true }
            }

            // Battery saver
            findPreference<SwitchPreferenceCompat>("battery_saver")?.apply {
                isChecked = viewModel.prefs.batterySaver.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setBatterySaver(v as Boolean); true }
            }

            // Recording min accuracy
            findPreference<ListPreference>("recording_min_accuracy")?.apply {
                value = viewModel.prefs.recordingMinAccuracy.first().toInt().toString()
                setOnPreferenceChangeListener { _, v ->
                    viewModel.setRecordingMinAccuracy((v as String).toFloat())
                    true
                }
            }

            // Routing profile
            findPreference<ListPreference>("routing_profile")?.apply {
                value = viewModel.prefs.routingProfile.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setRoutingProfile(v as String); true }
            }

            // Activity type
            findPreference<ListPreference>("activity_type")?.apply {
                value = viewModel.prefs.activityType.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setActivityType(v as String); true }
            }

            // Avoid tolls
            findPreference<SwitchPreferenceCompat>("avoid_tolls")?.apply {
                setOnPreferenceChangeListener { _, v -> viewModel.setAvoidTolls(v as Boolean); true }
            }

            // Avoid highways
            findPreference<SwitchPreferenceCompat>("avoid_highways")?.apply {
                setOnPreferenceChangeListener { _, v -> viewModel.setAvoidHighways(v as Boolean); true }
            }

            // Compass
            findPreference<SwitchPreferenceCompat>("compass_enabled")?.apply {
                isChecked = viewModel.prefs.compassEnabled.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setCompass(v as Boolean); true }
            }

            // Offline mode
            findPreference<SwitchPreferenceCompat>("offline_mode")?.apply {
                isChecked = viewModel.prefs.offlineMode.first()
                setOnPreferenceChangeListener { _, v -> viewModel.setOfflineMode(v as Boolean); true }
            }

            // ── Home Auto-Record ──────────────────────────────────────────────

            // Master toggle — also enforces that a home is set before enabling
            findPreference<SwitchPreferenceCompat>("home_auto_record")?.apply {
                isChecked = viewModel.prefs.homeAutoRecord.first()
                setOnPreferenceChangeListener { _, v ->
                    val enable = v as Boolean
                    if (enable) {
                        lifecycleScope.launch {
                            val homeSet = viewModel.prefs.homeIsSet.first()
                            if (!homeSet) {
                                isChecked = false
                                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                                    .setTitle(getString(R.string.pref_home_auto_record))
                                    .setMessage(getString(R.string.home_auto_record_requires_home))
                                    .setPositiveButton("OK", null)
                                    .show()
                            } else {
                                viewModel.setHomeAutoRecord(true)
                            }
                        }
                    } else {
                        viewModel.setHomeAutoRecord(false)
                    }
                    false // we manage isChecked manually above
                }
            }

            // Set home to current GPS location
            findPreference<Preference>("home_set_location")?.apply {
                // Show saved coords in summary when available
                val homeSet = viewModel.prefs.homeIsSet.first()
                summary = if (homeSet) {
                    val lat = viewModel.prefs.homeLat.first()
                    val lon = viewModel.prefs.homeLon.first()
                    "Currently: %.5f, %.5f".format(lat, lon)
                } else {
                    getString(R.string.pref_home_set_location_summary)
                }
                setOnPreferenceClickListener {
                    captureCurrentLocationAsHome()
                    true
                }
            }

            // Clear home location
            findPreference<Preference>("home_clear_location")?.apply {
                setOnPreferenceClickListener {
                    showClearHomeConfirmation()
                    true
                }
            }

            // Departure radius
            findPreference<ListPreference>("home_departure_radius")?.apply {
                value = viewModel.prefs.homeDepartureRadiusM.first().toInt().toString()
                setOnPreferenceChangeListener { _, v ->
                    viewModel.setHomeDepartureRadius((v as String).toFloatOrNull() ?: 200f)
                    true
                }
            }

            // Arrival radius
            findPreference<ListPreference>("home_arrival_radius")?.apply {
                value = viewModel.prefs.homeArrivalRadiusM.first().toInt().toString()
                setOnPreferenceChangeListener { _, v ->
                    viewModel.setHomeArrivalRadius((v as String).toFloatOrNull() ?: 150f)
                    true
                }
            }

            // Minimum trip distance
            findPreference<ListPreference>("home_min_trip_distance")?.apply {
                value = viewModel.prefs.homeMinTripDistanceM.first().toInt().toString()
                setOnPreferenceChangeListener { _, v ->
                    viewModel.setHomeMinTripDistance((v as String).toFloatOrNull() ?: 500f)
                    true
                }
            }

            // Minimum trip duration
            findPreference<ListPreference>("home_min_trip_duration")?.apply {
                value = viewModel.prefs.homeMinTripDurationS.first().toString()
                setOnPreferenceChangeListener { _, v ->
                    viewModel.setHomeMinTripDuration((v as String).toLongOrNull() ?: 60L)
                    true
                }
            }

            // About
            findPreference<Preference>("about")?.apply {
                summary = "9G GPS v${BuildConfig.VERSION_NAME}"
                setOnPreferenceClickListener {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("About 9G GPS")
                        .setMessage(
                            "Version ${BuildConfig.VERSION_NAME}\n\n" +
                            "© R.N.K 9G5AR, RadioZport\n\n" +
                            "Built with OpenStreetMap contributors " +
                            "and osmdroid."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    true
                }
            }

            // Clear tracks
            findPreference<Preference>("clear_data")?.apply {
                setOnPreferenceClickListener {
                    showClearDataConfirmation()
                    true
                }
            }

            // Offline maps
            findPreference<Preference>("offline_maps")?.apply {
                setOnPreferenceClickListener {
                    findNavController().navigate(R.id.offlineMapFragment)
                    true
                }
            }
        }
    }

    private fun applyTheme(mode: String) {
        val nightMode = when (mode) {
            "DARK"   -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            "LIGHT"  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            else     -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    private fun showClearDataConfirmation() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear All Data")
            .setMessage("This will delete all tracks, waypoints, and geofences. This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                // Clear handled by repository in production
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Reads the last known location from the fused provider and saves it as the
     * home location.  Shows a snackbar confirming success or explaining failure.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun captureCurrentLocationAsHome() {
        val fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                viewModel.setHomeLocation(location.latitude, location.longitude)
                // Refresh the set-location pref summary
                findPreference<Preference>("home_set_location")?.summary =
                    "Currently: %.5f, %.5f".format(location.latitude, location.longitude)
                // Auto-enable the toggle now that home is set
                findPreference<SwitchPreferenceCompat>("home_auto_record")?.isChecked = true
                viewModel.setHomeAutoRecord(true)
                com.google.android.material.snackbar.Snackbar
                    .make(requireView(), getString(R.string.home_location_set),
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                    .show()
            } else {
                com.google.android.material.snackbar.Snackbar
                    .make(requireView(), getString(R.string.home_location_set_no_fix),
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                    .show()
            }
        }.addOnFailureListener {
            com.google.android.material.snackbar.Snackbar
                .make(requireView(), getString(R.string.home_location_set_no_fix),
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .show()
        }
    }

    private fun showClearHomeConfirmation() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.pref_home_clear_location))
            .setMessage("This will remove your saved home location. Auto-record will be disabled.")
            .setPositiveButton("Clear") { _, _ ->
                viewModel.clearHomeLocation()
                viewModel.setHomeAutoRecord(false)
                findPreference<SwitchPreferenceCompat>("home_auto_record")?.isChecked = false
                findPreference<Preference>("home_set_location")?.summary =
                    getString(R.string.pref_home_set_location_summary)
                com.google.android.material.snackbar.Snackbar
                    .make(requireView(), getString(R.string.home_location_cleared),
                        com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
