"""密码学常量数据库 — 统一 auto_analyze.py 与 find_crypto.py 的两套定义。"""

from __future__ import annotations

import struct

# ── AES ────────────────────────────────────────────────────────────────────

AES_SBOX = bytes([
    0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5,
    0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
])

AES_INV_SBOX = bytes([
    0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38,
    0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
])

AES_RCON = bytes([0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36])

# ── DES ────────────────────────────────────────────────────────────────────

DES_IP = bytes([58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4])
DES_PC1 = bytes([57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2])

# ── Hash IVs ───────────────────────────────────────────────────────────────

MD5_IV = bytes.fromhex("0123456789abcdeffedcba9876543210")
SHA1_IV = bytes.fromhex("67452301efcdab8998badcfe10325476c3d2e1f0")
SHA256_IV = struct.pack(">IIIIIIII",
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
    0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
)
SHA512_IV = struct.pack(">QQQQQQQQ",
    0x6A09E667F3BCC908, 0xBB67AE8584CAA73B,
    0x3C6EF372FE94F82B, 0xA54FF53A5F1D36F1,
    0x510E527FADE682D1, 0x9B05688C2B3E6C1F,
    0x1F83D9ABFB41BD6B, 0x5BE0CD19137E2179,
)

# ── ARX / Stream Ciphers ───────────────────────────────────────────────────

CHACHA_CONSTANT = b"expand 32-byte k"
TEA_DELTA = 0x9E3779B9
CRC32_POLY = 0xEDB88320

# ── Base64 ─────────────────────────────────────────────────────────────────

BASE64_STANDARD = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
BASE64_URLSAFE = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

# ── SM4 (Chinese national standard) ────────────────────────────────────────

SM4_SBOX = bytes([
    0xd6, 0x90, 0xe9, 0xfe, 0xcc, 0xe1, 0x3d, 0xb7,
    0x16, 0xb6, 0x14, 0xc2, 0x28, 0xfb, 0x2c, 0x05,
])

# ── Structured lookup database (compatible with find_crypto.py) ────────────

CONSTANTS: dict[str, dict] = {
    "AES_SBOX": {
        "algo": "AES (Rijndael)",
        "bytes": AES_SBOX,
        "confidence": "high",
        "desc": "Forward S-box (first 16 of 256)",
    },
    "AES_INV_SBOX": {
        "algo": "AES (Rijndael)",
        "bytes": AES_INV_SBOX,
        "confidence": "high",
        "desc": "Inverse S-box (first 16 of 256)",
    },
    "AES_RCON": {
        "algo": "AES",
        "bytes": AES_RCON,
        "confidence": "medium",
        "desc": "Round constants (Rcon)",
    },
    "DES_IP": {
        "algo": "DES",
        "bytes": DES_IP,
        "confidence": "medium",
        "desc": "Initial Permutation table",
    },
    "DES_PC1": {
        "algo": "DES",
        "bytes": DES_PC1,
        "confidence": "medium",
        "desc": "Permuted Choice 1",
    },
    "MD5_IV": {
        "algo": "MD5",
        "bytes": MD5_IV,
        "confidence": "high",
        "desc": "Initialization Vector (LE)",
    },
    "SHA1_IV": {
        "algo": "SHA-1",
        "bytes": SHA1_IV,
        "confidence": "high",
        "desc": "Initialization Vector (BE)",
    },
    "SHA256_IV": {
        "algo": "SHA-256",
        "bytes": SHA256_IV,
        "confidence": "high",
        "desc": "Initialization Vector (BE)",
    },
    "SHA512_IV": {
        "algo": "SHA-512",
        "bytes": SHA512_IV,
        "confidence": "high",
        "desc": "Initialization Vector (BE)",
    },
    "CHACHA_CONSTANT": {
        "algo": "ChaCha20 / Salsa20",
        "bytes": CHACHA_CONSTANT,
        "confidence": "high",
        "desc": "expand 32-byte k constant",
    },
    "TEA_DELTA_LE": {
        "algo": "TEA / XTEA",
        "bytes": struct.pack("<I", TEA_DELTA),
        "confidence": "medium",
        "desc": "TEA delta (0x9E3779B9, LE)",
    },
    "CRC32_POLY_LE": {
        "algo": "CRC-32",
        "bytes": struct.pack("<I", CRC32_POLY),
        "confidence": "low",
        "desc": "CRC-32 reflected polynomial (found 3+ times)",
    },
    "BASE64_STANDARD": {
        "algo": "Base64 encoding",
        "bytes": BASE64_STANDARD,
        "confidence": "high",
        "desc": "Standard Base64 alphabet",
    },
    "BASE64_URLSAFE": {
        "algo": "Base64 encoding",
        "bytes": BASE64_URLSAFE,
        "confidence": "high",
        "desc": "URL-safe Base64 alphabet",
    },
    "SM4_SBOX": {
        "algo": "SM4 (Chinese national standard)",
        "bytes": SM4_SBOX,
        "confidence": "high",
        "desc": "SM4 S-box (first 16 of 256)",
    },
}
