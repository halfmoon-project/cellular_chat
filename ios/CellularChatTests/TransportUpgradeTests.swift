import XCTest
import CryptoKit
import CellularChatCore
@testable import CellularChat

/// A paired in-memory transport: each side delivers to the other's `onRecord`.
private final class UpgradeLoopback: PeerTransport {
    let kind: TransportKind
    let isAvailable = true
    var onRecord: (([UInt8]) -> Void)?
    var onClosed: ((ReasonCode) -> Void)?
    weak var peer: UpgradeLoopback?

    init(kind: TransportKind) { self.kind = kind }
    func connect() async -> Result<Void, TransportFailure> { .success(()) }
    func send(record: [UInt8]) throws {
        let peer = self.peer
        Task { @MainActor in peer?.onRecord?(record) }
    }
    func disconnect(reason: ReasonCode) { onClosed?(reason) }
}

/// Transport upgrade (PROTOCOL_V2.md §10, Feature A). The wire-critical
/// invariants — sid continuity, per-direction counter reset, ranging repoint
/// gating, and K-only abort on a capability difference — are exercised at the
/// `SessionRunner` level; the driver/responder decision logic is unit-tested via
/// the coordinator's pure helpers.
@MainActor
final class TransportUpgradeTests: XCTestCase {

    private struct Pair {
        let recordA: PairRecord, recordB: PairRecord
        let aPriv: Curve25519.KeyAgreement.PrivateKey
        let bPriv: Curve25519.KeyAgreement.PrivateKey
        let pairRoot: [UInt8]
    }

    private func makePair() -> Pair {
        let aPriv = Curve25519.KeyAgreement.PrivateKey()
        let bPriv = Curve25519.KeyAgreement.PrivateKey()
        let aPub = Array(aPriv.publicKey.rawRepresentation)
        let bPub = Array(bPriv.publicKey.rawRepresentation)
        let pairId = [UInt8](repeating: 0x11, count: 16)
        let pairRoot = [UInt8](repeating: 0x22, count: 32)
        return Pair(
            recordA: PairRecord(pairId: pairId, roleCode: 1, peerStaticPub: bPub,
                                negotiatedVersion: 2, alias: "B", createdAt: 1, revoked: false),
            recordB: PairRecord(pairId: pairId, roleCode: 2, peerStaticPub: aPub,
                                negotiatedVersion: 2, alias: "A", createdAt: 1, revoked: false),
            aPriv: aPriv, bPriv: bPriv, pairRoot: pairRoot)
    }

    private func makeUpgradeRunner(role: SessionRunner.RunnerRole, pair p: Pair,
                                   transport: PeerTransport, localCaps: CapabilitySet,
                                   ranging: RangingCoordinator, sid: [UInt8],
                                   expectedPeerCaps: CapabilitySet) throws -> SessionRunner {
        let (record, priv) = role == .initiator ? (p.recordA, p.aPriv) : (p.recordB, p.bPriv)
        return try SessionRunner(role: role, pair: record, pairRoot: p.pairRoot,
                                 localStaticPriv: Array(priv.rawRepresentation), transport: transport,
                                 localCaps: localCaps, ranging: ranging, findDeadline: 0,
                                 upgradeSid: sid, expectedPeerCaps: expectedPeerCaps)
    }

    /// An upgrade K session reuses the logical `sid`, completes a fresh handshake,
    /// and does NOT (re)start or bind ranging until switchover.
    func testUpgradeReusesSidAndDoesNotStartRanging() async throws {
        let p = makePair()
        let sid = [UInt8](repeating: 0xAB, count: 16)
        let capsA = CapabilitySet(os: .ios)
        let capsB = CapabilitySet(os: .ios)
        let awareA = UpgradeLoopback(kind: .wifiAware)
        let awareB = UpgradeLoopback(kind: .wifiAware)
        awareA.peer = awareB; awareB.peer = awareA
        let rangingA = RangingCoordinator()
        let rangingB = RangingCoordinator()

        let krA = try makeUpgradeRunner(role: .initiator, pair: p, transport: awareA,
                                        localCaps: capsA, ranging: rangingA, sid: sid, expectedPeerCaps: capsB)
        let krB = try makeUpgradeRunner(role: .responder, pair: p, transport: awareB,
                                        localCaps: capsB, ranging: rangingB, sid: sid, expectedPeerCaps: capsA)

        // Constructing a runner never binds ranging (§10 exactly-one-runner rule).
        XCTAssertNil(rangingA.sendMessage)
        XCTAssertNil(rangingB.sendMessage)

        let cA = expectation(description: "A connected")
        let cB = expectation(description: "B connected")
        krA.onConnected = { cA.fulfill() }
        krB.onConnected = { cB.fulfill() }
        krB.start(); krA.start()
        await fulfillment(of: [cA, cB], timeout: 2)

        // Same logical sid across the upgrade transport.
        XCTAssertEqual(krA.sid, sid)
        XCTAssertEqual(krB.sid, sid)
        // The upgrade runner does not renegotiate ranging; attempt state stays on
        // the working runner. Ranging remains unbound until activateRanging().
        XCTAssertNil(rangingA.selection)
        XCTAssertNil(rangingB.selection)
        XCTAssertNil(rangingA.sendMessage)

        krA.activateRanging()
        XCTAssertNotNil(rangingA.sendMessage)   // switchover binds ranging to K
    }

    /// A capability difference on the upgrade transport aborts K only (via the
    /// runner's fatal callback), never the logical session.
    func testUpgradeResponderAbortsOnCapabilityDifference() async throws {
        let p = makePair()
        let sid = [UInt8](repeating: 0xCD, count: 16)
        let capsA = CapabilitySet(os: .ios, wifiAware: true)   // what krA advertises on K
        let capsB = CapabilitySet(os: .ios)
        let wrongExpected = CapabilitySet(os: .ios)            // != capsA (wifiAware differs)
        let awareA = UpgradeLoopback(kind: .wifiAware)
        let awareB = UpgradeLoopback(kind: .wifiAware)
        awareA.peer = awareB; awareB.peer = awareA

        let krA = try makeUpgradeRunner(role: .initiator, pair: p, transport: awareA,
                                        localCaps: capsA, ranging: RangingCoordinator(),
                                        sid: sid, expectedPeerCaps: capsB)
        let krB = try makeUpgradeRunner(role: .responder, pair: p, transport: awareB,
                                        localCaps: capsB, ranging: RangingCoordinator(),
                                        sid: sid, expectedPeerCaps: wrongExpected)
        var fatalReason: ReasonCode?
        let fatal = expectation(description: "K aborts")
        krB.onFatal = { fatalReason = $0; fatal.fulfill() }
        krB.start(); krA.start()
        await fulfillment(of: [fatal], timeout: 2)
        XCTAssertEqual(fatalReason, .capabilityMismatch)
    }

    // MARK: - Driver/responder decision logic (pure)

    func testUpgradeCandidateEligibility() {
        let peerAware = CapabilitySet(os: .ios, wifiAware: true)
        let peerNone = CapabilitySet(os: .ios)
        // From ble: aware available AND peer advertises → aware.
        XCTAssertEqual(FindSessionCoordinator.upgradeCandidate(
            activeKind: .ble, availableKinds: [.wifiAware], peerCaps: peerAware), .wifiAware)
        // Peer does not advertise aware → nil.
        XCTAssertNil(FindSessionCoordinator.upgradeCandidate(
            activeKind: .ble, availableKinds: [.wifiAware], peerCaps: peerNone))
        // Not locally available → nil.
        XCTAssertNil(FindSessionCoordinator.upgradeCandidate(
            activeKind: .ble, availableKinds: [], peerCaps: peerAware))
        // Already on the best transport → nil (no strictly-better target).
        XCTAssertNil(FindSessionCoordinator.upgradeCandidate(
            activeKind: .wifiAware, availableKinds: [.wifiAware], peerCaps: peerAware))
        // ble is never an upgrade target.
        XCTAssertNil(FindSessionCoordinator.upgradeCandidate(
            activeKind: .nearby, availableKinds: [.ble], peerCaps: peerAware))
    }

    func testUpgradeAckAcceptedDecision() {
        // aware (code 1) from ble, available → accept.
        XCTAssertTrue(FindSessionCoordinator.upgradeAckAccepted(
            code: 1, activeKind: .ble, availableKinds: [.wifiAware]))
        // ble (code 3) is never accepted.
        XCTAssertFalse(FindSessionCoordinator.upgradeAckAccepted(
            code: 3, activeKind: .ble, availableKinds: [.wifiAware, .ble]))
        // Not strictly better (nearby from aware) → decline.
        XCTAssertFalse(FindSessionCoordinator.upgradeAckAccepted(
            code: 2, activeKind: .wifiAware, availableKinds: [.nearby]))
        // Unavailable locally → decline.
        XCTAssertFalse(FindSessionCoordinator.upgradeAckAccepted(
            code: 1, activeKind: .ble, availableKinds: []))
        // Unknown code → decline.
        XCTAssertFalse(FindSessionCoordinator.upgradeAckAccepted(
            code: 99, activeKind: .ble, availableKinds: [.wifiAware]))
    }

    /// Idempotency (A.9): a duplicate `(sid, attemptId)` re-sends the cached ack
    /// and never opens a second K.
    func testDuplicateUpgradeIsANoOp() {
        let first = FindSessionCoordinator.decideUpgradeAck(
            attemptId: 1, code: 1, activeKind: .ble, availableKinds: [.wifiAware], cache: nil)
        XCTAssertEqual(first, FindSessionCoordinator.UpgradeAckDecision(code: 1, accepted: true, openK: true))

        // A duplicate for the same attemptId re-sends the cached ack, opens no K.
        let dup = FindSessionCoordinator.decideUpgradeAck(
            attemptId: 1, code: 1, activeKind: .ble, availableKinds: [.wifiAware],
            cache: (attemptId: 1, code: 1, accepted: true))
        XCTAssertEqual(dup, FindSessionCoordinator.UpgradeAckDecision(code: 1, accepted: true, openK: false))

        // A declined target caches accepted=false and opens no K.
        let declined = FindSessionCoordinator.decideUpgradeAck(
            attemptId: 2, code: 3, activeKind: .ble, availableKinds: [.wifiAware], cache: nil)
        XCTAssertEqual(declined, FindSessionCoordinator.UpgradeAckDecision(code: 3, accepted: false, openK: false))
    }
}
