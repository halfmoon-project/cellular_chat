package com.cellularchat.app.transport

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.WifiAwareManager
import android.os.Handler
import android.os.Looper
import com.cellularchat.app.core.crypto.Derivations
import com.cellularchat.app.core.crypto.Discovery
import com.cellularchat.app.identity.PairRecord
import com.cellularchat.app.transport.aware.WifiAwareTransport
import com.cellularchat.app.transport.ble.BleGattCentral
import com.cellularchat.app.transport.ble.BleGattPeripheral
import com.cellularchat.app.transport.nearby.NearbyConnectionsTransport
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Builds the ordered transport candidate list for a pair (IMPLEMENTATION_PLAN.md
 * §4): Wi-Fi Aware → Nearby Connections → mandatory BLE GATT. BLE central/
 * peripheral ownership and the rotating tokens (§7/§9) are derived from the pair
 * keys so both phones agree deterministically.
 *
 * peerIsIos is unknown before the authenticated capability exchange; until then
 * the same-platform tie-break is used (both Android phones agree). Cross-platform
 * role reversal is applied once the peer OS is known.
 */
object TransportCandidateFactory {
    fun candidates(
        context: Context,
        record: PairRecord,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        peerIsIos: Boolean = false,
        handler: Handler = Handler(Looper.getMainLooper()),
    ): List<TransportCandidate> {
        val localStatic = record.localStaticPublic()
        val localCentral = BleRoleSelector.localIsCentral(
            localStatic = localStatic,
            peerStatic = record.peerStaticPublic,
            localIsIos = false,
            peerIsIos = peerIsIos,
        )

        val myDiscoveryKey = Derivations.discoveryKey(record.pairRoot, record.roleByte)
        val peerDiscoveryKey = Derivations.discoveryKey(record.pairRoot, record.peerRoleByte)
        val myToken = Discovery.token(myDiscoveryKey, Discovery.epoch(nowSeconds), record.roleByte)
        val myTokenHex = myToken.joinToString("") { "%02x".format(it) }
        val peerTokens = Discovery.acceptanceTokens(peerDiscoveryKey, nowSeconds, record.peerRoleByte)

        val aware = RadioTransportCandidate(
            tag = "aware",
            available = {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) &&
                    context.getSystemService(WifiAwareManager::class.java)?.isAvailable == true
            },
            make = { onReady ->
                WifiAwareTransport(context, publisher = !localCentral, onLinkReady = onReady)
                    .takeIf { it.start() }
            },
            handler = handler,
        )

        val nearby = RadioTransportCandidate(
            tag = "nearby",
            available = {
                GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
            },
            make = { onReady ->
                NearbyConnectionsTransport(
                    context,
                    advertise = !localCentral,
                    endpointName = myTokenHex,
                    onLinkReady = onReady,
                ).takeIf { it.start() }
            },
            handler = handler,
        )

        val ble = RadioTransportCandidate(
            tag = "ble",
            available = {
                val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) &&
                    adapter?.isEnabled == true
            },
            make = { onReady ->
                if (localCentral) {
                    BleGattCentral(context, peerTokens, onReady).takeIf { it.start() }
                } else {
                    BleGattPeripheral(context, myToken, onReady).takeIf { it.start() }
                }
            },
            handler = handler,
        )

        return listOf(aware, nearby, ble)
    }
}
