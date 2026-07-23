import XCTest
@testable import CellularChat

final class ConnectionIdentityTests: XCTestCase {
    func testNormalizationAndHashesMatchSharedVector() throws {
        let identity = try ConnectionIdentity(connectionID: "Abc-1234 ")

        XCTAssertEqual(identity.normalizedID, "ABC-1234")
        XCTAssertEqual(identity.roomHash, "db0f3cc6d7f064c0472ee745c6afdce3c097959263e75784f9f8df5fe2e07ecf")
        XCTAssertEqual(identity.authenticationKey.map { String(format: "%02x", $0) }.joined(), "5fa6ca86646d18558c862501efafaad023dfc83ec5d05cbc64c987f493a54d14")
    }

    func testAuthenticationProofsMatchSharedVector() throws {
        let identity = try ConnectionIdentity(connectionID: "Abc-1234 ")
        let clientID = "11111111-1111-4111-8111-111111111111"
        let serverID = "22222222-2222-4222-8222-222222222222"
        let clientNonce = "AAECAwQFBgcICQoLDA0ODw=="
        let serverNonce = "EBESExQVFhcYGRobHB0eHw=="

        let clientProof = identity.proof(
            role: .client,
            clientDeviceID: clientID,
            serverDeviceID: serverID,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )
        let serverProof = identity.proof(
            role: .server,
            clientDeviceID: clientID,
            serverDeviceID: serverID,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        )

        XCTAssertEqual(clientProof, "tfw99T2v/29w4q5gtDSPjlJaUtizR/FZnQHnQXRymfc=")
        XCTAssertEqual(serverProof, "nlpXIjJFKUZDi5B6Ox8ukcFv59oACLZYBzoe/hTMfUI=")
        XCTAssertTrue(identity.validates(
            proof: clientProof,
            role: .client,
            clientDeviceID: clientID,
            serverDeviceID: serverID,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        ))
        XCTAssertFalse(identity.validates(
            proof: serverProof,
            role: .client,
            clientDeviceID: clientID,
            serverDeviceID: serverID,
            clientNonce: clientNonce,
            serverNonce: serverNonce
        ))
    }

    func testRejectsInvalidConnectionIDs() {
        XCTAssertThrowsError(try ConnectionIdentity(connectionID: "short"))
        XCTAssertThrowsError(try ConnectionIdentity(connectionID: "ABC_123"))
        XCTAssertThrowsError(try ConnectionIdentity(connectionID: " ABC-123\n"))
        XCTAssertNoThrow(try ConnectionIdentity(connectionID: " ABC-123 "))
    }
}
