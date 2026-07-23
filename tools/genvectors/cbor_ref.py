"""Canonical CBOR (RFC 8949 deterministic core profile) reference codec.

Restricted to the types PROTOCOL_V2.md allows: ints, byte strings, text
strings, arrays, maps, false/true/null. Encoder emits canonical form;
decoder rejects anything noncanonical.
"""


def _head(major: int, value: int) -> bytes:
    if value < 24:
        return bytes([(major << 5) | value])
    if value < 0x100:
        return bytes([(major << 5) | 24, value])
    if value < 0x10000:
        return bytes([(major << 5) | 25]) + value.to_bytes(2, "big")
    if value < 0x100000000:
        return bytes([(major << 5) | 26]) + value.to_bytes(4, "big")
    return bytes([(major << 5) | 27]) + value.to_bytes(8, "big")


def encode(obj) -> bytes:
    if obj is False:
        return b"\xf4"
    if obj is True:
        return b"\xf5"
    if obj is None:
        return b"\xf6"
    if isinstance(obj, int):
        if obj >= 0:
            return _head(0, obj)
        return _head(1, -1 - obj)
    if isinstance(obj, bytes):
        return _head(2, len(obj)) + obj
    if isinstance(obj, str):
        raw = obj.encode("utf-8")
        return _head(3, len(raw)) + raw
    if isinstance(obj, (list, tuple)):
        return _head(4, len(obj)) + b"".join(encode(x) for x in obj)
    if isinstance(obj, dict):
        entries = sorted(
            ((encode(k), encode(v)) for k, v in obj.items()), key=lambda e: e[0]
        )
        return _head(5, len(entries)) + b"".join(k + v for k, v in entries)
    raise TypeError(f"unsupported type {type(obj)}")


class DecodeError(ValueError):
    pass


class _Decoder:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def _take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise DecodeError("truncated")
        out = self.data[self.pos : self.pos + n]
        self.pos += n
        return out

    def _read_head(self):
        b = self._take(1)[0]
        major, info = b >> 5, b & 0x1F
        if info < 24:
            return major, info
        if info == 24:
            v = self._take(1)[0]
            if v < 24:
                raise DecodeError("noncanonical length")
            return major, v
        if info == 25:
            v = int.from_bytes(self._take(2), "big")
            if v < 0x100:
                raise DecodeError("noncanonical length")
            return major, v
        if info == 26:
            v = int.from_bytes(self._take(4), "big")
            if v < 0x10000:
                raise DecodeError("noncanonical length")
            return major, v
        if info == 27:
            v = int.from_bytes(self._take(8), "big")
            if v < 0x100000000:
                raise DecodeError("noncanonical length")
            return major, v
        raise DecodeError("indefinite or reserved length")

    def decode_item(self):
        start = self.pos
        major, value = self._read_head()
        if major == 0:
            return value, self.data[start : self.pos]
        if major == 1:
            return -1 - value, self.data[start : self.pos]
        if major == 2:
            return self._take(value), self.data[start : self.pos]
        if major == 3:
            raw = self._take(value)
            try:
                return raw.decode("utf-8"), self.data[start : self.pos]
            except UnicodeDecodeError as e:
                raise DecodeError("invalid utf-8") from e
        if major == 4:
            out = []
            for _ in range(value):
                item, _enc = self.decode_item()
                out.append(item)
            return out, self.data[start : self.pos]
        if major == 5:
            out = {}
            prev_key_enc = None
            for _ in range(value):
                key, key_enc = self.decode_item()
                if prev_key_enc is not None and key_enc <= prev_key_enc:
                    raise DecodeError("map keys not sorted or duplicate")
                prev_key_enc = key_enc
                val, _enc = self.decode_item()
                out[key] = val
            return out, self.data[start : self.pos]
        if major == 7:
            if value == 20:
                return False, self.data[start : self.pos]
            if value == 21:
                return True, self.data[start : self.pos]
            if value == 22:
                return None, self.data[start : self.pos]
            raise DecodeError("unsupported simple/float")
        raise DecodeError("unsupported major type (tag/bignum)")


def decode(data: bytes):
    dec = _Decoder(data)
    item, _ = dec.decode_item()
    if dec.pos != len(data):
        raise DecodeError("trailing bytes")
    return item
