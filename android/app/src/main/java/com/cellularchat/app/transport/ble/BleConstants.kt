package com.cellularchat.app.transport.ble

import java.util.UUID

/** Fixed BLE GATT service/characteristic UUIDs (PROTOCOL_V2.md §9). */
object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("4A0C5000-9C6F-4B2E-8FD8-3B6A2E0D5C71")
    val RENDEZVOUS_UUID: UUID = UUID.fromString("4A0C5001-9C6F-4B2E-8FD8-3B6A2E0D5C71") // read
    val INBOX_UUID: UUID = UUID.fromString("4A0C5002-9C6F-4B2E-8FD8-3B6A2E0D5C71") // write w/ response
    val OUTBOX_UUID: UUID = UUID.fromString("4A0C5003-9C6F-4B2E-8FD8-3B6A2E0D5C71") // notify

    /** Client Characteristic Configuration Descriptor. */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val TRANSPORT_TAG = "ble"
    const val DEFAULT_MTU = 23
    const val PREFERRED_MTU = 512
    const val TOKEN_LENGTH = 16
}
