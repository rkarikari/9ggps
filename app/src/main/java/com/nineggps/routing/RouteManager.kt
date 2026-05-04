// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.routing

import android.location.Location
import com.nineggps.data.model.*
import com.nineggps.utils.NineGUtils
import kotlin.math.*

/**
 * RouteManager centralises all dynamic routing decisions:
 *
 *  • Building [AlternateRoute] objects from raw OSRM responses.
 *  • Detecting when the driver is off-route.
 *  • Scoring routes against simulated / real traffic conditions.
 *  • Deciding when to trigger an automatic reroute.
 *  • Advancing the active step index as the driver moves.
 *
 * All methods are pure / stateless — the caller (GpsTrackingService) owns the
 * [NavigationState] and applies the [RouteUpdate] returned by each call.
 */
object RouteManager {

    // ─── Tuning constants ─────────────────────────────────────────────────────

    /** metres from the route polyline before we consider the driver off-route. */
    const val OFF_ROUTE_THRESHOLD_M = 60.0

    /**
     * Seconds of extra delay that makes the traffic engine prefer an alternate.
     * i.e. if the active route has 120 s more delay than the best alternate,
     * suggest switching.
     */
    const val TRAFFIC_REROUTE_THRESHOLD_S = 120L

    /** Minimum milliseconds between automatic reroute attempts. */
    const val REROUTE_COOLDOWN_MS = 30_000L

    /** metres ahead to look for the "snap" point when advancing steps. */
    private const val STEP_ADVANCE_M = 25.0

    // ─── Alternate-route factory ──────────────────────────────────────────────

    /**
     * Converts a list of raw OSRM routes (already decoded) into [AlternateRoute]
     * objects with traffic scoring applied.
     *
     * @param routes         Pairs of (polyline points, steps) in OSRM order.
     * @param trafficFactors Per-route traffic-delay multiplier (0 = free flow,
     *                       1 = 100% extra, etc.).  Caller derives these from a
     *                       traffic data source; when unavailable pass all zeros.
     */
    fun buildAlternateRoutes(
        routes: List<RawRoute>,
        trafficFactors: List<Double> = emptyList()
    ): List<AlternateRoute> {
        return routes.mapIndexed { index, raw ->
            val factor = trafficFactors.getOrElse(index) { 0.0 }
            val delay  = (raw.baseDuration * factor).toLong()
            val cond   = trafficConditionFromFactor(factor)
            val label  = when (index) {
                0    -> "Fastest"
                1    -> "Alternate 1"
                2    -> "Alternate 2"
                else -> "Alternate $index"
            }
            AlternateRoute(
                id               = index,
                label            = label,
                route            = raw.points,
                steps            = raw.steps,
                totalDistance    = raw.distance,
                baseDuration     = raw.baseDuration,
                trafficDelay     = delay,
                trafficCondition = cond,
                isActive         = index == 0
            )
        }
    }

    /** Classify a numeric delay factor into a [TrafficCondition] level. */
    fun trafficConditionFromFactor(factor: Double): TrafficCondition = when {
        factor <= 0.05 -> TrafficCondition.FREE_FLOW
        factor <= 0.20 -> TrafficCondition.MODERATE
        factor <= 0.50 -> TrafficCondition.HEAVY
        factor > 0.50  -> TrafficCondition.STANDSTILL
        else           -> TrafficCondition.UNKNOWN
    }

    // ─── Off-route detection ──────────────────────────────────────────────────

    /**
     * Returns true when [location] is more than [OFF_ROUTE_THRESHOLD_M] metres
     * from every point in the active route polyline segment near the driver.
     *
     * We only check the current step's geometry (± 1 step) to avoid false
     * positives from the polyline far ahead folding back near the driver.
     */
    fun isOffRoute(location: Location, state: NavigationState): Boolean {
        if (state.route.isEmpty()) return false

        // Build the candidate point window: current step ± 1 step worth of points.
        val windowPoints = routeWindowAroundStep(state)
        if (windowPoints.isEmpty()) return false

        val minDist = windowPoints.minOf { pt ->
            distanceBetweenM(
                location.latitude, location.longitude,
                pt.latitude, pt.longitude
            )
        }
        return minDist > OFF_ROUTE_THRESHOLD_M
    }

    // ─── Position → step advancement ─────────────────────────────────────────

    /**
     * Given the driver's current [location] and the active [state], returns a
     * [RouteUpdate] describing:
     *  • Whether the step index should advance.
     *  • Whether the driver has arrived.
     *  • Updated distance-to-turn, remaining distance, and ETA.
     *  • An off-route flag.
     *  • Whether a traffic-triggered reroute should fire.
     */
    fun computeRouteUpdate(
        location: Location,
        state: NavigationState,
        speedKmh: Float
    ): RouteUpdate {
        val currentStep = state.steps.getOrNull(state.currentStepIndex)
            ?: return RouteUpdate(arrived = true)

        val stepEnd = currentStep.endLocation
        val distToTurn = distanceBetweenM(
            location.latitude, location.longitude,
            stepEnd.latitude, stepEnd.longitude
        )

        // ── Arrival check ─────────────────────────────────────────────────────
        val isLastStep = state.currentStepIndex >= state.steps.size - 1
        if (isLastStep && distToTurn < STEP_ADVANCE_M) {
            return RouteUpdate(arrived = true)
        }

        // ── Step advance ──────────────────────────────────────────────────────
        val nextStepIndex = if (distToTurn < STEP_ADVANCE_M && !isLastStep) {
            state.currentStepIndex + 1
        } else {
            state.currentStepIndex
        }

        // When the step just advanced the driver is now at the START of the new
        // step.  distToTurn still holds the distance to the end of the OLD step
        // (~25 m), which is not the distance to the next turn and would cause
        // steps[nextStepIndex].distance to be silently dropped from the remaining
        // total.  Recompute against the new step's end location instead.
        val effectiveDistToTurn: Double = if (nextStepIndex != state.currentStepIndex) {
            val newStep = state.steps.getOrNull(nextStepIndex)
            if (newStep != null) {
                distanceBetweenM(
                    location.latitude, location.longitude,
                    newStep.endLocation.latitude, newStep.endLocation.longitude
                )
            } else distToTurn
        } else distToTurn

        // ── Remaining distance ────────────────────────────────────────────────
        var remaining = effectiveDistToTurn
        for (i in (nextStepIndex + 1) until state.steps.size) {
            remaining += state.steps[i].distance
        }

        // ── ETA (speed-based, fall back to step durations) ────────────────────
        val timeRemaining: Long = if (speedKmh > 2f) {
            (remaining / (speedKmh / 3.6)).toLong()
        } else {
            // sum remaining step durations
            state.steps.drop(nextStepIndex).sumOf { it.duration }.toLong()
        }

        // ── Off-route ─────────────────────────────────────────────────────────
        val offRoute = isOffRoute(location, state)

        // ── Traffic reroute trigger ───────────────────────────────────────────
        val now = System.currentTimeMillis()
        val cooldownOk = (now - state.lastRerouteTimeMs) > REROUTE_COOLDOWN_MS
        val trafficReroute = cooldownOk && shouldRerouteForTraffic(state)

        return RouteUpdate(
            nextStepIndex    = nextStepIndex,
            distToTurn       = effectiveDistToTurn,
            remaining        = remaining,
            timeRemaining    = timeRemaining,
            isOffRoute       = offRoute,
            triggerTrafficReroute = trafficReroute,
            arrived          = false
        )
    }

    // ─── Traffic-aware route selection ────────────────────────────────────────

    /**
     * Re-scores [alternates] against a freshly sampled set of traffic factors.
     *
     * [AlternateRoute.trafficDelay] and [AlternateRoute.trafficCondition] are
     * immutable snapshots set when the routes were first decoded — they do not
     * update as the driver travels.  Calling this before any traffic comparison
     * ensures the evaluation reflects current conditions rather than stale data
     * from the start of the journey.
     */
    fun freshenTrafficScores(alternates: List<AlternateRoute>): List<AlternateRoute> {
        if (alternates.isEmpty()) return alternates
        val freshFactors = simulateTrafficFactors(alternates.size)
        return alternates.mapIndexed { i, route ->
            val factor = freshFactors.getOrElse(i) { 0.0 }
            route.copy(
                trafficDelay      = (route.baseDuration * factor).toLong(),
                trafficCondition  = trafficConditionFromFactor(factor)
            )
        }
    }

    /**
     * Returns true when a non-active alternate route has a materially better
     * effective duration than the currently active route.
     *
     * Traffic scores are freshened on every call so the comparison reflects
     * current conditions rather than the snapshot taken when the routes were
     * first fetched.
     */
    fun shouldRerouteForTraffic(state: NavigationState): Boolean {
        if (state.alternateRoutes.size < 2) return false
        val freshAlternates = freshenTrafficScores(state.alternateRoutes)
        val active = freshAlternates.getOrNull(state.activeRouteIndex) ?: return false
        val best   = freshAlternates
            .filterIndexed { i, _ -> i != state.activeRouteIndex }
            .minByOrNull { it.effectiveDuration } ?: return false
        return (active.effectiveDuration - best.effectiveDuration) > TRAFFIC_REROUTE_THRESHOLD_S
    }

    /**
     * Returns the index of the alternate route with the lowest effective
     * duration (free-flow + traffic delay).  Used after a reroute response
     * to auto-select the best route.
     */
    fun bestRouteIndex(alternates: List<AlternateRoute>): Int {
        if (alternates.isEmpty()) return 0
        return alternates.indexOfFirst { it.effectiveDuration == alternates.minOf { r -> r.effectiveDuration } }
            .coerceAtLeast(0)
    }

    /**
     * Applies new [alternates] to [state] and activates route at [newActiveIndex].
     *
     * Returns a fresh [NavigationState] with the selected route's polyline,
     * steps, and step index reset to 0 (the re-route restart point).
     */
    fun applyReroute(
        state: NavigationState,
        alternates: List<AlternateRoute>,
        newActiveIndex: Int,
        reason: RerouteReason
    ): NavigationState {
        val chosen = alternates.getOrNull(newActiveIndex) ?: alternates.firstOrNull()
            ?: return state
        val updatedAlternates = alternates.mapIndexed { i, r -> r.copy(isActive = i == newActiveIndex) }
        return state.copy(
            route                  = chosen.route,
            steps                  = chosen.steps,
            currentStepIndex       = 0,
            alternateRoutes        = updatedAlternates,
            activeRouteIndex       = newActiveIndex,
            isOffRoute             = false,
            isRerouting            = false,
            rerouteReason          = reason,
            trafficCondition       = chosen.trafficCondition,
            totalDistanceRemaining = chosen.totalDistance,
            estimatedTimeRemaining = chosen.effectiveDuration,
            currentInstruction     = chosen.steps.firstOrNull()?.instruction ?: "",
            nextInstruction        = chosen.steps.getOrNull(1)?.instruction ?: "Destination",
            lastRerouteTimeMs      = System.currentTimeMillis()
        )
    }

    /**
     * Switches the active route to [newIndex] without re-fetching from OSRM.
     * Used when the user manually taps an alternate route in the UI.
     */
    fun switchToAlternate(
        state: NavigationState,
        newIndex: Int
    ): NavigationState {
        val alternates = state.alternateRoutes
        if (newIndex < 0 || newIndex >= alternates.size) return state
        val chosen = alternates[newIndex]
        val updated = alternates.mapIndexed { i, r -> r.copy(isActive = i == newIndex) }
        return state.copy(
            route                  = chosen.route,
            steps                  = chosen.steps,
            currentStepIndex       = 0,
            alternateRoutes        = updated,
            activeRouteIndex       = newIndex,
            rerouteReason          = RerouteReason.USER_REQUESTED,
            trafficCondition       = chosen.trafficCondition,
            totalDistanceRemaining = chosen.totalDistance,
            estimatedTimeRemaining = chosen.effectiveDuration,
            currentInstruction     = chosen.steps.firstOrNull()?.instruction ?: "",
            nextInstruction        = chosen.steps.getOrNull(1)?.instruction ?: "Destination",
            lastRerouteTimeMs      = System.currentTimeMillis()
        )
    }

    // ─── Traffic factor simulation ────────────────────────────────────────────

    /**
     * Produces a per-route traffic-delay factor based on time-of-day.
     * Replace with a real traffic API (TomTom, HERE, Google Roads) for production.
     *
     *  • 07:00-09:00 and 17:00-19:00 → heavy
     *  • 11:00-13:00 → moderate
     *  • otherwise → free flow
     *
     * Jitter is applied as a **single global offset** shared across all routes,
     * representing uniform uncertainty in the traffic estimate.  Using independent
     * per-route random values would cause the relative ordering of alternates to
     * flip between GPS ticks, triggering endless oscillating traffic reroutes.
     *
     * Each alternate slot beyond index 0 receives a unique fixed bonus
     * (–3 % per slot) so their ordering is stable and deterministic regardless
     * of the random component.
     */
    fun simulateTrafficFactors(routeCount: Int): List<Double> {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val base = when (hour) {
            in 7..8, in 17..18 -> 0.45   // peak hours
            in 11..12          -> 0.15   // lunch hour
            in 22..23, 0       -> 0.02   // night
            else               -> 0.07   // normal
        }
        // One global jitter shifts ALL routes equally — it represents noise in the
        // traffic estimate that cannot distinguish between routes.
        val globalJitter = (Math.random() * 0.06) - 0.03  // ±3 %
        // Each alternate slot gets a unique fixed bonus.  OSRM already sorts routes
        // by ascending cost, so route i = 1 avoids the most-congested artery,
        // i = 2 the next-most, etc.  A per-slot decrement reflects that tendency
        // and guarantees a stable ordering under any jitter value.
        return List(routeCount) { i ->
            val altBonus = i * -0.03     // 0 %, –3 %, –6 % for indices 0, 1, 2
            (base + globalJitter + altBonus).coerceAtLeast(0.0)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Extracts the slice of the active route polyline that is relevant to
     * off-route detection (points belonging to the current step ± 1).
     */
    private fun routeWindowAroundStep(state: NavigationState): List<LatLngPoint> {
        if (state.steps.isEmpty() || state.route.isEmpty()) return state.route

        val prev = state.steps.getOrNull(state.currentStepIndex - 1)
        val curr = state.steps.getOrNull(state.currentStepIndex)
        val next = state.steps.getOrNull(state.currentStepIndex + 1)

        val anchors = listOfNotNull(
            prev?.startLocation, prev?.endLocation,
            curr?.startLocation, curr?.endLocation,
            next?.startLocation, next?.endLocation
        )
        if (anchors.isEmpty()) return state.route.take(50)

        // Find route indices closest to anchor points and return the span.
        val indices = anchors.map { anchor ->
            state.route.indexOfMinBy { pt ->
                distanceBetweenM(anchor.latitude, anchor.longitude, pt.latitude, pt.longitude)
            }
        }
        val lo = (indices.min() - 5).coerceAtLeast(0)
        val hi = (indices.max() + 5).coerceAtMost(state.route.lastIndex)
        return state.route.subList(lo, hi + 1)
    }

    private fun distanceBetweenM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun <T> List<T>.indexOfMinBy(selector: (T) -> Double): Int {
        if (isEmpty()) return 0
        var minIdx = 0
        var minVal = selector(this[0])
        for (i in 1..lastIndex) {
            val v = selector(this[i])
            if (v < minVal) { minVal = v; minIdx = i }
        }
        return minIdx
    }
}

// ─── Value objects ────────────────────────────────────────────────────────────

/**
 * Intermediate carrier for a single decoded OSRM route before it becomes an
 * [AlternateRoute].
 */
data class RawRoute(
    val points: List<com.nineggps.data.model.LatLngPoint>,
    val steps: List<com.nineggps.data.model.RouteStep>,
    val distance: Double,
    val baseDuration: Long    // seconds, OSRM free-flow
)

/**
 * Result returned by [RouteManager.computeRouteUpdate] on every GPS tick.
 */
data class RouteUpdate(
    val nextStepIndex: Int = 0,
    val distToTurn: Double = 0.0,
    val remaining: Double = 0.0,
    val timeRemaining: Long = 0L,
    val isOffRoute: Boolean = false,
    val triggerTrafficReroute: Boolean = false,
    val arrived: Boolean = false
)
