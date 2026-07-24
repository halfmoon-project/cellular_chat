import XCTest
@testable import CellularChatCore

/// Deterministic seeded-PRNG fuzzing of the three untrusted parsers
/// (IMPLEMENTATION_PLAN.md §7 "Both parsers also receive fuzz and model-state
/// testing"): the canonical CBOR decoder (§3), record/envelope parsing (§5/§6/§8),
/// and BLE fragment reassembly (§9).
///
/// For each parser we run ~2000 iterations of random bytes plus structured
/// mutations (truncation, bit flips, length/header corruption, insert/delete) of
/// valid bytes taken from `shared/vectors/`. Every iteration MUST:
///   * never crash or hang the process,
///   * only ever throw a defined `ProtocolError`, and
///   * when a decode succeeds, canonically re-encode byte-identically.
///
/// The PRNG is a fixed-seed SplitMix64 so a failure reproduces exactly in CI.
final class FuzzTests: XCTestCase {

    private let iterations = 2000
    private let seed: UInt64 = 0x0C7E_11F1_9D2B_5A03   // fixed → CI-stable

    // MARK: - Deterministic PRNG

    private struct SplitMix64 {
        var state: UInt64
        mutating func next() -> UInt64 {
            state = state &+ 0x9E37_79B9_7F4A_7C15
            var z = state
            z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
            z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
            return z ^ (z >> 31)
        }
        mutating func int(_ n: Int) -> Int { n <= 0 ? 0 : Int(next() % UInt64(n)) }
        mutating func byte() -> UInt8 { UInt8(next() & 0xFF) }
        mutating func bool() -> Bool { next() & 1 == 0 }
    }

    // MARK: - Corpus

    private func hexes(_ any: Any?) -> [[UInt8]] {
        ((any as? [Any]) ?? []).compactMap { ($0 as? String).map(hexToBytes) }
    }

    /// Canonical CBOR accept cases.
    private lazy var cborCorpus: [[UInt8]] = {
        let v = Vectors.loadObject("cbor_vectors.json")
        return (v["accept"] as? [[String: Any]] ?? []).compactMap {
            ($0["hex"] as? String).map(hexToBytes)
        }
    }()

    /// Decrypted envelope plaintexts (session §8 + pairing §6).
    private lazy var envelopeCorpus: [[UInt8]] = {
        var out: [[UInt8]] = []
        let env = Vectors.loadObject("envelope_vectors.json")
        if let s = env["session"] as? [String: Any] {
            out += (s["transportRecords"] as? [[String: Any]] ?? [])
                .compactMap { ($0["plaintextHex"] as? String).map(hexToBytes) }
        }
        let app = Vectors.loadObject("noise_app_vectors.json")
        if let p = app["pairing"] as? [String: Any] {
            out += (p["transportRecords"] as? [[String: Any]] ?? [])
                .compactMap { ($0["plaintextHex"] as? String).map(hexToBytes) }
        }
        return out
    }()

    /// Whole records (type byte + noise ciphertext) for `Records.parse`.
    private lazy var recordCorpus: [[UInt8]] = {
        var out: [[UInt8]] = []
        let env = Vectors.loadObject("envelope_vectors.json")
        if let s = env["session"] as? [String: Any] {
            out += (s["transportRecords"] as? [[String: Any]] ?? [])
                .compactMap { ($0["recordHex"] as? String).map(hexToBytes) }
            for k in ["msg1RecordHex", "msg2RecordHex"] {
                if let h = s[k] as? String { out.append(hexToBytes(h)) }
            }
        }
        return out
    }()

    /// Valid fragment sequences (one array per record) from §9 vectors.
    private lazy var fragmentCorpus: [[[UInt8]]] = {
        let v = Vectors.loadObject("fragment_vectors.json")
        var out: [[[UInt8]]] = []
        for case let c as [String: Any] in (v["cases"] as? [Any] ?? []) {
            out.append(hexes(c["fragments"]))
        }
        for case let c as [String: Any] in (v["malformed"] as? [Any] ?? []) {
            out.append(hexes(c["fragments"]))
        }
        return out.filter { !$0.isEmpty }
    }()

    // MARK: - Input generation

    /// A random byte string of a small length (exercises the pure-noise path).
    private func randomBytes(_ rng: inout SplitMix64) -> [UInt8] {
        let n = rng.int(72)
        return (0..<n).map { _ in rng.byte() }
    }

    /// A structured mutation of one corpus item: truncation, bit flips,
    /// header/length corruption, or a byte insert/delete.
    private func mutate(_ base: [UInt8], _ rng: inout SplitMix64) -> [UInt8] {
        if base.isEmpty { return randomBytes(&rng) }
        var out = base
        switch rng.int(6) {
        case 0:                                   // truncate
            out = Array(out.prefix(rng.int(out.count + 1)))
        case 1:                                   // flip 1..4 bits
            for _ in 0...rng.int(3) {
                out[rng.int(out.count)] ^= UInt8(1 << rng.int(8))
            }
        case 2:                                   // corrupt a header/length byte
            out[rng.int(min(5, out.count))] = rng.byte()
        case 3:                                   // insert a byte
            out.insert(rng.byte(), at: rng.int(out.count + 1))
        case 4:                                   // delete a byte
            out.remove(at: rng.int(out.count))
        default:                                  // append junk
            for _ in 0...rng.int(4) { out.append(rng.byte()) }
        }
        return out
    }

    private func nextInput(_ corpus: [[UInt8]], _ rng: inout SplitMix64) -> [UInt8] {
        if corpus.isEmpty || rng.int(10) < 3 { return randomBytes(&rng) }
        return mutate(corpus[rng.int(corpus.count)], &rng)
    }

    /// Runs `body`; passes if it returns (then `onSuccess`) or throws a
    /// `ProtocolError`. Any other error is a defect. A trap would abort the run,
    /// which is exactly the crash the fuzzer is meant to surface.
    private func expectDefined<T>(_ input: [UInt8], _ label: String,
                                  _ body: ([UInt8]) throws -> T,
                                  onSuccess: (T) -> Void = { _ in }) {
        do {
            onSuccess(try body(input))
        } catch is ProtocolError {
            // defined, expected rejection
        } catch {
            XCTFail("\(label): threw non-ProtocolError \(error) on \(bytesToHex(input))")
        }
    }

    // MARK: - (a) canonical CBOR decoder

    func testFuzzCborDecoder() {
        var rng = SplitMix64(state: seed)
        XCTAssertFalse(cborCorpus.isEmpty)
        var decoded = 0
        for _ in 0..<iterations {
            let input = nextInput(cborCorpus, &rng)
            expectDefined(input, "cbor", { try CBORCoder.decode($0) }) { value in
                decoded += 1
                // A successful decode was canonical, so it MUST re-encode identically.
                XCTAssertEqual(CBORCoder.encode(value), input,
                               "cbor re-encode drifted for \(bytesToHex(input))")
            }
        }
        // Guard against a vacuous run: some mutations must both decode and get rejected.
        XCTAssertGreaterThan(decoded, 50, "too few successful decodes to exercise round-trip")
        XCTAssertLessThan(decoded, iterations, "no inputs were rejected — corpus not being mutated")
    }

    /// Regression: a collection/string count larger than the remaining input must
    /// throw (PROTOCOL_V2 §3), never allocate/abort. Mirrors Android Cbor.readLength.
    func testCborOversizeCountThrows() {
        let huge: [UInt8] = [0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff]
        for major: UInt8 in [2, 3, 4, 5] {
            let input = [(major << 5) | 27] + huge
            XCTAssertThrowsError(try CBORCoder.decode(input)) { error in
                XCTAssertTrue(error is ProtocolError, "expected ProtocolError, got \(error)")
            }
        }
    }

    // MARK: - (b) record + envelope parsing

    func testFuzzRecordParsing() {
        var rng = SplitMix64(state: seed &+ 1)
        XCTAssertFalse(recordCorpus.isEmpty)
        for _ in 0..<iterations {
            let input = nextInput(recordCorpus, &rng)
            expectDefined(input, "record", { try Records.parse($0) }) { parsed in
                XCTAssertEqual(Records.make(parsed.type, payload: parsed.payload), input)
            }
            // Stream framing shares the size rules; it must also never crash.
            expectDefined(input, "stream", { try Records.readStream($0) })
        }
    }

    func testFuzzEnvelopeDecoding() {
        var rng = SplitMix64(state: seed &+ 2)
        XCTAssertFalse(envelopeCorpus.isEmpty)
        for _ in 0..<iterations {
            let input = nextInput(envelopeCorpus, &rng)
            expectDefined(input, "session-envelope", { try SessionEnvelope.decode($0) }) { env in
                XCTAssertEqual(env.encoded(), input, "session envelope re-encode drifted")
            }
            expectDefined(input, "pairing-envelope", { try PairingEnvelope.decode($0) }) { env in
                XCTAssertEqual(env.encoded(), input, "pairing envelope re-encode drifted")
            }
        }
    }

    // MARK: - (c) BLE fragment reassembly

    func testFuzzFragmentReassembly() {
        var rng = SplitMix64(state: seed &+ 3)
        XCTAssertFalse(fragmentCorpus.isEmpty)
        for _ in 0..<iterations {
            let sequence = fragmentSequence(&rng)
            let reassembler = FragmentReassembler()
            do {
                var completed: [UInt8]? = nil
                for fragment in sequence {
                    if let record = try reassembler.push(fragment) { completed = record }
                }
                if let record = completed {
                    XCTAssertFalse(record.isEmpty)
                    XCTAssertLessThanOrEqual(record.count, RecordLimits.maxRecord)
                    // A completed record must survive a clean re-fragment/reassemble.
                    let refrags = Fragmentation.fragment(record: record, mtu: 185)
                    let verifier = FragmentReassembler()
                    var round: [UInt8]? = nil
                    for f in refrags { if let r = try verifier.push(f) { round = r } }
                    XCTAssertEqual(round, record, "fragment round-trip drifted")
                }
            } catch is ProtocolError {
                // defined §9 rejection
            } catch {
                XCTFail("fragment: threw non-ProtocolError \(error)")
            }
        }
    }

    /// Either a mutated valid fragment list or a short run of random fragments.
    private func fragmentSequence(_ rng: inout SplitMix64) -> [[UInt8]] {
        if fragmentCorpus.isEmpty || rng.int(10) < 3 {
            let n = 1 + rng.int(4)
            return (0..<n).map { _ in randomBytes(&rng) }
        }
        var seq = fragmentCorpus[rng.int(fragmentCorpus.count)]
        if !seq.isEmpty {                     // mutate one fragment in place
            let idx = rng.int(seq.count)
            seq[idx] = mutate(seq[idx], &rng)
        }
        return seq
    }
}
