# Cellular Chat wire protocol v1

This document is the interoperability contract between the native iOS and
Android applications. Normative terms such as MUST and MUST NOT are deliberate.

## 1. Connection ID

The connection ID is both a discovery selector and a shared secret.

1. Remove leading and trailing ASCII spaces (`0x20`).
2. Accept only 6–32 ASCII characters from `[A-Za-z0-9-]`.
3. Convert `a`–`z` to ASCII uppercase.

The result is `normalizedID`.

```text
roomHash = lowercaseHex(SHA256(UTF8(normalizedID)))
authKey  = SHA256(UTF8("cellchat-v1") || 0x00 || UTF8(normalizedID))
```

The raw connection ID MUST NOT be advertised or sent over the connection.
`roomHash` is not a password hash and short IDs can be guessed, so the UI should
encourage random IDs. The authentication exchange below is the authoritative
same-ID check.

## 2. Discovery and connection ownership

- Bonjour / Android NSD service type: `_cellchat._tcp` (`_cellchat._tcp.` where
  the platform API requires the trailing dot).
- Service name: `cc1-<first 12 roomHash chars>-<32 lowercase UUID hex chars>`.
- TXT keys:
  - `v=1`
  - `room=<64-character roomHash>`
  - `device=<canonical lowercase UUID with hyphens>`
  - `platform=ios` or `platform=android`
- Both apps advertise and browse.
- Ignore the local device and results whose full TXT `room` differs. If TXT is
  unavailable, the 12-character hash in the service name MAY be used to attempt
  a connection; authentication still MUST succeed.
- Compare canonical lowercase device UUID strings. The lexicographically larger
  UUID opens the outbound TCP connection. The smaller UUID only accepts it.
  This prevents two connections for the same pair.
- Keep at most one authenticated connection per remote device UUID. A connection
  that violates ownership or duplicates an existing connection is closed.

Apple peer-to-peer Wi-Fi may be enabled on iOS, but this Apple-only link is not
assumed by the cross-platform contract. The interoperable baseline is an
internet-free local network (including a local-only hotspot).

## 3. Framing

Each message is compact UTF-8 JSON prefixed by an unsigned 4-byte big-endian
length. The length covers JSON bytes only. Receivers MUST reject zero-length
frames, invalid JSON, or frames over 1,048,576 bytes.

Every object contains:

```json
{"v":1,"type":"message_type"}
```

Unknown message types are ignored after authentication. Unknown fields are
ignored so compatible fields can be added later.

## 4. Same-ID mutual authentication

Nonces are 16 cryptographically random bytes encoded with standard padded
Base64. UUIDs use canonical lowercase form.

### 4.1 Client to server: `hello`

```json
{
  "v": 1,
  "type": "hello",
  "deviceId": "client UUID",
  "displayName": "client name",
  "platform": "ios|android",
  "roomHash": "64 lowercase hex characters",
  "clientNonce": "Base64",
  "capabilities": ["chat", "file", "apple-peer-ni", "apple-accessory-ni", "android-raw-uwb"]
}
```

The server MUST compare `roomHash` in constant time before continuing.

### 4.2 Server to client: `challenge`

```json
{
  "v": 1,
  "type": "challenge",
  "deviceId": "server UUID",
  "displayName": "server name",
  "platform": "ios|android",
  "clientNonce": "the received value",
  "serverNonce": "Base64",
  "capabilities": ["chat", "file"]
}
```

### 4.3 Client to server: `auth`

Build `clientTranscript` by joining these UTF-8 fields with one NUL byte:

```text
client, v1, clientDeviceId, serverDeviceId, clientNonce, serverNonce
```

```text
clientProof = Base64(HMAC-SHA256(authKey, clientTranscript))
```

```json
{"v":1,"type":"auth","proof":"Base64 clientProof"}
```

### 4.4 Server to client: `auth_ok`

After constant-time validation, build `serverTranscript` identically except its
first field is `server`:

```text
server, v1, clientDeviceId, serverDeviceId, clientNonce, serverNonce
```

```json
{"v":1,"type":"auth_ok","proof":"Base64 serverProof"}
```

The client MUST validate the server proof before exposing the connection. Any
failure sends an optional `error` and closes the socket.

## 5. Application messages

All messages in this section are valid only after mutual authentication.
Timestamps are Unix epoch milliseconds.

### Chat

`text` MUST be non-empty and at most 8,000 UTF-8 bytes. Senders MUST enforce
the byte limit before transmitting and receivers ignore oversized messages.

```json
{
  "v": 1,
  "type": "chat",
  "messageId": "UUID",
  "senderId": "UUID",
  "senderName": "display name",
  "timestamp": 1784800000000,
  "text": "UTF-8 message"
}
```

### Files

Files use explicit offer and acceptance. Names are display-only and MUST be
sanitized before choosing a destination path. A file is at most 104,857,600
bytes (100 MiB). A raw chunk is at most 49,152 bytes, keeping its Base64 JSON
frame small.

```json
{"v":1,"type":"file_offer","transferId":"UUID","name":"photo.jpg","size":12345,"sha256":"lowercase hex"}
{"v":1,"type":"file_accept","transferId":"UUID","accepted":true}
{"v":1,"type":"file_chunk","transferId":"UUID","index":0,"data":"Base64"}
{"v":1,"type":"file_complete","transferId":"UUID","chunkCount":1}
```

Chunks MUST arrive in increasing, zero-based index order. The receiver verifies
the declared size and SHA-256 before reporting success. Invalid or declined
transfers are removed.

## 6. Ranging and direction

Ranging is optional at the transport level but the UI MUST truthfully distinguish
`unsupported`, `distanceOnly`, `directionAvailable`, `searching`, and `failed`.
It MUST NOT synthesize a direction when the platform does not report one.

Each side first sends:

```json
{
  "v": 1,
  "type": "ranging_capabilities",
  "applePeerNI": true,
  "appleAccessoryNI": false,
  "androidRawUwb": false,
  "distance": true,
  "direction": true
}
```

Ranging is user-controlled. Selecting a connected peer sends
`{"v":1,"type":"ranging_start"}`. Either side MAY send the same message while
starting locally; duplicate start messages are idempotent and MUST NOT create a
second ranging session. Stopping sends `{"v":1,"type":"ranging_stop"}` and both
sides tear down the active ranging session for that peer.

After `ranging_start`, two Apple devices exchange discovery tokens. For an
Android/iPhone pair, Android is the accessory-side controller and sends the
accessory configuration; iOS answers with the generated shareable
configuration.

### Apple peer mode

Two Apple devices exchange an `NIDiscoveryToken` archived with secure coding:

```json
{"v":1,"type":"ni_discovery_token","data":"Base64 keyed archive"}
```

Each side runs `NINearbyPeerConfiguration(peerToken:)` in its own `NISession`.

### Apple accessory / Android raw UWB mode

An Android 16 UWB device and iPhone use the Apple Nearby Interaction Accessory
Protocol with Android `RANGING_SESSION_RAW`. OOB payloads use:

```json
{"v":1,"type":"ni_accessory_config","data":"Base64 Apple Accessory Configuration Data"}
{"v":1,"type":"ni_shareable_config","data":"Base64 Apple shareable configuration data"}
```

The binary payload is opaque to iOS. Android builds/parses it according to the
current Apple interoperability specification, then configures
`UwbRangingParams.CONFIG_UNICAST_DS_TWR`, matching addresses, channel, preamble,
session ID, Vendor ID + Static STS IV, slot duration, and update rate. Android
requests Angle of Arrival. A device or vendor stack that does not expose angle
data is reported as distance-only.

The exact preview-v2 binary layout, fixed timing profile, byte-order rules, and
physical-device caveats used by this prototype are documented in
`UWB_INTEROP.md`.

## 7. Test vector

The canonical vector is also machine-readable in `TEST_VECTOR.json`.

- Input ID: `Abc-1234 `
- Normalized ID: `ABC-1234`
- roomHash: `db0f3cc6d7f064c0472ee745c6afdce3c097959263e75784f9f8df5fe2e07ecf`
- authKey: `5fa6ca86646d18558c862501efafaad023dfc83ec5d05cbc64c987f493a54d14`
- client proof: `tfw99T2v/29w4q5gtDSPjlJaUtizR/FZnQHnQXRymfc=`
- server proof: `nlpXIjJFKUZDi5B6Ox8ukcFv59oACLZYBzoe/hTMfUI=`
