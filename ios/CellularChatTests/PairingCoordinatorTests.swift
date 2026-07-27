import XCTest
import CellularChatCore
@testable import CellularChat

/// In-memory pair-root storage so store logic runs without the Keychain.
private final class MemorySecretStore: PairSecretStore {
    private var items: [String: Data] = [:]
    func set(_ data: Data, account: String) throws { items[account] = data }
    func get(account: String) throws -> Data? { items[account] }
    func delete(account: String) { items[account] = nil }
}

/// A transport that must never be touched: the reuse guard rejects before connect.
private final class UnusedTransport: PeerTransport {
    let kind: TransportKind = .ble
    let isAvailable = true
    var onRecord: (([UInt8]) -> Void)?
    var onClosed: ((ReasonCode) -> Void)?
    func connect() async -> Result<Void, TransportFailure> {
        XCTFail("guard should reject before connecting")
        return .failure(.failed)
    }
    func send(record: [UInt8]) throws { XCTFail("guard should reject before sending") }
    func disconnect(reason: ReasonCode) {}
}

/// Two transports wired to each other, so a full pairing runs in-process.
private final class LoopbackTransport: PeerTransport {
    let kind: TransportKind = .ble
    let isAvailable = true
    var onRecord: (([UInt8]) -> Void)?
    var onClosed: ((ReasonCode) -> Void)?
    weak var peer: LoopbackTransport?
    func connect() async -> Result<Void, TransportFailure> { .success(()) }
    func send(record: [UInt8]) throws { peer?.onRecord?(record) }
    func disconnect(reason: ReasonCode) {}
}

/// Invitation reuse guard (Work Package A item 1): a pairId already committed in
/// the store cannot start a new pairing (mirrors Android §4 rejection).
final class PairingCoordinatorTests: XCTestCase {

    /// §6 step 4: pairing is mutual — one session registers *both* sides. The
    /// side that confirms second used to drop the peer's already-sent
    /// pair_complete and never commit, leaving the pair one-sided.
    @MainActor
    func testBothSidesCommitWhateverTheConfirmOrder() throws {
        let inviterStore = makeStore(), joinerStore = makeStore()
        let inviter = PairingCoordinator(pairStore: inviterStore)
        let joiner = PairingCoordinator(pairStore: joinerStore)
        let inviterLink = LoopbackTransport(), joinerLink = LoopbackTransport()
        inviterLink.peer = joinerLink
        joinerLink.peer = inviterLink

        let invite = try XCTUnwrap(inviter.makeInvitation())
        inviter.startAsInviter(over: inviterLink, alias: "joiner")
        joiner.startAsJoiner(inviteText: invite.text(), over: joinerLink, alias: "inviter")
        settle()

        XCTAssertEqual(inviter.step, .awaitingFingerprint)
        XCTAssertEqual(joiner.step, .awaitingFingerprint)
        XCTAssertEqual(inviter.fingerprint, joiner.fingerprint)

        joiner.confirmFingerprint()      // joiner first…
        settle()
        inviter.confirmFingerprint()     // …inviter second, the side that broke
        settle()

        XCTAssertNotNil(inviterStore.record(forPairId: invite.pairId))
        XCTAssertNotNil(joinerStore.record(forPairId: invite.pairId))
    }

    private func makeStore() -> PairStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pair-\(UUID().uuidString)")
        return PairStore(directory: dir, secrets: MemorySecretStore())
    }

    /// Records hop between the coordinators through @MainActor tasks; pump the
    /// main run loop so those hops actually run.
    @MainActor
    private func settle() {
        RunLoop.current.run(until: Date().addingTimeInterval(0.2))
    }

    @MainActor
    func testJoinerRejectsAlreadyConsumedInvitation() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pair-reuse-\(UUID().uuidString)")
        let store = PairStore(directory: dir, secrets: MemorySecretStore())
        let pairId = [UInt8](repeating: 7, count: 16)
        // A committed record for this pairId means the invitation was consumed.
        try store.commit(
            PairRecord(pairId: pairId, roleCode: 2,
                       peerStaticPub: [UInt8](repeating: 2, count: 32),
                       negotiatedVersion: 2, alias: "old", createdAt: 1, revoked: false),
            pairRoot: [UInt8](repeating: 3, count: 32))

        // A fresh, in-window invitation carrying that same pairId.
        let now = UInt64(Date().timeIntervalSince1970)
        let invite = Invitation(pairId: pairId, secret: [UInt8](repeating: 9, count: 32), createdAt: now)

        let coordinator = PairingCoordinator(pairStore: store)
        coordinator.startAsJoiner(inviteText: invite.text(), over: UnusedTransport(), alias: "me")

        XCTAssertEqual(coordinator.step, .failed("이미 사용된 초대입니다."))
    }
}
