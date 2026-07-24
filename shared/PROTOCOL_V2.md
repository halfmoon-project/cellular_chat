# Locator Protocol v2 (`cellfind/v2`)

This document is the normative wire and cryptography contract for the
serverless two-person finder. Both platform implementations MUST consume the
shared fixtures in `shared/vectors/` and MUST NOT negotiate protocol v1.

Related documents:

- `IMPLEMENTATION_PLAN.md` — product contract, fallback policy, phases.
- `UWB_INTEROP.md` — Apple Nearby Interaction interop byte contract carried by
  the `apple_config` / `apple_shareable` messages defined here.

## 1. Cryptographic suite

| Primitive | Choice |
|---|---|
| DH | X25519 |
| AEAD | ChaCha20-Poly1305 (IETF, 12-byte nonce) |
| Hash | SHA-256 |
| KDF | HKDF-SHA256 (RFC 5869) and HMAC-SHA256 labels |
| Handshake | Noise Protocol Framework, revision 34 |

Noise protocol names:

- Pairing: `Noise_NNpsk0_25519_ChaChaPoly_SHA256`
- Session: `Noise_IKpsk2_25519_ChaChaPoly_SHA256`

Implementations MUST pass the official pattern vectors in
`shared/vectors/noise_official_vectors.json` (extracted from the cacophony
vector suite) and the app-specific vectors in
`shared/vectors/noise_app_vectors.json`.

Notation:

- `HMAC(k, m)` = HMAC-SHA256.
- `HKDF(salt, ikm, info)` = RFC 5869 HKDF-SHA256, output length 32.
- `||` = byte concatenation. Labels are ASCII without terminator.
- `u16BE/u32BE/u64BE` = unsigned big-endian integers.
- All byte examples in vectors are lowercase hex.

Nonce construction (Noise standard): the ChaCha20-Poly1305 nonce is
`4 zero bytes || u64LE(n)` where `n` is the CipherState counter. Each
direction of each transport session has its own key from `Split()`, and keys
are fresh per transport session (fresh ephemerals), so `(key, n)` pairs are
never reused. Reusing a nonce is protocol-fatal: the sender MUST tear down the
session before `n` overflows (implementations MUST re-handshake before
`n = 2^32`).

## 2. Identities, roles, and keys

- Pairing produces a `PairRecord` on each phone. All keys below are
  pair-specific; nothing is shared across pairings.
- Role `A` = inviter (created the invitation). Role `B` = joiner (scanned it).
  Roles are permanent for the life of the pair.
- Each side generates a fresh X25519 static key pair during pairing
  (`staticA`, `staticB` denote the 32-byte raw public keys). The peer's static
  public key is pinned; a changed key is never accepted — re-pairing is
  required.
- 32-byte raw X25519 keys use the standard little-endian u-coordinate
  encoding everywhere (QR, CBOR, derivation inputs).

Derivations (all outputs 32 bytes):

```text
pairingPsk = HMAC(secret,   "cellfind/v2 pairing psk")
pairRoot   = HKDF(salt = h_pairing,
                  ikm  = pairingPsk,
                  info = "cellfind/v2 pair root" || staticA || staticB)
sessionPsk = HMAC(pairRoot, "cellfind/v2 session psk")
discKeyA   = HMAC(pairRoot, "cellfind/v2 discovery A")
discKeyB   = HMAC(pairRoot, "cellfind/v2 discovery B")
confirmA   = HMAC(pairRoot, "cellfind/v2 confirm" || 0x41)
confirmB   = HMAC(pairRoot, "cellfind/v2 confirm" || 0x42)
```

`secret` is the 32-byte invitation secret. `h_pairing` is the final Noise
handshake hash of the completed NNpsk0 pairing handshake.

Displayed pairing fingerprint (both screens MUST show the same value before
commit):

```text
fpr     = SHA256("cellfind/v2 fingerprint" || pairId || staticA || staticB)
display = decimal(u64BE(fpr[0..8]) mod 1_000_000), zero-padded to 6 digits
```

Storage requirements (normative, not on the wire):

- iOS: Keychain, `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`,
  excluded from backup.
- Android: Android Keystore AES-GCM-wrapped record in app-private storage.
- `UserDefaults`, plain `SharedPreferences`, MAC addresses, Wi-Fi Aware
  handles, and BLE identifiers are never security identities.

## 3. Canonical CBOR

All structured payloads are RFC 8949 CBOR restricted to the deterministic
core profile. Encoders MUST and decoders MUST enforce:

- Definite lengths only. No indefinite-length items.
- Integers in shortest form. No bignums.
- Map keys sorted by the bytewise lexicographic order of their encoded form.
- No duplicate map keys.
- No floating point, no tags, no `undefined`, no half/single/double floats.
- Allowed major types: unsigned int, negative int, byte string, text string,
  array, map, and the simple values `false`/`true`/`null`.
- Text strings MUST be valid UTF-8.

A decoder that encounters any violation MUST reject the whole record with a
protocol error. Test cases: `shared/vectors/cbor_vectors.json` (both accept
and reject cases).

All protocol maps use small unsigned-integer keys.

## 4. Invitation

The inviter generates:

- `pairId`: 16 random bytes (public, but never advertised over the air).
- `secret`: 32 random bytes, single-use.
- `createdAt`: unix seconds.

Invitation payload (canonical CBOR array):

```text
invite = [2, pairId (bstr .size 16), secret (bstr .size 32), createdAt (uint)]
```

Text form (QR content and the copy/paste accessibility fallback):

```text
"CF2:" || base64url_nopad(invite)
```

Rules:

- The joiner MUST reject an invitation whose version ≠ 2 or whose
  `createdAt` is more than 15 minutes in the past or 2 minutes in the future.
- The secret is consumed by one successful pairing commit; both sides erase
  it and all ephemeral pairing state afterwards. A consumed or expired
  invitation MUST never be accepted again.
- A short user-chosen PIN is not a valid invitation. (A future PAKE would use
  RFC 9382 SPAKE2; HMAC-with-short-code is forbidden.)

## 5. Records and framing

The unit of exchange on every transport is a **record**:

```text
record = recordType (1 byte) || payload
```

| recordType | Meaning | Payload |
|---|---|---|
| `0x01` | Pairing handshake message | raw Noise NNpsk0 message |
| `0x02` | Session handshake message | raw Noise IKpsk2 message |
| `0x03` | Session transport message | Noise transport ciphertext |
| `0x04` | Pairing transport message | Noise transport ciphertext |

Size limits (MUST be enforced before any other processing):

- Max Noise message: 65535 bytes (Noise limit).
- Max plaintext envelope: 65519 bytes (65535 − 16 tag).
- Max record: 65536 bytes.
- Zero-length records are invalid.

Stream transports (Wi-Fi Aware sockets) frame records as
`u32BE(recordLength) || record`; a declared length of 0 or > 65536 is a fatal
protocol error. Message-oriented transports (Nearby Connections BYTES
payloads) carry exactly one record per payload. BLE GATT uses the
fragmentation layer in §9.

Records MUST be processed strictly in order. The transport plus reassembly
layer guarantees ordered, complete records; the AEAD counter then rejects
any residual reordering, replay, or truncation.

Handshake-phase AEAD uses the Noise handshake hash as associated data (per
the framework). Transport-phase messages (record types `0x03`/`0x04`) use
zero-length associated data, the Noise standard for `Split()` CipherStates;
the envelope's explicit `seq` (and `sid`) fields provide the additional
binding checks.

## 6. Pairing channel (NNpsk0)

Prologue: `"cellfind/v2/pairing" || pairId`.

Noise roles: joiner (role B) = initiator; inviter (role A) = responder.
PSK = `pairingPsk` (§2). The invitation secret itself is never transmitted.

```text
B -> A : record 0x01, Noise NNpsk0 message 1   (pair_hello)
A -> B : record 0x01, Noise NNpsk0 message 2   (pair_challenge)
```

Both handshake payloads are empty. After message 2 both sides hold pairing
CipherStates (initiator→responder key and responder→initiator key) and
`h_pairing`.

Pairing transport messages (record `0x04`) carry this plaintext envelope
(canonical CBOR):

```text
pairingEnvelope = {1: msgType (uint), 2: seq (uint), 3: body (map)}
```

`seq` MUST equal the CipherState counter used to encrypt the message; each
direction counts from 0. A mismatch is fatal.

| msgType | Name | Body |
|---|---|---|
| 64 | `pair_bind` | `{1: role (1=A, 2=B), 2: staticPub (bstr .size 32), 3: maxVersion (uint)}` |
| 65 | `pair_proof` | `{1: mac (bstr .size 32)}` |
| 66 | `pair_complete` | `{}` |
| 67 | `pair_abort` | `{1: reason (uint)}` |

Sequence:

1. `B -> A: pair_bind` (B's static public key). `A -> B: pair_bind`.
   Each side verifies the peer's declared role is the expected one and the
   key sizes are exact. `maxVersion` is the highest supported protocol
   version; both sides record `min(local, peer)` in the PairRecord (currently
   always 2; a value < 2 aborts).
2. Both sides derive `pairRoot` (§2) and stage — but do not persist — the
   PairRecord.
3. `B -> A: pair_proof {mac = confirmB}`. A verifies in constant time, then
   `A -> B: pair_proof {mac = confirmA}`; B verifies.
4. Both UIs display the fingerprint (§2). On user confirmation each side
   sends `pair_complete` and persists the committed PairRecord after it has
   both received the peer's `pair_complete` and sent its own.
5. Both sides mark the invitation consumed and erase `secret`, `pairingPsk`,
   and all handshake state.

Failures (wrong secret, bad MAC, wrong role, oversize, timeout, user cancel)
send `pair_abort` where possible and MUST NOT reveal which field mismatched.
An aborted or interrupted pairing leaves no persisted record and does not
consume an unexpired invitation on the side that never reached step 4.

## 7. Rediscovery tokens

BLE advertisements never contain a static pair ID, name, or key fingerprint.
Each side advertises a 16-byte rotating token derived from its own role's
discovery key:

```text
epoch     = floor(unixSeconds / 120)
input     = 0x02 || u64BE(epoch) || roleByte        (roleByte: 0x41 A, 0x42 B)
token     = HMAC(discKey_role, input)[0..16]
```

- A scanner computes candidate tokens for the selected pair only, for the
  current epoch and the two adjacent epochs, for the peer's role, and matches
  scan results against that set.
- The token is a rendezvous filter only. It is never proof of identity;
  every connection still authenticates with IKpsk2.
- Only the pair currently selected for Find mode is advertised.
- Vectors: `shared/vectors/discovery_vectors.json`.

## 8. Session channel (IKpsk2)

Every new transport connection (including a transport upgrade of an existing
logical session) runs a fresh handshake with fresh ephemerals.

Prologue: `"cellfind/v2/session" || pairId || transportTag`, with
`transportTag` ∈ `"ble"`, `"aware"`, `"nearby"` (ASCII).

Noise roles: the connecting side is the initiator — BLE central, Wi-Fi Aware
subscriber, Nearby Connections discoverer. The initiator uses its own pair
static key as `s` and the pinned peer static key as `rs`.
PSK = `sessionPsk` (§2).

```text
init -> resp : record 0x02, IKpsk2 message 1    (session_hello)
resp -> init : record 0x02, IKpsk2 message 2    (session_auth)
```

Handshake payloads are empty. The responder MUST verify the initiator's
transmitted static key equals the pinned peer key; any other key aborts with
no response. A revoked pair never answers.

Session transport messages (record `0x03`) carry:

```text
sessionEnvelope = {1: msgType (uint), 2: seq (uint), 3: sid (bstr .size 16), 4: body (map)}
```

`seq` MUST equal the CipherState counter (per direction, from 0). `sid` is
the logical Find session ID (§10). Both mismatches are fatal.

| msgType | Name | Body |
|---|---|---|
| 1 | `session_ready` | `{1: capabilities (map §11), 2: findDeadline (uint, unix sec), 3: maxVersion (uint)}` |
| 2 | `ping` | `{1: n (uint)}` |
| 3 | `pong` | `{1: n (uint)}` |
| 4 | `disconnect` | `{1: reason (uint §13)}` |
| 5 | `capabilities` | same body as `session_ready` field 1, re-announcement |
| 6 | `transport_upgrade` | `{1: transport (1=aware, 2=nearby), 2: attemptId (uint)}` |
| 7 | `transport_ack` | `{1: transport (uint), 2: attemptId (uint), 3: accepted (bool)}` |
| 16 | `ranging_offer` | `{1: attemptId (uint), 2: method (uint §12)}` |
| 17 | `ranging_accept` | `{1: attemptId, 2: method}` |
| 18 | `ranging_start` | `{1: attemptId}` |
| 19 | `ranging_stop` | `{1: attemptId, 2: reason (uint)}` |
| 20 | `ranging_error` | `{1: attemptId, 2: code (uint §13)}` |
| 21 | `apple_config` | `{1: attemptId, 2: data (bstr, 48 bytes per UWB_INTEROP.md)}` |
| 22 | `apple_shareable` | `{1: attemptId, 2: data (bstr, 35 bytes per UWB_INTEROP.md)}` |
| 23 | `ni_token` | `{1: attemptId, 2: data (bstr, archived NIDiscoveryToken)}` |
| 24 | `oob_data` | `{1: attemptId, 2: data (bstr, opaque RangingManager OOB bytes)}` |
| 32 | `find_active` | `{1: deadline (uint)}` |
| 33 | `find_stopping` | `{}` |
| 34 | `find_expired` | `{}` |

The initiator's first transport message MUST be `session_ready`; the
responder replies with its own `session_ready` before anything else.
Capabilities and the selected transport are thereby bound to the
authenticated handshake transcript: they are only ever carried inside the
session's AEAD, and the transport is bound via the prologue.

Unknown-message rule: msgType < 128 unknown to the receiver is a fatal
protocol error; msgType ≥ 128 is ignored (reserved for forward-compatible
extensions).

Version negotiation: `maxVersion` in `session_ready` works as in pairing.
There is no downgrade below 2; any frame or field claiming version 1 is
rejected before it reaches the state machine.

## 9. BLE GATT transport

Service and characteristic UUIDs (fixed):

```text
service     4A0C5000-9C6F-4B2E-8FD8-3B6A2E0D5C71
rendezvous  4A0C5001-9C6F-4B2E-8FD8-3B6A2E0D5C71   (read)
inbox       4A0C5002-9C6F-4B2E-8FD8-3B6A2E0D5C71   (write with response)
outbox      4A0C5003-9C6F-4B2E-8FD8-3B6A2E0D5C71   (notify)
```

- Preferred cross-platform roles: Android peripheral/advertiser, iOS
  central/scanner. Same-platform pairs: the side with the bytewise smaller
  pinned static public key acts as central/initiator.
- The advertisement carries the service UUID and, where the platform allows,
  the current token (§7) as service data. `rendezvous` always returns the
  peripheral's current 16-byte token so a central that could not read service
  data (e.g. iOS overflow-area advertising) can verify before handshaking.
- Central→peripheral records go to `inbox`; peripheral→central records come
  from `outbox` notifications.

Fragmentation of one record into ATT payloads (usable payload =
`ATT_MTU − 3`):

```text
fragment      = flags (1 byte) || [u32BE totalLen if FIRST] || chunk
flags.bit7    = FIRST
flags.bit6    = FINAL
flags.bit0..5 = counter mod 64, counting from 0 per record
```

Reassembly MUST reject, with a fatal protocol error: a FIRST while a record
is incomplete, a non-FIRST with no record in progress, a counter that is not
`previous + 1 (mod 64)`, `totalLen` of 0 or > 65536, accumulated bytes
exceeding `totalLen`, a FINAL that does not complete exactly `totalLen`, an
empty chunk, or a record whose reassembly takes longer than 10 seconds.
Duplicate and reordered fragments are therefore rejected by the counter rule.
Vectors (MTU 23, 185, 512 plus malformed cases):
`shared/vectors/fragment_vectors.json`.

## 10. Logical Find session

- Both users explicitly arm Find mode with a deadline (default 30 minutes,
  max 2 hours). All discovery, advertising, and ranging stop at the deadline
  or on user stop; discovery credentials expire with the session.
- The Noise session initiator generates `sid` (16 random bytes) for a new
  logical session and includes it in every transport message. The responder
  adopts the initiator's `sid`.
- Transport upgrade keeps one logical Find session (same `sid`) while moving to
  a higher-preference transport (preference order §4: aware > nearby > ble).
  The **upgrade driver** is the current session's Noise initiator; only it
  starts upgrades, running on top of the working transport and never disturbing
  it on failure.
  - Trigger/observation. After reaching an authenticated, capability-exchanged
    session the driver re-evaluates transports every 5 s while in a
    connected/ranging state. A candidate `K` is upgrade-eligible when its
    preference index is strictly better than the active transport's, `K` is
    locally available at runtime, and the peer's exchanged CapabilitySet
    advertises it (`wifiAware` for aware, `nearbyConnections` for nearby). `ble`
    is never an upgrade target. At most one upgrade attempt is in flight.
  - Upgrade `attemptId`. A monotonically increasing uint scoped to the `sid`,
    counted independently of the ranging `attemptId` (§12), starting at 1.
  - Sequence. (1) The driver sends `transport_upgrade {transport:K,
    attemptId:a}` on the working transport and begins establishing `K` as the
    Noise initiator with a fresh IKpsk2 handshake, prologue `transportTag` = `K`,
    reusing `sid`. (2) The peer replies `transport_ack {transport:K,
    attemptId:a, accepted}` on the working transport: `accepted=true` and it
    accepts `K` as the Noise responder pre-initialized with the existing `sid`;
    `accepted=false` when it cannot host `K`, whereupon the driver abandons the
    attempt and keeps the working transport. A well-typed `transport` that is
    `ble`, unknown, or not strictly better is declined with `accepted=false`,
    not a fatal error. (3) On `K` both sides run the fresh handshake and
    re-exchange `session_ready` (initiator first). The `K` responder MUST reject
    a first-message `sid` ≠ the logical session's `sid`, and either side MUST
    reject a `session_ready` CapabilitySet that differs from the one already
    bound to this `sid` (§14) — both abort transport `K` only.
  - Switchover. All session/ranging/state traffic moves to `K` only after both
    `session_ready` are exchanged on `K`; until then everything, including
    ranging, continues on the working transport. Sequence numbers reset to 0 per
    direction on `K` (fresh `Split()` keys); the previous transport's counters
    are independent. Ranging attempt state (keyed by `sid`/`attemptId`) is
    preserved across switchover and is not renegotiated.
  - Fallback retention. After switchover a previous BLE transport is retained,
    authenticated and idle, as the control fallback; any other previous
    transport is closed with `disconnect {reason: upgraded}`. If the upgraded
    transport is later lost while a retained BLE control session is still
    authenticated, traffic reverts to BLE in place without a new handshake
    (reason `transportLost`, no `signalLost` transition) and upgrade evaluation
    resumes.
  - Failure/timeout. An attempt that does not reach both `session_ready` on `K`
    within 10 s, is declined, or fails the `K` handshake/`sid`/capability checks
    tears down only `K`, leaves the working transport and the Find state machine
    untouched, and backs off before the next attempt (initial 5 s, ×2, cap 60 s;
    reset on any successful upgrade). A successful upgrade emits no Find state
    transition.
  - Idempotency. `transport_upgrade`/`transport_ack` for an already-applied
    `(sid, attemptId)` are no-ops; the responder re-sends its cached
    `transport_ack` and never opens a second `K` connection for that
    `(sid, attemptId)`.
- Duplicate connections for the same pair — two authenticated connections
  presenting different `sid`s — are reconciled by both sides keeping the one
  whose Noise initiator has the bytewise smaller static public key and closing
  the other with `disconnect {reason: duplicate}`. A second authenticated
  connection presenting the current logical session's `sid` is the upgrade
  transport above, not a duplicate. Duplicate `ranging_start`/`ranging_stop` for
  an already-applied `(sid, attemptId)` are idempotent no-ops.

State machine (both platforms implement the identical reducer; conformance
fixtures in `shared/vectors/state_transitions.json`):

```text
idle -> arming -> searching -> p2pConnecting -> authenticating -> connected
     -> rangingStarting
     -> directionAvailable | distanceOnly | proximityOnly | connectedOnly
     -> signalLost -> retryWait -> searching
     -> stopped | expired | failed
```

Every transition carries a reason code (§13). Entering `signalLost` clears
the last measurement; stale values are never displayed. A ranging failure
falls back (§12) without leaving `connected`; it is never reported as a
pairing or authentication failure.

## 11. CapabilitySet

Canonical CBOR map:

| Key | Field | Type |
|---|---|---|
| 1 | os (1=android, 2=ios) | uint |
| 2 | osVersion | text |
| 3 | appVersion | text |
| 4 | wifiAware | bool |
| 5 | nearbyConnections | bool |
| 6 | bleCentral | bool |
| 7 | blePeripheral | bool |
| 8 | uwbPresent | bool |
| 9 | uwbAzimuth | bool |
| 10 | uwbElevation | bool |
| 11 | appleInteropUwb | bool |
| 12 | niEdm | bool |
| 13 | wifiRtt | bool |
| 14 | backgroundRanging | bool |

All values reflect runtime capability checks, never OS-version guesses.
`appleInteropUwb` is true on iOS only for 26.1+, and on Android only when the
raw-UWB stack reports support for the interop profile in `UWB_INTEROP.md`.
Unknown keys are ignored (forward compatibility); missing keys default to
`false`/empty.

## 12. Ranging selection

Deterministic function over both CapabilitySets, computed identically on both
sides (fixtures: `shared/vectors/capability_selection.json`):

1. `ni_peer` (3): both `os = ios`, both `uwbPresent`. EDM enabled only if
   both `niEdm`.
2. `uwb_android_oob` (2): both `os = android`, both `uwbPresent`. Runs
   RangingManager default OOB, `RANGING_MODE_HIGH_ACCURACY_PREFERRED`; the
   `oob_data` messages carry the platform OOB bytes over the authenticated
   session. The UI uses only the technology and nullable measurements the
   platform reports.
3. `uwb_apple_interop` (1): one `os = ios`, one `os = android`, both
   `uwbPresent`, both `appleInteropUwb`. Android is the initiator/controller
   per `UWB_INTEROP.md`.
4. `ble_rssi` (4): always available fallback. RSSI is filtered (rolling
   median plus hysteresis) into `veryNear` / `near` / `far` / `unknown`
   bands; it is never shown as an exact distance and never produces an arrow.

Direction UI appears only after a fresh platform angle sample. A missing
angle degrades to distance-only; missing distance degrades to proximity-only.
On UWB invalidation or sustained sample loss the session keeps the
authenticated link, falls back to `ble_rssi` immediately, and retries UWB
with bounded backoff (initial 5 s, ×2, cap 60 s).

Each attempt uses a fresh `attemptId` (monotonically increasing uint per
`sid`) and fresh ranging material; stale Apple shareable configurations are
never reused. Ranging results are never an authentication factor.

Attempt negotiation per method:

- `ni_peer`: the Noise session initiator sends `ranging_offer`; the peer
  answers `ranging_accept` (echoing the attempt ID); the offerer sends
  `ranging_start`; both exchange `ni_token`.
- `uwb_apple_interop`: the Android side (always the UWB controller) opens
  the attempt by sending `apple_config` directly — that message is the offer
  and carries the fresh `attemptId`; iOS adopts the ID and echoes it in
  `apple_shareable`. No separate `ranging_offer` round-trip is used.
- `uwb_android_oob`: either side's first `oob_data` for a fresh `attemptId`
  opens the attempt; the platform OOB payloads negotiate the rest.
- `ble_rssi`: local-only; no messages.

The offered/accepted/implied `method` MUST lie in the mutually-supported set of
both exchanged CapabilitySets (§14); an out-of-transcript method is a
`capabilityMismatch` disconnect, not a ranging fallback.

Duplicate operations for an already-applied `(sid, attemptId)` are
idempotent no-ops (§10); `ranging_stop`/`ranging_error` close an attempt.

## 13. Reason and error codes

Disconnect / transition reasons:

| Code | Meaning |
|---|---|
| 1 | normal |
| 2 | expired |
| 3 | revoked |
| 4 | duplicate |
| 5 | protocolError |
| 6 | authFailed |
| 7 | timeout |
| 8 | transportLost |
| 9 | upgraded |
| 10 | capabilityMismatch |
| 11 | userStopped |
| 12 | permissionRequired |
| 13 | radioUnavailable |
| 14 | backgroundSuspended |
| 15 | identityMismatch |

Ranging error codes: 1 unsupported, 2 configRejected, 3 platformError,
4 lostSignal, 5 timeout.

## 14. Mandatory rejection rules

Before any message reaches the state machine, implementations MUST reject:

- Records or frames exceeding the §5 limits, zero-length records, unknown
  record types.
- Noncanonical or duplicate-key CBOR (§3), wrong field types, missing
  required fields, extra unknown keys in fixed-schema pairing messages.
- AEAD failures, counter/seq mismatch, `sid` mismatch, replays, rollback.
- Any protocol version other than 2 and any downgrade attempt.
- Handshake messages after the handshake completed, transport messages
  before it completed.
- A session initiator static key that does not match the pinned peer key.
- Fragmentation violations (§9).

Capability-transcript consistency (disconnect with `capabilityMismatch`): the
CapabilitySet each side sends in its first `session_ready` is bound to the
logical session `sid`. Using only the two bound CapabilitySets, implementations
MUST reject:

- A later `capabilities` (5) or `session_ready` from a peer whose decoded
  CapabilitySet differs in any §11 field from that peer's first bound set.
  Comparison is over the normalized 14-field set (unknown keys ignored, missing
  keys defaulted), so a benign re-encoding is not a mismatch.
- A `ranging_offer`/`ranging_accept` (16/17) whose `method` is outside the
  mutually-supported set: `ni_peer` requires both `os=ios` and both
  `uwbPresent`; `uwb_android_oob` requires both `os=android` and both
  `uwbPresent`; `uwb_apple_interop` requires mixed `os`, both `uwbPresent`, and
  both `appleInteropUwb`; `ble_rssi` is always supported. A `ranging_accept`
  whose `method` differs from the matching `ranging_offer`'s is also a mismatch.
- An implicit offer — `apple_config` (21) for `uwb_apple_interop`, `oob_data`
  (24) for `uwb_android_oob`, `ni_token` (23) for `ni_peer` — whose implied
  method is outside that mutually-supported set.

On any of these the detecting side sends `disconnect {reason:
capabilityMismatch}` and tears down the logical session; it is a hard failure,
never a ranging fallback and never retried. During a transport upgrade a
capability difference observed on the upgrade transport aborts only that
transport (§10).

A fatal error tears down the transport connection and surfaces a §13 reason;
it never crashes the app and never leaves a half-open session that blocks the
next attempt.

## 15. Shared fixtures

| File | Contents |
|---|---|
| `noise_official_vectors.json` | cacophony vectors for both patterns |
| `noise_app_vectors.json` | NNpsk0 pairing handshake with the app prologue/PSK derivation and the full pairing-channel transport records |
| `derivation_vectors.json` | pairingPsk, pairRoot, sessionPsk, discovery keys, confirm MACs, fingerprint |
| `discovery_vectors.json` | tokens for epochs E−1/E/E+1, both roles |
| `cbor_vectors.json` | canonical encode cases and reject cases |
| `envelope_vectors.json` | IKpsk2 session handshake, split keys, session transport records for real message types, and the wrong-PSK / substituted-static / bit-flip / replay failure cases |
| `fragment_vectors.json` | fragmentation at MTU 23/185/512 and malformed-fragment reject cases |
| `invite_vectors.json` | invitation encode/decode and reject cases |
| `capability_selection.json` | ranging-method selection matrix |
| `state_transitions.json` | state-machine conformance transitions |
| `apple_uwb_vectors.json` | 48-byte accessory config and 35-byte shareable config (see UWB_INTEROP.md) |

`tools/genvectors/` contains the independent Python reference implementation
that generated the app-specific fixtures; it validates itself against the
cacophony vectors before emitting anything. Kotlin and Swift implementations
MUST pass every fixture byte-for-byte.
