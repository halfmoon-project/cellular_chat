import XCTest
import CellularChatCore
@testable import CellularChat

/// Per-pair feature/reason line derivation. Reasons come from capability
/// booleans only (PROTOCOL_V2.md §11) — never OS-version strings.
final class PairFeatureStatusTests: XCTestCase {

    private func caps(os: OSKind, aware: Bool = false, uwb: Bool = false,
                      interop: Bool = false) -> CapabilitySet {
        CapabilitySet(os: os, wifiAware: aware, uwbPresent: uwb, appleInteropUwb: interop)
    }

    func testUWBMethodShowsPreciseLabelNoReason() {
        let s = PairFeatureStatus.derive(
            local: caps(os: .ios, aware: true, uwb: true),
            peer: caps(os: .ios, aware: true, uwb: true),
            selection: RangingSelection(method: .niPeer, edm: false))
        XCTAssertEqual(s.rangingLabel, "UWB 정밀 거리")
        XCTAssertNil(s.rangingReason)
        XCTAssertNil(s.transportNote)
    }

    func testPreNegotiationReason() {
        let s = PairFeatureStatus.derive(local: caps(os: .ios, uwb: true), peer: nil, selection: nil)
        XCTAssertEqual(s.rangingLabel, "신호세기 기반(대략적)")
        XCTAssertEqual(s.rangingReason, "상대 기기 기능 확인 전")
        XCTAssertNil(s.transportNote)
    }

    func testPeerWithoutUWB() {
        let s = PairFeatureStatus.derive(
            local: caps(os: .ios, uwb: true),
            peer: caps(os: .android),
            selection: RangingSelection(method: .bleRssi, edm: false))
        XCTAssertEqual(s.rangingReason, "상대 기기에 UWB 없음")
    }

    func testPeerWithoutInterop() {
        let s = PairFeatureStatus.derive(
            local: caps(os: .ios, uwb: true, interop: true),
            peer: caps(os: .android, uwb: true),
            selection: RangingSelection(method: .bleRssi, edm: false))
        XCTAssertEqual(s.rangingReason, "상대 기기가 iPhone-Android 간 UWB 미지원")
    }

    func testLocalWithoutUWB() {
        let s = PairFeatureStatus.derive(
            local: caps(os: .ios),
            peer: caps(os: .ios, uwb: true),
            selection: RangingSelection(method: .bleRssi, edm: false))
        XCTAssertEqual(s.rangingReason, "이 기기에 UWB 없음")
    }

    /// The iOS 18 ↔ Android case: both radios have UWB, only the local side
    /// lacks interop (appleInteropUwb stays false below iOS 26.1).
    func testLocalWithoutInterop() {
        let s = PairFeatureStatus.derive(
            local: caps(os: .ios, uwb: true),
            peer: caps(os: .android, uwb: true, interop: true),
            selection: RangingSelection(method: .bleRssi, edm: false))
        XCTAssertEqual(s.rangingReason, "이 기기가 iPhone-Android 간 UWB 미지원")
    }

    func testSamePlatformRssiWithMutualUWBHasNoReason() {
        // Defensive fallthrough: selector would pick niPeer here, but a bleRssi
        // selection with fully capable caps must not invent a reason.
        let s = PairFeatureStatus.derive(
            local: caps(os: .ios, uwb: true),
            peer: caps(os: .ios, uwb: true),
            selection: RangingSelection(method: .bleRssi, edm: false))
        XCTAssertNil(s.rangingReason)
    }

    func testBLEOnlyPairShowsTransportNote() {
        let s = PairFeatureStatus.derive(
            local: caps(os: .ios, aware: true, uwb: true),
            peer: caps(os: .android, uwb: true, interop: true),
            selection: RangingSelection(method: .bleRssi, edm: false))
        XCTAssertEqual(s.transportNote, "기본 연결(BLE)로 동작 중")
    }

    func testLineJoinsLabelReasonAndNote() {
        let s = PairFeatureStatus(rangingLabel: "신호세기 기반(대략적)",
                                  rangingReason: "상대 기기에 UWB 없음",
                                  transportNote: "기본 연결(BLE)로 동작 중")
        XCTAssertEqual(s.line, "신호세기 기반(대략적) · 상대 기기에 UWB 없음 · 기본 연결(BLE)로 동작 중")
    }
}
