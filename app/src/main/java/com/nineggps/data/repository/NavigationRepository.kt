// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.data.repository

import com.nineggps.data.model.*
import com.nineggps.data.network.NominatimApi
import com.nineggps.data.network.OsrmApi
import com.nineggps.data.network.OpenWeatherApi
import com.nineggps.routing.RawRoute
import com.nineggps.routing.RouteManager
import com.nineggps.utils.NineGUtils
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String, val cause: Throwable? = null) : Result<Nothing>()
    object Loading : Result<Nothing>()
}

@Singleton
class NavigationRepository @Inject constructor(
    private val osrmApi: OsrmApi,
    private val nominatimApi: NominatimApi,
    private val openWeatherApi: OpenWeatherApi
) {

    // ─── Geocoding ────────────────────────────────────────────────────────────

    suspend fun search(query: String, nearLat: Double? = null, nearLon: Double? = null): Result<List<SearchResult>> {
        return try {
            val viewbox = if (nearLat != null && nearLon != null) {
                val delta = 0.5
                // Nominatim expects: left,top,right,bottom = min_lon,max_lat,max_lon,min_lat
                "${nearLon - delta},${nearLat + delta},${nearLon + delta},${nearLat - delta}"
            } else null

            val results = nominatimApi.search(
                query = query,
                limit = 10,
                viewBox = viewbox,
                bounded = if (viewbox != null) 0 else null
            )

            val mapped = results.map { r ->
                SearchResult(
                    displayName = r.displayName,
                    latitude = r.lat.toDoubleOrNull() ?: 0.0,
                    longitude = r.lon.toDoubleOrNull() ?: 0.0,
                    type = r.type,
                    importance = r.importance
                )
            }
            Result.Success(mapped)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error("Search failed: ${e.message}", e)
        }
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): Result<String> {
        return try {
            val result = nominatimApi.reverse(lat, lon)
            Result.Success(result.displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error("Reverse geocode failed: ${e.message}", e)
        }
    }

    // ─── Routing ──────────────────────────────────────────────────────────────

    /**
     * Fetches up to 3 alternate routes from OSRM (single-leg trips only —
     * OSRM does not support alternatives for multi-leg/waypoint routes),
     * applies traffic scoring, and returns a [NavigationState] whose
     * [NavigationState.alternateRoutes] list is fully populated.
     *
     * The route with the lowest effective ETA (free-flow + traffic delay) is
     * automatically marked active.  The caller can switch to any alternate via
     * [RouteManager.switchToAlternate].
     */
    suspend fun getRouteWithAlternates(
        origin: LatLngPoint,
        destination: LatLngPoint,
        waypoints: List<LatLngPoint> = emptyList(),
        profile: String = "car"
    ): Result<NavigationState> {
        return try {
            val allPoints = mutableListOf(origin) + waypoints + listOf(destination)
            val coordinates = allPoints.joinToString(";") { "${it.longitude},${it.latitude}" }

            // Pass null (omit the parameter) when we cannot use alternatives:
            //  • OSRM only supports alternatives for single-leg routes (2 coords).
            // For every single-leg route — including off-route reroutes — request
            // up to 3 alternatives so that traffic rerouting remains available
            // after the reroute completes.
            val alternativesCount: Int? = when {
                waypoints.isNotEmpty() -> null
                else                   -> 3
            }

            val response = osrmApi.getRoute(
                profile      = profile,
                coordinates  = coordinates,
                steps        = true,
                geometries   = "polyline6",
                overview     = "full",
                alternatives = alternativesCount
            )

            if (response.code != "Ok" || response.routes.isEmpty()) {
                return Result.Error("No route found")
            }

            // ── Decode each OSRM route into a RawRoute ────────────────────────
            val rawRoutes: List<RawRoute> = response.routes.map { osrmRoute ->
                val routePoints = NineGUtils.decodePolyline(osrmRoute.geometry, precision = 6)
                val steps = osrmRoute.legs.flatMap { leg ->
                    leg.steps.map { step ->
                        val stepPoints = NineGUtils.decodePolyline(step.geometry, precision = 6)
                        RouteStep(
                            instruction  = buildInstruction(step.maneuver.type, step.maneuver.modifier, step.name),
                            maneuver     = step.maneuver.type,
                            distance     = step.distance,
                            duration     = step.duration,
                            startLocation = stepPoints.firstOrNull() ?: LatLngPoint(0.0, 0.0),
                            endLocation   = stepPoints.lastOrNull() ?: LatLngPoint(0.0, 0.0),
                            heading       = step.maneuver.bearingAfter.toDouble()
                        )
                    }
                }
                RawRoute(
                    points       = routePoints,
                    steps        = steps,
                    distance     = osrmRoute.distance,
                    baseDuration = osrmRoute.duration.toLong()
                )
            }

            // ── Apply traffic scoring ──────────────────────────────────────────
            // Simulate traffic factors (replace with a real traffic API if available).
            val trafficFactors = RouteManager.simulateTrafficFactors(rawRoutes.size)
            val alternateRoutes = RouteManager.buildAlternateRoutes(rawRoutes, trafficFactors)

            // ── Choose the best route (lowest effective ETA) ──────────────────
            val bestIndex = RouteManager.bestRouteIndex(alternateRoutes)
            val activeAlternates = alternateRoutes.mapIndexed { i, r -> r.copy(isActive = i == bestIndex) }
            val chosen = activeAlternates[bestIndex]

            val navState = NavigationState(
                isNavigating           = true,
                destination            = destination,
                viaWaypoints           = waypoints,  // preserved so reroutes keep the same stops
                route                  = chosen.route,
                steps                  = chosen.steps,
                currentStepIndex       = 0,
                totalDistanceRemaining = chosen.totalDistance,
                estimatedTimeRemaining = chosen.effectiveDuration,
                currentInstruction     = chosen.steps.firstOrNull()?.instruction ?: "",
                nextInstruction        = chosen.steps.getOrNull(1)?.instruction ?: "Destination",
                alternateRoutes        = activeAlternates,
                activeRouteIndex       = bestIndex,
                trafficCondition       = chosen.trafficCondition
            )

            Result.Success(navState)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error("Routing failed: ${e.message}", e)
        }
    }

    /**
     * Backward-compatible single-route helper used by legacy call-sites.
     * Internally delegates to [getRouteWithAlternates].
     */
    suspend fun getRoute(
        origin: LatLngPoint,
        destination: LatLngPoint,
        waypoints: List<LatLngPoint> = emptyList(),
        profile: String = "car"
    ): Result<NavigationState> = getRouteWithAlternates(origin, destination, waypoints, profile)

    private fun buildInstruction(type: String, modifier: String?, streetName: String): String {
        val modStr = modifier?.replace("-", " ") ?: ""
        val street = if (streetName.isNotBlank()) " onto $streetName" else ""
        return when (type) {
            "turn"        -> "Turn $modStr$street"
            "new name"    -> "Continue$street"
            "depart"      -> "Head $modStr$street"
            "arrive"      -> "Arrive at destination"
            "merge"       -> "Merge $modStr$street"
            "on ramp"     -> "Take the ramp $modStr$street"
            "off ramp"    -> "Take the exit $modStr$street"
            "fork"        -> "Keep $modStr at the fork$street"
            "end of road" -> "Turn $modStr at the end of the road$street"
            "roundabout"  -> "Enter the roundabout and take the $modStr exit$street"
            "rotary"      -> "Enter the rotary$street"
            "continue"    -> "Continue $modStr$street"
            else          -> "Continue$street"
        }
    }

    // ─── Weather ──────────────────────────────────────────────────────────────

    suspend fun getWeather(lat: Double, lon: Double, apiKey: String): Result<WeatherData> {
        return try {
            if (apiKey.isBlank() || apiKey == "YOUR_OPENWEATHER_API_KEY") {
                return Result.Error("OpenWeather API key not configured")
            }
            val response = openWeatherApi.getCurrentWeather(lat, lon, apiKey)
            val weather = WeatherData(
                temperature  = response.main.temp,
                feelsLike    = response.main.feelsLike,
                humidity     = response.main.humidity,
                windSpeed    = response.wind.speed,
                windDirection = response.wind.deg,
                description  = response.weather.firstOrNull()?.description?.replaceFirstChar { it.uppercase() } ?: "",
                icon         = response.weather.firstOrNull()?.icon ?: "",
                visibility   = response.visibility,
                pressure     = response.main.pressure,
                timestamp    = System.currentTimeMillis()
            )
            Result.Success(weather)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error("Weather fetch failed: ${e.message}", e)
        }
    }
}

