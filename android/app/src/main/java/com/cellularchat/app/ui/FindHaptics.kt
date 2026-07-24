package com.cellularchat.app.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.cellularchat.app.ranging.ProximityBand

/**
 * Distance/proximity-driven haptic feedback for the Find screen
 * (IMPLEMENTATION_PLAN.md §8, Phase 8). Repeats a short pulse whose cadence is
 * derived only from the latest fresh measurement: the closer the peer, the
 * faster the pulses. It owns no service of its own — the Activity feeds it the
 * current state and it stops the moment there is no fresh measurement (signal
 * loss, stale, stop, expiry all clear the measurement, yielding a null cadence).
 */
class FindHaptics(context: Context) {
    private val vibrator: Vibrator? =
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    private val handler = Handler(Looper.getMainLooper())
    private var loop: Runnable? = null

    /**
     * Sets the pulse cadence. [intervalMillis] `null` stops pulsing; otherwise
     * pulses repeat every [intervalMillis]. Idempotent restart-safe.
     */
    fun update(intervalMillis: Long?) {
        stop()
        if (intervalMillis == null || vibrator?.hasVibrator() != true) return
        val runnable = object : Runnable {
            override fun run() {
                runCatching {
                    vibrator.vibrate(VibrationEffect.createOneShot(PULSE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
                }
                handler.postDelayed(this, intervalMillis)
            }
        }
        loop = runnable
        handler.post(runnable)
    }

    fun stop() {
        loop?.let { handler.removeCallbacks(it) }
        loop = null
        runCatching { vibrator?.cancel() }
    }

    companion object {
        private const val PULSE_MS = 60L

        /**
         * Pure cadence: interval between pulses in ms, shorter when closer; `null`
         * means no haptic. Precise distance wins over the coarse proximity band;
         * an `UNKNOWN`/absent band and no distance produce no pulse.
         */
        fun intervalMillis(distanceMeters: Double?, band: ProximityBand?): Long? {
            if (distanceMeters != null) {
                val clamped = distanceMeters.coerceIn(0.0, 10.0)
                return (MIN_INTERVAL_MS + clamped / 10.0 * (MAX_INTERVAL_MS - MIN_INTERVAL_MS)).toLong()
            }
            return when (band) {
                ProximityBand.VERY_NEAR -> 300L
                ProximityBand.NEAR -> 700L
                ProximityBand.FAR -> 1500L
                ProximityBand.UNKNOWN, null -> null
            }
        }

        private const val MIN_INTERVAL_MS = 200.0
        private const val MAX_INTERVAL_MS = 1600.0
    }
}
