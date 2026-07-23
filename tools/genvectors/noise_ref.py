"""Minimal Noise Protocol Framework (rev 34) reference for vector generation.

Supports X25519 / ChaChaPoly / SHA256 and the NNpsk0 / IKpsk2 patterns only.
This is NOT production code; it exists to generate and cross-check the shared
test vectors in shared/vectors/.
"""

import hashlib
import hmac as hmac_mod

from cryptography.hazmat.primitives.asymmetric.x25519 import (
    X25519PrivateKey,
    X25519PublicKey,
)
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives import serialization

HASHLEN = 32
DHLEN = 32


def sha256(data: bytes) -> bytes:
    return hashlib.sha256(data).digest()


def hmac(key: bytes, data: bytes) -> bytes:
    return hmac_mod.new(key, data, hashlib.sha256).digest()


def hkdf(chaining_key: bytes, ikm: bytes, num: int):
    temp = hmac(chaining_key, ikm)
    out1 = hmac(temp, b"\x01")
    out2 = hmac(temp, out1 + b"\x02")
    if num == 2:
        return out1, out2
    out3 = hmac(temp, out2 + b"\x03")
    return out1, out2, out3


def rfc5869_hkdf(salt: bytes, ikm: bytes, info: bytes, length: int = 32) -> bytes:
    prk = hmac(salt, ikm)
    okm = b""
    t = b""
    counter = 1
    while len(okm) < length:
        t = hmac(prk, t + info + bytes([counter]))
        okm += t
        counter += 1
    return okm[:length]


def dh(priv: bytes, pub: bytes) -> bytes:
    key = X25519PrivateKey.from_private_bytes(priv)
    return key.exchange(X25519PublicKey.from_public_bytes(pub))


def pubkey(priv: bytes) -> bytes:
    return (
        X25519PrivateKey.from_private_bytes(priv)
        .public_key()
        .public_bytes(
            serialization.Encoding.Raw, serialization.PublicFormat.Raw
        )
    )


def nonce_bytes(n: int) -> bytes:
    return b"\x00" * 4 + n.to_bytes(8, "little")


def aead_encrypt(k: bytes, n: int, ad: bytes, plaintext: bytes) -> bytes:
    return ChaCha20Poly1305(k).encrypt(nonce_bytes(n), plaintext, ad)


def aead_decrypt(k: bytes, n: int, ad: bytes, ciphertext: bytes) -> bytes:
    return ChaCha20Poly1305(k).decrypt(nonce_bytes(n), ciphertext, ad)


class CipherState:
    def __init__(self, k: bytes | None = None):
        self.k = k
        self.n = 0

    def encrypt_with_ad(self, ad: bytes, plaintext: bytes) -> bytes:
        if self.k is None:
            return plaintext
        out = aead_encrypt(self.k, self.n, ad, plaintext)
        self.n += 1
        return out

    def decrypt_with_ad(self, ad: bytes, ciphertext: bytes) -> bytes:
        if self.k is None:
            return ciphertext
        out = aead_decrypt(self.k, self.n, ad, ciphertext)
        self.n += 1
        return out


class SymmetricState:
    def __init__(self, protocol_name: bytes):
        if len(protocol_name) <= HASHLEN:
            self.h = protocol_name + b"\x00" * (HASHLEN - len(protocol_name))
        else:
            self.h = sha256(protocol_name)
        self.ck = self.h
        self.cipher = CipherState()

    def mix_key(self, ikm: bytes):
        self.ck, temp_k = hkdf(self.ck, ikm, 2)
        self.cipher = CipherState(temp_k)

    def mix_hash(self, data: bytes):
        self.h = sha256(self.h + data)

    def mix_key_and_hash(self, ikm: bytes):
        self.ck, temp_h, temp_k = hkdf(self.ck, ikm, 3)
        self.mix_hash(temp_h)
        self.cipher = CipherState(temp_k)

    def encrypt_and_hash(self, plaintext: bytes) -> bytes:
        c = self.cipher.encrypt_with_ad(self.h, plaintext)
        self.mix_hash(c)
        return c

    def decrypt_and_hash(self, ciphertext: bytes) -> bytes:
        p = self.cipher.decrypt_with_ad(self.h, ciphertext)
        self.mix_hash(ciphertext)
        return p

    def split(self):
        k1, k2 = hkdf(self.ck, b"", 2)
        return CipherState(k1), CipherState(k2)


PATTERNS = {
    "NNpsk0": {
        "pre_i": [],
        "pre_r": [],
        "messages": [["psk", "e"], ["e", "ee"]],
    },
    "IKpsk2": {
        "pre_i": [],
        "pre_r": ["s"],
        "messages": [["e", "es", "s", "ss"], ["e", "ee", "se", "psk"]],
    },
}


class HandshakeState:
    def __init__(
        self,
        pattern: str,
        initiator: bool,
        prologue: bytes = b"",
        s: bytes | None = None,
        e: bytes | None = None,
        rs: bytes | None = None,
        psk: bytes | None = None,
    ):
        self.pattern = PATTERNS[pattern]
        name = f"Noise_{pattern}_25519_ChaChaPoly_SHA256".encode()
        self.sym = SymmetricState(name)
        self.initiator = initiator
        self.s = s
        self.e = e
        self.rs = rs
        self.re = None
        self.psk = psk
        self.msg_index = 0
        self.sym.mix_hash(prologue)
        for token in self.pattern["pre_i"]:
            assert token == "s"
            pub = pubkey(self.s) if initiator else self.rs
            self.sym.mix_hash(pub)
        for token in self.pattern["pre_r"]:
            assert token == "s"
            pub = self.rs if initiator else pubkey(self.s)
            self.sym.mix_hash(pub)

    def write_message(self, payload: bytes = b"") -> bytes:
        tokens = self.pattern["messages"][self.msg_index]
        self.msg_index += 1
        buf = b""
        for token in tokens:
            if token == "e":
                epub = pubkey(self.e)
                buf += epub
                self.sym.mix_hash(epub)
                if self.psk is not None:
                    self.sym.mix_key(epub)
            elif token == "s":
                buf += self.sym.encrypt_and_hash(pubkey(self.s))
            elif token == "ee":
                self.sym.mix_key(dh(self.e, self.re))
            elif token == "es":
                if self.initiator:
                    self.sym.mix_key(dh(self.e, self.rs))
                else:
                    self.sym.mix_key(dh(self.s, self.re))
            elif token == "se":
                if self.initiator:
                    self.sym.mix_key(dh(self.s, self.re))
                else:
                    self.sym.mix_key(dh(self.e, self.rs))
            elif token == "ss":
                self.sym.mix_key(dh(self.s, self.rs))
            elif token == "psk":
                self.sym.mix_key_and_hash(self.psk)
            else:
                raise ValueError(token)
        buf += self.sym.encrypt_and_hash(payload)
        return buf

    def read_message(self, message: bytes) -> bytes:
        tokens = self.pattern["messages"][self.msg_index]
        self.msg_index += 1
        rest = message
        for token in tokens:
            if token == "e":
                self.re, rest = rest[:DHLEN], rest[DHLEN:]
                self.sym.mix_hash(self.re)
                if self.psk is not None:
                    self.sym.mix_key(self.re)
            elif token == "s":
                take = DHLEN + (16 if self.sym.cipher.k is not None else 0)
                chunk, rest = rest[:take], rest[take:]
                self.rs = self.sym.decrypt_and_hash(chunk)
            elif token == "ee":
                self.sym.mix_key(dh(self.e, self.re))
            elif token == "es":
                if self.initiator:
                    self.sym.mix_key(dh(self.e, self.rs))
                else:
                    self.sym.mix_key(dh(self.s, self.re))
            elif token == "se":
                if self.initiator:
                    self.sym.mix_key(dh(self.s, self.re))
                else:
                    self.sym.mix_key(dh(self.e, self.rs))
            elif token == "ss":
                self.sym.mix_key(dh(self.s, self.rs))
            elif token == "psk":
                self.sym.mix_key_and_hash(self.psk)
            else:
                raise ValueError(token)
        return self.sym.decrypt_and_hash(rest)

    def finished(self) -> bool:
        return self.msg_index == len(self.pattern["messages"])

    def split(self):
        return self.sym.split()

    @property
    def handshake_hash(self) -> bytes:
        return self.sym.h
