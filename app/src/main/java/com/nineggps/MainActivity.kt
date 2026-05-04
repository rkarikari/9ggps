// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.nineggps.data.prefs.UserPreferences
import com.nineggps.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject
    lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply theme before setContentView
        lifecycleScope.launch {
            val theme = userPreferences.themeMode.first()
            AppCompatDelegate.setDefaultNightMode(
                when (theme) {
                    "DARK"  -> AppCompatDelegate.MODE_NIGHT_YES
                    "LIGHT" -> AppCompatDelegate.MODE_NIGHT_NO
                    else    -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigation
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Bottom nav — visual sync with current destination
        binding.bottomNav.setupWithNavController(navController)

        // Override navigation behaviour so every tab press always returns to
        // that tab's root screen, regardless of where the user currently is.
        // setupWithNavController (Navigation 2.4+) uses saveState/restoreState
        // which would bring the user back into a nested screen (e.g. TrackDetail)
        // instead of the tab root. We replace both listeners to prevent that.
        val tabNavOptions = NavOptions.Builder()
            .setLaunchSingleTop(true)
            // Pop everything down to (but not including) the start destination,
            // so the stack is always clean before navigating to the selected tab.
            .setPopUpTo(R.id.mapFragment, inclusive = false, saveState = false)
            .build()

        binding.bottomNav.setOnItemSelectedListener { item ->
            try {
                navController.navigate(item.itemId, null, tabNavOptions)
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }

        // Re-selecting the currently active tab pops back to that tab's own root
        // (handles the case where the user somehow reached a sub-screen on the
        // same tab and then taps the already-highlighted tab button).
        binding.bottomNav.setOnItemReselectedListener { item ->
            try {
                navController.navigate(item.itemId, null, tabNavOptions)
            } catch (e: IllegalArgumentException) { /* ignore */ }
        }

        // Keep screen on per preference
        lifecycleScope.launch {
            if (userPreferences.keepScreenOn.first()) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // Show/hide bottom nav based on destination
        navController.addOnDestinationChangedListener { _, dest, _ ->
            val hideBottomNav = dest.id in listOf(
                R.id.trackDetailFragment,
                R.id.settingsFragment
            )
            binding.bottomNav.visibility = if (hideBottomNav)
                android.view.View.GONE
            else
                android.view.View.VISIBLE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
