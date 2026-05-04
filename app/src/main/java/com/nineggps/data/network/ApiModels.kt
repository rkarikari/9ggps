// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.data.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// ─── OSRM Routing API ─────────────────────────────────────────────────────────

interface OsrmApi {

    @GET("route/v1/{profile}/{coordinates}")
    suspend fun getRoute(
        @Path("profile") profile: String,         // car, bike, foot
        @Path(value = "coordinates", encoded = true) coordinates: String,  // lon1,lat1;lon2,lat2
        @Query("steps") steps: Boolean = true,
        @Query("geometries") geometries: String = "polyline6",
        @Query("overview") overview: String = "full",
        @Query("annotations") annotations: Boolean = false,
        // Request up to 3 alternative routes from OSRM.
        // Use false for mid-navigation reroutes where latency matters.
        @Query("alternatives") alternatives: Int? = null
    ): OsrmRouteResponse

    @GET("nearest/v1/{profile}/{coordinates}")
    suspend fun getNearestRoad(
        @Path("profile") profile: String,
        @Path("coordinates") coordinates: String,
        @Query("number") number: Int = 1
    ): OsrmNearestResponse
}

data class OsrmRouteResponse(
    val code: String,
    val routes: List<OsrmRoute> = emptyList(),
    val waypoints: List<OsrmWaypoint> = emptyList()
)

data class OsrmRoute(
    val distance: Double,
    val duration: Double,
    val geometry: String,
    val legs: List<OsrmLeg>
)

data class OsrmLeg(
    val distance: Double,
    val duration: Double,
    val steps: List<OsrmStep>,
    val summary: String
)

data class OsrmStep(
    val distance: Double,
    val duration: Double,
    val geometry: String,
    val name: String,
    val mode: String,
    val maneuver: OsrmManeuver,
    val intersections: List<OsrmIntersection> = emptyList(),
    @SerializedName("driving_side") val drivingSide: String = "right"
)

data class OsrmManeuver(
    val type: String,
    val modifier: String? = null,
    val instruction: String? = null,
    @SerializedName("bearing_after") val bearingAfter: Int = 0,
    @SerializedName("bearing_before") val bearingBefore: Int = 0,
    val location: List<Double>
)

data class OsrmIntersection(
    val location: List<Double>,
    val bearings: List<Int>,
    val entry: List<Boolean>,
    @SerializedName("in") val inLane: Int? = null,
    @SerializedName("out") val outLane: Int? = null
)

data class OsrmWaypoint(
    val name: String,
    val location: List<Double>,
    val hint: String,
    val distance: Double
)

data class OsrmNearestResponse(
    val code: String,
    val waypoints: List<OsrmWaypoint>
)

// ─── Nominatim Geocoding API ──────────────────────────────────────────────────

interface NominatimApi {

    @GET("search")
    suspend fun search(
        @Query("q") query: String,
        @Query("format") format: String = "jsonv2",
        @Query("limit") limit: Int = 10,
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("extratags") extraTags: Int = 1,
        @Query("viewbox") viewBox: String? = null,
        @Query("bounded") bounded: Int? = null,
        @Query("countrycodes") countryCodes: String? = null,
        @Query("accept-language") language: String = "en",
        @Query("email") email: String = "contact@nineggps.app"
    ): List<NominatimResult>

    @GET("reverse")
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("format") format: String = "jsonv2",
        @Query("addressdetails") addressDetails: Int = 1,
        @Query("zoom") zoom: Int = 18,
        @Query("accept-language") language: String = "en",
        @Query("email") email: String = "contact@nineggps.app"
    ): NominatimResult
}

data class NominatimResult(
    @SerializedName("place_id") val placeId: Long = 0,
    @SerializedName("display_name") val displayName: String = "",
    val lat: String = "0",
    val lon: String = "0",
    val type: String = "",
    val category: String = "",
    val importance: Double = 0.0,
    @SerializedName("boundingbox") val boundingBox: List<String>? = null,
    val address: NominatimAddress? = null,
    val icon: String? = null
)

data class NominatimAddress(
    val road: String? = null,
    @SerializedName("house_number") val houseNumber: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
    val postcode: String? = null
)

// ─── OpenWeatherMap API ───────────────────────────────────────────────────────

interface OpenWeatherApi {

    @GET("data/2.5/weather")
    suspend fun getCurrentWeather(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric",
        @Query("lang") lang: String = "en"
    ): WeatherResponse

    @GET("data/2.5/forecast")
    suspend fun getForecast(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("appid") apiKey: String,
        @Query("units") units: String = "metric",
        @Query("cnt") count: Int = 8,
        @Query("lang") lang: String = "en"
    ): ForecastResponse
}

data class WeatherResponse(
    val coord: WeatherCoord,
    val weather: List<WeatherCondition>,
    val main: WeatherMain,
    val wind: WeatherWind,
    val clouds: WeatherClouds,
    val visibility: Int = 0,
    val sys: WeatherSys? = null,
    val name: String = "",
    @SerializedName("dt") val timestamp: Long = 0L
)

data class WeatherCoord(val lat: Double, val lon: Double)
data class WeatherCondition(val id: Int, val main: String, val description: String, val icon: String)
data class WeatherMain(
    val temp: Double,
    @SerializedName("feels_like") val feelsLike: Double,
    val humidity: Int,
    val pressure: Int,
    @SerializedName("temp_min") val tempMin: Double,
    @SerializedName("temp_max") val tempMax: Double
)
data class WeatherWind(val speed: Double, val deg: Int, val gust: Double = 0.0)
data class WeatherClouds(val all: Int)
data class WeatherSys(val sunrise: Long, val sunset: Long, val country: String)

data class ForecastResponse(
    val list: List<ForecastItem>,
    val city: ForecastCity
)
data class ForecastItem(
    val dt: Long,
    val main: WeatherMain,
    val weather: List<WeatherCondition>,
    val wind: WeatherWind
)
data class ForecastCity(val name: String, val country: String)
