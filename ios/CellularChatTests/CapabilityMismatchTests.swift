import XCTest
import CryptoKit
import CellularChatCore
@testable import CellularChat

/// Capability-transcript mismatch enforcement (PROTOCOL_V2.md §14, Feature B):
/// the ranging coordinator rejects out-of-transcript methods, and the session
/// runner rejects a capability re-announcement that drifts from the bound set.
@MainActor
final class CapabilityMismatchTests: XCTestCase {

    private func iosUwb() -> CapabilitySet { CapabilitySet(os: .ios, uwbPresent: true) }
    private func iosNoUwb() -> CapabilitySet { CapabilitySet(os: .ios) }

    private func makeCoordinator() -> (RangingCoordinator, () -> Int) {
        let coordinator = RangingCoordinator()
        var count = 0
        coordinator.onCapabilityMismatch = { count += 1 }
        coordinator.sendMessage = { _, _ in }
        return (coordinator, { count })
    }

    // B.2.2: an offer for a method outside the mutually-supported set → mismatch.
    func testOfferWithUnsupportedMethodRaisesMismatch() {
        let (coordinator, mismatches) = makeCoordinator()
        // Both ios but NO uwb → only ble_rssi supported. An ni_peer offer is out.
        coordinator.start(local: iosNoUwb(), peer: iosNoUwb(), isInitiator: false)
        coordinator.handleSessionMessage(.rangingOffer, body: .map([
            CBORPair(.uint(1), .uint(5)),
            CBORPair(.uint(2), .uint(RangingMethod.niPeer.rawValue)),
        ]))
        XCTAssertEqual(mismatches(), 1)
    }

    // B.2.3: an accept whose method differs from our offer → mismatch.
    func testAcceptMethodDivergenceRaisesMismatch() {
        let (coordinator, mismatches) = makeCoordinator()
        coordinator.start(local: iosUwb(), peer: iosUwb(), isInitiator: true)   // offers ni_peer, attempt 1
        // A supported method (ble_rssi) but != the offered ni_peer for attempt 1.
        coordinator.handleSessionMessage(.rangingAccept, body: .map([
            CBORPair(.uint(1), .uint(1)),
            CBORPair(.uint(2), .uint(RangingMethod.bleRssi.rawValue)),
        ]))
        XCTAssertEqual(mismatches(), 1)
    }

    // B.2.4: apple_config implies uwb_apple_interop; two ios → unsupported → mismatch.
    func testImplicitAppleConfigRaisesMismatch() {
        let (coordinator, mismatches) = makeCoordinator()
        coordinator.start(local: iosUwb(), peer: iosUwb(), isInitiator: false)
        coordinator.handleSessionMessage(.appleConfig, body: .map([
            CBORPair(.uint(1), .uint(3)),
            CBORPair(.uint(2), .bytes([UInt8](repeating: 0, count: 48))),
        ]))
        XCTAssertEqual(mismatches(), 1)
    }

    // B.2.4: oob_data implies uwb_android_oob, never supportable on iOS → mismatch.
    func testImplicitOobDataRaisesMismatch() {
        let (coordinator, mismatches) = makeCoordinator()
        coordinator.start(local: iosUwb(), peer: iosUwb(), isInitiator: false)
        coordinator.handleSessionMessage(.oobData, body: .map([
            CBORPair(.uint(1), .uint(3)),
            CBORPair(.uint(2), .bytes([1, 2, 3])),
        ]))
        XCTAssertEqual(mismatches(), 1)
    }

    // A supported, matching exchange raises NO mismatch (no false positives).
    func testSupportedMatchingExchangeNoMismatch() {
        let (coordinator, mismatches) = makeCoordinator()
        coordinator.start(local: iosUwb(), peer: iosUwb(), isInitiator: true)   // offers ni_peer
        coordinator.handleSessionMessage(.rangingAccept, body: .map([
            CBORPair(.uint(1), .uint(1)),
            CBORPair(.uint(2), .uint(RangingMethod.niPeer.rawValue)),
        ]))
        XCTAssertEqual(mismatches(), 0)
    }

    // MARK: - B.2.1: capability re-announcement drift over a real session

    func testReannouncementDriftDisconnects() async throws {
        try await runDrift(reannounced: CapabilitySet(os: .ios, wifiAware: true), expectFatal: true)
    }

    func testIdenticalReannouncementDoesNotDisconnect() async throws {
        try await runDrift(reannounced: CapabilitySet(os: .ios), expectFatal: false)
    }

    /// Drives the responder `SessionRunner` under test against a hand-driven peer
    /// `SecureSession` initiator so a second `capabilities` (msgType 5) can be
    /// injected after the bound `session_ready`.
    private func runDrift(reannounced: CapabilitySet, expectFatal: Bool) async throws {
        let aPriv = Curve25519.KeyAgreement.PrivateKey()
        let bPriv = Curve25519.KeyAgreement.PrivateKey()
        let aPub = Array(aPriv.publicKey.rawRepresentation)
        let bPub = Array(bPriv.publicKey.rawRepresentation)
        let pairId = [UInt8](repeating: 0x33, count: 16)
        let pairRoot = [UInt8](repeating: 0x44, count: 32)
        let sid = [UInt8](repeating: 0x55, count: 16)
        let boundCaps = CapabilitySet(os: .ios)

        let recordB = PairRecord(pairId: pairId, roleCode: 2, peerStaticPub: aPub,
                                 negotiatedVersion: 2, alias: "A", createdAt: 1, revoked: false)
        let transport = ManualTransport(kind: .ble)
        let runner = try SessionRunner(role: .responder, pair: recordB, pairRoot: pairRoot,
                                       localStaticPriv: Array(bPriv.rawRepresentation),
                                       transport: transport, localCaps: CapabilitySet(os: .ios),
                                       ranging: RangingCoordinator(), findDeadline: 0)
        let connected = expectation(description: "connected")
        var fatalReason: ReasonCode?
        let fatal = expectation(description: "fatal")
        fatal.isInverted = !expectFatal
        runner.onConnected = { connected.fulfill() }
        runner.onFatal = { fatalReason = $0; fatal.fulfill() }
        runner.start()

        // Peer = IKpsk2 initiator mirroring the runner's session parameters.
        let prologue = Array("cellfind/v2/session".utf8) + pairId + Array("ble".utf8)
        let peer = try SecureSession(
            role: .initiator, prologue: prologue,
            sessionPsk: Derivations.sessionPsk(pairRoot: pairRoot),
            localStaticPriv: Array(aPriv.rawRepresentation), pinnedPeerStaticPub: bPub,
            ephemeralPriv: Array(Curve25519.KeyAgreement.PrivateKey().rawRepresentation), sid: sid)

        transport.onRecord?(try peer.writeHandshake())      // msg1 → runner emits msg2
        try await Task.sleep(nanoseconds: 40_000_000)
        try peer.readHandshake(transport.sent.removeFirst()) // consume runner's msg2

        // First session_ready binds `boundCaps` to the session.
        transport.onRecord?(try peer.send(msgType: SessionMsgType.sessionReady.rawValue,
                                          body: readyBody(boundCaps)))
        await fulfillment(of: [connected], timeout: 2)
        transport.sent.removeAll()                          // discard runner's own session_ready

        // Second capabilities re-announcement (drift or identical).
        transport.onRecord?(try peer.send(msgType: SessionMsgType.capabilities.rawValue,
                                          body: capsBody(reannounced)))
        await fulfillment(of: [fatal], timeout: expectFatal ? 2 : 0.5)
        if expectFatal { XCTAssertEqual(fatalReason, .capabilityMismatch) }
    }

    private func readyBody(_ caps: CapabilitySet) -> CBOR {
        .map([CBORPair(.uint(1), caps.encoded()),
              CBORPair(.uint(2), .uint(0)),
              CBORPair(.uint(3), .uint(2))])
    }

    private func capsBody(_ caps: CapabilitySet) -> CBOR {
        .map([CBORPair(.uint(1), caps.encoded())])
    }
}

/// A transport whose inbound delivery and captured outbound records are driven
/// directly by a test (no radios).
final class ManualTransport: PeerTransport {
    let kind: TransportKind
    let isAvailable = true
    var onRecord: (([UInt8]) -> Void)?
    var onClosed: ((ReasonCode) -> Void)?
    var sent: [[UInt8]] = []

    init(kind: TransportKind) { self.kind = kind }
    func connect() async -> Result<Void, TransportFailure> { .success(()) }
    func send(record: [UInt8]) throws { sent.append(record) }
    func disconnect(reason: ReasonCode) { onClosed?(reason) }
}
