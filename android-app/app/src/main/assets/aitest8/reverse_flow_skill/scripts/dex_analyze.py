#!/usr/bin/env python3
"""dex_analyze.py — DEX (Dalvik Executable) deep structural analysis

Parses the DEX header, string/type/proto/field/method IDs, class definitions,
access flags, and detects obfuscators/packers targeting Android Dalvik/ART.

Usage:
    python dex_analyze.py <classes.dex> [--out <dir>] [--json]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys
import zlib
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common.io_utils import read_file, FileTooLargeError  # noqa: E402
from common.entropy import shannon_entropy  # noqa: E402

# ── DEX constants ──────────────────────────────────────────────────────────

DEX_MAGIC_VERSIONS = {
    b"dex\n035\x00": "Android 2.3- (API 1-9)",
    b"dex\n036\x00": "Android 3.0 (API 10)",
    b"dex\n037\x00": "Android 4.4- (API 11-20)",
    b"dex\n038\x00": "Android 5.0+ (API 21-27)",
    b"dex\n039\x00": "Android 8.0+ (API 28)",
    b"dex\n040\x00": "Android 14+ (API 34)",
}

ENDIAN_CONSTANT = 0x12345678
REVERSE_ENDIAN_CONSTANT = 0x78563412

# Class access flags (from Dalvik/libdex)
ACC_PUBLIC = 0x1
ACC_PRIVATE = 0x2
ACC_PROTECTED = 0x4
ACC_STATIC = 0x8
ACC_FINAL = 0x10
ACC_SYNCHRONIZED = 0x20
ACC_VOLATILE = 0x40
ACC_BRIDGE = 0x40
ACC_VARARGS = 0x80
ACC_NATIVE = 0x100
ACC_INTERFACE = 0x200
ACC_ABSTRACT = 0x400
ACC_STRICT = 0x800
ACC_SYNTHETIC = 0x1000
ACC_ANNOTATION = 0x2000
ACC_ENUM = 0x4000
ACC_CONSTRUCTOR = 0x10000
ACC_DECLARED_SYNCHRONIZED = 0x20000

CLASS_ACCESS_NAMES: dict[int, str] = {
    0x1: "PUBLIC", 0x2: "PRIVATE", 0x4: "PROTECTED", 0x8: "STATIC",
    0x10: "FINAL", 0x200: "INTERFACE", 0x400: "ABSTRACT",
    0x1000: "SYNTHETIC", 0x2000: "ANNOTATION", 0x4000: "ENUM",
}

METHOD_ACCESS_NAMES: dict[int, str] = {
    0x1: "PUBLIC", 0x2: "PRIVATE", 0x4: "PROTECTED", 0x8: "STATIC",
    0x10: "FINAL", 0x20: "SYNCHRONIZED", 0x40: "BRIDGE",
    0x80: "VARARGS", 0x100: "NATIVE", 0x400: "ABSTRACT",
    0x800: "STRICT", 0x1000: "SYNTHETIC", 0x10000: "CONSTRUCTOR",
    0x20000: "DECLARED_SYNCHRONIZED",
}

FIELD_ACCESS_NAMES: dict[int, str] = {
    0x1: "PUBLIC", 0x2: "PRIVATE", 0x4: "PROTECTED", 0x8: "STATIC",
    0x10: "FINAL", 0x40: "VOLATILE", 0x80: "TRANSIENT",
    0x1000: "SYNTHETIC", 0x4000: "ENUM",
}

# Known Android packers/obfuscators (string patterns)
PACKER_SIGNATURES: dict[str, list[str]] = {
    "DexGuard": ["DexGuard", "dexguard"],
    "DexProtector": ["DexProtector", "dexprotector"],
    "Bangcle/SecNeo": ["bangcle", "secneo", "libDexHelper", "libsecexe"],
    "Qihoo 360": ["qihoo", "libjiagu", "jiagu"],
    "Tencent Legu": ["tencent", "legu", "libshell", "libtup"],
    "Ijiami": ["ijiami", "IjaMiA"],
    "AliPay ARL": ["libmobisec", "libsgmain", "libmsoa"],
    "APKProtect": ["APKProtect", "apkprotect"],
    "LIAPP": ["LIAPP", "liapp"],
    "Naga": ["naga"],
    "DingXiang": ["dingxiang", "libDx"],
    "AppSolid": ["AppSolid"],
    "Armor": ["armor", "libarmor"],
    "Baidu": ["baidu", "libbaiduprotect"],
    "NetEase Shield": ["netease", "libneshield"],
    "VMPsoft": ["vmpsoft", "libVMP"],
    "Virbox": ["Virbox", "virbox"],
}


def _decode_access_flags(flags: int, flag_map: dict[int, str]) -> list[str]:
    return [name for bit, name in sorted(flag_map.items()) if flags & bit]


# ── Utility ────────────────────────────────────────────────────────────────

def _read_uleb128(data: bytes, offset: int) -> tuple[int, int]:
    """Read unsigned LEB128, return (value, bytes_read)."""
    result = 0
    shift = 0
    while offset < len(data):
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            break
        shift += 7
    return result, offset


def _read_sleb128(data: bytes, offset: int) -> tuple[int, int]:
    """Read signed LEB128, return (value, bytes_read)."""
    result = 0
    shift = 0
    while offset < len(data):
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if not (byte & 0x80):
            if shift < 64 and (byte & 0x40):
                result |= -(1 << shift)
            # unsigned result is fine for our use
            break
    return result, offset


# ── Parsing ────────────────────────────────────────────────────────────────

def parse_dex(data: bytes, path: str) -> dict:
    result: dict = {
        "file": Path(path).name,
        "size": len(data),
        "scan_time": datetime.now(timezone.utc).isoformat(),
    }

    if len(data) < 0x70:
        result["error"] = "File too small for DEX header"
        return result

    magic = data[:8]
    if magic[:3] != b"dex":
        result["error"] = f"Not a DEX file (magic: {magic[:4].hex()})"
        return result

    result["dex_version"] = DEX_MAGIC_VERSIONS.get(magic, f"unknown ({magic.decode('ascii', errors='replace')})")

    checksum = struct.unpack_from("<I", data, 8)[0]
    sha1_sig = data[12:32]
    result["checksum"] = checksum
    result["sha1_signature"] = sha1_sig.hex()

    file_size = struct.unpack_from("<I", data, 32)[0]
    header_size = struct.unpack_from("<I", data, 36)[0]
    endian_tag = struct.unpack_from("<I", data, 40)[0]

    result["is_little_endian"] = endian_tag == ENDIAN_CONSTANT

    link_size = struct.unpack_from("<I", data, 44)[0]
    link_off = struct.unpack_from("<I", data, 48)[0]
    map_off = struct.unpack_from("<I", data, 52)[0]

    # ID table headers: size (4) + offset (4)
    string_ids_size = struct.unpack_from("<I", data, 56)[0]
    string_ids_off = struct.unpack_from("<I", data, 60)[0]
    type_ids_size = struct.unpack_from("<I", data, 64)[0]
    type_ids_off = struct.unpack_from("<I", data, 68)[0]
    proto_ids_size = struct.unpack_from("<I", data, 72)[0]
    proto_ids_off = struct.unpack_from("<I", data, 76)[0]
    field_ids_size = struct.unpack_from("<I", data, 80)[0]
    field_ids_off = struct.unpack_from("<I", data, 84)[0]
    method_ids_size = struct.unpack_from("<I", data, 88)[0]
    method_ids_off = struct.unpack_from("<I", data, 92)[0]
    class_defs_size = struct.unpack_from("<I", data, 96)[0]
    class_defs_off = struct.unpack_from("<I", data, 100)[0]
    data_size = struct.unpack_from("<I", data, 104)[0]
    data_off = struct.unpack_from("<I", data, 108)[0]

    result["header"] = {
        "file_size": file_size,
        "header_size": header_size,
        "endian_constant": hex(endian_tag),
        "link": {"size": link_size, "offset": link_off},
        "map_offset": map_off,
    }

    result["id_tables"] = {
        "string_ids": {"size": string_ids_size, "offset": string_ids_off},
        "type_ids": {"size": type_ids_size, "offset": type_ids_off},
        "proto_ids": {"size": proto_ids_size, "offset": proto_ids_off},
        "field_ids": {"size": field_ids_size, "offset": field_ids_off},
        "method_ids": {"size": method_ids_size, "offset": method_ids_off},
        "class_defs": {"size": class_defs_size, "offset": class_defs_off},
        "data": {"size": data_size, "offset": data_off},
    }

    # Extract string table
    strings = _extract_strings(data, string_ids_off, string_ids_size)
    result["strings"] = {"total": len(strings), "sample": strings[:300]}

    # Type names
    types = _extract_type_names(data, type_ids_off, type_ids_size, strings)
    result["types"] = {"total": len(types), "sample": types[:200]}

    # Class definitions
    result["classes"] = _extract_classes(data, class_defs_off, class_defs_size, strings, types)

    # Entropy
    result["entropy"] = round(shannon_entropy(data), 4)

    # Security
    result["security"] = _check_dex_security(result, data, strings)

    # Packer detection
    result["packers"] = _detect_packers(data, strings)

    # Additional integrity checks
    result["integrity"] = _check_integrity(data, result, checksum, sha1_sig)

    return result


def _extract_strings(data: bytes, offset: int, count: int) -> list[str]:
    strings = []
    for i in range(min(count, 65536)):
        off = offset + i * 4
        if off + 4 > len(data):
            break
        str_off = struct.unpack_from("<I", data, off)[0]
        # string_data_item has a ULEB128-prefixed utf16_size before the MUTF-8 data
        if str_off >= len(data):
            strings.append(f"<string_{i}>")
            continue
        _, data_start = _read_uleb128(data, str_off)
        end = data.find(b"\x00", data_start)
        if end == -1:
            strings.append(f"<string_{i}>")
            continue
        raw = data[data_start:end]
        try:
            s = raw.decode("utf-8", errors="replace")
            strings.append(s)
        except (UnicodeDecodeError, ValueError):
            strings.append(raw.decode("latin-1", errors="replace"))
    return strings


def _extract_type_names(data: bytes, offset: int, count: int, strings: list[str]) -> list[str]:
    types = []
    for i in range(min(count, 65536)):
        off = offset + i * 4
        if off + 4 > len(data):
            break
        type_idx = struct.unpack_from("<I", data, off)[0]
        if 0 <= type_idx < len(strings):
            types.append(strings[type_idx])
        else:
            types.append(f"<invalid_type_{i}>")
    return types


def _extract_classes(data: bytes, offset: int, count: int, strings: list[str], types: list[str]) -> dict:
    result: dict = {
        "total": count,
        "interfaces": 0,
        "annotations": 0,
        "synthetic": 0,
        "sample": [],
    }

    for i in range(min(count, min(65536, (len(data) - offset) // 32))):
        off = offset + i * 32
        if off + 32 > len(data):
            break

        class_idx = struct.unpack_from("<I", data, off)[0]
        access_flags = struct.unpack_from("<I", data, off + 4)[0]
        superclass_idx = struct.unpack_from("<I", data, off + 8)[0]
        interfaces_off = struct.unpack_from("<I", data, off + 12)[0]
        source_file_idx = struct.unpack_from("<I", data, off + 16)[0]
        annotations_off = struct.unpack_from("<I", data, off + 20)[0]
        class_data_off = struct.unpack_from("<I", data, off + 24)[0]

        class_name = types[class_idx] if 0 <= class_idx < len(types) else f"<class_{i}>"
        acc = _decode_access_flags(access_flags, CLASS_ACCESS_NAMES)

        if interfaces_off != 0:
            result["interfaces"] += 1
        if annotations_off != 0:
            result["annotations"] += 1
        if access_flags & ACC_SYNTHETIC:
            result["synthetic"] += 1

        if i < 100:
            entry = {
                "name": class_name,
                "access_flags": access_flags,
                "access": acc,
                "superclass": types[superclass_idx] if 0 <= superclass_idx < len(types) else "?",
            }
            if source_file_idx != 0xFFFFFFFF and 0 <= source_file_idx < len(strings):
                entry["source_file"] = strings[source_file_idx]
            if class_data_off != 0:
                methods, fields_count = _count_class_members(data, class_data_off)
                if methods:
                    entry["total_methods"] = methods
                if fields_count > 0:
                    entry["total_fields"] = fields_count
            result["sample"].append(entry)

    return result


def _count_class_members(data: bytes, offset: int) -> tuple[int, int]:
    """Parse class_data_item header to count methods and fields. Returns (method_count, field_count)."""
    try:
        pos = offset
        if pos >= len(data):
            return 0, 0

        # static_fields_size (uleb128), instance_fields_size, direct_methods_size, virtual_methods_size
        static_fields, pos = _read_uleb128(data, pos)
        instance_fields, pos = _read_uleb128(data, pos)
        direct_methods, pos = _read_uleb128(data, pos)
        virtual_methods, pos = _read_uleb128(data, pos)

        return direct_methods + virtual_methods, static_fields + instance_fields
    except (IndexError, ValueError):
        return 0, 0


def _check_dex_security(result: dict, data: bytes, strings: list[str]) -> dict:
    sec: dict = {
        "debuggable": False,
        "allow_backup": True,
        "has_native_code": False,
        "has_reflection": False,
        "has_dynamic_load": False,
        "has_encrypted_strings": False,
    }

    string_lower = " ".join(s[:100].lower() for s in strings[:5000])

    # Debuggable detection — check for debuggable flag string or build config
    if "debuggable" in string_lower or "BuildConfig.DEBUG" in string_lower:
        sec["debuggable"] = True

    # Native code references
    if "System.loadLibrary" in string_lower or "System.load(" in string_lower:
        sec["has_native_code"] = True

    # Reflection usage
    if "java.lang.reflect" in string_lower:
        sec["has_reflection"] = True

    # Dynamic class loading
    if "dalvik.system.DexClassLoader" in string_lower or "dalvik.system.PathClassLoader" in string_lower:
        sec["has_dynamic_load"] = True

    # Encrypted string detection (high entropy short strings or base64-encoded large strings)
    encoded_count = sum(1 for s in strings[:1000] if len(s) > 64 and "/" in s and "+" in s)
    if encoded_count > 10:
        sec["has_encrypted_strings"] = True

    return sec


def _detect_packers(data: bytes, strings: list[str]) -> dict:
    result: dict = {"detected": [], "confidence": "none"}

    search_text = " ".join(strings[:3000]).lower()
    raw_text = data[:100 * 1024].decode("latin-1", errors="replace").lower()

    for packer_name, signatures in PACKER_SIGNATURES.items():
        for sig in signatures:
            if sig.lower() in search_text or sig.lower() in raw_text:
                result["detected"].append(packer_name)
                break

    if result["detected"]:
        result["confidence"] = "medium"

    return result


def _check_integrity(data: bytes, result: dict, expected_checksum: int, expected_sha1: bytes) -> dict:
    integrity = {"checksum_valid": False, "sha1_valid": False}

    # Checksum: adler32 of everything except magic[0:8] and checksum[8:12]
    try:
        computed_checksum = zlib.adler32(data[12:]) & 0xFFFFFFFF
        integrity["checksum_valid"] = computed_checksum == expected_checksum
    except zlib.error:
        pass

    # SHA-1: hash of bytes from offset 32 to EOF
    computed_sha1 = hashlib.sha1(data[32:]).digest()
    integrity["sha1_valid"] = computed_sha1 == expected_sha1

    return integrity


# ── Reporting ──────────────────────────────────────────────────────────────

def write_report(result: dict, path: str):
    lines = [
        f"# DEX Analysis: {result.get('file', 'unknown')}",
        "",
        f"**Scan time:** {result.get('scan_time', '')}  ",
        f"**File size:** {result.get('size', 0):,} bytes  ",
        f"**DEX version:** {result.get('dex_version', '?')}  ",
        f"**Entropy:** {result.get('entropy', '?')}  ",
        "",
    ]

    if "error" in result:
        lines.append(f"**Error:** {result['error']}")
        lines.append("")
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        return

    # ID Tables
    id_tbl = result.get("id_tables", {})
    lines += [
        "## ID Tables",
        "",
        "| Table | Count |",
        "|---|---|",
    ]
    for name, info in id_tbl.items():
        if name == "data":
            continue
        lines.append(f"| {name} | {info['size']:,} |")
    lines.append("")

    # Integrity
    integ = result.get("integrity", {})
    lines += [
        "## Integrity",
        "",
        "| Check | Status |",
        "|---|---|",
        f"| Checksum (adler32) | {'Valid' if integ.get('checksum_valid') else '**Invalid**'} |",
        f"| SHA-1 | {'Valid' if integ.get('sha1_valid') else '**Invalid**'} |",
        "",
    ]

    # Strings
    strs = result.get("strings", {})
    lines += [
        f"## Strings ({strs.get('total', 0):,} total)",
        "",
    ]
    sample = strs.get("sample", [])
    for s in sample[:50]:
        if len(s) < 200:
            lines.append(f"- `{s}`")
    lines.append("")

    # Classes
    classes = result.get("classes", {})
    lines += [
        f"## Classes ({classes.get('total', 0):,} total)",
        "",
        f"- Interfaces: {classes.get('interfaces', 0):,}",
        f"- With annotations: {classes.get('annotations', 0):,}",
        f"- Synthetic: {classes.get('synthetic', 0):,}",
        "",
    ]
    sample_classes = classes.get("sample", [])
    if sample_classes:
        lines += ["| Class | Access | Superclass |", "|---|---|---|"]
        for c in sample_classes[:30]:
            flags_str = ", ".join(c.get("access", [])[:4])
            lines.append(f"| {c.get('name', '?')} | {flags_str} | {c.get('superclass', '?')} |")
        lines.append("")

    # Types
    types = result.get("types", {})
    if types.get("sample"):
        lines += ["## Type Sample", ""]
        for t in types["sample"][:30]:
            lines.append(f"- {t}")
        lines.append("")

    # Packers
    packers = result.get("packers", {})
    lines += [
        "## Packer / Obfuscator Detection",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| Detected | {', '.join(packers.get('detected', [])) or 'None'} |",
        f"| Confidence | {packers.get('confidence', 'none')} |",
        "",
    ]

    # Security
    sec = result.get("security", {})
    lines += [
        "## Security Indicators",
        "",
        "| Feature | Observed |",
        "|---|---|",
        f"| Debuggable | {sec.get('debuggable')} |",
        f"| Native code | {sec.get('has_native_code')} |",
        f"| Reflection | {sec.get('has_reflection')} |",
        f"| Dynamic loading | {sec.get('has_dynamic_load')} |",
        f"| Encrypted strings | {sec.get('has_encrypted_strings')} |",
        "",
    ]

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ── CLI ────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="dex_analyze — DEX file deep analysis")
    parser.add_argument("dex_file", help="Path to classes.dex file")
    parser.add_argument("--out", "-o", default="dex_analyze", help="Output directory")
    parser.add_argument("--json", action="store_true", help="Output JSON to stdout")
    args = parser.parse_args()

    if not os.path.exists(args.dex_file):
        print(f"Error: file not found: {args.dex_file}", file=sys.stderr)
        sys.exit(1)

    try:
        data = read_file(args.dex_file, allow_truncation=False)
        result = parse_dex(data, args.dex_file)
    except (OSError, struct.error, ValueError, FileTooLargeError) as e:
        print(f"Error parsing {args.dex_file}: {e}", file=sys.stderr)
        sys.exit(1)

    if "error" in result:
        print(f"Error: {result['error']}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)
    json_path = os.path.join(args.out, "dex_analyze.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    md_path = os.path.join(args.out, "dex_analyze.md")
    write_report(result, md_path)

    print(f"[+] Output: {json_path}, {md_path}")
    print(f"[+] DEX version: {result.get('dex_version', '?')}")
    print(f"[+] Strings: {result['strings']['total']:,}, Types: {result['types']['total']:,}, "
          f"Classes: {result['classes']['total']:,}")

    integ = result.get("integrity", {})
    checksum_ok = integ.get("checksum_valid", False)
    sha1_ok = integ.get("sha1_valid", False)
    if not checksum_ok or not sha1_ok:
        print(f"[!] Integrity: checksum={'OK' if checksum_ok else 'FAIL'}, sha1={'OK' if sha1_ok else 'FAIL'}")

    packers = result.get("packers", {}).get("detected", [])
    if packers:
        print(f"[!] Packer(s) detected: {', '.join(packers)}")

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
