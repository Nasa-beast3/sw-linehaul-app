package com.steadywheelers.linehaul

/**
 * ONE place to paste your deployed Apps Script Web App URL — the same
 * /exec link you already use in a browser. Used both to load the app in
 * the WebView, and by the background location service to report location
 * directly (it can't use google.script.run — that only works inside a page
 * Apps Script itself served, and the background service isn't a web page).
 *
 * Pre-filled below with the /exec URL seen earlier in this conversation.
 * Since you deploy with "New version" (not "New deployment"), this link
 * should still be current — but double-check it against Apps Script's
 * Deploy > Manage deployments screen before building, and replace it here
 * if it's changed.
 */
const val WEB_APP_URL = "https://script.google.com/macros/s/AKfycbyggd2FOq-_1vC2lMLv81SneDiJ9BRSMhGwxhbTPveDtW6-6a3OJGA4BIR0qQsMwUu5/exec"

// How often the background service fetches and reports location while a
// driver is logged in — independent of, and in addition to, the app's own
// in-browser hourly ping. Minutes. 15 is a reasonable default for real-time
// tracking without being excessive; change freely.
const val LOCATION_PING_INTERVAL_MINUTES = 15L

const val PREFS_NAME = "line_haul_prefs"
const val PREF_DRIVER_NAME = "driver_name"
const val PREF_SESSION_TOKEN = "session_token"
const val PREF_ROLE = "role"
