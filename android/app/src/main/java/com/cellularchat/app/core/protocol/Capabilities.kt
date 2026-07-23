package com.cellularchat.app.core.protocol

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.cbor.CborBool
import com.cellularchat.app.core.cbor.CborInt
import com.cellularchat.app.core.cbor.CborMap
import com.cellularchat.app.core.cbor.CborText

/** Authenticated capability advertisement (PROTOCOL_V2.md §11). */
data class CapabilitySet(
    val os: Int,
    val osVersion: String,
    val appVersion: String,
    val wifiAware: Boolean = false,
    val nearbyConnections: Boolean = false,
    val bleCentral: Boolean = false,
    val blePeripheral: Boolean = false,
    val uwbPresent: Boolean = false,
    val uwbAzimuth: Boolean = false,
    val uwbElevation: Boolean = false,
    val appleInteropUwb: Boolean = false,
    val niEdm: Boolean = false,
    val wifiRtt: Boolean = false,
    val backgroundRanging: Boolean = false,
) {
    fun toCbor(): CborMap = CborMap(
        listOf(
            CborInt(1) to CborInt(os.toLong()),
            CborInt(2) to CborText(osVersion),
            CborInt(3) to CborText(appVersion),
            CborInt(4) to CborBool(wifiAware),
            CborInt(5) to CborBool(nearbyConnections),
            CborInt(6) to CborBool(bleCentral),
            CborInt(7) to CborBool(blePeripheral),
            CborInt(8) to CborBool(uwbPresent),
            CborInt(9) to CborBool(uwbAzimuth),
            CborInt(10) to CborBool(uwbElevation),
            CborInt(11) to CborBool(appleInteropUwb),
            CborInt(12) to CborBool(niEdm),
            CborInt(13) to CborBool(wifiRtt),
            CborInt(14) to CborBool(backgroundRanging),
        ),
    )

    companion object {
        const val OS_ANDROID = 1
        const val OS_IOS = 2

        /** Unknown keys are ignored; missing keys take their default (false/empty). */
        fun fromCbor(map: CborMap): CapabilitySet {
            fun bool(key: Long): Boolean = (map[key] as? CborBool)?.value ?: false
            fun text(key: Long): String = (map[key] as? CborText)?.value ?: ""
            val os = (map[1L] as? CborInt)?.value?.toInt()
                ?: throw ProtocolException("capability os is required")
            return CapabilitySet(
                os = os,
                osVersion = text(2L),
                appVersion = text(3L),
                wifiAware = bool(4L),
                nearbyConnections = bool(5L),
                bleCentral = bool(6L),
                blePeripheral = bool(7L),
                uwbPresent = bool(8L),
                uwbAzimuth = bool(9L),
                uwbElevation = bool(10L),
                appleInteropUwb = bool(11L),
                niEdm = bool(12L),
                wifiRtt = bool(13L),
                backgroundRanging = bool(14L),
            )
        }
    }
}

/** Ranging method codes (PROTOCOL_V2.md §12). */
object RangingMethod {
    const val UWB_APPLE_INTEROP = 1
    const val UWB_ANDROID_OOB = 2
    const val NI_PEER = 3
    const val BLE_RSSI = 4
}

data class RangingSelection(val method: Int, val edm: Boolean)

/** Deterministic, symmetric ranging selection over both CapabilitySets (§12). */
object RangingSelector {
    fun select(a: CapabilitySet, b: CapabilitySet): RangingSelection {
        val bothIos = a.os == CapabilitySet.OS_IOS && b.os == CapabilitySet.OS_IOS
        val bothAndroid = a.os == CapabilitySet.OS_ANDROID && b.os == CapabilitySet.OS_ANDROID
        val mixed = (a.os == CapabilitySet.OS_IOS && b.os == CapabilitySet.OS_ANDROID) ||
            (a.os == CapabilitySet.OS_ANDROID && b.os == CapabilitySet.OS_IOS)
        val bothUwb = a.uwbPresent && b.uwbPresent

        return when {
            bothIos && bothUwb ->
                RangingSelection(RangingMethod.NI_PEER, edm = a.niEdm && b.niEdm)
            bothAndroid && bothUwb ->
                RangingSelection(RangingMethod.UWB_ANDROID_OOB, edm = false)
            mixed && bothUwb && a.appleInteropUwb && b.appleInteropUwb ->
                RangingSelection(RangingMethod.UWB_APPLE_INTEROP, edm = false)
            else -> RangingSelection(RangingMethod.BLE_RSSI, edm = false)
        }
    }
}
