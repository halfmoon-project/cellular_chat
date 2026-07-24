package com.cellularchat.app.ui

/**
 * Pure Korean spoken descriptions for TalkBack (IMPLEMENTATION_PLAN.md §8,
 * Phase 8). [DirectionView] is a custom canvas with no inherent semantics, so
 * its contentDescription is built here from the fresh measurement only and
 * updated on every angle sample. Kept resource-free and pure so it is unit
 * tested without an Activity.
 */
object FindAccessibility {
    /** Direction + distance for the direction indicator, e.g. "오른쪽, 약 3.2미터". */
    fun directionDescription(azimuthDegrees: Double, distanceMeters: Double?): String {
        var a = azimuthDegrees % 360.0
        if (a > 180.0) a -= 360.0
        if (a < -180.0) a += 360.0
        val direction = when {
            a in -20.0..20.0 -> "정면"
            a > 20.0 && a < 160.0 -> "오른쪽"
            a < -20.0 && a > -160.0 -> "왼쪽"
            else -> "뒤쪽"
        }
        return if (distanceMeters != null) "%s, 약 %.1f미터".format(direction, distanceMeters) else direction
    }
}
