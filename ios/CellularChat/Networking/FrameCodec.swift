import Foundation

enum FrameError: Error, Equatable {
    case emptyFrame
    case frameTooLarge(Int)
    case invalidJSON
}

struct FrameCodec {
    static let maximumFrameSize = 1_048_576

    static func encode(_ message: WireMessage) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        let payload = try encoder.encode(message)
        guard !payload.isEmpty else { throw FrameError.emptyFrame }
        guard payload.count <= maximumFrameSize else {
            throw FrameError.frameTooLarge(payload.count)
        }

        var length = UInt32(payload.count).bigEndian
        var framed = Data(bytes: &length, count: MemoryLayout<UInt32>.size)
        framed.append(payload)
        return framed
    }
}

final class FrameDecoder {
    private struct Envelope: Decodable {
        let v: Int
        let type: WireMessageType
    }

    private var buffer = Data()

    func append(_ data: Data) throws -> [WireMessage] {
        buffer.append(data)
        var messages: [WireMessage] = []

        while buffer.count >= 4 {
            let length = buffer.prefix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            guard length > 0 else { throw FrameError.emptyFrame }
            guard length <= FrameCodec.maximumFrameSize else {
                throw FrameError.frameTooLarge(Int(length))
            }
            guard buffer.count >= 4 + Int(length) else { break }

            let payload = buffer.subdata(in: 4..<(4 + Int(length)))
            buffer.removeSubrange(0..<(4 + Int(length)))
            do {
                let decoder = JSONDecoder()
                let envelope = try decoder.decode(Envelope.self, from: payload)
                if case .unknown = envelope.type {
                    var message = WireMessage(type: envelope.type)
                    message.v = envelope.v
                    messages.append(message)
                } else {
                    messages.append(try decoder.decode(WireMessage.self, from: payload))
                }
            } catch {
                throw FrameError.invalidJSON
            }
        }

        return messages
    }
}
