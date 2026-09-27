package com.steadywheelers.linehaul

import android.content.Context
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
}
