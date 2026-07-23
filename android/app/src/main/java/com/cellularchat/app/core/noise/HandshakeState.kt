package com.cellularchat.app.core.noise

import com.cellularchat.app.core.ProtocolException
import com.cellularchat.app.core.crypto.X25519

/** The two Noise patterns this product supports (§1). Both carry a PSK. */
enum class NoisePattern(val protocolName: String) {
    NN_PSK0("Noise_NNpsk0_25519_ChaChaPoly_SHA256"),
    IK_PSK2("Noise_IKpsk2_25519_ChaChaPoly_SHA256"),
}

private enum class Token { E, S, EE, ES, SE, SS, PSK }

/**
 * Noise rev34 HandshakeState for NNpsk0 and IKpsk2. Because every supported
 * pattern uses a PSK, the `e` token additionally calls MixKey (PSK 'e' rule).
 */
class HandshakeState(
    private val pattern: NoisePattern,
    private val initiator: Boolean,
    prologue: ByteArray,
    private val psk: ByteArray,
    localStaticPrivate: ByteArray? = null,
    remoteStaticPublic: ByteArray? = null,
    private val fixedEphemeralPrivate: ByteArray? = null,
) {
    private val symmetric = SymmetricState(pattern.protocolName)

    private var localStaticPriv: ByteArray? = localStaticPrivate
    private var localStaticPub: ByteArray? = localStaticPrivate?.let { X25519.derivePublic(it) }
    private var remoteStaticPub: ByteArray? = remoteStaticPublic

    private var ephemeralPriv: ByteArray? = null
    private var ephemeralPub: ByteArray? = null
    private var remoteEphemeralPub: ByteArray? = null

    private val messagePatterns: List<List<Token>> = when (pattern) {
        NoisePattern.NN_PSK0 -> listOf(
            listOf(Token.PSK, Token.E),
            listOf(Token.E, Token.EE),
        )
        NoisePattern.IK_PSK2 -> listOf(
            listOf(Token.E, Token.ES, Token.S, Token.SS),
            listOf(Token.E, Token.EE, Token.SE, Token.PSK),
        )
    }

    private var patternIndex = 0
    var splitStates: Pair<CipherState, CipherState>? = null
        private set

    init {
        symmetric.mixHash(prologue)
        // IK pre-message: the responder's static is known to the initiator.
        if (pattern == NoisePattern.IK_PSK2) {
            val responderStatic = if (initiator) {
                remoteStaticPub ?: throw ProtocolException("IK initiator requires the pinned peer static")
            } else {
                localStaticPub ?: throw ProtocolException("IK responder requires its static key")
            }
            symmetric.mixHash(responderStatic)
        }
    }

    val isComplete: Boolean get() = patternIndex >= messagePatterns.size

    fun handshakeHash(): ByteArray = symmetric.handshakeHash()

    /** The initiator's transmitted static learned by the responder (IKpsk2 msg1). */
    fun remoteStaticKey(): ByteArray? = remoteStaticPub

    fun writeMessage(payload: ByteArray): ByteArray {
        if (isComplete) throw ProtocolException("handshake already complete")
        val buffer = ArrayList<Byte>()
        for (token in messagePatterns[patternIndex]) {
            when (token) {
                Token.E -> {
                    val priv = fixedEphemeralPrivate?.takeIf { ephemeralPriv == null } ?: X25519.generatePrivate()
                    ephemeralPriv = priv
                    val pub = X25519.derivePublic(priv)
                    ephemeralPub = pub
                    pub.forEach { buffer.add(it) }
                    symmetric.mixHash(pub)
                    symmetric.mixKey(pub)
                }
                Token.S -> {
                    val pub = localStaticPub ?: throw ProtocolException("missing local static key")
                    symmetric.encryptAndHash(pub).forEach { buffer.add(it) }
                }
                else -> mixDh(token)
            }
        }
        symmetric.encryptAndHash(payload).forEach { buffer.add(it) }
        advance()
        return buffer.toByteArray()
    }

    fun readMessage(message: ByteArray): ByteArray {
        if (isComplete) throw ProtocolException("handshake already complete")
        var offset = 0
        for (token in messagePatterns[patternIndex]) {
            when (token) {
                Token.E -> {
                    val re = message.copyOfRange(offset, offset + 32)
                    offset += 32
                    remoteEphemeralPub = re
                    symmetric.mixHash(re)
                    symmetric.mixKey(re)
                }
                Token.S -> {
                    val len = if (symmetric.cipherState.hasKey()) 48 else 32
                    val temp = message.copyOfRange(offset, offset + len)
                    offset += len
                    remoteStaticPub = symmetric.decryptAndHash(temp)
                }
                else -> mixDh(token)
            }
        }
        val payload = symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
        advance()
        return payload
    }

    private fun mixDh(token: Token) {
        val ep = ephemeralPriv
        val sp = localStaticPriv
        val re = remoteEphemeralPub
        val rs = remoteStaticPub
        val dh = when (token) {
            Token.EE -> X25519.dh(ep!!, re!!)
            Token.ES -> if (initiator) X25519.dh(ep!!, rs!!) else X25519.dh(sp!!, re!!)
            Token.SE -> if (initiator) X25519.dh(sp!!, re!!) else X25519.dh(ep!!, rs!!)
            Token.SS -> X25519.dh(sp!!, rs!!)
            Token.PSK -> {
                symmetric.mixKeyAndHash(psk)
                return
            }
            else -> throw ProtocolException("unexpected token")
        }
        symmetric.mixKey(dh)
    }

    private fun advance() {
        patternIndex++
        if (isComplete) splitStates = symmetric.split()
    }
}
