"""Generates the shared cross-platform fixtures in shared/vectors/.

Usage:
    python gen_vectors.py <path-to-cacophony.txt> <output-dir>

The Noise reference is validated against the official cacophony vectors for
both app patterns before any fixture is written.
"""

import hashlib
import json
import struct
import sys
from base64 import urlsafe_b64encode

import cbor_ref
import noise_ref
from noise_ref import HandshakeState, hmac, rfc5869_hkdf, sha256

H = bytes.fromhex


def hx(b: bytes) -> str:
    return b.hex()


# ---------------------------------------------------------------- constants

PROTO_VERSION = 2
PAIR_ID = sha256(b"cellfind vector pairid")[:16]
SECRET = sha256(b"cellfind vector secret")
CREATED_AT = 1750000000
NOW = 1750000500
SID = sha256(b"cellfind vector sid")[:16]
FIND_DEADLINE = 1750001800

STATIC_A_PRIV = sha256(b"cellfind vector static A")
STATIC_B_PRIV = sha256(b"cellfind vector static B")
STATIC_C_PRIV = sha256(b"cellfind vector static C (attacker)")
EPH_PAIR_I = sha256(b"cellfind vector pairing ephemeral initiator")
EPH_PAIR_R = sha256(b"cellfind vector pairing ephemeral responder")
EPH_SESS_I = sha256(b"cellfind vector session ephemeral initiator")
EPH_SESS_R = sha256(b"cellfind vector session ephemeral responder")

STATIC_A_PUB = noise_ref.pubkey(STATIC_A_PRIV)
STATIC_B_PUB = noise_ref.pubkey(STATIC_B_PRIV)
STATIC_C_PUB = noise_ref.pubkey(STATIC_C_PRIV)

REC_PAIR_HS = 0x01
REC_SESS_HS = 0x02
REC_SESS_MSG = 0x03
REC_PAIR_MSG = 0x04


# ------------------------------------------------------- cacophony self-check


def check_official(cacophony_path: str):
    with open(cacophony_path) as f:
        allvec = json.load(f)["vectors"]
    wanted = [
        "Noise_NNpsk0_25519_ChaChaPoly_SHA256",
        "Noise_IKpsk2_25519_ChaChaPoly_SHA256",
    ]
    picked = []
    for name in wanted:
        vec = next(v for v in allvec if v["protocol_name"] == name)
        picked.append(vec)
        pattern = name.split("_")[1]
        init = HandshakeState(
            pattern,
            initiator=True,
            prologue=H(vec.get("init_prologue", "")),
            s=H(vec["init_static"]) if "init_static" in vec else None,
            e=H(vec["init_ephemeral"]),
            rs=H(vec["init_remote_static"]) if "init_remote_static" in vec else None,
            psk=H(vec["init_psks"][0]) if vec.get("init_psks") else None,
        )
        resp = HandshakeState(
            pattern,
            initiator=False,
            prologue=H(vec.get("resp_prologue", "")),
            s=H(vec["resp_static"]) if "resp_static" in vec else None,
            e=H(vec["resp_ephemeral"]),
            rs=H(vec["resp_remote_static"]) if "resp_remote_static" in vec else None,
            psk=H(vec["resp_psks"][0]) if vec.get("resp_psks") else None,
        )
        n_handshake = len(noise_ref.PATTERNS[pattern]["messages"])
        senders = []
        for i, msg in enumerate(vec["messages"]):
            payload = H(msg["payload"])
            if i < n_handshake:
                w, r = (init, resp) if i % 2 == 0 else (resp, init)
                out = w.write_message(payload)
                assert hx(out) == msg["ciphertext"], f"{name} handshake msg {i}"
                assert r.read_message(out) == payload
            else:
                if i == n_handshake:
                    i_send, i_recv = init.split()
                    r_recv, r_send = resp.split()
                    if "handshake_hash" in vec:
                        assert hx(init.handshake_hash) == vec["handshake_hash"]
                        assert hx(resp.handshake_hash) == vec["handshake_hash"]
                j = i - n_handshake
                send, recv = (
                    (i_send, r_recv) if j % 2 == 0 else (r_send, i_recv)
                )
                out = send.encrypt_with_ad(b"", payload)
                assert hx(out) == msg["ciphertext"], f"{name} transport msg {i}"
                assert recv.decrypt_with_ad(b"", out) == payload
        print(f"official vector OK: {name}")
    return picked


# ------------------------------------------------------------- app fixtures


def derive_all():
    d = {}
    d["pairingPsk"] = hmac(SECRET, b"cellfind/v2 pairing psk")
    return d


def run_pairing():
    """NNpsk0: initiator = joiner (role B), responder = inviter (role A)."""
    prologue = b"cellfind/v2/pairing" + PAIR_ID
    psk = hmac(SECRET, b"cellfind/v2 pairing psk")
    init = HandshakeState(
        "NNpsk0", True, prologue=prologue, e=EPH_PAIR_I, psk=psk
    )
    resp = HandshakeState(
        "NNpsk0", False, prologue=prologue, e=EPH_PAIR_R, psk=psk
    )
    msg1 = init.write_message(b"")
    assert resp.read_message(msg1) == b""
    msg2 = resp.write_message(b"")
    assert init.read_message(msg2) == b""
    i_send, i_recv = init.split()
    r_recv, r_send = resp.split()
    assert init.handshake_hash == resp.handshake_hash
    return {
        "prologue": prologue,
        "psk": psk,
        "msg1": msg1,
        "msg2": msg2,
        "h": init.handshake_hash,
        "b_to_a": i_send,  # initiator(B) -> responder(A)
        "a_to_b": r_send,
        "b_recv": i_recv,
        "a_recv": r_recv,
    }


def envelope_pairing(msg_type: int, seq: int, body: dict) -> bytes:
    return cbor_ref.encode({1: msg_type, 2: seq, 3: body})


def envelope_session(msg_type: int, seq: int, body: dict) -> bytes:
    return cbor_ref.encode({1: msg_type, 2: seq, 3: SID, 4: body})


def record(rec_type: int, payload: bytes) -> bytes:
    return bytes([rec_type]) + payload


def run_session(pair_root: bytes):
    """IKpsk2 over BLE: initiator = role B (central), responder = role A."""
    prologue = b"cellfind/v2/session" + PAIR_ID + b"ble"
    psk = hmac(pair_root, b"cellfind/v2 session psk")
    init = HandshakeState(
        "IKpsk2",
        True,
        prologue=prologue,
        s=STATIC_B_PRIV,
        e=EPH_SESS_I,
        rs=STATIC_A_PUB,
        psk=psk,
    )
    resp = HandshakeState(
        "IKpsk2",
        False,
        prologue=prologue,
        s=STATIC_A_PRIV,
        e=EPH_SESS_R,
        rs=None,
        psk=psk,
    )
    msg1 = init.write_message(b"")
    assert resp.read_message(msg1) == b""
    assert resp.rs == STATIC_B_PUB, "responder must see initiator static"
    msg2 = resp.write_message(b"")
    assert init.read_message(msg2) == b""
    i_send, i_recv = init.split()
    r_recv, r_send = resp.split()
    return {
        "prologue": prologue,
        "psk": psk,
        "msg1": msg1,
        "msg2": msg2,
        "h": init.handshake_hash,
        "k_init_to_resp": i_send.k,
        "k_resp_to_init": r_send.k,
        "i_send": i_send,
        "r_send": r_send,
    }


CAPS_ANDROID = {
    1: 1, 2: "16", 3: "2.0.0", 4: True, 5: True, 6: True, 7: True,
    8: True, 9: True, 10: False, 11: True, 12: False, 13: False, 14: False,
}
CAPS_IOS = {
    1: 2, 2: "26.1", 3: "2.0.0", 4: True, 5: False, 6: True, 7: False,
    8: True, 9: False, 10: False, 11: True, 12: False, 13: False, 14: True,
}


def build_accessory_config(addr_msb_first: bytes) -> bytes:
    out = struct.pack("<HHB", 1, 0, 20) + b"\x00" * 10 + bytes([32])
    out += struct.pack("<HHIII", 2, 0, 0, 0, 0x00020000)
    out += bytes([1, addr_msb_first[1], addr_msb_first[0]])
    out += struct.pack("<H", 50) + b"\x00" * 4 + bytes([1])
    out += struct.pack("<HHH", 6, 2400, 240)
    assert len(out) == 48
    return out


def build_shareable(session_id: int, preamble: int, channel: int,
                    sts_iv: bytes, dest_addr: int) -> bytes:
    out = struct.pack("<HHB", 2, 0, 30)
    out += b"US"
    out += struct.pack("<IBBHHHB", session_id & 0xFFFFFFFF, preamble, channel,
                       6, 2400, 240, 0x03)
    out += sts_iv
    out += struct.pack("<HH", dest_addr, 50)
    out += b"\x00" * 4 + bytes([1])
    assert len(out) == 35
    return out


def gen_state_transitions():
    states = [
        "idle", "arming", "searching", "p2pConnecting", "authenticating",
        "connected", "rangingStarting", "directionAvailable", "distanceOnly",
        "proximityOnly", "connectedOnly", "signalLost", "retryWait",
        "stopped", "expired", "failed",
    ]
    events = [
        "ARM", "ARMED", "PEER_FOUND", "TRANSPORT_CONNECTED", "AUTHENTICATED",
        "RANGING_STARTING", "SAMPLE_DIRECTION", "SAMPLE_DISTANCE",
        "SAMPLE_PROXIMITY", "RANGING_UNAVAILABLE", "SIGNAL_LOST",
        "RETRY_WAIT", "RETRY_ELAPSED", "USER_STOP", "DEADLINE", "FATAL",
    ]
    terminal = {"stopped", "expired", "failed"}
    ranging_states = {
        "rangingStarting", "directionAvailable", "distanceOnly",
        "proximityOnly", "connectedOnly",
    }
    linked = {"connected"} | ranging_states
    t = {}

    def allow(state, event, nxt):
        t[(state, event)] = nxt

    allow("idle", "ARM", "arming")
    allow("arming", "ARMED", "searching")
    allow("searching", "PEER_FOUND", "p2pConnecting")
    allow("p2pConnecting", "TRANSPORT_CONNECTED", "authenticating")
    allow("authenticating", "AUTHENTICATED", "connected")
    allow("connected", "RANGING_STARTING", "rangingStarting")
    allow("connectedOnly", "RANGING_STARTING", "rangingStarting")
    for s in ("connected", "rangingStarting", "directionAvailable",
              "distanceOnly", "proximityOnly"):
        allow(s, "RANGING_UNAVAILABLE", "connectedOnly")
    for s in ("rangingStarting", "directionAvailable", "distanceOnly",
              "proximityOnly"):
        allow(s, "SAMPLE_DIRECTION", "directionAvailable")
        allow(s, "SAMPLE_DISTANCE", "distanceOnly")
        allow(s, "SAMPLE_PROXIMITY", "proximityOnly")
    for s in linked | {"p2pConnecting", "authenticating"}:
        allow(s, "SIGNAL_LOST", "signalLost")
    allow("signalLost", "RETRY_WAIT", "retryWait")
    allow("retryWait", "RETRY_ELAPSED", "searching")
    for s in states:
        if s in terminal or s == "idle":
            continue
        allow(s, "USER_STOP", "stopped")
        allow(s, "DEADLINE", "expired")
        allow(s, "FATAL", "failed")

    rows = []
    for s in states:
        for e in events:
            nxt = t.get((s, e))
            rows.append({
                "state": s, "event": e,
                "next": nxt if nxt is not None else s,
                "valid": nxt is not None,
            })
    return {"states": states, "events": events, "transitions": rows}


class RangingNegotiationModel:
    """Independent reference for the app-side RangingCoordinator `ni_peer`
    attempt negotiation (PROTOCOL_V2.md §8 offer/accept/start, §10:379-382 /
    §12:469-470 idempotency). Mirrors the two idempotency guards both platforms
    implement: `startedAttempt` dedup (a duplicate (sid, attemptId) ranging_start
    is a single session effect), matched-only ranging_stop (a duplicate stop is a
    no-op), and stale-attemptId rejection (an accept/start/stop/error for a
    non-current attempt is ignored). It generates the outbound message stream and
    session-effect log the platform tests assert against."""

    NI_PEER = 3

    def __init__(self, offerer: bool):
        self.offerer = offerer
        self.attempt_counter = 0
        self.current = None
        self.started = None
        self.outbound = []   # (op, attemptId) emitted by the coordinator
        self.effects = []    # ("started"|"stopped", attemptId)

    def begin(self):
        if self.offerer:
            self.attempt_counter += 1
            self.current = self.attempt_counter
            self.outbound.append(("ranging_offer", self.current))

    def _begin_method(self, aid):
        if self.started != aid:           # dedup: single session effect
            self.started = aid
            self.effects.append(("started", aid))

    def feed(self, op):
        t, aid = op["op"], op["attemptId"]
        if t == "ranging_offer":          # controlee accepts, echoing the id
            self.current = aid
            self.outbound.append(("ranging_accept", aid))
        elif t == "ranging_accept":       # offerer starts iff it matches
            if aid == self.current:
                self.outbound.append(("ranging_start", aid))
                self._begin_method(aid)
        elif t == "ranging_start":        # controlee begins iff it matches
            if aid == self.current:
                self._begin_method(aid)
        elif t == "ranging_stop":         # matched-only: duplicate stop is a no-op
            if self.started == aid:
                self.started = None
                self.effects.append(("stopped", aid))
        elif t == "ranging_error":        # stale-safe fallback
            if aid == self.current:
                self.started = None
        else:
            raise ValueError(t)


def gen_duplicate_ops():
    specs = [
        {
            "name": "duplicateRangingStart",
            "role": "controlee",
            "note": "duplicate ranging_start (same sid+attemptId) -> single session effect",
            "ops": [
                {"op": "ranging_offer", "attemptId": 1, "method": 3},
                {"op": "ranging_start", "attemptId": 1},
                {"op": "ranging_start", "attemptId": 1},
            ],
        },
        {
            "name": "duplicateRangingStop",
            "role": "controlee",
            "note": "duplicate ranging_stop -> no error; the second stop is a no-op",
            "ops": [
                {"op": "ranging_offer", "attemptId": 2, "method": 3},
                {"op": "ranging_start", "attemptId": 2},
                {"op": "ranging_stop", "attemptId": 2, "reason": 1},
                {"op": "ranging_stop", "attemptId": 2, "reason": 1},
            ],
        },
        {
            "name": "staleAttemptIgnored",
            "role": "offerer",
            "note": "an accept for a non-current attemptId is ignored (no ranging_start for a stale/unknown attempt)",
            "ops": [
                {"op": "ranging_accept", "attemptId": 99, "method": 3},
                {"op": "ranging_accept", "attemptId": 1, "method": 3},
            ],
        },
    ]
    cases = []
    for spec in specs:
        model = RangingNegotiationModel(offerer=(spec["role"] == "offerer"))
        model.begin()
        for op in spec["ops"]:
            model.feed(op)
        cases.append({
            "name": spec["name"],
            "role": spec["role"],
            "note": spec["note"],
            "ops": spec["ops"],
            "expectOutbound": [{"op": o, "attemptId": a} for o, a in model.outbound],
            "sessionsStarted": [a for k, a in model.effects if k == "started"],
            "sessionsStopped": [a for k, a in model.effects if k == "stopped"],
        })

    # Self-validation: the idempotency invariants this fixture asserts must hold.
    byname = {c["name"]: c for c in cases}
    assert byname["duplicateRangingStart"]["sessionsStarted"] == [1], "dup start not single-effect"
    assert byname["duplicateRangingStart"]["expectOutbound"] == \
        [{"op": "ranging_accept", "attemptId": 1}]
    assert byname["duplicateRangingStop"]["sessionsStarted"] == [2]
    assert byname["duplicateRangingStop"]["sessionsStopped"] == [2], "dup stop not single-effect"
    assert byname["staleAttemptIgnored"]["expectOutbound"] == \
        [{"op": "ranging_offer", "attemptId": 1}, {"op": "ranging_start", "attemptId": 1}], \
        "stale accept was not ignored"
    assert byname["staleAttemptIgnored"]["sessionsStarted"] == [1]

    return {
        "sidHex": hx(SID),
        "note": (
            "Ranging-attempt idempotency conformance (PROTOCOL_V2.md "
            "§10:379-382, §12:469-470). ni_peer ranging_offer/accept/start/stop "
            "are the (sid, attemptId)-keyed operations, exchanged iOS<->iOS; "
            "Android is always the OOB controller and treats them as no-ops. "
            "'role' is the local RangingCoordinator role; an 'offerer' emits "
            "ranging_offer(attemptId=1) on start, before any op is fed. "
            "'expectOutbound' is the exact message stream the coordinator emits; "
            "'sessionsStarted'/'sessionsStopped' are the deduped session effects."
        ),
        "rangingMethods": {"niPeer": 3},
        "cases": cases,
    }


def gen_capability_selection():
    def caps(os, uwb=False, azimuth=False, interop=False, edm=False):
        return {"os": os, "uwbPresent": uwb, "uwbAzimuth": azimuth,
                "appleInteropUwb": interop, "niEdm": edm}

    cases = [
        (caps("ios", True, edm=True), caps("ios", True, edm=True), 3, True),
        (caps("ios", True, edm=True), caps("ios", True), 3, False),
        (caps("ios", True), caps("ios"), 4, False),
        (caps("android", True, True), caps("android", True), 2, False),
        (caps("android", True, True, True), caps("ios", True, False, True), 1, False),
        (caps("ios", True, False, True), caps("android", True, True, True), 1, False),
        (caps("android", True, True, True), caps("ios", True), 4, False),
        (caps("android", True), caps("ios", True, False, True), 4, False),
        (caps("android"), caps("ios", True, False, True), 4, False),
        (caps("android", True), caps("android"), 4, False),
        (caps("android"), caps("android"), 4, False),
        (caps("ios"), caps("ios"), 4, False),
    ]
    return {
        "methods": {"1": "uwb_apple_interop", "2": "uwb_android_oob",
                    "3": "ni_peer", "4": "ble_rssi"},
        "cases": [
            {"local": a, "peer": b, "method": m, "edm": edm}
            for a, b, m, edm in cases
        ],
    }


def fragment(rec: bytes, mtu: int):
    usable = mtu - 3
    frags = []
    total = len(rec)
    pos = 0
    counter = 0
    while True:
        header = bytearray()
        first = pos == 0
        room = usable - 1 - (4 if first else 0)
        chunk = rec[pos : pos + room]
        pos += len(chunk)
        final = pos == total
        flags = (0x80 if first else 0) | (0x40 if final else 0) | (counter & 0x3F)
        header.append(flags)
        if first:
            header += struct.pack(">I", total)
        frags.append(bytes(header) + chunk)
        counter += 1
        if final:
            break
    return frags


def gen_cbor_vectors():
    accept = []

    def acc(diag, obj):
        accept.append({"diag": diag, "hex": hx(cbor_ref.encode(obj))})
        assert cbor_ref.decode(cbor_ref.encode(obj)) == obj

    acc("0", 0)
    acc("23", 23)
    acc("24", 24)
    acc("255", 255)
    acc("256", 256)
    acc("65535", 65535)
    acc("65536", 65536)
    acc("4294967295", 4294967295)
    acc("4294967296", 4294967296)
    acc("-1", -1)
    acc("-24", -24)
    acc("-25", -25)
    acc("-256", -256)
    acc("h''", b"")
    acc("h'0102'", b"\x01\x02")
    acc('""', "")
    acc('"a"', "a")
    acc('"한"', "한")
    acc("[]", [])
    acc("[1, [2, 3]]", [1, [2, 3]])
    acc("{}", {})
    acc('{1: "a", 2: "b"}', {1: "a", 2: "b"})
    acc('{1: 0, 100: 2, h"01": 3, "a": 1}', {1: 0, 100: 2, b"\x01": 3, "a": 1})
    acc("true", True)
    acc("false", False)
    acc("null", None)
    acc("[{1: h'aa'}, -300]", [{1: b"\xaa"}, -300])

    reject = [
        ("1817", "nonminimal int (23 as 2 bytes)"),
        ("1900ff", "nonminimal int (255 as 3 bytes)"),
        ("1a0000ffff", "nonminimal int (65535 as u32)"),
        ("9fff", "indefinite array"),
        ("5f42010242030405ff", "indefinite byte string"),
        ("7f6161ff", "indefinite text string"),
        ("f93c00", "half float"),
        ("fb3ff0000000000000", "double float"),
        ("c06130", "tag"),
        ("c249010000000000000000", "bignum"),
        ("a201020103", "duplicate map key"),
        ("a202010102", "unsorted map keys"),
        ("4101ff", "trailing bytes"),
        ("a2010201", "truncated"),
        ("f7", "undefined"),
        ("f0", "unsupported simple value"),
        ("62c328", "invalid utf-8 text"),
    ]
    for hexstr, reason in reject:
        try:
            cbor_ref.decode(H(hexstr))
            raise AssertionError(f"reference accepted reject case: {reason}")
        except cbor_ref.DecodeError:
            pass
    return {
        "accept": accept,
        "reject": [{"hex": h_, "reason": r} for h_, r in reject],
    }


def main():
    cacophony_path, outdir = sys.argv[1], sys.argv[2]
    official = check_official(cacophony_path)

    note = "generated by tools/genvectors/gen_vectors.py -- do not edit"

    def write(name, obj):
        with open(f"{outdir}/{name}", "w") as f:
            json.dump({"_note": note, **obj}, f, indent=1, sort_keys=False)
            f.write("\n")
        print(f"wrote {name}")

    write("noise_official_vectors.json", {"vectors": official})

    # ---- pairing handshake + pairing channel
    pairing = run_pairing()
    pair_root = rfc5869_hkdf(
        salt=pairing["h"],
        ikm=pairing["psk"],
        info=b"cellfind/v2 pair root" + STATIC_A_PUB + STATIC_B_PUB,
    )
    session_psk = hmac(pair_root, b"cellfind/v2 session psk")
    disc_key_a = hmac(pair_root, b"cellfind/v2 discovery A")
    disc_key_b = hmac(pair_root, b"cellfind/v2 discovery B")
    confirm_a = hmac(pair_root, b"cellfind/v2 confirm" + b"\x41")
    confirm_b = hmac(pair_root, b"cellfind/v2 confirm" + b"\x42")
    fpr = sha256(b"cellfind/v2 fingerprint" + PAIR_ID + STATIC_A_PUB + STATIC_B_PUB)
    display = str(int.from_bytes(fpr[:8], "big") % 1_000_000).zfill(6)

    pairing_msgs = [
        ("B", 64, 0, {1: 2, 2: STATIC_B_PUB, 3: PROTO_VERSION}),
        ("A", 64, 0, {1: 1, 2: STATIC_A_PUB, 3: PROTO_VERSION}),
        ("B", 65, 1, {1: confirm_b}),
        ("A", 65, 1, {1: confirm_a}),
        ("B", 66, 2, {}),
        ("A", 66, 2, {}),
    ]
    pairing_records = []
    for sender, mtype, seq, body in pairing_msgs:
        cs = pairing["b_to_a"] if sender == "B" else pairing["a_to_b"]
        plain = envelope_pairing(mtype, seq, body)
        assert cs.n == seq
        rec = record(REC_PAIR_MSG, cs.encrypt_with_ad(b"", plain))
        pairing_records.append({
            "sender": sender, "msgType": mtype, "seq": seq,
            "plaintextHex": hx(plain), "recordHex": hx(rec),
        })

    write("noise_app_vectors.json", {
        "pairing": {
            "pattern": "NNpsk0",
            "initiatorRole": "B",
            "prologueHex": hx(pairing["prologue"]),
            "pskHex": hx(pairing["psk"]),
            "initEphemeralPrivHex": hx(EPH_PAIR_I),
            "respEphemeralPrivHex": hx(EPH_PAIR_R),
            "msg1RecordHex": hx(record(REC_PAIR_HS, pairing["msg1"])),
            "msg2RecordHex": hx(record(REC_PAIR_HS, pairing["msg2"])),
            "handshakeHashHex": hx(pairing["h"]),
            "transportRecords": pairing_records,
        },
    })

    # ---- session handshake (IKpsk2 over BLE, initiator = B)
    sess = run_session(pair_root)
    session_deadline = FIND_DEADLINE
    sess_msgs = [
        ("init", 1, 0, {1: CAPS_ANDROID, 2: session_deadline, 3: PROTO_VERSION}),
        ("resp", 1, 0, {1: CAPS_IOS, 2: session_deadline, 3: PROTO_VERSION}),
        ("init", 2, 1, {1: 7}),
        ("resp", 3, 1, {1: 7}),
        ("init", 32, 2, {1: session_deadline}),
        ("resp", 16, 2, {1: 1, 2: 1}),
        ("init", 17, 3, {1: 1, 2: 1}),
        ("resp", 21, 3, {1: 1, 2: build_accessory_config(H("aabb"))}),
        ("init", 4, 4, {1: 1}),
    ]
    sess_records = []
    for sender, mtype, seq, body in sess_msgs:
        cs = sess["i_send"] if sender == "init" else sess["r_send"]
        plain = envelope_session(mtype, seq, body)
        assert cs.n == seq
        rec = record(REC_SESS_MSG, cs.encrypt_with_ad(b"", plain))
        sess_records.append({
            "sender": sender, "msgType": mtype, "seq": seq,
            "plaintextHex": hx(plain), "recordHex": hx(rec),
        })

    # failure cases
    bitflip = bytearray(H(sess_records[0]["recordHex"]))
    bitflip[10] ^= 0x01
    wrong_secret = sha256(b"cellfind vector WRONG secret")
    wrong_psk = hmac(wrong_secret, b"cellfind/v2 pairing psk")
    attacker = HandshakeState(
        "IKpsk2", True, prologue=sess["prologue"],
        s=STATIC_C_PRIV, e=EPH_SESS_I, rs=STATIC_A_PUB, psk=sess["psk"],
    )
    substituted_msg1 = attacker.write_message(b"")

    write("envelope_vectors.json", {
        "session": {
            "pattern": "IKpsk2",
            "transportTag": "ble",
            "initiator": "B",
            "prologueHex": hx(sess["prologue"]),
            "pskHex": hx(sess["psk"]),
            "sidHex": hx(SID),
            "initStaticPrivHex": hx(STATIC_B_PRIV),
            "respStaticPrivHex": hx(STATIC_A_PRIV),
            "initEphemeralPrivHex": hx(EPH_SESS_I),
            "respEphemeralPrivHex": hx(EPH_SESS_R),
            "msg1RecordHex": hx(record(REC_SESS_HS, sess["msg1"])),
            "msg2RecordHex": hx(record(REC_SESS_HS, sess["msg2"])),
            "handshakeHashHex": hx(sess["h"]),
            "kInitToRespHex": hx(sess["k_init_to_resp"]),
            "kRespToInitHex": hx(sess["k_resp_to_init"]),
            "transportRecords": sess_records,
        },
        "failures": [
            {"case": "wrongPskPairing",
             "note": "responder with this psk must fail to read pairing msg1",
             "pskHex": hx(wrong_psk),
             "msg1RecordHex": hx(record(REC_PAIR_HS, pairing["msg1"])),
             "pairingPrologueHex": hx(pairing["prologue"]),
             "respEphemeralPrivHex": hx(EPH_PAIR_R)},
            {"case": "substitutedStatic",
             "note": "IKpsk2 msg1 from non-pinned static key: handshake may "
                     "verify cryptographically but pinning MUST reject it",
             "attackerStaticPubHex": hx(STATIC_C_PUB),
             "msg1RecordHex": hx(record(REC_SESS_HS, substituted_msg1))},
            {"case": "bitFlip",
             "note": "one flipped bit in a transport record must fail AEAD",
             "recordHex": hx(bytes(bitflip))},
            {"case": "replay",
             "note": "delivering this record twice must fail on second delivery",
             "recordHex": sess_records[0]["recordHex"]},
        ],
    })

    # ---- derivations
    write("derivation_vectors.json", {
        "secretHex": hx(SECRET),
        "pairIdHex": hx(PAIR_ID),
        "staticAPrivHex": hx(STATIC_A_PRIV),
        "staticAPubHex": hx(STATIC_A_PUB),
        "staticBPrivHex": hx(STATIC_B_PRIV),
        "staticBPubHex": hx(STATIC_B_PUB),
        "pairingPskHex": hx(pairing["psk"]),
        "pairingHandshakeHashHex": hx(pairing["h"]),
        "pairRootHex": hx(pair_root),
        "sessionPskHex": hx(session_psk),
        "discKeyAHex": hx(disc_key_a),
        "discKeyBHex": hx(disc_key_b),
        "confirmAHex": hx(confirm_a),
        "confirmBHex": hx(confirm_b),
        "fingerprintHex": hx(fpr),
        "fingerprintDisplay": display,
    })

    # ---- discovery tokens
    epoch = NOW // 120
    tokens = []
    for role, key in (("A", disc_key_a), ("B", disc_key_b)):
        for e in (epoch - 1, epoch, epoch + 1):
            inp = bytes([PROTO_VERSION]) + struct.pack(">Q", e) + (
                b"\x41" if role == "A" else b"\x42"
            )
            tokens.append({
                "role": role, "epoch": e,
                "inputHex": hx(inp),
                "tokenHex": hx(hmac(key, inp)[:16]),
            })
    write("discovery_vectors.json", {
        "unixSeconds": NOW, "epochSeconds": 120, "epoch": epoch,
        "tokens": tokens,
    })

    # ---- invitation
    invite = cbor_ref.encode([PROTO_VERSION, PAIR_ID, SECRET, CREATED_AT])
    invite_text = "CF2:" + urlsafe_b64encode(invite).decode().rstrip("=")
    expired = cbor_ref.encode([PROTO_VERSION, PAIR_ID, SECRET, NOW - 1000])
    future = cbor_ref.encode([PROTO_VERSION, PAIR_ID, SECRET, NOW + 500])
    badver = cbor_ref.encode([3, PAIR_ID, SECRET, CREATED_AT])
    shortsecret = cbor_ref.encode([PROTO_VERSION, PAIR_ID, SECRET[:16], CREATED_AT])

    def b64(x):
        return "CF2:" + urlsafe_b64encode(x).decode().rstrip("=")

    write("invite_vectors.json", {
        "nowUnixSeconds": NOW,
        "maxAgeSeconds": 900,
        "maxFutureSkewSeconds": 120,
        "valid": {
            "pairIdHex": hx(PAIR_ID), "secretHex": hx(SECRET),
            "createdAt": CREATED_AT,
            "cborHex": hx(invite), "text": invite_text,
        },
        "reject": [
            {"text": "XX2:" + invite_text[4:], "reason": "bad prefix"},
            {"text": "CF2:!!!!", "reason": "bad base64url"},
            {"text": b64(badver), "reason": "unsupported version"},
            {"text": b64(shortsecret), "reason": "secret wrong length"},
            {"text": b64(expired), "reason": "expired (createdAt too old)"},
            {"text": b64(future), "reason": "createdAt in the future"},
            {"text": b64(invite[:20]), "reason": "truncated cbor"},
            {"text": b64(invite + b"\x00"), "reason": "trailing bytes"},
        ],
    })

    # ---- cbor
    write("cbor_vectors.json", gen_cbor_vectors())

    # ---- fragmentation
    ready_record = H(sess_records[0]["recordHex"])
    synthetic = record(REC_SESS_MSG, bytes((i * 7 + 3) & 0xFF for i in range(3000)))
    frag_cases = []
    for mtu in (23, 185, 512):
        frag_cases.append({
            "name": f"sessionReadyMtu{mtu}", "mtu": mtu,
            "recordHex": hx(ready_record),
            "fragments": [hx(f) for f in fragment(ready_record, mtu)],
        })
    frag_cases.append({
        "name": "longRecordMtu185", "mtu": 185,
        "recordHex": hx(synthetic),
        "fragments": [hx(f) for f in fragment(synthetic, 185)],
    })
    good = fragment(ready_record, 185)
    long_frags = fragment(synthetic, 185)
    malformed = [
        {"name": "continuationWithoutStart", "mtu": 185,
         "fragments": [hx(long_frags[1])], "error": "noRecordInProgress"},
        {"name": "firstWhileInProgress", "mtu": 185,
         "fragments": [hx(long_frags[0]), hx(long_frags[0])],
         "error": "unexpectedFirst"},
        {"name": "counterSkip", "mtu": 185,
         "fragments": [hx(long_frags[0]), hx(long_frags[2])],
         "error": "badCounter"},
        {"name": "duplicateFragment", "mtu": 185,
         "fragments": [hx(long_frags[0]), hx(long_frags[1]), hx(long_frags[1])],
         "error": "badCounter"},
        {"name": "declaredTooLong", "mtu": 185,
         "fragments": [hx(b"\x80" + struct.pack(">I", 70000) + b"\x01")],
         "error": "declaredLengthInvalid"},
        {"name": "declaredZero", "mtu": 185,
         "fragments": [hx(b"\xc0" + struct.pack(">I", 0))],
         "error": "declaredLengthInvalid"},
        {"name": "finalTooShort", "mtu": 185,
         "fragments": [hx(b"\xc0" + struct.pack(">I", 500) + b"\x01\x02")],
         "error": "lengthMismatch"},
        {"name": "overflowBeyondDeclared", "mtu": 185,
         "fragments": [hx(good[0][:1] + struct.pack(">I", 5) + good[0][5:])],
         "error": "lengthMismatch"},
        {"name": "emptyChunk", "mtu": 185,
         "fragments": [hx(long_frags[0]), hx(bytes([0x01]))],
         "error": "emptyChunk"},
    ]
    write("fragment_vectors.json", {
        "cases": frag_cases, "malformed": malformed,
    })

    # ---- capability selection + state machine
    write("capability_selection.json", gen_capability_selection())
    write("state_transitions.json", gen_state_transitions())

    # ---- ranging-attempt idempotency (duplicate start/stop, stale attemptId)
    write("duplicate_ops.json", gen_duplicate_ops())

    # ---- apple uwb
    acc48 = build_accessory_config(H("aabb"))
    expected_doc_hex = (
        "010000001400000000000000000000200200000000000000000000000000"
        "020001bbaa3200000000000106006009f000"
    )
    assert hx(acc48) == expected_doc_hex, "accessory config drifted from UWB_INTEROP.md"
    sts_iv = H("a1a2a3a4a5a6")
    share35 = build_shareable(0x12345678, 10, 9, sts_iv, 0xAABB)
    bad_preamble = bytearray(share35); bad_preamble[11] = 13
    bad_major = bytearray(share35); bad_major[0] = 3
    bad_rfu = bytearray(share35); bad_rfu[30] = 1
    bad_hopping = bytearray(share35); bad_hopping[34] = 0
    write("apple_uwb_vectors.json", {
        "accessoryConfig": {
            "localAddressMsbFirstHex": "aabb",
            "dataHex": hx(acc48),
        },
        "shareable": {
            "dataHex": hx(share35),
            "parsed": {
                "sessionId": 0x12345678, "preambleIndex": 10, "channel": 9,
                "slotsPerRound": 6, "slotDurationRstu": 2400,
                "rangingIntervalMs": 240, "hoppingEnabled": True,
                "staticStsIvHex": hx(sts_iv),
                "peerAddressMsbFirstHex": "aabb",
            },
            "androidSessionKeyInfoHex": "004c" + hx(sts_iv),
        },
        "shareableReject": [
            {"dataHex": hx(bytes(bad_major)), "reason": "unsupported major version"},
            {"dataHex": hx(bytes(bad_preamble)), "reason": "preamble out of range"},
            {"dataHex": hx(bytes(bad_rfu)), "reason": "nonzero RFU"},
            {"dataHex": hx(bytes(bad_hopping)), "reason": "hopping disabled"},
            {"dataHex": hx(share35[:34]), "reason": "truncated"},
        ],
    })

    print("all fixtures generated")


if __name__ == "__main__":
    main()
