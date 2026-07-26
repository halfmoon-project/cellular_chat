package com.cellularchat.app.ui

import com.cellularchat.app.core.protocol.CapabilitySet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reasons come from capability booleans only (PROTOCOL_V2.md §11) — never
 * OS-version strings. */
class UwbUnavailableReasonTest {

    private fun caps(os: Int, uwb: Boolean = false, interop: Boolean = false) =
        CapabilitySet(os, "", "", uwbPresent = uwb, appleInteropUwb = interop)

    @Test
    fun peerCapsUnknownBeforeNegotiation() {
        assertEquals(
            UwbUnavailableReason.PEER_CAPS_UNKNOWN,
            UwbUnavailableReason.of(caps(CapabilitySet.OS_ANDROID, uwb = true), null),
        )
    }

    @Test
    fun peerWithoutUwb() {
        assertEquals(
            UwbUnavailableReason.PEER_NO_UWB,
            UwbUnavailableReason.of(
                caps(CapabilitySet.OS_ANDROID, uwb = true),
                caps(CapabilitySet.OS_IOS),
            ),
        )
    }

    @Test
    fun peerWithoutInterop() {
        // The Android ↔ iOS 18 case: the iPhone has a UWB radio but advertises
        // appleInteropUwb=false (interop needs iOS 26.1+).
        assertEquals(
            UwbUnavailableReason.PEER_NO_INTEROP,
            UwbUnavailableReason.of(
                caps(CapabilitySet.OS_ANDROID, uwb = true, interop = true),
                caps(CapabilitySet.OS_IOS, uwb = true),
            ),
        )
    }

    @Test
    fun localWithoutUwb() {
        assertEquals(
            UwbUnavailableReason.LOCAL_NO_UWB,
            UwbUnavailableReason.of(
                caps(CapabilitySet.OS_ANDROID),
                caps(CapabilitySet.OS_ANDROID, uwb = true),
            ),
        )
    }

    @Test
    fun localWithoutInterop() {
        assertEquals(
            UwbUnavailableReason.LOCAL_NO_INTEROP,
            UwbUnavailableReason.of(
                caps(CapabilitySet.OS_ANDROID, uwb = true),
                caps(CapabilitySet.OS_IOS, uwb = true, interop = true),
            ),
        )
    }

    @Test
    fun mutualUwbYieldsNoReason() {
        assertNull(
            UwbUnavailableReason.of(
                caps(CapabilitySet.OS_ANDROID, uwb = true),
                caps(CapabilitySet.OS_ANDROID, uwb = true),
            ),
        )
        assertNull(
            UwbUnavailableReason.of(
                caps(CapabilitySet.OS_ANDROID, uwb = true, interop = true),
                caps(CapabilitySet.OS_IOS, uwb = true, interop = true),
            ),
        )
    }
}
