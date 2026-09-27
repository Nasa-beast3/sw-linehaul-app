package com.steadywheelers.linehaul

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.*
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * The entire app UI is still Index.html — everything already built (login,
 * trip flow, photos, geofencing, all of it) works completely unchanged
 * inside this WebView, exactly as it does in a phone's browser today. This
 * activity's only real job beyond that is walking through Android's
 * location/notification permission prompts once, and handing off to
 * LocationForegroundService (see WebAppInterface) — that's the part a
 * browser tab could never do.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val REQUEST_CODE_PERMISSIONS = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true // needed for the app's own localStorage-based 24h session persistence
        webView.settings.setGeolocationEnabled(true)
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false // keep all navigation inside the app, never hand off to an external browser
            }
        }

        // In-page geolocation (used by the trip flow itself — Arrived Origin,
        // photo/odometer steps, etc, exactly as in a normal browser) needs
        // this to actually return a location rather than silently fail,
        // since the WebView has no address-bar permission-prompt UI of its
        // own.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, hasLocationPermission(), false)
            }
        }

        requestNeededPermissions()

        if (WEB_APP_URL.startsWith("PASTE_")) {
            webView.loadData(
                "<html><body style='font-family:sans-serif;padding:24px;'>" +
                        "<h3>Setup needed</h3><p>Open <code>Config.kt</code> in the project and paste your " +
                        "deployed Apps Script Web App URL into WEB_APP_URL, then rebuild.</p></body></html>",
                "text/html", "UTF-8"
            )
        } else {
            webView.loadUrl(WEB_APP_URL)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Walks through Android's permission steps in the order the OS actually
     * requires them:
     *  1. Fine location (a normal dialog).
     *  2. On Android 10+, background location is a SEPARATE permission the
     *     OS will not grant from a simple dialog on Android 11+ — it must be
     *     set via the app's own system Settings screen ("Allow all the
     *     time"). This app opens that screen directly for the driver so
     *     they just have to tap the right option once.
     *  3. Notification permission (Android 13+) — needed just to SHOW the
     *     "location active" notification the foreground service depends on.
     */
    private fun requestNeededPermissions() {
        val permissionsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        } else {
            maybeRequestBackgroundLocation()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            maybeRequestBackgroundLocation()
        }
    }

    private fun maybeRequestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return // background location isn't a separate permission before Android 10
        if (!hasLocationPermission()) return // foreground location wasn't granted — nothing to escalate

        val hasBackground = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (hasBackground) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: must be granted from the app's own Settings page — a
            // normal runtime dialog can't ask for "Allow all the time" directly.
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_CODE_PERMISSIONS + 1
            )
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
