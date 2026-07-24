package com.cellularchat.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangingTechnologyLabelTest {
    @Test
    fun mapsEachRangingManagerConstant() {
        // android.ranging.RangingManager: UWB=0, BLE_CS=1, WIFI_NAN_RTT=2, BLE_RSSI=3.
        assertEquals(RangingTechnologyLabel.Tech.UWB, RangingTechnologyLabel.of(0))
        assertEquals(RangingTechnologyLabel.Tech.BLE_CS, RangingTechnologyLabel.of(1))
        assertEquals(RangingTechnologyLabel.Tech.WIFI_NAN_RTT, RangingTechnologyLabel.of(2))
        assertEquals(RangingTechnologyLabel.Tech.BLE_RSSI, RangingTechnologyLabel.of(3))
    }

    @Test
    fun unknownTechnologyYieldsNull() {
        assertNull(RangingTechnologyLabel.of(-1))
        assertNull(RangingTechnologyLabel.of(99))
    }
}
