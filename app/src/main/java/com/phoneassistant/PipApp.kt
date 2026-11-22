package com.phoneassistant

import android.app.Application

/**
 * The first thing that runs, which is why the crash recorder is installed here.
 *
 * A crash before the first frame of a screen leaves the user holding a screen that closes
 * itself, with nothing to read and no way to fetch a log. Recording it at the process level
 * catches the cases a try/catch in an activity cannot: a failure while the class loads, a
 * throw in onResume, or anything on a background thread.
 */
class PipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.watchForCrashes(this)
    }
}
