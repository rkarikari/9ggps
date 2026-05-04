// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.utils

import android.content.Context
import com.nineggps.data.db.entity.OfflineRegionEntity
import kotlinx.coroutines.*
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import java.io.File

/**
 * Manages offline map tile downloading and caching for OSMDroid.
 * Provides methods to pre-cache map tiles for a given bounding box.
 */
object OfflineMapManager {

    data class DownloadProgress(
        val downloaded: Int,
        val total: Int,
        val percent: Float = if (total > 0) downloaded / total.toFloat() * 100f else 0f
    )

    /**
     * Estimate tile count for a bounding box across zoom levels.
     * Each zoom level multiplies by ~4.
     */
    fun estimateTileCount(box: BoundingBox, minZoom: Int, maxZoom: Int): Int {
        var count = 0
        for (zoom in minZoom..maxZoom) {
            val latTiles = tileCount(box.latNorth, box.latSouth, zoom)
            val lonTiles = tileCount(box.lonWest, box.lonEast, zoom)
            count += latTiles * lonTiles
        }
        return count
    }

    private fun tileCount(a: Double, b: Double, zoom: Int): Int {
        val tileA = latToTile(a, zoom)
        val tileB = latToTile(b, zoom)
        return Math.abs(tileA - tileB) + 1
    }

    private fun latToTile(lat: Double, zoom: Int): Int {
        val n = Math.pow(2.0, zoom.toDouble())
        val latRad = Math.toRadians(lat)
        return ((1.0 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    }

    /**
     * Pre-cache tiles for a bounding box. Runs in a coroutine.
     *
     * Tiles are written to the OSMDroid flat-file cache at:
     *   `{osmdroidTileCache}/{tileSourceName}/{zoom}/{x}/{y}.png`
     *
     * This is the exact path that OSMDroid's [MapTileFilesystemProvider] reads
     * from, so cached tiles are served immediately without a network round-trip
     * the next time the map pans over the same area.
     */
    suspend fun downloadRegion(
        context: Context,
        box: BoundingBox,
        minZoom: Int,
        maxZoom: Int,
        onProgress: (DownloadProgress) -> Unit,
        onComplete: (OfflineRegionEntity) -> Unit
    ) = withContext(Dispatchers.IO) {
        val estimatedTiles = estimateTileCount(box, minZoom, maxZoom)
        var downloaded = 0

        // Root of the OSMDroid tile cache — NOT a custom sub-folder.
        // OSMDroid's MapTileFilesystemProvider resolves tiles as:
        //   {osmdroidTileCache}/{tileSourceName}/{zoom}/{x}/{y}.png
        val cacheRoot = Configuration.getInstance().osmdroidTileCache
        cacheRoot.mkdirs()

        val tileSource = TileSourceFactory.MAPNIK

        for (zoom in minZoom..maxZoom) {
            if (!isActive) break

            val xMin = lonToTile(box.lonWest, zoom)
            val xMax = lonToTile(box.lonEast, zoom)
            val yMin = latToTileY(box.latNorth, zoom)
            val yMax = latToTileY(box.latSouth, zoom)

            for (x in xMin..xMax) {
                for (y in yMin..yMax) {
                    if (!isActive) break
                    try {
                        val url = tileSource.getTileURLString(
                            MapTileIndex.getTileIndex(zoom, x, y)
                        )
                        downloadTile(url, zoom, x, y, cacheRoot, tileSource.name())
                        downloaded++
                        withContext(Dispatchers.Main) {
                            onProgress(DownloadProgress(downloaded, estimatedTiles))
                        }
                        delay(50) // Rate limiting
                    } catch (e: Exception) {
                        // Skip failed tiles — keep going for the rest of the region
                    }
                }
            }
        }

        val region = OfflineRegionEntity(
            name = "Offline Region ${System.currentTimeMillis()}",
            minLat = box.latSouth,
            maxLat = box.latNorth,
            minLon = box.lonWest,
            maxLon = box.lonEast,
            minZoom = minZoom,
            maxZoom = maxZoom,
            tileCount = downloaded,
            isComplete = true
        )

        withContext(Dispatchers.Main) {
            onComplete(region)
        }
    }

    private fun lonToTile(lon: Double, zoom: Int): Int {
        val n = Math.pow(2.0, zoom.toDouble())
        return ((lon + 180.0) / 360.0 * n).toInt()
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val n = Math.pow(2.0, zoom.toDouble())
        val latRad = Math.toRadians(lat)
        return ((1.0 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    }

    /**
     * Writes a single tile into the OSMDroid flat-file cache at the path:
     *   `{cacheRoot}/{tileSourceName}/{zoom}/{x}/{y}.png`
     *
     * Skips the download if the file already exists (idempotent on retry).
     */
    private fun downloadTile(
        url: String,
        zoom: Int,
        x: Int,
        y: Int,
        cacheRoot: File,
        tileSourceName: String
    ) {
        // Mirror OSMDroid's MapTileFilesystemProvider path convention exactly.
        val tileFile = File(cacheRoot, "$tileSourceName/$zoom/$x/$y.png")
        if (tileFile.exists()) return
        tileFile.parentFile?.mkdirs()
        try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("User-Agent", "NineGGPS")
            connection.inputStream.use { input ->
                tileFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            tileFile.delete()   // Remove partial file so a future retry can re-download it
        }
    }

    // ─── Offline mode helpers ─────────────────────────────────────────────────

    /**
     * Puts [mapView] into offline-first (no network) or normal (network + cache)
     * mode.  Call this whenever the user toggles the "Offline mode" setting.
     *
     * When [offlineOnly] is `true`, OSMDroid will serve tiles exclusively from
     * the local file cache and will never attempt to open a network connection
     * for map tiles.  Regions that have not been pre-downloaded will show as
     * grey empty tiles.
     *
     * When [offlineOnly] is `false` (default), OSMDroid uses its normal
     * cache-then-download strategy: cached tiles are shown immediately; missing
     * tiles are fetched from the tile server in the background.
     */
    fun applyToMapView(mapView: MapView, offlineOnly: Boolean) {
        mapView.setUseDataConnection(!offlineOnly)
        mapView.invalidate()
    }

    /**
     * Returns `true` if at least one tile has been downloaded into the
     * OSMDroid cache, giving the UI enough information to decide whether
     * offering "offline mode" makes sense.
     */
    fun hasOfflineTiles(context: Context): Boolean {
        val cacheDir = Configuration.getInstance().osmdroidTileCache ?: return false
        return cacheDir.walkTopDown().any { it.isFile && it.extension == "png" }
    }

    fun getCacheSize(context: Context): Long {
        val cacheDir = Configuration.getInstance().osmdroidTileCache
        return cacheDir?.walkBottomUp()?.fold(0L) { acc, file -> acc + file.length() } ?: 0L
    }

    fun clearCache(context: Context) {
        Configuration.getInstance().osmdroidTileCache?.deleteRecursively()
    }

    fun formatCacheSize(bytes: Long): String {
        return when {
            bytes < 1024       -> "$bytes B"
            bytes < 1048576    -> "${bytes / 1024} KB"
            bytes < 1073741824 -> "${bytes / 1048576} MB"
            else               -> "${bytes / 1073741824} GB"
        }
    }
}
