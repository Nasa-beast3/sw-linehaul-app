package com.steadywheelers.linehaul

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * THE reason this app exists instead of just a browser tab: a foreground
 * service with a persistent, visible notification (same pattern as Google
 * Maps navigation or Uber's driver app) is the one thing Android will not
 * freeze the moment the screen locks. As long as this notification is
 * showing, this code keeps running and keeps reporting location — a plain
 * website tab cannot do that no matter what JavaScript trick is tried.
 *
 * It reports to the SAME audit trail as the in-browser hourly ping
 * (recordLocationPing / UserLogs "Location Ping: Passed/Failed"), just via
 * a direct HTTP POST to doPost in Code.gs instead of google.script.run,
 * because a background service isn't a web page.
 */
class LocationForegroundService : Service() {

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var pingRunnable: Runnable? = null
    private var lastLocation: Location? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "line_haul_location_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.steadywheelers.linehaul.STOP"

        fun start(context: Context) {
            val intent = Intent(context, LocationForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Waiting for GPS fix..."))
        beginLocationUpdates()
        schedulePings()

        // START_STICKY: if Android kills this service under memory pressure,
        // it restarts it automatically as soon as resources free up — this
        // is what makes it survive being backgrounded for hours, not just
        // minutes.
        return START_STICKY
    }

    private fun beginLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return // permission not granted — nothing this service can do; MainActivity is responsible for requesting it
        }
        try {
            val providers = locationManager.getProviders(true)
            if (providers.contains(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 50f, locationListener)
            }
            if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 50f, locationListener)
            }
        } catch (e: SecurityException) {
            // Permission revoked between the check above and this call — ignore, next scheduled ping will just report "Failed."
        }
    }

    private fun schedulePings() {
        pingRunnable?.let { handler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                sendPing()
                handler.postDelayed(this, LOCATION_PING_INTERVAL_MINUTES * 60_000L)
            }
        }
        pingRunnable = runnable
        handler.post(runnable) // send one immediately, then every interval after that
    }

    private fun sendPing() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val driverName = prefs.getString(PREF_DRIVER_NAME, null)
        val token = prefs.getString(PREF_SESSION_TOKEN, null)
        if (driverName == null || token == null) {
            stopSelf() // logged out from somewhere else — nothing to report, stop running
            return
        }

        val loc = lastLocation
        updateNotification(loc)

        Thread {
            try {
                postLocationToServer(driverName, token, loc)
            } catch (e: Exception) {
                // Network hiccup — nothing to do here, next scheduled ping tries again.
                // The server-side watchdog (logMissedLocationPings in Code.gs) also
                // catches a driver who's been silent for over an hour, as a backstop.
            }
        }.start()
    }

    private fun postLocationToServer(driverName: String, token: String, loc: Location?) {
        if (WEB_APP_URL.startsWith("PASTE_")) return // not configured yet — silently no-op, same pattern as the Slack/WhatsApp placeholders in Code.gs

        val params = StringBuilder()
        params.append("action=").append(enc("nativeLocationPing"))
        params.append("&driver=").append(enc(driverName))
        params.append("&token=").append(enc(token))
        if (loc != null) {
            params.append("&lat=").append(enc(loc.latitude.toString()))
            params.append("&lng=").append(enc(loc.longitude.toString()))
        } else {
            params.append("&locError=").append(enc("unavailable"))
        }

        val url = URL(WEB_APP_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        // Apps Script's /exec endpoint issues a redirect before the real response —
        // HttpURLConnection needs to follow it manually for POST.
        conn.instanceFollowRedirects = false

        OutputStreamWriter(conn.outputStream).use { it.write(params.toString()) }
        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == 303) {
            val redirectUrl = conn.getHeaderField("Location")
            if (redirectUrl != null) {
                val redirectConn = URL(redirectUrl).openConnection() as HttpURLConnection
                redirectConn.connectTimeout = 15_000
                redirectConn.readTimeout = 15_000
                redirectConn.inputStream.close()
                redirectConn.disconnect()
            }
        }
        conn.disconnect()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW // low = no sound/heads-up popup, just a quiet persistent icon
            )
            channel.description = "Shows while your location is being shared for an active trip."
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("SW Line Haul — Location active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(loc: Location?) {
        val text = if (loc != null) "Last reported just now" else "Waiting for GPS fix..."
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        super.onDestroy()
        pingRunnable?.let { handler.removeCallbacks(it) }
        try { locationManager.removeUpdates(locationListener) } catch (e: SecurityException) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
