package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.cbor.Cbor
import com.cellularchat.app.core.cbor.CborMap
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Feature B: the pure capability-transcript checks (PROTOCOL_V2.md §14). */
class CapabilityTranscriptTest {
    private val android = CapabilitySet(CapabilitySet.OS_ANDROID, "16", "2.0.0", uwbPresent = true)
    private val ios = CapabilitySet(CapabilitySet.OS_IOS, "26", "2.0.0", uwbPresent = true)

    @Test
    fun methodSupportedMatrix() {
        // ni_peer: both iOS + both UWB.
        assertTrue(CapabilityTranscript.methodSupported(ios, ios, RangingMethod.NI_PEER))
        assertFalse(CapabilityTranscript.methodSupported(android, android, RangingMethod.NI_PEER))
        assertFalse(CapabilityTranscript.methodSupported(android, ios, RangingMethod.NI_PEER))

        // uwb_android_oob: both Android + both UWB.
        assertTrue(CapabilityTranscript.methodSupported(android, android, RangingMethod.UWB_ANDROID_OOB))
        assertFalse(
            CapabilityTranscript.methodSupported(
                android.copy(uwbPresent = false), android, RangingMethod.UWB_ANDROID_OOB,
            ),
        )
        assertFalse(CapabilityTranscript.methodSupported(android, ios, RangingMethod.UWB_ANDROID_OOB))

        // uwb_apple_interop: mixed OS + both UWB + both interop.
        val androidInterop = android.copy(appleInteropUwb = true)
        val iosInterop = ios.copy(appleInteropUwb = true)
        assertTrue(CapabilityTranscript.methodSupported(androidInterop, iosInterop, RangingMethod.UWB_APPLE_INTEROP))
        assertFalse(CapabilityTranscript.methodSupported(androidInterop, ios, RangingMethod.UWB_APPLE_INTEROP))
        assertFalse(CapabilityTranscript.methodSupported(androidInterop, iosInterop.copy(os = CapabilitySet.OS_ANDROID), RangingMethod.UWB_APPLE_INTEROP))

        // ble_rssi always supported.
        assertTrue(CapabilityTranscript.methodSupported(android, ios, RangingMethod.BLE_RSSI))
    }

    @Test
    fun driftDetectsAnyChangedFieldButNotBenignReencoding() {
        // A round-trip re-encode is normalized to the same 14-field set: no drift.
        val reencoded = CapabilitySet.fromCbor(Cbor.decode(Cbor.encode(android.toCbor())) as CborMap)
        assertFalse(CapabilityTranscript.isReannouncementDrift(android, reencoded))

        // Any changed field is drift — including os.
        assertTrue(CapabilityTranscript.isReannouncementDrift(android, android.copy(os = CapabilitySet.OS_IOS)))
        assertTrue(CapabilityTranscript.isReannouncementDrift(android, android.copy(wifiAware = true)))
        assertTrue(CapabilityTranscript.isReannouncementDrift(android, android.copy(osVersion = "17")))
    }
}
