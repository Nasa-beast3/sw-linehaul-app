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
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val REQUEST_CODE_FILE_CHOOSER = 2001

    // Holds the WebView's callback between "the page tapped Choose file" and
    // "the camera/gallery app returned a result" — the WebView is paused on
    // this the whole time, so losing it means the page's Submit button never
    // sees a file and stays stuck exactly like the bug this fixes.
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraPhotoUri: Uri? = null

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

            // Without this override, the WebView does NOTHING when a page's
            // <input type="file"> "Choose file" button is tapped — no
            // picker, no camera, no error, it just silently sits there.
            // This is what was happening on the Origin/Destination Photo
            // steps: the page itself was fine, the WebView just never asked
            // the OS to show anything.
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val captureIntent = try {
                    val photoFile = createTempPhotoFile()
                    pendingCameraPhotoUri = FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", photoFile
                    )
                    Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(android.provider.MediaStore.EXTRA_OUTPUT, pendingCameraPhotoUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                } catch (e: Exception) {
                    null
                }

                val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }

                val chooser = Intent.createChooser(galleryIntent, "Take or choose a photo")
                if (captureIntent != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
                }

                try {
                    startActivityForResult(chooser, REQUEST_CODE_FILE_CHOOSER)
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    return false
                }
                return true
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

    private fun createTempPhotoFile(): File {
        val dir = File(cacheDir, "camera_photos").apply { mkdirs() }
        val name = "photo_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return File(dir, name)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE_FILE_CHOOSER) return

        val callback = fileUploadCallback
        fileUploadCallback = null
        if (callback == null) return

        if (resultCode != Activity.RESULT_OK) {
            callback.onReceiveValue(null)
            pendingCameraPhotoUri = null
            return
        }

        // A gallery pick comes back in `data`; a camera capture comes back
        // with `data == null` (the photo is wherever EXTRA_OUTPUT pointed,
        // which is the file we already made in onShowFileChooser).
        val resultUri = data?.data ?: pendingCameraPhotoUri
        pendingCameraPhotoUri = null

        if (resultUri != null) {
            callback.onReceiveValue(arrayOf(resultUri))
        } else {
            callback.onReceiveValue(null)
        }
    }
}
