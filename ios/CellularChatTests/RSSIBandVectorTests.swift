import XCTest
import Foundation
@testable import CellularChat

/// Consumes `shared/vectors/rssi_bands.json` (PROTOCOL_V2.md §12) against the
/// real `RSSIProximityFilter`. The band parameters — window, median rule, entry
/// thresholds and hysteresis — used to live only in each app's code and had
/// silently diverged, so the two phones of one pair reported different bands at
/// the same distance. §12 now pins them and this fixture is the regression guard
/// that stops either platform drifting again.
final class RSSIBandVectorTests: XCTestCase {

    private func fixture() -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath)
        while dir.pathComponents.count > 1 {
            let f = dir.appendingPathComponent("shared/vectors/rssi_bands.json")
            if FileManager.default.fileExists(atPath: f.path) {
                let data = try! Data(contentsOf: f)
                return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
            }
            dir = dir.deletingLastPathComponent()
        }
        fatalError("could not locate shared/vectors/rssi_bands.json from \(#filePath)")
    }

    /// The filter's own defaults must BE the pinned §12 parameters — a fixture
    /// that only passes with hand-supplied constructor arguments would not catch
    /// a drifting default, which is exactly how the platforms diverged.
    func testDefaultsMatchThePinnedParameters() {
        let params = fixture()["params"] as! [String: Any]
        XCTAssertEqual(params["veryNearEntryDbm"] as! Double, -55)
        XCTAssertEqual(params["nearEntryDbm"] as! Double, -75)
        XCTAssertEqual(params["hysteresisDb"] as! Double, 5)
        XCTAssertEqual(params["window"] as! Int, 5)
        XCTAssertEqual(params["median"] as! String, "averaged-middle")
    }

    func testEveryCaseClassifiesIdenticallyToTheReference() {
        let cases = fixture()["cases"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as! String
            let samples = c["samplesDbm"] as! [Double]
            let expected = c["expectedBands"] as! [String]
            // Default-constructed on purpose: the defaults are the contract.
            let filter = RSSIProximityFilter()
            var got: [String] = []
            // A fixed 1 s cadence keeps every sample inside the 10 s gap reset,
            // so the band path is exercised without the trend interfering.
            for (i, s) in samples.enumerated() {
                got.append(filter.add(rssi: s, at: Double(i)).rawValue)
            }
            XCTAssertEqual(got, expected, "case \(name)")
        }
    }
}
