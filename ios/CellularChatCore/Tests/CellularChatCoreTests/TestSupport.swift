import Foundation
import XCTest
@testable import CellularChatCore

/// Locates shared/vectors/ by walking up from this file to the repo root and
/// loads fixtures with Foundation JSONSerialization.
enum Vectors {

    static let directory: URL = {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while dir.path != "/" {
            let candidate = dir.appendingPathComponent("shared/vectors", isDirectory: true)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        fatalError("could not locate shared/vectors from \(#filePath)")
    }()

    static func loadObject(_ name: String) -> [String: Any] {
        let url = directory.appendingPathComponent(name)
        let data = try! Data(contentsOf: url)
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }
}

func hexToBytes(_ s: String) -> [UInt8] {
    precondition(s.count % 2 == 0, "odd-length hex: \(s)")
    var out = [UInt8]()
    out.reserveCapacity(s.count / 2)
    var idx = s.startIndex
    while idx < s.endIndex {
        let next = s.index(idx, offsetBy: 2)
        out.append(UInt8(s[idx..<next], radix: 16)!)
        idx = next
    }
    return out
}

func bytesToHex(_ b: [UInt8]) -> String {
    b.map { String(format: "%02x", $0) }.joined()
}

extension XCTestCase {
    /// Asserts `expression` throws the given ProtocolError.
    func assertThrows<T>(_ expected: ProtocolError,
                         _ expression: @autoclosure () throws -> T,
                         _ message: String = "",
                         file: StaticString = #filePath, line: UInt = #line) {
        do {
            _ = try expression()
            XCTFail("expected throw \(expected) \(message)", file: file, line: line)
        } catch let e as ProtocolError {
            XCTAssertEqual(e, expected, message, file: file, line: line)
        } catch {
            XCTFail("threw \(error), expected \(expected) \(message)", file: file, line: line)
        }
    }
}
