package com.cellularchat.app.ui

/**
 * Maps the RangingManager technology constant the platform actually started
 * ranging with (`FindUiState.rangingTechnology`, IMPLEMENTATION_PLAN.md §8,
 * PROTOCOL_V2.md §12) to a stable enum for the label. Driven by the reported
 * technology only; an unknown value yields `null` so no label is shown. Kept
 * resource-free so it is unit tested without an Activity.
 */
object RangingTechnologyLabel {
    // android.ranging.RangingManager technology constants (API 36).
    private const val UWB = 0
    private const val BLE_CS = 1
    private const val WIFI_NAN_RTT = 2
    private const val BLE_RSSI = 3

    enum class Tech { UWB, BLE_CS, WIFI_NAN_RTT, BLE_RSSI }

    fun of(technology: Int): Tech? = when (technology) {
        UWB -> Tech.UWB
        BLE_CS -> Tech.BLE_CS
        WIFI_NAN_RTT -> Tech.WIFI_NAN_RTT
        BLE_RSSI -> Tech.BLE_RSSI
        else -> null
    }
}
