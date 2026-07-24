package com.cellularchat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FindAccessibilityTest {
    @Test
    fun frontAheadWithDistance() {
        assertEquals("정면, 약 %.1f미터".format(3.2), FindAccessibility.directionDescription(0.0, 3.2))
    }

    @Test
    fun rightAndLeftBuckets() {
        assertEquals("오른쪽", FindAccessibility.directionDescription(90.0, null))
        assertEquals("왼쪽", FindAccessibility.directionDescription(-90.0, null))
    }

    @Test
    fun behindBucket() {
        assertEquals("뒤쪽", FindAccessibility.directionDescription(180.0, null))
    }

    @Test
    fun anglesAreNormalizedToPlusMinus180() {
        // 370° normalizes to 10° -> still 정면.
        assertEquals("정면", FindAccessibility.directionDescription(370.0, null))
        // -190° normalizes to 170° -> 뒤쪽.
        assertEquals("뒤쪽", FindAccessibility.directionDescription(-190.0, null))
    }

    @Test
    fun distanceIsAppendedWhenPresent() {
        assertEquals("왼쪽, 약 %.1f미터".format(1.0), FindAccessibility.directionDescription(-90.0, 1.0))
    }
}
