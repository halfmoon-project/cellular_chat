package com.cellularchat.app.core.protocol

/**
 * Capability-transcript consistency checks (PROTOCOL_V2.md §14, Feature B),
 * computed only from the two CapabilitySets both sides exchanged inside the
 * session AEAD. Pure and symmetric, so it is unit-tested directly and shared by
 * the session layer (re-announcement drift) and the ranging layer (method set).
 */
object CapabilityTranscript {
    /**
     * A later `capabilities`/`session_ready` re-announcement drifts when its
     * decoded set differs in any §11 field from the peer's first bound set. The
     * comparison is over the normalized 14-field [CapabilitySet] (`==`), so a
     * benign CBOR re-encoding is NOT a mismatch (B.2.1).
     */
    fun isReannouncementDrift(bound: CapabilitySet, announced: CapabilitySet): Boolean =
        bound != announced

    /**
     * Whether [method] lies in the mutually-supported set of the two bound
     * CapabilitySets [local]/[peer] (B.2.2):
     * `ni_peer` needs both iOS + both UWB; `uwb_android_oob` needs both Android +
     * both UWB; `uwb_apple_interop` needs mixed OS + both UWB + both interop;
     * `ble_rssi` is always supported.
     */
    fun methodSupported(local: CapabilitySet, peer: CapabilitySet, method: Int): Boolean {
        val bothUwb = local.uwbPresent && peer.uwbPresent
        val bothIos = local.os == CapabilitySet.OS_IOS && peer.os == CapabilitySet.OS_IOS
        val bothAndroid = local.os == CapabilitySet.OS_ANDROID && peer.os == CapabilitySet.OS_ANDROID
        val mixed = (local.os == CapabilitySet.OS_IOS && peer.os == CapabilitySet.OS_ANDROID) ||
            (local.os == CapabilitySet.OS_ANDROID && peer.os == CapabilitySet.OS_IOS)
        return when (method) {
            RangingMethod.NI_PEER -> bothIos && bothUwb
            RangingMethod.UWB_ANDROID_OOB -> bothAndroid && bothUwb
            RangingMethod.UWB_APPLE_INTEROP -> mixed && bothUwb && local.appleInteropUwb && peer.appleInteropUwb
            RangingMethod.BLE_RSSI -> true
            else -> false
        }
    }
}
