package com.cellularchat.app.transport.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.transport.PeerTransport
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * BLE GATT peripheral: advertiser + GATT server (PROTOCOL_V2.md §9). Advertises
 * the service UUID plus the rotating token as service data, serves the current
 * token on the rendezvous characteristic, receives central→peripheral records on
 * `inbox`, and pushes peripheral→central records as `outbox` notifications. All
 * platform touches are permission-checked and never crash on missing hardware.
 */
class BleGattPeripheral(
    private val context: Context,
    private val token: ByteArray,
    private val onLinkReady: () -> Unit,
) : PeerTransport {

    override val tag: String = BleConstants.TRANSPORT_TAG

    private val handler = Handler(Looper.getMainLooper())
    private val fragments = BleFragmentChannel(
        scheduleTimeout = { delay, action -> handler.postDelayed(action, delay) },
        onStalled = { listener?.onLinkLost(ReasonCodes.PROTOCOL_ERROR) },
    )
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var advertiser: BluetoothLeAdvertiser? = null
    private var server: BluetoothGattServer? = null
    private var central: BluetoothDevice? = null
    private var outbox: BluetoothGattCharacteristic? = null
    private var listener: PeerTransport.Listener? = null
    private val pending = ConcurrentLinkedQueue<ByteArray>()

    override fun setListener(listener: PeerTransport.Listener) {
        this.listener = listener
    }

    /** Begins advertising and opens the GATT server. Returns false if unavailable. */
    fun start(): Boolean {
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE) ||
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {
            return false
        }
        val adapter = manager?.adapter ?: return false
        if (!adapter.isEnabled) return false
        return runCatching {
            openServer()
            startAdvertising(adapter.bluetoothLeAdvertiser ?: return false)
            true
        }.getOrDefault(false)
    }

    private fun openServer() {
        val gattServer = manager?.openGattServer(context, serverCallback) ?: return
        server = gattServer
        val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleConstants.RENDEZVOUS_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BleConstants.INBOX_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        val outboxChar = BluetoothGattCharacteristic(
            BleConstants.OUTBOX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0,
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    BleConstants.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
        }
        service.addCharacteristic(outboxChar)
        outbox = outboxChar
        runCatching { gattServer.addService(service) }
    }

    private fun startAdvertising(leAdvertiser: BluetoothLeAdvertiser) {
        advertiser = leAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false) // never advertise a static name (§7).
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .addServiceData(ParcelUuid(BleConstants.SERVICE_UUID), token)
            .build()
        runCatching { leAdvertiser.startAdvertising(settings, data, advertiseCallback) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            listener?.onLinkLost(ReasonCodes.RADIO_UNAVAILABLE)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    central = device
                    onLinkReady()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (device == central) {
                        central = null
                        listener?.onLinkLost(ReasonCodes.TRANSPORT_LOST)
                    }
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = if (characteristic.uuid == BleConstants.RENDEZVOUS_UUID) token else ByteArray(0)
            runCatching {
                server?.sendResponse(device, requestId, 0, offset, value.copyOfRange(offset.coerceAtMost(value.size), value.size))
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, 0, offset, null) }
            if (characteristic.uuid != BleConstants.INBOX_UUID) return
            runCatching { fragments.accept(value) }
                .onSuccess { record -> record?.let { listener?.onRecord(it) } }
                .onFailure { listener?.onLinkLost(ReasonCodes.PROTOCOL_ERROR) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, 0, offset, null) }
            drainPending()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            fragments.mtu = mtu
        }
    }

    override fun send(record: ByteArray) {
        val target = central
        val characteristic = outbox
        if (target == null || characteristic == null) {
            pending.add(record)
            return
        }
        for (fragment in fragments.fragment(record)) {
            notify(target, characteristic, fragment)
        }
    }

    private fun drainPending() {
        val target = central ?: return
        val characteristic = outbox ?: return
        while (true) {
            val record = pending.poll() ?: break
            for (fragment in fragments.fragment(record)) notify(target, characteristic, fragment)
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, fragment: ByteArray) {
        runCatching {
            characteristic.value = fragment
            server?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { central?.let { server?.cancelConnection(it) } }
        runCatching { server?.close() }
        advertiser = null
        server = null
        central = null
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
