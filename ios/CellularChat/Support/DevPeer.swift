#if DEBUG
import Foundation
import CryptoKit
import CellularChatCore

/// Simulator dev peer: a second, in-process Find session that plays the other
/// phone, so the whole §8/§10/§12 flow — discovery, IKpsk2 handshake, capability
/// negotiation, ranging selection, measurement stream, signal loss, recovery,
/// teardown — runs on one Simulator with no radios at all.
///
/// Activated with the `CC_DEV_PEER` environment variable; absent, nothing here
/// is ever constructed and the app behaves exactly as it does in production.
///   Xcode:  Scheme ▸ Run ▸ Arguments ▸ Environment Variables ▸ CC_DEV_PEER = 1
///   CLI:    SIMCTL_CHILD_CC_DEV_PEER=1 xcrun simctl launch --console booted com.cellularchat.app
@MainActor
final class DevPeer {

    /// Fixed dev identity, so the seeded pair is stable across launches.
    private static let pairId = [UInt8](repeating: 0xD5, count: 16)
    private static let pairRoot = [UInt8](repeating: 0xE7, count: 32)
    private static let peerSeed = [UInt8](repeating: 0x5A, count: 32)

    private let find: FindSessionCoordinator
    private let peerRecord: PairRecord
    private let peerStaticPriv: [UInt8]
    private let peerCaps: CapabilitySet

    // The peer half of the loopback is owned by NOBODY else: `SessionRunner`
    // holds its transport weakly and the coordinator only retains the LOCAL
    // transport, so all three of these must be strong or the peer silently dies
    // mid-handshake and Find sits in `authenticating` forever.
    private var localTransport: DevLoopbackTransport?
    private var peerTransport: DevLoopbackTransport?
    private var peerRunner: SessionRunner?
    private var peerRanging: RangingCoordinator?
    private var script: Task<Void, Never>?

    /// Installs the dev peer when `CC_DEV_PEER` is set; returns nil otherwise.
    static func install(find: FindSessionCoordinator, pairStore: PairStore) -> DevPeer? {
        guard ProcessInfo.processInfo.environment["CC_DEV_PEER"] != nil else { return nil }
        return DevPeer(find: find, pairStore: pairStore)
    }

    private init(find: FindSessionCoordinator, pairStore: PairStore) {
        self.find = find

        let peerPriv = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data(Self.peerSeed))
        self.peerStaticPriv = Array(peerPriv.rawRepresentation)
        let peerPub = Array(peerPriv.publicKey.rawRepresentation)

        // Seed the pair once: local static key in the Keychain, peer key from the
        // fixed seed, pairRoot committed to the store.
        if pairStore.record(forPairId: Self.pairId) == nil {
            try! DeviceKeyStore().createStaticKey(pairId: Self.pairId)
            // Seeded with the untouched default alias on purpose: the peer's §11
            // `deviceName` then really adopts, and the row renames itself in the UI.
            let record = PairRecord(pairId: Self.pairId, roleCode: 1, peerStaticPub: peerPub,
                                    negotiatedVersion: 2, alias: PairRecord.defaultAlias,
                                    createdAt: UInt64(Date().timeIntervalSince1970), revoked: false)
            try! pairStore.commit(record, pairRoot: Self.pairRoot)
        }
        // A Keychain miss here would surface downstream as a bare `authFailed`
        // with nothing to debug, so fail loudly at seed time instead.
        guard let localPub = try? DeviceKeyStore().staticPublicKey(pairId: Self.pairId),
              (try? pairStore.pairRoot(pairStore.record(forPairId: Self.pairId)!)) == Self.pairRoot else {
            fatalError("DevPeer: pair seeding failed (Keychain?)")
        }

        // The peer's view of the pair: opposite role, our key pinned.
        self.peerRecord = PairRecord(pairId: Self.pairId, roleCode: 2, peerStaticPub: localPub,
                                     negotiatedVersion: 2, alias: "Me",
                                     createdAt: UInt64(Date().timeIntervalSince1970), revoked: false)
        self.peerCaps = CapabilitySet(os: .ios, osVersion: "26.0", appVersion: "dev",
                                      bleCentral: true, blePeripheral: true,
                                      uwbPresent: true, uwbAzimuth: true, niEdm: true,
                                      deviceName: "Dev Peer")

        // The Simulator has no radios, so both the local CapabilitySet and the
        // transport candidates have to come from here.
        find.readLocalCaps = { CapabilitySet(os: .ios, osVersion: "26.0", appVersion: "dev",
                                             bleCentral: true, blePeripheral: true,
                                             uwbPresent: true, uwbAzimuth: true, niEdm: true,
                                             deviceName: "Simulator") }
        find.ranging.simulatedRanging = true
        find.makeTransports = { [weak self] isInitiator in
            guard let self else { return [] }
            return [self.openPeerSide(localIsInitiator: isInitiator)]
        }
    }

    /// One discovery pass: a fresh loopback pair plus a fresh peer session. Every
    /// §10 retry calls this, so the previous pass is dropped first.
    private func openPeerSide(localIsInitiator: Bool) -> PeerTransport {
        teardownPeer()
        let local = DevLoopbackTransport()
        let peer = DevLoopbackTransport()
        local.peer = peer
        peer.peer = local

        let ranging = RangingCoordinator()
        ranging.simulatedRanging = true
        let runner = try! SessionRunner(role: localIsInitiator ? .responder : .initiator,
                                        pair: peerRecord, pairRoot: Self.pairRoot,
                                        localStaticPriv: peerStaticPriv, transport: peer,
                                        localCaps: peerCaps, ranging: ranging,
                                        findDeadline: UInt64(Date().timeIntervalSince1970 + 900))
        runner.onConnected = { [weak self] in
            self?.peerRunner?.activateRanging()
            self?.startScript()
        }
        // A local `stop()`/expiry closes the loopback from the other end, so the
        // peer session has to go with it or it outlives the session it served.
        runner.onFatal = { [weak self] _ in self?.teardownPeer() }

        localTransport = local
        peerTransport = peer
        peerRunner = runner
        peerRanging = ranging
        runner.start()
        return local
    }

    private func teardownPeer() {
        script?.cancel()
        script = nil
        peerRanging?.stop()
        peerRunner = nil
        peerRanging = nil
        peerTransport = nil
        localTransport = nil
    }

    /// The scenario. Everything a developer wants to watch, on a 30-second loop:
    /// an RSSI ramp that walks the proximity bands, a UWB stream that closes the
    /// distance and sweeps the arrow, then a transport drop into §10 recovery.
    private func startScript() {
        script?.cancel()
        script = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 400_000_000)   // let ranging.start() land
            // 1. RSSI ramp: -90 → -45 dBm walks far → near → veryNear.
            for step in 0..<24 {
                guard !Task.isCancelled else { return }
                self?.find.ranging.feedRSSI(-90 + Double(step) * 2)
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
            // 2. UWB stream: distance closes, azimuth sweeps ±45°.
            for step in 0..<40 {
                guard !Task.isCancelled else { return }
                let t = Double(step)
                self?.find.ranging.injectUWB(Measurement(
                    timestamp: Date(), method: .niPeer,
                    distanceMeters: max(0.3, 8.0 - t * 0.18),
                    horizontalAngleRadians: sin(t / 6) * .pi / 4,
                    proximity: nil))
                try? await Task.sleep(nanoseconds: 200_000_000)
            }
            // 3. UWB sample lost: RSSI fallback + bounded UWB retry (§12).
            self?.find.ranging.injectUWB(nil)
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            // 4. Transport drop: signalLost → retryWait → searching → re-handshake.
            self?.peerTransport?.disconnect(reason: .transportLost)
        }
    }
}

/// Whole-record loopback (the same shape as `SessionRunnerLoopbackTests`): each
/// side's `send` is the other side's `onRecord`. `peer` is weak on both sides —
/// `DevPeer` owns one end and `FindSessionCoordinator` the other.
final class DevLoopbackTransport: PeerTransport {
    let kind: TransportKind = .ble
    let isAvailable = true
    /// Records that arrived before this side had a runner attached. The two
    /// SessionRunners are wired at different points in the arm sequence, so
    /// without this the peer's handshake message 1 is dropped and Find sits in
    /// `authenticating` forever.
    private var pending: [[UInt8]] = []
    var onRecord: (([UInt8]) -> Void)? {
        didSet {
            guard let onRecord, !pending.isEmpty else { return }
            let queued = pending
            pending = []
            queued.forEach(onRecord)
        }
    }
    var onClosed: ((ReasonCode) -> Void)?
    weak var peer: DevLoopbackTransport?

    func connect() async -> Result<Void, TransportFailure> { .success(()) }

    func send(record: [UInt8]) throws {
        let peer = self.peer
        Task { @MainActor in peer?.deliver(record) }
    }

    private func deliver(_ record: [UInt8]) {
        if let onRecord { onRecord(record) } else { pending.append(record) }
    }

    func disconnect(reason: ReasonCode) {
        let peer = self.peer
        onClosed?(reason)
        Task { @MainActor in peer?.onClosed?(reason) }
    }
}
#endif
