# 테스트 전략 — 실기기·TestFlight 없이 어디까지 가능한가

조사일 2026-07-29. 이 문서의 "검증됨" 항목은 이 맥에서 실제로 명령을 실행해
얻은 결과다. "문서" 항목은 1차 출처를 확인했지만 여기서 재현하지는 않았다.

## 한 줄 요약

**내부 루프(§0)가 진짜 답이다**: iOS는 `⌘R`로 시뮬레이터에서 전체 Find 흐름이
돌고, Android는 에뮬레이터 2대가 진짜 BLE를 나른다. 둘 다 검증됐다.
TestFlight도 지금 당장 끊을 수 있다(§2). 반대로 **Wi-Fi Aware는 양쪽 플랫폼
어디에서도 가상화가 불가능**하다 — 이건 하드웨어 게이트다.

---

# 0. 내부 루프 — "만들었는데 되는지" 확인하기

이게 원래 질문이다. 회귀 테스트(§7)가 아니라 **고치고 나서 눈으로 보는
사이클**. 결론부터: **배포는 필요 없고, 대부분의 경우 기기도 필요 없다.**

핵심은 `PeerTransport`가 라디오 경계라는 것이다. 매일 고치는 것 — 상태머신,
capability 협상, ranging 선택·폴백, Find UI, 코칭 문구, 백오프, Live Activity —
은 **전부 이 경계 위**에 있다. 라디오 자체(GATT 배선)는 거의 안 바뀐다.

## 0.1 iOS — `DevPeer`: 인프로세스 상대 (검증됨)

시뮬레이터에서 앱이 **자기 자신과 상대역을 동시에 연기**한다. 진짜
`SecureSession`, 진짜 IKpsk2 핸드셰이크, 진짜 CBOR, 진짜 상태머신. 가짜인 건
라디오 하나뿐이다.

iPhone 16 시뮬레이터에서 실제로 끝까지 돌려 확인한 흐름:

```
arm → discovery → IKpsk2 핸드셰이크 → capability 협상 → niPeer 선택
→ RSSI 램프 (far → near → veryNear)
→ UWB 스트림 8.00 m → 0.98 m, azimuth ±0.78 rad
→ 샘플 유실 → RSSI 폴백 → 전송 끊김 → signalLost → retryWait
→ 재핸드셰이크 → 사용자 중지 → 재arm
```

§11 `deviceName` 채택도 눈에 보이게 동작했다(People 행이 "상대 기기" →
"Dev Peer"로 스스로 개명). 기존 91개 테스트 전부 그대로 통과.

**변경량**: 기존 파일 3개에 **프로덕션 4줄** + `#if DEBUG`로 통째로 감싼
새 파일 1개(205줄).

프로토타입이 스크래치패드에 있고 `git apply --check` 통과를 확인했다(저장소에는
적용하지 않았다):
`<scratchpad>/dev-peer.patch` (3개 파일 diff) + `<scratchpad>/DevPeer.swift`.

```swift
// FindSessionCoordinator.swift
var readLocalCaps: () -> CapabilitySet = { LocalCapabilities.current() }   // :28 근처
var makeTransports: ((_ isInitiator: Bool) -> [PeerTransport])?            // :92 근처
localCaps = readLocalCaps()                                               // :146
let all: [PeerTransport] = makeTransports?(isInitiator) ?? [aware, nearby, ble]  // :196

// RangingCoordinator.swift
var simulatedRanging = false
func injectUWB(_ m: Measurement?) { handleUWB(measurement: m) }
```

이 주입 지점들은 **이미 이 파일에 있는 패턴**이다 — `var makeUpgradeTransport`
(:77), `var announce`(:34) 둘 다 "overridable in tests"로 이미 존재한다. 새로운
추상화가 아니라 기존 관례를 한 칸 넓히는 것이다.

**활성화**: `CC_DEV_PEER=1` 환경변수. 호출부 자체가 `#if DEBUG` 안에 있어
릴리스 빌드에는 코드가 존재하지 않는다.

```sh
# Xcode: Scheme ▸ Run ▸ Arguments ▸ Environment Variables ▸ CC_DEV_PEER = 1
SIMCTL_CHILD_CC_DEV_PEER=1 xcrun simctl launch --console-pty \
  --terminate-running-process booted com.cellularchat.app
```

`ios/CellularChat/`가 `PBXFileSystemSynchronizedRootGroup`이라 새 파일은
**프로젝트 파일 수정 없이** 타깃에 들어간다.

### 반나절 날릴 함정 하나

**`CODE_SIGNING_ALLOWED=NO`로 빌드하지 말 것.** `application-identifier`
entitlement가 벗겨져서 `DeviceKeyStore.swift:20`의 모든 Keychain 호출이
`OSStatus -34018`을 뱉고, `PairStore.swift:69`까지 연쇄로 실패해 페어 시딩이
안 된다. 그러면 arm이 `.radioUnavailable`로 죽는다. 시뮬레이터 한계가 아니라
**빌드 플래그 부작용**이다. 기본 ad-hoc 시뮬레이터 서명으로 빌드하면 정상이다
(양쪽 다 측정해서 확인).

### 재구현할 때 걸리는 것 3가지

1. **`onRecord` 설정 전 버퍼링이 필수다.** `RoleArbiter`가 로컬을 responder로
   정하면 상대 러너가 `makeTransports` 반환 직후 IKpsk2 message 1을 보내는데,
   로컬 러너는 `SessionRunner.start()`(:87)에서야 `onRecord`를 단다. 그 사이
   레코드가 유실되고 Find는 **에러도 로그도 없이** `authenticating`에 영원히
   앉는다. DEBUG 파일에 8줄이면 해결(`onRecord`의 `didSet`에서 flush).
   참고로 **기존 `LoopbackTransport`에는 이게 없다** — XCTest가 양쪽 러너를
   직접 start하기 때문에 우연히 사는 것뿐이다.
2. **`injectUWB(nil)`은 signalLost를 안 만든다.** `handleUWB(nil)`은
   `fallbackToRSSI()`를 타고 `proximity`가 실린 **non-nil** Measurement를
   publish한다(`RangingCoordinator.swift:309-312`). signalLost → retryWait →
   재핸드셰이크로 가는 유일한 경로는 `disconnect(reason: .transportLost)`다.
3. **`simulatedRanging = true`면 `ni_token`(msgType 23)이 안 나간다.**
   `peerRanger.start`가 안 도니 `onSendToken`도 안 뜬다. 즉 커밋 `5d106c9`의
   iOS-보냄/Android-안보냄 비대칭은 **이 모드로 안 덮인다.** 덮였다고 착각하지 말 것.

## 0.2 Android — 반대 결론: 가짜를 만들지 마라

같은 전략을 양쪽에 적용하면 한쪽이 틀린다. **iOS 시뮬레이터는 라디오가 0이라
가짜가 유일한 선택지고, Android 에뮬레이터는 진짜 가상 BLE 라디오가 있어서
가짜를 만들면 공짜로 얻는 걸 버리는 것이다.**

Android 내부 루프 = **에뮬레이터 2대 띄우고 맥 화면에서 양쪽 UI를 나란히 보기.**
§3의 검증된 레인 그대로다. 다만 지금 이대로는 반쪽만 동작한다:

- `FindController.kt:244` — `(won.transport as? BleGattCentral)?.onRssi = ...`
  구체 클래스 캐스트. **central 역할 기기에서만 RSSI가 흐른다.**
  peripheral 역할 기기의 Find UI는 밴드도 추세도 햅틱도 없이
  `RANGING_STARTING`에 멈춰 있다. 그리고 역할은 정적 키 바이트 비교로
  결정되므로(`TransportCandidateFactory.kt:43`) **같은 페어면 항상 같은 폰이
  당한다.** → 3줄짜리 ranging 훅 하나가 필요하다.
- `TransportCandidateFactory.candidates(context, pair)`는 Kotlin `object`의
  **정적 호출**이다(`FindController.kt:172`). 주입 지점이 아예 없다.
  (참고: iOS와 달리 Android는 CapabilitySet 오버라이드 문제가 이미 해결돼 있다.)
- `android/app/src/`에 **`debug` 소스셋이 없다**(`androidTest`도 없다).
- `google_apis_playstore` 이미지라 `nearby` 후보가 available로 보고되어
  **매 arbitration 패스마다 4초를 태운다** — 에뮬레이터엔 Wi-Fi Direct가 없어
  성공할 수 없는 시도인데도. 개발 중엔 이게 체감 지연으로 온다.
- `TransportCoordinator.kt:21` — BLE의 arbitration 타임아웃이 0이라
  `BleGattCentral.start()`가 성공했는데 상대가 안 나타나면 후보가 영원히
  안 끝나고 `onArbitrationExhausted`가 안 돈다. **개발 중엔 그냥 멈춘 걸로 보인다.**

## 0.3 iOS 앱 ↔ Android 앱을 내부 루프에서 보려면

시뮬레이터엔 BLE가 없고 에뮬레이터는 시뮬레이터에 닿을 수 없다. 둘의 가짜
transport를 TCP로 잇는 게 유일한 길이고, 그건 §4의 크로스언어 interop
하네스와 같은 것이다. **증명되는 것**: 와이어 계약, 협상, 상태머신 상호작용.
**증명 안 되는 것**: 라디오 전부.

## 0.4 이 모드가 절대 못 잡는 것 (과신 금지)

`PeerTransport`는 "온전하고 순서 있는 레코드"를 **약속**한다. 가짜 transport는
**그 약속이 지켜진다는 전제 하에** 위쪽 코드가 옳음을 증명한다. 실제 라디오
버그는 전부 "약속이 안 지켜진다"에 산다.

- **랑데부 자체가 경계 아래다.** `localToken`/`acceptsPeerToken`은
  `PeerTransport` 프로토콜이 아니라 `BLETransport` **생성자 인자**다
  (`BLETransport.swift:64-73`). 가짜는 이미 연결된 상대를 받으므로
  `Discovery.token`/`accepts`가 **통째로 건너뛰어진다** — 즉 "두 폰이 실제로
  서로를 찾는가"라는 앱의 존재 이유가 안 돌아간다. 특히 양쪽이 벽시계를 읽고
  epoch 120초에 {e-1,e,e+1} 수용이므로 **시계 오차 ±2분을 넘으면 라디오가
  멀쩡해도 영원히 못 만난다.** 인프로세스에선 시계가 하나라 절대 안 보인다.
- **역압(backpressure)·조각 유실.** 가짜의 `send`는 무조건 성공한다. 실제
  peripheral 쪽은 그렇지 않다(§0.5 버그 2·3).
- **CapabilitySet 오버라이드가 그 자체로 사각지대를 만든다.** CapabilitySet은
  인증되고 **바이트 비교**된다(§14, `FindSessionCoordinator.swift:28`). dev
  모드는 와이어와 Noise transcript의 바이트를 바꾼다. → 실제 하드웨어 차이에
  의존하는 협상 경로(진짜 UWB 없는 아이폰 vs 있는 아이폰, EDM 비대칭,
  26.1 interop 게이트)는 도달 불가이고, **dev 모드가 초록인데 실기기가 깨질 수
  있다.**
- **UWB/NearbyInteraction은 어떤 레벨에서도 대체 불가.** `NIDiscoveryToken`
  아카이브 왕복, EDM 협상, 카메라 어시스트(ARSession이 NI 세션을 무효화할 수
  있음), 48바이트 `apple_config` → 35바이트 `apple_shareable` interop 체인,
  `sessionWasSuspended`, 모든 `NIError` 분기. 특히 `NIAngle.swift:17-20`의
  "direction 먼저 읽고 horizontalAngle 폴백"은 **iOS 18 아이폰 두 대가 거리는
  나오는데 화살표가 안 뜨던 문제**의 수정이다 — 순수 기하 버그라 실기기에서만 난다.
- **백그라운드 실행.** 기본 세션이 30분인데 포그라운드 시뮬레이터는 서스펜드가
  없다. `FindLiveActivityController`의 `Timer`, RSSI/stall `DispatchSourceTimer`,
  백오프 `Task.sleep`, 5초 업그레이드 타이머 — 전부 안 돈다. 게다가
  `backgroundRanging`은 "no runtime probe; kept conservative" 주석과 함께
  하드코딩 `false`다. 커밋 `fd556f3`(Live Activity 어긋남)이 이 클래스에서 이미
  한 번 물렸다.
- **권한 상태머신.** denied / restricted / 아직 안 물음 / 켰다가 세션 중 끔.
  시뮬레이터 CoreBluetooth는 `.unsupported`라 `.radioUnavailable`로만 매핑된다.
- **Wi-Fi Aware는 모든 축에서 미검증.** `WiFiAwareTransport.swift:22-24`의 주석이
  이미 열린 게이트를 자백한다 — iOS는 RFC6763 표기 `_cellfind._udp`, Android는
  맨 이름 `cellfind`. 가짜가 `.wifiAware`로 "이겼다"고 하면 **실하드웨어에서
  단 1바이트도 나른 적 없는 경로 위에서 §10 업그레이드 상태머신을 돌리게 된다.**
- **에뮬레이터 2대 초록도 증거가 아니다.** netsim/RootCanal은 경로손실·간섭·
  체차폐·현실적 ATT 버퍼 압력이 없고 MTU를 517로 보고한다. 핸드셰이크
  레코드가 **1조각**이라 다조각 영역에 아예 안 들어간다 — 아래 버그 2·3이
  물리는 바로 그 영역이다.

## 0.5 그리고 — 지금 코드에 이미 있는 버그 5개

내부 루프를 조사하다 나왔다. **전부 소스에서 직접 확인했다.** 이것들이
"현재 프로세스로는 못 잡는다"의 가장 강한 근거다.

1. **한 번의 연결 시도 안에서 iOS central이 두 번째 후보를 절대 시도하지 않는다.**
   `scanForPeripherals`는 `centralManagerDidUpdateState`의 `.poweredOn`에서
   **딱 한 번**만 호출된다(`BLETransport.swift:212`). `didDiscover`(:228)는
   무조건 `stopScan()`을 하고, 랑데부 읽기가 토큰을 거절하면(:273-277)
   `cancelPeripheralConnection` + `finishConnect(.failure)`로 끝난다 —
   **그 인스턴스에는 다음 후보가 없다.** 게다가 iOS peripheral은 service data를
   광고할 수 없으므로(:317-320) 상대가 아이폰이면 :223-227의 사전 검증 분기는
   **항상 건너뛰어지고** 서비스 UUID를 광고하는 첫 기기에 무조건 붙는다.

   정확한 심각도: 재시도는 `beginSearch`가 `BLETransport`를 새로 만들므로
   `CBCentralManager`도 새것이고 **스캔 자체는 다시 열린다.** 영구 고장은
   아니다. 하지만 거절한 상대를 기억하지 않으므로, 같은 앱을 쓰는 제3의 기기가
   근처에 있으면 **매 시도가 그 기기에서 소진되고 같은 선택을 반복한다** —
   백오프 5→10→20→40→60초를 태우며 굶는다. 내 페어 두 대가 멀쩡히 광고
   중이어도 그렇다.
2. **iOS peripheral이 `updateValue`의 반환값을 버린다.**
   `BLETransport.swift:192`. `peripheralManagerIsReadyToUpdateSubscribers`는
   저장소 어디에도 없다(확인함). 전송 큐가 차면 조각이 조용히 버려지고 상대
   재조립이 10초 §9 예산까지 멈췄다가 `.protocolError`로 죽는다. **사용자에겐
   "범위를 벗어남"으로 보인다.**
3. **Android peripheral도 같은 문제.** `BleGattPeripheral.kt:213-216`이
   `notifyCharacteristicChanged`를 루프로 쏘는데 `onNotificationSent` 오버라이드가
   없다. 대조적으로 **central 쪽은 양 플랫폼 다 올바르다** — iOS는 `.withResponse`
   쓰기(큐잉됨), Android는 `drainWrites`로 직렬화. **peripheral 절반만 안 됐다.**
   → 정적 키 타이브레이크에서 진 쪽 폰에만 터진다.
4. **iOS peripheral 경로는 토큰 검증이 아예 없다.**
   `peripheralManager(_:central:didSubscribeTo:)`(:344-348)가 검증 없이
   `finishConnect(.success)`를 부른다. central 경로는 `maybeFinishConnect`로
   `tokenVerified && inboxChar != nil && notifyReady`를 요구하는데(:139-142)
   peripheral 경로는 아무것도 요구하지 않는다. `subscribedCentral`이 슬롯
   하나라 두 번째 central이 구독하면 덮어써져서 이후 모든 `updateValue`가
   엉뚱한 기기로 간다. Noise IKpsk2가 사칭은 막지만 **가용성은 안 지켜진다.**
5. **BLE 전용 페어에서 peripheral 역할 기기는 근접 밴드를 영원히 못 본다.**
   RSSI 소스가 central 전용이다(iOS `BLETransport.swift:131`,
   Android `FindController.kt:244`). GATT 서버에는 링크 RSSI API가 없다.
   역할이 정적 키에서 결정되므로 **같은 폰이 항상 빈 화면**을 본다.
   §12 폴백이 안전망인데 한쪽에서 안전망이 없다.

1·4·5는 **두 폰을 나란히 놓고 양쪽 화면을 다 봐야** 보인다. 2·3은
**낮은 MTU + 다조각**에서만 터지므로 에뮬레이터(MTU 517)로도 안 잡힌다.

---

## 1. 레이어별 가상화 가능성

| 레이어 | 가상화 | 현재 상태 |
|---|---|---|
| L0 프로토콜 로직 vs 고정 픽스처 | ✅ | **완료** (공유 벡터 12종, 양 플랫폼) |
| L1 앱 레이어 로직 (전송 중재·RSSI 필터·역할 결정) | ✅ 시뮬레이터 | **완료** (91개 테스트, 20초) |
| L2 살아있는 2자 크로스언어 interop | ✅ 소켓 | **없음** ← 만들 것 |
| L3 Android BLE GATT 실제 라디오 스택 | ✅ 에뮬 2대 | **없음** ← 최대 수확 |
| L4 전송 열화(MTU·손실·재정렬·중간 끊김) | ✅ seam 데코레이터 | **없음** |
| L5 iOS BLE / NearbyInteraction | ❌ | 실기기 전용 |
| L6 Wi-Fi Aware (양 플랫폼) | ❌ | 실기기 전용 |
| L7 물리 (거리·벽·각도·배터리·처리량) | ❌ | 실기기 전용 |

지금 L0·L1만 있고 L2·L3·L4가 통째로 비어 있다. TestFlight까지 가야만 알 수
있던 버그들은 대부분 L2·L3 구멍에서 나온 것들이다.

---

## 2. 지금 당장 — TestFlight 루프를 끊어라 (검증됨)

가장 큰 착각부터: **Wi-Fi Aware entitlement는 Development 프로파일로 발급된다.**
배포 프로파일도, 애플에 요청하는 절차도 필요 없다.

이 저장소에서 실제로 확인한 것:

```sh
xcodebuild build-for-testing -project ios/CellularChat.xcodeproj \
  -scheme CellularChat -destination 'generic/platform=iOS' \
  DEVELOPMENT_TEAM=Q27VK9D5N7 -allowProvisioningUpdates
```

→ Xcode가 `iOS Team Provisioning Profile: com.cellularchat.app`를 즉석에서
발급했고, `security cms -D`로 뜯어보니:

```
com.apple.developer.wifi-aware = [Publish, Subscribe]
get-task-allow = true
ProvisionedDevices = 4개 UDID
```

`codesign -d --entitlements -`로 빌드된 .app에도 실제로 적용됨을 확인했다.

### 설치·실행·로그 회수

```sh
# UDID는 반드시 --json-output의 hardwareProperties.udid에서 뽑을 것.
# 기본 테이블에 보이는 identifier는 CoreDevice UUID로 다른 값이고,
# xcodebuild -destination id= 에는 UDID가 필요하다.
xcrun devicectl list devices --json-output /tmp/dev.json

xcrun devicectl device install app --device <UDID> path/to/CellularChat.app
DEVICECTL_CHILD_FINDER_ROLE=publisher \
  xcrun devicectl device process launch --console --terminate-existing \
  --device <UDID> com.cellularchat.app

# os_log 회수 (log stream에는 --device 플래그가 없다. collect만 있다)
log collect --device-udid <UDID> --last 5m --output run.logarchive
log show --archive run.logarchive --predicate 'subsystem == "com.cellularchat.app"' --info --debug

# 크래시 로그
xcrun devicectl device copy from --device <UDID> --domain-type systemCrashLogs ...
```

이미 이 맥의 아이폰은 `connectionProperties.transportType = localNetwork`,
`pairingState = paired` 상태다. 즉 **케이블 없이 이미 동작한다.** 최초 1회만
Xcode GUI에서 "Connect via network"를 켜면 그 뒤로는 전부 무선이다.

### 지금 이걸 막고 있는 것 — 한 줄 수정

```
error: Signing for "CellularChatTests" requires a development team.
```

`CellularChatTests` 타깃에 `DEVELOPMENT_TEAM`이 없다. entitlement 문제가
아니라 **이것 하나가** 실기기 `xcodebuild test`를 막고 있다.
`project.pbxproj`의 테스트 빌드 설정 두 곳에 `DEVELOPMENT_TEAM = Q27VK9D5N7;`
추가하면 끝이다(임시로는 커맨드라인에 넘겨도 된다).

그리고 좋은 소식 하나: 이 프로젝트의 XCTest 번들은 **app-hosted**다
(`IsAppHostedTestBundle => true`, `TestHostBundleIdentifier => com.cellularchat.app`).
테스트가 entitlement를 가진 앱 프로세스 안에서 돌기 때문에 **hosted unit test에서
Wi-Fi Aware / NearbyInteraction API를 진짜로 호출할 수 있다.** 2폰 통합 테스트는
XCUITest가 아니라 `CellularChatTests`에 넣어야 하는 이유다(XCUITest 러너는
별도 번들 ID라 자체 프로파일이 필요해진다).

### 다만 — 지금 폰으로는 Wi-Fi Aware를 못 본다

이 맥이 아는 아이폰은 **한 대**뿐이고 **iOS 18.4.1**이다. Wi-Fi Aware는
iOS 26.0+ 전용이다. 즉:

- 지금 폰으로 검증 가능: BLE, NearbyInteraction(iPhone↔iPhone UWB), 페어링
- 불가능: Wi-Fi Aware — **iOS 26 폰이 2대** 있어야 한다

Wi-Fi Aware는 유료 개발자 프로그램 팀 필수(무료 계정 불가). NearbyInteraction은
entitlement 자체가 없고 `NSNearbyInteractionUsageDescription` 키만 있으면 된다 —
이건 쫓아다닐 필요 없다.

---

## 3. Android 에뮬레이터 2대로 BLE 전체 랑데부 (검증됨, 최대 수확)

이번 조사에서 가장 큰 발견. **실제로 APK를 만들어 에뮬레이터 두 대에 올리고
돌려봤고, 완전한 BLE GATT 랑데부가 약 1초 만에 성공했다.**

```
중앙(central) logcat:
  SCAN HIT (filter matched) dev=54:81:10:9E:3E:EE rssi=-8 serviceData=ABCD01020304
  CLI MTU NEGOTIATED = 517
```

성공한 항목: service data를 실은 advertise, 서비스 UUID ScanFilter 스캔,
GATT connect, MTU 협상(517), characteristic write, notify 구독. 400바이트
write가 MTU 517로 정상 도달 — **L2CAP 단편화도 기능적으로 맞다.**

동작 원리: 에뮬레이터가 자동으로 `netsimd`(0.3.47, 내부에 RootCanal 가상
컨트롤러)를 띄우고, **한 호스트의 모든 에뮬레이터가 이 하나의 netsimd에
붙어 하나의 가상 전파 매질을 공유한다.** `netsim_session_stats.json`에서
device 1의 tx 536 / rx 306 과 device 6의 tx 306 / rx 536 이 정확히 거울상인
것으로 확인했다.

**범위를 정확히**: 이 검증은 랑데부 시퀀스를 그대로 재현한 **전용 테스트
APK**로 한 것이고, CellularChat 앱 자체의 트래픽이 netsim 매질을 건너는 것은
깨끗하게 입증되지 않았다(측정 중간에 제3의 에뮬레이터가 붙어 귀속이 오염됐다).
앱 경로는 회전 토큰, acceptance set, `BleFragmentChannel`, minSdk 36 런타임
권한, 페어링을 시작시키는 UI가 더 얹힌다. **닫는 데 10분**: 에뮬레이터를
정확히 2대만 띄우고 앱을 설치·실행한 뒤 `netsim_session_stats.json`을 diff하거나
`netsimd --pcap`으로 캡처하면 된다.

앱이 실제로 쓰는 API와 harness가 쓴 API가 동일함도 확인했다 —
`BleGattPeripheral.kt:120`의 `AdvertiseSettings.Builder()` +
`.addServiceData(ParcelUuid(SERVICE_UUID), token)` + `startAdvertising(...)`,
그리고 `BleGattCentral.kt`의 `BluetoothLeScanner`/`connectGatt`. **API 모양
차이로 인한 함정은 없다.** ni_token을 service data에 실어 보내는 랑데부 방식이
그대로 테스트된다.

컨트롤러 능력치도 넉넉하다(HCI 5.3, advertising set 16개, advertising data
512바이트, extended advertising, 2M/Coded PHY). BLE 에뮬레이션을 다른 곳에서
죽이는 게 보통 peripheral 역할인데 **여기서는 통과한다.**

### CI에서 돌리는 법

```sh
# 두 인스턴스를 같은 AVD에서 띄우려면 -read-only 필수
emulator -avd <AVD> -read-only -no-window -no-audio -no-snapshot \
  -no-boot-anim -gpu swiftshader_indirect &
emulator -avd <AVD> -read-only -no-window ... -port 5556 &

# CI 사전조건 1: netsimd가 정확히 1개여야 한다.
# 각 에뮬레이터가 자기 netsimd를 띄우면 개별적으론 멀쩡해 보이는데
# 서로가 안 보인다 — 가장 빠지기 쉬운 함정.
[ "$(ps ax | grep -c '[n]etsimd')" = 1 ] || exit 1

# CI 사전조건 2: 에뮬레이터가 정확히 2대여야 한다.
# 매질이 공유되므로 제3의 인스턴스가 붙으면 광고가 섞이고 통계 귀속이
# 깨진다 — 이번 조사에서 실제로 측정 하나를 오염시킨 게 이거다.
[ "$(adb devices | grep -c '^emulator-')" = 2 ] || exit 1

# 권한은 헤드리스로 부여 가능
adb -s emulator-5554 shell pm grant com.cellularchat.app android.permission.BLUETOOTH_SCAN
```

병렬 CI 잡은 서로의 advertise가 보이므로 잡별로 다른 서비스 UUID를 쓰거나
`netsimd -i/--instance <N>`으로 격리할 것. 패킷 캡처는 `netsimd --pcap`.

### 선결 조건

`android/app/src/` 아래에 `androidTest` 소스셋이 **아예 없다**. `build.gradle.kts`에
`testInstrumentationRunner`는 이미 선언돼 있으니 소스셋과 androidx.test 의존성만
추가하면 된다. 참고로 조사 중 확인한 바로는 Gradle 없이 build-tools만으로도
(javac → aapt2 → d8 → apksigner) 수초 만에 테스트용 APK를 만들 수 있다 —
라디오 테스트를 앱의 Gradle 빌드와 엮지 않는 선택지도 있다.

### 에뮬레이터로 안 되는 것 (측정으로 확인)

- **Wi-Fi Aware/NAN: 완전 부재.** `pm has-feature android.hardware.wifi.aware` → false,
  `dumpsys wifiaware` → "Can't find service". `advancedFeatures.ini`의
  `WiFiPacketStream`은 netsim의 가상 Wi-Fi 백플레인이지 Aware HAL이 아니다 —
  켜도 NAN은 안 생긴다.
- **Wi-Fi Direct: 선언은 돼 있지만 그룹 형성 불가.** `dumpsys wifip2p`가
  `P2pDisabledState`, `p2p0` 인터페이스 없음. → **Nearby Connections의
  Wi-Fi 승격 경로는 에뮬레이터에서 형성되지 않는다.** BLE 전용으로만 동작.
- **RSSI가 -8 dBm 고정.** 3회 실행 6개 스캔 결과 전부 동일. → **근접 밴드와
  RSSI 추세 로직은 에뮬레이터로 못 돌린다.** 합성 샘플 스트림 단위테스트에
  남겨야 한다(§5 참조). netsim에 위치 모델은 있지만 이를 조작하려면
  포트 6402의 gRPC `FrontendService` 클라이언트를 직접 짜야 한다.
- **UWB: 실제로 레인징이 된다.** ← 초판의 "레인징 세션은 한 번도 열린 적
  없다"는 **틀렸다.** 재조사에서 에뮬레이터 2대(Pixel_3 + flutter_emulator,
  둘 다 android-36, `pgrep netsimd == 1`, 특별한 플래그 없음)로
  `cmd uwb start-fira-ranging-session`을 controlee → controller 순으로 열어
  **양방향 리포트가 ~200 ms 주기로 흘렀다**(remote 0x07D0 / 0x03E8, status 0).
  netsimd 안의 Pica(구글 가상 UWB 컨트롤러)가 실제로 동작한다.
  → **UWB 세션 수명주기(open/start/active/SessionInfoNtf/stop/재시도/실패
  경로)가 하드웨어 없이 CI에서 돈다.** 자세한 건 §13.
- **처리량·지연·배터리 숫자는 무의미하다.** `le_acl_data_packet_length: 27`,
  Data Length Extension이 degenerate 상태다. 기능적 정확성만 믿을 것.

---

## 4. Swift↔Kotlin 라이브 interop 하네스

공유 벡터는 **고정 픽스처에 대한 바이트 일치**를 증명한다. 증명하지 못하는 것:
누가 먼저 말하는가, 실제 교환 위에서의 capability 협상, 흐름 중간의 상태머신
발산, `session_ready` 순서. **지금 TestFlight에서만 드러나는 버그가 정확히
이 클래스다.**

이게 이론이 아니라는 증거가 저장소 안에 있다. 커밋 `5d106c9` — iOS
RangingCoordinator는 시도마다 `ni_token`(msgType 23)을 보내는데 Android는 절대
안 보낸다. 게다가 보낼지 말지가 로컬 UWB 라디오 상태에 달려 있어 **공유
픽스처로는 표현이 불가능**했고, 결국 `duplicate_ops.json`을 좁히는 것으로
해결했다. 라이브 하네스라면 그냥 잡혔을 문제다.

### 만드는 법 — 소스 추출 불필요

양쪽 다 **이미 실행 가능한 테스트 프로세스 안에 seam이 있다**:

- `ios/CellularChatTests/SessionRunnerLoopbackTests.swift`의 `LoopbackTransport`가
  이미 `PeerTransport`를 구현해 두 `SessionRunner`를 진짜 IKpsk2 핸드셰이크로 연결한다
- `android/app/src/test/.../SecureSessionRunnerTest.kt`가 이미 JVM에서 같은 일을 한다

즉 **`LoopbackTransport`를 소켓 기반 `PeerTransport`로 갈아끼우면 끝이다.**
`SecureSession`의 API가 이미 record-in/record-out이라 딱 맞는다
(`writeHandshake()` / `readHandshake(_:)` / `send(msgType:body:)` / `receive(_:)`).

와이어는 TCP + 4바이트 길이 프리픽스 = `PeerTransport`의 레코드 경계 그대로.
양쪽 각각 30줄 수준.

**시나리오는 `shared/vectors/`에서 구동할 것.** 새로 손으로 쓴 스크립트로
구동하면 하네스가 "진실의 세 번째 구현"이 되고, 결국 하네스를 디버깅하게 된다.

### 하지 말 것

- **SwiftPM 실행 타깃 만들기**: 실제로 컴파일해보고 확인한 구체적 블로커 3개 —
  `SessionRunner.swift:91`의 `transport as? BLETransport`(CoreBluetooth 클래스에
  직접 결합), `PairStore.swift:144`의 iOS 전용
  `.completeFileProtectionUntilFirstUserAuthentication`, 그리고
  `LocalCapabilities`가 `WAPairedDevice`를 참조하는 `SystemPairObserver.swift`에
  들어 있는 것. 푸는 데 반나절. **in-test 경로는 0원이다.**
- **`kotlin("jvm")` 모듈 추가**: 불필요. 기존 `testDebugUnitTest`가 이미 순수 JVM
  프로세스이고 코어가 classpath에 있다. 코어는 `java.*`/`javax.crypto`만
  import한다 — `android.*` 의존이 0이다.

### 필요한 것 하나

오케스트레이터. `swift test`도 `gradle test`도 서로를 spawn할 수 없다.
포트를 열고 `xcodebuild test`와 `./gradlew testDebugUnitTest`를 각각 env로
포트를 넘겨 띄운 뒤 양쪽 exit code를 모으는 셸/파이썬 100줄.

선행 사례: BoringSSL의 `ssl/test` runner+shim 계약
(`PORTING.md` — runner가 서버 소켓을 열고 shim이 TCP 클라이언트로 붙음,
"구현 안 함"용 별도 exit code), 그리고 QUIC Interop Runner(구현별 표준
엔트리포인트, unsupported는 exit 127, **열화 계층은 별도 컴포넌트**).
**"미지원" 전용 exit code 관례는 꼭 가져올 것** — ni_token 비대칭이 정확히
그걸 필요로 한다.

### 정직한 비용

libsignal은 이 문제를 **크로스언어 interop을 안 하는 것**으로 푼다 — Rust 코어
하나에 FFI/JNI 브릿지. 다시 쓰라는 말이 아니라, **두 구현을 유지하는 한
interop 하네스는 일회성 구축이 아니라 영구적 반복 비용**이라는 걸 알고
시작하라는 뜻이다.

---

## 5. 열화 주입 (L4)

`PeerTransport` 위의 데코레이터: 시드 고정 RNG, MTU(20/23/185/517), 손실률,
재정렬, 중복, 핸드셰이크 중간 끊김. 결정적이므로 **실패한 시드가 곧 재현
가능한 버그 리포트**가 된다.

기성품은 없다(Swift/Kotlin용 in-process 시드 결정적 열화 라이브러리 검색 결과
하드웨어 장비와 tc/netem 기사만 나옴). tc/netem·Toxiproxy는 out-of-process에
시드 결정적이지 않아 모양이 안 맞는다. ~100줄 직접 구현이 맞다.

**주의**: `send()`가 양쪽 다 동기라서 데코레이터가 지연·재정렬용 스케줄러를
직접 소유해야 한다. 그리고 양쪽 코드가 벽시계를 직접 읽는다
(`BLETransport.swift:298`의 `Date().timeIntervalSince1970`, DispatchSource
타이머들, `FindSessionCoordinator.swift`의 4곳, Android도 동일). **시계 주입은
라이브러리 추가가 아니라 양쪽 코드 수정이다.** 열화 데코레이터와 같이 해야
한다 — 어느 쪽이든 벽시계로 자면 2프로세스 하네스의 결정성이 깨진다.

---

## 6. 한 번 캡처해서 영원히 재생 + 관측성

### 먼저: 앱에 로그가 하나도 없다

`ios/CellularChat` 전체에 OSLog/Logger/os_signpost가 0개, `android/app/src/main`에
`android.util.Log`가 0개, 양쪽 main 소스에 print/println도 0개다.

**순서가 중요하다.** 아래 도구는 전부 "읽는 쪽"이고 지금은 읽을 게 없다.
iOS에 Logger subsystem 하나, Android에 Log 태그 하나 먼저 넣어야 나머지가
의미를 갖는다.

### RSSI/NI 트레이스 재생

`RSSIProximityFilter`는 이미 타임스탬프를 파라미터로 받는다:

- Swift `add(rssi: Double, at timestamp: TimeInterval = Date()...)`
- Kotlin `update(rssiDb: Int, atMillis: Long = System.currentTimeMillis())`

→ **재생은 파싱한 JSON 위의 for 루프다. 추상화를 만들지 말 것.**
단, 순수 함수가 아니라 상태를 가진 객체이므로 재생 루프가 (a) 생성자 인자를
고정하고 (b) 픽스처 사이에 `reset()`을 호출해야 한다. 두 줄이다.

픽스처는 정수 ms + 정수 dBm으로 기록할 것(Kotlin API가 `Long`/`Int`를 받고,
라디오가 실제로 주는 것도 정수다). 그리고 **밴드만 기록하면 안 된다** —
`trend`와 `trendConfidence`는 별도 프로퍼티이고 confidence가 UI 라벨을
게이팅한다. 밴드만 기록한 픽스처는 커밋 `2bd4150`이 고친 바로 그 버그 클래스를
놓친다. 타임스탬프는 반드시 페이로드에 실린 값을 쓸 것 — 로그 프레임워크의
발신 시각으로 복원하면 갭 리셋(>10초)과 최소 구간(3초/6초) 판정이 재현되지
않는다(코드 주석이 이미 경고하고 있다).

### 무선 구간 패킷 캡처

- **Android HCI snoop**: 개발자 옵션에서 켜고 `adb bugreport`로 받아 zip 안에서
  찾는다. `/sdcard/btsnoop_hci.log`는 낡은 경로다(실경로는
  `/data/misc/bluetooth/logs/btsnoop_hci.log`, 루트 없이는 `adb pull` 불가.
  단 일부 빌드에서는 됨 — `adb shell cat /etc/bluetooth/bt_stack.conf | grep BtSnoop`로
  설정된 경로 확인 가능). 포트 8872 라이브 스트리밍은 2015년에 죽었다.
- **iOS**: Additional Tools for Xcode의 PacketLogger + 애플 Bluetooth 로깅 프로파일.
- **한계**: HCI는 블루투스 전용이다. Wi-Fi Aware 데이터 경로와 UWB 레인징
  프레임은 **안 보인다.** 다만 Nearby Connections와 Wi-Fi Aware의 **BLE
  부트스트랩 구간은 보인다** — 랑데부 실패는 대개 거기서 나므로 쓸모가 있다.

### UWB 관측성

- Android: `cmd uwb`, `dumpsys uwb`. 삼성 기기는 추가로
  `cmd samsunguwb_aosp enable-diagnostics-notification`(`-r` RSSI, `-a` AoA,
  `-c` CIR) 및 `dumpsys samsunguwb*` — 레인징 앱에는 실제로 유용하다.
- iOS: `dumpsys` 등가물은 없다. 다만 애플이 전용
  `NearbyInteractionLogging.mobileconfig`를 배포한다
  (developer.apple.com/bug-reporting/profiles-and-logs/) — UWB 실패는 이걸
  먼저 시도할 것. 그 외에는 NISession invalidation reason 뿐이다.
- AOSP UWB 문서에는 디버깅 가이드가 **전혀 없다**(확인함). 여긴 원래 척박하다.

### 크래시 생존 로그

`OSLogStore`는 `.currentProcessIdentifier`만 지원한다 — **직전 실행의 로그를
못 읽는다. 크래시 생존 수단이 아니다.** 앱 컨테이너에 파일로 쓰고
`devicectl device copy from`으로 빼야 하는데, **이건 development 서명
설치에서만 된다.** TestFlight 빌드는 Xcode 툴링으로 컨테이너 접근이 안 된다
(애플 DTS 공식 답변). → 진단 루프는 §2의 직접 설치로 가야 하는 또 하나의 이유.

---

## 7. CI가 없다

`.github`가 없다. 100개 넘는 테스트 파일이 두 플랫폼에 있는데 전부 수동이다.
이번에 실측한 실행 시간:

| 레인 | 테스트 | 시간 |
|---|---|---|
| `swift test` (코어) | 35 | 2.2초 |
| `./gradlew :app:testDebugUnitTest` | 150 | 1.4초 |
| `xcodebuild test` (iOS 26.2 시뮬) | 91 | 20초 (빌드 포함) |

전부 서명 없이, 기기 없이 돈다. **위의 어떤 하네스를 만들든 CI에 올라가지
않으면 "돌리는 걸 기억해야 하는 또 하나의 것"이 될 뿐이다.** yaml 하나가
나머지 전부의 배수기다.

비용 주의: iOS 레인은 `swift test`까지 포함해 전부 macOS 러너가 필요하다
(코어가 CryptoKit을 쓴다). 호스티드 CI에서 macOS는 리눅스보다 몇 배 비싼
등급이다 — 실행 시간은 30초라도 청구서는 그렇게 안 읽힌다. Android 레인만
리눅스로 분리하면 대부분의 커밋에서 macOS 러너를 안 깨워도 된다.

---

## 8. 물리적으로 불가능한 것 (정직한 목록)

여기에 시간 쓰지 말 것. 전부 확인했다.

- **iOS 시뮬레이터 CoreBluetooth**: `CBCentralManager`·`CBPeripheralManager`
  둘 다 `state = .unsupported`(raw 2). 시뮬레이터 SDK로 프로브를 컴파일해
  `simctl spawn`으로 실행해 재현했다.
- **iOS 시뮬레이터 Wi-Fi Aware**: 앱을 시뮬레이터에서 띄우자 첫 로그가
  `(WiFiPeerToPeer) [WARNING]: The simulator does not support peer to peer Wi-Fi.
  All calls to this API will return errors.` 였다.
- **ImpossiBLE**(시뮬레이터용 CoreBluetooth 프록시): `CBCentralManager`만
  스위즐하고 peripheral/broadcaster 역할은 명시적으로 미지원. 이 앱은
  `BLETransport.swift:37-38`에서 양쪽 역할을 다 쓴다 → 탈락.
- **Android 에뮬레이터 Wi-Fi Aware**: 기능·서비스·HAL 전부 부재.
- **Bumble의 `android-netsim:localhost:8877`**: 이 머신에서 8877은 아무도 안 듣는다.
  `tcp-client:127.0.0.1:6402`를 써야 한다(동작 확인함). 참고로 Bumble의
  android-netsim 트랜스포트는 Android 16/API 36에서 netsimd가 chip_kind UWB를
  먼저 돌려줘 오연결되는 미해결 이슈가 있다.
- **BlueZ btvirt/vhci**: 리눅스 커널 전용. darwin에서 죽음.
- **Gradle Managed Devices**: 로컬은 가상 기기만, 물리 기기는 Firebase Test Lab —
  원격 데이터센터라 두 기기가 물리적으로 붙어 있지 않다. 근접성 테스트에 무의미.
- **`xcodebuild test -destination A -destination B`로 두 폰에 다른 역할 주기**:
  destination별 환경변수/인자 오버라이드가 없다. 불가능.

---

## 9. 2폰 자동화 — 실제로 지원되는 형태

애플이 두 기기를 하나의 테스트로 조율하는 방법은 **제공하지 않는다.**
LAN 랑데부 패턴은 직접 만드는 것이다. 다만 조각들은 1급 기능이다:

```sh
# 1) 한 번만 빌드
xcodebuild build-for-testing -scheme CellularChat \
  -destination 'generic/platform=iOS' -derivedDataPath dd \
  DEVELOPMENT_TEAM=Q27VK9D5N7 -allowProvisioningUpdates

# 2) .xctestrun을 복사해 역할별 EnvironmentVariables를 심는다
#    (EnvironmentVariables는 man 5 xcodebuild.xctestrun의 정식 필드다)
plutil -insert ...EnvironmentVariables.FINDER_ROLE -string publisher role-a.xctestrun

# 3) 두 번 병렬 실행
xcodebuild test-without-building -xctestrun role-a.xctestrun \
  -destination "platform=iOS,id=$UDID_A" -resultBundlePath runA.xcresult &
xcodebuild test-without-building -xctestrun role-b.xctestrun \
  -destination "platform=iOS,id=$UDID_B" -resultBundlePath runB.xcresult &
wait

# 4) 결과 집계
xcrun xcresulttool get test-results summary --path runA.xcresult
```

테스트 안에서는 `ProcessInfo.processInfo.environment`로 역할을 읽는다.
직접 만들어야 하는 건 **랑데부 배리어 서버(맥에서 도는 TCP 100줄)** 뿐이다.

Android는 더 쉽다 — 역할 인자가 네이티브로 지원된다:

```sh
adb -s <A> shell am instrument -w -e role publisher -e rendezvous 192.168.1.10:9000 \
  com.cellularchat.app.test/androidx.test.runner.AndroidJUnitRunner
```

`./gradlew connectedAndroidTest`는 **모든** 연결 기기에 **같은** 스위트를 돌리므로
2역할 페어링 테스트에는 모양이 안 맞는다. `ANDROID_SERIAL=<serial>`로 한 대씩
고정하거나 `am instrument`로 내려갈 것. Android 11+는 `adb pair`로 케이블 없이
등록된다(아이폰만 최초 1회 USB 신뢰가 필요).

두 번째 아이폰을 추가할 땐 첫 빌드에
`-allowProvisioningUpdates -allowProvisioningDeviceRegistration`을 붙일 것.

---

## 10. 맥을 BLE 상대로 쓰기 — 폰 1대로 줄이기

macOS에는 진짜 CoreBluetooth가 있다. 조사 중 확인한 것: **`BLETransport.swift`
자체는 macOS용으로 컴파일된다.** 걸리는 건 그것이 참조하는 `LocalCapabilities`다.

`LocalCapabilities`는 `Support/LocalCapabilities.swift`에 있고(조사 중 나온
"`SystemPairObserver.swift` 안에 있다"는 보고는 틀렸다 — 직접 확인함), 그 파일이
`WiFiAware`와 `NearbyInteraction`을 import하고 `NISession.deviceCapabilities`,
`WACapabilities.supportedFeatures`를 호출한다. macOS에서는 이 프레임워크들이 없다.

→ 해법은 `#if os(iOS)` 가드다. **`#if canImport(WiFiAware)`는 안 된다** —
두 프레임워크 모두 MacOSX.sdk에 실제로 들어 있어서 `canImport`가 macOS에서
**true를 반환**한다(확인함). import는 성공하고 **사용 지점만** 깨진다:
`error: 'NISession' is unavailable in macOS`, `'WACapabilities' is unavailable
in macOS`. macOS용 `CapabilitySet`을 `wifiAware=false, uwbPresent=false,
blePeripheral=true`로 돌려주면 된다. 한 파일에 십여 줄.

그러면 **맥이 여러분의 실제 `BLETransport`를 그대로 써서 아이폰 1대와
GATT를 할 수 있다.** 재조사에서 실측한 범위:

- **맥이 central(스캐너·GATT 클라이언트): 완전히 동작한다.** 12초 스캔에
  advertiser 57개, `advertisementData[CBAdvertisementDataServiceDataKey]
  as? [CBUUID: Data]` 캐스트(=`BLETransport.swift:257`과 동일)가 실제
  advertiser에서 성공했다. → 아이폰의 **peripheral 역할**을 진짜 라디오로
  검증할 수 있고, **진짜 경로손실이 실린 RSSI 시계열**도 얻는다(에뮬레이터는
  -8 dBm 고정이라 불가능한 것).
- **맥이 peripheral: service data에서 하드 크래시한다.**
  `NSInvalidArgumentException: -[CBUUID UTF8String]: unrecognized selector`.
  CoreBluetooth의 XPC 직렬화가 dict 키를 문자열로 가정하는데 CBUUID는
  아니다. SDK 헤더가 못 박고 있다 — 지원되는 advertising 데이터는
  `LocalName`과 `ServiceUUIDs` **둘뿐**. → 맥은 Android의
  `.addServiceData(SERVICE_UUID, token)` 랑데부를 흉내 낼 수 없다.
  이 방향은 서비스 데이터를 다른 곳에 실어야 하는데, 그러면 **프로덕션이
  안 쓰는 경로를 테스트하는 셈**이 된다.
- **낮은 MTU는 맥으로 못 만든다.** CoreBluetooth에 양쪽 어디에도 MTU를
  강제로 낮추는 API가 없다. §0.5의 버그 2·3이 사는 영역은 맥으로 못 간다.

한계는 명확히 해두자: 이건 **여러분의 GATT 프로파일**을 검증하는 것이지
**Android 쪽 구현**을 검증하는 게 아니다. macOS의 CBPeripheralManager가
service data를 실제로 advertise하는지는 별도 확인이 필요하다.

---

## 11. 조사 중 발견한 것 — RSSI 밴드 임계값이 플랫폼마다 다르다

부수적으로 나온 실제 불일치다.

| | iOS (`RSSIProximityFilter.swift:20-21,56`) | Android (`RssiProximityFilter.kt:15-18`) |
|---|---|---|
| veryNear | **-55** dBm | **-60** dBm |
| near | **-75** dBm | **-80** dBm |
| 히스테리시스 | **5** | **4** |

Kotlin 쪽은 생성자 기본값이지만 **프로덕션 호출부가 기본값 그대로다**
(`RangingCoordinator.kt:29` — `RssiProximityFilter()`, 유일한 main 호출부).
iOS도 `RangingCoordinator.swift:52`에서 `RSSIProximityFilter()`이고 임계값은
아예 하드코딩이다. 즉 실제로 다른 값이 돈다.

같은 거리에서 **두 폰이 서로 다른 근접 밴드를 표시한다.**

추세(trend) 상수 10개는 전부 일치하고, Swift 쪽 주석은 *"These are pinned in
code on both platforms so a given (RSSI, timestamp) sequence yields identical
enums"* 라고 명시적으로 플랫폼 간 일치를 목표로 선언한다. 밴드 상수에는 그런
주석이 없고 값이 다르다.

`PROTOCOL_V2.md` §12는 `ble_rssi`를 "local-only; no messages"로 규정하고 임계값을
고정하지 않았으므로 **프로토콜 위반은 아니었다.** 하지만 사용자에게 보이는
출력이 비대칭이 된다. 그리고 이건 **정확히 두 폰을 나란히 놓아야만 보이는
종류의 버그** — 이 문서 전체의 논지를 뒷받침하는 사례다.

**해결됨** (2026-07-29): §12가 이제 window·median 규칙·임계값·히스테리시스를
모두 고정하고, `shared/vectors/rssi_bands.json`을 양 플랫폼이 소비한다.
Android를 iOS 값(-55/-75/5, averaged-middle median)에 맞췄다.

두 가지를 정직하게 남긴다:

- **-55/-75/5는 측정값이 아니다.** 두 개의 근거 없는 추정치 중 하나를 고른
  것이고, iOS 쪽에 문서화된 경계 설명이 있다는 이유뿐이다. §13.2의
  tape+삼각대 캘리브레이션으로 **실측해서 교체해야 할 잠정값**이다.
- **Android 사용자에게 보이는 밴드가 실제로 바뀌었다.** 예: -78 dBm이
  이전엔 `near`, 이제 `far`다.

**남은 작업**: `proximity_hint`(msgType 25)는 양 플랫폼 단위테스트만 있고
`envelope_vectors.json`에는 아직 없다. 그 픽스처는 세션 전체가 Noise 키
스트림으로 묶여 있어 레코드 추가가 아니라 **전체 재생성**이고, 생성기가
쓰기 전에 공식 cacophony 벡터로 자기검증을 하는데(`check_official`) 그
파일이 로컬에 없고 `cryptography` 모듈도 없다. 커밋 `5d106c9`이 새 와이어
메시지에서 플랫폼별 테스트가 놓치는 걸 보여준 사례이므로, 다음 명령으로
채워 넣을 것:

```sh
pip install cryptography
python tools/genvectors/gen_vectors.py <cacophony.txt> shared/vectors
```

부수 효과: RSSI 재생 픽스처에서 `trend`/`trendConfidence`는 양 플랫폼 공유
기대값을 쓸 수 있지만 **밴드는 플랫폼별 기대값을 따로 가져야 한다**(중앙값
계산도 다르다 — Swift는 짝수일 때 가운데 둘의 평균 Double, Kotlin은 위쪽 Int).

---

## 13. "실기기 2대로만 되던 것" — 실제로 얼마나 줄어드나

§8의 불가능 목록을 다시 조사한 결과다. **상당 부분 줄어든다. 다만 Wi-Fi
Aware만은 안 줄어든다.**

### 13.1 공짜로 지금 되는 것

- **UWB(Android): 에뮬레이터 2대로 실제 레인징이 된다.** §3 정정 참조.
  `$0`, 헤드리스, ~3분. UWB 세션 수명주기 전체가 CI에 들어간다.
- **아이폰을 iOS 26으로 올리면 된다.** Wi-Fi Aware의 게이트는 80만원이
  아니라 **무료 업데이트**다. 보유 기기가 iPhone 16 Pro라 iOS 26을 지원한다.
  (그래도 Aware는 P2P라 26 기기가 **2대** 필요하다.)
- **맥이 두 번째 BLE 라디오.** §10 참조 — central 방향은 완전히 되고,
  peripheral 방향은 service data에서 막힌다.
- **2폰 무인 실행.** `devicectl device notification post`(iOS) +
  `am start`(Android)로 **양쪽 탭을 사람 없이** 동시에 건다. 지금은 사람이
  폰 두 대를 들고 각각 누르는데, 그 수초 차이가 역할 중재를 흔든다.
  `devicectl device process launch --environment-variables`도 있어서
  §9의 `.xctestrun` 트릭이 필요 없다.
- **`devicectl device sysdiagnose`** — 앱 **아래**를 보는 유일한 iOS 레버.
  Wi-Fi/NAN, Bluetooth, 전력 서브시스템 로그. (a)와 (b)/(f)의 깊은 절반에
  닿는 유일한 관측 수단이다.

### 13.2 5만원 이하로 크게 바뀌는 것

- **nRF52840-DK (~6만원) + Bumble.** 이게 §0.5 버그 2·3·4에 **실제로 닿는
  유일한 수단**이다. 스크립트로 **MTU 23을 강제**하고, MTU 승격을 거부하고,
  notify를 지연·유실시키고, **두 central이 동시에 구독**하게 하고, 같은
  서비스 UUID로 **가짜 토큰 미끼**를 광고할 수 있다 — 버그 1과 4의 시나리오
  그대로다. 폰 개수를 2 → 1로 줄인다.
- **프로그래머블 TX power = 합성 거리 램프.** DK의 TX power를 스크립트로
  깎으면 **사람이 걸어다니지 않고** 접근/후퇴 프로파일이 재현된다. 커밋
  `2bd4150`의 시간축 추세 로직이 정확히 이걸로 검증된다. Kconfig 3줄 +
  파이썬 루프.
- **nRF52840 동글 2개 (~3.5만원)** 또는 **Sniffle on TI LaunchPad (~5만원)** —
  진짜 무선 구간 캡처가 Wireshark로 들어온다. 버그 2·3을 "재현된다"에서
  "진단된다"로 바꾼다. Sniffle이 연결 수립 순간을 더 잘 잡는다.

### 13.3 하드웨어 없이 관측성만으로 (가장 저평가된 항목)

재조사가 **최고 가치 항목으로 지목한 것은 하드웨어가 아니다**:

- **run-artifact 규격** — run ID + 공통 시계 + JSONL. 실기기 실행 하나를
  "무슨 일이 있었는지에 대한 기억"에서 **diff 가능한 파일**로 바꾼다.
  (d)(e)(f) 전부의 선결 조건이다.
- **실패 스냅샷** — 종단 전이 시점에 무엇을 덤프할 것인가. aware → nearby →
  ble 3단 폴백 앱은 그 사슬 **어딘가에서 조용히 죽는다.** 지금은 "안 됐다"만
  알 수 있다.
- 둘 다 앱이 **이미 계산하고 있는 값**이라 새 배선이 없다. 하지만 §6의
  선결 조건(로그 emitter가 0개)이 먼저다.

### 13.4 재조사가 찾아낸 6번째 버그

**`_cellfind._udp` vs `cellfind` 서비스 이름 불일치.** iOS는 RFC6763 표기를
요구하고 Android는 맨 이름을 publish한다. `WiFiAwareTransport.swift:22-24`의
주석이 이미 자백하고 있다. → **첫 크로스플랫폼 Wi-Fi Aware 테스트는 반드시
실패한다.** 폰을 사기 전에 고쳐야 한다. 상수 하나 + 벡터 하나.

### 13.5 여전히 안 줄어드는 것

- **Wi-Fi Aware는 iOS 26 아이폰 2대가 바닥이다.** macOS SDK의
  `WiFiAware.swiftinterface`가 모든 공개 타입에 `@available(macOS, unavailable)`을
  달고 있다(직접 읽어 확인). 맥은 두 번째 NAN 피어가 **될 수 없다.**
  시뮬레이터에도 에뮬레이터에도 NAN이 없다. 게다가 사도 iOS↔Android가
  된다는 보장이 없다(§13.4).
- **클라우드 디바이스 팜은 전부 죽은 길이다.** AWS Device Farm 문서가
  *"network isolation prevents cross-device communication over wireless
  networks"* 라고 명시한다 — **격리가 곧 상품**이다. BrowserStack은
  Bluetooth를 아예 끈다. 인접 배치가 가능한 유일한 등급(HeadSpin/Kobiton
  on-prem)은 **하드웨어를 직접 넣는 것**이라 결국 "직접 랩 운영"으로 수렴한다.
- **차폐 박스 + 스텝 감쇠기는 500만원+**이고, 폰은 안테나 포트가 없어서
  그 돈 주고도 재현성이 안 나온다. 사지 말 것.
- **`xcrun devicectl`에 화면 녹화가 없다**(subcommand 9개 확인). 그리고
  **ffmpeg로 아이폰 화면 캡처도 안 된다** — ffmpeg의 avfoundation은
  CoreMediaIO 호출을 아예 하지 않아서(소스 확인) 기기가 목록에 안 뜬다.
  뜨는 건 Continuity Camera다. QuickTime GUI뿐.

### 13.6 결국 사야 하는 것

**아이폰 1대**(중고 20~38만원)와 **Android 1대**(25~40만원). 그게 전부다.
맥미니도, 차폐 박스도, 팜 구독도 아니다. 그 전에 §13.1~13.3을 다 하면
그 2대가 하는 일이 훨씬 줄어든다.

---

## 12. 권장 순서

투입 대비 효과 순. **1~4가 내부 루프**(원래 질문), 5부터가 회귀·심화다.

**완료됨** (2026-07-29): §0.1 DevPeer 적용, §0.5 버그 1~5 수정, §11 밴드
임계값 정렬 + `shared/vectors/rssi_bands.json` 신설, §12 `proximity_hint`
(msgType 25) 추가. 코어 35 + iOS 103 + Android 161 = **299 테스트 통과.**

1. **§13.4 Wi-Fi Aware 서비스 이름 불일치 수정** — 상수 하나. 폰 사기 전에.
2. **`CellularChatTests`에 `DEVELOPMENT_TEAM` 추가** — 한 줄. 실기기 테스트를
   막고 있는 유일한 것. 실기기가 필요한 나머지 절반의 루프가 여기서 열린다.
4. **Android 에뮬레이터 2대 레인 + ranging 훅 3줄** (§0.2) —
   `FindController.kt:244`의 구체 캐스트를 풀어야 peripheral 쪽 UI가 산다.
   `debug`/`androidTest` 소스셋도 같이.
5. **로그 emitter 추가** — iOS Logger subsystem 1개, Android Log 태그 1개.
   §6의 모든 도구가 여기에 의존한다. 실기기 실패의 진단 가능성이 여기서 갈린다.
6. **CI yaml 하나** — `swift test` + `testDebugUnitTest` + 시뮬레이터 테스트.
   합쳐 30초. Android는 리눅스 러너로 분리(§7 비용 주의).
7. **크로스언어 interop 하네스** (§4) — 양쪽 기존 테스트의 `LoopbackTransport`를
   소켓 버전으로 교체 + 오케스트레이터 100줄. 시나리오는 `shared/vectors/`에서 구동.
8. **RSSI 밴드 임계값 불일치 해결** (§11) — 의도 확인 후 정렬 또는 문서화.
9. **RSSI/NI 트레이스 픽스처** — 실기기에서 한 번 캡처, 영원히 재생. 추상화 금지.
10. **열화 데코레이터 + 시계 주입** (§5) — 버그 2·3이 사는 낮은 MTU 영역을
    강제로 만들 수 있는 유일한 소프트웨어 수단이다.
11. **2폰 오케스트레이터** (§9) — `.xctestrun` 역할 주입 + LAN 배리어.
    **iOS 26 폰 2대를 확보한 뒤에.**
12. **맥을 BLE 상대로** (§10) — 폰 1대로 iOS BLE 검증.

**끝까지 실기기로 남는 것**: Wi-Fi Aware 전 구간, iOS BLE·NearbyInteraction,
UWB 실제 레인징, 처리량·지연·배터리, 물리적 거리·차폐. 이건 줄일 수는 있어도
없앨 수는 없다.
