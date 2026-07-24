package com.cellularchat.app.ui

/**
 * User-selectable Find durations (PROTOCOL_V2.md §10: default 30 minutes, max
 * 2 hours). The chosen value flows through to the foreground-service deadline.
 */
object FindDurations {
    /** Options in minutes: 15분 / 30분 / 1시간 / 2시간. */
    val minuteOptions = listOf(15, 30, 60, 120)

    const val DEFAULT_MINUTES = 30

    val defaultIndex = minuteOptions.indexOf(DEFAULT_MINUTES)

    fun millis(minutes: Int): Long = minutes * 60_000L
}
