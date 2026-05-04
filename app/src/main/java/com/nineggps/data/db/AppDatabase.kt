// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nineggps.data.db.dao.*
import com.nineggps.data.db.entity.*

@Database(
    entities = [
        TrackEntity::class,
        TrackPointEntity::class,
        WaypointEntity::class,
        GeofenceEntity::class,
        GeofenceEventEntity::class,
        SpeedCameraEntity::class,
        OfflineRegionEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun waypointDao(): WaypointDao
    abstract fun geofenceDao(): GeofenceDao
    abstract fun speedCameraDao(): SpeedCameraDao
    abstract fun offlineRegionDao(): OfflineRegionDao
}
