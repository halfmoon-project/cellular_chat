import XCTest
import CellularChatCore
@testable import CellularChat

/// In-memory pair-root storage so store logic runs without the Keychain.
private final class MemorySecretStore: PairSecretStore {
    private var items: [String: Data] = [:]
    func set(_ data: Data, account: String) throws { items[account] = data }
    func get(account: String) throws -> Data? { items[account] }
    func delete(account: String) { items[account] = nil }
}

/// Accessibility announcements on Find state changes (Work Package C item 2) and
/// the extended searching wording (item 6). Arming a pair whose key material is
/// absent gives a deterministic idle→arming→searching→failed path with no async
/// transport work, so the announcement sink can be observed synchronously.
final class FindAccessibilityTests: XCTestCase {

    @MainActor
    func testStateChangesAreAnnouncedIncludingSearchingHonesty() {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("a11y-\(UUID().uuidString)")
        let store = PairStore(directory: dir, secrets: MemorySecretStore())   // no committed root
        let coordinator = FindSessionCoordinator(pairStore: store)

        var announced: [String] = []
        coordinator.announce = { announced.append($0) }

        let pair = PairRecord(pairId: (0..<16).map { UInt8($0) }, roleCode: 1,
                              peerStaticPub: [UInt8](repeating: 2, count: 32),
                              negotiatedVersion: 2, alias: "친구", createdAt: 1, revoked: false)
        coordinator.arm(pair: pair)

        XCTAssertEqual(coordinator.state, .failed)
        // The searching announcement carries the honesty clause required by item 6.
        XCTAssertTrue(announced.contains { $0.contains("상대가 없다는 뜻이 아닙니다") },
                      "searching status should be announced: \(announced)")
        // The terminal failure reason is announced too.
        XCTAssertTrue(announced.contains(ReasonCode.radioUnavailable.userText),
                      "failure should be announced: \(announced)")
    }
}
