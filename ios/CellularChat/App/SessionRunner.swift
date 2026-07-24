import Foundation
import CryptoKit
import CellularChatCore

/// Runs one authenticated Find session (PROTOCOL_V2.md §8) over a connected
/// `PeerTransport`: a fresh IKpsk2 handshake with pinned-peer verification, then
/// the `session_ready` capability exchange, then routing of ranging messages
/// to/from the `RangingCoordinator`. Capabilities and the selected transport are
/// only ever carried inside the session AEAD.
@MainActor
final class SessionRunner {

    enum RunnerRole { case initiator, responder }

    var onAuthenticated: (() -> Void)?
    var onConnected: (() -> Void)?          // both session_ready exchanged
    var onPeerCapabilities: ((CapabilitySet) -> Void)?   // peer's first session_ready
    var onFatal: ((ReasonCode) -> Void)?
    /// Transport-upgrade control messages (§10, Feature A), surfaced to the Find
    /// coordinator which owns the transports, the `sid`, and the peer caps.
    var onTransportUpgrade: ((_ code: UInt64, _ attemptId: UInt64) -> Void)?
    var onTransportAck: ((_ code: UInt64, _ attemptId: UInt64, _ accepted: Bool) -> Void)?

    /// The logical Find session ID (§10): the initiator generates it, the
    /// responder adopts it from the first transport message.
    var sid: [UInt8]? { session?.sid }

    /// The transport this runner speaks over — used by the upgrade driver to know
    /// whether the previous transport is BLE (retained as a control fallback).
    let transportKind: TransportKind

    private let role: RunnerRole
    private let pair: PairRecord
    private let localCaps: CapabilitySet
    private let ranging: RangingCoordinator
    private let findDeadline: UInt64
    /// An upgrade runner reuses the logical `sid` and must NOT own ranging or
    /// (re)start it; ranging stays on the working runner until switchover (§10).
    private let isUpgrade: Bool
    /// For an upgrade runner: the CapabilitySet already bound to `sid`; the K
    /// `session_ready` MUST equal it or transport K aborts (§14).
    private let expectedPeerCaps: CapabilitySet?

    private var session: SecureSession?
    private weak var transport: PeerTransport?
    private var handshakeDone = false
    private var sentReady = false
    private var recvReady = false
    /// Whether this runner currently owns ranging I/O (exactly one runner does).
    private var rangingActive = false
    /// The peer's first bound CapabilitySet (§14, Feature B) for drift detection.
    private var firstPeerCaps: CapabilitySet?

    init(role: RunnerRole, pair: PairRecord, pairRoot: [UInt8], localStaticPriv: [UInt8],
         transport: PeerTransport, localCaps: CapabilitySet, ranging: RangingCoordinator,
         findDeadline: UInt64, upgradeSid: [UInt8]? = nil,
         expectedPeerCaps: CapabilitySet? = nil) throws {
        self.role = role
        self.pair = pair
        self.localCaps = localCaps
        self.ranging = ranging
        self.findDeadline = findDeadline
        self.transport = transport
        self.transportKind = transport.kind
        self.isUpgrade = upgradeSid != nil
        self.expectedPeerCaps = expectedPeerCaps

        let prologue = Array("cellfind/v2/session".utf8) + pair.pairId + transport.kind.transportTag
        let sessionPsk = Derivations.sessionPsk(pairRoot: pairRoot)
        let ephemeral = Array(Curve25519.KeyAgreement.PrivateKey().rawRepresentation)
        // A transport upgrade reuses the existing logical `sid` (§10) on BOTH
        // roles: the initiator seeds it, the responder is pre-initialized with it
        // so the first-message sid check enforces equality. A primary initiator
        // mints a fresh `sid`; a primary responder adopts it (nil here).
        var sid: [UInt8]? = upgradeSid
        if role == .initiator && sid == nil {
            sid = try secureRandomBytes(count: 16)
        }
        session = try SecureSession(
            role: role == .initiator ? .initiator : .responder,
            prologue: prologue, sessionPsk: sessionPsk,
            localStaticPriv: localStaticPriv, pinnedPeerStaticPub: pair.peerStaticPub,
            ephemeralPriv: ephemeral, sid: sid)
    }

    func start() {
        transport?.onRecord = { [weak self] record in Task { @MainActor in self?.handle(record) } }
        transport?.onClosed = { [weak self] reason in Task { @MainActor in self?.onFatal?(reason) } }
        // Feed live BLE RSSI into the §12 proximity fallback (finding: bleRssi),
        // but only while this runner owns ranging (§10 exactly-one-runner rule).
        if let ble = transport as? BLETransport {
            ble.onRSSI = { [weak self] rssi in
                Task { @MainActor in if self?.rangingActive == true { self?.ranging.feedRSSI(rssi) } }
            }
        }
        if role == .initiator { emitHandshake() }   // IKpsk2 message 1
    }

    /// Bind this runner as the active owner of ranging I/O (§10). The primary
    /// runner is activated at its `onConnected`; an upgrade runner at switchover.
    func activateRanging() {
        rangingActive = true
        ranging.sendMessage = { [weak self] type, body in self?.send(type, body: body) }
    }

    /// Release ranging ownership (the other runner activates atomically next).
    func deactivateRanging() {
        rangingActive = false
    }

    // MARK: handshake

    private func emitHandshake() {
        guard let session, let transport else { return }
        do {
            let record = try session.writeHandshake()
            try transport.send(record: record)
            if role == .responder { finishHandshake() }   // responder completes on msg 2
        } catch {
            onFatal?(.protocolError)
        }
    }

    private func handle(_ record: [UInt8]) {
        guard let session else { return }
        if !handshakeDone {
            do {
                try session.readHandshake(record)   // responder verifies pinned key here
                if role == .responder {
                    emitHandshake()                 // msg 2 back to initiator
                } else {
                    finishHandshake()               // initiator completes on msg 2
                }
            } catch {
                // A pinned-key mismatch or bad handshake is an auth failure (§14).
                onFatal?(error is ProtocolError && (error as? ProtocolError) == .pinnedKeyMismatch
                         ? .identityMismatch : .authFailed)
            }
            return
        }
        // Transport phase.
        do {
            switch try session.receive(record) {
            case .ignored:
                break
            case let .message(msgType, body):
                route(msgType: msgType, body: body)
            }
        } catch {
            onFatal?(.protocolError)
        }
    }

    private func finishHandshake() {
        handshakeDone = true
        onAuthenticated?()
        // §8: the initiator's first transport message MUST be session_ready. The
        // responder has no `sid` yet (it adopts the initiator's from the first
        // received message), so it replies only after receiving that message.
        if role == .initiator { sendSessionReady() }
    }

    // MARK: transport messages

    private func sendSessionReady() {
        guard !sentReady else { return }
        let body = CBOR.map([
            CBORPair(.uint(1), localCaps.encoded()),
            CBORPair(.uint(2), .uint(findDeadline)),
            CBORPair(.uint(3), .uint(2)),
        ])
        send(.sessionReady, body: body)
        sentReady = true
    }

    private func route(msgType: UInt64, body: CBOR) {
        guard let type = SessionMsgType(rawValue: msgType) else { return }
        switch type {
        case .sessionReady, .capabilities:
            guard let capsCBOR = body.value(forKey: 1),
                  let peerCaps = try? CapabilitySet.decode(capsCBOR) else {
                onFatal?(.protocolError); return
            }
            if !recvReady {
                // §14 (Feature B): bind the peer's first CapabilitySet to the
                // session. An upgrade runner's K `session_ready` MUST equal the
                // set already bound to this `sid`, else transport K aborts.
                if let expected = expectedPeerCaps, peerCaps != expected {
                    onFatal?(.capabilityMismatch); return
                }
                firstPeerCaps = peerCaps
                recvReady = true
                onPeerCapabilities?(peerCaps)   // learn the peer's platform (§11)
                // §8: the responder replies with its own session_ready (its `sid`
                // is now adopted) before anything else moves.
                if role == .responder { sendSessionReady() }
                onConnected?()
                // An upgrade runner does NOT (re)start ranging: attempt state is
                // preserved across switchover and stays on the working runner (§10).
                if !isUpgrade {
                    ranging.start(local: localCaps, peer: peerCaps, isInitiator: role == .initiator)
                }
            } else if let bound = firstPeerCaps, peerCaps != bound {
                // §14 (B.2.1): a later capabilities/session_ready that differs from
                // the bound set (any of the 14 fields, incl. os) is a mismatch.
                onFatal?(.capabilityMismatch)
            }
        case .ping:
            if let n = body.value(forKey: 1)?.asUInt {
                send(.pong, body: .map([CBORPair(.uint(1), .uint(n))]))
            }
        case .rangingOffer, .rangingAccept, .rangingStart, .rangingStop, .rangingError,
             .niToken, .appleConfig, .appleShareable, .oobData:
            // The §8 ranging exchange (offer/accept/start/stop/error) and its
            // material are driven by the coordinator, which owns attemptId (§12).
            // Only the active runner feeds/serves ranging (§10 exactly-one-runner).
            if rangingActive { ranging.handleSessionMessage(type, body: body) }
        case .transportUpgrade:
            // §10 (Feature A): carried on the working transport; surfaced to the
            // Find coordinator. A malformed body is a §14 teardown of this
            // transport; a well-typed value is answered with transport_ack.
            guard let code = body.value(forKey: 1)?.asUInt,
                  let attemptId = body.value(forKey: 2)?.asUInt else {
                onFatal?(.protocolError); return
            }
            onTransportUpgrade?(code, attemptId)
        case .transportAck:
            guard let code = body.value(forKey: 1)?.asUInt,
                  let attemptId = body.value(forKey: 2)?.asUInt else {
                onFatal?(.protocolError); return
            }
            guard case let .bool(accepted) = body.value(forKey: 3) else {
                onFatal?(.protocolError); return
            }
            onTransportAck?(code, attemptId, accepted)
        case .disconnect:
            let reason = body.value(forKey: 1)?.asUInt.flatMap { ReasonCode(rawValue: $0) } ?? .normal
            onFatal?(reason)
        default:
            break   // find_* not routed to the state machine yet
        }
    }

    private func send(_ type: SessionMsgType, body: CBOR) {
        guard let session, let transport else { return }
        do {
            let record = try session.send(msgType: type.rawValue, body: body)
            try transport.send(record: record)
        } catch {
            onFatal?(.protocolError)
        }
    }

    func sendDisconnect(reason: ReasonCode) {
        send(.disconnect, body: .map([CBORPair(.uint(1), .uint(reason.rawValue))]))
    }

    /// §10 (Feature A) transport-upgrade control messages on the working transport.
    func sendTransportUpgrade(code: UInt64, attemptId: UInt64) {
        send(.transportUpgrade, body: .map([CBORPair(.uint(1), .uint(code)),
                                            CBORPair(.uint(2), .uint(attemptId))]))
    }

    func sendTransportAck(code: UInt64, attemptId: UInt64, accepted: Bool) {
        send(.transportAck, body: .map([CBORPair(.uint(1), .uint(code)),
                                        CBORPair(.uint(2), .uint(attemptId)),
                                        CBORPair(.uint(3), .bool(accepted))]))
    }
}
