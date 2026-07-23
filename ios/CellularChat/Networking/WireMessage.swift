import Foundation

enum WireMessageType: Codable, Equatable {
    case hello
    case challenge
    case auth
    case authOK
    case error
    case chat
    case fileOffer
    case fileAccept
    case fileChunk
    case fileComplete
    case rangingCapabilities
    case rangingStart
    case rangingStop
    case niDiscoveryToken
    case niAccessoryConfig
    case niShareableConfig
    case unknown(String)

    init(from decoder: Decoder) throws {
        let value = try decoder.singleValueContainer().decode(String.self)
        switch value {
        case "hello": self = .hello
        case "challenge": self = .challenge
        case "auth": self = .auth
        case "auth_ok": self = .authOK
        case "error": self = .error
        case "chat": self = .chat
        case "file_offer": self = .fileOffer
        case "file_accept": self = .fileAccept
        case "file_chunk": self = .fileChunk
        case "file_complete": self = .fileComplete
        case "ranging_capabilities": self = .rangingCapabilities
        case "ranging_start": self = .rangingStart
        case "ranging_stop": self = .rangingStop
        case "ni_discovery_token": self = .niDiscoveryToken
        case "ni_accessory_config": self = .niAccessoryConfig
        case "ni_shareable_config": self = .niShareableConfig
        default: self = .unknown(value)
        }
    }

    func encode(to encoder: Encoder) throws {
        let value: String
        switch self {
        case .hello: value = "hello"
        case .challenge: value = "challenge"
        case .auth: value = "auth"
        case .authOK: value = "auth_ok"
        case .error: value = "error"
        case .chat: value = "chat"
        case .fileOffer: value = "file_offer"
        case .fileAccept: value = "file_accept"
        case .fileChunk: value = "file_chunk"
        case .fileComplete: value = "file_complete"
        case .rangingCapabilities: value = "ranging_capabilities"
        case .rangingStart: value = "ranging_start"
        case .rangingStop: value = "ranging_stop"
        case .niDiscoveryToken: value = "ni_discovery_token"
        case .niAccessoryConfig: value = "ni_accessory_config"
        case .niShareableConfig: value = "ni_shareable_config"
        case .unknown(let raw): value = raw
        }
        var container = encoder.singleValueContainer()
        try container.encode(value)
    }
}

struct WireMessage: Codable, Equatable {
    var v = 1
    let type: WireMessageType

    var deviceId: String?
    var displayName: String?
    var platform: String?
    var roomHash: String?
    var clientNonce: String?
    var serverNonce: String?
    var capabilities: [String]?
    var proof: String?
    var reason: String?

    var messageId: String?
    var senderId: String?
    var senderName: String?
    var timestamp: Int64?
    var text: String?

    var transferId: String?
    var name: String?
    var size: Int?
    var sha256: String?
    var accepted: Bool?
    var index: Int?
    var data: String?
    var chunkCount: Int?

    var applePeerNI: Bool?
    var appleAccessoryNI: Bool?
    var androidRawUwb: Bool?
    var distance: Bool?
    var direction: Bool?

    init(type: WireMessageType) {
        self.type = type
    }
}

struct ChatMessage: Identifiable, Equatable {
    let id: String
    let senderID: String
    let senderName: String
    let timestamp: Date
    let text: String
    let isLocal: Bool
}

struct ConnectedPeer: Identifiable, Equatable {
    let id: String
    let displayName: String
    let platform: String
    var rangingCapabilities: RangingCapabilities?
}

struct RangingCapabilities: Equatable {
    let applePeerNI: Bool
    let appleAccessoryNI: Bool
    let androidRawUwb: Bool
    let distance: Bool
    let direction: Bool
}

struct FileOffer: Identifiable, Equatable {
    let id: String
    let peerID: String
    let peerName: String
    let name: String
    let size: Int
    let sha256: String
}

struct ReceivedFile: Identifiable, Equatable {
    let id: String
    let name: String
    let localURL: URL
    let senderName: String
}
