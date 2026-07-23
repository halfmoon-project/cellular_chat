# Cellular Chat

인터넷이나 셀룰러 데이터 없이, 같은 로컬 네트워크에 있는 두 기기를 연결하는 네이티브 iOS·Android 프로토타입입니다. 같은 연결 ID를 입력한 기기끼리 상호 인증한 뒤 채팅, 파일 전송, 지원 기기의 UWB 거리·방향 찾기를 제공합니다. 별도 서버나 비콘은 사용하지 않습니다.

## 구현 범위

| 기능 | iOS | Android | 교차 플랫폼 |
|---|---|---|---|
| 주변 기기 검색·TCP 연결 | Bonjour / Network.framework | NSD / TCP socket | 지원 |
| 같은 ID 상호 인증 | SHA-256 + HMAC-SHA256 | SHA-256 + HMAC-SHA256 | 지원 |
| 채팅·파일 전송 | 지원 | 지원 | 지원 |
| UWB 거리·방향 | Nearby Interaction | Android Ranging API | 아래 제한 참고 |

연결 ID는 앞뒤 ASCII 공백 제거와 대문자 변환 후 비교되며, 허용 형식은 `6~32`자의 영문자·숫자·하이픈입니다. 네트워크에는 ID 원문 대신 해시를 광고하고, 실제 연결에서는 HMAC challenge-response로 같은 ID인지 확인합니다. 자세한 wire format과 테스트 벡터는 [shared/PROTOCOL.md](shared/PROTOCOL.md), [shared/TEST_VECTOR.json](shared/TEST_VECTOR.json)에 있고, 실험적 교차 UWB 바이트 계약은 [shared/UWB_INTEROP.md](shared/UWB_INTEROP.md)에 별도로 정리했습니다.

“같은 ID”는 서로 다른 ID의 연결을 인증 단계에서 거부한다는 의미입니다. 같은 ID라도 다음 네트워크 조건이 충족되지 않으면 발견·연결을 보장할 수 없습니다.

- 두 기기가 같은 LAN 또는 한 기기가 제공한 로컬 핫스팟에 연결되어 있어야 합니다. 인터넷 회선과 Bluetooth는 필요하지 않지만 Wi-Fi 인터페이스는 켜져 있어야 합니다.
- 공유기가 mDNS/Bonjour와 기기 간 TCP를 허용해야 합니다. 게스트 Wi-Fi의 클라이언트 격리 기능이 켜져 있으면 연결되지 않습니다.
- iOS의 Apple 전용 peer-to-peer Wi-Fi만으로 Android와 연결하는 기능과 Wi-Fi Direct/Aware는 현재 구현 범위가 아닙니다.
- 네트워크가 순간적으로 끊긴 뒤 자동 재연결되지 않으면 양쪽 앱에서 **나가기**를 누르고 같은 ID로 다시 시작하세요.

이 버전은 연결 ID 인증 후의 채팅·파일 payload를 암호화하는 완성형 보안 메신저가 아닙니다. 추측하기 쉬운 ID 대신 iOS 앱의 **안전한 ID 만들기**로 긴 임의 ID를 만든 뒤 Android에도 입력하고, 신뢰할 수 없는 LAN에서 민감한 파일을 전송하지 마세요.

## 빌드

### iOS

요구 사항은 Xcode 26.2, iOS 16 이상, 실제 실행용 Apple 개발자 서명입니다. Xcode에서 `ios/CellularChat.xcodeproj`를 열고 `CellularChat` scheme을 선택한 뒤, Signing & Capabilities의 Team과 Bundle Identifier를 자신의 값으로 설정해 실제 iPhone에서 실행합니다. Nearby Interaction은 별도 entitlement를 임의로 추가하지 않고 `Info.plist`의 사용 목적 문구와 런타임 기기 capability 확인을 사용합니다.

서명 없이 컴파일만 확인하려면 저장소 루트에서 다음을 실행합니다.

```sh
xcodebuild -project ios/CellularChat.xcodeproj \
  -scheme CellularChat \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

시뮬레이터에서는 UWB를 시험할 수 없습니다.

### Android

요구 사항은 JDK 17과 Android SDK 36입니다. Android Studio에서 `android/`를 열거나 다음을 실행합니다.

```sh
cd android
./gradlew testDebugUnitTest assembleDebug
```

Gradle 배포 파일과 의존성이 이미 로컬 캐시에 준비된 환경에서는 위 명령에
`--offline`을 추가해 네트워크 없이 빌드할 수 있습니다. 처음 여는 개발 환경은
빌드 도구와 JUnit 의존성을 한 번 내려받아야 합니다.

디버그 APK는 `android/app/build/outputs/apk/debug/app-debug.apk`에 생성됩니다. 채팅·파일은 Android 8.0(API 26) 이상에서 동작하도록 구성되어 있고, 새 Android Ranging API 기반 UWB는 Android 16(API 36)과 실제 UWB 하드웨어가 필요합니다.

## 실행 방법

1. 두 실제 기기를 같은 로컬 Wi-Fi 또는 로컬 핫스팟에 연결합니다. 해당 네트워크에 인터넷이 연결되어 있지 않아도 됩니다.
2. 양쪽 앱에서 표시 이름과 동일한 연결 ID를 입력하고 **주변 기기 찾기**를 누릅니다.
3. iOS에서는 로컬 네트워크·Nearby Interaction·카메라 권한을, Android에서는 주변 거리 측정 권한을 허용합니다. 카메라는 iPhone의 방향 측정 보조에만 사용됩니다.
4. 인증된 기기가 표시되면 메시지를 보내거나 클립 버튼으로 파일을 고릅니다. 수신자가 제안을 수락해야 파일 전송이 시작됩니다.
5. 상대 기기의 **방향 찾기**를 누르면 지원되는 조합에서 거리와 상대 방향 화살표가 표시됩니다.

## UWB 방향 찾기의 범위와 제한

UWB는 별도 비콘 없이 두 휴대폰 사이의 거리와 **휴대폰 정면을 기준으로 한 상대 수평 방향**을 측정합니다. 화살표는 GPS 위치, 지도 좌표 또는 절대 북쪽 방위를 뜻하지 않습니다.

- iPhone끼리는 Apple Nearby Interaction peer mode를 사용합니다.
- iPhone·Android 조합은 Apple Nearby Interaction accessory protocol의 out-of-band 데이터를 기존 TCP 연결로 교환하고, Android 16의 raw UWB session에서 Angle of Arrival을 요청합니다. 현재 구현은 iOS 26.1 개발자 프리뷰 상호운용 규격의 UWB config v2.0에 의존하므로, iOS 26.1 이상과 대상 Android OEM 조합에서 물리 검증이 필요합니다.
- Android끼리의 UWB ranging은 현재 미지원입니다. 해당 조합에서도 LAN 기반 인증·채팅·파일 전송은 지원합니다.
- 앱은 iOS의 `supportsDirectionMeasurement`와 Android의 azimuth capability를 확인하고, 플랫폼 API가 실제 각도 값을 돌려준 경우에만 화살표를 표시합니다. 지원 기기에서도 런타임 각도 값은 없을 수 있고, 이때는 거리만 표시하며 방향을 추정해서 만들지 않습니다. Camera Assistance 역시 지원 기기·카메라 권한·측정 수렴이 필요합니다.
- 공연장처럼 사람과 금속 구조물이 많은 환경에서는 신체 차폐와 다중 경로 때문에 값이 늦거나 끊기고 정확도가 낮아질 수 있습니다. 두 휴대폰을 손에 들고 천천히 몸을 돌리며 측정해야 합니다.
- iOS·Android 교차 플랫폼 UWB는 실제 호환 기기 두 대에서 아직 검증되지 않은 실험적 경로입니다. Apple/Android 버전과 제조사 UWB stack 조합에 따라 세션 설정이나 방향 보고가 실패할 수 있습니다.

UWB는 거리·방향 측정 전용입니다. 채팅과 파일은 UWB로 보내지 않고, 연결을 유지한 로컬 Wi-Fi TCP를 사용합니다.

## 디자인 시스템

UI는 [`halfmoon-design`](https://github.com/halfmoon-project/halfmoon-design)의 시맨틱 토큰을 사용합니다. iOS 앱은 Swift Package Manager로 `HalfmoonTokens` 0.4.0을 직접 참조하며, Android 앱은 현재 XML/View 기반이므로 같은 버전의 라이트·다크 색상과 간격·라디우스를 Android 리소스에 매핑합니다. Android를 Jetpack Compose로 전환하면 공식 JitPack 패키지로 교체할 수 있습니다.

## 실제 기기 검증 매트릭스

| 조합 | LAN 인증·채팅·파일 | UWB 거리 | UWB 방향 | 확인할 조건 |
|---|---|---|---|---|
| UWB iPhone ↔ UWB iPhone | 테스트 필요 | 테스트 필요 | 테스트 필요 | Nearby Interaction 지원, 카메라 권한 |
| UWB iPhone ↔ Android 16 UWB | 테스트 필요 | 실험적·테스트 필요 | 실험적·테스트 필요 | iOS 26.1+, API 36 Ranging, azimuth 지원 vendor stack |
| Android ↔ Android | 테스트 필요 | 현재 미지원 | 현재 미지원 | LAN 인증·채팅·파일만 지원 |
| Simulator / Emulator | 프로토콜·UI만 | 불가 | 불가 | UWB는 반드시 실제 기기 사용 |

각 실제 조합에서 발견, 같은 ID 인증, 다른 ID 거부, 양방향 채팅, 파일 크기·SHA-256 검증, 거리 갱신, 좌우 방향 화살표를 차례로 확인하세요.
