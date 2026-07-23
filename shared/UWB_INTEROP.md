# iOS / Android UWB interoperability note

This note is the implementation contract for the `apple_config` and
`apple_shareable` messages in `PROTOCOL_V2.md`.

## Status and scope

- Android is the UWB **initiator/controller** and advertises
  `RANGING_ROLE = 1`.
- iOS is the responder/controlee and uses
  `NINearbyAccessoryConfiguration`.
- Android uses the Android 16 platform Ranging API (`android.ranging.*`), not
  the older `androidx.core.uwb` API.
- The 32-byte inner accessory configuration below is from Apple's
  **Nearby Interaction with UWB Interoperability Specification, Developer
  Preview R4**, dated 2025-09-19 and published for iOS 26.1. Its inner format
  version is 2.0.

The v2.0 format is necessary for this prototype because it lets Android request
the exact fixed timing profile exposed by `UwbRangingParams`:

```text
hopping enabled, 6 slots, 2400 RSTU slot duration, 240 ms ranging interval
```

The stable R3 inner format is v1.1 and has no request fields. In captures, iOS
selected values such as 3600 RSTU and 180 ms. Android 16's public
`UwbRangingParams` cannot represent that combination, so a v1.1 fallback can
successfully exchange configuration yet still fail to produce physical ranging
results.

This is a preview protocol, not a claim of production compatibility. The direct
R4 preview PDF used for this note is no longer the current unauthenticated
download as of 2026-07; Apple's landing page now requires a developer sign-in
and may point to a newer preview. Revalidate this layout against the current
Apple agreement/specification before shipping.

## OOB sequence

UWB is a ranging radio, not the data link. The authenticated Noise session
from `PROTOCOL_V2.md` is the out-of-band (OOB) link.

1. Capability negotiation (PROTOCOL_V2.md §12) selects `uwb_apple_interop`
   deterministically on both sides; no `ranging_offer` round-trip is used for
   this method.
2. Android creates a fresh UWB short address and a fresh attempt ID, builds
   the 48-byte Accessory Configuration Data below, and sends it as the
   byte-string payload of `apple_config` — this message opens the attempt.
   iOS adopts the attempt ID and echoes it in `apple_shareable`.
3. iOS passes the 48 bytes directly to
   `NINearbyAccessoryConfiguration(data:)`. It assigns an `NISessionDelegate`
   and calls `NISession.run(_:)`.
4. iOS receives
   `session(_:didGenerateShareableConfigurationData:for:)` and immediately
   sends that `Data` unchanged as `apple_shareable`.
5. Android parses and validates the 35-byte shareable data, creates the raw
   Android ranging session, and starts it immediately. Apple's callback puts
   the iOS session in a limited preparedness window.
6. Both apps display only measurements actually returned by their platform.
7. `ranging_stop`, disconnect, retry, or failure tears down both sessions. A
   retry MUST create a new accessory configuration and a new random source
   address; stale shareable configuration MUST NOT be reused.

The payload contains only the Apple configuration bytes. Do not prepend the
`0x01` message ID used by Apple's BLE sample application; the protocol v2
message type already provides framing.

## 48-byte Accessory Configuration Data (Android to iOS)

All integer fields in both the outer and inner Apple messages are
little-endian. Byte strings and RFU fields are copied in listed order.

### Outer Accessory Protocol wrapper (16 bytes)

| Offset | Size | Type | Field | Required value |
| ---: | ---: | --- | --- | --- |
| 0 | 2 | UInt16 LE | `MajorVersion` | `1` |
| 2 | 2 | UInt16 LE | `MinorVersion` | `0` |
| 4 | 1 | UInt8 | `PreferredUpdateRate` | `20` (`User Interactive`) |
| 5 | 10 | bytes | RFU | all zero |
| 15 | 1 | UInt8 | `UWBConfigDataLength` | `32` (`0x20`) |

### Inner `UWBConfigData` v2.0 (32 bytes, outer offset 16)

The "inner offset" column starts at outer offset 16.

| Inner offset | Outer offset | Size | Type | Field | Prototype value |
| ---: | ---: | ---: | --- | --- | --- |
| 0 | 16 | 2 | UInt16 LE | `MajorVersion` | `2` |
| 2 | 18 | 2 | UInt16 LE | `MinorVersion` | `0` |
| 4 | 20 | 4 | UInt32 LE | Manufacturer ID | `0` |
| 8 | 24 | 4 | UInt32 LE | UWB chipset model ID | `0` |
| 12 | 28 | 4 | UInt32 LE | UWB middleware version | `0x00020000` |
| 16 | 32 | 1 | UInt8 | `RANGING_ROLE` | `1` (initiator/controller) |
| 17 | 33 | 2 | UInt16 LE | `SOURCE_ADDRESS` | fresh random short address |
| 19 | 35 | 2 | UInt16 LE | Maximum UWB clock drift | `50` ppm |
| 21 | 37 | 4 | bytes | RFU | all zero |
| 25 | 41 | 1 | UInt8 | `REQUESTED_HOPPING_MODE` | `1` (enabled) |
| 26 | 42 | 2 | UInt16 LE | `REQUESTED_NUM_SLOTS_PER_RROUND` | `6` |
| 28 | 44 | 2 | UInt16 LE | `REQUESTED_SLOT_DURATION` | `2400` RSTU |
| 30 | 46 | 2 | UInt16 LE | `REQUESTED_RANGING_INTERVAL` | `240` ms |

Apple says the three identification fields are "determined by manufacturer" and
does not reserve or prohibit zero in the public table. Thus the prototype
values are syntactically valid, but they are not confirmed OEM/MFi production
identifiers. Android exposes no public API for querying these identifiers.

Likewise, `50` ppm is a prototype assumption. It is valid only if it is at
least the actual maximum drift of the phone's UWB clock. The production value
must come from the Android OEM/chipset vendor.

### Deterministic 48-byte vector

This vector uses:

```text
Android UwbAddress API bytes = AA BB
Apple numeric SOURCE_ADDRESS = 0xAABB
Apple little-endian wire bytes = BB AA
```

Hex, exactly 48 bytes:

```text
010000001400000000000000000000200200000000000000000000000000020001bbaa3200000000000106006009f000
```

Grouped by 16-byte offset:

```text
0000: 01 00 00 00 14 00 00 00 00 00 00 00 00 00 00 20
0010: 02 00 00 00 00 00 00 00 00 00 00 00 00 00 02 00
0020: 01 bb aa 32 00 00 00 00 00 01 06 00 60 09 f0 00
```

The encoder should generate a new source address in production, not use
`0xAABB`.

## 35-byte Apple shareable configuration (iOS to Android)

For v2.0, the returned data is 35 bytes. All integers are little-endian.

| Offset | Size | Type | Field | Android use |
| ---: | ---: | --- | --- | --- |
| 0 | 2 | UInt16 LE | `MajorVersion` | require `2` |
| 2 | 2 | UInt16 LE | `MinorVersion` | require `0` |
| 4 | 1 | UInt8 | `ConfigDataLength` | require `30` |
| 5 | 2 | ASCII | `REGULATORY_COUNTRY_CODE` | log/retain |
| 7 | 4 | UInt32 LE | `SESSION_ID` | `UwbRangingParams` session ID |
| 11 | 1 | UInt8 | `PREAMBLE_ID` | complex-channel preamble; expected 9...12 |
| 12 | 1 | UInt8 | `CHANNEL_NUMBER` | complex-channel channel |
| 13 | 2 | UInt16 LE | `NUM_SLOTS_PER_RROUND` | require `6` |
| 15 | 2 | UInt16 LE | `SLOT_DURATION` | require `2400` RSTU |
| 17 | 2 | UInt16 LE | `RANGING_DURATION` | require `240` ms |
| 19 | 1 | UInt8 | `RANGING_ROUND_CONTROL` | log; observed compatible value `0x03` |
| 20 | 6 | bytes | `STATIC_STS_IV` | append unchanged after Vendor ID |
| 26 | 2 | UInt16 LE | `DEST_ADDRESS` | iPhone peer UWB short address |
| 28 | 2 | UInt16 LE | Maximum UWB clock drift | log/retain |
| 30 | 4 | bytes | RFU | require all zero for this version |
| 34 | 1 | UInt8 | `HOPPING_MODE` | require `1` |

Apple may grant or decline individual requested values. Android's API 36
predefined profile has no setters for slots-per-round or hopping. Therefore
Android MUST validate the returned slots, slot duration, ranging duration, and
hopping values before opening the radio. If any differ, report an unsupported
configuration instead of starting a known-mismatched session.

Do not convert the six-byte `STATIC_STS_IV` to an integer or reverse it.

`SESSION_ID` is an Apple UInt32 while the Android constructor accepts a signed
Kotlin/Java `Int`. Parsing with little-endian `getInt` preserves the required
32-bit pattern even when the resulting language value is negative.

## Address and Vendor ID byte order

Apple configuration addresses are UInt16 values and therefore little-endian on
the wire. Android `UwbAddress` holds the displayed address bytes in
most-significant-byte-first order.

For a numeric short address `0xAABB`:

```text
Android UwbAddress.fromBytes input: AA BB
Apple SOURCE_ADDRESS / DEST_ADDRESS wire bytes: BB AA
```

Recommended source generation:

1. Generate a random UInt16 value other than reserved/broadcast values.
2. Give Android `[(value >> 8), value]`.
3. Give Apple `[value, (value >> 8)]` through a UInt16 LE writer.

For the reply, parse `DEST_ADDRESS` as a UInt16 LE value, then give Android its
high byte followed by its low byte. This also avoids relying on historical
Android 13-and-lower address reversal behavior; this app requires API 36.

Apple's default FiRa Static STS Vendor ID is numeric `0x004C`. The Android
session-key byte array is a FiRa/UCI byte array, not an Apple UInt16 field:

```kotlin
val sessionKeyInfo =
    byteArrayOf(0x00, 0x4C) + shareable.copyOfRange(20, 26)
```

Use `00 4C`, not `4C 00`. The current AOSP backend passes these first two bytes
to the FiRa `VendorId` parameter as-is. Physical Android UWB logs also show the
Apple setting as `VendorId [0, 76]`.

## Android 16 `RangingManager` parameters

The fixed API 36 profile corresponding to the v2.0 requests is:

```text
config ID:             UwbRangingParams.CONFIG_UNICAST_DS_TWR (1)
role:                  RangingPreference.DEVICE_ROLE_INITIATOR
raw config:            RawInitiatorRangingConfig
update rate:           RawRangingDevice.UPDATE_RATE_NORMAL (1, 240 ms)
slot duration:         UwbRangingParams.DURATION_2_MS (2, maps to 2400 RSTU)
slots per round:       6 (fixed internally by config ID 1)
hopping:               enabled (fixed internally by config ID 1)
session key info:      00 4C || STATIC_STS_IV
angle requested:       azimuth capability가 있을 때 true, 아니면 false
data notification:     enabled
```

Core construction:

```kotlin
val params = UwbRangingParams.Builder(
    parsed.sessionId,
    UwbRangingParams.CONFIG_UNICAST_DS_TWR,
    sourceAddress,
    peerAddress,
)
    .setComplexChannel(
        UwbComplexChannel.Builder()
            .setChannel(parsed.channel)
            .setPreambleIndex(parsed.preamble)
            .build()
    )
    .setRangingUpdateRate(RawRangingDevice.UPDATE_RATE_NORMAL)
    .setSessionKeyInfo(byteArrayOf(0x00, 0x4C) + parsed.staticStsIv)
    .setSlotDuration(UwbRangingParams.DURATION_2_MS)
    .build()

val rawDevice = RawRangingDevice.Builder()
    .setRangingDevice(RangingDevice.Builder().build())
    .setUwbRangingParams(params)
    .build()

val sessionConfig = SessionConfig.Builder()
    .setAngleOfArrivalNeeded(caps.isAzimuthalAngleSupported)
    .setDataNotificationConfig(
        DataNotificationConfig.Builder()
            .setNotificationConfigType(
                DataNotificationConfig.NOTIFICATION_CONFIG_ENABLE
            )
            .build()
    )
    .build()

val preference = RangingPreference.Builder(
    RangingPreference.DEVICE_ROLE_INITIATOR,
    RawInitiatorRangingConfig.Builder()
        .addRawRangingDevice(rawDevice)
        .build(),
)
    .setSessionConfig(sessionConfig)
    .build()

val rangingSession = rangingManager.createRangingSession(executor, callback)
rangingSession.start(preference)
```

Manifest/runtime requirements:

```xml
<uses-permission android:name="android.permission.RANGING" />
```

Request it at runtime. API level 36 alone does not imply that the phone contains
UWB or that its OEM stack supports Apple interoperability. Inspect
`UwbRangingCapabilities`, including supported channel, preamble, update rate,
slot duration, azimuth, and elevation, before starting.

## iOS calls

```swift
let configuration = try NINearbyAccessoryConfiguration(data: accessoryData)

if NISession.deviceCapabilities.supportsCameraAssistance {
    configuration.isCameraAssistanceEnabled = true
}

let session = NISession()
session.delegate = self
session.run(configuration)
```

Forward the delegate result immediately and unchanged:

```swift
func session(
    _ session: NISession,
    didGenerateShareableConfigurationData data: Data,
    for object: NINearbyObject
) {
    sendShareableConfiguration(data)
}
```

Receive ranging updates through:

```swift
func session(_ session: NISession, didUpdate nearbyObjects: [NINearbyObject])
```

Required usage descriptions:

- `NSNearbyInteractionUsageDescription`
- `NSCameraUsageDescription` when Camera Assistance is enabled

There is no general `com.apple.developer.nearby-interaction` app entitlement.
Other transports or background modes can have separate entitlements and
requirements.

## Direction and UI behavior

Direction is conditional on hardware, orientation, radio conditions, and
platform output. It cannot be promised for every iPhone/Android pair.

Android:

- Check `UwbRangingCapabilities.isAzimuthalAngleSupported()` and request it with
  `SessionConfig.Builder().setAngleOfArrivalNeeded(true)` only when supported.
  Start a distance-only session with `false` otherwise.
- Also check
  `isElevationAngleSupported()`.
- Read `RangingData.azimuth` and `RangingData.elevation`.
- Either measurement can be `null`, including on a capability-advertising
  device.
- `RangingMeasurement.measurement` uses degrees for angles and meters for
  distance.

iOS:

- Check `NISession.deviceCapabilities.supportsDirectionMeasurement`.
- `NINearbyObject.direction` is an optional unit vector.
- `NINearbyObject.horizontalAngle` is optional and is in **radians**, not
  degrees.
- `verticalDirectionEstimate` is categorical
  (`unknown`, `same`, `above`, `below`, or `aboveOrBelow`).
- Camera Assistance may improve/enable a useful horizontal direction only when
  `supportsCameraAssistance` is true and its visual algorithm converges. It is
  still not a guarantee.

The UI MUST keep a distance-only state and MUST NOT fabricate an arrow from
RSSI, last-known angle, or the peer's self-reported angle. An arrow is relative
to the local phone's current reference frame, not a global venue coordinate.

In a crowded venue, human-body blockage and multipath reflections can make
distance or angle intermittent. UWB still helps find the selected paired
device, but it does not create a venue map and does not locate unpaired users.

## Production and physical-test caveats

- Apple's public accessory page tells accessory manufacturers to use a
  Nearby Interaction accessory protocol implementation and an MFi-certified
  UWB solution. Chipset vendors are directed to FiRa membership and the MFi
  program.
- Google officially documents Android 16 Ranging API interoperability with
  iOS, but an arbitrary Android 16 UWB phone is not thereby guaranteed to
  implement every Apple profile detail.
- The zero identification fields and 50 ppm drift in this prototype are not
  vendor-certified values.
- The v2.0 payload comes from a developer-preview specification. Test
  `NINearbyAccessoryConfiguration(data:)` on the exact supported iOS releases.
- Simulator/emulator tests can validate codecs and state machines only. Success
  requires two physical UWB devices and must verify: config acceptance,
  shareable length/version, exact negotiated timing, first distance result,
  azimuth availability, stop/retry, and failure after deliberate byte-order or
  timing mismatch.

Until that device matrix passes, describe iOS/Android direction as
**experimental and physically unverified**, not merely as an OS-version
feature.

## Sources

- [Apple Nearby Interaction with UWB landing page](https://developer.apple.com/nearby-interaction/)
- [Apple Nearby Interaction with UWB landing page (Korean; explicitly describes MFi-certified UWB solutions)](https://developer.apple.com/kr/nearby-interaction/)
- [Apple Nearby Interaction framework](https://developer.apple.com/documentation/nearbyinteraction)
- [Apple `NINearbyAccessoryConfiguration`](https://developer.apple.com/documentation/nearbyinteraction/ninearbyaccessoryconfiguration)
- [Apple shareable-configuration delegate](https://developer.apple.com/documentation/nearbyinteraction/nisessiondelegate/session%28_%3Adidgenerateshareableconfigurationdata%3Afor%3A%29)
- [Apple Developer Preview R4 PDF URL used for the v2.0 tables](https://developer.apple.com/download/files/Nearby-Interaction-with-UWB-Interoperability-Specification-Developer-Preview-R4.pdf)
- [Apple stable R3 interoperability PDF](https://developer.apple.com/download/files/Nearby-Interaction-with-UWB-Interoperability-Specification-R3.pdf)
- [Apple Accessory Protocol R4 PDF](https://developer.apple.com/download/files/Nearby-Interaction-Accessory-Protocol-Specification-R4.pdf)
- [Apple entitlements reference thread (states that there is no general Nearby Interaction entitlement)](https://developer.apple.com/forums/topics/code-signing-topic/code-signing-topic-entitlements)
- [Android: Range between devices, including iOS UWB interoperability](https://developer.android.com/develop/connectivity/ranging)
- [Android `UwbRangingParams`](https://developer.android.com/reference/android/ranging/uwb/UwbRangingParams)
- [Android `UwbRangingParams.Builder`](https://developer.android.com/reference/android/ranging/uwb/UwbRangingParams.Builder)
- [Android `RawRangingDevice`](https://developer.android.com/reference/android/ranging/raw/RawRangingDevice)
- [Android `RangingData`](https://developer.android.com/reference/android/ranging/RangingData)
- [Android `RangingMeasurement`](https://developer.android.com/reference/android/ranging/RangingMeasurement)
- [Android `UwbRangingCapabilities`](https://developer.android.com/reference/android/ranging/uwb/UwbRangingCapabilities)
- [Android UWB byte-order compatibility note](https://developer.android.com/develop/connectivity/uwb)
- [AOSP predefined config timing values (`Utils.java`)](https://android.googlesource.com/platform/packages/modules/Uwb/+/refs/heads/main/ranging/uwb_backend/src/com/android/ranging/uwb/backend/internal/Utils.java)
- [AOSP session-key and address conversion (`ConfigurationManager.java`)](https://android.googlesource.com/platform/packages/modules/Uwb/+/refs/heads/main/ranging/uwb_backend/src/com/android/ranging/uwb/backend/internal/ConfigurationManager.java)
