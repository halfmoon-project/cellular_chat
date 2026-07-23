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
    var onFatal: ((ReasonCode) -> Void)?

    private let role: RunnerRole
    private let pair: PairRecord
    private let localCaps: CapabilitySet
    private let ranging: RangingCoordinator
    private let findDeadline: UInt64

    private var session: SecureSession?
    private weak var transport: PeerTransport?
    private var handshakeDone = false
    private var sentReady = false
    private var recvReady = false

    init(role: RunnerRole, pair: PairRecord, pairRoot: [UInt8], localStaticPriv: [UInt8],
         transport: PeerTransport, localCaps: CapabilitySet, ranging: RangingCoordinator,
         findDeadline: UInt64) throws {
        self.role = role
        self.pair = pair
        self.localCaps = localCaps
        self.ranging = ranging
        self.findDeadline = findDeadline
        self.transport = transport

        let prologue = Array("cellfind/v2/session".utf8) + pair.pairId + transport.kind.transportTag
        let sessionPsk = Derivations.sessionPsk(pairRoot: pairRoot)
        let ephemeral = Array(Curve25519.KeyAgreement.PrivateKey().rawRepresentation)
        var sid: [UInt8]? = nil
        if role == .initiator {
            sid = try secureRandomBytes(count: 16)
        }
        session = try SecureSession(
            role: role == .initiator ? .initiator : .responder,
            prologue: prologue, sessionPsk: sessionPsk,
            localStaticPriv: localStaticPriv, pinnedPeerStaticPub: pair.peerStaticPub,
            ephemeralPriv: ephemeral, sid: sid)

        // Wire ranging outbound messages into the session AEAD. The coordinator
        // owns the monotonic attemptId and stamps it into every ranging body.
        ranging.sendMessage = { [weak self] type, body in self?.send(type, body: body) }
    }

    func start() {
        transport?.onRecord = { [weak self] record in Task { @MainActor in self?.handle(record) } }
        transport?.onClosed = { [weak self] reason in Task { @MainActor in self?.onFatal?(reason) } }
        // Feed live BLE RSSI into the §12 proximity fallback (finding: bleRssi).
        if let ble = transport as? BLETransport {
            ble.onRSSI = { [weak self] rssi in Task { @MainActor in self?.ranging.feedRSSI(rssi) } }
        }
        if role == .initiator { emitHandshake() }   // IKpsk2 message 1
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
        sendSessionReady()
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
                recvReady = true
                onConnected?()
                ranging.start(local: localCaps, peer: peerCaps, isInitiator: role == .initiator)
            }
        case .ping:
            if let n = body.value(forKey: 1)?.asUInt {
                send(.pong, body: .map([CBORPair(.uint(1), .uint(n))]))
            }
        case .rangingOffer, .rangingAccept, .rangingStart, .rangingStop, .rangingError,
             .niToken, .appleConfig, .appleShareable, .oobData:
            // The §8 ranging exchange (offer/accept/start/stop/error) and its
            // material are driven by the coordinator, which owns attemptId (§12).
            ranging.handleSessionMessage(type, body: body)
        case .disconnect:
            let reason = body.value(forKey: 1)?.asUInt.flatMap { ReasonCode(rawValue: $0) } ?? .normal
            onFatal?(reason)
        default:
            break   // transport_upgrade/ack, find_* not routed to the state machine yet
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
}
