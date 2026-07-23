package com.cellularchat.app.transport.ble

import com.cellularchat.app.core.FragmentException
import com.cellularchat.app.core.protocol.Fragmentation
import com.cellularchat.app.core.protocol.Reassembler

/**
 * Applies the §9 fragmentation layer to one GATT link: splits outbound records
 * for the negotiated MTU and reassembles inbound fragments into whole records.
 * A fatal reassembly violation is surfaced so the caller can tear down the link.
 *
 * When a reassembly begins it arms a 10s [scheduleTimeout] check so a peer that
 * sends only a FIRST fragment and then goes silent still trips the §9 deadline
 * and [onStalled] tears the link down.
 */
class BleFragmentChannel(
    @Volatile var mtu: Int = BleConstants.DEFAULT_MTU,
    private val reassembler: Reassembler = Reassembler(),
    private val scheduleTimeout: ((delayMillis: Long, action: () -> Unit) -> Unit)? = null,
    private val onStalled: (() -> Unit)? = null,
) {
    fun fragment(record: ByteArray): List<ByteArray> = Fragmentation.fragment(record, mtu)

    /** @return the completed record, or null if more fragments are expected. */
    @Throws(FragmentException::class)
    fun accept(fragment: ByteArray): ByteArray? {
        val wasReassembling = reassembler.reassemblyInProgress
        val record = reassembler.offer(fragment)
        // A fresh reassembly just started: arm its deadline once.
        if (!wasReassembling && reassembler.reassemblyInProgress) {
            scheduleTimeout?.invoke(Reassembler.TIMEOUT_MILLIS) { fireTimeoutCheck() }
        }
        return record
    }

    private fun fireTimeoutCheck() {
        try {
            reassembler.checkTimeout()
        } catch (_: FragmentException) {
            onStalled?.invoke()
        }
    }
}
