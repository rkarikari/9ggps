// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.service

import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.nineggps.NineGApp
import com.nineggps.R
import com.nineggps.data.db.dao.GeofenceDao
import com.nineggps.data.db.entity.GeofenceEventEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GeofenceTransitionService : LifecycleService() {

    @Inject
    lateinit var geofenceDao: GeofenceDao

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        intent?.let { processGeofenceEvent(it) }

        return START_NOT_STICKY
    }

    private fun processGeofenceEvent(intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) { stopSelf(); return }

        val transition = event.geofenceTransition
        val triggeringLocation = event.triggeringLocation
        val geofences = event.triggeringGeofences ?: return

        val eventType = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT  -> "EXIT"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "DWELL"
            else -> return
        }

        CoroutineScope(Dispatchers.IO).launch {
            geofences.forEach { geofence ->
                val id = geofence.requestId.toLongOrNull() ?: return@forEach
                val dbGeofence = geofenceDao.getGeofenceById(id) ?: return@forEach

                // Record event
                geofenceDao.insertGeofenceEvent(
                    GeofenceEventEntity(
                        geofenceId = id,
                        eventType = eventType,
                        latitude = triggeringLocation?.latitude ?: 0.0,
                        longitude = triggeringLocation?.longitude ?: 0.0
                    )
                )

                // Update counters
                when (eventType) {
                    "ENTER" -> geofenceDao.incrementEnterCount(id, System.currentTimeMillis())
                    "EXIT"  -> geofenceDao.incrementExitCount(id, System.currentTimeMillis())
                }

                // Notification
                val title = when (eventType) {
                    "ENTER" -> "Entered: ${dbGeofence.name}"
                    "EXIT"  -> "Exited: ${dbGeofence.name}"
                    "DWELL" -> "Dwelling in: ${dbGeofence.name}"
                    else    -> return@forEach
                }

                sendNotification(title, dbGeofence.notificationMessage.ifBlank { title })
            }
        }

        stopSelf()
    }

    private fun sendNotification(title: String, message: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, NineGApp.CHANNEL_GEOFENCE)
            .setSmallIcon(R.drawable.ic_geofence)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() / 1000).toInt(), notification)
    }
}
