import XCTest
import CryptoKit
import CellularChatCore
@testable import CellularChat

/// In-memory pair-root storage so store logic runs without the Keychain.
private final class MemorySecretStore: PairSecretStore {
    private var items: [String: Data] = [:]
    func set(_ data: Data, account: String) throws { items[account] = data }
    func get(account: String) throws -> Data? { items[account] }
    func delete(account: String) { items[account] = nil }
}

/// Delivers each sent record to its paired transport's `onRecord` (async, on the
/// main actor) so two `SessionRunner`s can complete a real IKpsk2 handshake and
/// session_ready exchange without radios.
private final class LoopbackTransport: PeerTransport {
    let kind: TransportKind = .ble
    let isAvailable = true
    var onRecord: (([UInt8]) -> Void)?
    var onClosed: ((ReasonCode) -> Void)?
    weak var peer: LoopbackTransport?

    func connect() async -> Result<Void, TransportFailure> { .success(()) }
    func send(record: [UInt8]) throws {
        let peer = self.peer
        Task { @MainActor in peer?.onRecord?(record) }
    }
    func disconnect(reason: ReasonCode) { onClosed?(reason) }
}

/// Initiator↔responder session flow (Work Package B item 2): the same-platform
/// responder role (added for role arbitration) completes the §8 handshake and
/// session_ready exchange, and each side persists the peer's platform (§11) so a
/// later Find arm can derive the same-platform transport roles (§9/§10).
final class SessionRunnerLoopbackTests: XCTestCase {

    @MainActor
    func testInitiatorAndResponderExchangeReadyAndLearnPlatform() async throws {
        // Two pair-specific static keys; each side pins the other's public key.
        let aPriv = Curve25519.KeyAgreement.PrivateKey()
        let bPriv = Curve25519.KeyAgreement.PrivateKey()
        let aPub = Array(aPriv.publicKey.rawRepresentation)
        let bPub = Array(bPriv.publicKey.rawRepresentation)
        let pairId = [UInt8](repeating: 0x11, count: 16)
        let pairRoot = [UInt8](repeating: 0x22, count: 32)

        let recordA = PairRecord(pairId: pairId, roleCode: 1, peerStaticPub: bPub,
                                 negotiatedVersion: 2, alias: "B", createdAt: 1, revoked: false)
        let recordB = PairRecord(pairId: pairId, roleCode: 2, peerStaticPub: aPub,
                                 negotiatedVersion: 2, alias: "A", createdAt: 1, revoked: false)

        // Only B has a store: it should persist A's platform on the exchange.
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("runner-\(UUID().uuidString)")
        let storeB = PairStore(directory: dir, secrets: MemorySecretStore())
        try storeB.commit(recordB, pairRoot: pairRoot)

        let loopA = LoopbackTransport()
        let loopB = LoopbackTransport()
        loopA.peer = loopB
        loopB.peer = loopA

        let capsA = CapabilitySet(os: .ios)
        let capsB = CapabilitySet(os: .ios)

        let runnerA = try SessionRunner(role: .initiator, pair: recordA, pairRoot: pairRoot,
                                        localStaticPriv: Array(aPriv.rawRepresentation),
                                        transport: loopA, localCaps: capsA,
                                        ranging: RangingCoordinator(), findDeadline: 0)
        let runnerB = try SessionRunner(role: .responder, pair: recordB, pairRoot: pairRoot,
                                        localStaticPriv: Array(bPriv.rawRepresentation),
                                        transport: loopB, localCaps: capsB,
                                        ranging: RangingCoordinator(), findDeadline: 0)

        let connectedA = expectation(description: "A connected")
        let connectedB = expectation(description: "B connected")
        runnerA.onConnected = { connectedA.fulfill() }
        runnerB.onConnected = { connectedB.fulfill() }
        runnerB.onPeerCapabilities = { storeB.setPeerPlatform($0.os, pairId: pairId) }

        runnerB.start()   // responder waits for the initiator's message 1
        runnerA.start()   // initiator drives the handshake

        await fulfillment(of: [connectedA, connectedB], timeout: 2)
        // B learned and persisted A's platform (§11) → same-platform on the next arm.
        XCTAssertEqual(storeB.record(forPairId: pairId)?.peerPlatform, .ios)
    }
}
