package com.cellularchat.app.pairing

import com.cellularchat.app.core.crypto.Invitation
import java.security.SecureRandom

/** Generates a fresh, single-use invitation (PROTOCOL_V2.md §4). */
object InvitationFactory {
    private val random = SecureRandom()

    fun create(nowUnixSeconds: Long = System.currentTimeMillis() / 1000): Invitation {
        val pairId = ByteArray(16).also { random.nextBytes(it) }
        val secret = ByteArray(32).also { random.nextBytes(it) }
        return Invitation(pairId, secret, nowUnixSeconds)
    }

    fun text(invitation: Invitation): String = Invitation.encode(invitation)
}
