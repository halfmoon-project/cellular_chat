import XCTest
import CryptoKit
@testable import CellularChat

/// §5 fixes the Wi-Fi Aware service name at `_cellfind._udp`. NAN carries no
/// service name on air — only a 6-byte Service ID hashed from it, matched with
/// a fixed-width compare — so a spelling difference between the platforms is a
/// total discovery failure with no error on either side. Android published the
/// bare name `cellfind` until 2026-07 and could never have matched.
///
/// These pin the on-air artifact rather than the source string, so the mirror
/// test in `ProximityHintTest`'s Android counterpart
/// (`WifiAwareServiceNameTest`) asserts the same six bytes.
final class WiFiAwareServiceNameTests: XCTestCase {

    /// sha256("_cellfind._udp")[0..6] — the bytes that actually go on air.
    private let expectedServiceID = "58bd1bc27102"

    private func serviceID(_ name: String) -> String {
        SHA256.hash(data: Data(name.utf8)).prefix(6)
            .map { String(format: "%02x", $0) }.joined()
    }

    func testServiceNameHashesToThePinnedServiceID() {
        XCTAssertEqual(WiFiAwareTransport.serviceName, "_cellfind._udp")
        XCTAssertEqual(serviceID(WiFiAwareTransport.serviceName), expectedServiceID)
    }

    /// A name absent from `WiFiAwareServices` makes `allServices[name]` nil, so
    /// `isAvailable` reports false and the transport is skipped — silently, on a
    /// device that fully supports Wi-Fi Aware.
    func testInfoPlistDeclaresExactlyThatService() {
        let services = Bundle.main.infoDictionary?["WiFiAwareServices"] as? [String: Any]
        XCTAssertNotNil(services, "Info.plist has no WiFiAwareServices key")
        XCTAssertEqual(Array(services?.keys ?? [:].keys), [WiFiAwareTransport.serviceName])
    }

    /// The service name is a discovery identifier. The `cellfind/v2 …` labels
    /// are byte-exact crypto inputs (§2/§6/§8) and must never move with it.
    func testTheBareNameDoesNotCollideWithThePinnedID() {
        XCTAssertNotEqual(serviceID("cellfind"), expectedServiceID)
        XCTAssertEqual(serviceID("cellfind"), "1b68dd9f32c5")
    }
}
