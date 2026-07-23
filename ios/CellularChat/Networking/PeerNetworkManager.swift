import CryptoKit
import Foundation
import Network
import UniformTypeIdentifiers

@MainActor
final class PeerNetworkManager: ObservableObject {
    static let serviceType = "_cellchat._tcp"
    static let maximumFileSize = 100 * 1_024 * 1_024
    static let chunkSize = 49_152
    nonisolated static let maximumChatPayloadBytes = 8_000

    private static let maximumPendingInboundConnections = 16
    private static let handshakeTimeoutNanoseconds: UInt64 = 15_000_000_000
    private static let maximumConcurrentOutgoingTransfers = 1

    @Published private(set) var isRunning = false
    @Published private(set) var statusText = "연결 ID를 입력해 시작하세요."
    @Published private(set) var peers: [ConnectedPeer] = []
    @Published private(set) var messages: [ChatMessage] = []
    @Published private(set) var pendingFileOffers: [FileOffer] = []
    @Published private(set) var receivedFiles: [ReceivedFile] = []
    @Published var errorMessage: String?

    let deviceID: String
    let ranging = NearbyInteractionManager()

    private var displayName = ""
    private var identity: ConnectionIdentity?
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var allConnections: [UUID: PeerConnection] = [:]
    private var connectionsByPeer: [String: PeerConnection] = [:]
    private var outgoingTransfers: [String: OutgoingTransfer] = [:]
    private var incomingTransfers: [String: IncomingTransfer] = [:]

    init(deviceID: String = DeviceIdentity.persistentID()) {
        self.deviceID = deviceID
        ranging.onSendMessage = { [weak self] peerID, message in
            self?.connectionsByPeer[peerID]?.send(message)
        }
    }

    func start(displayName: String, connectionID: String) {
        let cleanName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanName.isEmpty else {
            errorMessage = "표시 이름을 입력하세요."
            return
        }

        do {
            let newIdentity = try ConnectionIdentity(connectionID: connectionID)
            stop()
            self.displayName = String(cleanName.prefix(40))
            identity = newIdentity
            try startListener(identity: newIdentity)
            startBrowser(identity: newIdentity)
            isRunning = true
            statusText = "같은 연결 ID를 사용하는 기기를 찾는 중…"
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func stop() {
        listener?.cancel()
        browser?.cancel()
        listener = nil
        browser = nil
        allConnections.values.forEach { $0.cancel() }
        allConnections.removeAll()
        connectionsByPeer.removeAll()
        peers.removeAll()
        messages.removeAll()
        pendingFileOffers.removeAll()
        outgoingTransfers.removeAll()
        incomingTransfers.removeAll()
        ranging.stop()
        identity = nil
        isRunning = false
        statusText = "연결이 중지되었습니다."
    }

    func sendChat(_ text: String) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        let payload = Self.chatTextPrefix(clean)
        guard !payload.isEmpty else {
            errorMessage = "메시지의 첫 문자가 전송 한도를 초과합니다."
            return
        }

        let id = UUID().uuidString.lowercased()
        let now = Date()
        var message = WireMessage(type: .chat)
        message.messageId = id
        message.senderId = deviceID
        message.senderName = displayName
        message.timestamp = Int64(now.timeIntervalSince1970 * 1_000)
        message.text = payload

        authenticatedConnections.forEach { $0.send(message) }
        messages.append(ChatMessage(
            id: id,
            senderID: deviceID,
            senderName: displayName,
            timestamp: now,
            text: payload,
            isLocal: true
        ))
    }

    func offerFile(at url: URL) {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }

        do {
            guard outgoingTransfers.count < Self.maximumConcurrentOutgoingTransfers else {
                throw FileTransferError.outgoingTransferInProgress
            }
            let recipients = authenticatedConnections.compactMap { peer -> (id: String, peer: PeerConnection)? in
                guard let peerID = peer.remoteDeviceID else { return nil }
                return (peerID, peer)
            }
            guard !recipients.isEmpty else {
                throw FileTransferError.noPeers
            }
            let data = try Data(contentsOf: url, options: [.mappedIfSafe])
            guard data.count <= Self.maximumFileSize else {
                throw FileTransferError.fileTooLarge
            }

            let transferID = UUID().uuidString.lowercased()
            let digest = Data(SHA256.hash(data: data)).hexString
            let name = Self.safeFileName(url.lastPathComponent)
            let recipientStates = Dictionary(uniqueKeysWithValues: recipients.map {
                ($0.id, OutgoingRecipientState.awaitingResponse)
            })
            outgoingTransfers[transferID] = OutgoingTransfer(
                name: name,
                data: data,
                sha256: digest,
                recipients: recipientStates
            )

            var offer = WireMessage(type: .fileOffer)
            offer.transferId = transferID
            offer.name = name
            offer.size = data.count
            offer.sha256 = digest
            recipients.forEach { $0.peer.send(offer) }
            statusText = "\(name) 전송 수락을 기다리는 중…"
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func respond(to offer: FileOffer, accepted: Bool) {
        pendingFileOffers.removeAll { $0.id == offer.id }
        guard let peer = connectionsByPeer[offer.peerID], peer.isAuthenticated else { return }

        var response = WireMessage(type: .fileAccept)
        response.transferId = offer.id
        response.accepted = accepted
        peer.send(response)

        guard accepted else { return }
        incomingTransfers[offer.id] = IncomingTransfer(
            peerID: offer.peerID,
            senderName: offer.peerName,
            name: Self.safeFileName(offer.name),
            size: offer.size,
            sha256: offer.sha256,
            nextIndex: 0,
            data: Data()
        )
        statusText = "\(offer.name) 받는 중…"
    }

    func startFinding(peerID: String) {
        guard let peer = peers.first(where: { $0.id == peerID }) else { return }
        let previousPeerID = ranging.activePeerID
        if let previousPeerID, previousPeerID != peerID {
            connectionsByPeer[previousPeerID]?.send(WireMessage(type: .rangingStop))
        }
        ranging.startFinding(
            peerID: peer.id,
            peerPlatform: peer.platform,
            capabilities: peer.rangingCapabilities
        ) { [weak self] in
            self?.connectionsByPeer[peerID]?.send(WireMessage(type: .rangingStart))
        }
    }

    func stopFinding() {
        if let peerID = ranging.activePeerID {
            connectionsByPeer[peerID]?.send(WireMessage(type: .rangingStop))
        }
        ranging.stop()
    }

    private var authenticatedConnections: [PeerConnection] {
        connectionsByPeer.values.filter(\.isAuthenticated)
    }

    private func startListener(identity: ConnectionIdentity) throws {
        let parameters = Self.parameters()
        let listener = try NWListener(using: parameters, on: .any)
        let serviceName = "cc1-\(identity.roomHash.prefix(12))-\(deviceID.replacingOccurrences(of: "-", with: ""))"
        let txt = NWTXTRecord([
            "v": "1",
            "room": identity.roomHash,
            "device": deviceID,
            "platform": "ios"
        ])
        listener.service = NWListener.Service(name: serviceName, type: Self.serviceType, txtRecord: txt)
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor [weak self] in
                self?.accept(connection)
            }
        }
        listener.stateUpdateHandler = { [weak self] state in
            Task { @MainActor [weak self] in
                switch state {
                case .failed(let error), .waiting(let error):
                    self?.statusText = "수신 대기 오류: \(error.localizedDescription)"
                case .ready:
                    self?.statusText = "같은 연결 ID를 사용하는 기기를 찾는 중…"
                default:
                    break
                }
            }
        }
        listener.start(queue: .main)
        self.listener = listener
    }

    private func startBrowser(identity: ConnectionIdentity) {
        let browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: Self.serviceType, domain: nil),
            using: Self.parameters()
        )
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                for result in results {
                    self.consider(result, identity: identity)
                }
            }
        }
        browser.stateUpdateHandler = { [weak self] state in
            Task { @MainActor [weak self] in
                if case .failed(let error) = state {
                    self?.statusText = "기기 검색 오류: \(error.localizedDescription)"
                }
            }
        }
        browser.start(queue: .main)
        self.browser = browser
    }

    private func consider(_ result: NWBrowser.Result, identity: ConnectionIdentity) {
        guard case .bonjour(let txt) = result.metadata,
              txt["v"] == "1",
              let advertisedRoom = txt["room"],
              Self.constantTimeEqual(advertisedRoom, identity.roomHash),
              let remoteID = txt["device"],
              Self.isCanonicalDeviceID(remoteID),
              remoteID != deviceID,
              deviceID > remoteID,
              connectionsByPeer[remoteID] == nil else {
            return
        }

        let connection = NWConnection(to: result.endpoint, using: Self.parameters())
        let peer = PeerConnection(connection: connection, role: .client, expectedRemoteDeviceID: remoteID)
        install(peer)
        connectionsByPeer[remoteID] = peer
        peer.start()
        scheduleHandshakeTimeout(for: peer)
    }

    private func accept(_ connection: NWConnection) {
        let pendingInboundCount = allConnections.values.reduce(into: 0) { count, peer in
            guard !peer.isAuthenticated else { return }
            if case .server = peer.role { count += 1 }
        }
        guard pendingInboundCount < Self.maximumPendingInboundConnections else {
            connection.cancel()
            return
        }

        let peer = PeerConnection(connection: connection, role: .server)
        install(peer)
        peer.start()
        scheduleHandshakeTimeout(for: peer)
    }

    private func scheduleHandshakeTimeout(for peer: PeerConnection) {
        Task { @MainActor [weak self, weak peer] in
            try? await Task.sleep(nanoseconds: Self.handshakeTimeoutNanoseconds)
            guard let self, let peer,
                  self.allConnections[peer.id] === peer,
                  !peer.isAuthenticated else { return }
            peer.cancel()
        }
    }

    private func install(_ peer: PeerConnection) {
        allConnections[peer.id] = peer
        peer.onStateChange = { [weak self] connection, state in
            self?.handleState(state, for: connection)
        }
        peer.onMessage = { [weak self] connection, message in
            self?.handle(message, from: connection)
        }
    }

    private func handleState(_ state: PeerConnection.State, for peer: PeerConnection) {
        switch state {
        case .ready where peer.role == .client:
            sendHello(on: peer)
        case .failed(let error):
            if peer.isAuthenticated {
                statusText = "\(peer.remoteDisplayName ?? "기기") 연결 오류: \(error.localizedDescription)"
            }
        case .cancelled:
            remove(peer)
        default:
            break
        }
    }

    private func sendHello(on peer: PeerConnection) {
        guard let identity else { return }
        var hello = WireMessage(type: .hello)
        hello.deviceId = deviceID
        hello.displayName = displayName
        hello.platform = "ios"
        hello.roomHash = identity.roomHash
        hello.clientNonce = peer.clientNonce
        hello.capabilities = Self.localCapabilities
        peer.send(hello)
    }

    private func handle(_ message: WireMessage, from peer: PeerConnection) {
        guard message.v == 1 else {
            reject(peer, reason: "지원하지 않는 프로토콜 버전")
            return
        }

        if !peer.isAuthenticated {
            handleHandshake(message, from: peer)
            return
        }

        switch message.type {
        case .chat:
            receiveChat(message, from: peer)
        case .fileOffer:
            receiveFileOffer(message, from: peer)
        case .fileAccept:
            receiveFileAcceptance(message, from: peer)
        case .fileChunk:
            receiveFileChunk(message, from: peer)
        case .fileComplete:
            receiveFileComplete(message, from: peer)
        case .rangingCapabilities:
            receiveRangingCapabilities(message, from: peer)
        case .rangingStart:
            if let peerID = peer.remoteDeviceID {
                if let previousPeerID = ranging.activePeerID, previousPeerID != peerID {
                    connectionsByPeer[previousPeerID]?.send(WireMessage(type: .rangingStop))
                    ranging.stop()
                }
                let capabilities = peers.first(where: { $0.id == peerID })?.rangingCapabilities
                ranging.handleStartRequest(from: peerID, platform: peer.remotePlatform ?? "unknown", capabilities: capabilities)
            }
        case .rangingStop:
            if ranging.activePeerID == peer.remoteDeviceID {
                ranging.stop()
            }
        case .niDiscoveryToken, .niAccessoryConfig, .niShareableConfig:
            if let peerID = peer.remoteDeviceID {
                ranging.receive(message, from: peerID, platform: peer.remotePlatform ?? "unknown")
            }
        case .error:
            errorMessage = message.reason ?? "상대 기기에서 오류가 발생했습니다."
        case .unknown:
            break
        default:
            break
        }
    }

    private func handleHandshake(_ message: WireMessage, from peer: PeerConnection) {
        guard let identity else {
            peer.cancel()
            return
        }

        switch (peer.role, message.type) {
        case (.server, .hello):
            guard let remoteID = message.deviceId,
                  Self.isCanonicalDeviceID(remoteID),
                  deviceID < remoteID,
                  let roomHash = message.roomHash,
                  Self.constantTimeEqual(roomHash, identity.roomHash),
                  let clientNonce = message.clientNonce,
                  Self.validNonce(clientNonce),
                  let name = message.displayName,
                  !name.isEmpty,
                  let platform = message.platform,
                  ["ios", "android"].contains(platform) else {
                reject(peer, reason: "연결 ID 또는 연결 소유권이 올바르지 않습니다.")
                return
            }

            if let existing = connectionsByPeer[remoteID], existing !== peer {
                if existing.isAuthenticated {
                    peer.cancel()
                    return
                }
                // Both sides can use the client nonce as the same stable tie-breaker.
                if existing.clientNonce <= clientNonce {
                    peer.cancel()
                    return
                }
                existing.cancel()
            }

            peer.remoteDeviceID = remoteID
            peer.clientNonce = clientNonce
            peer.remoteDisplayName = String(name.prefix(40))
            peer.remotePlatform = platform
            connectionsByPeer[remoteID] = peer
            let serverNonce = ConnectionIdentity.randomNonce()
            peer.serverNonce = serverNonce

            var challenge = WireMessage(type: .challenge)
            challenge.deviceId = deviceID
            challenge.displayName = displayName
            challenge.platform = "ios"
            challenge.clientNonce = clientNonce
            challenge.serverNonce = serverNonce
            challenge.capabilities = Self.localCapabilities
            peer.send(challenge)

        case (.client, .challenge):
            guard let remoteID = message.deviceId,
                  remoteID == peer.expectedRemoteDeviceID,
                  Self.isCanonicalDeviceID(remoteID),
                  deviceID > remoteID,
                  message.clientNonce == peer.clientNonce,
                  let serverNonce = message.serverNonce,
                  Self.validNonce(serverNonce),
                  let name = message.displayName,
                  !name.isEmpty,
                  let platform = message.platform,
                  ["ios", "android"].contains(platform) else {
                reject(peer, reason: "인증 요청이 올바르지 않습니다.")
                return
            }

            peer.remoteDeviceID = remoteID
            peer.remoteDisplayName = String(name.prefix(40))
            peer.remotePlatform = platform
            peer.serverNonce = serverNonce

            var auth = WireMessage(type: .auth)
            auth.proof = identity.proof(
                role: .client,
                clientDeviceID: deviceID,
                serverDeviceID: remoteID,
                clientNonce: peer.clientNonce,
                serverNonce: serverNonce
            )
            peer.send(auth)

        case (.server, .auth):
            guard let remoteID = peer.remoteDeviceID,
                  let serverNonce = peer.serverNonce,
                  let proof = message.proof,
                  identity.validates(
                    proof: proof,
                    role: .client,
                    clientDeviceID: remoteID,
                    serverDeviceID: deviceID,
                    clientNonce: peer.clientNonce,
                    serverNonce: serverNonce
                  ) else {
                reject(peer, reason: "연결 ID 인증에 실패했습니다.")
                return
            }

            var ok = WireMessage(type: .authOK)
            ok.proof = identity.proof(
                role: .server,
                clientDeviceID: remoteID,
                serverDeviceID: deviceID,
                clientNonce: peer.clientNonce,
                serverNonce: serverNonce
            )
            peer.send(ok)
            authenticated(peer)

        case (.client, .authOK):
            guard let remoteID = peer.remoteDeviceID,
                  let serverNonce = peer.serverNonce,
                  let proof = message.proof,
                  identity.validates(
                    proof: proof,
                    role: .server,
                    clientDeviceID: deviceID,
                    serverDeviceID: remoteID,
                    clientNonce: peer.clientNonce,
                    serverNonce: serverNonce
                  ) else {
                reject(peer, reason: "상대 기기 인증에 실패했습니다.")
                return
            }
            authenticated(peer)

        case (_, .error):
            errorMessage = message.reason ?? "연결이 거부되었습니다."
            peer.cancel()
        default:
            reject(peer, reason: "인증 순서가 올바르지 않습니다.")
        }
    }

    private func authenticated(_ peer: PeerConnection) {
        guard !peer.isAuthenticated,
              let id = peer.remoteDeviceID,
              let name = peer.remoteDisplayName,
              let platform = peer.remotePlatform else { return }
        peer.isAuthenticated = true
        upsertPeer(ConnectedPeer(id: id, displayName: name, platform: platform))
        statusText = "\(peers.count)명과 오프라인으로 연결됨"

        var capabilities = WireMessage(type: .rangingCapabilities)
        let local = NearbyInteractionManager.localCapabilities
        capabilities.applePeerNI = local.applePeerNI
        capabilities.appleAccessoryNI = local.appleAccessoryNI
        capabilities.androidRawUwb = false
        capabilities.distance = local.distance
        capabilities.direction = local.direction
        peer.send(capabilities)
    }

    private func reject(_ peer: PeerConnection, reason: String) {
        var error = WireMessage(type: .error)
        error.reason = reason
        peer.send(error) { peer.cancel() }
    }

    private func remove(_ peer: PeerConnection) {
        allConnections[peer.id] = nil
        guard let remoteID = peer.remoteDeviceID ?? peer.expectedRemoteDeviceID,
              connectionsByPeer[remoteID] === peer else { return }
        connectionsByPeer[remoteID] = nil
        peers.removeAll { $0.id == remoteID }
        pendingFileOffers.removeAll { $0.peerID == remoteID }
        incomingTransfers = incomingTransfers.filter { $0.value.peerID != remoteID }
        for transferID in Array(outgoingTransfers.keys) {
            resolveOutgoingRecipient(transferID: transferID, peerID: remoteID)
        }
        if ranging.activePeerID == remoteID { ranging.stop() }
        statusText = peers.isEmpty ? "같은 연결 ID를 사용하는 기기를 찾는 중…" : "\(peers.count)명과 오프라인으로 연결됨"
    }

    private func upsertPeer(_ peer: ConnectedPeer) {
        if let index = peers.firstIndex(where: { $0.id == peer.id }) {
            var copy = peer
            copy.rangingCapabilities = peers[index].rangingCapabilities
            peers[index] = copy
        } else {
            peers.append(peer)
            peers.sort { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        }
    }

    private func receiveChat(_ message: WireMessage, from peer: PeerConnection) {
        guard let id = message.messageId,
              UUID(uuidString: id) != nil,
              let senderID = message.senderId,
              senderID == peer.remoteDeviceID,
              let senderName = message.senderName,
              let timestamp = message.timestamp,
              let text = message.text,
              !text.isEmpty,
              text.utf8.count <= Self.maximumChatPayloadBytes else { return }

        guard !messages.contains(where: { $0.id == id }) else { return }
        messages.append(ChatMessage(
            id: id,
            senderID: senderID,
            senderName: String(senderName.prefix(40)),
            timestamp: Date(timeIntervalSince1970: Double(timestamp) / 1_000),
            text: text,
            isLocal: false
        ))
    }

    private func receiveFileOffer(_ message: WireMessage, from peer: PeerConnection) {
        guard let transferID = message.transferId,
              UUID(uuidString: transferID) != nil,
              let name = message.name,
              let size = message.size,
              (0...Self.maximumFileSize).contains(size),
              let sha256 = message.sha256,
              Self.isValidSHA256Hex(sha256),
              let peerID = peer.remoteDeviceID,
              let peerName = peer.remoteDisplayName,
              incomingTransfers[transferID] == nil,
              !pendingFileOffers.contains(where: { $0.id == transferID }) else { return }

        pendingFileOffers.append(FileOffer(
            id: transferID,
            peerID: peerID,
            peerName: peerName,
            name: Self.safeFileName(name),
            size: size,
            sha256: sha256
        ))
    }

    private func receiveFileAcceptance(_ message: WireMessage, from peer: PeerConnection) {
        guard let transferID = message.transferId,
              let accepted = message.accepted,
              let peerID = peer.remoteDeviceID,
              var transfer = outgoingTransfers[transferID],
              transfer.recipients[peerID] == .awaitingResponse else { return }
        guard accepted else {
            resolveOutgoingRecipient(transferID: transferID, peerID: peerID)
            return
        }

        transfer.recipients[peerID] = .sending
        outgoingTransfers[transferID] = transfer
        sendFileChunk(transferID: transferID, index: 0, to: peer)
    }

    private func sendFileChunk(transferID: String, index: Int, to peer: PeerConnection) {
        guard let peerID = peer.remoteDeviceID,
              let transfer = outgoingTransfers[transferID],
              transfer.recipients[peerID] == .sending,
              peer.isAuthenticated else { return }
        let chunkCount = (transfer.data.count + Self.chunkSize - 1) / Self.chunkSize
        guard index < chunkCount else {
            var complete = WireMessage(type: .fileComplete)
            complete.transferId = transferID
            complete.chunkCount = chunkCount
            peer.send(complete)
            resolveOutgoingRecipient(transferID: transferID, peerID: peerID)
            statusText = "\(transfer.name) 전송 완료"
            return
        }

        let lower = index * Self.chunkSize
        let upper = min(lower + Self.chunkSize, transfer.data.count)
        var chunk = WireMessage(type: .fileChunk)
        chunk.transferId = transferID
        chunk.index = index
        chunk.data = transfer.data.subdata(in: lower..<upper).base64EncodedString()
        peer.send(chunk) { [weak self, weak peer] in
            guard let self, let peer else { return }
            self.sendFileChunk(transferID: transferID, index: index + 1, to: peer)
        }
    }

    private func receiveFileChunk(_ message: WireMessage, from peer: PeerConnection) {
        guard let transferID = message.transferId,
              var transfer = incomingTransfers[transferID],
              transfer.peerID == peer.remoteDeviceID,
              let index = message.index,
              index == transfer.nextIndex,
              let encoded = message.data,
              let chunk = Data(base64Encoded: encoded),
              chunk.count <= Self.chunkSize,
              transfer.data.count + chunk.count <= transfer.size else {
            failIncomingTransfer(message.transferId, reason: "파일 청크가 올바르지 않습니다.")
            return
        }

        transfer.data.append(chunk)
        transfer.nextIndex += 1
        incomingTransfers[transferID] = transfer
    }

    private func receiveFileComplete(_ message: WireMessage, from peer: PeerConnection) {
        guard let transferID = message.transferId,
              let transfer = incomingTransfers[transferID],
              transfer.peerID == peer.remoteDeviceID,
              message.chunkCount == transfer.nextIndex,
              transfer.data.count == transfer.size,
              Data(SHA256.hash(data: transfer.data)).hexString == transfer.sha256 else {
            failIncomingTransfer(message.transferId, reason: "파일 무결성 검증에 실패했습니다.")
            return
        }

        do {
            let directory = try Self.receivedFilesDirectory()
            let destination = directory.appendingPathComponent("\(transferID)-\(transfer.name)", isDirectory: false)
            try transfer.data.write(to: destination, options: .atomic)
            receivedFiles.append(ReceivedFile(
                id: transferID,
                name: transfer.name,
                localURL: destination,
                senderName: transfer.senderName
            ))
            incomingTransfers[transferID] = nil
            statusText = "\(transfer.name) 받기 완료"
        } catch {
            failIncomingTransfer(transferID, reason: error.localizedDescription)
        }
    }

    private func failIncomingTransfer(_ transferID: String?, reason: String) {
        if let transferID { incomingTransfers[transferID] = nil }
        errorMessage = reason
    }

    private func resolveOutgoingRecipient(transferID: String, peerID: String) {
        guard var transfer = outgoingTransfers[transferID],
              transfer.recipients.removeValue(forKey: peerID) != nil else { return }
        if transfer.recipients.isEmpty {
            outgoingTransfers[transferID] = nil
        } else {
            outgoingTransfers[transferID] = transfer
        }
    }

    private func receiveRangingCapabilities(_ message: WireMessage, from peer: PeerConnection) {
        guard let peerID = peer.remoteDeviceID,
              let index = peers.firstIndex(where: { $0.id == peerID }) else { return }
        peers[index].rangingCapabilities = RangingCapabilities(
            applePeerNI: message.applePeerNI ?? false,
            appleAccessoryNI: message.appleAccessoryNI ?? false,
            androidRawUwb: message.androidRawUwb ?? false,
            distance: message.distance ?? false,
            direction: message.direction ?? false
        )
    }

    private static func parameters() -> NWParameters {
        let parameters = NWParameters.tcp
        parameters.includePeerToPeer = true
        parameters.allowLocalEndpointReuse = true
        return parameters
    }

    private static var localCapabilities: [String] {
        var values = ["chat", "file"]
        let ranging = NearbyInteractionManager.localCapabilities
        if ranging.applePeerNI { values.append("apple-peer-ni") }
        if ranging.appleAccessoryNI { values.append("apple-accessory-ni") }
        return values
    }

    private static func validNonce(_ value: String) -> Bool {
        Data(base64Encoded: value)?.count == 16
    }

    private static func isCanonicalDeviceID(_ value: String) -> Bool {
        guard let uuid = UUID(uuidString: value) else { return false }
        return uuid.uuidString.lowercased() == value
    }

    private static func constantTimeEqual(_ lhs: String, _ rhs: String) -> Bool {
        let left = Array(lhs.utf8)
        let right = Array(rhs.utf8)
        var difference = UInt8(left.count == right.count ? 0 : 1)
        let count = max(left.count, right.count)
        for index in 0..<count {
            let a = index < left.count ? left[index] : 0
            let b = index < right.count ? right[index] : 0
            difference |= a ^ b
        }
        return difference == 0
    }

    private static func safeFileName(_ value: String) -> String {
        let candidate = URL(fileURLWithPath: value).lastPathComponent
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        return candidate.isEmpty || candidate == "." ? "file" : String(candidate.prefix(120))
    }

    nonisolated static func chatTextPrefix(_ text: String) -> String {
        var byteCount = 0
        var end = text.startIndex
        var index = text.startIndex

        while index < text.endIndex {
            let next = text.index(after: index)
            let characterByteCount = text[index..<next].utf8.count
            guard byteCount + characterByteCount <= maximumChatPayloadBytes else { break }
            byteCount += characterByteCount
            end = next
            index = next
        }

        return String(text[..<end])
    }

    nonisolated static func isValidSHA256Hex(_ value: String) -> Bool {
        let bytes = value.utf8
        guard bytes.count == 64 else { return false }
        return bytes.allSatisfy { byte in
            (byte >= 48 && byte <= 57) || (byte >= 97 && byte <= 102)
        }
    }

    private static func receivedFilesDirectory() throws -> URL {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let directory = support.appendingPathComponent("ReceivedFiles", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }
}

private struct OutgoingTransfer {
    let name: String
    let data: Data
    let sha256: String
    var recipients: [String: OutgoingRecipientState]
}

private enum OutgoingRecipientState: Equatable {
    case awaitingResponse
    case sending
}

private struct IncomingTransfer {
    let peerID: String
    let senderName: String
    let name: String
    let size: Int
    let sha256: String
    var nextIndex: Int
    var data: Data
}

private enum FileTransferError: LocalizedError {
    case fileTooLarge
    case noPeers
    case outgoingTransferInProgress

    var errorDescription: String? {
        switch self {
        case .fileTooLarge:
            return "100MB 이하 파일만 전송할 수 있습니다."
        case .noPeers:
            return "연결된 기기가 없습니다."
        case .outgoingTransferInProgress:
            return "진행 중인 파일 전송이 끝난 뒤 다시 시도하세요."
        }
    }
}

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
