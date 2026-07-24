import XCTest
@testable import CellularChat

/// Find duration selection (Work Package C item 4): 15/30/60/120 min, default 30,
/// all within the Live Activity clamp so the picked value arms verbatim.
final class FindDurationTests: XCTestCase {

    func testDefaultIsThirtyMinutes() {
        XCTAssertEqual(FindDuration.default, .min30)
        XCTAssertEqual(FindDuration.default.seconds, 1800)
    }

    func testOptionsAndLabels() {
        XCTAssertEqual(FindDuration.allCases.map(\.seconds), [900, 1800, 3600, 7200])
        XCTAssertEqual(FindDuration.allCases.map(\.label), ["15분", "30분", "1시간", "2시간"])
    }

    @MainActor
    func testEveryOptionSurvivesTheLiveActivityClamp() {
        for option in FindDuration.allCases {
            XCTAssertGreaterThanOrEqual(option.seconds, 60)
            XCTAssertLessThanOrEqual(option.seconds, FindLiveActivityController.maxDuration)
        }
    }
}
