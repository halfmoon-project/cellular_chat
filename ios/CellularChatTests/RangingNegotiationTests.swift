import XCTest
@testable import CellularChat
import CellularChatCore

/// Ranging-attempt negotiation over the authenticated session (PROTOCOL_V2.md §8
/// offer → accept → start, §10/§12). Exercises only the pure message layer: the
/// offer/accept/start emission and the monotonic, echoed attemptId. No NISession
/// is created (the exchange precedes any platform ranging start).
@MainActor
final class RangingNegotiationTests: XCTestCase {

    private func iosUwb() -> CapabilitySet { CapabilitySet(os: .ios, uwbPresent: true) }

    private func makeCoordinator() -> (RangingCoordinator, () -> [(SessionMsgType, CBOR)]) {
        let coordinator = RangingCoordinator()
        var sent: [(SessionMsgType, CBOR)] = []
        coordinator.sendMessage = { type, body in sent.append((type, body)) }
        return (coordinator, { sent })
    }

    func testOffererDrivesOfferThenStartWithMonotonicAttemptId() {
        let (coordinator, sent) = makeCoordinator()
        coordinator.start(local: iosUwb(), peer: iosUwb(), isInitiator: true)

        // The ni_peer offerer emits ranging_offer(attemptId=1, method=niPeer).
        XCTAssertEqual(sent().first?.0, .rangingOffer)
        XCTAssertEqual(sent().first?.1.value(forKey: 1)?.asUInt, 1)
        XCTAssertEqual(sent().first?.1.value(forKey: 2)?.asUInt, RangingMethod.niPeer.rawValue)

        // The peer accepts → the offerer emits ranging_start for the same attempt.
        coordinator.handleSessionMessage(.rangingAccept, body: .map([
            CBORPair(.uint(1), .uint(1)),
            CBORPair(.uint(2), .uint(RangingMethod.niPeer.rawValue)),
        ]))
        XCTAssertTrue(sent().contains { $0.0 == .rangingStart && $0.1.value(forKey: 1)?.asUInt == 1 })
    }

    func testControleeAcceptsOfferEchoingPeerAttemptId() {
        let (coordinator, sent) = makeCoordinator()
        coordinator.start(local: iosUwb(), peer: iosUwb(), isInitiator: false)

        // The controlee is silent until the peer offers.
        XCTAssertTrue(sent().isEmpty)

        // An inbound ranging_offer is accepted (not dropped), echoing attemptId 7.
        coordinator.handleSessionMessage(.rangingOffer, body: .map([
            CBORPair(.uint(1), .uint(7)),
            CBORPair(.uint(2), .uint(RangingMethod.niPeer.rawValue)),
        ]))
        XCTAssertEqual(sent().count, 1)
        XCTAssertEqual(sent().first?.0, .rangingAccept)
        XCTAssertEqual(sent().first?.1.value(forKey: 1)?.asUInt, 7)
    }

    func testStaleAcceptDoesNotStart() {
        let (coordinator, sent) = makeCoordinator()
        coordinator.start(local: iosUwb(), peer: iosUwb(), isInitiator: true)   // offers attemptId 1
        // An accept for an unknown attemptId must never produce a ranging_start.
        coordinator.handleSessionMessage(.rangingAccept, body: .map([
            CBORPair(.uint(1), .uint(99)),
            CBORPair(.uint(2), .uint(RangingMethod.niPeer.rawValue)),
        ]))
        XCTAssertFalse(sent().contains { $0.0 == .rangingStart })
    }
}
