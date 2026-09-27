package com.steadywheelers.linehaul

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface

/**
 * The bridge between the web page (Index.html, running inside the WebView
 * exactly as it does in a normal browser) and this native app. Index.html
 * calls window.AndroidBridge.onLoggedIn(...)/onLoggedOut() right after a
 * real login/logout — see the matching calls added in Index.html's
 * enterAppAs() and logout() functions. Everything else about the app (login
 * screen, trip flow, all of it) is completely unchanged; this bridge only
 * exists so the native background location service knows WHO is currently
 * logged in, since it runs independently of the web page.
 */
class WebAppInterface(private val context: Context) {

    @JavascriptInterface
    fun onLoggedIn(driverName: String, sessionToken: String, role: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(PREF_DRIVER_NAME, driverName)
            .putString(PREF_SESSION_TOKEN, sessionToken)
            .putString(PREF_ROLE, role)
            .apply()

        // Only drivers need location tracked — a supervisor's phone isn't a truck.
        if (role == "Driver") {
            LocationForegroundService.start(context)
        }
    }

    @JavascriptInterface
    fun onLoggedOut() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        LocationForegroundService.stop(context)
    }

    /**
     * Lets Index.html check "is this phone running an old shell?" against
     * the server's LATEST_APP_VERSION (Code.gs) without us needing to push
     * anything through the Play Store — there isn't one. Returns the
     * installed build's versionCode (app/build.gradle's versionCode),
     * bumped by us each time the native Kotlin side changes.
     */
    @JavascriptInterface
    fun getAppVersion(): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }

    /**
     * Opens a URL in the phone's real browser (Chrome, etc), never inside
     * this app's own WebView — needed because the WebView has no download
     * manager of its own and would otherwise just try (and fail) to render
     * the .apk as a page. The real browser downloads it normally and the
     * phone's existing "install unknown apps" permission (already granted
     * once, from installing this app the first time) takes it from there.
     */
    @JavascriptInterface
    fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            // No browser available to handle it — nothing sensible to do here.
        }
    }
}
