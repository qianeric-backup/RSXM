#!/usr/bin/env python3
"""dotnet_analyze.py — .NET assembly structural analysis

Parses the CLR header, metadata streams (#~, #Strings, #US, #Blob, #GUID),
extracts assembly/module identity, counts types/methods, detects obfuscators,
and reports security attributes (Strong Name, SuppressIldasm, Authenticode).

Usage:
    python dotnet_analyze.py <dotnet_pe> [--out <dir>] [--json]
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common.io_utils import read_file, FileTooLargeError  # noqa: E402
from common.entropy import shannon_entropy  # noqa: E402

# ── PE / CLR constants ──────────────────────────────────────────────────────

IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR = 14
IMAGE_COR20_HEADER_SIZE = 72

# Metadata root magic
META_MAGIC = 0x424A5342  # "BSJB"

# #~ stream heap sizes flags
HEAP_BIG_STRING = 0x01
HEAP_BIG_GUID = 0x02
HEAP_BIG_BLOB = 0x04

# Metadata table indices
TABLE_MODULE = 0x00
TABLE_TYPEREF = 0x01
TABLE_TYPEDEF = 0x02
TABLE_FIELD = 0x04
TABLE_METHODDEF = 0x06
TABLE_PARAM = 0x08
TABLE_MEMBERREF = 0x0A
TABLE_ASSEMBLY = 0x20
TABLE_ASSEMBLYREF = 0x23
TABLE_EXPORTEDTYPE = 0x27
TABLE_MANIFESTRESOURCE = 0x28

TABLE_NAMES = {
    0x00: "Module", 0x01: "TypeRef", 0x02: "TypeDef", 0x04: "Field",
    0x06: "MethodDef", 0x08: "Param", 0x09: "InterfaceImpl",
    0x0A: "MemberRef", 0x0B: "Constant", 0x0C: "CustomAttribute",
    0x0D: "FieldMarshal", 0x0E: "DeclSecurity", 0x0F: "ClassLayout",
    0x10: "FieldLayout", 0x11: "StandAloneSig", 0x12: "EventMap",
    0x14: "Event", 0x15: "PropertyMap", 0x17: "Property",
    0x18: "MethodSemantics", 0x19: "MethodImpl", 0x1A: "ModuleRef",
    0x1B: "TypeSpec", 0x1C: "ImplMap", 0x1D: "FieldRVA",
    0x20: "Assembly", 0x21: "AssemblyProcessor", 0x22: "AssemblyOS",
    0x23: "AssemblyRef", 0x24: "AssemblyRefProcessor", 0x25: "AssemblyRefOS",
    0x26: "File", 0x27: "ExportedType", 0x28: "ManifestResource",
    0x29: "NestedClass", 0x2A: "GenericParam", 0x2B: "MethodSpec",
    0x2C: "GenericParamConstraint",
}

# Known obfuscator signatures in #Strings / #US heaps
OBFUSCATOR_SIGNATURES = {
    "ConfuserEx": ["ConfuserEx", "Confuser", "cfx.", "Protections."],
    "Obfuscar": ["Obfuscar", "Obfuscation.", "Obfuscar."],
    "SmartAssembly": ["SmartAssembly", "SmartAssembly.", "RedGate."],
    ".NET Reactor": ["NETReactor", "Reactor.", "Eziriz."],
    "Babel Obfuscator": ["Babel", "BabelObfuscator"],
    "DeepSea": ["DeepSea", "DeepSeaObfuscator"],
    "Agile.NET": ["AgileDotNet", "SecureTeam"],
    "Eazfuscator": ["Eazfuscator.NET", "Eazfuscator"],
    "Goliath.NET": ["Goliath", "GoliathDotNet"],
    "Yano": ["Yano", "YanoObfuscator"],
    "Phoenix Protector": ["PhoenixProtect", "Stub."],
    "DNGuard": ["DNGuard", "DNGuard_HVM"],
    "VMProtect": ["VMProtect", "VMP"],
    "Themida": ["Themida", "Oreans"],
    "CodeWall": ["CodeWall"],
    "Crypto Obfuscator": ["CryptoObfuscator", "CryptoObfuscator."],
    "Spices.Net": ["NineRays", "Spices."],
    "ElecKey": ["ElecKey", "ElecKey."],
    "MaxtoCode": ["MaxtoCode", "MaxtoCode_"],
    "XeonCode": ["XenoCode", "Xenocode"],
    "MPRESS": ["MPRESS", "MARCROC-CODE"],
    "Dotfuscator": ["Dotfuscator", "DotfuscatorAttribute", "PreEmptive"],
    "ILProtector": ["ILProtector", "ILProtector."],
    "Skater .NET Obfuscator": ["Skater", "RustemSoft"],
    "CodeVeil": ["CodeVeil"],
    "ArmDot": ["ArmDot", "ArmDot."],
    "KoiVM": ["KoiVM", "Koi.", "KoiVM.Runtime"],
    "BitMono": ["BitMono", "BitMono."],
    "NetGuard": ["NetGuard", "NetGuard."],
}

# De4dot-supported obfuscators (subset that de4dot can fully/partially clean)
DE4DOT_SUPPORTED = {
    "ConfuserEx", "Obfuscar", "SmartAssembly", ".NET Reactor", "Babel Obfuscator",
    "DeepSea", "Agile.NET", "Eazfuscator", "Goliath.NET", "Phoenix Protector",
    "DNGuard", "Dotfuscator", "ILProtector", "Skater .NET Obfuscator",
    "Crypto Obfuscator", "CodeWall", "Spices.Net", "MPRESS", "MaxtoCode",
    "XeonCode", "CodeVeil", "KoiVM",
}


# ── Utility ────────────────────────────────────────────────────────────────

def _read_pe_data_dir(data: bytes) -> tuple[int, int] | None:
    """Extract COM descriptor (CLR header) RVA and size from PE optional header."""
    if len(data) < 64 or data[:2] != b"MZ":
        return None
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    if pe_offset + 4 > len(data) or data[pe_offset:pe_offset + 4] != b"PE\x00\x00":
        return None

    # Optional header starts at pe_offset + 24 (after PE sig + file header)
    oh_start = pe_offset + 24
    if oh_start + 2 > len(data):
        return None
    magic = struct.unpack_from("<H", data, oh_start)[0]
    is_pe32plus = magic == 0x20B

    # Number of data directory entries
    dd_count_offset = oh_start + (108 if is_pe32plus else 92)
    if dd_count_offset + 4 > len(data):
        return None
    num_dirs = struct.unpack_from("<I", data, dd_count_offset)[0]

    # Data directory entry 14 = CLR header
    dd_start = oh_start + (112 if is_pe32plus else 96)
    if num_dirs <= IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR:
        return None
    rva_offset = dd_start + IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR * 8
    size_offset = rva_offset + 4
    if size_offset + 4 > len(data):
        return None
    clr_rva = struct.unpack_from("<I", data, rva_offset)[0]
    clr_size = struct.unpack_from("<I", data, size_offset)[0]
    if clr_rva == 0:
        return None
    return clr_rva, clr_size


def _rva_to_offset(data: bytes, rva: int) -> int | None:
    """Convert PE RVA to file offset using section headers."""
    if len(data) < 64 or data[:2] != b"MZ":
        return None
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    num_sections = struct.unpack_from("<H", data, pe_offset + 6)[0]
    oh_size = struct.unpack_from("<H", data, pe_offset + 20)[0]
    sh_start = pe_offset + 24 + oh_size

    for i in range(min(num_sections, 96)):
        sh_off = sh_start + i * 40
        if sh_off + 40 > len(data):
            break
        s_va = struct.unpack_from("<I", data, sh_off + 12)[0]
        s_vsize = struct.unpack_from("<I", data, sh_off + 8)[0]
        s_raw = struct.unpack_from("<I", data, sh_off + 20)[0]
        if s_va <= rva < s_va + s_vsize:
            return (rva - s_va) + s_raw
    return None


def _read_null_terminated(data: bytes, offset: int) -> str:
    end = data.find(b"\x00", offset)
    if end == -1:
        return ""
    return data[offset:end].decode("utf-8", errors="replace")


def _decode_version(v: int) -> str:
    return f"{v >> 16}.{(v >> 8) & 0xFF}.{v & 0xFF}"


def _read_lenstr(data: bytes, offset: int) -> str | None:
    """Read length-prefixed UTF-8 string (used in metadata root)."""
    if offset + 4 > len(data):
        return None
    length = struct.unpack_from("<I", data, offset)[0]
    offset += 4
    if length == 0 or offset + length > len(data):
        return ""
    return data[offset:offset + length].decode("utf-8", errors="replace")


# ── Parsing ────────────────────────────────────────────────────────────────

def parse_dotnet(data: bytes, path: str) -> dict:
    result: dict = {
        "file": Path(path).name,
        "size": len(data),
        "scan_time": datetime.now(timezone.utc).isoformat(),
    }

    if len(data) < 64:
        result["error"] = "File too small"
        return result
    if data[:2] != b"MZ":
        result["error"] = "Not a PE file"
        return result
    if b"mscoree" not in data[:4096].lower():
        result["error"] = "Not a .NET assembly (no CLR header reference)"
        return result

    # Basic PE info
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
    machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
    machine_map = {0x14C: "x86", 0x8664: "x64", 0xAA64: "ARM64", 0x1C0: "ARM"}
    result["architecture"] = machine_map.get(machine, hex(machine))
    result["pe_offset"] = pe_offset

    # Timestamp
    try:
        ts = struct.unpack_from("<I", data, pe_offset + 8)[0]
        if ts != 0:
            result["pe_timestamp"] = datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()
    except (OSError, ValueError):
        pass

    # Entropy (global)
    result["entropy"] = round(shannon_entropy(data), 4)

    # CLR header
    dd = _read_pe_data_dir(data)
    if dd is None:
        result["error"] = "PE has CLR reference but no valid COM descriptor directory"
        return result

    clr_rva, clr_size = dd
    clr_offset = _rva_to_offset(data, clr_rva)
    if clr_offset is None or clr_offset + IMAGE_COR20_HEADER_SIZE > len(data):
        result["error"] = f"Cannot resolve CLR header RVA 0x{clr_rva:08X}"
        return result

    clr = _parse_clr_header(data, clr_offset)
    result["clr_header"] = clr

    # Metadata
    meta_rva = clr.get("meta_rva", 0)
    meta_size = clr.get("meta_size", 0)
    if meta_rva and meta_size:
        meta_offset = _rva_to_offset(data, meta_rva)
        if meta_offset is not None:
            result["metadata"] = _parse_metadata(data, meta_offset, meta_size)

    # Strong Name
    sn_rva = clr.get("strong_name_rva", 0)
    sn_size = clr.get("strong_name_size", 0)
    if sn_rva and sn_size:
        sn_offset = _rva_to_offset(data, sn_rva)
        if sn_offset is not None:
            result["strong_name"] = {"rva": sn_rva, "size": sn_size, "present": True}
        else:
            result["strong_name"] = {"rva": sn_rva, "size": sn_size, "present": False, "warning": "RVA not resolvable"}
    else:
        result["strong_name"] = {"present": False}

    # Security analysis
    result["security"] = _check_dotnet_security(result, data)

    # Obfuscator detection
    result["obfuscation"] = _detect_obfuscators(data, result.get("metadata", {}))

    return result


def _parse_clr_header(data: bytes, offset: int) -> dict:
    # IMAGE_COR20_HEADER layout (ECMA-335 II.25.3.3):
    # +0 cb, +4 MajorRuntimeVersion, +6 MinorRuntimeVersion,
    # +8 MetaData, +16 Flags, +20 EntryPointToken, +24 Resources,
    # +32 StrongNameSignature, +40 CodeManager, +48 VTableFixups
    cb = struct.unpack_from("<I", data, offset)[0]
    major = struct.unpack_from("<H", data, offset + 4)[0]
    minor = struct.unpack_from("<H", data, offset + 6)[0]
    meta_rva = struct.unpack_from("<I", data, offset + 8)[0]
    meta_size = struct.unpack_from("<I", data, offset + 12)[0]
    flags = struct.unpack_from("<I", data, offset + 16)[0]
    entry = struct.unpack_from("<I", data, offset + 20)[0]

    result = {
        "header_size": cb,
        "runtime_version": f"v{major}.{minor}",
        "flags": flags,
        "flags_detail": _decode_clr_flags(flags),
    }

    # COMIMAGE_FLAGS_NATIVE_ENTRYPOINT (0x10) set → field is RVA; else → metadata token
    if not (flags & 0x10):
        if entry != 0:
            table = entry >> 24
            row = entry & 0xFFFFFF
            result["entry_point_token"] = f"0x{entry:08X} (table {table}, row {row})"
        else:
            result["entry_point_token"] = "none"
    else:
        result["entry_point_rva"] = f"0x{entry:08X}" if entry else "none"

    result["meta_rva"] = meta_rva
    result["meta_size"] = meta_size

    # Strong name (+32)
    sn_rva = struct.unpack_from("<I", data, offset + 32)[0]
    sn_size = struct.unpack_from("<I", data, offset + 36)[0]
    result["strong_name_rva"] = sn_rva
    result["strong_name_size"] = sn_size

    # Resources (+24)
    resources_rva = struct.unpack_from("<I", data, offset + 24)[0]
    resources_size = struct.unpack_from("<I", data, offset + 28)[0]
    result["resources"] = {"rva": resources_rva, "size": resources_size}

    # VTable fixups (+48)
    vtfixup_rva = struct.unpack_from("<I", data, offset + 48)[0]
    vtfixup_size = struct.unpack_from("<I", data, offset + 52)[0]
    result["vtable_fixups"] = {"rva": vtfixup_rva, "size": vtfixup_size}

    return result


def _decode_clr_flags(flags: int) -> list[str]:
    flag_map = {
        0x1: "ILONLY",
        0x2: "32BITREQUIRED",
        0x4: "IL_LIBRARY",
        0x8: "STRONGNAMESIGNED",
        0x10: "NATIVE_ENTRYPOINT",
        0x10000: "TRACKDEBUGDATA",
        0x20000: "32BITPREFERRED",
    }
    return [name for bit, name in flag_map.items() if flags & bit]


def _parse_metadata(data: bytes, offset: int, size: int) -> dict:
    result: dict = {"offset": offset, "size": size}
    end = min(offset + size, len(data))
    if offset + 16 > end:
        result["error"] = "Metadata too small"
        return result

    # Root header
    magic = struct.unpack_from("<I", data, offset)[0]
    if magic != META_MAGIC:
        result["error"] = f"Invalid metadata signature: 0x{magic:08X}"
        return result

    major = struct.unpack_from("<H", data, offset + 4)[0]
    minor = struct.unpack_from("<H", data, offset + 6)[0]
    result["version"] = f"{major}.{minor}"

    # Version string
    verstr_len = struct.unpack_from("<I", data, offset + 12)[0]
    verstr = ""
    if verstr_len > 0 and offset + 16 + verstr_len <= end:
        verstr = data[offset + 16:offset + 16 + verstr_len].decode("utf-8", errors="replace").rstrip("\x00")
    result["version_string"] = verstr

    # Stream headers start after version string (padded to 4-byte boundary)
    streams_start = offset + 16 + verstr_len
    streams_start = (streams_start + 3) & ~3  # align to 4
    flags_pos = streams_start
    stream_count = 0
    # 2 bytes flags, 2 bytes stream count (ECMA-335 II.24.2.1)
    if flags_pos + 4 <= end:
        result["heap_flags"] = struct.unpack_from("<H", data, flags_pos)[0]
        stream_count = struct.unpack_from("<H", data, flags_pos + 2)[0]
    else:
        result["heap_flags"] = 0
        stream_count = 0

    streams_data_start = flags_pos + 4
    streams = []
    streams_pos = streams_data_start

    for _ in range(min(stream_count, 16)):
        if streams_pos + 8 > end:
            break
        s_offset = struct.unpack_from("<I", data, streams_pos)[0]
        s_size = struct.unpack_from("<I", data, streams_pos + 4)[0]
        s_name = _read_null_terminated(data, streams_pos + 8)
        s_abs_offset = offset + s_offset
        s_abs_end = min(s_abs_offset + s_size, end)

        stream_info = {
            "name": s_name,
            "offset": s_abs_offset,
            "size": s_size,
        }

        # Parse #~ stream (metadata tables)
        if s_name == "#~" and s_size >= 24:
            stream_info["tables"] = _parse_tilde_stream(data, s_abs_offset, s_abs_end)
        elif s_name == "#Strings":
            stream_info["string_count"] = data[s_abs_offset:s_abs_end].count(b"\x00")
        elif s_name == "#US":
            stream_info["estimated_user_strings"] = data[s_abs_offset:s_abs_end].count(b"\x00")
        elif s_name == "#GUID":
            stream_info["guid_count"] = s_size // 16

        streams.append(stream_info)
        # Advance: 8 bytes (offset+size) + null-terminated name, padded to 4-byte boundary
        name_len = len(s_name) + 1  # +1 for null terminator
        streams_pos += 8 + name_len
        streams_pos = (streams_pos + 3) & ~3

    result["streams"] = streams

    # Extract strings for obfuscator detection
    for s in streams:
        if s["name"] == "#Strings" and s["size"] < 100 * 1024 * 1024:
            raw = data[s["offset"]:s["offset"] + s["size"]]
            result["strings_sample"] = _sample_strings(raw, 2000)
        elif s["name"] == "#US" and s["size"] < 100 * 1024 * 1024:
            raw = data[s["offset"]:s["offset"] + s["size"]]
            result["us_strings_sample"] = _sample_us_strings(raw, 1000)

    return result


def _parse_tilde_stream(data: bytes, offset: int, end: int) -> dict:
    if offset + 24 > end:
        return {"error": "#~ stream too small"}

    major = data[offset + 4]
    minor = data[offset + 5]
    heap_sizes = data[offset + 6]

    valid = struct.unpack_from("<Q", data, offset + 8)[0]
    sorted_mask = struct.unpack_from("<Q", data, offset + 16)[0]

    # Build list of present tables
    present_tables = []
    row_pos = offset + 24
    for tid in range(64):
        if valid & (1 << tid):
            if row_pos + 4 > end:
                break
            rows = struct.unpack_from("<I", data, row_pos)[0]
            row_pos += 4
            name = TABLE_NAMES.get(tid, f"Table_{tid:02X}")
            present_tables.append({"id": tid, "name": name, "rows": rows})

    result = {
        "version": f"{major}.{minor}",
        "heap_sizes": {
            "big_string": bool(heap_sizes & HEAP_BIG_STRING),
            "big_guid": bool(heap_sizes & HEAP_BIG_GUID),
            "big_blob": bool(heap_sizes & HEAP_BIG_BLOB),
        },
        "valid_mask": valid,
        "sorted_mask": sorted_mask,
        "total_tables_present": len(present_tables),
        "tables": present_tables,
    }

    # Extract key counts
    for t in present_tables:
        tid = t["id"]
        if tid == TABLE_TYPEDEF:
            result["type_count"] = t["rows"]
        elif tid == TABLE_MODULE:
            result["module_count"] = t["rows"]
        elif tid == TABLE_METHODDEF:
            result["method_count"] = t["rows"]
        elif tid == TABLE_FIELD:
            result["field_count"] = t["rows"]
        elif tid == TABLE_ASSEMBLYREF:
            result["assembly_ref_count"] = t["rows"]
        elif tid == TABLE_ASSEMBLY:
            result["assembly_count"] = t["rows"]

    return result


def _sample_strings(data: bytes, max_chars: int) -> str:
    """Extract printable strings from #Strings heap for scanning."""
    parts = []
    current = bytearray()
    collected = 0
    for b in data:
        if b == 0:
            if current:
                s = current.decode("utf-8", errors="replace")
                if s.strip():
                    parts.append(s)
                    collected += len(s)
                    if collected >= max_chars:
                        break
            current = bytearray()
        elif 32 <= b < 127:
            current.append(b)
        elif b >= 0x80:
            current.append(b)  # Keep UTF-8 multi-byte
    if current and collected < max_chars:
        s = current.decode("utf-8", errors="replace")
        if s.strip():
            parts.append(s)
    return "\n".join(parts)


def _sample_us_strings(data: bytes, max_chars: int) -> str:
    """Extract strings from #US heap (length-prefixed, 7-bit packed length or 0x80 marker)."""
    parts = []
    collected = 0
    pos = 0
    while pos + 1 < len(data) and collected < max_chars:
        length = data[pos]
        offset = 1
        if length & 0x80:
            # 2-byte or 4-byte packed length
            if length & 0x40:
                if pos + 4 > len(data):
                    break
                length = ((length & 0x1F) << 24) | (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3]
                offset = 4
            else:
                length = ((length & 0x3F) << 8) | data[pos + 1]
                offset = 2
        string_end = pos + offset + length  # length is byte count per ECMA-335
        if string_end > len(data) or length == 0:
            pos += 1
            continue
        raw = data[pos + offset:string_end]
        try:
            s = raw.decode("utf-16-le", errors="replace")
            if s.strip() and len(s) < 256:
                parts.append(s)
                collected += len(s)
        except (UnicodeDecodeError, ValueError):
            pass
        pos = string_end
    return "\n".join(parts)


# ── Security ───────────────────────────────────────────────────────────────

def _check_dotnet_security(result: dict, data: bytes) -> dict:
    sec: dict = {
        "strong_name": result.get("strong_name", {}).get("present", False),
        "il_only": False,
        "suppress_ildasm": False,
        "authenticode": False,
        "debug_info": False,
    }

    # ILONLY flag
    clr = result.get("clr_header", {})
    if clr.get("flags", 0) & 0x1:
        sec["il_only"] = True

    # SuppressIldasmAttribute
    if b"SuppressIldasmAttribute" in data or b"SuppressIldasm" in data:
        sec["suppress_ildasm"] = True

    # Debug directory (PE debug data)
    pe_offset = result.get("pe_offset", 0)
    if pe_offset:
        oh_start = pe_offset + 24
        if oh_start + 2 <= len(data):
            magic = struct.unpack_from("<H", data, oh_start)[0]
            is_pe32plus = magic == 0x20B
            dd_count_off = oh_start + (108 if is_pe32plus else 92)
            if dd_count_off + 4 <= len(data):
                num_dirs = struct.unpack_from("<I", data, dd_count_off)[0]
                dd_start = oh_start + (112 if is_pe32plus else 96)
                if num_dirs > 6:  # entry 6 = debug directory
                    debug_rva = struct.unpack_from("<I", data, dd_start + 6 * 8)[0]
                    if debug_rva != 0:
                        sec["debug_info"] = True

    return sec


def _detect_obfuscators(data: bytes, metadata: dict) -> dict:
    result: dict = {
        "detected": [],
        "confidence": "none",
        "de4dot_cleanable": [],
    }

    # Search all strings from metadata
    all_strings = ""
    for key in ("strings_sample", "us_strings_sample"):
        s = metadata.get(key, "")
        if s:
            all_strings += s.lower() + "\n"

    if not all_strings:
        all_strings = data[:2 * 1024 * 1024].decode("latin-1", errors="replace").lower()

    for obf_name, signatures in OBFUSCATOR_SIGNATURES.items():
        for sig in signatures:
            if sig.lower() in all_strings:
                result["detected"].append(obf_name)
                break

    # Also check PE section names
    sections_text = ""
    pe_offset = struct.unpack_from("<I", data, 0x3C)[0] if len(data) > 0x40 else 0
    if pe_offset:
        num_sections = struct.unpack_from("<H", data, pe_offset + 6)[0]
        oh_size = struct.unpack_from("<H", data, pe_offset + 20)[0]
        sh_start = pe_offset + 24 + oh_size
        for i in range(min(num_sections, 96)):
            sh_off = sh_start + i * 40
            if sh_off + 8 > len(data):
                break
            s_name = data[sh_off:sh_off + 8].rstrip(b"\x00").decode("ascii", errors="replace")
            sections_text += s_name.lower() + " "

    # Section-name-based obfuscator fingerprints
    section_obfuscators = {
        ".agile": "Agile.NET",
        ".phx": "Phoenix Protector",
        ".neolit": ".NET Reactor",
        ".netshrink": ".NET Reactor",
        ".mpress1": "MPRESS",
        ".mpress2": "MPRESS",
        ".mprs1": "MPRESS",
        ".mprs2": "MPRESS",
        "yano": "Yano",
    }
    for sec_name, obf_name in section_obfuscators.items():
        if sec_name in sections_text and obf_name not in result["detected"]:
            result["detected"].append(obf_name)

    if result["detected"]:
        result["confidence"] = "medium"
        # Check if any detected obfuscator is cleanable by de4dot
        result["de4dot_cleanable"] = [d for d in result["detected"] if d in DE4DOT_SUPPORTED]

    return result


# ── Reporting ──────────────────────────────────────────────────────────────

def write_report(result: dict, path: str):
    lines = [
        f"# .NET Assembly Analysis: {result.get('file', 'unknown')}",
        "",
        f"**Scan time:** {result.get('scan_time', '')}  ",
        f"**File size:** {result.get('size', 0):,} bytes  ",
        f"**Architecture:** {result.get('architecture', '?')}  ",
        f"**Entropy:** {result.get('entropy', '?')}  ",
        "",
    ]

    if "error" in result:
        lines.append(f"**Error:** {result['error']}")
        lines.append("")
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        return

    # CLR Header
    clr = result.get("clr_header", {})
    if clr:
        lines += [
            "## CLR Header",
            "",
            "| Field | Value |",
            "|---|---|",
            f"| Runtime version | {clr.get('runtime_version', '?')} |",
            f"| Flags | {', '.join(clr.get('flags_detail', []))} |",
            f"| Entry point | {clr.get('entry_point_token', clr.get('entry_point_rva', '?'))} |",
            f"| Metadata | RVA=0x{clr.get('meta_rva', 0):08X}, size={clr.get('meta_size', 0):,} |",
            f"| Strong Name | {'Yes' if clr.get('strong_name_size', 0) > 0 else 'No'} |",
            "",
        ]

    # Metadata streams
    meta = result.get("metadata", {})
    if meta and "error" not in meta:
        lines += [
            "## Metadata",
            "",
            f"**Version:** {meta.get('version', '?')}  ",
            f"**Version string:** {meta.get('version_string', '?')}  ",
            "",
        ]

        streams = meta.get("streams", [])
        for s in streams:
            name = s["name"]
            lines.append(f"### {name} stream")
            lines.append(f"- Size: {s['size']:,} bytes")
            if "tables" in s:
                tables = s["tables"]
                lines.append(f"- Present tables: {tables.get('total_tables_present', 0)}")
                if tables.get('type_count'):
                    lines.append(f"- TypeDef: {tables['type_count']:,}")
                if tables.get('method_count'):
                    lines.append(f"- MethodDef: {tables['method_count']:,}")
                if tables.get('field_count'):
                    lines.append(f"- Field: {tables['field_count']:,}")
                if tables.get('assembly_ref_count'):
                    lines.append(f"- AssemblyRef: {tables['assembly_ref_count']:,}")
                lines.append("")
                # Table detail
                for t in tables.get("tables", []):
                    if t["rows"] > 0:
                        lines.append(f"  - {t['name']}: {t['rows']:,} rows")
                lines.append("")
            elif name == "#Strings":
                lines.append(f"- Null terminators: ~{s.get('string_count', 0):,}")
                lines.append("")
            elif name == "#GUID":
                lines.append(f"- GUIDs: {s.get('guid_count', 0)}")
                lines.append("")

    # Obfuscation
    obf = result.get("obfuscation", {})
    lines += [
        "## Obfuscation Detection",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| Detected | {', '.join(obf.get('detected', [])) or 'None'} |",
        f"| Confidence | {obf.get('confidence', 'none')} |",
        f"| De4dot cleanable | {', '.join(obf.get('de4dot_cleanable', [])) or 'None'} |",
        "",
    ]

    # Security
    sec = result.get("security", {})
    lines += [
        "## Security",
        "",
        "| Feature | Status |",
        "|---|---|",
        f"| Strong Name | {'Yes' if sec.get('strong_name') else '**No**'} |",
        f"| IL Only | {'Yes' if sec.get('il_only') else '**No** (mixed-mode)'} |",
        f"| SuppressIldasm | {'**Yes**' if sec.get('suppress_ildasm') else 'No'} |",
        f"| Debug info | {'Yes' if sec.get('debug_info') else 'No'} |",
        "",
    ]

    if "pe_timestamp" in result:
        lines.append(f"**PE timestamp:** {result['pe_timestamp']}  ")
        lines.append("")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ── CLI ────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="dotnet_analyze — .NET assembly analysis")
    parser.add_argument("dotnet_file", help="Path to .NET PE file")
    parser.add_argument("--out", "-o", default="./dotnet_analyze", help="Output directory")
    parser.add_argument("--json", action="store_true", help="Output JSON to stdout")
    args = parser.parse_args()

    if not os.path.exists(args.dotnet_file):
        print(f"Error: file not found: {args.dotnet_file}", file=sys.stderr)
        sys.exit(1)

    try:
        data = read_file(args.dotnet_file, allow_truncation=False)
        result = parse_dotnet(data, args.dotnet_file)
    except (OSError, struct.error, ValueError, FileTooLargeError) as e:
        print(f"Error parsing {args.dotnet_file}: {e}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)
    json_path = os.path.join(args.out, "dotnet_analyze.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    md_path = os.path.join(args.out, "dotnet_analyze.md")
    write_report(result, md_path)

    print(f"[+] Output: {json_path}, {md_path}")

    clr = result.get("clr_header", {})
    meta = result.get("metadata", {})
    tables_info = ""
    for s in meta.get("streams", []):
        if "tables" in s:
            tc = s["tables"].get("type_count", 0)
            mc = s["tables"].get("method_count", 0)
            if tc or mc:
                tables_info = f", {tc} types, {mc} methods"
    print(f"[+] Runtime: {clr.get('runtime_version', '?')}, "
          f"ILOnly={bool(clr.get('flags', 0) & 0x1)}{tables_info}")

    obf = result.get("obfuscation", {})
    if obf.get("detected"):
        print(f"[!] Obfuscator(s) detected: {', '.join(obf['detected'])}")

    sec = result.get("security", {})
    print(f"[+] StrongName={sec.get('strong_name')} "
          f"SuppressIldasm={sec.get('suppress_ildasm')}")

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
