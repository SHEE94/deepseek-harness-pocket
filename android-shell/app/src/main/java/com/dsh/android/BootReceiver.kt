package com.dsh.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Restarts the engine host after reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val svc = Intent(context, DshEngineService::class.java)
            // targetSdk 28 is still subject to Android 8 background-start limits;
            // BOOT_COMPLETED grants a temporary exemption window, and the service
            // calls startForeground() immediately inside onCreate to honor the promise.
            ContextCompat.startForegroundService(context, svc)
        }
    }
}
