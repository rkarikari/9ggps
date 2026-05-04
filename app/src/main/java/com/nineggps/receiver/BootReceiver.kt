// Copyright © R.N.K 9G5AR RadioZport
package com.nineggps.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nineggps.service.GpsTrackingService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED
            )
        ) {
            // Restart GPS service if it was running
            val serviceIntent = Intent(context, GpsTrackingService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
