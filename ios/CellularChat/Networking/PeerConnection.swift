import Foundation
import Network

final class PeerConnection: Identifiable {
    enum Role {
        case client
        case server
    }

    enum State {
        case setup
        case ready
        case failed(Error)
        case cancelled
    }

    let id = UUID()
    let connection: NWConnection
    let role: Role
    let expectedRemoteDeviceID: String?
    var clientNonce: String

    var remoteDeviceID: String?
    var remoteDisplayName: String?
    var remotePlatform: String?
    var serverNonce: String?
    var isAuthenticated = false

    var onStateChange: ((PeerConnection, State) -> Void)?
    var onMessage: ((PeerConnection, WireMessage) -> Void)?

    private let decoder = FrameDecoder()
    private var stopped = false

    init(
        connection: NWConnection,
        role: Role,
        expectedRemoteDeviceID: String? = nil,
        clientNonce: String = ConnectionIdentity.randomNonce()
    ) {
        self.connection = connection
        self.role = role
        self.expectedRemoteDeviceID = expectedRemoteDeviceID
        self.clientNonce = clientNonce
    }

    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.onStateChange?(self, .ready)
                self.receiveNext()
            case .failed(let error), .waiting(let error):
                self.onStateChange?(self, .failed(error))
                if case .failed = state { self.cancel() }
            case .cancelled:
                self.onStateChange?(self, .cancelled)
            default:
                break
            }
        }
        connection.start(queue: .main)
    }

    func send(_ message: WireMessage, completion: (() -> Void)? = nil) {
        guard !stopped else { return }
        do {
            let data = try FrameCodec.encode(message)
            connection.send(content: data, completion: .contentProcessed { [weak self] error in
                DispatchQueue.main.async {
                    if let error, let self {
                        self.onStateChange?(self, .failed(error))
                        self.cancel()
                    } else {
                        completion?()
                    }
                }
            })
        } catch {
            onStateChange?(self, .failed(error))
            cancel()
        }
    }

    func cancel() {
        guard !stopped else { return }
        stopped = true
        connection.cancel()
    }

    private func receiveNext() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { [weak self] data, _, isComplete, error in
            guard let self, !self.stopped else { return }

            if let data, !data.isEmpty {
                do {
                    for message in try self.decoder.append(data) {
                        self.onMessage?(self, message)
                    }
                } catch {
                    self.onStateChange?(self, .failed(error))
                    self.cancel()
                    return
                }
            }

            if let error {
                self.onStateChange?(self, .failed(error))
                self.cancel()
            } else if isComplete {
                self.cancel()
            } else {
                self.receiveNext()
            }
        }
    }
}
