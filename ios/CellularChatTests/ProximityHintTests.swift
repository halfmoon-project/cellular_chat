import XCTest
import CellularChatCore
@testable import CellularChat

/// §12 `proximity_hint` (msgType 25). Only the BLE central has a link-RSSI API,
/// so without this message the peripheral-role device — deterministically the
/// same phone for a given pair (§9) — shows a permanently empty proximity
/// screen on a BLE-only pair.
@MainActor
final class ProximityHintTests: XCTestCase {

    private func makeCoordinator() -> (RangingCoordinator, () -> [(SessionMsgType, CBOR)]) {
        let coordinator = RangingCoordinator()
        var sent: [(SessionMsgType, CBOR)] = []
        coordinator.sendMessage = { sent.append(($0, $1)) }
        // Two iOS devices with no UWB → ble_rssi is the selected method.
        coordinator.start(local: CapabilitySet(os: .ios), peer: CapabilitySet(os: .ios),
                          isInitiator: true)
        return (coordinator, { sent })
    }

    private func hints(_ sent: [(SessionMsgType, CBOR)]) -> [UInt64] {
        sent.filter { $0.0 == .proximityHint }.compactMap { $0.1.value(forKey: 1)?.asUInt }
    }

    // MARK: central side

    func testBandChangeIsAnnouncedOnceNotPerSample() {
        let (coordinator, sent) = makeCoordinator()
        // Settle firmly in `far`, then climb to `veryNear`. Many samples, few bands.
        for i in 0..<10 { coordinator.feedRSSI(-90, at: Double(i)) }
        for i in 10..<20 { coordinator.feedRSSI(-48, at: Double(i)) }

        // far(1) then veryNear(3) — one message per band, not one per sample.
        XCTAssertEqual(hints(sent()), [1, 3])
    }

    func testASteadyLinkAnnouncesNothingAfterTheFirstBand() {
        let (coordinator, sent) = makeCoordinator()
        for i in 0..<30 { coordinator.feedRSSI(-90, at: Double(i)) }
        XCTAssertEqual(hints(sent()), [1])
    }

    func testStopClearsTheLatchSoTheNextSessionReannounces() {
        let (coordinator, sent) = makeCoordinator()
        for i in 0..<10 { coordinator.feedRSSI(-90, at: Double(i)) }
        coordinator.stop()
        coordinator.start(local: CapabilitySet(os: .ios), peer: CapabilitySet(os: .ios),
                          isInitiator: true)
        for i in 0..<10 { coordinator.feedRSSI(-90, at: Double(i)) }
        // Same band, but a new session: the peer has never heard it.
        XCTAssertEqual(hints(sent()), [1, 1])
    }

    // MARK: peripheral side

    func testReceivedHintIsDisplayedVerbatim() {
        let (coordinator, _) = makeCoordinator()
        coordinator.handleSessionMessage(.proximityHint,
                                         body: .map([CBORPair(.uint(1), .uint(3))]))
        XCTAssertEqual(coordinator.measurement?.proximity, .veryNear)
        // Never synthesised into a distance or an arrow (§12).
        XCTAssertNil(coordinator.measurement?.distanceMeters)
        XCTAssertNil(coordinator.measurement?.horizontalAngleRadians)
    }

    func testAnUnknownBandCodeIsIgnored() {
        let (coordinator, _) = makeCoordinator()
        coordinator.handleSessionMessage(.proximityHint,
                                         body: .map([CBORPair(.uint(1), .uint(9))]))
        XCTAssertNil(coordinator.measurement)
    }

    func testALiveUwbDistanceOutranksAHint() {
        let (coordinator, _) = makeCoordinator()
        coordinator.injectUWB(Measurement(timestamp: Date(), method: .niPeer,
                                          distanceMeters: 1.2,
                                          horizontalAngleRadians: 0.2, proximity: nil))
        coordinator.handleSessionMessage(.proximityHint,
                                         body: .map([CBORPair(.uint(1), .uint(1))]))
        // A real distance is strictly better information than a coarse band.
        XCTAssertEqual(coordinator.measurement?.distanceMeters, 1.2)
    }
}
