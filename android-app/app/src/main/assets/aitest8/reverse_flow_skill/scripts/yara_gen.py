#!/usr/bin/env python3
"""yara_gen.py — 自动生成 YARA 规则

从二进制样本中提取独特字符串、字节序列、结构特征，自动生成 YARA 规则。

Usage:
    python yara_gen.py <sample> [--out <output_dir>] [--min-str-len 6] [--max-strings 15]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path


# ── String extraction ─────────────────────────────────────────────────────────

def extract_strings(data: bytes, min_len: int = 6) -> list[str]:
    """Extract printable ASCII strings from binary data."""
    result = []
    current = []
    for b in data:
        if 0x20 <= b <= 0x7E:
            current.append(chr(b))
        else:
            if len(current) >= min_len:
                result.append("".join(current))
            current = []
    if len(current) >= min_len:
        result.append("".join(current))
    return result


def extract_unicode_strings(data: bytes, min_len: int = 6) -> list[str]:
    """Extract UTF-16LE strings (common in Windows binaries)."""
    result = []
    current = []
    i = 0
    while i < len(data) - 1:
        char = struct.unpack_from("<H", data, i)[0]
        if 0x20 <= char <= 0x7E:  # ASCII range in Unicode
            current.append(chr(char))
        else:
            if len(current) >= min_len:
                result.append("".join(current))
            current = []
        i += 2
    if len(current) >= min_len:
        result.append("".join(current))
    return result


# ── String selection heuristics ───────────────────────────────────────────────

def score_string(s: str) -> float:
    """Score a string for its usefulness as a YARA signature indicator.
    Higher score = better signature candidate."""
    score = 0.0

    # Longer strings are more unique
    score += min(len(s) / 8, 3.0)

    # Mixed-case is interesting
    has_upper = any(c.isupper() for c in s)
    has_lower = any(c.islower() for c in s)
    if has_upper and has_lower:
        score += 1.0

    # Contains special characters (paths, URLs, etc.)
    special = set(c for c in s if not c.isalnum() and c != " ")
    score += min(len(special), 5) * 0.5

    # Looks like an identifier (contains underscores, dots)
    if "_" in s or "." in s:
        score += 0.5

    # Contains error/status keywords
    keywords = ["error", "fail", "success", "version", "debug", "config",
                "http", "admin", "password", "key", "secret"]
    for kw in keywords:
        if kw in s.lower():
            score += 0.5
            break

    # Penalize very common strings
    common = {"Microsoft", "Windows", "Copyright", "http://", "https://",
              "kernel32", "user32", "ntdll", "LoadLibrary", "GetProcAddress"}
    for c in common:
        if c.lower() in s.lower():
            score -= 1.0
            break

    return max(score, 0.5)


def select_strings(strings: list[str], max_strings: int = 15) -> list[str]:
    """Select the best string candidates for YARA rules."""
    scored = [(s, score_string(s)) for s in strings]
    scored.sort(key=lambda x: -x[1])

    # Deduplicate similar strings (keep highest-scored)
    selected = []
    for s, sc in scored:
        if len(selected) >= max_strings:
            break
        # Skip if too similar to already selected
        if any(_similarity(s, existing) > 0.8 for existing, _ in selected):
            continue
        selected.append((s, sc))

    return [s for s, _ in selected]


def _similarity(a: str, b: str) -> float:
    """Simple Jaccard-like similarity between two strings."""
    sa = set(a.lower())
    sb = set(b.lower())
    if not sa or not sb:
        return 0.0
    return len(sa & sb) / len(sa | sb)


# ── Byte pattern extraction ───────────────────────────────────────────────────

def extract_byte_patterns(data: bytes, min_len: int = 8) -> list[str]:
    """Extract unique byte sequences near interesting strings or APIs."""
    patterns = []

    # Find unique sequences near suspicious imports
    interesting = [
        b"CreateRemoteThread", b"WriteProcessMemory", b"VirtualProtect",
        b"CryptEncrypt", b"IsDebuggerPresent", b"WinExec",
        b"reg delete", b"cmd.exe", b"powershell", b"certutil",
        b"Startup", b"CurrentVersion\\Run",
    ]

    for keyword in interesting:
        idx = data.find(keyword)
        if idx >= 0:
            # Capture surrounding bytes
            start = max(0, idx - 4)
            end = min(len(data), idx + len(keyword) + 8)
            pattern = data[start:end]
            patterns.append(pattern.hex())

    # Find high-entropy unique sequences at function boundaries
    # Look for common function prologues
    prologue_patterns = [
        b"\x55\x8B\xEC",       # push ebp; mov ebp, esp (x86)
        b"\x48\x89\x5C\x24",   # mov [rsp+...], rbx (x64)
        b"\x40\x55",            # push rbp (x64)
    ]
    for pp in prologue_patterns:
        idx = 0
        count = 0
        while count < 3:
            idx = data.find(pp, idx)
            if idx == -1:
                break
            start = max(0, idx - 2)
            end = min(len(data), idx + 16)
            patterns.append(data[start:end].hex())
            idx += 1
            count += 1

    # De-duplicate while preserving order
    seen = set()
    unique = []
    for p in patterns:
        if p not in seen:
            seen.add(p)
            unique.append(p)

    return unique[:15]


# ── Rule generation ───────────────────────────────────────────────────────────

def generate_rule(sample_path: str, selected_strings: list[str],
                  byte_patterns: list[str], hashes: dict,
                  file_type: str, author: str = "") -> str:
    """Generate a complete YARA rule."""
    sample_name = Path(sample_path).name
    safe_name = "".join(c if c.isalnum() or c == "_" else "_" for c in Path(sample_path).stem)
    safe_name = safe_name[:50]

    date_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    lines = [f"rule AutoGen_{safe_name} {{"]
    lines.append("    meta:")
    lines.append(f'        description = "Auto-generated rule for {sample_name}"')
    lines.append(f'        date = "{date_str}"')
    lines.append(f'        sha256 = "{hashes["sha256"]}"')
    lines.append(f'        md5 = "{hashes["md5"]}"')
    lines.append(f'        file_size = {os.path.getsize(sample_path)}')
    lines.append(f'        file_type = "{file_type}"')
    lines.append('        generated_by = "yara_gen.py"')
    if author:
        lines.append(f'        author = "{author}"')
    lines.append("")
    lines.append("    strings:")

    # String indicators
    for i, s in enumerate(selected_strings):
        escaped = s.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
        # Determine if it should be ascii, wide, or both
        if any(ord(c) > 127 for c in s):
            lines.append(f'        $s{i} = "{escaped}" wide')
        else:
            lines.append(f'        $s{i} = "{escaped}" ascii wide')

    # Byte pattern indicators
    for i, bp in enumerate(byte_patterns):
        lines.append(f"        $hex{i} = {{ {_format_hex_pattern(bp)} }}")

    # Magic bytes at offset 0
    with open(sample_path, "rb") as f:
        magic = f.read(4)
    lines.append(f"        $magic = {{ {magic.hex()} }} at 0")

    lines.append("")
    lines.append("    condition:")
    lines.extend(_build_condition(selected_strings, byte_patterns))
    lines.append("}")
    lines.append("")

    return "\n".join(lines)


def _format_hex_pattern(hex_str: str) -> str:
    """Format a hex string for YARA with optional wildcards."""
    # Insert spaces every 2 hex chars for readability
    result = " ".join(hex_str[i:i+2] for i in range(0, len(hex_str), 2))
    return result


def _build_condition(strings: list[str], patterns: list[str]) -> list[str]:
    """Build YARA condition string."""
    lines = []

    if strings and patterns:
        # Require magic + (2 strings OR 1 hex pattern)
        lines.append("        $magic and (")
        if len(strings) >= 2:
            lines.append(f"            {len(strings)} of ($s*)")
            lines.append("            or")
        if patterns:
            lines.append("            1 of ($hex*)")
        lines.append("        )")
    elif strings:
        lines.append(f"        $magic and {min(2, len(strings))} of ($s*)")
    elif patterns:
        lines.append("        $magic and 1 of ($hex*)")
    else:
        lines.append("        $magic")

    return lines


def identify_file_type(data: bytes, path: str) -> str:
    """Quick file type identification."""
    ext = Path(path).suffix.lower()
    if data[:2] == b"MZ":
        return "PE executable"
    elif data[:4] == b"\x7fELF":
        return "ELF executable"
    elif data[:4] in (b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe"):
        return "Mach-O executable"
    elif data[:2] == b"PK":
        if ext == ".apk":
            return "APK (Android)"
        return "ZIP archive"
    elif data[:4] == b"\x89PNG":
        return "PNG image"
    elif data[:4] == b"%PDF":
        return "PDF document"
    elif data[:4] == b"\x1f\x8b\x08":
        return "GZIP compressed"
    elif data[:4] == b"\xca\xfe\xba\xbe":
        return "Java class"
    return f"unknown ({ext or 'no extension'})"


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="yara_gen — auto-generate YARA rules from samples")
    parser.add_argument("sample", help="Path to sample file")
    parser.add_argument("--out", "-o", default="./yara_rules",
                        help="Output directory (default: ./yara_rules)")
    parser.add_argument("--min-str-len", type=int, default=6,
                        help="Minimum string length (default: 6)")
    parser.add_argument("--max-strings", type=int, default=15,
                        help="Maximum string count in rule (default: 15)")
    parser.add_argument("--author", default="",
                        help="Author name for rule metadata")
    parser.add_argument("--json", action="store_true",
                        help="Output JSON to stdout")

    args = parser.parse_args()

    if not os.path.exists(args.sample):
        print(f"Error: file not found: {args.sample}", file=sys.stderr)
        sys.exit(1)

    with open(args.sample, "rb") as f:
        data = f.read()

    hashes = {
        "md5": hashlib.md5(data).hexdigest(),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
    file_type = identify_file_type(data, args.sample)

    print(f"[*] Analyzing {Path(args.sample).name} ({len(data):,} bytes) — {file_type}")

    # Extract and select strings
    ascii_strs = extract_strings(data, args.min_str_len)
    unicode_strs = extract_unicode_strings(data, args.min_str_len)
    all_strs = ascii_strs + unicode_strs
    print(f"[*] Extracted {len(ascii_strs)} ASCII + {len(unicode_strs)} Unicode strings")

    selected = select_strings(all_strs, args.max_strings)
    print(f"[*] Selected {len(selected)} strings for rule")

    # Extract byte patterns
    byte_patterns = extract_byte_patterns(data)
    print(f"[*] Extracted {len(byte_patterns)} byte patterns")

    # Generate rule
    rule = generate_rule(args.sample, selected, byte_patterns, hashes,
                         file_type, args.author)

    os.makedirs(args.out, exist_ok=True)
    sample_stem = Path(args.sample).stem
    rule_path = os.path.join(args.out, f"{sample_stem}.yar")
    with open(rule_path, "w", encoding="utf-8") as f:
        f.write(rule)

    result = {
        "sample": Path(args.sample).name,
        "file_type": file_type,
        "hashes": hashes,
        "strings_extracted": len(all_strs),
        "strings_selected": len(selected),
        "byte_patterns": len(byte_patterns),
        "rule_file": rule_path,
    }

    print(f"[+] Generated rule: {rule_path}")
    print(f"[+] Strings: {len(selected)}, Hex patterns: {len(byte_patterns)}")

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
