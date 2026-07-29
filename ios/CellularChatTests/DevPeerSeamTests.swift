import XCTest
import CryptoKit
import CellularChatCore
@testable import CellularChat

/// The four seams the DEBUG dev peer rides on (`readLocalCaps`, `makeTransports`,
/// `simulatedRanging`, `injectUWB`). The Simulator has no radios, so if any of
/// these stops taking effect the dev loop silently degrades — Find sits in
/// `searching` or `bleRssi` — with nothing failing. This drives a real
/// `FindSessionCoordinator` through a real IKpsk2 handshake over the dev
/// loopback and asserts the whole path still lands on a UWB measurement.
@MainActor
final class DevPeerSeamTests: XCTestCase {

    private final class MemorySecretStore: PairSecretStore {
        private var items: [String: Data] = [:]
        func set(_ data: Data, account: String) throws { items[account] = data }
        func get(account: String) throws -> Data? { items[account] }
        func delete(account: String) { items[account] = nil }
    }

    private static let pairId = [UInt8](repeating: 0xD5, count: 16)
    private static let pairRoot = [UInt8](repeating: 0xE7, count: 32)

    private func devCaps(_ name: String) -> CapabilitySet {
        CapabilitySet(os: .ios, osVersion: "26.0", appVersion: "dev",
                      bleCentral: true, blePeripheral: true,
                      uwbPresent: true, uwbAzimuth: true, niEdm: true, deviceName: name)
    }

    func testDevSeamsCarryASessionToAUwbMeasurement() async throws {
        // The coordinator reads the LOCAL static key from the Keychain
        // (`FindSessionCoordinator.swift:248`), so the peer must pin that one —
        // not a freshly generated key. DevPeer seeds it the same way.
        let keys = DeviceKeyStore()
        let pairId = Self.pairId
        if try keys.staticPublicKey(pairId: pairId) == nil {
            _ = try keys.createStaticKey(pairId: pairId)
        }
        let localPub = try XCTUnwrap(keys.staticPublicKey(pairId: pairId))
        let peerPriv = Curve25519.KeyAgreement.PrivateKey()
        let peerPub = Array(peerPriv.publicKey.rawRepresentation)

        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("devseam-\(UUID().uuidString)")
        let store = PairStore(directory: dir, secrets: MemorySecretStore())
        let local = PairRecord(pairId: Self.pairId, roleCode: 1, peerStaticPub: peerPub,
                               negotiatedVersion: 2, alias: "상대 기기",
                               createdAt: 1, revoked: false)
        try store.commit(local, pairRoot: Self.pairRoot)
        let peer = PairRecord(pairId: Self.pairId, roleCode: 2, peerStaticPub: localPub,
                              negotiatedVersion: 2, alias: "Me", createdAt: 1, revoked: false)

        let find = FindSessionCoordinator(pairStore: store)
        // Seam 1: the Simulator reports no UWB, so ni_peer would never be selected.
        find.readLocalCaps = { [weak self] in self?.devCaps("Simulator") ?? CapabilitySet(os: .ios) }
        // Seam 2: no radios to arbitrate — hand back a loopback to an in-process peer.
        var peerRunner: SessionRunner?
        var peerRanging: RangingCoordinator?
        var peerTransport: DevLoopbackTransport?
        find.makeTransports = { localIsInitiator in
            let a = DevLoopbackTransport(), b = DevLoopbackTransport()
            a.peer = b; b.peer = a
            let ranging = RangingCoordinator()
            ranging.simulatedRanging = true       // seam 3, peer side
            let runner = try! SessionRunner(role: localIsInitiator ? .responder : .initiator,
                                            pair: peer, pairRoot: Self.pairRoot,
                                            localStaticPriv: Array(peerPriv.rawRepresentation),
                                            transport: b, localCaps: self.devCaps("Dev Peer"),
                                            ranging: ranging,
                                            findDeadline: UInt64(Date().timeIntervalSince1970 + 900))
            runner.onConnected = { runner.activateRanging() }
            peerRunner = runner; peerRanging = ranging; peerTransport = b
            runner.start()
            return [a]
        }
        find.ranging.simulatedRanging = true      // seam 3, local side

        find.arm(pair: local, duration: 900)

        // The handshake and capability exchange must settle before ranging starts.
        try await waitUntil("ni_peer selected") { find.ranging.selection?.method == .niPeer }

        // Seam 4: a synthetic sample must travel the same path a real NI update does.
        find.ranging.injectUWB(Measurement(timestamp: Date(), method: .niPeer,
                                           distanceMeters: 2.5,
                                           horizontalAngleRadians: 0.4, proximity: nil))
        // Wait on the STATE, not the measurement: the coordinator reacts to the
        // published sample one main-actor turn later, so asserting on the
        // measurement alone would race the transition it is supposed to drive.
        try await waitUntil("direction shown") { find.state == .directionAvailable }

        XCTAssertEqual(find.ranging.measurement?.distanceMeters, 2.5)
        // §11: the peer's deviceName really is adopted, which is what renames the row.
        XCTAssertEqual(find.peerCaps?.deviceName, "Dev Peer")

        find.stop()
        _ = (peerRunner, peerRanging, peerTransport)   // held for the session's lifetime
    }

    /// Polls the main actor rather than sleeping a fixed interval: the handshake
    /// hops through several `Task { @MainActor }` turns and a fixed sleep would
    /// be either flaky or slow.
    private func waitUntil(_ what: String, timeout: TimeInterval = 5,
                           _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > deadline { XCTFail("timed out waiting for \(what)"); return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }
}
