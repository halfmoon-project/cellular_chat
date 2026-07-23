import XCTest
import CellularChatCore
@testable import CellularChat

/// Fake transport for arbitration tests: injects availability + a connect result.
private final class FakeTransport: PeerTransport {
    let kind: TransportKind
    let isAvailable: Bool
    private let result: Result<Void, TransportFailure>
    private(set) var connectCalls = 0

    var onRecord: (([UInt8]) -> Void)?
    var onClosed: ((ReasonCode) -> Void)?

    init(_ kind: TransportKind, available: Bool, result: Result<Void, TransportFailure>) {
        self.kind = kind
        self.isAvailable = available
        self.result = result
    }

    func connect() async -> Result<Void, TransportFailure> {
        connectCalls += 1
        return result
    }
    func send(record: [UInt8]) throws {}
    func disconnect(reason: ReasonCode) {}
}

/// Sequential arbitration + fall-through (PROTOCOL_V2.md §4).
final class TransportArbitrationTests: XCTestCase {

    @MainActor
    func testPrefersWifiAwareWhenItConnects() async {
        let aware = FakeTransport(.wifiAware, available: true, result: .success(()))
        let nearby = FakeTransport(.nearby, available: true, result: .success(()))
        let ble = FakeTransport(.ble, available: true, result: .success(()))
        let winner = await TransportCoordinator.arbitrate(transports: [aware, nearby, ble])
        XCTAssertTrue(winner === aware)
        XCTAssertEqual(nearby.connectCalls, 0)   // no parallel discovery
        XCTAssertEqual(ble.connectCalls, 0)
    }

    @MainActor
    func testFallsThroughToBLEWhenHigherFail() async {
        let aware = FakeTransport(.wifiAware, available: true, result: .failure(.timeout))
        let nearby = FakeTransport(.nearby, available: false, result: .failure(.unsupported))
        let ble = FakeTransport(.ble, available: true, result: .success(()))
        let winner = await TransportCoordinator.arbitrate(transports: [aware, nearby, ble])
        XCTAssertTrue(winner === ble)
        XCTAssertEqual(aware.connectCalls, 1)
        XCTAssertEqual(nearby.connectCalls, 0)   // unavailable is skipped, not connected
        XCTAssertEqual(ble.connectCalls, 1)
    }

    @MainActor
    func testReturnsNilWhenNothingConnects() async {
        let ble = FakeTransport(.ble, available: true, result: .failure(.failed))
        let winner = await TransportCoordinator.arbitrate(transports: [ble])
        XCTAssertNil(winner)
    }

    @MainActor
    func testUpgradeOnlyToHigherRankedTransport() async {
        let ble = FakeTransport(.ble, available: true, result: .success(()))
        let coordinator = TransportCoordinator()
        _ = await coordinator.select(transports: [ble], order: [.ble])
        XCTAssertTrue(coordinator.shouldUpgrade(to: .wifiAware))   // aware outranks ble
        XCTAssertFalse(coordinator.shouldUpgrade(to: .ble))        // same transport
    }
}
