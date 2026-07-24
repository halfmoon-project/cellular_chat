import Foundation
import CellularChatCore

/// A committed pairing, persisted locally. The 32-byte peer static public key is
/// pinned (PROTOCOL_V2.md §2); the secret `pairRoot` and this device's static
/// private key live in the Keychain, not in this metadata record.
struct PairRecord: Codable, Equatable, Identifiable {
    var pairId: [UInt8]           // 16 bytes, never advertised
    var roleCode: UInt8           // 1 = A (inviter), 2 = B (joiner)
    var peerStaticPub: [UInt8]    // 32 bytes, pinned
    var negotiatedVersion: UInt64
    var alias: String
    var createdAt: UInt64
    var epoch: UInt64             // pair epoch (Android parity); 0 until bumped
    var peerPlatform: OSKind?     // nil until learned via capability exchange
    var pairingHandle: String?    // Wi-Fi Aware WAPairedDevice association: an
                                  // install-scoped routing hint, never identity (§8)
    var revoked: Bool

    var id: String { Data(pairId).base64EncodedString() }
    var pairRole: PairRole { roleCode == 1 ? .a : .b }
    /// The peer's permanent role is the opposite of ours.
    var peerRole: PairRole { roleCode == 1 ? .b : .a }

    init(pairId: [UInt8], roleCode: UInt8, peerStaticPub: [UInt8], negotiatedVersion: UInt64,
         alias: String, createdAt: UInt64,
         epoch: UInt64 = 0, peerPlatform: OSKind? = nil, pairingHandle: String? = nil,
         revoked: Bool) {
        self.pairId = pairId
        self.roleCode = roleCode
        self.peerStaticPub = peerStaticPub
        self.negotiatedVersion = negotiatedVersion
        self.alias = alias
        self.createdAt = createdAt
        self.epoch = epoch
        self.peerPlatform = peerPlatform
        self.pairingHandle = pairingHandle
        self.revoked = revoked
    }

    private enum CodingKeys: String, CodingKey {
        case pairId, roleCode, peerStaticPub, negotiatedVersion, alias, createdAt
        case epoch, peerPlatform, pairingHandle, revoked
    }

    // Custom coding so records written before `epoch`/`peerPlatform`/`pairingHandle`
    // existed still decode (missing fields default to 0/nil), and `OSKind` (a core
    // enum without Codable) round-trips through its raw value.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        pairId = try c.decode([UInt8].self, forKey: .pairId)
        roleCode = try c.decode(UInt8.self, forKey: .roleCode)
        peerStaticPub = try c.decode([UInt8].self, forKey: .peerStaticPub)
        negotiatedVersion = try c.decode(UInt64.self, forKey: .negotiatedVersion)
        alias = try c.decode(String.self, forKey: .alias)
        createdAt = try c.decode(UInt64.self, forKey: .createdAt)
        epoch = try c.decodeIfPresent(UInt64.self, forKey: .epoch) ?? 0
        peerPlatform = try c.decodeIfPresent(UInt64.self, forKey: .peerPlatform).flatMap(OSKind.init(rawValue:))
        pairingHandle = try c.decodeIfPresent(String.self, forKey: .pairingHandle)
        revoked = try c.decode(Bool.self, forKey: .revoked)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(pairId, forKey: .pairId)
        try c.encode(roleCode, forKey: .roleCode)
        try c.encode(peerStaticPub, forKey: .peerStaticPub)
        try c.encode(negotiatedVersion, forKey: .negotiatedVersion)
        try c.encode(alias, forKey: .alias)
        try c.encode(createdAt, forKey: .createdAt)
        try c.encode(epoch, forKey: .epoch)
        try c.encodeIfPresent(peerPlatform?.rawValue, forKey: .peerPlatform)
        try c.encodeIfPresent(pairingHandle, forKey: .pairingHandle)
        try c.encode(revoked, forKey: .revoked)
    }

    /// The 6-digit pairing fingerprint (PROTOCOL_V2.md §2), the same value shown
    /// during pairing confirmation. `staticA` is the inviter's key and `staticB`
    /// the joiner's, reconstructed from our pinned local key and the peer's.
    func fingerprintDisplay(localStaticPub: [UInt8]) -> String {
        let (a, b) = pairRole == .a
            ? (localStaticPub, peerStaticPub)
            : (peerStaticPub, localStaticPub)
        return Derivations.fingerprintDisplay(pairId: pairId, staticA: a, staticB: b)
    }
}

/// Coarse proximity bands derived from filtered BLE RSSI (PROTOCOL_V2.md §12).
/// Never an exact distance, never a direction.
enum ProximityBand: String, Equatable {
    case veryNear, near, far, unknown

    var label: String {
        switch self {
        case .veryNear: return "매우 가까움"
        case .near: return "가까움"
        case .far: return "멀리 있음"
        case .unknown: return "알 수 없음"
        }
    }
}

/// A single ranging sample. Only fields the platform actually reported are set;
/// a nil angle degrades the UI to distance-only, a nil distance to proximity-only.
/// `trend`/`trendConfidence` accompany the RSSI proximity path only (shared
/// contract Feature C); UWB samples carry the defaults (steady/low).
struct Measurement: Equatable {
    var timestamp: Date
    var method: RangingMethod
    var distanceMeters: Double?
    var horizontalAngleRadians: Double?
    var proximity: ProximityBand?
    var trend: RSSITrend = .steady
    var trendConfidence: TrendConfidence = .low
}

extension ReasonCode {
    /// User-facing text for a transition/disconnect reason (PROTOCOL_V2.md §13).
    var userText: String {
        switch self {
        case .normal: return "정상 종료"
        case .expired: return "찾기 시간이 만료되었습니다."
        case .revoked: return "해제된 페어입니다."
        case .duplicate: return "중복 연결을 정리했습니다."
        case .protocolError: return "프로토콜 오류가 발생했습니다."
        case .authFailed: return "상대 기기 인증에 실패했습니다."
        case .timeout: return "응답이 없어 시간 초과되었습니다."
        case .transportLost: return "연결이 끊겼습니다."
        case .upgraded: return "더 빠른 연결로 전환했습니다."
        case .capabilityMismatch: return "기능이 호환되지 않습니다."
        case .userStopped: return "사용자가 중지했습니다."
        case .permissionRequired: return "권한이 필요합니다."
        case .radioUnavailable: return "무선 기능을 사용할 수 없습니다."
        case .backgroundSuspended: return "백그라운드에서 일시 중단되었습니다."
        case .identityMismatch: return "상대 기기의 키가 일치하지 않습니다. 다시 등록하세요."
        }
    }
}
