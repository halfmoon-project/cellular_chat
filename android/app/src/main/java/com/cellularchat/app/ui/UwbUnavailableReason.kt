package com.cellularchat.app.ui

import com.cellularchat.app.core.protocol.CapabilitySet

/**
 * Why precise UWB ranging is unavailable for this pair, derived from the
 * negotiated capability booleans only (PROTOCOL_V2.md §11 — never OS-version
 * strings, which are free-form and unparseable). `null` means UWB is mutually
 * supported and no reason is shown. Resource-free so it is unit tested without
 * an Activity; string mapping lives in MainActivity like the other labels.
 */
enum class UwbUnavailableReason {
    PEER_CAPS_UNKNOWN,
    PEER_NO_UWB,
    PEER_NO_INTEROP,
    LOCAL_NO_UWB,
    LOCAL_NO_INTEROP,
    ;

    companion object {
        fun of(local: CapabilitySet, peer: CapabilitySet?): UwbUnavailableReason? {
            peer ?: return PEER_CAPS_UNKNOWN
            val crossPlatform = local.os != peer.os
            return when {
                !peer.uwbPresent -> PEER_NO_UWB
                crossPlatform && !peer.appleInteropUwb -> PEER_NO_INTEROP
                !local.uwbPresent -> LOCAL_NO_UWB
                crossPlatform && !local.appleInteropUwb -> LOCAL_NO_INTEROP
                else -> null
            }
        }
    }
}
