import Foundation
import Combine
import CoreImage.CIFilterBuiltins
import UIKit
import CryptoKit
import CellularChatCore

/// Drives the core `PairingProtocol` (NNpsk0 + bind/proof/complete, §6) over a
/// `PeerTransport`. The inviter (role A) generates a single-use invitation and
/// runs the responder role; the joiner (role B) scans/pastes it and runs the
/// initiator role. Both display the same fingerprint (§2) before committing.
@MainActor
final class PairingCoordinator: ObservableObject {

    enum Step: Equatable {
        case idle, connecting, handshaking, binding, proving
        case awaitingFingerprint, completing, done
        case failed(String)
    }

    @Published private(set) var step: Step = .idle
    @Published private(set) var fingerprint: String?
    @Published private(set) var invitationText: String?
    @Published private(set) var qrImage: UIImage?

    private let pairStore: PairStore
    private let deviceKeys = DeviceKeyStore()

    private var proto: PairingProtocol?
    private var transport: PeerTransport?
    private var role: PairRole = .a
    private var pairId: [UInt8] = []
    private var invitation: Invitation?
    private var alias = "상대 기기"

    init(pairStore: PairStore) {
        self.pairStore = pairStore
    }

    // MARK: Inviter (role A)

    /// Create a fresh single-use invitation and render its QR + copyable string.
    /// Returns nil (and fails the step) if the CSPRNG cannot produce key material.
    func makeInvitation() -> Invitation? {
        guard let pairId = try? secureRandomBytes(count: 16),
              let secret = try? secureRandomBytes(count: 32) else {
            step = .failed("보안 난수를 생성할 수 없습니다.")
            return nil
        }
        let invite = Invitation(pairId: pairId, secret: secret,
                                createdAt: UInt64(Date().timeIntervalSince1970))
        self.invitation = invite
        self.pairId = pairId
        self.invitationText = invite.text()
        self.qrImage = Self.qr(from: invite.text())
        return invite
    }

    func startAsInviter(over transport: PeerTransport, alias: String) {
        guard let invitation else { step = .failed("초대장이 없습니다."); return }
        begin(role: .a, invitation: invitation, transport: transport, alias: alias)
    }

    // MARK: Joiner (role B)

    /// Parse and validate a scanned/pasted invitation before starting (§4).
    func startAsJoiner(inviteText: String, over transport: PeerTransport, alias: String) {
        let now = UInt64(Date().timeIntervalSince1970)
        guard let invitation = try? Invitation.parse(text: inviteText, nowUnixSeconds: now) else {
            step = .failed("유효하지 않거나 만료된 초대장입니다.")
            return
        }
        self.invitation = invitation
        begin(role: .b, invitation: invitation, transport: transport, alias: alias)
    }

    // MARK: shared driver

    private func begin(role: PairRole, invitation: Invitation, transport: PeerTransport, alias: String) {
        self.role = role
        self.pairId = invitation.pairId
        self.alias = alias
        self.transport = transport
        step = .connecting

        let pairingPsk = Derivations.pairingPsk(secret: invitation.secret)
        let prologue = Array("cellfind/v2/pairing".utf8) + invitation.pairId
        guard let staticPub = try? deviceKeys.createStaticKey(pairId: invitation.pairId).publicKey.rawRepresentation else {
            step = .failed("키를 생성할 수 없습니다."); return
        }
        let ephemeral = Curve25519.KeyAgreement.PrivateKey().rawRepresentation
        do {
            proto = try PairingProtocol(role: role, pairId: invitation.pairId, prologue: prologue,
                                        pairingPsk: pairingPsk, ephemeralPriv: Array(ephemeral),
                                        localStaticPub: Array(staticPub))
        } catch {
            step = .failed("페어링을 시작할 수 없습니다."); return
        }

        transport.onRecord = { [weak self] record in Task { @MainActor in self?.handle(record) } }
        transport.onClosed = { [weak self] reason in Task { @MainActor in self?.fail(reason.userText) } }

        Task { [weak self] in
            guard let self else { return }
            let result = await transport.connect()
            await MainActor.run {
                guard case .success = result else { self.fail("연결에 실패했습니다."); return }
                self.step = .handshaking
                if role == .b { self.emit { try $0.writeHandshake() } }   // B sends msg 1
            }
        }
    }

    private func handle(_ record: [UInt8]) {
        guard let proto else { return }
        do {
            switch (role, step) {
            case (.a, .handshaking):
                try proto.readHandshake(record)          // msg 1 from B
                emit { try $0.writeHandshake() }         // msg 2 to B
                step = .binding

            case (.b, .handshaking):
                try proto.readHandshake(record)          // msg 2 from A
                emit { try $0.sendPairBind() }           // B's bind
                step = .binding

            case (.a, .binding):
                try proto.receivePairBind(record)        // B's bind
                emit { try $0.sendPairBind() }           // A's bind
                try proto.stagePairRoot()
                step = .proving

            case (.b, .binding):
                try proto.receivePairBind(record)        // A's bind
                try proto.stagePairRoot()
                emit { try $0.sendPairProof() }          // B's proof
                step = .proving

            case (.a, .proving):
                try proto.receivePairProof(record)       // verify B
                emit { try $0.sendPairProof() }          // A's proof
                fingerprint = try proto.fingerprintDisplay()
                step = .awaitingFingerprint

            case (.b, .proving):
                try proto.receivePairProof(record)       // verify A
                fingerprint = try proto.fingerprintDisplay()
                step = .awaitingFingerprint

            case (_, .completing):
                try proto.receivePairComplete(record)
                try commitIfReady()

            default:
                break
            }
        } catch {
            // Abort without revealing which field mismatched (§6).
            if let abort = try? proto.sendPairAbort(reason: .authFailed) {
                try? transport?.send(record: abort)
            }
            fail("페어링에 실패했습니다.")
        }
    }

    /// The user confirmed the fingerprint matches on both screens (§6 step 4).
    func confirmFingerprint() {
        guard step == .awaitingFingerprint else { return }
        step = .completing
        emit { try $0.sendPairComplete() }
        try? commitIfReady()
    }

    func cancel(reason: ReasonCode = .userStopped) {
        if let record = try? proto?.sendPairAbort(reason: reason) { try? transport?.send(record: record) }
        transport?.disconnect(reason: reason)
        step = .idle
    }

    private func commitIfReady() throws {
        guard let proto, proto.isCommitted,
              let root = proto.pairRoot,
              let peerStatic = proto.peerStaticKey,
              let version = proto.negotiatedVersion else { return }
        let record = PairRecord(pairId: pairId, roleCode: role == .a ? 1 : 2,
                                peerStaticPub: peerStatic, negotiatedVersion: version,
                                alias: alias, createdAt: UInt64(Date().timeIntervalSince1970),
                                revoked: false)
        try pairStore.commit(record, pairRoot: root)
        // Single-use invitation is now consumed; ephemeral state is dropped.
        invitation = nil
        step = .done
    }

    // MARK: helpers

    private func emit(_ make: (PairingProtocol) throws -> [UInt8]) {
        guard let proto, let transport else { return }
        do {
            let record = try make(proto)
            try transport.send(record: record)
        } catch {
            fail("전송에 실패했습니다.")
        }
    }

    private func fail(_ message: String) {
        if case .done = step { return }
        step = .failed(message)
    }

    static func qr(from string: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let context = CIContext()
        guard let cg = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
