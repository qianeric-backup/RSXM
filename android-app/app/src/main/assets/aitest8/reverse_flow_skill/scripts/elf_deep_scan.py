#!/usr/bin/env python3
"""elf_deep_scan.py — ELF binary deep structural analysis

Covers: ELF header, program headers, section headers, dynamic section,
security mitigations (PIE/RELRO/NX/Canary/Fortify/RUNPATH),
symbol versioning, init/fini arrays, .note parsing, .eh_frame detection.

Usage:
    python elf_deep_scan.py <elf_file> [--out <dir>] [--json]
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path

# ── ELF constants ─────────────────────────────────────────────────────────

ELFCLASSNONE = 0
ELFCLASS32 = 1
ELFCLASS64 = 2

ELFDATANONE = 0
ELFDATA2LSB = 1
ELFDATA2MSB = 2

ET_NONE = 0
ET_REL = 1
ET_EXEC = 2
ET_DYN = 3
ET_CORE = 4
ETYPE_NAMES = {0: "NONE", 1: "REL", 2: "EXEC", 3: "DYN", 4: "CORE"}

PT_NULL = 0
PT_LOAD = 1
PT_DYNAMIC = 2
PT_INTERP = 3
PT_NOTE = 4
PT_SHLIB = 5
PT_PHDR = 6
PT_TLS = 7
PT_GNU_EH_FRAME = 0x6474E550
PT_GNU_STACK = 0x6474E551
PT_GNU_RELRO = 0x6474E552
PT_GNU_PROPERTY = 0x6474E553
PTYPE_NAMES = {
    PT_NULL: "NULL", PT_LOAD: "LOAD", PT_DYNAMIC: "DYNAMIC",
    PT_INTERP: "INTERP", PT_NOTE: "NOTE", PT_SHLIB: "SHLIB",
    PT_PHDR: "PHDR", PT_TLS: "TLS",
    PT_GNU_EH_FRAME: "GNU_EH_FRAME", PT_GNU_STACK: "GNU_STACK",
    PT_GNU_RELRO: "GNU_RELRO", PT_GNU_PROPERTY: "GNU_PROPERTY",
}

DT_NULL = 0
DT_NEEDED = 1
DT_SONAME = 14
DT_RPATH = 15
DT_RUNPATH = 29
DT_FLAGS = 30
DT_FLAGS_1 = 0x6FFFFFFB
DT_BIND_NOW = 24
DT_INIT_ARRAY = 25
DT_FINI_ARRAY = 26
DT_INIT_ARRAYSZ = 27
DT_FINI_ARRAYSZ = 28
DT_GNU_HASH = 0x6FFFFEF5
DT_VERSYM = 0x6FFFFFF0
DT_VERNEED = 0x6FFFFFFE
DTYPE_NAMES = {
    0: "NULL", 1: "NEEDED", 2: "PLTGOT", 3: "HASH", 5: "STRTAB",
    6: "SYMTAB", 10: "STRSZ", 12: "INIT", 13: "FINI", 14: "SONAME",
    15: "RPATH", 17: "REL", 23: "JMPREL",
    24: "BIND_NOW", 25: "INIT_ARRAY", 26: "FINI_ARRAY",
    27: "INIT_ARRAYSZ", 28: "FINI_ARRAYSZ", 29: "RUNPATH",
    30: "FLAGS",
}

DF_1_NOW = 0x00000001
DF_BIND_NOW_FLAG = 0x8  # DT_FLAGS bit for BIND_NOW

PF_X = 1
PF_W = 2
PF_R = 4

SHT_DYNSYM = 11
SHT_REL = 9
SHT_RELA = 4
SHT_HASH = 5
SHT_GNU_HASH = 0x6FFFFFF6
SHT_NOTE = 7

# ── Utility ────────────────────────────────────────────────────────────────

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common.io_utils import read_file, FileTooLargeError  # noqa: E402


# ── ELF Parsing ────────────────────────────────────────────────────────────

def parse_elf(data: bytes, path: str) -> dict:
    result: dict = {
        "file": Path(path).name,
        "size": len(data),
        "scan_time": datetime.now(timezone.utc).isoformat(),
    }

    if len(data) < 16 or data[:4] != b"\x7fELF":
        result["error"] = "Not a valid ELF file"
        return result

    elf_class = data[4]  # 1=32bit, 2=64bit
    elf_endian = data[5]  # 1=LE, 2=BE
    is_64 = elf_class == ELFCLASS64
    is_le = elf_endian == ELFDATA2LSB
    endian_char = "<" if is_le else ">"

    result["header"] = _parse_elf_header(data, is_64, endian_char)
    result["program_headers"] = _parse_program_headers(data, result["header"], is_64, endian_char)
    result["section_headers"] = _parse_section_headers(data, result["header"], is_64, endian_char)
    result["dynamic"] = _parse_dynamic(data, result["program_headers"], is_64, endian_char)
    result["notes"] = _parse_notes(data, result["program_headers"], is_64, endian_char)
    result["mitigations"] = _check_mitigations(data, result, is_64, endian_char)
    result["init_fini"] = _parse_init_fini(result["dynamic"], result["section_headers"])
    result["strings"] = _extract_elf_strings(data)

    return result


def _parse_elf_header(data: bytes, is_64: bool, ec: str) -> dict:
    """Parse ELF file header (e_ident + fixed fields)."""
    hdr: dict = {}
    hdr["class"] = "ELF64" if is_64 else "ELF32"
    hdr["endian"] = "little" if ec == "<" else "big"
    hdr["os_abi"] = data[7]

    e_type = struct.unpack_from(ec + "H", data, 16)[0]
    hdr["type"] = ETYPE_NAMES.get(e_type, f"0x{e_type:04X}")

    machine = struct.unpack_from(ec + "H", data, 18)[0]
    machine_map = {
        0x03: "x86", 0x28: "ARM", 0xB7: "AArch64", 0x3E: "x86-64",
        0x08: "MIPS", 0x14: "PowerPC", 0x15: "PowerPC64",
        0x2A: "SuperH", 0xF3: "RISC-V",
    }
    hdr["machine"] = machine_map.get(machine, f"0x{machine:04X}")
    hdr["version"] = struct.unpack_from(ec + "I", data, 20)[0]

    if is_64:
        hdr["entry"] = struct.unpack_from(ec + "Q", data, 24)[0]
        hdr["phoff"] = struct.unpack_from(ec + "Q", data, 32)[0]
        hdr["shoff"] = struct.unpack_from(ec + "Q", data, 40)[0]
        hdr["phentsize"] = struct.unpack_from(ec + "H", data, 54)[0]
        hdr["phnum"] = struct.unpack_from(ec + "H", data, 56)[0]
        hdr["shentsize"] = struct.unpack_from(ec + "H", data, 58)[0]
        hdr["shnum"] = struct.unpack_from(ec + "H", data, 60)[0]
        hdr["shstrndx"] = struct.unpack_from(ec + "H", data, 62)[0]
    else:
        hdr["entry"] = struct.unpack_from(ec + "I", data, 24)[0]
        hdr["phoff"] = struct.unpack_from(ec + "I", data, 28)[0]
        hdr["shoff"] = struct.unpack_from(ec + "I", data, 32)[0]
        hdr["phentsize"] = struct.unpack_from(ec + "H", data, 42)[0]
        hdr["phnum"] = struct.unpack_from(ec + "H", data, 44)[0]
        hdr["shentsize"] = struct.unpack_from(ec + "H", data, 46)[0]
        hdr["shnum"] = struct.unpack_from(ec + "H", data, 48)[0]
        hdr["shstrndx"] = struct.unpack_from(ec + "H", data, 50)[0]

    return hdr


def _parse_program_headers(data: bytes, hdr: dict, is_64: bool, ec: str) -> list[dict]:
    """Parse ELF program headers."""
    phoff = hdr.get("phoff", 0)
    phnum = min(hdr.get("phnum", 0), 128)
    phentsize = hdr.get("phentsize", 0)
    if phoff == 0 or phnum == 0 or phentsize == 0:
        return []

    phdrs = []
    for i in range(phnum):
        offset = phoff + i * phentsize
        if offset + phentsize > len(data):
            break

        p_type = struct.unpack_from(ec + "I", data, offset)[0]
        if is_64:
            p_flags = struct.unpack_from(ec + "I", data, offset + 4)[0]
            p_offset = struct.unpack_from(ec + "Q", data, offset + 8)[0]
            p_vaddr = struct.unpack_from(ec + "Q", data, offset + 16)[0]
            p_filesz = struct.unpack_from(ec + "Q", data, offset + 32)[0]
            p_memsz = struct.unpack_from(ec + "Q", data, offset + 40)[0]
        else:
            p_offset = struct.unpack_from(ec + "I", data, offset + 4)[0]
            p_vaddr = struct.unpack_from(ec + "I", data, offset + 8)[0]
            p_filesz = struct.unpack_from(ec + "I", data, offset + 16)[0]
            p_memsz = struct.unpack_from(ec + "I", data, offset + 20)[0]
            p_flags = struct.unpack_from(ec + "I", data, offset + 24)[0]

        perm = ""
        if p_flags & PF_R: perm += "R"
        if p_flags & PF_W: perm += "W"
        if p_flags & PF_X: perm += "X"

        phdrs.append({
            "index": i,
            "type": PTYPE_NAMES.get(p_type, f"0x{p_type:08X}"),
            "flags": perm,
            "offset": p_offset,
            "vaddr": p_vaddr,
            "filesz": p_filesz,
            "memsz": p_memsz,
        })
    return phdrs


def _parse_section_headers(data: bytes, hdr: dict, is_64: bool, ec: str) -> list[dict]:
    """Parse ELF section headers (name only, for identification)."""
    shoff = hdr.get("shoff", 0)
    shnum = min(hdr.get("shnum", 0), 256)
    shentsize = hdr.get("shentsize", 0)
    shstrndx = hdr.get("shstrndx", 0)

    if shoff == 0 or shnum == 0 or shentsize == 0:
        return []

    # Read section name string table
    shstr_offset = shoff + shstrndx * shentsize
    shstr_data = _read_shstrtab(data, shstr_offset, is_64, ec)
    if shstr_data is None:
        return []

    sections = []
    for i in range(shnum):
        soff = shoff + i * shentsize
        if soff + shentsize > len(data):
            break

        sh_name_idx = struct.unpack_from(ec + "I", data, soff)[0]
        sh_type = struct.unpack_from(ec + "I", data, soff + 4)[0]

        # Read name from string table
        name = _str_from_table(shstr_data, sh_name_idx)

        type_names = {
            0: "NULL", 1: "PROGBITS", 2: "SYMTAB", 3: "STRTAB",
            4: "RELA", 5: "HASH", 6: "DYNAMIC", 7: "NOTE", 8: "NOBITS",
            9: "REL", 10: "SHLIB", 11: "DYNSYM",
            14: "INIT_ARRAY", 15: "FINI_ARRAY", 16: "PREINIT_ARRAY",
            0x6FFFFFF6: "GNU_HASH", 0x6FFFFFFE: "VERNEED",
            0x6FFFFFFF: "VERSYM",
        }

        if is_64:
            sh_addr = struct.unpack_from(ec + "Q", data, soff + 16)[0]
            sh_size = struct.unpack_from(ec + "Q", data, soff + 32)[0]
        else:
            sh_addr = struct.unpack_from(ec + "I", data, soff + 12)[0]
            sh_size = struct.unpack_from(ec + "I", data, soff + 20)[0]

        sections.append({
            "index": i,
            "name": name,
            "type": type_names.get(sh_type, f"0x{sh_type:08X}"),
            "addr": sh_addr,
            "size": sh_size,
        })
    return sections


def _read_shstrtab(data: bytes, offset: int, is_64: bool, ec: str) -> bytes | None:
    """Read section header string table content."""
    if offset + (40 if is_64 else 24) > len(data):
        return None
    if is_64:
        sh_offset = struct.unpack_from(ec + "Q", data, offset + 24)[0]
        sh_size = struct.unpack_from(ec + "Q", data, offset + 32)[0]
    else:
        sh_offset = struct.unpack_from(ec + "I", data, offset + 16)[0]
        sh_size = struct.unpack_from(ec + "I", data, offset + 20)[0]
    if sh_offset + sh_size > len(data):
        return None
    return data[sh_offset : sh_offset + sh_size]


def _str_from_table(table: bytes, offset: int) -> str:
    """Read null-terminated string from string table at offset."""
    end = table.find(b"\x00", offset)
    if end == -1:
        end = len(table)
    return table[offset:end].decode("ascii", errors="replace")


def _parse_dynamic(data: bytes, phdrs: list[dict], is_64: bool, ec: str) -> dict:
    """Parse .dynamic section entries."""
    result: dict = {"needed": [], "soname": None, "rpath": None, "runpath": None,
                    "bind_now": False, "init_array": None, "fini_array": None,
                    "raw_entries": []}

    dyn_phdr = next((p for p in phdrs if p["type"] == "DYNAMIC"), None)
    if dyn_phdr is None:
        return result

    offset = dyn_phdr["offset"]
    filesz = dyn_phdr["filesz"]
    d_tag_size = 16 if is_64 else 8
    d_val_size = 8 if is_64 else 4
    entry_size = d_tag_size

    entries = []
    pos = offset
    while pos + d_tag_size <= min(offset + filesz, len(data)):
        d_tag = struct.unpack_from(ec + ("Q" if is_64 else "I"), data, pos)[0]
        d_val = struct.unpack_from(ec + ("Q" if is_64 else "I"), data, pos + d_val_size)[0]
        if d_tag == DT_NULL:
            entries.append({"tag": "NULL", "value": d_val})
            break
        tag_name = DTYPE_NAMES.get(d_tag, f"0x{d_tag:08X}")
        entries.append({"tag": tag_name, "value": d_val})

        if d_tag == DT_NEEDED:
            result["needed"].append(hex(d_val))  # strtab offset, resolved later
        elif d_tag == DT_SONAME:
            result["soname"] = hex(d_val)
        elif d_tag == DT_RPATH:
            result["rpath"] = hex(d_val)
        elif d_tag == DT_RUNPATH:
            result["runpath"] = hex(d_val)
        elif d_tag == DT_FLAGS_1 and d_val & DF_1_NOW:
            result["bind_now"] = True
        elif d_tag == DT_FLAGS and d_val & DF_BIND_NOW_FLAG:
            result["bind_now"] = True
        elif d_tag == DT_BIND_NOW:
            result["bind_now"] = True
        elif d_tag == DT_INIT_ARRAY:
            result["init_array"] = d_val
        elif d_tag == DT_FINI_ARRAY:
            result["fini_array"] = d_val
        pos += entry_size

    result["raw_entries"] = entries
    return result


def _parse_notes(data: bytes, phdrs: list[dict], is_64: bool, ec: str) -> list[dict]:
    """Parse .note / PT_NOTE segments."""
    notes = []
    for phdr in phdrs:
        if phdr["type"] == "NOTE":
            note_data = data[phdr["offset"] : phdr["offset"] + phdr["filesz"]]
            parsed = _parse_note_segment(note_data, ec)
            notes.extend(parsed)
    return notes


def _parse_note_segment(data: bytes, ec: str) -> list[dict]:
    """Parse individual notes from a PT_NOTE segment."""
    notes = []
    pos = 0
    while pos + 12 <= len(data):
        namesz = struct.unpack_from(ec + "I", data, pos)[0]
        descsz = struct.unpack_from(ec + "I", data, pos + 4)[0]
        ntype = struct.unpack_from(ec + "I", data, pos + 8)[0]
        pos += 12

        name_end = pos + namesz
        name = data[pos : name_end].rstrip(b"\x00").decode("ascii", errors="replace")
        pos = (name_end + 3) & ~3  # align to 4 bytes

        desc = data[pos : pos + descsz]
        pos = (pos + descsz + 3) & ~3

        desc_hex = ""
        if ntype == 3 and name == "GNU" and len(desc) >= 16:  # NT_GNU_BUILD_ID
            desc_hex = desc[:20].hex()
        elif len(desc) <= 64:
            desc_hex = desc.hex()

        notes.append({
            "owner": name,
            "type": ntype,
            "desc_size": descsz,
            "desc_hex": desc_hex,
        })
    return notes


def _parse_init_fini(dynamic: dict, sections: list[dict]) -> dict:
    """Identify init_array/fini_array sections and their function pointers."""
    result: dict = {"init_array": [], "fini_array": [], "preinit_array": []}
    for sec in sections:
        if sec["name"] == ".init_array":
            result["init_array"].append({"addr": sec["addr"], "size": sec["size"]})
        elif sec["name"] == ".fini_array":
            result["fini_array"].append({"addr": sec["addr"], "size": sec["size"]})
        elif sec["name"] == ".preinit_array":
            result["preinit_array"].append({"addr": sec["addr"], "size": sec["size"]})
    return result


def _check_mitigations(data: bytes, result: dict, is_64: bool, ec: str) -> dict:
    """Check ELF security mitigations (like checksec)."""
    mitigs: dict = {}
    phdrs = result.get("program_headers", [])
    hdr = result.get("header", {})

    # PIE — position independent executable
    mitigs["pie"] = hdr.get("type") == "DYN"

    # NX — check GNU_STACK flags
    gnu_stack = next((p for p in phdrs if p["type"] == "GNU_STACK"), None)
    if gnu_stack:
        mitigs["nx"] = "X" not in gnu_stack["flags"]
    else:
        mitigs["nx"] = "unknown (no GNU_STACK)"

    # RELRO — Partial vs Full
    gnu_relro = next((p for p in phdrs if p["type"] == "GNU_RELRO"), None)
    dyn = result.get("dynamic", {})
    if gnu_relro:
        mitigs["relro"] = "Full" if dyn.get("bind_now") else "Partial"
    else:
        mitigs["relro"] = "None"

    # Stack Canary — check for __stack_chk_fail symbol
    mitigs["canary"] = b"__stack_chk_fail" in data

    # FORTIFY — check for _chk function variants
    fortify_indicators = [b"__sprintf_chk", b"__snprintf_chk", b"__memcpy_chk",
                          b"__memset_chk", b"__strcpy_chk"]
    mitigs["fortify"] = any(ind in data for ind in fortify_indicators)

    # RUNPATH / RPATH
    mitigs["rpath"] = dyn.get("rpath") is not None
    mitigs["runpath"] = dyn.get("runpath") is not None

    # RWX segments
    rwx_segments = [p for p in phdrs if "W" in p["flags"] and "X" in p["flags"]]
    mitigs["has_rwx_segment"] = len(rwx_segments) > 0
    mitigs["rwx_segments"] = [p["type"] for p in rwx_segments]

    return mitigs


def _extract_elf_strings(data: bytes) -> list[str]:
    """Extract notable strings relevant to ELF analysis using jump scan."""
    interesting = []
    pos = 0
    while True:
        idx = data.find(b"lib", pos)
        if idx == -1 or idx + 4 >= len(data):
            break
        if data[idx + 3 : idx + 4].isalpha():
            end = data.find(b"\x00", idx)
            if end != -1 and end - idx < 128:
                try:
                    s = data[idx:end].decode("ascii")
                    if s.endswith(".so") or ".so." in s:
                        interesting.append(s)
                except UnicodeDecodeError:
                    pass
            pos = idx + 4
        else:
            pos = idx + 1
    return sorted(set(interesting))


# ── Reporting ──────────────────────────────────────────────────────────────

def write_report(result: dict, path: str):
    """Write Markdown report."""
    lines = [
        f"# ELF Deep Scan: {result.get('file', 'unknown')}",
        "",
        f"**Scan time:** {result.get('scan_time', '')}  ",
        f"**File size:** {result.get('size', 0):,} bytes",
        "",
    ]

    if "error" in result:
        lines.append(f"**Error:** {result['error']}")
        lines.append("")
        Path(path).write_text("\n".join(lines), encoding="utf-8")
        return

    hdr = result.get("header", {})
    lines += [
        "## ELF Header",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| Class | {hdr.get('class', '?')} |",
        f"| Endian | {hdr.get('endian', '?')} |",
        f"| Type | {hdr.get('type', '?')} |",
        f"| Machine | {hdr.get('machine', '?')} |",
        f"| Entry point | {hex(hdr.get('entry', 0))} |",
        f"| Program headers | {hdr.get('phnum', 0)} |",
        f"| Section headers | {hdr.get('shnum', 0)} |",
        "",
    ]

    # Program Headers
    phdrs = result.get("program_headers", [])
    if phdrs:
        lines += ["## Program Headers", "", "| # | Type | Flags | VAddr | FileSz | MemSz |", "|---|---|---|---|---|---|"]
        for p in phdrs:
            lines.append(f"| {p['index']} | {p['type']} | {p['flags']} | {hex(p['vaddr'])} | {p['filesz']} | {p['memsz']} |")
        lines.append("")

    # Section Headers
    sections = result.get("section_headers", [])
    if sections:
        lines += ["## Section Headers", "", "| # | Name | Type | Addr | Size |", "|---|---|---|---|---|"]
        for s in sections:
            lines.append(f"| {s['index']} | {s['name']} | {s['type']} | {hex(s['addr'])} | {s['size']} |")
        lines.append("")

    # Dynamic section
    dyn = result.get("dynamic", {})
    entries = dyn.get("raw_entries", [])
    if entries:
        lines += ["## Dynamic Section", "", "| Tag | Value |", "|---|---|"]
        for e in entries[:40]:
            lines.append(f"| {e['tag']} | {hex(e['value']) if isinstance(e['value'], int) else e['value']} |")
        lines.append("")

    # Security Mitigations
    mitigs = result.get("mitigations", {})
    lines += [
        "## Security Mitigations",
        "",
        "| Mitigation | Status |",
        "|---|---|",
        f"| PIE | {'Yes' if mitigs.get('pie') else '**No**'} |",
        f"| NX | {'Yes' if mitigs.get('nx') == True else mitigs.get('nx', '?')} |",
        f"| RELRO | {mitigs.get('relro', '?')} |",
        f"| Stack Canary | {'Yes' if mitigs.get('canary') else '**No**'} |",
        f"| FORTIFY | {'Yes' if mitigs.get('fortify') else '**No**'} |",
        f"| RPATH | {'**Yes**' if mitigs.get('rpath') else 'No'} |",
        f"| RUNPATH | {'Yes' if mitigs.get('runpath') else 'No'} |",
        f"| RWX Segment | {'**Yes**' if mitigs.get('has_rwx_segment') else 'No'} |",
        "",
    ]

    # Init/Fini arrays
    init_fini = result.get("init_fini", {})
    for key, label in [("init_array", ".init_array"), ("fini_array", ".fini_array"),
                       ("preinit_array", ".preinit_array")]:
        entries_list = init_fini.get(key, [])
        if entries_list:
            lines.append(f"## {label}")
            lines.append("")
            for item in entries_list:
                lines.append(f"- `{hex(item['addr'])}` ({item['size']} bytes)")
            lines.append("")

    # Notes
    notes = result.get("notes", [])
    if notes:
        lines += ["## Notes", ""]
        for n in notes:
            lines.append(f"- **{n['owner']}** type={n['type']}: {n['desc_hex'][:64]}...")
        lines.append("")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ── CLI ────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="elf_deep_scan — ELF binary deep analysis")
    parser.add_argument("elf_file", help="Path to ELF binary")
    parser.add_argument("--out", "-o", default="./elf_scan", help="Output directory")
    parser.add_argument("--json", action="store_true", help="Output JSON to stdout")
    args = parser.parse_args()

    if not os.path.exists(args.elf_file):
        print(f"Error: file not found: {args.elf_file}", file=sys.stderr)
        sys.exit(1)

    try:
        data = read_file(args.elf_file, allow_truncation=False)
        result = parse_elf(data, args.elf_file)
    except (OSError, struct.error, ValueError, FileTooLargeError) as e:
        print(f"Error parsing {args.elf_file}: {e}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)
    json_path = os.path.join(args.out, "elf_scan.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    md_path = os.path.join(args.out, "elf_scan.md")
    write_report(result, md_path)

    print(f"[+] Output: {json_path}, {md_path}")
    if result.get("mitigations"):
        m = result["mitigations"]
        print(f"[+] PIE={m.get('pie')} NX={m.get('nx')} RELRO={m.get('relro')} "
              f"Canary={m.get('canary')} FORTIFY={m.get('fortify')}")

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
