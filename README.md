# Cellular Chat — 두 사람 전용 서버리스 파인더

서버, 클라우드 릴레이, 비콘, 제3의 기기 없이 두 대의 스마트폰이 서로를
다시 찾아내는 네이티브 iOS(26+)·Android(16/API 36+) 앱입니다. 한 번 QR로
페어링하면, 이후 양쪽이 시간 제한이 있는 **찾기 모드**를 켰을 때 직접
P2P 전파(Wi-Fi Aware → Nearby Connections → BLE)로 재발견·상호 인증하고,
기기 조합이 지원하는 최선의 측정(UWB 방향+거리 → 거리만 → BLE 근접
밴드)을 보여줍니다.

이전의 LAN 채팅 프로토타입은 제거되었습니다. 채팅·파일 전송은 범위에
없으며, 전체 계획과 제품 계약은 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)에
있습니다.

## 동작 모델

1. **페어링(1회)**: 초대하는 쪽이 256-bit 일회용 초대 비밀이 담긴 QR을
   표시하고(복사/붙여넣기 문자열 대체 가능), 상대가 스캔합니다. 양쪽은
   Noise `NNpsk0` 핸드셰이크로 비밀 소지를 증명한 뒤, 암호화 채널 안에서
   페어 전용 X25519 정적 공개키를 교환·고정(pinning)하고, 동일한 6자리
   지문을 양쪽 화면에서 확인한 후 커밋합니다.
2. **찾기 모드**: 양쪽 모두 명시적으로 켜야 합니다(기본 30분, 최대 2시간).
   BLE 광고는 2분마다 회전하는 128-bit 토큰만 내보내며, 고정 ID·이름·키
   지문은 절대 전파되지 않습니다.
3. **연결·인증**: 어떤 전송으로 연결되든 고정된 상대 키와 페어 루트로
   Noise `IKpsk2` 핸드셰이크를 새로 수행합니다. 평문·HMAC 전용 세션·
   프로토콜 v1 같은 보안 하향 경로는 존재하지 않습니다.
4. **측정**: capability 협상이 양쪽 공통 지원 방식을 결정합니다.
   플랫폼이 실제로 보고한 측정만 표시하며, 방향이 없으면 거리만, 정밀
   거리가 없으면 근접 밴드(`veryNear/near/far/unknown`)만 보여줍니다.
   RSSI로 화살표를 합성하지 않습니다.

`검색 중`은 "아직 직접 전파 범위에 없다"는 뜻이지 "상대가 그 장소에
없다"는 뜻이 아닙니다. 서버가 없으므로 전파 범위 밖의 상대를 깨우는
것은 불가능하며, 강제 종료된 앱의 발견은 보장하지 않습니다.

## 저장소 구조

| 경로 | 내용 |
|---|---|
| `shared/PROTOCOL_V2.md` | 정규 wire·암호 계약 (Noise 스위트, canonical CBOR, 토큰, 단편화, 상태머신) |
| `shared/UWB_INTEROP.md` | iOS/Android UWB 상호운용 바이트 계약 (Apple R4 preview 기반, 실험적) |
| `shared/vectors/` | 두 플랫폼 테스트가 byte-for-byte로 소비하는 공유 픽스처 12종 |
| `tools/genvectors/` | 픽스처를 생성한 Python 레퍼런스 구현 (공식 cacophony Noise 벡터로 자체 검증) |
| `android/` | Android 앱 — `core`(순수 JVM 프로토콜 코어) + identity/pairing/transport/ranging/background/ui |
| `ios/CellularChatCore/` | Swift 프로토콜 코어 패키지 (CryptoKit, `swift test`로 호스트 검증) |
| `ios/CellularChat*` | iOS 앱 — Identity/Pairing/Transport/Ranging/Background/Features |

## 빌드·테스트

### Android (JDK 17, Android SDK 36)

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
```

### iOS (Xcode 26.2+)

프로토콜 코어는 시뮬레이터 없이 macOS에서 바로 테스트됩니다.

```sh
cd ios/CellularChatCore && swift test
```

앱 빌드(서명 없이 컴파일 확인):

```sh
xcodebuild -project ios/CellularChat.xcodeproj \
  -scheme CellularChat \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

실기기 실행은 Xcode에서 Team/Bundle Identifier를 설정해야 하며, Wi-Fi
Aware entitlement(`com.apple.developer.wifi-aware`)가 프로비저닝에
포함되어야 합니다.

## 검증 상태 (정직한 현황)

- **소프트웨어로 검증됨**: 두 플랫폼 코어가 동일한 공유 벡터(공식
  cacophony Noise 벡터 포함)를 byte-for-byte로 통과. 상태머신 256개
  전이, CBOR 수용/거부, 단편화 오류 분류, 실패 케이스(잘못된 비밀,
  키 치환, bit-flip, 재전송) 전부 테스트됨.
- **실기기 미검증**: BLE GATT, Wi-Fi Aware 데이터 경로, Nearby
  Connections, 모든 UWB 경로는 컴파일·단위테스트만 통과했고 물리
  전파는 검증되지 않았습니다. IMPLEMENTATION_PLAN.md의 Phase 1 spike
  (교차 Wi-Fi Aware / 교차 UWB / BLE / Noise 상호운용)와 Phase 10
  기기 매트릭스가 릴리스 게이트로 남아 있습니다.
- **교차 UWB**: Apple developer-preview R4 스펙 기반이므로 실험적이며,
  iOS 26.1+/Android 16 조합의 실기기 게이트 통과 전에는 기본
  비활성입니다(해당 조합은 근접 밴드로 동작).
- iOS의 Nearby Connections는 공식 SDK 부재로 정직한 미지원 스텁이며,
  중재는 BLE로 자연히 내려갑니다.

## 보안 게이트 이탈 — 자체 구현 Noise (사인오프 필요)

IMPLEMENTATION_PLAN.md §6 "Security implementation gate"는 프로덕션에서
직접 만든 인증 키교환을 금지하고, Phase 2 전에 **감사된 서드파티 Noise
구현**을 Swift·Kotlin에서 상호운용하도록 요구합니다. 현재 저장소는 이
게이트에서 의도적으로 이탈했습니다.

- **이탈**: Noise(NNpsk0/IKpsk2, X25519/ChaChaPoly/SHA-256)를 감사된
  라이브러리 대신 저장소 안에서 직접 구현했습니다.
  - iOS: `ios/CellularChatCore/Sources/CellularChatCore/Noise.swift`
  - Android: `android/app/src/main/java/com/cellularchat/app/core/noise/`
    (`HandshakeState.kt`, `SymmetricState.kt`, `CipherState.kt`)
- **완화**: 두 구현 모두 공식 cacophony 테스트 벡터를
  (`shared/vectors/noise_official_vectors.json`) 두 패턴(NNpsk0·IKpsk2)과
  두 역할(initiator·responder)에 대해 byte-for-byte로 통과하며, 이는
  양 플랫폼 단위테스트로 강제됩니다. 그럼에도 이는 서드파티 감사를
  대체하지 못합니다(타이밍 부채널·오용 방지 등은 벡터로 검증되지 않음).
- **업그레이드 경로**: Swift·Kotlin에서 상호운용 가능한 감사된 Noise
  라이브러리가 나오면 코어의 이 파일들을 그 라이브러리로 교체합니다.
  wire 계약은 PROTOCOL_V2.md에 고정되어 있으므로 벡터가 회귀 가드가
  됩니다.
- **상태**: 릴리스 전 이 이탈에 대한 **명시적 보안 사인오프**가
  필요합니다. 사인오프 전에는 실배포 대상이 아닙니다.
