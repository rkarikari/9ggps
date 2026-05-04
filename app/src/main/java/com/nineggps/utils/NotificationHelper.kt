// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.utils

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nineggps.NineGApp
import com.nineggps.MainActivity
import com.nineggps.R

object NotificationHelper {

    fun buildSpeedAlert(context: Context, currentKmh: Float, limitKmh: Int): Notification {
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, NineGApp.CHANNEL_SPEED_ALERT)
            .setSmallIcon(R.drawable.ic_speed)
            .setContentTitle("Speed Alert")
            .setContentText("${currentKmh.toInt()} km/h in a ${limitKmh} km/h zone")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
    }

    fun notify(context: Context, id: Int, notification: Notification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(id, notification)
    }

    fun cancel(context: Context, id: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(id)
    }
}
