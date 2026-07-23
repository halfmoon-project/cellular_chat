package com.cellularchat.app.core.noise

import com.cellularchat.app.core.Vectors
import com.cellularchat.app.core.hex
import com.cellularchat.app.core.toHex
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NoiseOfficialVectorsTest {
    private val vectors = Vectors.json("noise_official_vectors.json").getJSONArray("vectors")

    @Test
    fun replaysEveryOfficialVectorAsBothRoles() {
        for (i in 0 until vectors.length()) {
            replay(vectors.getJSONObject(i))
        }
    }

    private fun replay(vector: JSONObject) {
        val name = vector.getString("protocol_name")
        val pattern = if (name.contains("NNpsk0")) NoisePattern.NN_PSK0 else NoisePattern.IK_PSK2
        val psk = hex(vector.getJSONArray("init_psks").getString(0))
        val prologue = hex(vector.getString("init_prologue"))

        val initiator = HandshakeState(
            pattern = pattern,
            initiator = true,
            prologue = prologue,
            psk = psk,
            localStaticPrivate = vector.optString("init_static").takeIf { it.isNotEmpty() }?.let { hex(it) },
            remoteStaticPublic = vector.optString("init_remote_static").takeIf { it.isNotEmpty() }?.let { hex(it) },
            fixedEphemeralPrivate = hex(vector.getString("init_ephemeral")),
        )
        val responder = HandshakeState(
            pattern = pattern,
            initiator = false,
            prologue = hex(vector.getString("resp_prologue")),
            psk = hex(vector.getJSONArray("resp_psks").getString(0)),
            localStaticPrivate = vector.optString("resp_static").takeIf { it.isNotEmpty() }?.let { hex(it) },
            fixedEphemeralPrivate = hex(vector.getString("resp_ephemeral")),
        )

        val messages = vector.getJSONArray("messages")
        val handshakeCount = 2
        var initSplit: Pair<CipherState, CipherState>? = null
        var respSplit: Pair<CipherState, CipherState>? = null

        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            val payload = hex(message.getString("payload"))
            val expected = message.getString("ciphertext")
            val initiatorSends = i % 2 == 0

            if (i < handshakeCount) {
                val produced = if (initiatorSends) {
                    val out = initiator.writeMessage(payload)
                    assertArrayEquals("$name read msg $i", payload, responder.readMessage(out))
                    out
                } else {
                    val out = responder.writeMessage(payload)
                    assertArrayEquals("$name read msg $i", payload, initiator.readMessage(out))
                    out
                }
                assertEquals("$name handshake msg $i", expected, produced.toHex())
                if (i == handshakeCount - 1) {
                    initSplit = initiator.splitStates
                    respSplit = responder.splitStates
                }
            } else {
                val (ic1, ic2) = initSplit!!
                val (rc1, rc2) = respSplit!!
                val produced = if (initiatorSends) {
                    val out = ic1.encryptWithAd(ByteArray(0), payload)
                    assertArrayEquals("$name transport read $i", payload, rc1.decryptWithAd(ByteArray(0), out))
                    out
                } else {
                    val out = rc2.encryptWithAd(ByteArray(0), payload)
                    assertArrayEquals("$name transport read $i", payload, ic2.decryptWithAd(ByteArray(0), out))
                    out
                }
                assertEquals("$name transport msg $i", expected, produced.toHex())
            }
        }

        val expectedHash = vector.getString("handshake_hash")
        assertEquals("$name init hash", expectedHash, initiator.handshakeHash().toHex())
        assertEquals("$name resp hash", expectedHash, responder.handshakeHash().toHex())
    }
}
