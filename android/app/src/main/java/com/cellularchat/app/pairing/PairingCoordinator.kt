package com.cellularchat.app.pairing

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.ReasonCodes
import com.cellularchat.app.core.crypto.Derivations
import com.cellularchat.app.core.crypto.Invitation
import com.cellularchat.app.core.crypto.X25519
import com.cellularchat.app.core.protocol.PairingProtocol
import com.cellularchat.app.core.protocol.Records
import com.cellularchat.app.identity.PairRecord
import com.cellularchat.app.identity.PairStore

/**
 * A bidirectional record channel for the pairing handshake (typically BLE GATT
 * during pairing). Records are whole (§5); ordering is guaranteed by the link.
 */
interface PairingLink {
    fun send(record: ByteArray)
    fun close()
}

/**
 * Drives the core [PairingProtocol] over a [PairingLink] for either role
 * (PROTOCOL_V2.md §6). The inviter (role A) is the NNpsk0 responder; the joiner
 * (role B) is the initiator. Both screens show the same fingerprint before
 * commit; the record is persisted only after each side has both sent and
 * received `pair_complete`. Enforces invitation expiry and single use.
 */
class PairingCoordinator(
    private val store: PairStore,
    private val events: Events,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val newStaticPrivate: () -> ByteArray = { X25519.generatePrivate() },
) {
    interface Events {
        /** Both sides display this; the user calls [confirmFingerprint] to proceed. */
        fun onFingerprint(display: String)
        fun onCommitted(record: PairRecord)
        fun onAborted(reason: Int, detail: String)
    }

    private var proto: PairingProtocol? = null
    private var link: PairingLink? = null
    private var invitation: Invitation? = null
    private var alias: String = ""
    private var localStaticPrivate: ByteArray? = null

    private var fingerprintShown = false
    private var userConfirmed = false
    private var sentComplete = false
    private var receivedComplete = false
    private var finished = false

    /** Inviter (role A). Requires the invitation it generated and a fresh link. */
    fun beginInviter(invitation: Invitation, alias: String, link: PairingLink) {
        if (isExpired(invitation)) {
            abort(ReasonCodes.EXPIRED, "이 초대는 만료되었습니다.")
            return
        }
        val staticPrivate = newStaticPrivate()
        start(
            PairingProtocol.roleA(
                pairId = invitation.pairId,
                pairingPsk = Derivations.pairingPsk(invitation.secret),
                localStaticPrivate = staticPrivate,
            ),
            invitation, alias, link, staticPrivate,
        )
        // Role A waits for the joiner's pair_hello; nothing is sent yet.
    }

    /** Joiner (role B). Parses and validates the invitation text, then connects. */
    fun beginJoiner(invitationText: String, alias: String, link: PairingLink) {
        val parsed = runCatching { Invitation.parse(invitationText, clock()) }.getOrElse {
            abort(ReasonCodes.EXPIRED, it.message ?: "초대 코드가 올바르지 않습니다.")
            return
        }
        val staticPrivate = newStaticPrivate()
        val protocol = PairingProtocol.roleB(
            pairId = parsed.pairId,
            pairingPsk = Derivations.pairingPsk(parsed.secret),
            localStaticPrivate = staticPrivate,
        )
        start(protocol, parsed, alias, link, staticPrivate)
        guard { link.send(protocol.startHandshake()) } // B -> A: pair_hello.
    }

    /** The user accepted the shown fingerprint. Sends this side's pair_complete. */
    @Synchronized
    fun confirmFingerprint() {
        if (finished || !fingerprintShown || userConfirmed) return
        userConfirmed = true
        val protocol = proto ?: return
        guard {
            link?.send(protocol.sendComplete())
            sentComplete = true
            maybeCommit()
        }
    }

    fun cancel() = abort(ReasonCodes.USER_STOPPED, "페어링을 취소했습니다.")

    /** Feed one inbound record (any pairing record type). */
    @Synchronized
    fun onRecord(record: ByteArray) {
        if (finished) return
        val protocol = proto ?: return
        guard {
            when (Records.recordType(record)) {
                Records.PAIRING_HANDSHAKE -> onHandshake(protocol, record)
                Records.PAIRING_TRANSPORT -> onTransport(protocol, record)
                else -> throw ProtocolException("unexpected record during pairing")
            }
        }
    }

    private fun onHandshake(protocol: PairingProtocol, record: ByteArray) {
        when (protocol.role) {
            PairingProtocol.Role.A -> {
                protocol.readHello(record)
                link?.send(protocol.writeChallenge()) // A -> B: pair_challenge.
                // A now waits for B's pair_bind before sending its own.
            }
            PairingProtocol.Role.B -> {
                protocol.readChallenge(record)
                link?.send(protocol.sendBind()) // B -> A: pair_bind.
            }
        }
    }

    private fun onTransport(protocol: PairingProtocol, record: ByteArray) {
        val received = protocol.receive(record)
        when (received.type) {
            PairingProtocol.Type.BIND -> {
                if (protocol.role == PairingProtocol.Role.A) {
                    link?.send(protocol.sendBind())  // A -> B: pair_bind (after B's).
                } else {
                    link?.send(protocol.sendProof()) // B -> A: pair_proof (after both binds).
                }
            }
            PairingProtocol.Type.PROOF -> {
                if (protocol.role == PairingProtocol.Role.A) {
                    link?.send(protocol.sendProof()) // A verified B; A -> B: pair_proof.
                }
                showFingerprint(protocol)
            }
            PairingProtocol.Type.COMPLETE -> {
                receivedComplete = true
                maybeCommit()
            }
            PairingProtocol.Type.ABORT -> abort(ReasonCodes.PROTOCOL_ERROR, "상대가 페어링을 중단했습니다.")
        }
    }

    private fun showFingerprint(protocol: PairingProtocol) {
        if (fingerprintShown) return
        val staged = protocol.stagedPairData ?: return
        fingerprintShown = true
        events.onFingerprint(staged.fingerprintDisplay)
    }

    private fun maybeCommit() {
        if (finished || !sentComplete || !receivedComplete) return
        val protocol = proto ?: return
        val staged = protocol.stagedPairData ?: return
        val privateKey = localStaticPrivate ?: return
        val peerStatic = if (staged.role == PairingProtocol.Role.A) staged.staticB else staged.staticA
        val record = PairRecord(
            pairId = staged.pairId,
            role = staged.role.value.toInt(),
            localStaticPrivate = privateKey,
            peerStaticPublic = peerStatic,
            pairRoot = staged.pairRoot,
            negotiatedVersion = staged.negotiatedVersion.toInt(),
            alias = alias.ifBlank { "상대" },
            createdAt = clock(),
        )
        finished = true
        store.upsert(record)          // consumed: this pairId will not pair again.
        events.onCommitted(record)
        link?.close()
    }

    private fun start(
        protocol: PairingProtocol,
        inv: Invitation,
        alias: String,
        link: PairingLink,
        staticPrivate: ByteArray,
    ) {
        this.proto = protocol
        this.invitation = inv
        this.alias = alias
        this.link = link
        this.localStaticPrivate = staticPrivate
    }

    private fun isExpired(inv: Invitation): Boolean =
        clock() - inv.createdAt > Invitation.MAX_AGE_SECONDS

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: ProtocolException) {
            trySendAbort()
            abort(e.reason, e.message ?: "페어링 중 오류가 발생했습니다.")
        } catch (e: Exception) {
            abort(ReasonCodes.PROTOCOL_ERROR, e.message ?: "페어링 중 오류가 발생했습니다.")
        }
    }

    private fun trySendAbort() {
        runCatching { proto?.let { link?.send(it.sendAbort(ReasonCodes.PROTOCOL_ERROR)) } }
    }

    private fun abort(reason: Int, detail: String) {
        if (finished) return
        finished = true
        runCatching { link?.close() }
        events.onAborted(reason, detail)
    }
}
