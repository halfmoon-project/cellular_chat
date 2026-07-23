import XCTest
@testable import CellularChat

/// Bounded UWB retry backoff: initial 5 s, ×2, cap 60 s (PROTOCOL_V2.md §12).
final class BackoffTests: XCTestCase {

    func testSequenceCapsAtSixty() {
        var backoff = BoundedBackoff()
        XCTAssertEqual(backoff.next(), 5)
        XCTAssertEqual(backoff.next(), 10)
        XCTAssertEqual(backoff.next(), 20)
        XCTAssertEqual(backoff.next(), 40)
        XCTAssertEqual(backoff.next(), 60)
        XCTAssertEqual(backoff.next(), 60)   // capped
    }

    func testResetStartsOver() {
        var backoff = BoundedBackoff()
        _ = backoff.next()
        _ = backoff.next()
        backoff.reset()
        XCTAssertEqual(backoff.next(), 5)
    }
}
