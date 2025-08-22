
package com.example.bikelogger.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocationService : Service() {
    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        private const val CHANNEL_ID = "ride_tracking"
        private const val NOTIF_ID = 1001
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var request: LocationRequest
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .setMinUpdateDistanceMeters(1f)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundTracking()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startForegroundTracking() {
        createChannel()
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nagrywanie trasy")
            .setContentText("Licznik rowerowy działa…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)

        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val point = TrackPoint(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    altitude = loc.altitude,
                    timestampMs = System.currentTimeMillis(),
                    speedKmh = (loc.speed.toDouble() * 3.6).coerceAtLeast(0.0)
                )
                CoroutineScope(Dispatchers.Default).launch {
                    LocationBus.emit(point)
                }
            }
        }

        try {
            fused.requestLocationUpdates(request, callback as LocationCallback, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Śledzenie trasy", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    override fun onDestroy() {
        super.onDestroy()
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
