// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.data.db.dao

import androidx.room.*
import com.nineggps.data.db.entity.*
import kotlinx.coroutines.flow.Flow

// ─── Track DAO ────────────────────────────────────────────────────────────────

@Dao
interface TrackDao {

    @Query("SELECT * FROM tracks ORDER BY startTime DESC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :trackId")
    suspend fun getTrackById(trackId: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE activityType = :type ORDER BY startTime DESC")
    fun getTracksByType(type: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity): Long

    @Update
    suspend fun updateTrack(track: TrackEntity)

    @Delete
    suspend fun deleteTrack(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :trackId")
    suspend fun deleteTrackById(trackId: Long)

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun getTrackCount(): Int

    @Query("SELECT SUM(distance) FROM tracks")
    suspend fun getTotalDistance(): Double?

    @Query("SELECT SUM(duration) FROM tracks")
    suspend fun getTotalDuration(): Long?

    @Query("SELECT * FROM tracks WHERE startTime BETWEEN :from AND :to ORDER BY startTime DESC")
    fun getTracksBetween(from: Long, to: Long): Flow<List<TrackEntity>>

    // Track Points
    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    suspend fun getTrackPoints(trackId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp ASC")
    fun getTrackPointsFlow(trackId: Long): Flow<List<TrackPointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPoint(point: TrackPointEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPoints(points: List<TrackPointEntity>)

    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    suspend fun deleteTrackPoints(trackId: Long)

    @Query("SELECT COUNT(*) FROM track_points WHERE trackId = :trackId")
    suspend fun getPointCountForTrack(trackId: Long): Int

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastTrackPoint(trackId: Long): TrackPointEntity?
}

// ─── Waypoint DAO ─────────────────────────────────────────────────────────────

@Dao
interface WaypointDao {

    @Query("SELECT * FROM waypoints ORDER BY createdAt DESC")
    fun getAllWaypoints(): Flow<List<WaypointEntity>>

    @Query("SELECT * FROM waypoints WHERE isFavorite = 1 ORDER BY name ASC")
    fun getFavorites(): Flow<List<WaypointEntity>>

    @Query("SELECT * FROM waypoints WHERE id = :id")
    suspend fun getWaypointById(id: Long): WaypointEntity?

    @Query("SELECT * FROM waypoints WHERE category = :category ORDER BY createdAt DESC")
    fun getWaypointsByCategory(category: String): Flow<List<WaypointEntity>>

    @Query("""
        SELECT * FROM waypoints WHERE
        name LIKE '%' || :query || '%' OR
        description LIKE '%' || :query || '%' OR
        address LIKE '%' || :query || '%'
        ORDER BY name ASC
    """)
    fun searchWaypoints(query: String): Flow<List<WaypointEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaypoint(waypoint: WaypointEntity): Long

    @Update
    suspend fun updateWaypoint(waypoint: WaypointEntity)

    @Delete
    suspend fun deleteWaypoint(waypoint: WaypointEntity)

    @Query("DELETE FROM waypoints WHERE id = :id")
    suspend fun deleteWaypointById(id: Long)

    @Query("UPDATE waypoints SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("SELECT COUNT(*) FROM waypoints")
    suspend fun getWaypointCount(): Int
}

// ─── Geofence DAO ─────────────────────────────────────────────────────────────

@Dao
interface GeofenceDao {

    @Query("SELECT * FROM geofences ORDER BY createdAt DESC")
    fun getAllGeofences(): Flow<List<GeofenceEntity>>

    @Query("SELECT * FROM geofences WHERE isActive = 1")
    fun getActiveGeofences(): Flow<List<GeofenceEntity>>

    @Query("SELECT * FROM geofences WHERE isActive = 1")
    suspend fun getActiveGeofencesList(): List<GeofenceEntity>

    @Query("SELECT * FROM geofences WHERE id = :id")
    suspend fun getGeofenceById(id: Long): GeofenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeofence(geofence: GeofenceEntity): Long

    @Update
    suspend fun updateGeofence(geofence: GeofenceEntity)

    @Delete
    suspend fun deleteGeofence(geofence: GeofenceEntity)

    @Query("UPDATE geofences SET isActive = :active WHERE id = :id")
    suspend fun setGeofenceActive(id: Long, active: Boolean)

    @Query("UPDATE geofences SET enterCount = enterCount + 1, lastTriggeredAt = :time WHERE id = :id")
    suspend fun incrementEnterCount(id: Long, time: Long)

    @Query("UPDATE geofences SET exitCount = exitCount + 1, lastTriggeredAt = :time WHERE id = :id")
    suspend fun incrementExitCount(id: Long, time: Long)

    // Events
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGeofenceEvent(event: GeofenceEventEntity): Long

    @Query("SELECT * FROM geofence_events WHERE geofenceId = :id ORDER BY timestamp DESC LIMIT 50")
    suspend fun getGeofenceEvents(id: Long): List<GeofenceEventEntity>
}

// ─── Offline Region DAO ───────────────────────────────────────────────────────

@Dao
interface OfflineRegionDao {

    @Query("SELECT * FROM offline_regions ORDER BY downloadedAt DESC")
    fun getAllRegions(): Flow<List<com.nineggps.data.db.entity.OfflineRegionEntity>>

    @Query("SELECT * FROM offline_regions WHERE isComplete = 1 ORDER BY downloadedAt DESC")
    fun getCompleteRegions(): Flow<List<com.nineggps.data.db.entity.OfflineRegionEntity>>

    @Query("SELECT * FROM offline_regions WHERE id = :id")
    suspend fun getRegionById(id: Long): com.nineggps.data.db.entity.OfflineRegionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegion(region: com.nineggps.data.db.entity.OfflineRegionEntity): Long

    @Update
    suspend fun updateRegion(region: com.nineggps.data.db.entity.OfflineRegionEntity)

    @Delete
    suspend fun deleteRegion(region: com.nineggps.data.db.entity.OfflineRegionEntity)

    @Query("DELETE FROM offline_regions WHERE id = :id")
    suspend fun deleteRegionById(id: Long)

    @Query("SELECT COUNT(*) FROM offline_regions WHERE isComplete = 1")
    suspend fun getCompleteRegionCount(): Int

    @Query("SELECT SUM(sizeBytes) FROM offline_regions")
    suspend fun getTotalCacheSize(): Long?

    @Query("SELECT SUM(tileCount) FROM offline_regions WHERE isComplete = 1")
    suspend fun getTotalTileCount(): Int?
}

// ─── Speed Camera DAO ─────────────────────────────────────────────────────────

@Dao
interface SpeedCameraDao {

    @Query("SELECT * FROM speed_cameras")
    fun getAllCameras(): Flow<List<SpeedCameraEntity>>

    @Query("""
        SELECT * FROM speed_cameras WHERE
        latitude BETWEEN :minLat AND :maxLat AND
        longitude BETWEEN :minLon AND :maxLon
    """)
    suspend fun getCamerasInBounds(
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ): List<SpeedCameraEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCamera(camera: SpeedCameraEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCameras(cameras: List<SpeedCameraEntity>)

    @Delete
    suspend fun deleteCamera(camera: SpeedCameraEntity)

    @Query("DELETE FROM speed_cameras")
    suspend fun deleteAll()
}
