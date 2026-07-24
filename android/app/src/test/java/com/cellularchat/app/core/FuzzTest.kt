package com.cellularchat.app.core

import com.cellularchat.app.core.cbor.Cbor
import com.cellularchat.app.core.protocol.Fragmentation
import com.cellularchat.app.core.protocol.PairingEnvelopeCodec
import com.cellularchat.app.core.protocol.Reassembler
import com.cellularchat.app.core.protocol.Records
import com.cellularchat.app.core.protocol.SessionEnvelopeCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Deterministic seeded-PRNG fuzzing of the three untrusted parsers
 * (IMPLEMENTATION_PLAN.md §7 "Both parsers also receive fuzz and model-state
 * testing"): the canonical CBOR decoder (§3), record/envelope parsing (§5/§6/§8),
 * and BLE fragment reassembly (§9).
 *
 * Each parser runs ~2000 iterations of random bytes plus structured mutations
 * (truncation, bit flips, header/length corruption, insert/delete) of valid bytes
 * from `shared/vectors/`. Every iteration MUST: never crash or hang, only ever
 * throw a defined [ProtocolException] (CborException / FragmentException / …), and
 * when a decode succeeds, canonically re-encode byte-identically. The PRNG is a
 * fixed-seed SplitMix64 so a failure reproduces exactly in CI.
 */
class FuzzTest {

    private val iterations = 2000
    private val seed = 0x0C7E11F19D2B5A03uL   // fixed → CI-stable

    // --- deterministic PRNG ---

    private class SplitMix64(seed: ULong) {
        private var state = seed
        fun next(): ULong {
            state += 0x9E3779B97F4A7C15uL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
            z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
            return z xor (z shr 31)
        }
        fun int(n: Int): Int = if (n <= 0) 0 else (next() % n.toULong()).toInt()
        fun byte(): Byte = (next() and 0xFFuL).toByte()
    }

    // --- corpus ---

    private val cborCorpus: List<ByteArray> = run {
        val a = Vectors.json("cbor_vectors.json").getJSONArray("accept")
        (0 until a.length()).map { hex(a.getJSONObject(it).getString("hex")) }
    }

    private val envelopeCorpus: List<ByteArray> = run {
        val out = mutableListOf<ByteArray>()
        val session = Vectors.json("envelope_vectors.json").getJSONObject("session")
            .getJSONArray("transportRecords")
        for (i in 0 until session.length()) out.add(hex(session.getJSONObject(i).getString("plaintextHex")))
        val pairing = Vectors.json("noise_app_vectors.json").getJSONObject("pairing")
            .getJSONArray("transportRecords")
        for (i in 0 until pairing.length()) out.add(hex(pairing.getJSONObject(i).getString("plaintextHex")))
        out
    }

    private val recordCorpus: List<ByteArray> = run {
        val out = mutableListOf<ByteArray>()
        val session = Vectors.json("envelope_vectors.json").getJSONObject("session")
        val recs = session.getJSONArray("transportRecords")
        for (i in 0 until recs.length()) out.add(hex(recs.getJSONObject(i).getString("recordHex")))
        out.add(hex(session.getString("msg1RecordHex")))
        out.add(hex(session.getString("msg2RecordHex")))
        out
    }

    private val fragmentCorpus: List<List<ByteArray>> = run {
        val v = Vectors.json("fragment_vectors.json")
        val out = mutableListOf<List<ByteArray>>()
        for (key in listOf("cases", "malformed")) {
            val arr = v.getJSONArray(key)
            for (i in 0 until arr.length()) {
                val frags = arr.getJSONObject(i).optJSONArray("fragments") ?: continue
                out.add((0 until frags.length()).map { hex(frags.getString(it)) })
            }
        }
        out.filter { it.isNotEmpty() }
    }

    // --- input generation ---

    private fun randomBytes(rng: SplitMix64): ByteArray = ByteArray(rng.int(72)) { rng.byte() }

    private fun mutate(base: ByteArray, rng: SplitMix64): ByteArray {
        if (base.isEmpty()) return randomBytes(rng)
        val out = base.toMutableList()
        when (rng.int(6)) {
            0 -> return base.copyOf(rng.int(base.size + 1))          // truncate
            1 -> repeat(rng.int(4) + 1) {                             // flip 1..4 bits
                val i = rng.int(out.size)
                out[i] = (out[i].toInt() xor (1 shl rng.int(8))).toByte()
            }
            2 -> {                                                    // corrupt a header/length byte
                val i = rng.int(minOf(5, out.size))
                out[i] = rng.byte()
            }
            3 -> out.add(rng.int(out.size + 1), rng.byte())          // insert a byte
            4 -> out.removeAt(rng.int(out.size))                     // delete a byte
            else -> repeat(rng.int(4) + 1) { out.add(rng.byte()) }   // append junk
        }
        return out.toByteArray()
    }

    private fun nextInput(corpus: List<ByteArray>, rng: SplitMix64): ByteArray =
        if (corpus.isEmpty() || rng.int(10) < 3) randomBytes(rng)
        else mutate(corpus[rng.int(corpus.size)], rng)

    /** Runs [body]; a [ProtocolException] is the only tolerated throw. */
    private inline fun expectDefined(label: String, input: ByteArray, body: () -> Unit) {
        try {
            body()
        } catch (expected: ProtocolException) {
            // defined rejection
        } catch (assertion: AssertionError) {
            throw assertion                    // a round-trip mismatch is a real failure
        } catch (other: Throwable) {
            fail("$label: threw ${other::class.java.name}: ${other.message} on ${input.toHex()}")
        }
    }

    // --- (a) canonical CBOR decoder ---

    @Test
    fun fuzzCborDecoder() {
        val rng = SplitMix64(seed)
        assertTrue(cborCorpus.isNotEmpty())
        var decoded = 0
        repeat(iterations) {
            val input = nextInput(cborCorpus, rng)
            expectDefined("cbor", input) {
                val value = Cbor.decode(input)
                // A successful decode was canonical, so re-encode MUST match.
                assertArrayEquals("cbor re-encode drifted for ${input.toHex()}", input, Cbor.encode(value))
                decoded++
            }
        }
        assertTrue("too few / too many successful decodes ($decoded)", decoded in 51 until iterations)
    }

    // --- (b) record + envelope parsing ---

    @Test
    fun fuzzRecordParsing() {
        val rng = SplitMix64(seed + 1uL)
        assertTrue(recordCorpus.isNotEmpty())
        var parsed = 0
        repeat(iterations) {
            val input = nextInput(recordCorpus, rng)
            expectDefined("record", input) {
                val type = Records.recordType(input)
                val payload = Records.payload(input)
                assertArrayEquals(input, Records.build(type, payload))
                parsed++
            }
        }
        assertTrue("record parsing looks vacuous ($parsed)", parsed in 1 until iterations)
    }

    @Test
    fun fuzzEnvelopeDecoding() {
        val rng = SplitMix64(seed + 2uL)
        assertTrue(envelopeCorpus.isNotEmpty())
        var decoded = 0
        repeat(iterations) {
            val input = nextInput(envelopeCorpus, rng)
            expectDefined("session-envelope", input) {
                val env = SessionEnvelopeCodec.decode(input)
                assertArrayEquals("session envelope re-encode drifted", input, SessionEnvelopeCodec.encode(env))
                decoded++
            }
            expectDefined("pairing-envelope", input) {
                val env = PairingEnvelopeCodec.decode(input)
                assertArrayEquals("pairing envelope re-encode drifted", input, PairingEnvelopeCodec.encode(env))
            }
        }
        assertTrue("envelope decoding looks vacuous ($decoded)", decoded in 1 until iterations)
    }

    // --- (c) BLE fragment reassembly ---

    @Test
    fun fuzzFragmentReassembly() {
        val rng = SplitMix64(seed + 3uL)
        assertTrue(fragmentCorpus.isNotEmpty())
        repeat(iterations) {
            val sequence = fragmentSequence(rng)
            try {
                val reassembler = Reassembler()
                var completed: ByteArray? = null
                for (fragment in sequence) {
                    reassembler.offer(fragment)?.let { completed = it }
                }
                completed?.let { record ->
                    assertTrue(record.isNotEmpty())
                    assertTrue(record.size <= Records.MAX_RECORD)
                    // A completed record must survive a clean re-fragment/reassemble.
                    val round = Fragmentation.reassemble(Fragmentation.fragment(record, 185))
                    assertArrayEquals("fragment round-trip drifted", record, round)
                }
            } catch (expected: ProtocolException) {
                // defined §9 rejection
            } catch (assertion: AssertionError) {
                throw assertion
            } catch (other: Throwable) {
                fail("fragment: threw ${other::class.java.name}: ${other.message}")
            }
        }
    }

    private fun fragmentSequence(rng: SplitMix64): List<ByteArray> {
        if (fragmentCorpus.isEmpty() || rng.int(10) < 3) {
            return List(1 + rng.int(4)) { randomBytes(rng) }
        }
        val seq = fragmentCorpus[rng.int(fragmentCorpus.size)].toMutableList()
        if (seq.isNotEmpty()) {
            val idx = rng.int(seq.size)
            seq[idx] = mutate(seq[idx], rng)
        }
        return seq
    }
}
