package com.nova.iptv.ui.saver

import android.os.SystemClock

/** Last remote / touch input (hero waits during presses; screensaver starts after a minute without any). */
object UserActivity {
    @Volatile var last: Long = SystemClock.uptimeMillis()
        private set
    fun touch() { last = SystemClock.uptimeMillis() }
    val idleMs: Long get() = SystemClock.uptimeMillis() - last
}
