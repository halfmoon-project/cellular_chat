# Serverless Two-Person Finder Implementation Plan

## 한국어 요약

이 계획은 기존 LAN 채팅 앱을 호환성 없이 새로 구성하는 계획이다. 서버와
외부 비콘은 사용하지 않으며, 두 사용자가 최초 1회 QR로 기기를 등록한 뒤
시간 제한이 있는 `찾기 모드`를 양쪽에서 켜면 직접 P2P로 재발견한다.

핵심 경로는 다음과 같다.

```text
256-bit QR 등록 + 페어별 공개키 고정
  -> Wi-Fi Aware
  -> 실패 시 Nearby Connections
  -> 실패 시 BLE GATT
  -> UWB 방향+거리
  -> 방향 미지원 시 거리만
  -> UWB 미지원 시 BLE 신호 강도만
  -> 모든 전파 범위 밖이면 검색 중
```

기능은 자연스럽게 낮아지지만 보안은 낮아지지 않는다. 모든 transport는
검증된 Noise 보안 세션을 사용하며, 구형 프로토콜·평문·단순 HMAC 세션으로
후퇴하지 않는다. iOS 26.0은 유지하되 Android와의 교차 UWB는 iOS 26.1+
실기기 검증 조합에서만 기본 활성화한다.

계획의 첫 단계는 제품 코딩이 아니라 네 가지 실기기 gate다: 교차 Wi-Fi
Aware, 교차 UWB, BLE, Noise 상호운용. 하나가 실패하면 문서에 정한 fallback을
선택하며, 보안 gate가 실패하면 구현을 중단한다.

서버가 없으므로 전파 범위 밖 상대를 깨우거나 공연장 반대편의 최초 방향을
알아내는 것은 범위에 포함하지 않는다. 양쪽이 미리 찾기 모드를 활성화해야
하고, 앱 강제 종료 상태는 보장하지 않는다.

## 1. Objective

Rebuild the app as a serverless, two-person finder for Android 16 (API 36) and
iOS 26 or later.

Two people pair their phones once. Later, both people can explicitly enable a
time-limited Find mode. The phones rediscover and authenticate each other
without an internet connection, access point, beacon, or other device. Once a
peer-to-peer link is available, the app selects the best ranging technology
supported by both phones and shows only measurements the platform actually
reports.

The product contract is:

- Pairing proves possession of the same high-entropy invitation secret and
  permanently binds the other phone's public key.
- A paired phone is recognized by its cryptographic key, not by a display name,
  transient Wi-Fi/Bluetooth identifier, or user-entered room ID.
- Wi-Fi Aware is the preferred serverless discovery and control transport.
- BLE is the mandatory compatibility and control fallback.
- UWB is the preferred precise ranging technology.
- A missing direction measurement falls back to distance only.
- A missing precise distance measurement falls back to coarse proximity only.
- A peer outside all direct-radio ranges remains `searching`; the app never
  invents a direction or claims that the peer is absent from the venue.
- Functional capability may degrade, but security never does. There is no
  plaintext, HMAC-only session, protocol-v1, or unauthenticated transport
  fallback.

## 2. Hard constraints and accepted limitations

### Required

- Minimum Android version: Android 16 / API 36.
- Minimum iOS version: iOS 26.0.
- No backend, cloud relay, venue beacon, fixed anchor, or third relay phone.
- iOS, Android, and cross-platform pairs must use one shared protocol and the
  same security semantics.
- Pairing data must survive ordinary app restarts and device reboots.
- Users must be able to revoke a pairing locally.

### Explicit product limitations

- Both people must enable Find mode before discovery can be relied upon. A
  serverless app cannot remotely wake a phone that is outside radio range.
- Force-quit, disabled radios, revoked permissions, and OS suspension can make
  a phone undiscoverable. The UI must report this as an unknown/searching state.
- Two phones alone cannot guarantee discovery across an entire large venue.
  Wi-Fi Aware and BLE work only when their radios can reach each other.
- Before the first P2P contact there is no truthful initial bearing to the peer.
- Release policy enables cross-platform UWB by default only on iOS 26.1 or
  later. An iOS 26.0 and Android 16 pair falls back to coarse proximity unless
  that exact profile passes the physical-device interoperability gate.
- OS version is not treated as hardware capability. Wi-Fi Aware, UWB, azimuth,
  Extended Distance Measurement, and Wi-Fi RTT are all runtime capabilities.

## 3. Scope changes from the current prototype

The existing LAN chat prototype is reference code, not a compatibility target.
There is no migration or wire-compatibility requirement.

Remove after their replacements pass tests:

- Bonjour/NSD room discovery and local-LAN connection ownership.
- The user-entered reusable connection-ID flow.
- The monolithic peer managers tied directly to TCP sockets.
- Chat history, file offers, file transfer, and received-file provider.
- Protocol v1 and its room-hash advertisement.

Retain or adapt:

- iOS Nearby Interaction capability handling and measurement presentation.
- Android 16 RangingManager integration.
- The Android/iOS Apple UWB configuration parser, after updating it to the
  selected iOS 26.1 interoperability specification and new test vectors.
- Length-framing and protocol tests where they still fit the new transport-
  independent protocol.
- The rule that unsupported direction is never synthesized.

The initial release is a finder, not a messenger. Coordination messages needed
to establish P2P and ranging sessions remain in scope; general chat and file
transfer do not.

## 4. Capability and fallback policy

Each device publishes an authenticated `CapabilitySet` after the secure P2P
session is established. Capability negotiation, not model-name allowlists,
selects the runtime path.

### Transport order

1. Wi-Fi Aware, when both devices report it available, system pairing is valid,
   and the cross-platform feasibility gate passes.
2. Nearby Connections, when its SDK/runtime is available on both devices. It is
   the high-throughput fallback for missing or failed Wi-Fi Aware.
3. BLE GATT, as the dependency-free mandatory cross-platform baseline.

Attempt Wi-Fi Aware and Nearby Connections sequentially with short, measured
timeouts; do not keep both high-power discovery mechanisms running together.
Stop losing transports after one authenticated high-throughput path wins.
Nearby unavailability, including Android devices without Google Play services,
falls through to BLE without changing the security or ranging protocol.

For BLE, prefer Android advertiser/GATT peripheral and iOS scanner/GATT central
for cross-platform pairs because that direction is the safer background path on
iOS. Same-platform pairs derive central/peripheral ownership deterministically
from their pinned pair keys. If the preferred side cannot perform its role, the
capability negotiation may reverse roles only through an explicitly tested
profile.

When BLE connects first, authentication and capability exchange may start on
BLE. If Wi-Fi Aware becomes available, the same logical Find session upgrades
to Wi-Fi Aware, but the new transport performs a fresh secure handshake with
fresh keys and sequence numbers. BLE remains available as a control fallback.
Session IDs and connection ownership rules prevent duplicate logical sessions.

### Ranging order

1. UWB with direction, when both devices and the active interop profile support
   it and the platform supplies an angle.
2. UWB distance only.
3. For Android-to-Android, let base-API-36 RangingManager default OOB mode with
   `RANGING_MODE_HIGH_ACCURACY_PREFERRED` select among mutually supported
   precise technologies. Drive the UI from the technology reported by
   `onStarted` and its nullable measurements.
4. BLE RSSI proximity bands (`veryNear`, `near`, `far`, `unknown`). RSSI is not
   exposed as an exact distance and never produces an arrow.

The Android default-OOB fallback is Android-to-Android only. Android-to-iOS uses
the explicitly negotiated Apple raw-UWB profile; if it fails, the app starts
its own BLE RSSI fallback rather than assuming default OOB can range an iPhone.

Ranging is not an authentication factor. Bind every UWB OOB exchange to the
authenticated Noise peer and generate fresh ranging material per attempt, but
do not use a distance result to authorize access. The Apple interop static-STS
profile has relay limitations that app-layer authentication cannot remove.

### Pair matrix

| Pair | Preferred P2P | Precise ranging | Fallback |
|---|---|---|---|
| iOS 26+ / iOS 26+ | Wi-Fi Aware | Nearby Interaction peer UWB; enable EDM only when both peers report it | BLE RSSI proximity |
| Android 16 / Android 16 | Wi-Fi Aware | RangingManager default OOB, high-accuracy preferred; azimuth only when reported | System-selected distance technology, then app BLE RSSI |
| iOS 26.1+ / Android 16 | Wi-Fi Aware | Android raw UWB + Apple Nearby Interaction interop profile | BLE RSSI proximity |
| iOS 26.0 / Android 16 | Wi-Fi Aware | Cross-UWB disabled | BLE RSSI proximity |
| Any pair without Wi-Fi Aware | Nearby Connections, then BLE GATT | Use UWB if the required OOB exchange and capabilities are available | BLE RSSI proximity |

Bluetooth Channel Sounding is not part of the iOS 26 plan. It may be added as a
future iOS 27 capability without changing the fallback state model.

## 5. Target architecture

```text
Pairing / People / Find UI
             |
      FindSessionCoordinator
       /       |          \
Identity      Transport          Ranging
& Pairing     Coordinator        Coordinator
   |       /       |       \      /   |   \
Key store Aware  Nearby   BLE   UWB  RTT  RSSI
Pair DB
             |
      Background Controller
   (Live Activity / Foreground Service)
```

### Shared domain concepts

- `DeviceIdentity`: the local app-install identity used only for local record
  ownership; it is not advertised as a global radio identity.
- `PairRecord`: pair-specific local/peer static public keys, pair root, pair ID,
  pair epoch, highest successfully negotiated protocol version, display alias,
  system pairing handles, creation time, and revocation state.
- `CapabilitySet`: OS, platform, Wi-Fi Aware, BLE roles, UWB profiles,
  direction, EDM, and Wi-Fi RTT flags.
- `FindSession`: pair ID, session ID, expiry, local state, selected transport,
  selected ranging method, and last valid measurement.
- `Measurement`: timestamp, method, quality, optional distance, and optional
  horizontal angle.

### State machine

```text
idle
  -> arming
  -> searching
  -> p2pConnecting
  -> authenticating
  -> connected
  -> rangingStarting
  -> directionAvailable | distanceOnly | proximityOnly | connectedOnly
  -> signalLost -> retryWait -> searching
  -> stopped | expired | failed
```

Every transition carries a reason code. A stale measurement is cleared when
the session enters `signalLost`; it is not left on screen as if current.
Permission required, radio unavailable, background suspension, identity
mismatch, and revocation are distinct reasons rather than one generic timeout.

## 6. Identity, pairing, and session security

### Security implementation gate

Do not design a custom authenticated key-exchange protocol in production code.
Before Phase 2, select and interoperate an audited Noise Protocol implementation
on Swift and Kotlin. The target suite is X25519/ChaChaPoly/SHA-256 with:

- `NNpsk0` for the first QR-authenticated registration channel.
- `IKpsk2` for reconnecting a known, pinned peer using the pair root.

If an audited, maintainable Noise implementation cannot pass cross-platform
test vectors and real BLE/Wi-Fi transport tests, stop at the security gate. Do
not replace it with an ad-hoc ECDH/HMAC construction. TLS 1.3 with pinned mutual
credentials is acceptable for Wi-Fi Aware alone, but it does not satisfy the
required transport-independent BLE fallback.

### Long-term identity

- Generate pair-specific Noise static keys during pairing so two different
  pairings cannot be correlated through one globally advertised public key.
- Store iOS pair key material in Keychain with
  `AfterFirstUnlockThisDeviceOnly` (or a stricter tested this-device-only)
  accessibility and exclude it from backup.
- Store Android pair key material in Android Keystore or a Keystore-wrapped
  encrypted record, according to the selected Noise library's key support.
- Pin the peer static key. A key change is never accepted automatically; it
  requires revocation and pairing again.
- Do not use `UserDefaults`, `SharedPreferences`, MAC addresses, Wi-Fi Aware
  peer handles, or BLE identifiers as the security identity.

### Initial pairing

1. The inviting phone creates a 256-bit random, single-use invitation secret,
   pair ID, short-lived invitation advertisement, and permanent pair role A;
   the joining phone becomes role B.
2. The normal path transfers the invitation with a QR code. A full-entropy
   encoded copy/paste string is the accessibility fallback; a short
   user-chosen PIN is not accepted in the MVP.
3. The joining phone discovers the inviter over BLE and, when supported, begins
   Wi-Fi Aware system app-to-app pairing.
4. Both sides run the audited `NNpsk0` handshake with the invitation secret as
   the PSK. The invitation secret itself is never transmitted.
5. Inside that authenticated encrypted channel, both sides exchange and bind
   their pair-specific static keys and the complete pairing transcript.
6. Each side derives the pair root and stages the `PairRecord`.
7. Both sides exchange key-confirmation and commit acknowledgements. Only then
   do they persist the committed record, mark the invitation consumed, and
   erase the invitation secret and ephemeral state.
8. Both screens show the same peer fingerprint confirmation before completing.

If a short human-entered code is added later, use an audited RFC 9382 SPAKE2
implementation. Never treat HMAC with a short code as a PAKE fallback.

### Rediscovery

- BLE advertisements use a 128-bit rotating token derived from a direction-
  specific discovery key and
  `HMAC(discoveryKey, protocolVersion || floor(time / 120s) || role)`.
- Accept only the current and adjacent two-minute epochs to tolerate clock
  skew.
- Advertise only the pair currently selected by the user. The token is a search
  filter, never proof of identity.
- Never advertise a static pair ID, display name, public-key fingerprint, or
  hash of a human-entered secret.
- Wi-Fi Aware uses declared static service types and the OS paired-device list.
  Its paired-device handle is only a routing hint and is never the app security
  identity; the app still runs Noise authentication after connection.

### Session authentication and encryption

- Resolve the candidate PairRecord from the rotating rendezvous token; do not
  expose a static pair ID before authentication.
- Run `IKpsk2` with the pinned peer key and pair root for every new transport.
- Use the Noise-derived directional traffic keys and ChaChaPoly encryption.
- Include protocol version, logical Find session ID, monotonically increasing
  sequence number, and message type in the encrypted/authenticated envelope.
- Define a unique per-direction nonce derivation from the transport-session ID
  and sequence number; nonce reuse is a protocol-fatal error.
- Reject replays, sequence rollback, unknown peers, expired sessions, and
  mismatched capability transcripts.
- Wi-Fi Aware link encryption is defense in depth; app-layer authentication and
  encryption remain mandatory so BLE and Wi-Fi paths have the same semantics.

## 7. Protocol v2

Create a new `shared/PROTOCOL_V2.md`, deterministic CBOR schemas, and
cross-platform cryptographic test vectors before production networking code.
Protocol v1 negotiation is forbidden. Locator v2 application frames are capped
at 64 KiB; BLE fragments are completely and uniquely reassembled before the
Noise payload is decrypted.

Required message groups:

- Pairing: `pair_hello`, `pair_challenge`, `pair_proof`, `pair_bind`,
  `pair_complete`, `pair_abort`.
- Secure session: `session_hello`, `session_auth`, `session_ready`, `ping`,
  `disconnect`.
- Capabilities: `capabilities`, `transport_upgrade`, `transport_ack`.
- Ranging: `ranging_offer`, `ranging_accept`, `ranging_start`, `ranging_stop`,
  `ranging_error`, and the Apple/Android opaque UWB configuration payloads.
- State: `find_active`, `find_stopping`, `find_expired`.

Every start/stop operation carries a logical Find session ID and attempt ID so
duplicates are idempotent across reconnects and transport upgrades.

The protocol specification must define:

- Exact encoding for the Noise prologue, PSK/root derivation inputs, discovery
  HMAC input, and every authenticated application envelope.
- Canonical CBOR requirements and rejection of noncanonical or duplicate keys.
- Maximum message and fragment sizes.
- BLE fragmentation, acknowledgement, timeout, retransmission, and flow
  control.
- Version negotiation and unknown-message handling.
- Connection-role tie-breaking from public-key fingerprints.
- Idempotency rules for duplicate start/stop/upgrade messages.
- Error codes that map directly to user-visible states.
- Binding of the advertised capability ranges, selected ranging profile, and
  selected transport to the authenticated handshake transcript.
- Hard rejection of oversize, truncated, overlapping, duplicate-key, and
  downgrade frames before they can affect the state machine.

Required shared fixtures include fixed Noise PSKs/static/ephemeral keys,
pair-root output, A/B previous/current/next discovery tokens, canonical CBOR
bytes, traffic ciphertext for sequence 0 and 1, BLE fragmentation at MTU
23/185/512, capability-selection cases, Apple UWB opaque payloads, wrong-secret
and key-substitution cases, replay/bit-flip cases, and duplicate start/stop
state transitions. Both parsers also receive fuzz and model-state testing.

## 8. Platform structure

### iOS

Suggested modules:

- `Identity/DeviceKeyStore.swift`
- `Pairing/PairStore.swift`, `PairingCoordinator.swift`
- `Transport/PeerTransport.swift`
- `Transport/WiFiAwareTransport.swift`
- `Transport/NearbyConnectionsTransport.swift`
- `Transport/BLETransport.swift`
- `Transport/TransportCoordinator.swift`
- `Ranging/RangingCoordinator.swift`
- `Ranging/ApplePeerRanger.swift`
- `Ranging/AndroidInteropRanger.swift`
- `Background/FindLiveActivityController.swift`
- `Features/Pairing`, `Features/People`, `Features/Find`

Required project changes:

- Set the deployment target to iOS 26.0.
- Add Wi-Fi Aware `Publish` and `Subscribe` entitlement values.
- Declare one fixed, short `WiFiAwareServices` publishable/subscribable service
  shared with Android.
- Add Bluetooth usage descriptions and the central background mode. Add the
  peripheral background mode only if an approved fallback profile actually
  makes iOS a BLE peripheral.
- Keep Nearby Interaction and camera usage descriptions.
- Add the Nearby Interaction background capability and Live Activity support.
- Use `DeviceDiscoveryUI` for Wi-Fi Aware app-to-app system pairing.
- Gate all features with `WACapabilities` and `NISession.deviceCapabilities`.
- Treat `WAPairedDevice.ID` as an install-scoped routing association, not a
  permanent identity, and observe system-pair removal.
- Build with an SDK containing the selected iOS 26.1 UWB interop definitions
  even though the deployment target remains iOS 26.0.

### Android

Suggested packages:

- `identity`: Keystore identity and pair persistence.
- `pairing`: invitation and pairing state machine.
- `transport`: common interface and coordinator.
- `transport.aware`: WifiAware publish/subscribe and network data path.
- `transport.nearby`: Nearby Connections discovery and payload data path.
- `transport.ble`: advertiser, scanner, GATT server/client, fragmentation.
- `ranging`: coordinator and method-specific controllers.
- `background`: time-limited Find foreground service.
- `ui`: pairing, people, and finder screens backed by lifecycle-aware state.

Required project changes:

- Change `minSdk` to 36 and retain `compileSdk`/`targetSdk` 36.
- Add `NEARBY_WIFI_DEVICES`, Wi-Fi state/change-network permissions,
  Bluetooth scan/advertise/connect permissions, foreground-service permissions,
  `POST_NOTIFICATIONS`, and `RANGING`.
- Remove the legacy `UWB_RANGING` permission because the new minimum is 36.
- Declare `FOREGROUND_SERVICE_CONNECTED_DEVICE` and register the Find service
  with the `connectedDevice` foreground-service type.
- Because RSSI is intentionally used to infer physical proximity, do not assert
  `neverForLocation` blindly. Define and test the required nearby/location
  permission policy explicitly for API 36.
- Declare Wi-Fi Aware and UWB as optional hardware features so fallback-capable
  devices can install the app.
- Check `FEATURE_WIFI_AWARE`, `WifiAwareManager.isAvailable`, UWB features, and
  every `RangingCapabilities` field at runtime.
- Observe Wi-Fi Aware availability changes and resource exhaustion, close stale
  sessions, and re-enter transport arbitration instead of treating them as
  fatal pairing errors.
- Re-read ranging technology availability for every Find attempt and use the
  actual technology plus nullable distance/azimuth/elevation delivered by the
  session, not the requested preference, to drive UI.
- Use `RANGING_SESSION_OOB` with a P2P-backed `TransportHandle` for
  Android-to-Android and `RANGING_SESSION_RAW` only for the Apple interop path.
- Stay within base API 36. Do not plan around API 36.1
  `setRangingTechnologyFilter`/`WIFI_STA_RTT` additions or API 37 Bluetooth
  DeviceHandle APIs.
- Run active Find mode in a visible, time-limited foreground service.
- Separate Activity/UI lifetime from P2P and ranging session lifetime.
- Treat non-UWB background ranging as foreground-only. A foreground service
  does not bypass platform ranging limits; background UWB is used only when the
  device explicitly reports background-ranging support.

## 9. Implementation phases and verification gates

### Phase 0 — Freeze product contract

Deliverables:

- Approve the constraints and fallback table in this document.
- Confirm that chat/file transfer and venue-wide guaranteed discovery are out of
  scope.
- Select representative hardware for every required capability class.

Verify:

- Every product statement maps to an observable state or an explicit
  limitation.
- No requirement assumes a server, push notification, or third relay device.

### Phase 1 — Throwaway feasibility spikes

Do not build production architecture until these risks are measured on physical
devices.

Spike A: iOS 26 Wi-Fi Aware to Android 16 Wi-Fi Aware.

- Pair through the Apple-supported app-to-app flow.
- Reconnect a previously paired device.
- Exchange a bidirectional TCP payload without an AP or internet.
- Exercise foreground, background, screen-off, and reconnect behavior.

Spike B: Android 16 to iOS 26.1 UWB.

- Exchange Apple interop configuration over a temporary local channel.
- Obtain distance on both sides.
- Record whether Android reports azimuth on each target model.
- Verify signal-loss and restart behavior in a body-obstructed environment.

Spike C: BLE cross-platform baseline.

- Advertise, discover, connect, and exchange a fragmented authenticated payload.
- Measure foreground and background discovery behavior.

Spike D: audited Noise interoperability.

- Run official/library test vectors on Swift and Kotlin.
- Complete `NNpsk0` and `IKpsk2` over both a stream fixture and a deliberately
  fragmented/reordered BLE fixture.
- Verify secure key persistence on representative iOS and Android devices.

Go/no-go decisions:

- If native cross-platform Wi-Fi Aware is reliable, ship the preferred order
  Wi-Fi Aware -> Nearby Connections -> BLE.
- If it fails interoperability or pairing requirements, remove Wi-Fi Aware
  from the release path and ship Nearby Connections -> BLE.
- If cross-platform UWB fails on a device combination, ship that combination as
  proximity-only; do not block pairing or fabricate a direction.
- If Noise interoperability or safe key persistence fails, stop production
  implementation. Security is not allowed to fall back.

### Phase 2 — New skeleton and shared protocol

- Introduce the shared state model and transport/ranging interfaces.
- Add protocol v2 specification and cross-platform test vectors.
- Add injectable capability providers so every fallback can be unit-tested
  without hardware.
- Build minimal Pairing, People, and Find screens against fake implementations.

Verify:

- Both platform unit suites consume identical cryptographic/protocol vectors.
- State transitions are deterministic for all capability combinations.
- Both apps build with the new minimum OS versions.

### Phase 3 — Secure identity and pairing

- Implement device keys, pair DB, QR invitation, manual high-entropy fallback,
  Noise registration, static-key binding, confirmation, and revocation.
- Implement upgrade handling for an incomplete pairing and cleanup of expired
  invitations.

Verify:

- Same secret succeeds; different secret fails without disclosing which field
  mismatched.
- Modified transcript, replayed proof, substituted public key, and expired
  invitation all fail.
- Consumed invitations cannot be reused and a peer key change always requires
  re-pairing.
- App restart and device reboot preserve valid pair records.
- Local revocation prevents subsequent session authentication.

### Phase 4 — BLE mandatory transport

- Implement rotating-token advertisement and discovery.
- Implement deterministic connection ownership.
- Implement GATT framing, fragmentation, flow control, timeout, and secure
  session handshake.
- Filter RSSI with a rolling median and hysteresis, then expose only calibrated
  proximity bands and a confidence-qualified approaching/receding trend.

Verify:

- Only the paired device advances beyond discovery.
- Static identifiers and display names are absent from over-the-air discovery
  data.
- Dropped, duplicated, reordered, and oversized fragments fail safely.
- BLE-only devices complete pairing, authentication, capability exchange, and
  proximity mode.

### Phase 5 — Preferred and fallback high-throughput transports

- Implement system pairing record association and paired-device lookup.
- Implement publish/subscribe roles and encrypted data connection.
- Integrate Nearby Connections on both platforms as the post-Aware fallback,
  while keeping the same Noise session and protocol semantics.
- Add sequential timeout arbitration and runtime checks for SDK/GMS
  availability; never leave Aware and Nearby discovery running indefinitely in
  parallel.
- Integrate BLE-to-Wi-Fi transport upgrade and fallback on path loss.
- Keep one logical session while transports change.

Verify:

- No AP, hotspot, cellular data, or internet route is present during tests.
- Previously paired devices reconnect without repeating app-level registration.
- Duplicate BLE/Wi-Fi connections produce one session.
- Wi-Fi loss re-enters transport arbitration and reaches Nearby or BLE without
  retaining stale ranging UI.
- Aware unavailable selects Nearby without user re-pairing; Nearby unavailable
  selects BLE without security downgrade.

### Phase 6 — Ranging coordinator

- Implement iOS-to-iOS Nearby Interaction peer mode.
- Enable EDM only when both Apple discovery tokens report support.
- Implement Android-to-Android RangingManager UWB.
- Update and integrate iOS 26.1/Android 16 raw UWB interop.
- Verify Android-to-Android default-OOB selection across its supported
  high-accuracy ranging technologies, including NAN RTT where the platform
  actually selects it; do not hard-code a 36.1-only technology filter.
- Add BLE RSSI proximity fallback for every pair.
- On UWB invalidation or sustained missing samples, retain the authenticated P2P
  session, fall back immediately to proximity, and retry UWB with bounded
  backoff. A ranging failure must not be reported as a pairing failure.

Verify:

- Capability negotiation selects the same method on both phones.
- Direction UI appears only after a fresh platform angle sample.
- Distance-only and proximity-only paths never display an arrow.
- Signal loss clears stale distance/direction and returns to searching or the
  next supported fallback.
- Starting/stopping repeatedly never creates duplicate ranging sessions.

### Phase 7 — Background Find mode

- Add user-selected, time-limited Find availability.
- iOS: Live Activity, Bluetooth/Wi-Fi Aware background modes, Nearby Interaction
  background handling, state restoration where supported.
- Android: visible foreground service, notification action to stop, wake-lock
  usage only when measurements prove it necessary.
- Expire discovery credentials and radio work when the session ends.

Verify:

- Background and screen-off results are recorded separately from foreground
  results.
- Force-quit/force-stop is reported as unsupported rather than claimed to work.
- Find mode always has visible user affordance and a deterministic stop/expiry.
- No discovery or ranging remains active after expiry.

### Phase 8 — Production finder UX

- Replace setup/chat screens with People, Pair, Find, and Pair Settings screens.
- Show transport-independent states and actionable recovery guidance.
- Provide haptics whose cadence is based only on fresh distance/proximity data.
- Add camera-assistance coaching only when the capability is available.
- Add explicit wording that `searching` means “not yet in direct radio range,”
  not “the other person is not here.”

Verify:

- Every injected capability profile renders the correct state.
- Permission denial has a settings/retry path.
- VoiceOver/TalkBack conveys state, distance, and direction changes.
- The screen never presents stale or synthesized measurements.

### Phase 9 — Remove prototype code

- Delete protocol v1, Bonjour/NSD managers, local TCP peer classes, room-ID UI,
  chat, and file-transfer code.
- Remove permissions, plist keys, resources, dependencies, and tests made unused
  by those deletions.
- Update README and protocol documentation to describe the finder accurately.

Verify:

- Repository search finds no `_cellchat._tcp`, room hash, chat, or file-transfer
  production paths.
- Both clean builds and all unit tests pass.
- The app still installs on capability-limited devices and enters fallback mode.

### Phase 10 — Security and venue validation

Test matrix dimensions:

- iOS 26.0 and 26.1+.
- Apple peer with and without EDM/direction.
- Android 16 with no UWB, UWB distance only, and UWB azimuth.
- Wi-Fi Aware present, unavailable at runtime, and absent.
- iOS/iOS, Android/Android, and iOS/Android pairs.
- Foreground, background, screen off, reconnect, and signal loss.
- Empty room, normal indoor space, and dense body-obstructed venue conditions.

Record:

- Discovery and authenticated-connect latency.
- Chosen transport and every fallback reason.
- First measurement latency, update frequency, and stale-sample count.
- Distance/direction availability rather than only numerical accuracy.
- Disconnect/reconnect rate and battery use for each Find duration.

Release gates:

- No false peer is accepted in negative identity tests.
- No replay or altered transcript creates a session.
- Pairing, reconnect, and finding pass with cellular disabled, no associated
  infrastructure Wi-Fi, and no app-originated DNS/HTTP/server traffic.
- Every supported capability combination completes its documented best path.
- Every unsupported combination reaches the documented fallback without crash.
- No stale or synthetic direction is shown.
- Repeat the required physical-device combinations at least 30 times and set
  discovery/reconnect targets from measured distributions before release.
- Real-device results define the marketed range; no theoretical Bluetooth,
  Wi-Fi, or UWB maximum is presented as guaranteed coverage.

## 10. Recommended delivery order

1. Complete all four feasibility spikes.
2. Freeze protocol v2 and the capability/fallback table.
3. Ship secure pairing over BLE.
4. Ship BLE-only authenticated discovery and proximity mode.
5. Add Wi-Fi Aware, Nearby fallback, and transport upgrade.
6. Add iOS/iOS and Android/Android UWB.
7. Add iOS 26.1/Android 16 cross-platform UWB.
8. Add background Find mode and production UX.
9. Remove the old prototype.
10. Run the dense-venue device matrix before making range claims.

This order always leaves a vertically working, truthful fallback and prevents
the experimental cross-platform UWB path from blocking the core paired-device
finder.

## 11. Verified platform references

- Apple Wi-Fi Aware overview and supported devices:
  <https://developer.apple.com/documentation/WiFiAware>
- Apple app-to-app Wi-Fi Aware pairing and P2P connection:
  <https://developer.apple.com/documentation/wifiaware/connecting-paired-devices>
- Apple Wi-Fi Aware introduction and cross-platform statement:
  <https://developer.apple.com/videos/play/wwdc2025/228/>
- Android Wi-Fi Aware:
  <https://developer.android.com/develop/connectivity/wifi/wifi-aware>
- Google Nearby Connections cross-platform overview:
  <https://developers.google.com/nearby/overview>
- Android 16 RangingManager and Apple UWB custom-OOB interop:
  <https://developer.android.com/develop/connectivity/ranging>
- Apple Nearby Interaction capability/background behavior:
  <https://developer.apple.com/documentation/nearbyinteraction>
- Apple UWB interoperability specifications, including the iOS 26.1 preview:
  <https://developer.apple.com/kr/nearby-interaction/>
- Apple Core Bluetooth background behavior:
  <https://developer.apple.com/documentation/corebluetooth/>
- Android connected-device foreground-service requirements:
  <https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device>
