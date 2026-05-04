// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.data.repository

import com.nineggps.data.db.dao.TrackDao
import com.nineggps.data.db.dao.WaypointDao
import com.nineggps.data.db.entity.TrackEntity
import com.nineggps.data.db.entity.TrackPointEntity
import com.nineggps.data.db.entity.WaypointEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepository @Inject constructor(
    private val trackDao: TrackDao,
    private val waypointDao: WaypointDao
) {
    fun getAllTracks(): Flow<List<TrackEntity>> = trackDao.getAllTracks()

    suspend fun getTrackById(id: Long): TrackEntity? = trackDao.getTrackById(id)

    suspend fun insertTrack(track: TrackEntity): Long = trackDao.insertTrack(track)

    suspend fun updateTrack(track: TrackEntity) = trackDao.updateTrack(track)

    suspend fun deleteTrack(track: TrackEntity) {
        trackDao.deleteTrackPoints(track.id)
        trackDao.deleteTrack(track)
    }

    suspend fun getTrackPoints(trackId: Long): List<TrackPointEntity> =
        trackDao.getTrackPoints(trackId)

    fun getTrackPointsFlow(trackId: Long): Flow<List<TrackPointEntity>> =
        trackDao.getTrackPointsFlow(trackId)

    suspend fun insertTrackPoint(point: TrackPointEntity) =
        trackDao.insertTrackPoint(point)

    suspend fun insertTrackPoints(points: List<TrackPointEntity>) =
        trackDao.insertTrackPoints(points)

    suspend fun getTotalStats(): Triple<Int, Double, Long> {
        return Triple(
            trackDao.getTrackCount(),
            trackDao.getTotalDistance() ?: 0.0,
            trackDao.getTotalDuration() ?: 0L
        )
    }

    // Waypoints
    fun getAllWaypoints(): Flow<List<WaypointEntity>> = waypointDao.getAllWaypoints()
    fun getFavorites(): Flow<List<WaypointEntity>> = waypointDao.getFavorites()
    fun searchWaypoints(query: String): Flow<List<WaypointEntity>> = waypointDao.searchWaypoints(query)

    suspend fun insertWaypoint(waypoint: WaypointEntity): Long = waypointDao.insertWaypoint(waypoint)
    suspend fun updateWaypoint(waypoint: WaypointEntity) = waypointDao.updateWaypoint(waypoint)
    suspend fun deleteWaypoint(waypoint: WaypointEntity) = waypointDao.deleteWaypoint(waypoint)
    suspend fun setFavorite(id: Long, favorite: Boolean) = waypointDao.setFavorite(id, favorite)
}
