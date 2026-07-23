import Foundation
import Network
import WiFiAware
import DeviceDiscoveryUI
import CellularChatCore

/// Wi-Fi Aware transport (PROTOCOL_V2.md §4/§8) over the fixed `cellfind`
/// service declared in Info.plist `WiFiAwareServices`. iOS is the subscriber
/// (Noise initiator) in the cross-platform model; the publisher role is the
/// responder. Records are stream-framed as `u32BE(len) || record` (§5) via the
/// core `Records` helpers. Every framework touch is gated on `WACapabilities`.
///
/// SDK reality vs plan: the plan named a "publish/subscribe" API; the shipped
/// iOS 26 surface is `WAPublishableService`/`WASubscribableService` +
/// `WAPublisherListener`/`WASubscriberBrowser` bridged into Network.framework,
/// with system app-to-app pairing done through `DeviceDiscoveryUI`. This uses
/// those real types.
final class WiFiAwareTransport: PeerTransport {
    enum WARole { case subscriber, publisher }

    static let serviceName = "cellfind"

    let kind: TransportKind = .wifiAware
    let role: WARole
    private let connectTimeout: TimeInterval

    var onRecord: (([UInt8]) -> Void)?
    var onClosed: ((ReasonCode) -> Void)?

    private let queue = DispatchQueue(label: "com.cellularchat.aware")
    private var browser: NWBrowser?
    private var listener: NWListener?
    private var connection: NWConnection?
    private var rxBuffer: [UInt8] = []

    private var connectContinuation: CheckedContinuation<Result<Void, TransportFailure>, Never>?

    init(role: WARole, connectTimeout: TimeInterval = 8) {
        self.role = role
        self.connectTimeout = connectTimeout
    }

    /// Runtime gate: the radio must report the feature AND the fixed service must
    /// be declared in Info.plist (otherwise `allServices` has no entry).
    var isAvailable: Bool {
        WACapabilities.supportedFeatures.contains(.wifiAware)
            && WASubscribableService.allServices[Self.serviceName] != nil
    }

    /// Whether the system app-to-app pairing UI can run for our service.
    static func systemPairingSupported() -> Bool {
        guard let svc = WAPublishableService.allServices[serviceName] else { return false }
        let provider = WAPublisherListener.wifiAware(.connecting(to: svc, from: .allPairedDevices))
        return DDDevicePairingViewController.isSupported(provider)
    }

    func connect() async -> Result<Void, TransportFailure> {
        guard isAvailable else { return .failure(.unsupported) }
        return await withCheckedContinuation { cont in
            queue.async { [weak self] in
                guard let self else { cont.resume(returning: .failure(.failed)); return }
                self.connectContinuation = cont
                switch self.role {
                case .subscriber: self.startSubscriber()
                case .publisher: self.startPublisher()
                }
                self.queue.asyncAfter(deadline: .now() + self.connectTimeout) { [weak self] in
                    self?.finishConnect(.failure(.timeout))
                }
            }
        }
    }

    func send(record: [UInt8]) throws {
        let framed = Data(Records.frameForStream(record))
        queue.async { [weak self] in
            self?.connection?.send(content: framed, completion: .contentProcessed { _ in })
        }
    }

    func disconnect(reason: ReasonCode) {
        queue.async { [weak self] in
            guard let self else { return }
            self.connection?.cancel()
            self.browser?.cancel()
            self.listener?.cancel()
            self.onClosed?(reason)
        }
    }

    // MARK: subscriber (initiator)

    private func startSubscriber() {
        guard let svc = WASubscribableService.allServices[Self.serviceName] else {
            finishConnect(.failure(.unsupported)); return
        }
        let provider = WASubscriberBrowser.wifiAware(.connecting(to: .allPairedDevices, from: svc))
        let params = provider.configureParameters(nil)
        let browser = NWBrowser(for: provider.makeDescriptor(), using: params)
        self.browser = browser
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self, let result = results.first else { return }
            self.browser?.cancel()
            self.openConnection(to: result.endpoint, params: params)
        }
        browser.start(queue: queue)
    }

    // MARK: publisher (responder)

    private func startPublisher() {
        guard let svc = WAPublishableService.allServices[Self.serviceName] else {
            finishConnect(.failure(.unsupported)); return
        }
        let provider = WAPublisherListener.wifiAware(.connecting(to: svc, from: .allPairedDevices))
        let params = NWParameters()
        provider.configureParameters(params)
        do {
            let listener = try NWListener(service: provider.service, using: params)
            self.listener = listener
            listener.newConnectionHandler = { [weak self] conn in
                self?.adopt(connection: conn)
            }
            listener.start(queue: queue)
        } catch {
            finishConnect(.failure(.failed))
        }
    }

    // MARK: shared connection handling

    private func openConnection(to endpoint: NWEndpoint, params: NWParameters) {
        let conn = NWConnection(to: endpoint, using: params)
        adopt(connection: conn)
    }

    private func adopt(connection conn: NWConnection) {
        connection = conn
        conn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.finishConnect(.success(()))
                self?.receiveLoop()
            case .failed, .cancelled:
                self?.onClosed?(.transportLost)
            default:
                break
            }
        }
        conn.start(queue: queue)
    }

    private func receiveLoop() {
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 65540) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty {
                self.rxBuffer.append(contentsOf: data)
                do {
                    let (records, consumed) = try Records.readStream(self.rxBuffer)
                    if consumed > 0 { self.rxBuffer.removeFirst(consumed) }
                    for record in records { self.onRecord?(record) }
                } catch {
                    self.onClosed?(.protocolError)   // §5 stream length violation is fatal
                    return
                }
            }
            if let error {
                _ = error
                self.onClosed?(.transportLost)
                return
            }
            if isComplete { self.onClosed?(.transportLost); return }
            self.receiveLoop()
        }
    }

    private func finishConnect(_ result: Result<Void, TransportFailure>) {
        guard let cont = connectContinuation else { return }
        connectContinuation = nil
        cont.resume(returning: result)
    }
}
