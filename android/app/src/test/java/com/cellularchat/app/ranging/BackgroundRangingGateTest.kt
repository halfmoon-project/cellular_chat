package com.cellularchat.app.ranging

import com.cellularchat.app.core.protocol.RangingMethod
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRangingGateTest {
    @Test
    fun foregroundAlwaysRanges() {
        assertTrue(BackgroundRangingGate.shouldRange(true, RangingMethod.BLE_RSSI, false))
        assertTrue(BackgroundRangingGate.shouldRange(true, RangingMethod.UWB_ANDROID_OOB, false))
    }

    @Test
    fun backgroundStopsNonUwb() {
        // BLE RSSI is non-UWB: foreground-only regardless of the capability flag.
        assertFalse(BackgroundRangingGate.shouldRange(false, RangingMethod.BLE_RSSI, false))
        assertFalse(BackgroundRangingGate.shouldRange(false, RangingMethod.BLE_RSSI, true))
    }

    @Test
    fun backgroundUwbOnlyWithDeviceSupport() {
        // Background UWB runs only when the device reports background support.
        assertFalse(BackgroundRangingGate.shouldRange(false, RangingMethod.UWB_ANDROID_OOB, false))
        assertTrue(BackgroundRangingGate.shouldRange(false, RangingMethod.UWB_ANDROID_OOB, true))
        assertTrue(BackgroundRangingGate.shouldRange(false, RangingMethod.UWB_APPLE_INTEROP, true))
        assertTrue(BackgroundRangingGate.shouldRange(false, RangingMethod.NI_PEER, true))
    }

    @Test
    fun classifiesUwbMethods() {
        assertTrue(BackgroundRangingGate.isUwbMethod(RangingMethod.UWB_ANDROID_OOB))
        assertTrue(BackgroundRangingGate.isUwbMethod(RangingMethod.UWB_APPLE_INTEROP))
        assertTrue(BackgroundRangingGate.isUwbMethod(RangingMethod.NI_PEER))
        assertFalse(BackgroundRangingGate.isUwbMethod(RangingMethod.BLE_RSSI))
    }
}
