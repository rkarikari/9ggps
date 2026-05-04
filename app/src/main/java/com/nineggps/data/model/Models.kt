// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.data.model

import android.location.Location

// ─── GPS State ────────────────────────────────────────────────────────────────

data class GpsState(
    val location: Location? = null,
    val speed: Float = 0f,           // m/s
    val speedKmh: Float = 0f,        // km/h
    val bearing: Float = 0f,         // degrees
    val altitude: Double = 0.0,      // meters
    val accuracy: Float = 0f,        // meters
    val satellites: Int = 0,
    val isFixed: Boolean = false,
    val provider: String = ""
)

// ─── Navigation State ─────────────────────────────────────────────────────────

/**
 * Reason the engine triggered a route recalculation.
 */
enum class RerouteReason {
    NONE,
    OFF_ROUTE,          // user deviated beyond the off-route threshold
    TRAFFIC,            // active route became significantly slower than an alternate
    USER_REQUESTED      // user manually switched alternate or tapped "Reroute"
}

/**
 * Coarse traffic level inferred from OSRM duration vs. free-flow baseline.
 */
enum class TrafficCondition(val label: String) {
    UNKNOWN("Unknown"),
    FREE_FLOW("Free Flow"),
    MODERATE("Moderate Traffic"),
    HEAVY("Heavy Traffic"),
    STANDSTILL("Standstill")
}

/**
 * One candidate route — either the primary or an alternative returned by OSRM.
 *
 * @param id            Zero-based index matching the OSRM response order.
 * @param label         Human-readable label ("Fastest", "Alternate 1", …).
 * @param route         Decoded polyline points for map rendering.
 * @param steps         Turn-by-turn steps.
 * @param totalDistance Total route distance in metres.
 * @param baseDuration  OSRM free-flow duration in seconds (no traffic).
 * @param trafficDelay  Extra seconds added by current traffic conditions.
 * @param trafficCondition Coarse traffic level for this route.
 * @param isActive      True when this is the route the driver is following.
 */
data class AlternateRoute(
    val id: Int,
    val label: String,
    val route: List<LatLngPoint>,
    val steps: List<RouteStep>,
    val totalDistance: Double,
    val baseDuration: Long,         // seconds, free-flow
    val trafficDelay: Long = 0L,    // seconds added by traffic
    val trafficCondition: TrafficCondition = TrafficCondition.UNKNOWN,
    val isActive: Boolean = false
) {
    /** Effective ETA including traffic delay. */
    val effectiveDuration: Long get() = baseDuration + trafficDelay
}

data class NavigationState(
    val isNavigating: Boolean = false,
    val destination: LatLngPoint? = null,
    // ── Active route ──────────────────────────────────────────────────────────
    val route: List<LatLngPoint> = emptyList(),
    val steps: List<RouteStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val distanceToNextTurn: Double = 0.0,      // metres
    val totalDistanceRemaining: Double = 0.0,
    val estimatedTimeRemaining: Long = 0L,      // seconds
    val currentInstruction: String = "",
    val nextInstruction: String = "",
    // ── Alternate routes ─────────────────────────────────────────────────────
    val alternateRoutes: List<AlternateRoute> = emptyList(),
    val activeRouteIndex: Int = 0,             // index into alternateRoutes
    // ── Dynamic update state ─────────────────────────────────────────────────
    val isOffRoute: Boolean = false,
    val isRerouting: Boolean = false,
    val rerouteReason: RerouteReason = RerouteReason.NONE,
    val trafficCondition: TrafficCondition = TrafficCondition.UNKNOWN,
    val lastRerouteTimeMs: Long = 0L,
    // Intermediate waypoints — preserved across reroutes so the driver
    // always returns to the original stops after a deviation.
    val viaWaypoints: List<LatLngPoint> = emptyList()
)

data class RouteStep(
    val instruction: String,
    val maneuver: String,
    val distance: Double,    // meters
    val duration: Double,    // seconds
    val startLocation: LatLngPoint,
    val endLocation: LatLngPoint,
    val heading: Double = 0.0
)

// ─── Track Stats ──────────────────────────────────────────────────────────────

data class TrackStats(
    val distance: Double = 0.0,       // meters
    val duration: Long = 0L,          // milliseconds
    val avgSpeed: Float = 0f,         // km/h
    val maxSpeed: Float = 0f,         // km/h
    val minAltitude: Double = 0.0,
    val maxAltitude: Double = 0.0,
    val elevationGain: Double = 0.0,
    val elevationLoss: Double = 0.0,
    val calories: Int = 0,
    val pointCount: Int = 0
)

// ─── Location Point ───────────────────────────────────────────────────────────

data class LatLngPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

// ─── Map Settings ─────────────────────────────────────────────────────────────

data class MapSettings(
    val mapType: MapType = MapType.STANDARD,
    val isTrafficEnabled: Boolean = false,
    val isCompassEnabled: Boolean = true,
    val isScaleBarEnabled: Boolean = true,
    val isFollowMode: Boolean = true,
    val tiltEnabled: Boolean = true,
    val zoom: Double = 15.0
)

enum class MapType {
    STANDARD, SATELLITE, TERRAIN, HYBRID, NIGHT
}

// ─── Weather Data ─────────────────────────────────────────────────────────────

data class WeatherData(
    val temperature: Double = 0.0,
    val feelsLike: Double = 0.0,
    val humidity: Int = 0,
    val windSpeed: Double = 0.0,
    val windDirection: Int = 0,
    val description: String = "",
    val icon: String = "",
    val visibility: Int = 0,
    val pressure: Int = 0,
    val uvIndex: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

// ─── Search Result ────────────────────────────────────────────────────────────

data class SearchResult(
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val type: String = "",
    val importance: Double = 0.0,
    val boundingBox: List<Double>? = null
)

// ─── POI Category ─────────────────────────────────────────────────────────────

enum class PoiCategory(val displayName: String, val icon: Int) {
    RESTAURANT("Restaurant", 0),
    FUEL("Gas Station", 0),
    HOSPITAL("Hospital", 0),
    PARKING("Parking", 0),
    HOTEL("Hotel", 0),
    BANK("Bank/ATM", 0),
    SHOPPING("Shopping", 0),
    ENTERTAINMENT("Entertainment", 0),
    NATURE("Nature", 0),
    EDUCATION("Education", 0)
}

// ─── Recording State ──────────────────────────────────────────────────────────

enum class RecordingState {
    IDLE, RECORDING, PAUSED
}

// ─── Satellite Info ───────────────────────────────────────────────────────────

data class SatelliteInfo(
    val svid: Int,                      // Satellite Vehicle ID (PRN)
    val constellation: String,          // "GPS", "GLONASS", "Galileo", "BeiDou", etc.
    val constellationCode: Int,         // GnssStatus.CONSTELLATION_* constant
    val cn0DbHz: Float,                 // Signal strength: carrier-to-noise density dB-Hz
    val elevationDegrees: Float,        // 0° (horizon) – 90° (zenith)
    val azimuthDegrees: Float,          // 0° – 360° clockwise from North
    val hasAlmanac: Boolean,
    val hasEphemeris: Boolean,
    val usedInFix: Boolean,
    val carrierFrequencyHz: Float = 0f  // L1/L2/L5 carrier frequency
)

data class SatelliteState(
    val satellites: List<SatelliteInfo> = emptyList(),
    val totalCount: Int = 0,
    val usedInFixCount: Int = 0,
    val avgCn0: Float = 0f,
    val maxCn0: Float = 0f
)

// ─── Speed Unit ───────────────────────────────────────────────────────────────

enum class SpeedUnit(val label: String, val factor: Float) {
    KMH("km/h", 3.6f),
    MPH("mph", 2.237f),
    KNOTS("kn", 1.944f),
    MS("m/s", 1f)
}

// ─── Distance Unit ────────────────────────────────────────────────────────────

enum class DistanceUnit(val label: String) {
    METRIC("km/m"),
    IMPERIAL("mi/ft")
}
