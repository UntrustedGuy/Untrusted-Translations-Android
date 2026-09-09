package com.untrustedtranslations.android.processing

import android.util.Log

/**
 * Lightweight diagnostic timer. Logs a summary line when the user finishes a page,
 * so they can share it for debugging slow performance on long comics.
 *
 * Usage:
 *   val perf = PerformanceProfiler()
 *   perf.lap("OCR")   // times the step
 *   perf.lap("Translate")
 *   perf.log()        // prints "OCR=1.2s Translate=0.8s TOTAL=2.0s"
 */
class PerformanceProfiler {
    private val timestamps = mutableListOf(Triple(System.nanoTime(), "start", 0L))
    private var running = false

    /** Begin the timer; resets any previous state. */
    fun start() {
        timestamps.clear()
        timestamps.add(Triple(System.nanoTime(), "start", 0L))
        running = true
    }

    /** Record a lap at the current moment. Call [start] first. */
    fun lap(label: String) {
        if (!running) return
        val now = System.nanoTime()
        val elapsed = (now - timestamps.last().first) / 1_000_000L
        timestamps.add(Triple(now, label, elapsed))
    }

    /**
     * Write the timing summary to Logcat (tag: "UntrustedPerf").
     * The line includes per-step durations and a total.
     */
    fun log(message: String = "") {
        running = false
        if (timestamps.size < 2) return
        val total = (timestamps.last().first - timestamps[0].first) / 1_000_000L
        val steps = timestamps.drop(1).joinToString(" ") { (_, label, ms) ->
            val sec = "%.1f".format(ms / 1000f)
            "$label=${sec}s"
        }
        Log.i("UntrustedPerf", "$steps TOTAL=${total / 1000f}s  $message")
    }
}
