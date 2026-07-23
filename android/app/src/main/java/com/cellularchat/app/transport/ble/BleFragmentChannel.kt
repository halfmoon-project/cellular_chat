package com.cellularchat.app.transport.ble

import com.cellularchat.app.core.FragmentException
import com.cellularchat.app.core.protocol.Fragmentation
import com.cellularchat.app.core.protocol.Reassembler

/**
 * Applies the §9 fragmentation layer to one GATT link: splits outbound records
 * for the negotiated MTU and reassembles inbound fragments into whole records.
 * A fatal reassembly violation is surfaced so the caller can tear down the link.
 */
class BleFragmentChannel(
    @Volatile var mtu: Int = BleConstants.DEFAULT_MTU,
) {
    private val reassembler = Reassembler()

    fun fragment(record: ByteArray): List<ByteArray> = Fragmentation.fragment(record, mtu)

    /** @return the completed record, or null if more fragments are expected. */
    @Throws(FragmentException::class)
    fun accept(fragment: ByteArray): ByteArray? = reassembler.offer(fragment)
}
