package com.cellularchat.app.pairing

import android.content.Context
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.crypto.Invitation
import com.cellularchat.app.identity.PairStore
import com.cellularchat.app.transport.PeerTransport
import com.cellularchat.app.transport.ble.BleGattCentral
import com.cellularchat.app.transport.ble.BleGattPeripheral
import java.io.Closeable

/** Adapts a [PeerTransport] to the pairing [PairingLink] record channel. */
private class TransportPairingLink(private val transport: PeerTransport) : PairingLink {
    override fun send(record: ByteArray) = transport.send(record)
    override fun close() = transport.close()
}

/**
 * Live handle to an in-progress BLE pairing. The UI holds exactly this object
 * and forwards the user's fingerprint confirm/cancel to the running
 * [PairingCoordinator] through it, so the coordinator can never be left
 * unwired (the confirm path drives [PairingCoordinator.maybeCommit]).
 */
class PairingHandle internal constructor(
    private val coordinator: PairingCoordinator,
    private val transport: Closeable,
) : Closeable {
    fun confirmFingerprint() = coordinator.confirmFingerprint()
    fun cancel() = coordinator.cancel()
    override fun close() = transport.close()
}

/**
 * Wires a BLE GATT link to a [PairingCoordinator] for the initial NNpsk0
 * pairing (PROTOCOL_V2.md §6/§9). The inviter (role A) is the BLE peripheral;
 * the joiner (role B) is the central. The pairId is never advertised (§4), so
 * the central connects to any peer advertising the service and NNpsk0 then
 * authenticates.
 */
object BlePairing {
    fun startInviter(
        context: Context,
        store: PairStore,
        invitation: Invitation,
        alias: String,
        events: PairingCoordinator.Events,
    ): PairingHandle {
        val coordinator = PairingCoordinator(store, events)
        // A random 16-byte advertising nonce; not identity, only a rendezvous hint.
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val peripheral = BleGattPeripheral(context, nonce, onLinkReady = {})
        wire(peripheral, coordinator, events)
        coordinator.beginInviter(invitation, alias, TransportPairingLink(peripheral))
        if (!peripheral.start()) {
            events.onAborted(ReasonCodes.RADIO_UNAVAILABLE, "BLE 광고를 시작할 수 없습니다.")
        }
        return PairingHandle(coordinator, Closeable { peripheral.close() })
    }

    fun startJoiner(
        context: Context,
        store: PairStore,
        invitationText: String,
        alias: String,
        events: PairingCoordinator.Events,
    ): PairingHandle {
        val coordinator = PairingCoordinator(store, events)
        lateinit var central: BleGattCentral
        central = BleGattCentral(context, emptyList(), onLinkReady = {
            coordinator.beginJoiner(invitationText, alias, TransportPairingLink(central))
        })
        wire(central, coordinator, events)
        if (!central.start()) {
            events.onAborted(ReasonCodes.RADIO_UNAVAILABLE, "BLE 검색을 시작할 수 없습니다.")
        }
        return PairingHandle(coordinator, Closeable { central.close() })
    }

    private fun wire(
        transport: PeerTransport,
        coordinator: PairingCoordinator,
        events: PairingCoordinator.Events,
    ) {
        transport.setListener(object : PeerTransport.Listener {
            override fun onRecord(record: ByteArray) = coordinator.onRecord(record)
            override fun onLinkLost(reason: Int) =
                events.onAborted(reason, "페어링 연결이 끊겼습니다.")
        })
    }
}
