import XCTest
import Foundation
@testable import CellularChat
import CellularChatCore

/// Consumes `shared/vectors/duplicate_ops.json` (PROTOCOL_V2.md §10:379-382,
/// §12:469-470) against the real app-side `RangingCoordinator`. The
/// (sid, attemptId)-keyed `ni_peer` ranging_offer/accept/start/stop exchange is
/// the iOS<->iOS path, so this is where the idempotency rule is actually
/// enforced: a duplicate ranging_start is a single session effect, a duplicate
/// ranging_stop is a no-op, and an accept for a stale/older attempt is ignored.
///
/// Each fixture case pins the exact outbound message stream the coordinator must
/// emit (`expectOutbound`), generated and self-validated by the same reference
/// model in `tools/genvectors`. The coordinator drives no `NISession` here — the
/// negotiation precedes any platform ranging start, and UWB is unavailable on the
/// host anyway — so only the message layer is exercised.
@MainActor
final class DuplicateOpsTests: XCTestCase {

    private func iosUwb() -> CapabilitySet { CapabilitySet(os: .ios, uwbPresent: true) }

    private func msgType(_ op: String) -> SessionMsgType {
        switch op {
        case "ranging_offer": return .rangingOffer
        case "ranging_accept": return .rangingAccept
        case "ranging_start": return .rangingStart
        case "ranging_stop": return .rangingStop
        case "ranging_error": return .rangingError
        default: fatalError("unknown op \(op)")
        }
    }

    private func opName(_ type: SessionMsgType) -> String {
        switch type {
        case .rangingOffer: return "ranging_offer"
        case .rangingAccept: return "ranging_accept"
        case .rangingStart: return "ranging_start"
        case .rangingStop: return "ranging_stop"
        case .rangingError: return "ranging_error"
        default: return "other(\(type.rawValue))"
        }
    }

    private func body(for op: [String: Any]) -> CBOR {
        var pairs = [CBORPair(.uint(1), .uint(UInt64(op["attemptId"] as! Int)))]
        if let method = op["method"] as? Int {
            pairs.append(CBORPair(.uint(2), .uint(UInt64(method))))
        } else if let reason = op["reason"] as? Int {
            pairs.append(CBORPair(.uint(2), .uint(UInt64(reason))))
        }
        return .map(pairs)
    }

    func testDuplicateOpsFixtureMatchesCoordinatorOutbound() {
        let fixture = loadDuplicateOps()
        XCTAssertEqual(hexToBytes(fixture["sidHex"] as! String).count, 16)
        let cases = fixture["cases"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)

        for c in cases {
            let name = c["name"] as! String
            let role = c["role"] as! String
            let ops = c["ops"] as! [[String: Any]]
            let expected = (c["expectOutbound"] as! [[String: Any]]).map {
                ($0["op"] as! String, UInt64($0["attemptId"] as! Int))
            }

            let coordinator = RangingCoordinator()
            var sent: [(SessionMsgType, CBOR)] = []
            coordinator.sendMessage = { type, body in sent.append((type, body)) }

            // An "offerer" emits ranging_offer(attemptId=1) on start; a "controlee"
            // stays silent until the peer offers.
            coordinator.start(local: iosUwb(), peer: iosUwb(),
                              isInitiator: role == "offerer")

            for op in ops {
                coordinator.handleSessionMessage(msgType(op["op"] as! String),
                                                 body: body(for: op))
            }

            let actual = sent.map { (opName($0.0), $0.1.value(forKey: 1)?.asUInt) }
            XCTAssertEqual(actual.count, expected.count,
                           "\(name): outbound count \(actual) vs \(expected)")
            for (a, e) in zip(actual, expected) {
                XCTAssertEqual(a.0, e.0, "\(name): outbound op")
                XCTAssertEqual(a.1, e.1, "\(name): outbound attemptId")
            }
            coordinator.stop()
        }
    }

    // MARK: - fixture loading

    private func loadDuplicateOps() -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            let f = dir.appendingPathComponent("shared/vectors/duplicate_ops.json")
            if FileManager.default.fileExists(atPath: f.path) {
                let data = try! Data(contentsOf: f)
                return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
            }
            dir = dir.deletingLastPathComponent()
        }
        fatalError("could not locate shared/vectors/duplicate_ops.json from \(#filePath)")
    }

    private func hexToBytes(_ s: String) -> [UInt8] {
        var out = [UInt8](); out.reserveCapacity(s.count / 2)
        var idx = s.startIndex
        while idx < s.endIndex {
            let next = s.index(idx, offsetBy: 2)
            out.append(UInt8(s[idx..<next], radix: 16)!)
            idx = next
        }
        return out
    }
}
