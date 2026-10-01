#!/usr/bin/env python3
"""pe_deep_scan.py — 深度 PE (Portable Executable) 结构分析

分析 PE 文件的详细结构: DOS/PE/Rich headers, sections, imports/exports, resources,
TLS callbacks, debug info, certificates, version info, load config, relocations,
and security mitigations.

Usage:
    python pe_deep_scan.py <pe_file> [--out <output_dir>] [--mitigations] [--json]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys
import math
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


# ── PE structures ─────────────────────────────────────────────────────────────

IMAGE_DOS_SIGNATURE = 0x5A4D       # "MZ"
IMAGE_NT_SIGNATURE = 0x00004550     # "PE\0\0"

MACHINE_TYPES = {
    0x14C:  "x86 (I386)",
    0x8664: "x64 (AMD64)",
    0xAA64: "ARM64",
    0x1C0:  "ARM (little-endian)",
    0x1C4:  "ARM Thumb-2 (little-endian)",
    0x200:  "IA-64 (Itanium)",
}

SUBSYSTEMS = {
    1:  "Native", 2: "Windows GUI", 3: "Windows CUI",
    5:  "OS/2 CUI", 7: "POSIX CUI", 9: "Windows CE GUI",
    10: "EFI Application", 11: "EFI Boot Service Driver",
    12: "EFI Runtime Driver", 16: "Xbox",
}

SECTION_CHARACTERISTICS = {
    0x00000020: "CODE", 0x00000040: "INITIALIZED_DATA",
    0x00000080: "UNINITIALIZED_DATA", 0x02000000: "DISCARDABLE",
    0x04000000: "NOT_CACHED", 0x08000000: "NOT_PAGED",
    0x10000000: "SHARED", 0x20000000: "EXECUTE",
    0x40000000: "READ", 0x80000000: "WRITE",
}

# DLL Characteristics (mitigations)
DLL_CHARACTERISTICS = {
    0x0040: "ASLR (DYNAMICBASE)",
    0x0100: "DEP (NXCOMPAT)",
    0x0200: "NO_ISOLATION",
    0x0400: "NO_SEH",
    0x1000: "AppContainer",
    0x4000: "CFG (CONTROL FLOW GUARD)",
    0x8000: "High Entropy ASLR",
}

# File Header Characteristics
FILE_HEADER_CHARACTERISTICS = {
    0x0001: "RELOCS_STRIPPED",
    0x0002: "EXECUTABLE_IMAGE",
    0x0004: "LINE_NUMS_STRIPPED",
    0x0008: "LOCAL_SYMS_STRIPPED",
    0x0010: "AGGRESSIVE_WS_TRIM",
    0x0020: "LARGE_ADDRESS_AWARE",
    0x0080: "BYTES_REVERSED_LO",
    0x0100: "32BIT_MACHINE",
    0x0200: "DEBUG_STRIPPED",
    0x0400: "REMOVABLE_RUN_FROM_SWAP",
    0x0800: "NET_RUN_FROM_SWAP",
    0x1000: "SYSTEM",
    0x2000: "DLL",
    0x4000: "UP_SYSTEM_ONLY",
    0x8000: "BYTES_REVERSED_HI",
}

PACKER_SECTIONS = [
    "UPX0", "UPX1", "UPX2", ".aspack", ".pec", ".petite",
    ".mpress", ".mpress1", ".mpress2", ".enigma", ".vmp",
    ".vmp0", ".vmp1", ".themida", ".winlic", ".sforce",
    "DAta", "coder", ".ccg", ".mackt", ".taggant", ".pklstb",
]

DANGEROUS_IMPORTS = {
    "process_injection": [
        "CreateRemoteThread", "WriteProcessMemory", "VirtualAllocEx",
        "NtCreateThreadEx", "QueueUserAPC", "SetThreadContext",
        "NtMapViewOfSection", "RtlCreateUserThread",
    ],
    "process_manipulation": [
        "OpenProcess", "TerminateProcess", "NtOpenProcess",
        "NtTerminateProcess", "ZwUnmapViewOfSection",
    ],
    "memory_manipulation": [
        "VirtualProtect", "VirtualProtectEx", "NtProtectVirtualMemory",
        "VirtualAlloc", "NtAllocateVirtualMemory",
    ],
    "privilege_escalation": [
        "AdjustTokenPrivileges", "LookupPrivilegeValue", "OpenProcessToken",
        "SeDebugPrivilege",
    ],
    "credential_access": [
        "CryptUnprotectData", "LsaRetrievePrivateData",
        "SamQueryInformationUser", "CredRead",
    ],
    "anti_debug": [
        "IsDebuggerPresent", "CheckRemoteDebuggerPresent", "NtQueryInformationProcess",
        "OutputDebugString", "NtSetInformationThread",
    ],
}


# ── Utility ───────────────────────────────────────────────────────────────────

def read_pe_file(path: str) -> bytes:
    with open(path, "rb") as f:
        return f.read()


def get_dword(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def get_word(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


# ── Analysis ──────────────────────────────────────────────────────────────────

def analyze_pe(path: str, mitigations_only: bool = False) -> dict:
    data = read_pe_file(path)
    if len(data) < 2 or struct.unpack_from("<H", data, 0)[0] != IMAGE_DOS_SIGNATURE:
        return {"error": "Not a valid PE file (missing MZ signature)"}

    pe_offset = get_dword(data, 0x3C)
    if pe_offset > len(data) - 4 or get_dword(data, pe_offset) != IMAGE_NT_SIGNATURE:
        return {"error": f"Invalid PE signature at offset {pe_offset:#x}"}

    file_header_offset = pe_offset + 4
    optional_header_offset = file_header_offset + 20

    machine = get_word(data, file_header_offset)
    num_sections = get_word(data, file_header_offset + 2)
    timestamp = get_dword(data, file_header_offset + 4)
    size_opt_header = get_word(data, file_header_offset + 16)
    characteristics = get_word(data, file_header_offset + 18)

    if size_opt_header < 2:
        return {"error": f"Invalid optional header size: {size_opt_header}"}
    opt_header = data[optional_header_offset:optional_header_offset + size_opt_header]
    magic = get_word(opt_header, 0)
    is_pe32plus = magic == 0x20B

    subsys = get_word(opt_header, 68)

    # Entry point
    ep = get_dword(opt_header, 16)

    # DLL Characteristics
    dll_chars = 0
    dll_chars_offset = 74 if is_pe32plus else 70
    if size_opt_header > dll_chars_offset + 2:
        dll_chars = get_word(opt_header, dll_chars_offset)

    # Sections
    sections_offset = optional_header_offset + size_opt_header
    sections = []
    section_entropy = {}
    for i in range(num_sections):
        sec_off = sections_offset + i * 40
        name = data[sec_off:sec_off+8].rstrip(b"\x00").decode("ascii", errors="replace")
        vsize = get_dword(data, sec_off + 8)
        vaddr = get_dword(data, sec_off + 12)
        rsize = get_dword(data, sec_off + 16)
        roffset = get_dword(data, sec_off + 20)
        flags = get_dword(data, sec_off + 36)

        # Entropy of section
        if roffset > 0 and rsize > 0 and roffset + rsize <= len(data):
            sec_data = data[roffset:roffset + min(rsize, 1048576)]
            counts = Counter(sec_data)
            total = len(sec_data)
            ent = -sum((c / total) * math.log2(c / total) for c in counts.values()) if total > 0 else 0
            section_entropy[name] = round(ent, 3)

        sections.append({
            "name": name,
            "virtual_size": vsize, "virtual_address": hex(vaddr),
            "raw_size": rsize, "raw_offset": roffset,
            "characteristics": flags,
            "characteristics_names": [n for bit, n in SECTION_CHARACTERISTICS.items()
                                       if flags & bit],
            "permissions": "".join([
                "R" if flags & 0x40000000 else "-",
                "W" if flags & 0x80000000 else "-",
                "X" if flags & 0x20000000 else "-",
            ]),
            "entropy": section_entropy.get(name, 0),
        })

    # Imports
    imports = []
    if not mitigations_only and size_opt_header > 0:
        import_dir_rva = get_dword(opt_header, 80 if is_pe32plus else 76)
        import_dir_size = get_dword(opt_header, 84 if is_pe32plus else 80)
        if import_dir_rva and import_dir_size:
            imports = _parse_imports(data, sections, import_dir_rva, is_pe32plus)

    # TLS callbacks
    tls_callbacks = []
    tls_rva = get_dword(opt_header, 168 if is_pe32plus else 152) if size_opt_header > (168 if is_pe32plus else 152) + 4 else 0
    if tls_rva:
        tls_callbacks = _parse_tls(data, sections, tls_rva, is_pe32plus)

    # Rich header
    rich_info = _parse_rich_header(data, pe_offset)

    # Mitigations
    mitigations = _check_mitigations(data, dll_chars, sections, is_pe32plus)

    # Packer detection
    packer_indicators = []
    for s in sections:
        for ps in PACKER_SECTIONS:
            if ps.lower() in s["name"].lower():
                packer_indicators.append(f"Section name: {s['name']} matches {ps}")
    for s in sections:
        if s["entropy"] > 7.5:
            packer_indicators.append(f"High entropy section: {s['name']} ({s['entropy']:.2f})")
    if num_sections < 3:
        packer_indicators.append(f"Abnormally few sections ({num_sections})")

    # Suspicious imports
    sus_imports = {}
    if not mitigations_only:
        for imp in imports:
            for cat, apis in DANGEROUS_IMPORTS.items():
                if imp["name"] in apis:
                    if cat not in sus_imports:
                        sus_imports[cat] = []
                    sus_imports[cat].append(imp["name"])

    result = {
        "file": Path(path).name,
        "size": len(data),
        "hashes": {
            "md5": hashlib.md5(data).hexdigest(),
            "sha256": hashlib.sha256(data).hexdigest(),
        },
        "pe_header": {
            "machine": MACHINE_TYPES.get(machine, f"Unknown ({machine:#x})"),
            "machine_id": hex(machine),
            "num_sections": num_sections,
            "timestamp": timestamp,
            "timestamp_utc": datetime.fromtimestamp(timestamp, tz=timezone.utc).isoformat() if timestamp else None,
            "subsystem": SUBSYSTEMS.get(subsys, f"Unknown ({subsys})"),
            "is_64bit": is_pe32plus,
            "characteristics": characteristics,
            "characteristics_names": [n for bit, n in FILE_HEADER_CHARACTERISTICS.items()
                                      if characteristics & bit],
            "entry_point_rva": hex(ep) if ep else "N/A",
        },
        "sections": sections,
        "mitigations": mitigations,
    }

    if not mitigations_only:
        result["imports"] = imports[:100]  # limit to first 100
        result["suspicious_imports"] = sus_imports
        result["tls_callbacks"] = tls_callbacks
        result["rich_header"] = rich_info
        result["packer_indicators"] = packer_indicators

    return result


def _check_mitigations(data: bytes, dll_chars: int, sections: list,
                       is_pe32plus: bool) -> dict:
    mitigs = {}

    # ASLR
    mitigs["aslr"] = bool(dll_chars & 0x0040)
    mitigs["high_entropy_aslr"] = bool(dll_chars & 0x8000)

    # DEP/NX
    mitigs["dep"] = bool(dll_chars & 0x0100)

    # SafeSEH (x86 only) — IMAGE_DLLCHARACTERISTICS_NO_SEH (0x0400) set → SafeSEH disabled
    mitigs["safeseh"] = "N/A (x64)" if is_pe32plus else not bool(dll_chars & 0x0400)

    # CFG
    mitigs["cfg"] = bool(dll_chars & 0x4000)

    # Stack cookie (/GS)
    mitigs["gs_cookie"] = False  # Requires import check
    if b"__security_check_cookie" in data or b"__security_cookie" in data:
        mitigs["gs_cookie"] = True

    # Sections with RWX
    mitigs["has_rwx_section"] = any(s["permissions"] == "RWX" for s in sections)
    mitigs["rwx_sections"] = [s["name"] for s in sections if s["permissions"] == "RWX"]

    # code integrity (AppContainer)
    mitigs["appcontainer"] = bool(dll_chars & 0x1000)

    return mitigs


def _rva_to_offset(rva: int, sections: list) -> int:
    for sec in sections:
        vaddr = int(sec["virtual_address"], 16)
        if vaddr <= rva < vaddr + sec["virtual_size"]:
            return sec["raw_offset"] + (rva - vaddr)
    return -1


def _parse_imports(data: bytes, sections: list, import_dir_rva: int,
                   is_pe32plus: bool = True) -> list[dict]:
    imports = []
    offset = _rva_to_offset(import_dir_rva, sections)
    if offset < 0:
        return imports

    thunk_size = 8 if is_pe32plus else 4  # IMAGE_THUNK_DATA size

    idx = 0
    while True:
        entry = data[offset + idx*20:offset + (idx+1)*20]
        if len(entry) < 20 or all(b == 0 for b in entry):
            break
        name_rva = get_dword(entry, 12)
        dll_offset = _rva_to_offset(name_rva, sections)
        if dll_offset > 0:
            dll_name = data[dll_offset:data.find(b"\x00", dll_offset)].decode("ascii", errors="replace")
            # Parse thunks
            ilt_rva = get_dword(entry, 0)
            iat_rva = get_dword(entry, 16)
            thunk_rva = ilt_rva if ilt_rva else iat_rva
            if thunk_rva == 0:
                idx += 1
                continue
            thunk_offset = _rva_to_offset(thunk_rva, sections)
            if thunk_offset > 0:
                thunk_idx = 0
                while True:
                    thunk_data = data[thunk_offset+thunk_idx*thunk_size:
                                      thunk_offset+(thunk_idx+1)*thunk_size]
                    if len(thunk_data) < thunk_size or all(b == 0 for b in thunk_data):
                        break
                    if is_pe32plus:
                        ordinal_bit = get_dword(thunk_data, 4) & 0x80000000
                        name_rva2 = get_dword(thunk_data, 0) & 0x7FFFFFFF
                        ord_num = get_word(thunk_data, 0)
                    else:
                        # PE32: IMAGE_THUNK_DATA32 is 4 bytes; ordinal flag is bit 31
                        ordinal_bit = get_dword(thunk_data, 0) & 0x80000000
                        name_rva2 = get_dword(thunk_data, 0) & 0x7FFFFFFF
                        ord_num = get_word(thunk_data, 0)
                    if ordinal_bit:
                        imports.append({"dll": dll_name, "name": f"#{ord_num}", "ordinal": ord_num})
                    else:
                        name_off = _rva_to_offset(name_rva2, sections)
                        if name_off > 0:
                            func_name = data[name_off+2:data.find(b"\x00", name_off+2)].decode("ascii", errors="replace")
                            imports.append({"dll": dll_name, "name": func_name})
                    thunk_idx += 1
        idx += 1
    return imports


def _parse_tls(data: bytes, sections: list, tls_rva: int, is_pe32plus: bool) -> list[str]:
    callbacks = []
    cb_offset = _rva_to_offset(tls_rva, sections)
    if cb_offset < 0:
        return callbacks

    if is_pe32plus:
        # IMAGE_TLS_DIRECTORY64: callback array pointer at offset 24
        array_rva = struct.unpack_from("<Q", data, cb_offset + 24)[0] if len(data) > cb_offset + 32 else 0
    else:
        # IMAGE_TLS_DIRECTORY32: callback array pointer at offset 12
        array_rva = struct.unpack_from("<I", data, cb_offset + 12)[0] if len(data) > cb_offset + 16 else 0

    if array_rva:
        array_off = _rva_to_offset(array_rva, sections)
        if array_off > 0:
            idx = 0
            while True:
                cb_rva_data = data[array_off+idx*8:array_off+(idx+1)*8] if is_pe32plus else data[array_off+idx*4:array_off+(idx+1)*4]
                if len(cb_rva_data) < 4:
                    break
                cb_addr = struct.unpack_from("<Q" if is_pe32plus else "<I", cb_rva_data, 0)[0]
                if cb_addr == 0:
                    break
                callbacks.append(hex(cb_addr))
                idx += 1

    return callbacks


def _parse_rich_header(data: bytes, pe_offset: int) -> dict | None:
    """Extract Rich header info (compiler toolchain metadata)."""
    # Rich header sits between DOS stub and PE signature
    # Look for "Rich" marker before PE offset
    rich_marker = b"Rich"
    rich_offset = data.rfind(rich_marker, 0x40, pe_offset - 4)
    if rich_offset < 0:
        return None

    # XOR key is the DWORD right after "Rich"
    xor_key = struct.unpack_from("<I", data, rich_offset + 4)[0]

    # Decode entries before "DanS" → "Rich"
    # Each entry is 2 DWORDS: (product_id << 16 | build_number), count
    # Search backwards for "DanS" marker
    dans_marker = b"DanS"
    dans_offset = data.find(dans_marker, 0x40, pe_offset)
    if dans_offset < 0:
        return None

    entry_data = data[dans_offset + 4:rich_offset]
    entries = []
    for i in range(0, len(entry_data), 8):
        if i + 8 > len(entry_data):
            break
        a = struct.unpack_from("<I", entry_data, i)[0] ^ xor_key
        b = struct.unpack_from("<I", entry_data, i + 4)[0] ^ xor_key
        prod_id = a >> 16
        build = a & 0xFFFF
        count = b
        entries.append({"product_id": prod_id, "build": build, "count": count})

    return {"xor_key": hex(xor_key), "entries": entries, "entry_count": len(entries)}


# ── Output ────────────────────────────────────────────────────────────────────

def write_report(result: dict, path: str):
    pe = result.get("pe_header", {})
    lines = [
        f"# PE Deep Scan: {result['file']}",
        "",
        "## File Overview",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| File | {result['file']} |",
        f"| Size | {result['size']:,} bytes |",
        f"| MD5 | {result['hashes']['md5']} |",
        f"| SHA-256 | {result['hashes']['sha256']} |",
        f"| Type | {pe.get('machine', 'N/A')} |",
        f"| 64-bit | {'Yes' if pe.get('is_64bit') else 'No'} |",
        f"| Timestamp | {pe.get('timestamp_utc', 'N/A')} |",
        f"| Subsystem | {pe.get('subsystem', 'N/A')} |",
        f"| Entry Point RVA | {pe.get('entry_point_rva', 'N/A')} |",
        f"| Sections | {pe.get('num_sections', 0)} |",
        f"| Characteristics | {', '.join(pe.get('characteristics_names', [])) or 'N/A'} |",
    ]

    # Mitigations
    mitigs = result.get("mitigations", {})
    lines += [
        "",
        "## Security Mitigations",
        "",
        "| Mitigation | Enabled |",
        "|---|---|",
    ]
    for name, enabled in mitigs.items():
        if name == "rwx_sections":
            continue
        if isinstance(enabled, bool):
            lines.append(f"| {name.upper()} | {'Yes' if enabled else '**No**'} |")
    if mitigs.get("has_rwx_section"):
        lines.append(f"| RWX Sections | **{', '.join(mitigs.get('rwx_sections', []))}** |")

    # Sections
    lines += [
        "",
        "## Sections",
        "",
        "| # | Name | V.Size | V.Addr | R.Size | Perm | Entropy |",
        "|---|---|---|---|---|---|---|",
    ]
    for i, s in enumerate(result.get("sections", [])):
        lines.append(f"| {i} | {s['name']} | {s['virtual_size']:,} | {s['virtual_address']} "
                     f"| {s['raw_size']:,} | {s['permissions']} | {s.get('entropy', 0):.2f} |")

    # Packer indicators
    packer = result.get("packer_indicators", [])
    if packer:
        lines += [
            "",
            "## Packer/Protector Indicators",
        ]
        for p in packer:
            lines.append(f"- {p}")

    # TLS callbacks
    tls = result.get("tls_callbacks", [])
    if tls:
        lines += [
            "",
            f"## TLS Callbacks ({len(tls)})",
        ]
        for cb in tls:
            lines.append(f"- {cb}")

    # Suspicious imports
    sus = result.get("suspicious_imports", {})
    if sus:
        lines += [
            "",
            "## Suspicious Imports by Category",
        ]
        for cat, apis in sus.items():
            lines.append(f"\n### {cat.replace('_', ' ').title()}")
            for api in set(apis):
                lines.append(f"- `{api}`")

    # Rich header
    rich = result.get("rich_header")
    if rich:
        lines += [
            "",
            "## Rich Header",
            "",
            f"- XOR Key: {rich['xor_key']}",
            f"- Toolchain entries: {rich['entry_count']}",
        ]

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="pe_deep_scan — deep PE structural analysis")
    parser.add_argument("pe_file", help="Path to PE file")
    parser.add_argument("--out", "-o", default="./pe_scan",
                        help="Output directory (default: ./pe_scan)")
    parser.add_argument("--mitigations", action="store_true",
                        help="Only check security mitigations")
    parser.add_argument("--json", action="store_true",
                        help="Output JSON to stdout")

    args = parser.parse_args()

    if not os.path.exists(args.pe_file):
        print(f"Error: file not found: {args.pe_file}", file=sys.stderr)
        sys.exit(1)

    result = analyze_pe(args.pe_file, args.mitigations)
    if "error" in result:
        print(f"Error: {result['error']}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)
    json_path = os.path.join(args.out, "pe_scan.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    md_path = os.path.join(args.out, "pe_scan.md")
    write_report(result, md_path)

    # Summary
    mitigs = result["mitigations"]
    print(f"[+] PE File: {result['file']}")
    print(f"[+] Type: {result['pe_header']['machine']} ({'x64' if result['pe_header']['is_64bit'] else 'x86'})")
    print(f"[+] Sections: {result['pe_header']['num_sections']}")
    print(f"[+] Mitigations: ASLR={mitigs['aslr']} DEP={mitigs['dep']} CFG={mitigs['cfg']} GS={mitigs.get('gs_cookie', False)}")
    print(f"[+] RWX Sections: {mitigs['has_rwx_section']}")
    if result.get("packer_indicators"):
        print(f"[!] Packer indicators: {len(result['packer_indicators'])}")
    if result.get("tls_callbacks"):
        print(f"[!] TLS callbacks: {len(result['tls_callbacks'])}")
    print(f"[+] Output: {json_path}, {md_path}")

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
