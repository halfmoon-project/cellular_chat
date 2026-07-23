import XCTest
@testable import CellularChatCore

/// state_transitions.json (PROTOCOL_V2.md §10): all 256 rows.
final class StateTransitionTests: XCTestCase {

    func testAllTransitions() {
        let v = Vectors.loadObject("state_transitions.json")
        let transitions = v["transitions"] as! [[String: Any]]
        XCTAssertEqual(transitions.count, 256)

        for row in transitions {
            let state = FindState(rawValue: row["state"] as! String)!
            let event = FindEvent(rawValue: row["event"] as! String)!
            let next = FindState(rawValue: row["next"] as! String)!
            let valid = row["valid"] as! Bool

            let result = FindStateMachine.reduce(state, event)
            if valid {
                XCTAssertEqual(result, next, "\(state)+\(event) should -> \(next)")
            } else {
                XCTAssertNil(result, "\(state)+\(event) should be invalid")
                // The fixture reports `next == state` for invalid transitions.
                XCTAssertEqual(next, state)
            }
        }
    }
}
