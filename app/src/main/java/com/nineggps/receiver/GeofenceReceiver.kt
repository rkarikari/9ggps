// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.nineggps.NineGApp
import com.nineggps.R

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            val errorMsg = GeofenceStatusCodes.getStatusCodeString(geofencingEvent.errorCode)
            return
        }

        val geofenceTransition = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

        val transitionString = when (geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "Entered"
            Geofence.GEOFENCE_TRANSITION_EXIT  -> "Exited"
            Geofence.GEOFENCE_TRANSITION_DWELL -> "Dwelling in"
            else -> "Unknown transition"
        }

        triggeringGeofences.forEach { geofence ->
            val notificationId = (System.currentTimeMillis() / 1000).toInt()
            val notification = NotificationCompat.Builder(context, NineGApp.CHANNEL_GEOFENCE)
                .setSmallIcon(R.drawable.ic_geofence)
                .setContentTitle("Geofence Alert")
                .setContentText("$transitionString geofence: ${geofence.requestId}")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(notificationId, notification)
        }
    }
}
