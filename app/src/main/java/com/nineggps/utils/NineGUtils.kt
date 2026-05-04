// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.utils

import android.location.Location
import com.nineggps.data.model.LatLngPoint
import org.osmdroid.util.GeoPoint
import java.util.concurrent.TimeUnit
import kotlin.math.*

object NineGUtils {

    // ─── Distance / Formatting ────────────────────────────────────────────────

    fun formatDistance(meters: Double, isImperial: Boolean = false): String {
        return if (isImperial) {
            val feet = meters * 3.28084
            if (feet < 5280) String.format("%.0f ft", feet)
            else String.format("%.1f mi", feet / 5280.0)
        } else {
            if (meters < 1000) String.format("%.0f m", meters)
            else String.format("%.2f km", meters / 1000.0)
        }
    }

    fun formatDistanceShort(meters: Double): String {
        return if (meters < 1000) "${meters.toInt()} m"
        else String.format("%.1f km", meters / 1000.0)
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    fun formatSpeed(ms: Float, unit: String = "KMH"): String {
        return when (unit) {
            "KMH"   -> String.format("%.0f", ms * 3.6f)
            "MPH"   -> String.format("%.0f", ms * 2.237f)
            "KNOTS" -> String.format("%.0f", ms * 1.944f)
            "MS"    -> String.format("%.1f", ms)
            else    -> String.format("%.0f", ms * 3.6f)
        }
    }

    fun formatAltitude(meters: Double, isImperial: Boolean = false): String {
        return if (isImperial) {
            String.format("%.0f ft", meters * 3.28084)
        } else {
            String.format("%.0f m", meters)
        }
    }

    fun formatBearing(degrees: Float): String {
        val dirs = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val ix = ((degrees + 11.25f) / 22.5f).toInt() % 16
        return dirs[ix]
    }

    fun formatAccuracy(meters: Float): String = "${meters.toInt()} m"

    fun formatEta(seconds: Long): String {
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins} min"
    }

    // ─── Conversions ──────────────────────────────────────────────────────────

    fun metersToKmh(ms: Float) = ms * 3.6f
    fun kmhToMs(kmh: Float) = kmh / 3.6f
    fun metersToMph(ms: Float) = ms * 2.237f
    fun metersToFeet(m: Double) = m * 3.28084
    fun metersToMiles(m: Double) = m / 1609.344
    fun pressureToAltitude(hPa: Float): Double =
        44330.0 * (1.0 - (hPa / 1013.25).pow(1.0 / 5.255))

    // ─── Coordinate Utilities ─────────────────────────────────────────────────

    fun distanceBetween(p1: LatLngPoint, p2: LatLngPoint): Double {
        val results = FloatArray(1)
        Location.distanceBetween(p1.latitude, p1.longitude, p2.latitude, p2.longitude, results)
        return results[0].toDouble()
    }

    fun bearingBetween(p1: LatLngPoint, p2: LatLngPoint): Double {
        val lat1 = Math.toRadians(p1.latitude)
        val lat2 = Math.toRadians(p2.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun midpoint(p1: LatLngPoint, p2: LatLngPoint): LatLngPoint {
        return LatLngPoint(
            (p1.latitude + p2.latitude) / 2,
            (p1.longitude + p2.longitude) / 2
        )
    }

    fun Location.toLatLngPoint() = LatLngPoint(latitude, longitude, altitude, time)
    fun LatLngPoint.toGeoPoint() = GeoPoint(latitude, longitude, altitude)
    fun GeoPoint.toLatLngPoint() = LatLngPoint(latitude, longitude, altitude)

    // ─── Polyline Decoding ────────────────────────────────────────────────────

    fun decodePolyline(encoded: String, precision: Int = 6): List<LatLngPoint> {
        val factor = 10.0.pow(precision)
        val points = mutableListOf<LatLngPoint>()
        var index = 0
        var lat = 0
        var lng = 0

        while (index < encoded.length) {
            var result = 0
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            result = 0; shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(LatLngPoint(lat / factor, lng / factor))
        }
        return points
    }

    // ─── Calorie Estimation ───────────────────────────────────────────────────

    fun estimateCalories(
        distanceMeters: Double,
        durationMs: Long,
        weightKg: Double = 75.0,
        activityType: String = "DRIVING"
    ): Int {
        val met = when (activityType) {
            "RUNNING"  -> 9.8
            "CYCLING"  -> 7.5
            "WALKING"  -> 3.5
            "HIKING"   -> 6.0
            "DRIVING"  -> 1.5
            else       -> 3.5
        }
        val hours = durationMs / 3_600_000.0
        return (met * weightKg * hours).toInt()
    }

    // ─── Bounding Box ─────────────────────────────────────────────────────────

    data class BoundingBox(
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    fun getBoundingBox(points: List<LatLngPoint>): BoundingBox? {
        if (points.isEmpty()) return null
        return BoundingBox(
            points.minOf { it.latitude },
            points.maxOf { it.latitude },
            points.minOf { it.longitude },
            points.maxOf { it.longitude }
        )
    }

    fun expandBoundingBox(box: BoundingBox, paddingMeters: Double): BoundingBox {
        val latPad = paddingMeters / 111_111.0
        val lonPad = paddingMeters / (111_111.0 * cos(Math.toRadians((box.minLat + box.maxLat) / 2)))
        return BoundingBox(
            box.minLat - latPad, box.maxLat + latPad,
            box.minLon - lonPad, box.maxLon + lonPad
        )
    }

    // ─── Maneuver Icon ────────────────────────────────────────────────────────

    fun getManeuverIcon(maneuverType: String, modifier: String?): Int {
        return when (maneuverType) {
            "turn" -> when (modifier) {
                "left" -> com.nineggps.R.drawable.ic_turn_left
                "right" -> com.nineggps.R.drawable.ic_turn_right
                "sharp left" -> com.nineggps.R.drawable.ic_turn_sharp_left
                "sharp right" -> com.nineggps.R.drawable.ic_turn_sharp_right
                "slight left" -> com.nineggps.R.drawable.ic_turn_slight_left
                "slight right" -> com.nineggps.R.drawable.ic_turn_slight_right
                "uturn" -> com.nineggps.R.drawable.ic_uturn
                else -> com.nineggps.R.drawable.ic_straight
            }
            "roundabout", "rotary" -> com.nineggps.R.drawable.ic_roundabout
            "arrive" -> com.nineggps.R.drawable.ic_destination
            "depart" -> com.nineggps.R.drawable.ic_start
            "merge" -> com.nineggps.R.drawable.ic_merge
            "ramp" -> when (modifier) {
                "left" -> com.nineggps.R.drawable.ic_ramp_left
                "right" -> com.nineggps.R.drawable.ic_ramp_right
                else -> com.nineggps.R.drawable.ic_straight
            }
            else -> com.nineggps.R.drawable.ic_straight
        }
    }

    // ─── GPS Signal Quality ───────────────────────────────────────────────────

    fun getSignalQuality(accuracy: Float): String {
        return when {
            accuracy <= 0f -> "No Fix"
            accuracy < 5   -> "Excellent"
            accuracy < 10  -> "Good"
            accuracy < 20  -> "Fair"
            accuracy < 50  -> "Poor"
            else           -> "No Fix"
        }
    }

    fun getSignalQuality(accuracy: Float, isFixed: Boolean): String {
        if (!isFixed) return "No Fix"
        return when {
            accuracy <= 0f -> "Acquiring"
            accuracy < 5   -> "Excellent"
            accuracy < 10  -> "Good"
            accuracy < 20  -> "Fair"
            accuracy < 50  -> "Poor"
            else           -> "Poor"
        }
    }

    fun getSignalStrength(accuracy: Float): Int { // 0-4
        return when {
            accuracy < 5   -> 4
            accuracy < 10  -> 3
            accuracy < 20  -> 2
            accuracy < 50  -> 1
            else           -> 0
        }
    }
}
