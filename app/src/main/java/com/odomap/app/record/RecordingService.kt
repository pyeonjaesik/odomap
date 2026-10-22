package com.odomap.app.record

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.odomap.app.OdomapApp
import com.odomap.app.R
import com.odomap.app.engine.formatKm
import com.odomap.app.ui.MainActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service (type location) that keeps live recording alive with the screen elsewhere. Started by the activity once location
 * permission is granted, and at boot ([BootReceiver]) so a drive is recorded without opening the app.
 */
class RecordingService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification(getString(R.string.notif_idle)),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "cannot start foreground", t)
            stopSelf()
            return
        }
        val recorder = (application as OdomapApp).recorder
        recorder.startLive()
        lifecycleScope.launch {
            recorder.live.collectLatest { live ->
                val text = if (live == null) getString(R.string.notif_idle) else getString(R.string.notif_driving, formatKm(live.distanceM), formatKm(live.pioneeredM))
                nm.notify(NOTIF_ID, notification(text))
                kotlinx.coroutines.delay(5000) // at most one update per 5 s
            }
        }
    }

    override fun onDestroy() {
        (application as OdomapApp).recorder.stopLive()
        super.onDestroy()
    }

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1

        fun canRun(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            if (!canRun(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "cannot start service", t)
            }
        }
    }
}

/** Starts recording after boot (the car is the device: no need to open the app). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // TODO(phase 2): verify on a Pleos head unit — Android 14+ may refuse a location FGS started from the background without
            //  ACCESS_BACKGROUND_LOCATION; the user-0/user-10 split on AAOS also matters.
            RecordingService.start(context)
        }
    }
}
