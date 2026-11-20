package com.chun.pibridge

import android.os.SystemClock
import android.util.Log

/** Debug-only timing, without addresses, keys, or conversation content. */
class ConnectionTrace(private val update: (Map<String, Long>) -> Unit = {}) {
    private val started = SystemClock.elapsedRealtime()
    private val phases = linkedMapOf<String, Long>()
    @Synchronized
    fun mark(phase: String) {
        phases[phase] = SystemClock.elapsedRealtime() - started
        update(phases.toMap())
        if (BuildConfig.DEBUG) Log.i("PiBridgeTiming",
            "connection=$started phase=$phase elapsedMs=${SystemClock.elapsedRealtime() - started}")
    }
}
