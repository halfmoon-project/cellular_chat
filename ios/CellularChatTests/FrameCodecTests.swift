import XCTest
@testable import CellularChat

final class FrameCodecTests: XCTestCase {
    func testSplitFrameIsReassembled() throws {
        var original = WireMessage(type: .chat)
        original.messageId = "ca36b9c5-2e9f-4f54-aa13-18facbabe3f4"
        original.senderId = "11111111-1111-4111-8111-111111111111"
        original.senderName = "Tester"
        original.timestamp = 1_784_800_000_000
        original.text = "hello 안녕"

        let frame = try FrameCodec.encode(original)
        let decoder = FrameDecoder()
        XCTAssertEqual(try decoder.append(frame.prefix(3)), [])
        XCTAssertEqual(try decoder.append(frame.dropFirst(3).prefix(8)), [])
        XCTAssertEqual(try decoder.append(frame.dropFirst(11)), [original])
    }

    func testMultipleFramesAreDecodedTogether() throws {
        var first = WireMessage(type: .chat)
        first.text = "one"
        var second = WireMessage(type: .chat)
        second.text = "two"

        var bytes = try FrameCodec.encode(first)
        bytes.append(try FrameCodec.encode(second))

        XCTAssertEqual(try FrameDecoder().append(bytes), [first, second])
    }

    func testRejectsEmptyAndOversizedFrames() throws {
        let emptyLength = Data([0, 0, 0, 0])
        XCTAssertThrowsError(try FrameDecoder().append(emptyLength)) { error in
            XCTAssertEqual(error as? FrameError, .emptyFrame)
        }

        let tooLarge = FrameCodec.maximumFrameSize + 1
        let header = Data([
            UInt8((tooLarge >> 24) & 0xff),
            UInt8((tooLarge >> 16) & 0xff),
            UInt8((tooLarge >> 8) & 0xff),
            UInt8(tooLarge & 0xff)
        ])
        XCTAssertThrowsError(try FrameDecoder().append(header)) { error in
            XCTAssertEqual(error as? FrameError, .frameTooLarge(tooLarge))
        }
    }

    func testRejectsInvalidJSON() {
        let payload = Data("not-json".utf8)
        var length = UInt32(payload.count).bigEndian
        var frame = Data(bytes: &length, count: 4)
        frame.append(payload)

        XCTAssertThrowsError(try FrameDecoder().append(frame)) { error in
            XCTAssertEqual(error as? FrameError, .invalidJSON)
        }
    }

    func testUnknownMessageTypeCanBeIgnoredAfterDecoding() throws {
        let payload = Data(#"{"v":1,"type":"future_message","size":"auto","accepted":{"mode":"later"}}"#.utf8)
        var length = UInt32(payload.count).bigEndian
        var frame = Data(bytes: &length, count: 4)
        frame.append(payload)

        let messages = try FrameDecoder().append(frame)
        XCTAssertEqual(messages.count, 1)
        XCTAssertEqual(messages.first?.type, .unknown("future_message"))
        XCTAssertNil(messages.first?.size)
        XCTAssertNil(messages.first?.accepted)
    }

    func testChatPrefixUsesAtMostEightThousandUTF8BytesWithoutSplittingGraphemes() {
        let exact = String(repeating: "🙂", count: 2_000)
        XCTAssertEqual(PeerNetworkManager.chatTextPrefix(exact), exact)
        XCTAssertEqual(PeerNetworkManager.chatTextPrefix(exact + "a").utf8.count, 8_000)

        let boundary = String(repeating: "a", count: 7_999) + "한"
        let prefix = PeerNetworkManager.chatTextPrefix(boundary)
        XCTAssertEqual(prefix, String(repeating: "a", count: 7_999))
        XCTAssertLessThanOrEqual(prefix.utf8.count, PeerNetworkManager.maximumChatPayloadBytes)
    }

    func testFileDigestRequiresExactlyLowercaseSHA256Hex() {
        XCTAssertTrue(PeerNetworkManager.isValidSHA256Hex(String(repeating: "0a", count: 32)))
        XCTAssertFalse(PeerNetworkManager.isValidSHA256Hex(String(repeating: "0A", count: 32)))
        XCTAssertFalse(PeerNetworkManager.isValidSHA256Hex(String(repeating: "zz", count: 32)))
        XCTAssertFalse(PeerNetworkManager.isValidSHA256Hex(String(repeating: "a", count: 63)))
    }
}
