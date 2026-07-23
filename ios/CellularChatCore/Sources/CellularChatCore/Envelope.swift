import Foundation

/// Pairing (§6) and session (§8) transport envelopes with strict schema
/// validation. Body is always a CBOR map.

public struct PairingEnvelope: Equatable {
    public let msgType: UInt64
    public let seq: UInt64
    public let body: CBOR   // .map

    public init(msgType: UInt64, seq: UInt64, body: CBOR) {
        self.msgType = msgType
        self.seq = seq
        self.body = body
    }

    /// pairingEnvelope = {1: msgType, 2: seq, 3: body(map)}
    public func encoded() -> [UInt8] {
        CBORCoder.encode(.map([
            CBORPair(.uint(1), .uint(msgType)),
            CBORPair(.uint(2), .uint(seq)),
            CBORPair(.uint(3), body),
        ]))
    }

    public static func decode(_ bytes: [UInt8]) throws -> PairingEnvelope {
        let cbor = try CBORCoder.decode(bytes)
        guard case let .map(pairs) = cbor, pairs.count == 3 else {
            throw ProtocolError.schemaViolation
        }
        guard let mt = cbor.value(forKey: 1)?.asUInt,
              let sq = cbor.value(forKey: 2)?.asUInt,
              let body = cbor.value(forKey: 3), body.isMap else {
            throw ProtocolError.schemaViolation
        }
        return PairingEnvelope(msgType: mt, seq: sq, body: body)
    }
}

public struct SessionEnvelope: Equatable {
    public let msgType: UInt64
    public let seq: UInt64
    public let sid: [UInt8]
    public let body: CBOR   // .map

    public init(msgType: UInt64, seq: UInt64, sid: [UInt8], body: CBOR) {
        self.msgType = msgType
        self.seq = seq
        self.sid = sid
        self.body = body
    }

    /// sessionEnvelope = {1: msgType, 2: seq, 3: sid(bstr 16), 4: body(map)}
    public func encoded() -> [UInt8] {
        CBORCoder.encode(.map([
            CBORPair(.uint(1), .uint(msgType)),
            CBORPair(.uint(2), .uint(seq)),
            CBORPair(.uint(3), .bytes(sid)),
            CBORPair(.uint(4), body),
        ]))
    }

    public static func decode(_ bytes: [UInt8]) throws -> SessionEnvelope {
        let cbor = try CBORCoder.decode(bytes)
        guard case let .map(pairs) = cbor, pairs.count == 4 else {
            throw ProtocolError.schemaViolation
        }
        guard let mt = cbor.value(forKey: 1)?.asUInt,
              let sq = cbor.value(forKey: 2)?.asUInt,
              let sid = cbor.value(forKey: 3)?.asBytes, sid.count == 16,
              let body = cbor.value(forKey: 4), body.isMap else {
            throw ProtocolError.schemaViolation
        }
        return SessionEnvelope(msgType: mt, seq: sq, sid: sid, body: body)
    }
}

/// Pairing message types (§6).
public enum PairMsgType: UInt64 {
    case pairBind = 64
    case pairProof = 65
    case pairComplete = 66
    case pairAbort = 67
}

/// Session message types (§8).
public enum SessionMsgType: UInt64 {
    case sessionReady = 1
    case ping = 2
    case pong = 3
    case disconnect = 4
    case capabilities = 5
    case transportUpgrade = 6
    case transportAck = 7
    case rangingOffer = 16
    case rangingAccept = 17
    case rangingStart = 18
    case rangingStop = 19
    case rangingError = 20
    case appleConfig = 21
    case appleShareable = 22
    case niToken = 23
    case oobData = 24
    case findActive = 32
    case findStopping = 33
    case findExpired = 34
}
