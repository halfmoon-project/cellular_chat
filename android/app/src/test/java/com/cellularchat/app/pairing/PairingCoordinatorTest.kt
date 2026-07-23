package com.cellularchat.app.pairing

import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.identity.MasterCipher
import com.cellularchat.app.identity.PairRecord
import com.cellularchat.app.identity.PairStore
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pass-through cipher: exercises store logic without the Android Keystore. */
private class PlainCipher : MasterCipher {
    override fun seal(plaintext: ByteArray): ByteArray = plaintext.copyOf()
    override fun open(sealed: ByteArray): ByteArray = sealed.copyOf()
}

/** Delivers each sent record straight into the peer coordinator (synchronous). */
private class MemoryLink : PairingLink {
    var peer: PairingCoordinator? = null
    var closed = false
    override fun send(record: ByteArray) { peer?.onRecord(record) }
    override fun close() { closed = true }
}

class PairingCoordinatorTest {
    private fun store(): PairStore =
        PairStore(File.createTempFile("pairs", ".enc").also { it.delete() }, PlainCipher())

    private class Collector : PairingCoordinator.Events {
        var fingerprint: String? = null
        var committed: PairRecord? = null
        var aborted: Pair<Int, String>? = null
        override fun onFingerprint(display: String) { fingerprint = display }
        override fun onCommitted(record: PairRecord) { committed = record }
        override fun onAborted(reason: Int, detail: String) { aborted = reason to detail }
    }

    @Test
    fun bothSidesConfirmAndCommitThenEraseSecret() {
        val storeA = store()
        val storeB = store()
        val invitation = InvitationFactory.create()
        val invitationText = InvitationFactory.text(invitation)

        val eventsA = Collector()
        val eventsB = Collector()
        val coordA = PairingCoordinator(storeA, eventsA)
        val coordB = PairingCoordinator(storeB, eventsB)
        val linkA = MemoryLink().apply { peer = coordB }
        val linkB = MemoryLink().apply { peer = coordA }

        coordA.beginInviter(invitation, "Bob", linkA)
        coordB.beginJoiner(invitationText, "Alice", linkB)

        // The full handshake ran synchronously; both screens show the same code.
        assertNotNull(eventsA.fingerprint)
        assertEquals(eventsA.fingerprint, eventsB.fingerprint)

        // Neither side commits until both have confirmed and exchanged complete.
        coordA.confirmFingerprint()
        assertNull(eventsA.committed)
        coordB.confirmFingerprint()

        val recordA = eventsA.committed
        val recordB = eventsB.committed
        assertNotNull(recordA)
        assertNotNull(recordB)
        assertNotNull(storeA.get(invitation.pairId))
        assertNotNull(storeB.get(invitation.pairId))

        // Both sides derived the identical pairRoot, and it survived the wipe.
        assertArrayEquals(recordA!!.pairRoot, recordB!!.pairRoot)
        assertFalse(recordA.pairRoot.all { it == 0.toByte() })

        // §4/§6: the invitation secret is zeroized after commit.
        assertTrue(invitation.secret.all { it == 0.toByte() })
    }

    @Test
    fun joinerRejectsAlreadyConsumedInvitation() {
        val store = store()
        val invitation = InvitationFactory.create()
        val invitationText = InvitationFactory.text(invitation)
        // A record for this pairId already exists: the invitation was consumed.
        store.upsert(
            PairRecord(
                pairId = invitation.pairId,
                role = 2,
                localStaticPrivate = ByteArray(32) { 1 },
                peerStaticPublic = ByteArray(32) { 2 },
                pairRoot = ByteArray(32) { 3 },
                negotiatedVersion = 2,
                alias = "Alice",
                createdAt = 1,
            ),
        )

        val events = Collector()
        PairingCoordinator(store, events).beginJoiner(invitationText, "Alice", MemoryLink())

        assertEquals(ReasonCodes.PROTOCOL_ERROR, events.aborted?.first)
        assertNull(events.committed)
    }
}
