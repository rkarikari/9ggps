// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration as ResConfiguration
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import android.view.WindowManager
import androidx.work.Configuration as WorkConfiguration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import javax.inject.Inject

/**
 * Application entry-point for 9G GPS.
 *
 * Responsibilities
 * ────────────────
 *  • OSMDroid initialisation with a browser-like User-Agent and a bounded
 *    tile cache so device storage is never exhausted.
 *  • Android notification-channel registration (all four channels).
 *  • WorkManager on-demand initialisation via [WorkConfiguration.Provider],
 *    allowing a per-build-type logging level without relying on the
 *    auto-startup ContentProvider (removed in AndroidManifest.xml).
 *  • StrictMode configuration in DEBUG builds to surface disk/network calls
 *    on the main thread and leaked Closeable / SQLite objects early.
 *  • Application-level [ActivityLifecycleCallbacks] that apply (or clear) the
 *    FLAG_KEEP_SCREEN_ON window flag on every Activity based on the live user
 *    preference, so the setting takes effect immediately without an app restart.
 *  • Graduated [onTrimMemory] / [onLowMemory] handlers that shrink or purge
 *    the OSMDroid tile cache under memory pressure.
 *  • [onConfigurationChanged] that re-loads the OSMDroid configuration so
 *    locale-sensitive tile-source settings are refreshed automatically.
 *  • [onTerminate] cleanup that cancels the application-scope coroutine and
 *    un-registers the lifecycle callbacks.
 */
@HiltAndroidApp
class NineGApp : Application(), WorkConfiguration.Provider {

    // ─── Injected dependencies ────────────────────────────────────────────────

    @Inject
    lateinit var userPreferences: com.nineggps.data.prefs.UserPreferences

    // ─── Application-scoped coroutine scope ───────────────────────────────────

    /**
     * Long-lived coroutine scope tied to the process lifetime.
     * Cancelled in [onTerminate]. Used for observers that must survive
     * individual Activity/Fragment lifecycles (e.g. collecting the
     * keepScreenOn preference to feed [activityCallbacks]).
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ─── Cached preference — synchronous access for onActivityCreated ─────────

    /** Mirrors [com.nineggps.data.prefs.UserPreferences.keepScreenOn] for synchronous access. */
    @Volatile private var keepScreenOn: Boolean = true

    // ─── ActivityLifecycleCallbacks ───────────────────────────────────────────

    private val activityCallbacks = object : ActivityLifecycleCallbacks {
        /**
         * Apply or clear FLAG_KEEP_SCREEN_ON on every Activity as it is created
         * so the preference takes effect without an app restart.
         * HudActivity manages its own window flags independently — skip it.
         */
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is com.nineggps.ui.hud.HudActivity) return
            if (keepScreenOn) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    // ─── Application lifecycle ────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // ── StrictMode (debug only) ───────────────────────────────────────────
        // Surfaces disk/network calls on the main thread and leaked SQLite /
        // Closeable objects via logcat. Penalties are logged only — never shown
        // to users in release builds.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedSqlLiteObjects()
                    .detectLeakedClosableObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }

        // ── OSMDroid ──────────────────────────────────────────────────────────
        // A browser-like User-Agent is required for tile servers that reject the
        // package-name default (e.g. Google tile CDN).
        // tileFileSystemCacheMaxBytes caps on-device tile storage at 200 MB;
        // tileFileSystemCacheTrimBytes is the target size after a trim pass.
        Configuration.getInstance().apply {
            load(this@NineGApp, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = "Mozilla/5.0 (Linux; Android 10) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
            isDebugMode = BuildConfig.DEBUG
            osmdroidBasePath = filesDir
            osmdroidTileCache = cacheDir
            tileFileSystemCacheMaxBytes  = TILE_CACHE_MAX_BYTES
            tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
        }

        // ── Notification channels ─────────────────────────────────────────────
        createNotificationChannels()

        // ── Keep-screen-on preference observer ────────────────────────────────
        // Collect into [keepScreenOn] so [activityCallbacks].onActivityCreated
        // can apply the window flag synchronously without suspending.
        appScope.launch {
            userPreferences.keepScreenOn.collect { enabled ->
                keepScreenOn = enabled
            }
        }

        // ── Activity lifecycle callbacks ──────────────────────────────────────
        registerActivityLifecycleCallbacks(activityCallbacks)
    }

    // ─── WorkManager custom configuration ────────────────────────────────────

    /**
     * Provides a WorkManager [WorkConfiguration] with per-build-type logging.
     *
     * The default WorkManager ContentProvider initialiser is removed in
     * AndroidManifest.xml (tools:node="remove" on WorkManagerInitializer) so
     * this getter is the sole initialisation path — WorkManager is lazily
     * created on first [androidx.work.WorkManager.getInstance] call.
     */
    override val workManagerConfiguration: WorkConfiguration
        get() = WorkConfiguration.Builder()
            .setMinimumLoggingLevel(
                if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR
            )
            .build()

    // ─── Memory-pressure callbacks ────────────────────────────────────────────

    /**
     * Emergency callback when the system is critically low on memory.
     * Purges the entire OSMDroid tile cache directory to release as much
     * storage as possible.
     */
    override fun onLowMemory() {
        super.onLowMemory()
        Configuration.getInstance().osmdroidTileCache?.deleteRecursively()
    }

    /**
     * Graduated response to memory-pressure signals from the ActivityManager.
     *
     * | Level                            | Action                                     |
     * |----------------------------------|--------------------------------------------|
     * | UI_HIDDEN / BACKGROUND           | No action — cache stays warm for resume.   |
     * | MODERATE / RUNNING_MODERATE      | Halve the cache ceiling so the next tile   |
     * |                                  | write-back trims to a smaller target.      |
     * | COMPLETE / RUNNING_CRITICAL /    | Purge the cache immediately and reset the  |
     * | RUNNING_LOW                      | ceiling to the trim-target value.          |
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            TRIM_MEMORY_UI_HIDDEN,
            TRIM_MEMORY_BACKGROUND -> {
                // App moved to background but memory is not yet critical.
                // OSMDroid's LRU eviction handles routine cleanup — no action needed.
            }
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_RUNNING_MODERATE -> {
                // Moderate pressure: reduce the ceiling so upcoming tile writes
                // trigger an earlier background trim.
                Configuration.getInstance()
                    .tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES / 2
            }
            TRIM_MEMORY_COMPLETE,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_RUNNING_LOW -> {
                // Critical pressure: purge the tile cache directory immediately
                // and reset the ceiling to the trim-target value.
                Configuration.getInstance().osmdroidTileCache?.deleteRecursively()
                Configuration.getInstance()
                    .tileFileSystemCacheMaxBytes = TILE_CACHE_TRIM_BYTES
            }
        }
    }

    // ─── Configuration change ─────────────────────────────────────────────────

    /**
     * Re-loads OSMDroid configuration after a system configuration change
     * (locale, font scale, screen layout, etc.) so any locale-sensitive
     * tile-source settings stored in SharedPreferences are refreshed correctly.
     */
    override fun onConfigurationChanged(newConfig: ResConfiguration) {
        super.onConfigurationChanged(newConfig)
        Configuration.getInstance().load(
            this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
    }

    // ─── Termination ─────────────────────────────────────────────────────────

    /**
     * Called when the application process is being cleanly terminated
     * (emulator / instrumentation use-cases; rarely called on real devices).
     * Un-registers [activityCallbacks] to prevent a reference leak and
     * cancels [appScope] to stop all running coroutines.
     */
    override fun onTerminate() {
        unregisterActivityLifecycleCallbacks(activityCallbacks)
        appScope.cancel()
        super.onTerminate()
    }

    // ─── Notification channels ────────────────────────────────────────────────

    private fun createNotificationChannels() {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // GPS Tracking — low importance: persistent, silent foreground notification
        val trackingChannel = NotificationChannel(
            CHANNEL_GPS_TRACKING,
            "GPS Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while GPS tracking is active"
            setShowBadge(false)
        }

        // Navigation — high importance: audible turn-by-turn heads-up notifications
        val navigationChannel = NotificationChannel(
            CHANNEL_NAVIGATION,
            "Navigation",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Turn-by-turn navigation instructions"
        }

        // Geofence Alerts — default importance: enter/exit/dwell transition alerts
        val geofenceChannel = NotificationChannel(
            CHANNEL_GEOFENCE,
            "Geofence Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when entering or leaving geofenced areas"
        }

        // Speed Alerts — high importance: speed-camera proximity warnings
        val speedAlertChannel = NotificationChannel(
            CHANNEL_SPEED_ALERT,
            "Speed Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Speed limit warnings and camera proximity alerts"
            enableVibration(true)
        }

        notificationManager.createNotificationChannels(
            listOf(trackingChannel, navigationChannel, geofenceChannel, speedAlertChannel)
        )
    }

    // ─── Constants ────────────────────────────────────────────────────────────

    companion object {
        // Notification channel IDs
        const val CHANNEL_GPS_TRACKING = "gps_tracking"
        const val CHANNEL_NAVIGATION   = "navigation"
        const val CHANNEL_GEOFENCE     = "geofence"
        const val CHANNEL_SPEED_ALERT  = "speed_alert"

        // Notification IDs
        const val NOTIFICATION_ID_TRACKING    = 1001
        const val NOTIFICATION_ID_NAVIGATION  = 1002
        const val NOTIFICATION_ID_SPEED_ALERT = 1003

        // OSMDroid tile-cache size limits
        /** Maximum on-device tile-cache size: 200 MB. */
        const val TILE_CACHE_MAX_BYTES  = 200L * 1024L * 1024L   // 200 MB
        /** Target tile-cache size after a trim pass: 100 MB. */
        const val TILE_CACHE_TRIM_BYTES = 100L * 1024L * 1024L   // 100 MB
    }
}
