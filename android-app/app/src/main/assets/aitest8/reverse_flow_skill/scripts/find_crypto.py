#!/usr/bin/env python3
"""find_crypto.py — 密码学常量扫描和算法识别

扫描二进制文件中嵌入的密码学常量、S-box、初始化向量、轮常量、base64字母表等，
识别可能的加密算法。

Usage:
    python find_crypto.py <binary> [--out <output_dir>] [--constants-only] [--entropy-threshold 7.0] [--target aes]
"""

from __future__ import annotations

import argparse
import json
import math
import os
import struct
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


# ── Comprehensive crypto constant database ────────────────────────────────────

CONSTANTS = {
    # ── AES ──
    "AES_SBOX": {
        "algo": "AES (Rijndael)",
        "bytes": bytes([0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
                         0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0]),
        "confidence": "high",
        "desc": "Forward S-box (first 32 bytes of 256)",
    },
    "AES_INV_SBOX": {
        "algo": "AES (Rijndael)",
        "bytes": bytes([0x52,0x09,0x6a,0xd5,0x30,0x36,0xa5,0x38,0xbf,0x40,0xa3,0x9e,0x81,0xf3,0xd7,0xfb,
                         0x7c,0xe3,0x39,0x82,0x9b,0x2f,0xff,0x87,0x34,0x8e,0x43,0x44,0xc4,0xde,0xe9,0xcb]),
        "confidence": "high",
        "desc": "Inverse S-box (first 32 bytes of 256)",
    },
    "AES_RCON": {
        "algo": "AES",
        "bytes": bytes([0x01,0x02,0x04,0x08,0x10,0x20,0x40,0x80,0x1b,0x36]),
        "confidence": "medium",
        "desc": "Round constants (Rcon)",
    },

    # ── DES ──
    "DES_IP": {
        "algo": "DES",
        "bytes": bytes([58,50,42,34,26,18,10,2,60,52,44,36,28,20,12,4]),
        "confidence": "medium",
        "desc": "Initial Permutation table (first 16 of 64)",
    },
    "DES_PC1": {
        "algo": "DES",
        "bytes": bytes([57,49,41,33,25,17,9,1,58,50,42,34,26,18,10,2]),
        "confidence": "medium",
        "desc": "Permuted Choice 1 (first 16 of 56)",
    },

    # ── Hash IVs ──
    "MD5_IV": {
        "algo": "MD5",
        "bytes": struct.pack("<IIII", 0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476),
        "confidence": "high",
        "desc": "MD5 Initialization Vector (little-endian)",
    },
    "SHA1_IV": {
        "algo": "SHA-1",
        "bytes": struct.pack(">IIIII", 0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476, 0xC3D2E1F0),
        "confidence": "high",
        "desc": "SHA-1 Initialization Vector (big-endian)",
    },
    "SHA256_IV": {
        "algo": "SHA-256",
        "bytes": struct.pack(">IIIIIIII", 0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
                             0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19),
        "confidence": "high",
        "desc": "SHA-256 Initialization Vector (first 8 words, big-endian)",
    },
    "SHA512_IV": {
        "algo": "SHA-512",
        "bytes": struct.pack(">QQQQQQQQ",
            0x6A09E667F3BCC908, 0xBB67AE8584CAA73B,
            0x3C6EF372FE94F82B, 0xA54FF53A5F1D36F1,
            0x510E527FADE682D1, 0x9B05688C2B3E6C1F,
            0x1F83D9ABFB41BD6B, 0x5BE0CD19137E2179),
        "confidence": "high",
        "desc": "SHA-512 Initialization Vector (all 8 words, big-endian)",
    },

    # ── ARX ciphers ──
    "CHACHA_CONSTANT": {
        "algo": "ChaCha20 / Salsa20",
        "bytes": b"expand 32-byte k",
        "confidence": "high",
        "desc": "expand 32-byte k constant",
    },

    # ── TEA/XTEA ──
    "TEA_DELTA_LE": {
        "algo": "TEA / XTEA",
        "bytes": struct.pack("<I", 0x9E3779B9),
        "confidence": "medium",
        "desc": "TEA delta (0x9E3779B9, little-endian)",
    },

    # ── CRC polynomials ──
    "CRC32_POLY_LE": {
        "algo": "CRC-32",
        "bytes": struct.pack("<I", 0xEDB88320),
        "confidence": "low",
        "desc": "CRC-32 reflected polynomial (found 3+ times)",
    },

    # ── Base64 alphabets ──
    "BASE64_STANDARD": {
        "algo": "Base64 encoding",
        "bytes": b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/",
        "confidence": "high",
        "desc": "Standard Base64 alphabet",
    },
    "BASE64_URLSAFE": {
        "algo": "Base64 encoding",
        "bytes": b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_",
        "confidence": "high",
        "desc": "URL-safe Base64 alphabet",
    },

    # ── SM4 (Chinese national standard) ──
    "SM4_SBOX": {
        "algo": "SM4 (Chinese national standard)",
        "bytes": bytes([0xd6,0x90,0xe9,0xfe,0xcc,0xe1,0x3d,0xb7,0x16,0xb6,0x14,0xc2,0x28,0xfb,0x2c,0x05]),
        "confidence": "high",
        "desc": "SM4 S-box (first 16 of 256)",
    },
}

# Group by algorithm family for reporting
ALGO_FAMILIES = {
    "AES": ["AES_SBOX", "AES_INV_SBOX", "AES_RCON"],
    "DES": ["DES_IP", "DES_PC1"],
    "MD5": ["MD5_IV"],
    "SHA-1": ["SHA1_IV"],
    "SHA-256": ["SHA256_IV"],
    "SHA-512": ["SHA512_IV"],
    "ChaCha20/Salsa20": ["CHACHA_CONSTANT"],
    "TEA/XTEA": ["TEA_DELTA_LE"],
    "CRC-32": ["CRC32_POLY_LE"],
    "Base64": ["BASE64_STANDARD", "BASE64_URLSAFE"],
    "SM4": ["SM4_SBOX"],
}


# ── Utility ───────────────────────────────────────────────────────────────────

def shannon_entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = Counter(data)
    total = len(data)
    return -sum((c / total) * math.log2(c / total) for c in counts.values())


def read_file(path: str, max_size: int | None = None) -> bytes:
    with open(path, "rb") as f:
        return f.read(max_size) if max_size else f.read()


def find_high_entropy_regions(data: bytes, window: int = 256,
                               threshold: float = 7.0) -> list[dict]:
    """Scan for high-entropy windows that may indicate encrypted/key data."""
    regions = []
    step = window // 2  # 50% overlap
    for start in range(0, len(data) - window + 1, step):
        window_data = data[start:start + window]
        ent = shannon_entropy(window_data)
        if ent >= threshold:
            regions.append({
                "offset": start,
                "offset_hex": hex(start),
                "entropy": round(ent, 3),
                "size": window,
            })
    # Merge adjacent regions
    merged = []
    for r in regions:
        if merged and r["offset"] <= merged[-1]["offset"] + merged[-1]["size"]:
            merged[-1]["size"] = r["offset"] + r["size"] - merged[-1]["offset"]
            merged[-1]["entropy"] = max(merged[-1]["entropy"], r["entropy"])
        else:
            merged.append(r.copy())
    return merged


# ── Scan ──────────────────────────────────────────────────────────────────────

def scan_constants(data: bytes, target: str | None = None) -> list[dict]:
    """Scan binary data for known cryptographic constants."""
    findings = []
    for name, info in CONSTANTS.items():
        if target and target.lower() not in info["algo"].lower():
            continue
        pattern = info["bytes"]
        offset = 0
        count = 0
        while True:
            idx = data.find(pattern, offset)
            if idx == -1:
                break
            count += 1
            if count == 1:  # Record first occurrence
                findings.append({
                    "constant": name,
                    "algorithm": info["algo"],
                    "confidence": info["confidence"],
                    "description": info["desc"],
                    "offset": idx,
                    "offset_hex": hex(idx),
                    "occurrences": 0,  # Will update after counting
                })
            offset = idx + 1

        # Update occurrence count
        if count > 0 and findings:
            for f in findings:
                if f["constant"] == name:
                    f["occurrences"] = count

    # Special: CRC-32 requires 3+ occurrences to be meaningful
    findings = [f for f in findings
                if not (f["constant"] == "CRC32_POLY_LE" and f["occurrences"] < 3)]

    return findings


def scan_imports(data: bytes) -> list[str]:
    """Scan for cryptographic API import patterns."""
    crypto_apis = [
        "CryptEncrypt", "CryptDecrypt", "CryptAcquireContext",
        "BCryptEncrypt", "BCryptDecrypt",
        "EVP_EncryptInit", "EVP_DecryptInit",
        "AES_encrypt", "AES_set_encrypt_key",
        "RC4_set_key", "RC4",
        "RSA_public_encrypt", "RSA_private_decrypt",
        "MD5_Init", "SHA1_Init", "SHA256_Init",
        "encrypt", "decrypt", "cipher",
    ]
    found = []
    data_lower = data.lower()
    for api in crypto_apis:
        if api.lower().encode() in data_lower:
            found.append(api)
    return sorted(set(found))


# ── Output ────────────────────────────────────────────────────────────────────

def write_report(findings: list[dict], entropy_regions: list[dict],
                 imports: list[str], path: str, sample_name: str):
    """Write a Markdown report of crypto findings."""
    lines = [
        f"# Crypto Analysis: {sample_name}",
        "",
        f"**Scan time:** {datetime.now(timezone.utc).isoformat()}",
        "",
    ]

    # Group by algorithm
    by_algo = {}
    for f in findings:
        algo = f["algorithm"]
        if algo not in by_algo:
            by_algo[algo] = []
        by_algo[algo].append(f)

    if by_algo:
        lines += [
            "## Identified Cryptographic Constants",
            "",
            "| Algorithm | Indicator | Confidence | Offset | Occurrences |",
            "|---|---|---|---|---|",
        ]
        for algo, items in sorted(by_algo.items()):
            for item in items:
                lines.append(
                    f"| {algo} | {item['description']} | {item['confidence']} "
                    f"| {item['offset_hex']} | {item['occurrences']} |"
                )

    if imports:
        lines += [
            "",
            "## Cryptographic API Imports",
        ]
        for api in imports:
            lines.append(f"- `{api}`")

    if entropy_regions:
        lines += [
            "",
            f"## High-Entropy Regions ({len(entropy_regions)} regions)",
            "",
            "| Offset | Size | Entropy |",
            "|---|---|---|",
        ]
        for r in entropy_regions[:20]:
            lines.append(f"| {r['offset_hex']} | {r['size']}B | {r['entropy']:.3f} |")

    if not by_algo and not imports:
        lines += ["", "No known cryptographic constants or APIs detected.", ""]
        if entropy_regions:
            lines.append(f"However, {len(entropy_regions)} high-entropy regions were found "
                          f"— these may contain encrypted data or keys.")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="find_crypto — cryptographic constant scanner")
    parser.add_argument("binary", help="Path to binary file to scan")
    parser.add_argument("--out", "-o", default="./crypto_scan",
                        help="Output directory (default: ./crypto_scan)")
    parser.add_argument("--constants-only", action="store_true",
                        help="Only scan for constants (skip imports/entropy)")
    parser.add_argument("--entropy-threshold", type=float, default=7.0,
                        help="Entropy threshold for high-entropy regions (default: 7.0)")
    parser.add_argument("--target", "-t",
                        help="Target specific algorithm (e.g., aes, des, sha)")
    parser.add_argument("--json", action="store_true",
                        help="Output JSON to stdout")

    args = parser.parse_args()

    if not os.path.exists(args.binary):
        print(f"Error: file not found: {args.binary}", file=sys.stderr)
        sys.exit(1)

    sample_name = Path(args.binary).name
    data = read_file(args.binary)

    print(f"[*] Scanning {sample_name} ({len(data):,} bytes)...")

    # Run scans
    findings = scan_constants(data, args.target)
    crypto_apis = scan_imports(data) if not args.constants_only else []
    entropy_regions = []
    if not args.constants_only:
        entropy_regions = find_high_entropy_regions(data, threshold=args.entropy_threshold)

    # Output
    os.makedirs(args.out, exist_ok=True)

    result = {
        "sample": sample_name,
        "size": len(data),
        "scan_time": datetime.now(timezone.utc).isoformat(),
        "constants_found": len(findings),
        "findings": [{"algorithm": f["algorithm"], "indicator": f["description"],
                       "confidence": f["confidence"], "offset": f["offset_hex"],
                       "occurrences": f["occurrences"]} for f in findings],
        "crypto_api_imports": crypto_apis,
        "high_entropy_regions": len(entropy_regions),
    }

    json_path = os.path.join(args.out, "crypto_scan.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    md_path = os.path.join(args.out, "crypto_scan.md")
    write_report(findings, entropy_regions, crypto_apis, md_path, sample_name)

    print(f"[+] Found {len(findings)} crypto indicators")
    for f_item in findings:
        print(f"    {f_item['algorithm']}: {f_item['description']} ({f_item['confidence']}) @ {f_item['offset_hex']}")
    if crypto_apis:
        print(f"[+] Found {len(crypto_apis)} crypto API imports")
    if entropy_regions:
        print(f"[+] Found {len(entropy_regions)} high-entropy regions (>= {args.entropy_threshold})")
    print(f"[+] Output: {json_path}, {md_path}")

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
