import XCTest
@testable import CellularChatCore

/// noise_official_vectors.json: both patterns, both roles, handshake ciphertexts
/// + handshake_hash + transport messages exact (PROTOCOL_V2.md §15).
final class NoiseOfficialTests: XCTestCase {

    func testOfficialVectorsExact() throws {
        let v = Vectors.loadObject("noise_official_vectors.json")
        let vectors = v["vectors"] as! [[String: Any]]
        XCTAssertEqual(vectors.count, 2)

        for vec in vectors {
            let name = vec["protocol_name"] as! String
            let pattern: NoisePattern = name.contains("NNpsk0") ? .nnpsk0 : .ikpsk2
            func hex(_ k: String) -> [UInt8]? { (vec[k] as? String).map(hexToBytes) }
            func psk(_ k: String) -> [UInt8]? { (vec[k] as? [String])?.first.map(hexToBytes) }

            let initHS = try HandshakeState(
                pattern: pattern, initiator: true,
                prologue: hex("init_prologue") ?? [],
                s: hex("init_static"), e: hex("init_ephemeral"),
                rs: hex("init_remote_static"), psk: psk("init_psks"))
            let respHS = try HandshakeState(
                pattern: pattern, initiator: false,
                prologue: hex("resp_prologue") ?? [],
                s: hex("resp_static"), e: hex("resp_ephemeral"),
                rs: hex("resp_remote_static"), psk: psk("resp_psks"))

            let messages = vec["messages"] as! [[String: String]]
            let nHandshake = pattern.messages.count

            var iSend: CipherState!, iRecv: CipherState!
            var rSend: CipherState!, rRecv: CipherState!

            for (i, msg) in messages.enumerated() {
                let payload = hexToBytes(msg["payload"]!)
                let expected = msg["ciphertext"]!
                if i < nHandshake {
                    let (w, r) = i % 2 == 0 ? (initHS, respHS) : (respHS, initHS)
                    let out = try w.writeMessage(payload)
                    XCTAssertEqual(bytesToHex(out), expected, "\(name) handshake msg \(i)")
                    XCTAssertEqual(try r.readMessage(out), payload)
                    if i == nHandshake - 1 {
                        (iSend, iRecv) = initHS.split()
                        (rRecv, rSend) = respHS.split()
                        if let hh = vec["handshake_hash"] as? String {
                            XCTAssertEqual(bytesToHex(initHS.handshakeHash), hh)
                            XCTAssertEqual(bytesToHex(respHS.handshakeHash), hh)
                        }
                    }
                } else {
                    let j = i - nHandshake
                    let (send, recv) = j % 2 == 0 ? (iSend!, rRecv!) : (rSend!, iRecv!)
                    let out = try send.encryptWithAd([], payload)
                    XCTAssertEqual(bytesToHex(out), expected, "\(name) transport msg \(i)")
                    XCTAssertEqual(try recv.decryptWithAd([], out), payload)
                }
            }
        }
    }
}
