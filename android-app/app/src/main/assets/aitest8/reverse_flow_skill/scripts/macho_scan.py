#!/usr/bin/env python3
"""macho_scan.py — Mach-O binary deep structural analysis

Covers: fat/universal binary detection, Mach-O header, load commands
(LC_SEGMENT, LC_LOAD_DYLIB, LC_MAIN, LC_CODE_SIGNATURE, LC_UUID,
LC_ENCRYPTION_INFO, LC_VERSION_MIN, LC_DYLD_INFO), security analysis.

Usage:
    python macho_scan.py <macho_file> [--out <dir>] [--json]
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

# ── Mach-O constants ───────────────────────────────────────────────────────

MH_MAGIC = 0xFEEDFACE
MH_CIGAM = 0xCEFAEDFE
MH_MAGIC_64 = 0xFEEDFACF
MH_CIGAM_64 = 0xCFFAEDFE
FAT_MAGIC = 0xCAFEBABE
FAT_CIGAM = 0xBEBAFECA

MH_OBJECT = 0x1
MH_EXECUTE = 0x2
MH_DYLIB = 0x6
MH_DYLINKER = 0x7
MH_BUNDLE = 0x8
FILETYPE_NAMES = {
    1: "OBJECT", 2: "EXECUTE", 3: "FVMLIB", 4: "CORE",
    5: "PRELOAD", 6: "DYLIB", 7: "DYLINKER", 8: "BUNDLE",
    9: "DYLIB_STUB", 10: "DSYM", 11: "KEXT_BUNDLE",
}

CPU_TYPE_NAMES = {
    0x00000007: "x86", 0x01000007: "x86-64",
    0x0000000C: "ARM", 0x0100000C: "ARM64",
    0x0100000D: "ARM64_32",
    0x00000012: "PPC", 0x01000012: "PPC64",
}

LC_REQ_DYLD = 0x80000000
LC_SEGMENT = 0x1
LC_SYMTAB = 0x2
LC_DYSYMTAB = 0xB
LC_LOAD_DYLIB = 0xC
LC_ID_DYLIB = 0xD
LC_LOAD_WEAK_DYLIB = 0x80000018
LC_SEGMENT_64 = 0x19
LC_UUID = 0x1B
LC_RPATH = 0x8000001C
LC_CODE_SIGNATURE = 0x1D
LC_SEGMENT_SPLIT_INFO = 0x1E
LC_REEXPORT_DYLIB = 0x8000001F
LC_ENCRYPTION_INFO = 0x21
LC_DYLD_INFO = 0x22
LC_DYLD_INFO_ONLY = 0x80000022
LC_VERSION_MIN_MACOSX = 0x24
LC_VERSION_MIN_IPHONEOS = 0x25
LC_FUNCTION_STARTS = 0x26
LC_DYLD_ENVIRONMENT = 0x27
LC_MAIN = 0x80000028
LC_DATA_IN_CODE = 0x29
LC_SOURCE_VERSION = 0x2A
LC_DYLIB_CODE_SIGN_DRS = 0x2B
LC_ENCRYPTION_INFO_64 = 0x2C
LC_BUILD_VERSION = 0x32
LC_NOTE = 0x31
LCMD_NAMES = {
    LC_SEGMENT: "SEGMENT", LC_SYMTAB: "SYMTAB", LC_DYSYMTAB: "DYSYMTAB",
    LC_LOAD_DYLIB: "LOAD_DYLIB", LC_ID_DYLIB: "ID_DYLIB",
    LC_SEGMENT_64: "SEGMENT_64", LC_UUID: "UUID",
    LC_CODE_SIGNATURE: "CODE_SIGNATURE", LC_SEGMENT_SPLIT_INFO: "SEGMENT_SPLIT_INFO",
    LC_REEXPORT_DYLIB: "REEXPORT_DYLIB", LC_ENCRYPTION_INFO: "ENCRYPTION_INFO",
    LC_DYLD_INFO: "DYLD_INFO", LC_DYLD_INFO_ONLY: "DYLD_INFO_ONLY",
    LC_VERSION_MIN_MACOSX: "VERSION_MIN_MACOSX",
    LC_VERSION_MIN_IPHONEOS: "VERSION_MIN_IPHONEOS",
    LC_FUNCTION_STARTS: "FUNCTION_STARTS", LC_MAIN: "MAIN",
    LC_DATA_IN_CODE: "DATA_IN_CODE", LC_SOURCE_VERSION: "SOURCE_VERSION",
    LC_DYLIB_CODE_SIGN_DRS: "DYLIB_CODE_SIGN_DRS",
    LC_ENCRYPTION_INFO_64: "ENCRYPTION_INFO_64",
    LC_BUILD_VERSION: "BUILD_VERSION", LC_NOTE: "NOTE",
    LC_RPATH: "RPATH", LC_LOAD_WEAK_DYLIB: "LOAD_WEAK_DYLIB",
    LC_DYLD_ENVIRONMENT: "DYLD_ENVIRONMENT",
}

SG_PROT_READ = 0x1
SG_PROT_WRITE = 0x2
SG_PROT_EXEC = 0x4

# ── Utility ────────────────────────────────────────────────────────────────

def _swap32(v: int) -> int:
    return ((v & 0xFF) << 24) | ((v & 0xFF00) << 8) | ((v & 0xFF0000) >> 8) | ((v >> 24) & 0xFF)


def _get_uint32(data: bytes, offset: int, swap: bool) -> int:
    v = struct.unpack_from("<I", data, offset)[0]
    return _swap32(v) if swap else v


def _get_uint64(data: bytes, offset: int, swap: bool) -> int:
    lo = _get_uint32(data, offset, swap)
    hi = _get_uint32(data, offset + 4, swap)
    return (hi << 32) | lo if not swap else (lo << 32) | hi


# ── Parsing ────────────────────────────────────────────────────────────────

def parse_macho(data: bytes, path: str) -> dict:
    result: dict = {
        "file": Path(path).name,
        "size": len(data),
        "scan_time": datetime.now(timezone.utc).isoformat(),
    }

    if len(data) < 4:
        result["error"] = "File too small"
        return result

    magic = struct.unpack_from("<I", data, 0)[0]

    # Fat/Universal binary (0xCAFEBABE also matches Java .class; disambiguate by extension)
    if magic in (FAT_MAGIC, FAT_CIGAM):
        if Path(path).suffix.lower() == ".class":
            result["error"] = "Java class file, not a Mach-O binary"
            return result
        nfat_arch = _get_uint32(data, 4, magic == FAT_CIGAM)
        if nfat_arch == 0:
            result["error"] = "Not a valid Mach-O fat binary (nfat_arch=0)"
            return result
        return _parse_fat(data, path, result, magic)

    # Thin Mach-O
    if magic in (MH_MAGIC, MH_CIGAM):
        return _parse_thin(data, False, magic in (MH_CIGAM,), result)
    elif magic in (MH_MAGIC_64, MH_CIGAM_64):
        return _parse_thin(data, True, magic in (MH_CIGAM_64,), result)

    result["error"] = f"Unknown magic: 0x{magic:08X}"
    return result


def _parse_fat(data: bytes, path: str, result: dict, magic: int) -> dict:
    swap = magic == FAT_CIGAM
    nfat_arch = _get_uint32(data, 4, swap)
    result["format"] = "FAT/Universal"
    result["num_architectures"] = nfat_arch
    slices = []

    if nfat_arch > 16:
        print(f"Warning: fat binary has {nfat_arch} architectures, only first 16 parsed",
              file=sys.stderr)
    for i in range(min(nfat_arch, 16)):
        off = 8 + i * 20
        if off + 20 > len(data):
            break
        cpu_type = _get_uint32(data, off, swap)
        cpu_subtype = _get_uint32(data, off + 4, swap)
        arch_offset = _get_uint32(data, off + 8, swap)
        arch_size = _get_uint32(data, off + 12, swap)
        arch_align = _get_uint32(data, off + 16, swap)

        slice_info = {
            "cpu_type": CPU_TYPE_NAMES.get(cpu_type, f"0x{cpu_type:08X}"),
            "cpu_subtype": cpu_subtype,
            "offset": arch_offset,
            "size": arch_size,
            "align": 2 ** arch_align,
        }
        # Parse thin slice header
        if arch_offset + 28 <= len(data):
            try:
                thin = _parse_thin_header(data[arch_offset:], arch_offset)
                slice_info["header"] = thin
            except (struct.error, IndexError):
                pass
        slices.append(slice_info)

    result["slices"] = slices
    return result


def _parse_thin(data: bytes, is_64: bool, swap: bool, result: dict) -> dict:
    result["format"] = "Mach-O 64-bit" if is_64 else "Mach-O 32-bit"
    header = _parse_thin_header(data, 0, swap)
    result["header"] = header
    load_cmds = _parse_load_commands(data, header, is_64, swap)
    result["load_commands"] = load_cmds
    result["dylibs"] = [c for c in load_cmds if c["cmd"] in ("LOAD_DYLIB", "LOAD_WEAK_DYLIB", "REEXPORT_DYLIB")]
    result["security"] = _check_security(load_cmds, header["flags"])
    return result


def _parse_thin_header(data: bytes, base_offset: int, swap: bool = False) -> dict:
    cputype = _get_uint32(data, 4, swap)
    cpusubtype = _get_uint32(data, 8, swap)
    filetype = _get_uint32(data, 12, swap)
    ncmds = _get_uint32(data, 16, swap)
    sizeofcmds = _get_uint32(data, 20, swap)
    flags = _get_uint32(data, 24, swap)

    return {
        "cputype": CPU_TYPE_NAMES.get(cputype, f"0x{cputype:08X}"),
        "cpusubtype": cpusubtype,
        "filetype": FILETYPE_NAMES.get(filetype, f"0x{filetype:X}"),
        "ncmds": ncmds,
        "sizeofcmds": sizeofcmds,
        "flags": flags,
        "flags_detail": _decode_flags(flags),
        "base_offset": base_offset,
    }


def _decode_flags(flags: int) -> list[str]:
    flag_bits = {
        0x1: "NOUNDEFS", 0x2: "INCRLINK", 0x4: "DYLDLINK",
        0x8: "BINDATLOAD", 0x10: "PREBOUND", 0x20: "SPLIT_SEGS",
        0x40: "LAZY_INIT", 0x80: "TWOLEVEL", 0x100: "FORCE_FLAT",
        0x200: "NOMULTIDEFS", 0x400: "NOFIXPREBINDING", 0x800: "PREBINDABLE",
        0x1000: "ALLMODSBOUND", 0x2000: "SUBSECTIONS_VIA_SYMBOLS",
        0x4000: "CANONICAL", 0x8000: "WEAK_DEFINES",
        0x10000: "BINDS_TO_WEAK", 0x20000: "ALLOW_STACK_EXECUTION",
        0x100000: "NO_REEXPORTED_DYLIBS", 0x200000: "PIE",
        0x400000: "DEAD_STRIPPABLE_DYLIB", 0x800000: "HAS_TLV_DESCRIPTORS",
        0x1000000: "NO_HEAP_EXECUTION", 0x2000000: "APP_EXTENSION_SAFE",
        0x8000000: "DYLIB_IN_CACHE",
    }
    return [name for bit, name in flag_bits.items() if flags & bit]


def _parse_load_commands(data: bytes, header: dict, is_64: bool, swap: bool) -> list[dict]:
    ncmds = header.get("ncmds", 0)
    total_size = header.get("sizeofcmds", 0)
    hdr_size = 32 if is_64 else 28
    offset = hdr_size
    cmds = []

    for _ in range(min(ncmds, 128)):
        if offset + 8 > len(data) or offset - hdr_size >= total_size:
            break
        cmd = _get_uint32(data, offset, swap)
        cmdsize = _get_uint32(data, offset + 4, swap)
        if cmdsize < 8 or offset + cmdsize > len(data):
            break

        cmd_name = LCMD_NAMES.get(cmd, f"0x{cmd:08X}")
        parsed = {"cmd": cmd_name, "cmdsize": cmdsize, "offset": offset}

        if cmd in (LC_SEGMENT, LC_SEGMENT_64):
            _parse_segment(data, offset, parsed, cmd == LC_SEGMENT_64, swap)
        elif cmd in (LC_LOAD_DYLIB, LC_LOAD_WEAK_DYLIB, LC_REEXPORT_DYLIB, LC_ID_DYLIB):
            _parse_dylib_cmd(data, offset, parsed, swap, cmdsize)
        elif cmd == LC_UUID:
            parsed["uuid"] = data[offset + 8 : offset + 24].hex().upper()
            parsed["uuid_formatted"] = "-".join([
                parsed["uuid"][:8], parsed["uuid"][8:12],
                parsed["uuid"][12:16], parsed["uuid"][16:20], parsed["uuid"][20:32],
            ])
        elif cmd == LC_MAIN:
            parsed["entry_offset"] = _get_uint64(data, offset + 8, swap)
            parsed["stack_size"] = _get_uint64(data, offset + 16, swap)
        elif cmd == LC_CODE_SIGNATURE:
            parsed["codesig_offset"] = _get_uint32(data, offset + 8, swap)
            parsed["codesig_size"] = _get_uint32(data, offset + 12, swap)
        elif cmd in (LC_ENCRYPTION_INFO, LC_ENCRYPTION_INFO_64):
            parsed["crypt_offset"] = _get_uint32(data, offset + 8, swap)
            parsed["crypt_size"] = _get_uint32(data, offset + 12, swap)
            parsed["crypt_id"] = _get_uint32(data, offset + 16, swap)
            parsed["encrypted"] = parsed["crypt_id"] != 0
        elif cmd in (LC_VERSION_MIN_MACOSX, LC_VERSION_MIN_IPHONEOS):
            ver = _get_uint32(data, offset + 8, swap)
            sdk = _get_uint32(data, offset + 12, swap)
            parsed["version"] = f"{ver >> 16}.{(ver >> 8) & 0xFF}.{ver & 0xFF}"
            parsed["sdk"] = f"{sdk >> 16}.{(sdk >> 8) & 0xFF}.{sdk & 0xFF}"
        elif cmd == LC_BUILD_VERSION:
            plat = _get_uint32(data, offset + 8, swap)
            minos = _get_uint32(data, offset + 12, swap)
            sdk = _get_uint32(data, offset + 16, swap)
            parsed["platform"] = plat
            parsed["minos"] = f"{minos >> 16}.{(minos >> 8) & 0xFF}.{minos & 0xFF}"
            parsed["sdk"] = f"{sdk >> 16}.{(sdk >> 8) & 0xFF}.{sdk & 0xFF}"
        elif cmd == LC_RPATH:
            _parse_rpath(data, offset, parsed, swap, cmdsize)
        elif cmd in (LC_DYLD_INFO, LC_DYLD_INFO_ONLY):
            parsed["rebase_off"] = _get_uint32(data, offset + 8, swap)
            parsed["rebase_size"] = _get_uint32(data, offset + 12, swap)
            parsed["bind_off"] = _get_uint32(data, offset + 16, swap)
            parsed["bind_size"] = _get_uint32(data, offset + 20, swap)
            parsed["weak_bind_off"] = _get_uint32(data, offset + 24, swap)
            parsed["weak_bind_size"] = _get_uint32(data, offset + 28, swap)
            parsed["lazy_bind_off"] = _get_uint32(data, offset + 32, swap)
            parsed["lazy_bind_size"] = _get_uint32(data, offset + 36, swap)
            parsed["export_off"] = _get_uint32(data, offset + 40, swap)
            parsed["export_size"] = _get_uint32(data, offset + 44, swap)

        cmds.append(parsed)
        offset += cmdsize

    return cmds


def _parse_segment(data: bytes, offset: int, parsed: dict, is_64: bool, swap: bool):
    """Parse LC_SEGMENT / LC_SEGMENT_64 including section entries."""
    segname = data[offset + 8 : offset + 24].rstrip(b"\x00").decode("ascii", errors="replace")
    parsed["segname"] = segname
    if is_64:
        parsed["vmaddr"] = _get_uint64(data, offset + 24, swap)
        parsed["vmsize"] = _get_uint64(data, offset + 32, swap)
        parsed["fileoff"] = _get_uint64(data, offset + 40, swap)
        parsed["filesize"] = _get_uint64(data, offset + 48, swap)
        parsed["maxprot"] = _get_uint32(data, offset + 56, swap)
        parsed["initprot"] = _get_uint32(data, offset + 60, swap)
        parsed["nsects"] = _get_uint32(data, offset + 64, swap)
        parsed["flags"] = _get_uint32(data, offset + 68, swap)
        sect_offset = offset + 72
    else:
        parsed["vmaddr"] = _get_uint32(data, offset + 24, swap)
        parsed["vmsize"] = _get_uint32(data, offset + 28, swap)
        parsed["fileoff"] = _get_uint32(data, offset + 32, swap)
        parsed["filesize"] = _get_uint32(data, offset + 36, swap)
        parsed["maxprot"] = _get_uint32(data, offset + 40, swap)
        parsed["initprot"] = _get_uint32(data, offset + 44, swap)
        parsed["nsects"] = _get_uint32(data, offset + 48, swap)
        parsed["flags"] = _get_uint32(data, offset + 52, swap)
        sect_offset = offset + 56

    parsed["prot_detail"] = _prot_to_str(parsed["maxprot"])
    sections = []
    sect_size = 80 if is_64 else 68
    for _ in range(min(parsed["nsects"], 64)):
        if sect_offset + sect_size > len(data):
            break
        sect_name = data[sect_offset : sect_offset + 16].rstrip(b"\x00").decode("ascii", errors="replace")
        if is_64:
            s_addr = _get_uint64(data, sect_offset + 32, swap)
            s_size = _get_uint64(data, sect_offset + 40, swap)
            s_flags = _get_uint32(data, sect_offset + 64, swap)
        else:
            s_addr = _get_uint32(data, sect_offset + 32, swap)
            s_size = _get_uint32(data, sect_offset + 36, swap)
            s_flags = _get_uint32(data, sect_offset + 56, swap)

        sections.append({"name": sect_name, "addr": s_addr, "size": s_size,
                        "flags": s_flags, "attrs": _section_attrs(s_flags)})
        sect_offset += sect_size

    if sections:
        parsed["sections"] = sections


def _parse_dylib_cmd(data: bytes, offset: int, parsed: dict, swap: bool, cmdsize: int = 0):
    """Parse LC_LOAD_DYLIB / LC_LOAD_WEAK_DYLIB / LC_REEXPORT_DYLIB."""
    name_offset = _get_uint32(data, offset + 8, swap)  # lc_str
    timestamp = _get_uint32(data, offset + 12, swap)
    current_ver = _get_uint32(data, offset + 16, swap)
    compat_ver = _get_uint32(data, offset + 20, swap)

    # Library name is at offset + name_offset
    name_start = offset + name_offset
    name_bound = offset + cmdsize if cmdsize else len(data)
    name_end = data.find(b"\x00", name_start, name_bound)
    if name_end == -1:
        name_end = name_bound
    if name_start < name_bound:
        parsed["name"] = data[name_start:name_end].decode("ascii", errors="replace")
    parsed["current_version"] = f"{current_ver >> 16}.{(current_ver >> 8) & 0xFF}.{current_ver & 0xFF}"
    parsed["compat_version"] = f"{compat_ver >> 16}.{(compat_ver >> 8) & 0xFF}.{compat_ver & 0xFF}"
    parsed["timestamp"] = timestamp
    parsed["timestamp_utc"] = datetime.fromtimestamp(timestamp, tz=timezone.utc).isoformat() if timestamp else None


def _parse_rpath(data: bytes, offset: int, parsed: dict, swap: bool, cmdsize: int = 0):
    """Parse LC_RPATH — same string layout as dylib commands."""
    name_offset = _get_uint32(data, offset + 8, swap)
    name_start = offset + name_offset
    name_bound = offset + cmdsize if cmdsize else len(data)
    name_end = data.find(b"\x00", name_start, name_bound)
    if name_end == -1:
        name_end = name_bound
    if name_start < name_bound:
        parsed["name"] = data[name_start:name_end].decode("ascii", errors="replace")


def _check_security(load_cmds: list[dict], header_flags: int = 0) -> dict:
    stack_exec = bool(header_flags & 0x20000)
    sec = {
        "pie": bool(header_flags & 0x200000), "nx": not stack_exec, "arc": False,
        "encrypted": False, "codesigned": False, "has_rpath": False,
        "rpaths": [], "stack_exec": stack_exec,
    }
    for cmd in load_cmds:
        cmd_name = cmd["cmd"]
        if cmd_name == "MAIN":
            sec["pie"] = True
        elif cmd_name == "CODE_SIGNATURE":
            sec["codesigned"] = True
        elif cmd_name == "RPATH":
            sec["has_rpath"] = True
            sec["rpaths"].append(cmd.get("name", "?"))
        elif cmd_name in ("ENCRYPTION_INFO", "ENCRYPTION_INFO_64"):
            if cmd.get("encrypted"):
                sec["encrypted"] = True
        elif cmd_name in ("SEGMENT", "SEGMENT_64"):
            if cmd.get("segname") == "__RESTRICT" and any(
                s.get("name") == "__restrict" for s in cmd.get("sections", [])
            ):
                sec["arc"] = True
    return sec


# ── Helpers ────────────────────────────────────────────────────────────────

def _prot_to_str(prot: int) -> str:
    s = []
    if prot & SG_PROT_READ: s.append("r")
    if prot & SG_PROT_WRITE: s.append("w")
    if prot & SG_PROT_EXEC: s.append("x")
    return "".join(s) or "-"


def _section_attrs(flags: int) -> list[str]:
    attrs = []
    s_attrs = {
        0xFF000000: ["SYSTEM", 24],
        0x1: "PURE_INSTRUCTIONS", 0x2: "NO_TOC", 0x4: "STRIP_STATIC_SYMS",
        0x8: "NO_DEAD_STRIP", 0x10: "LIVE_SUPPORT", 0x20: "SELF_MODIFYING_CODE",
        0x40: "DEBUG", 0x100: "SOME_INSTRUCTIONS", 0x200: "EXT_RELOC",
        0x400: "LOC_RELOC",
    }
    for bit, name in s_attrs.items():
        if isinstance(name, list):
            continue  # skip system attrs
        if flags & bit:
            attrs.append(name)
    sect_type = (flags & 0xFF)
    type_names = {0: "REGULAR", 1: "ZEROFILL", 2: "CSTRING_LITERALS", 3: "4BYTE_LITERALS",
                  4: "8BYTE_LITERALS", 5: "LITERAL_POINTERS", 6: "NON_LAZY_SYMBOL_POINTERS",
                  7: "LAZY_SYMBOL_POINTERS", 8: "SYMBOL_STUBS", 9: "MOD_INIT_FUNC_POINTERS",
                  10: "MOD_TERM_FUNC_POINTERS", 11: "COALESCED", 12: "GB_ZEROFILL",
                  13: "INTERPOSING", 14: "16BYTE_LITERALS", 15: "DTRACE_DOF",
                  16: "LAZY_DYLIB_SYMBOL_POINTERS"}
    if sect_type in type_names:
        attrs.append(type_names[sect_type])
    return attrs


# ── Reporting ──────────────────────────────────────────────────────────────

def write_report(result: dict, path: str):
    lines = [
        f"# Mach-O Scan: {result.get('file', 'unknown')}",
        "",
        f"**Scan time:** {result.get('scan_time', '')}  ",
        f"**File size:** {result.get('size', 0):,} bytes",
        f"**Format:** {result.get('format', '?')}",
        "",
    ]

    if "error" in result:
        lines.append(f"**Error:** {result['error']}")
        lines.append("")
        with open(path, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))
        return

    # Fat slices
    slices = result.get("slices", [])
    if slices:
        lines += ["## Architecture Slices", "", "| # | CPU Type | Offset | Size |", "|---|---|---|---|"]
        for i, s in enumerate(slices):
            lines.append(f"| {i} | {s['cpu_type']} | {hex(s['offset'])} | {s['size']:,} |")
        lines.append("")

    # Header
    hdr = result.get("header", {})
    if hdr:
        flags_list = hdr.get("flags_detail", [])
        lines += [
            "## Mach-O Header",
            "",
            "| Field | Value |",
            "|---|---|",
            f"| CPU | {hdr.get('cputype', '?')} |",
            f"| File type | {hdr.get('filetype', '?')} |",
            f"| Commands | {hdr.get('ncmds', 0)} ({hdr.get('sizeofcmds', 0):,} bytes) |",
            f"| Flags | {', '.join(flags_list) if flags_list else f'0x{hdr.get("flags", 0):X}'} |",
            "",
        ]

    # Load Commands summary
    lcs = result.get("load_commands", [])
    if lcs:
        lines += ["## Load Commands", "", "| Cmd | Details |", "|---|---|"]
        for lc in lcs:
            detail = ""
            if lc["cmd"] in ("SEGMENT", "SEGMENT_64"):
                detail = f"{lc.get('segname', '?')} — {lc.get('prot_detail', '?')} "
                detail += f"(vsiz={hex(lc.get('vmsize', 0))})"
                if lc.get("sections"):
                    detail += f" [{len(lc['sections'])} sections]"
            elif lc["cmd"] in ("LOAD_DYLIB", "LOAD_WEAK_DYLIB", "REEXPORT_DYLIB"):
                detail = lc.get("name", "?")
            elif lc["cmd"] == "MAIN":
                detail = f"entry={hex(lc.get('entry_offset', 0))}"
            elif lc["cmd"] == "UUID":
                detail = lc.get("uuid_formatted", "?")
            elif lc["cmd"] == "CODE_SIGNATURE":
                detail = f"offset={hex(lc.get('codesig_offset', 0))} size={lc.get('codesig_size', 0):,}"
            elif lc["cmd"] in ("ENCRYPTION_INFO", "ENCRYPTION_INFO_64"):
                detail = f"encrypted={lc.get('encrypted')}"
            elif lc["cmd"] in ("VERSION_MIN_MACOSX", "VERSION_MIN_IPHONEOS", "BUILD_VERSION"):
                detail = f"v{lc.get('version', lc.get('minos', '?'))}"
            lines.append(f"| {lc['cmd']} | {detail} |")
        lines.append("")

    # Security
    sec = result.get("security", {})
    lines += [
        "## Security Analysis",
        "",
        "| Feature | Status |",
        "|---|---|",
        f"| PIE | {'Yes' if sec.get('pie') else '**No**'} |",
        f"| NX (no stack exec) | {'Yes' if sec.get('nx') else '**No**'} |",
        f"| ARC (anti-debug) | {'**Yes**' if sec.get('arc') else 'No'} |",
        f"| Code Signed | {'Yes' if sec.get('codesigned') else '**No**'} |",
        f"| Encrypted (FairPlay) | {'**Yes**' if sec.get('encrypted') else 'No'} |",
        f"| Stack Exec | {'**Yes**' if sec.get('stack_exec') else 'No'} |",
        f"| RPATH | {'Yes' if sec.get('has_rpath') else 'No'} |",
        "",
    ]

    # Dylibs
    dylibs = result.get("dylibs", [])
    if dylibs:
        lines += ["## Dependencies", ""]
        for d in dylibs:
            ts = d.get("timestamp_utc") or d.get("timestamp", "?")
            lines.append(f"- {d.get('name', '?')} ({d['cmd']}, compat {d.get('compat_version', '?')}, linked {ts})")
        lines.append("")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ── CLI ────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="macho_scan — Mach-O binary deep analysis")
    parser.add_argument("macho_file", help="Path to Mach-O binary")
    parser.add_argument("--out", "-o", default="./macho_scan", help="Output directory")
    parser.add_argument("--json", action="store_true", help="Output JSON to stdout")
    args = parser.parse_args()

    if not os.path.exists(args.macho_file):
        print(f"Error: file not found: {args.macho_file}", file=sys.stderr)
        sys.exit(1)

    try:
        data = read_file(args.macho_file, allow_truncation=False)
        result = parse_macho(data, args.macho_file)
    except (OSError, struct.error, ValueError, FileTooLargeError) as e:
        print(f"Error parsing {args.macho_file}: {e}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)
    json_path = os.path.join(args.out, "macho_scan.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    md_path = os.path.join(args.out, "macho_scan.md")
    write_report(result, md_path)

    print(f"[+] Output: {json_path}, {md_path}")
    slices = result.get("slices", [])
    if slices:
        print(f"[+] Fat binary with {len(slices)} architectures: "
              f"{', '.join(s['cpu_type'] for s in slices)}")
    sec = result.get("security", {})
    if sec:
        print(f"[+] PIE={sec.get('pie')} Codesigned={sec.get('codesigned')} "
              f"Encrypted={sec.get('encrypted')}")

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
