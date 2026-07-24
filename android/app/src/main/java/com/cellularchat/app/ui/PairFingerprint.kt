package com.cellularchat.app.ui

import com.cellularchat.app.core.crypto.Derivations
import com.cellularchat.app.identity.PairRecord

/**
 * Recomputes the six-digit pairing fingerprint (PROTOCOL_V2.md §2) for a stored
 * [PairRecord], so the Pair Settings screen can display the same value both
 * screens showed at pairing confirmation. `staticA`/`staticB` are ordered by
 * permanent role (A = inviter), so both phones derive an identical display.
 */
object PairFingerprint {
    fun display(record: PairRecord): String {
        val local = record.localStaticPublic()
        val staticA = if (record.role == 1) local else record.peerStaticPublic
        val staticB = if (record.role == 1) record.peerStaticPublic else local
        return Derivations.fingerprintDisplay(Derivations.fingerprint(record.pairId, staticA, staticB))
    }
}
