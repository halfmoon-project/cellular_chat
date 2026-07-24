package com.cellularchat.app.ranging

import com.cellularchat.app.core.protocol.RangingMethod

/**
 * Background-ranging policy (IMPLEMENTATION_PLAN.md §8). A foreground service
 * does not bypass platform ranging limits: non-UWB ranging is foreground-only,
 * and background UWB is used only when the device explicitly reports
 * background-ranging support. Pure decision so it is unit-tested without radios.
 */
object BackgroundRangingGate {
    private val UWB_METHODS = setOf(
        RangingMethod.UWB_APPLE_INTEROP,
        RangingMethod.UWB_ANDROID_OOB,
        RangingMethod.NI_PEER,
    )

    fun isUwbMethod(method: Int): Boolean = method in UWB_METHODS

    /**
     * Whether ranging may run for [method] given the current lifecycle. In the
     * foreground it always may; in the background only UWB methods may, and only
     * when [backgroundRangingSupported] is reported by the local device.
     */
    fun shouldRange(
        foreground: Boolean,
        method: Int,
        backgroundRangingSupported: Boolean,
    ): Boolean {
        if (foreground) return true
        return isUwbMethod(method) && backgroundRangingSupported
    }
}
