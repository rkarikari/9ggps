// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// ─── Track Entity ─────────────────────────────────────────────────────────────

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = 0L,
    val distance: Double = 0.0,          // meters
    val duration: Long = 0L,             // ms
    val avgSpeed: Float = 0f,            // km/h
    val maxSpeed: Float = 0f,            // km/h
    val minAltitude: Double = 0.0,
    val maxAltitude: Double = 0.0,
    val elevationGain: Double = 0.0,
    val elevationLoss: Double = 0.0,
    val calories: Int = 0,
    val pointCount: Int = 0,
    val activityType: String = "DRIVING",
    val color: String = "#FF5722",
    val isExported: Boolean = false,
    val thumbnailPath: String? = null,
    val notes: String = ""
)

// ─── Track Point Entity ───────────────────────────────────────────────────────

@Entity(
    tableName = "track_points",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId")]
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val trackId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val accuracy: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val heartRate: Int? = null
)

// ─── Waypoint Entity ──────────────────────────────────────────────────────────

@Entity(tableName = "waypoints")
data class WaypointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val category: String = "GENERAL",
    val icon: String = "pin",
    val color: String = "#2196F3",
    val address: String = "",
    val phone: String = "",
    val url: String = "",
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val visitedAt: Long? = null,
    val rating: Float = 0f,
    val photoPath: String? = null,
    val notes: String = ""
)

// ─── Geofence Entity ──────────────────────────────────────────────────────────

@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radius: Double,            // meters
    val isActive: Boolean = true,
    val triggerOnEnter: Boolean = true,
    val triggerOnExit: Boolean = true,
    val triggerOnDwell: Boolean = false,
    val dwellDelayMs: Int = 30000,
    val notificationMessage: String = "",
    val color: String = "#4CAF50",
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val enterCount: Int = 0,
    val exitCount: Int = 0,
    val lastTriggeredAt: Long? = null
)

// ─── Geofence Event Entity ────────────────────────────────────────────────────

@Entity(
    tableName = "geofence_events",
    foreignKeys = [
        ForeignKey(
            entity = GeofenceEntity::class,
            parentColumns = ["id"],
            childColumns = ["geofenceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("geofenceId")]
)
data class GeofenceEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val geofenceId: Long,
    val eventType: String, // ENTER, EXIT, DWELL
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double
)

// ─── Speed Camera Entity ──────────────────────────────────────────────────────

@Entity(tableName = "speed_cameras")
data class SpeedCameraEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val bearing: Double = -1.0,     // -1 = all directions
    val speedLimit: Int = 0,        // km/h, 0 = unknown
    val type: String = "FIXED",     // FIXED, MOBILE, AVERAGE, REDLIGHT
    val isVerified: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    val country: String = "",
    val road: String = ""
)

// ─── Offline Map Region Entity ────────────────────────────────────────────────

@Entity(tableName = "offline_regions")
data class OfflineRegionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val minZoom: Int = 10,
    val maxZoom: Int = 16,
    val tileSource: String = "MAPNIK",
    val downloadedAt: Long = System.currentTimeMillis(),
    val sizeBytes: Long = 0,
    val tileCount: Int = 0,
    val isComplete: Boolean = false
)
