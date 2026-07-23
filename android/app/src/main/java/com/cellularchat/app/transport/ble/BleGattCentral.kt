package com.cellularchat.app.transport.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.transport.PeerTransport
import java.util.ArrayDeque

/**
 * BLE GATT central: scanner + GATT client (PROTOCOL_V2.md §9). Filters on the
 * service UUID, verifies the peer's rotating token against the acceptance set
 * (from advertised service data, or by reading the rendezvous characteristic
 * when service data is unavailable), negotiates MTU, subscribes to `outbox`
 * notifications, and writes records to `inbox`. Capability- and permission-
 * checked; never crashes on missing hardware.
 */
class BleGattCentral(
    private val context: Context,
    private val acceptTokens: List<ByteArray>,
    private val onLinkReady: () -> Unit,
) : PeerTransport {

    override val tag: String = BleConstants.TRANSPORT_TAG

    private val fragments = BleFragmentChannel()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var inbox: BluetoothGattCharacteristic? = null
    private var listener: PeerTransport.Listener? = null
    private var ready = false

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false

    override fun setListener(listener: PeerTransport.Listener) {
        this.listener = listener
    }

    fun start(): Boolean {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) ||
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            return false
        }
        val adapter = manager?.adapter ?: return false
        if (!adapter.isEnabled) return false
        val leScanner = adapter.bluetoothLeScanner ?: return false
        scanner = leScanner
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return runCatching {
            leScanner.startScan(filters, settings, scanCallback)
            true
        }.getOrDefault(false)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (gatt != null) return
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(BleConstants.SERVICE_UUID))
            // Empty acceptTokens = pairing mode: connect to any peer advertising the
            // service (pairId is never on the air, §4); NNpsk0 then authenticates.
            if (acceptTokens.isNotEmpty() && serviceData != null && !tokenAccepted(serviceData)) return
            // Service data present+matched, or absent (iOS overflow): connect and
            // verify via rendezvous read before trusting the link.
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            listener?.onLinkLost(ReasonCodes.RADIO_UNAVAILABLE)
        }
    }

    private fun tokenAccepted(token: ByteArray): Boolean =
        acceptTokens.any { it.size == token.size && it.contentEquals(token) }

    private fun connect(device: BluetoothDevice) {
        gatt = runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (gatt == null) listener?.onLinkLost(ReasonCodes.TRANSPORT_LOST)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> runCatching { g.requestMtu(BleConstants.PREFERRED_MTU) }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (ready) listener?.onLinkLost(ReasonCodes.TRANSPORT_LOST)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            fragments.mtu = mtu
            runCatching { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(BleConstants.SERVICE_UUID) ?: run {
                runCatching { g.disconnect() }
                listener?.onLinkLost(ReasonCodes.PROTOCOL_ERROR)
                return
            }
            inbox = service.getCharacteristic(BleConstants.INBOX_UUID)
            val outbox = service.getCharacteristic(BleConstants.OUTBOX_UUID)
            if (inbox == null || outbox == null) {
                runCatching { g.disconnect() }
                listener?.onLinkLost(ReasonCodes.PROTOCOL_ERROR)
                return
            }
            runCatching {
                g.setCharacteristicNotification(outbox, true)
                val cccd = outbox.getDescriptor(BleConstants.CCCD_UUID)
                cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (cccd != null) g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!ready) {
                ready = true
                onLinkReady()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != BleConstants.OUTBOX_UUID) return
            val value = characteristic.value ?: return
            runCatching { fragments.accept(value) }
                .onSuccess { record -> record?.let { listener?.onRecord(it) } }
                .onFailure { listener?.onLinkLost(ReasonCodes.PROTOCOL_ERROR) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writing = false
            drainWrites()
        }
    }

    override fun send(record: ByteArray) {
        for (fragment in fragments.fragment(record)) {
            synchronized(writeQueue) { writeQueue.add(fragment) }
        }
        drainWrites()
    }

    @Suppress("DEPRECATION")
    private fun drainWrites() {
        val characteristic = inbox ?: return
        val g = gatt ?: return
        synchronized(writeQueue) {
            if (writing) return
            val fragment = writeQueue.poll() ?: return
            writing = true
            runCatching {
                characteristic.value = fragment
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(characteristic)
            }.onFailure { writing = false }
        }
    }

    private fun stopScan() {
        runCatching { scanner?.stopScan(scanCallback) }
    }

    override fun close() {
        stopScan()
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        inbox = null
        ready = false
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
