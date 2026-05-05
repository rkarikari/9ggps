// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.data.repository

import android.util.Log
import com.nineggps.data.db.dao.SpeedCameraDao
import com.nineggps.data.db.entity.SpeedCameraEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpeedCameraRepository @Inject constructor(
    private val speedCameraDao: SpeedCameraDao,
    private val okHttpClient: OkHttpClient
) {

    // ─── Local DB ─────────────────────────────────────────────────────────────

    fun getAllCameras(): Flow<List<SpeedCameraEntity>> = speedCameraDao.getAllCameras()

    suspend fun insertCamera(camera: SpeedCameraEntity): Long =
        speedCameraDao.insertCamera(camera)

    suspend fun deleteCamera(camera: SpeedCameraEntity) =
        speedCameraDao.deleteCamera(camera)

    suspend fun deleteAll() = speedCameraDao.deleteAll()

    // ─── Overpass API Import ──────────────────────────────────────────────────

    /**
     * Fetches speed cameras from the OpenStreetMap Overpass API within
     * [radiusKm] kilometres of [lat]/[lon], then upserts them into the local DB.
     *
     * Overpass query targets:
     *  - highway=speed_camera   (fixed / ANPR cameras)
     *  - enforcement=maxspeed   (general speed enforcement nodes)
     *
     * Returns the count of cameras successfully imported, or throws on network
     * error (callers should catch and surface to the UI).
     */
    suspend fun fetchFromOverpass(lat: Double, lon: Double, radiusKm: Int = 10): Int =
        withContext(Dispatchers.IO) {
            val radiusM = radiusKm * 1000
            val query = """
                [out:json][timeout:25];
                (
                  node["highway"="speed_camera"](around:$radiusM,$lat,$lon);
                  node["enforcement"="maxspeed"](around:$radiusM,$lat,$lon);
                );
                out body;
            """.trimIndent()

            val requestBody = query.toRequestBody("text/plain".toMediaType())
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(requestBody)
                .build()

            val responseJson = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Overpass API error: HTTP ${response.code}")
                }
                response.body?.string() ?: throw Exception("Empty response from Overpass API")
            }

            val entities = parseOverpassResponse(responseJson)
            if (entities.isNotEmpty()) {
                speedCameraDao.insertCameras(entities)
            }
            Log.d("SpeedCamRepo", "Imported ${entities.size} cameras from Overpass")
            entities.size
        }

    // ─── Overpass JSON Parser ─────────────────────────────────────────────────

    private fun parseOverpassResponse(json: String): List<SpeedCameraEntity> {
        val result = mutableListOf<SpeedCameraEntity>()
        try {
            val root = JSONObject(json)
            val elements = root.getJSONArray("elements")
            for (i in 0 until elements.length()) {
                val node = elements.getJSONObject(i)
                val nodeLat = node.optDouble("lat", Double.NaN)
                val nodeLon = node.optDouble("lon", Double.NaN)
                if (nodeLat.isNaN() || nodeLon.isNaN()) continue

                val tags = node.optJSONObject("tags") ?: JSONObject()

                // Speed limit
                val maxspeedRaw = tags.optString("maxspeed", "")
                val speedLimit = maxspeedRaw.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

                // Camera type
                val type = when {
                    tags.optString("camera:type") == "average" -> "AVERAGE"
                    tags.optString("enforcement") == "traffic_signals" -> "REDLIGHT"
                    tags.optString("mobile") == "yes" -> "MOBILE"
                    else -> "FIXED"
                }

                // Direction
                val direction = tags.optString("direction", "-1")
                val bearing = direction.toDoubleOrNull() ?: -1.0

                // Road name
                val road = tags.optString("addr:street", tags.optString("name", ""))

                result.add(
                    SpeedCameraEntity(
                        latitude   = nodeLat,
                        longitude  = nodeLon,
                        bearing    = bearing,
                        speedLimit = speedLimit,
                        type       = type,
                        isVerified = true,          // OSM data is considered verified
                        road       = road
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("SpeedCamRepo", "Parse error: ${e.message}")
        }
        return result
    }
}
