#!/usr/bin/env python3
"""auto_analyze.py — 一键自动化二进制分析流水线

对任意文件执行: file type → hashes → entropy → strings → imports/exports →
packer detection → crypto constant scan → PE/ELF/Mach-O structural analysis →
YARA rule generation → risk indicator summary

Usage:
    python auto_analyze.py <artifact> [--out <output_dir>] [--skip-yara] [--json] [--quick]

Output: <out>/auto_analysis.json + <out>/auto_analysis.md
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import struct
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

# ── Crypto constants ──────────────────────────────────────────────────────────

AES_SBOX = bytes([
    0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76
])

AES_INV_SBOX = bytes([
    0x52,0x09,0x6a,0xd5,0x30,0x36,0xa5,0x38,0xbf,0x40,0xa3,0x9e,0x81,0xf3,0xd7,0xfb
])

MD5_IV = bytes.fromhex("0123456789abcdeffedcba9876543210")
SHA1_IV = bytes.fromhex("67452301efcdab8998badcfe10325476c3d2e1f0")

SHA256_IV = struct.pack(">IIIIIIII",
    0x6A09E667,0xBB67AE85,0x3C6EF372,0xA54FF53A,
    0x510E527F,0x9B05688C,0x1F83D9AB,0x5BE0CD19
)

TEA_DELTA = 0x9E3779B9
CRC32_POLY = 0xEDB88320

# Common crypto API patterns
CRYPTO_IMPORTS = [
    "CryptEncrypt", "CryptDecrypt", "CryptAcquireContext", "CryptGenRandom",
    "BCryptEncrypt", "BCryptDecrypt", "BCryptGenRandom",
    "EVP_EncryptInit", "EVP_DecryptInit", "EVP_CipherInit",
    "AES_encrypt", "AES_decrypt", "AES_set_encrypt_key", "AES_set_decrypt_key",
    "MD5_Init", "MD5_Update", "MD5_Final",
    "SHA1_Init", "SHA256_Init", "SHA512_Init",
    "RC4_set_key", "RC4",
    "RSA_public_encrypt", "RSA_private_decrypt",
    "RtlAesEncrypt", "RtlAesDecrypt",  # Windows internal
    "CryptImportKey", "CryptExportKey", "CryptDestroyKey",
    "System.Security.Cryptography",  # .NET
]

# Common packer section names
PACKER_SECTIONS = ["UPX0", "UPX1", "UPX2", ".aspack", ".pec", ".petite",
                   ".mpress1", ".mpress2", ".enigma", ".vmp0", ".vmp1",
                   ".themida", ".winlic", "DAta", "coder"]

# Common anti-analysis strings
ANTI_ANALYSIS_STRINGS = [
    "IsDebuggerPresent", "CheckRemoteDebuggerPresent", "NtQueryInformationProcess",
    "NtGlobalFlag", "BeingDebugged", "OutputDebugString",
    "FindWindow", "EnumWindows", "GetForegroundWindow",
    "vmtoolsd", "VBoxService", "VBoxTray", "vmware",
    "QEMU", "VirtualBox", "XenVMM", "Hyper-V",
    "SbieDll", "SxIn", "SbieSvc",  # Sandboxie
    "Procmon", "Wireshark", "x64dbg", "windbg", "ollydbg",
    "ida", "Immunity", "frida", "ProcessHacker",
    "GetTickCount", "QueryPerformanceCounter", "rdtsc",
]

# ── Utility functions ─────────────────────────────────────────────────────────

def read_file(path: str, max_size: int | None = None) -> bytes:
    with open(path, "rb") as f:
        if max_size:
            return f.read(max_size)
        return f.read()


def shannon_entropy(data: bytes) -> float:
    if not data:
        return 0.0
    counts = Counter(data)
    total = len(data)
    return -sum((c / total) * math.log2(c / total) for c in counts.values())


def compute_hashes(data: bytes) -> dict:
    return {
        "md5": hashlib.md5(data).hexdigest(),
        "sha1": hashlib.sha1(data).hexdigest(),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def identify_file_type(data: bytes, path: str) -> dict:
    """Basic magic-byte identification without external dependencies."""
    magic = data[:16]
    ext = Path(path).suffix.lower()

    result = {"extension": ext, "magic_hex": magic[:8].hex(), "type": "unknown"}

    # PE
    if magic[:2] == b"MZ":
        result["type"] = "PE (Windows executable)"
        pe_offset = struct.unpack_from("<I", data, 0x3C)[0] if len(data) > 0x3E else 0
        if pe_offset and len(data) > pe_offset + 4:
            pe_sig = data[pe_offset:pe_offset+4]
            if pe_sig == b"PE\x00\x00":
                machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
                machine_map = {0x14C: "x86", 0x8664: "x64", 0xAA64: "ARM64", 0x1C0: "ARM", 0x1C4: "ARM NT"}
                result["type"] = f"PE{machine_map.get(machine, '?')} (Windows executable)"
                result["pe_offset"] = pe_offset
                result["machine"] = machine_map.get(machine, hex(machine))

    # ELF
    elif magic[:4] == b"\x7fELF":
        bits = "64" if data[4] == 2 else "32"
        endian = "LE" if data[5] == 1 else "BE"
        ei_type = {2: "executable", 3: "shared library", 1: "relocatable"}
        e_type = struct.unpack_from("<H", data, 16)[0]
        result["type"] = f"ELF{bits} {endian} ({ei_type.get(e_type, 'type='+str(e_type))})"
        result["elf_class"] = bits
        result["elf_endian"] = endian

    # Mach-O
    elif magic[:4] in (b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe",
                        b"\xfe\xed\xfa\xcf", b"\xfe\xed\xfa\xce"):
        result["type"] = "Mach-O (macOS/iOS binary)"

    # APK (ZIP-based, recognized by extension before generic ZIP)
    elif ext == ".apk":
        result["type"] = "APK (Android application package)"

    # Archives
    elif magic[:2] == b"PK":
        result["type"] = "ZIP archive"
    elif magic[:4] == b"Rar!":
        result["type"] = "RAR archive"
    elif magic[:4] == b"\x1f\x8b\x08":
        result["type"] = "GZIP compressed"
    elif magic[:2] in (b"BZ",):
        result["type"] = "BZ2 compressed"
    elif magic[:4] == b"\xfd7zX":
        result["type"] = "XZ/LZMA compressed"

    # Images
    elif magic[:4] == b"\x89PNG":
        result["type"] = "PNG image"
    elif magic[:2] == b"\xff\xd8":
        result["type"] = "JPEG image"
    elif magic[:4] in (b"RIFF",):
        result["type"] = "RIFF container (WAV/AVI)"

    # PDF
    elif magic[:4] == b"%PDF":
        result["type"] = "PDF document"

    # Java
    elif magic[:4] == b"\xca\xfe\xba\xbe":
        result["type"] = "Java class file"

    # .NET — check magic bytes BEFORE type is rewritten with architecture
    if magic[:2] == b"MZ" and b"mscoree" in data[:2048].lower():
        result["is_dotnet"] = True
        # Prepend to existing type (already set to PEx64/PEx86/etc)
        result["type"] = ".NET " + result["type"]

    return result


# ── String extraction ─────────────────────────────────────────────────────────

def extract_strings(data: bytes, min_len: int = 4) -> list[str]:
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


# ── Crypto constant scan ──────────────────────────────────────────────────────

def scan_crypto_constants(data: bytes) -> list[dict]:
    findings = []

    # Search for AES S-box
    if data.find(AES_SBOX) != -1:
        findings.append({"algorithm": "AES", "indicator": "S-box (forward)", "confidence": "high"})
    if data.find(AES_INV_SBOX) != -1:
        findings.append({"algorithm": "AES", "indicator": "Inverse S-box", "confidence": "high"})

    # Search for MD5 IV (little-endian: A=0x67452301 → 01 23 45 67 ...)
    if data.find(MD5_IV) != -1:
        findings.append({"algorithm": "MD5", "indicator": "Initialization Vector (LE)", "confidence": "high"})

    # Search for SHA-1 IV (big-endian: A=0x67452301 → 67 45 23 01 ...)
    if data.find(SHA1_IV) != -1:
        findings.append({"algorithm": "SHA-1", "indicator": "Initialization Vector (BE)", "confidence": "high"})

    # Search for SHA-256 IV (big-endian, first 8 words)
    if data.find(SHA256_IV) != -1:
        findings.append({"algorithm": "SHA-256", "indicator": "Initialization Vector (BE)", "confidence": "high"})

    # Search for TEA delta
    tea_bytes = struct.pack("<I", TEA_DELTA)
    count = data.count(tea_bytes)
    if count > 0:
        findings.append({"algorithm": "TEA/XTEA", "indicator": f"Delta constant 0x9E3779B9 (found {count}x)", "confidence": "medium"})

    # Search for base64 alphabet
    base64_std = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    base64_url = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    if base64_std in data:
        findings.append({"algorithm": "Base64", "indicator": "Standard alphabet string", "confidence": "high"})
    if base64_url in data:
        findings.append({"algorithm": "Base64", "indicator": "URL-safe alphabet string", "confidence": "high"})

    # Search for CRC-32 table entries (partial match on first 32 bytes of table)
    # Simple check: look for repeated occurrences of CRC32_POLY in data
    crc_poly_bytes = struct.pack("<I", CRC32_POLY)
    count = data.count(crc_poly_bytes)
    if count >= 3:
        findings.append({"algorithm": "CRC-32", "indicator": f"Polynomial 0xEDB88320 (found {count}x)", "confidence": "low"})

    return findings


# ── Packer detection ──────────────────────────────────────────────────────────

def detect_packer(data: bytes, file_type: str) -> dict:
    result = {"packed": False, "indicators": [], "confidence": "low"}

    if "PE" not in file_type:
        return result

    # Check section names
    data_str = data[:65536]  # First 64k usually enough for headers
    for section in PACKER_SECTIONS:
        if section.encode() in data_str:
            result["packed"] = True
            result["indicators"].append(f"Known packer section: {section}")
            result["confidence"] = "high"

    # Check entropy of first executable section
    # Simplified: check overall entropy
    ent = shannon_entropy(data)
    if ent > 7.5:
        result["packed"] = True
        result["indicators"].append(f"Very high entropy ({ent:.1f}) — likely packed/encrypted")
        result["confidence"] = "high"
    elif ent > 7.0:
        result["indicators"].append(f"High entropy ({ent:.1f}) — possibly packed")
        result["confidence"] = "medium"
        if not result["packed"]:
            result["packed"] = True

    return result


# ── Anti-analysis string detection ────────────────────────────────────────────

def scan_anti_analysis(strings: list[str]) -> list[str]:
    found = []
    for s in strings:
        for pattern in ANTI_ANALYSIS_STRINGS:
            if pattern.lower() in s.lower():
                found.append(s)
                break
    return sorted(set(found))


# ── Suspicious API detection ──────────────────────────────────────────────────

def scan_suspicious_api(strings: list[str]) -> dict[str, list[str]]:
    by_category = defaultdict(list)
    categories = {
        "injection": ["CreateRemoteThread", "WriteProcessMemory", "VirtualAllocEx",
                       "NtCreateThreadEx", "QueueUserAPC", "SetThreadContext"],
        "persistence": ["RegSetValue", "CreateService", "OpenSCManager",
                         "Schedule.Service", "CurrentVersion\\Run"],
        "execution": ["WinExec", "ShellExecute", "CreateProcess", "system(", "popen("],
        "network": ["HttpSend", "URLDownload", "WinHTTP", "WinInet",
                     "socket(", "connect(", "WSASocket", "InternetConnect"],
        "credential": ["CryptUnprotectData", "lsass", "sekurlsa", "SamEnumerateUsers"],
        "evasion": ["IsDebuggerPresent", "NtQueryInformationProcess", "GetTickCount",
                     "VirtualProtect", "NtUnmapViewOfSection", "SetErrorMode"],
        "crypto": CRYPTO_IMPORTS,
    }
    for s in strings:
        for cat, patterns in categories.items():
            for p in patterns:
                if p.lower() in s.lower():
                    by_category[cat].append(s)
                    break
    return dict(by_category)


# ── YARA rule generation ──────────────────────────────────────────────────────

def generate_yara_rule(data: bytes, strings: list[str], sample_name: str,
                       hashes: dict, file_type: str) -> str:
    """Generate a basic YARA rule from unique strings and patterns."""
    safe_name = "".join(c if c.isalnum() or c == "_" else "_" for c in sample_name)
    safe_name = safe_name[:40]

    # Pick the most unique strings (longest, least common)
    uniq_strings = sorted(set(s for s in strings if len(s) >= 8), key=len, reverse=True)[:10]

    string_decls = []
    for i, s in enumerate(uniq_strings):
        escaped = s.replace("\\", "\\\\").replace('"', '\\"')
        string_decls.append(f'        $s{i} = "{escaped}" ascii wide')

    # Add hex pattern for file type magic
    magic_hex = data[:4].hex()
    string_decls.insert(0, f'        $magic = {{ {magic_hex} }}')

    rule = f"""rule AutoGen_{safe_name} {{
    meta:
        description = "Auto-generated for {sample_name}"
        date = "{datetime.now(timezone.utc).strftime('%Y-%m-%d')}"
        sha256 = "{hashes['sha256']}"
        file_type = "{file_type}"

    strings:
{chr(10).join(string_decls)}

    condition:
        $magic at 0 and any of ($s*)
}}
"""
    return rule


def _normalize_rating(rating: str) -> str:
    """Normalize report_from_triage rating vocabulary to lowercase tokens
    (e.g. 'Medium' -> 'medium', 'Low/Medium' -> 'low/medium', 'Unknown/Low' -> 'unknown/low')."""
    return rating.strip().lower().replace(" ", "")


# ── Main analysis ─────────────────────────────────────────────────────────────

def analyze_artifact(path: str, out_dir: str, skip_yara: bool = False,
                     quick: bool = False) -> dict:
    sample_name = Path(path).name
    file_size = os.path.getsize(path)

    # Load data (limit for quick mode)
    max_read = 2 * 1024 * 1024 if quick else 100 * 1024 * 1024  # 2MB vs 100MB
    data = read_file(path, max_size=max_read)

    # Base analysis
    hashes = compute_hashes(data)
    file_type = identify_file_type(data, path)
    entropy = shannon_entropy(data)
    strings = extract_strings(data)
    crypto = scan_crypto_constants(data)
    packer = detect_packer(data, file_type["type"])
    anti_analysis = scan_anti_analysis(strings)
    suspicious_api = scan_suspicious_api(strings)

    # ── Deep structural analysis (integrated sibling parsers) ────────────
    # Structural parsers require the COMPLETE file bytes; they silently
    # tolerate truncation (boundary `break` without error), so feeding them
    # the truncated `data` above would yield misleading partial reports.
    # Read the full file separately (HARD_LIMIT-capped by common.io_utils).
    deep_analysis: dict = {}
    ftype = file_type["type"]
    ext = Path(path).suffix.lower()
    try:
        from common.io_utils import read_file as _io_read_file
        full_data = _io_read_file(path, allow_truncation=False)
    except Exception as e:                       # FileTooLargeError / OSError
        full_data = None
        deep_analysis["_error"] = f"deep analysis: cannot read full file ({e})"
    if full_data is not None:
        try:
            if "ELF" in ftype:
                from elf_deep_scan import parse_elf
                deep_analysis["elf"] = parse_elf(full_data, path)
            elif "Mach-O" in ftype:
                from macho_scan import parse_macho
                deep_analysis["macho"] = parse_macho(full_data, path)
            elif ext == ".dex" or data[:4] == b"dex\n":
                from dex_analyze import parse_dex
                deep_analysis["dex"] = parse_dex(full_data, path)
            elif ftype.startswith(".NET") or file_type.get("is_dotnet"):
                from dotnet_analyze import parse_dotnet
                deep_analysis["dotnet"] = parse_dotnet(full_data, path)
            elif "PE" in ftype and not file_type.get("is_dotnet"):
                from pe_deep_scan import analyze_pe
                deep_analysis["pe"] = analyze_pe(path)
            elif ext == ".apk" or "APK" in ftype:
                from apk_deep_scan import analyze_apk
                deep_analysis["apk"] = analyze_apk(path)
        except Exception as exc:  # noqa: BLE001 — isolation: never kill pipeline
            deep_analysis["_error"] = f"deep analysis failed: {exc}"

    # ── Triage enrichment (generic offline indicators + workflow hints) ──
    # RE correctness: indicators must scan the COMPLETE file's strings, not the
    # truncated `data`. Reuse `full_data` (same complete read as deep_analysis);
    # fall back to `data` only when the full read was unavailable.
    triage: dict = {}
    try:
        from triage_artifact import indicators as _triage_indicators, \
            profile_and_tools as _profile_and_tools
        _triage_src = full_data if full_data is not None else data
        _triage_strings = extract_strings(_triage_src)
        triage["triage_indicators"] = _triage_indicators(_triage_strings)
        _profiles, _tools, _steps = _profile_and_tools(
            Path(path), [file_type["type"]], triage["triage_indicators"])
        triage["recommended_profiles"] = _profiles
        triage["recommended_tools"] = _tools
        triage["recommended_next_steps"] = _steps
    except Exception as exc:  # noqa: BLE001 — isolation: never kill pipeline
        triage["_error"] = f"triage enrichment failed: {exc}"

    # ── Risk hint (report_from_triage.risk_hint, adapter-reuse) ────────────
    # Three-auditor decision: the multi-file aggregation logic in
    # report_from_triage is NOT integrated — it is orthogonal to the single-file
    # pipeline and stays a standalone tool. Only the `risk_hint` helper is
    # reused here, behind an adapter mapping our schema onto its expected keys
    # (entropy -> prefix_entropy, triage.triage_indicators -> indicators,
    # identification.type -> magic_hints). Isolated so a missing module or
    # schema drift never kills the pipeline.
    risk_hint: dict = {}
    try:
        from report_from_triage import risk_hint as _risk_hint
        _triage_inds = triage.get("triage_indicators") or {} \
            if isinstance(triage, dict) else {}
        _adapter = {
            "prefix_entropy": entropy,
            "indicators": _triage_inds,
            "magic_hints": [file_type["type"]],
        }
        _rating, _reasons = _risk_hint(_adapter)
        risk_hint = {
            "rating": _normalize_rating(_rating),
            "rating_raw": _rating,
            "reasons": _reasons,
        }
    except Exception as exc:  # noqa: BLE001 — isolation: never kill pipeline
        risk_hint["_error"] = f"risk hint failed: {exc}"

    # ── Crypto deep scan (find_crypto, supplemental) ───────────────────────
    # RE: scan COMPLETE file via find_crypto's richer constant DB; keep the
    # inline `crypto_indicators` untouched and add a parallel `crypto_scan`.
    # `find_high_entropy_regions` is O(n) windowed — cap its input to avoid
    # pathological slowdown on very large files (constants scan uses full file).
    crypto_scan: dict = {}
    try:
        from find_crypto import scan_constants as _fc_constants, \
            scan_imports as _fc_imports, \
            find_high_entropy_regions as _fc_entropy
        _fc_src = full_data if full_data is not None else data
        _entropy_src = _fc_src[:50 * 1024 * 1024]
        crypto_scan["constants"] = _fc_constants(_fc_src)
        crypto_scan["imports"] = _fc_imports(_fc_src)
        crypto_scan["high_entropy_regions"] = _fc_entropy(_entropy_src)
    except Exception as exc:  # noqa: BLE001 — isolation: never kill pipeline
        crypto_scan["_error"] = f"crypto scan failed: {exc}"

    # ── Ghidra availability (ghidra_headless.detect_ghidra, env capability) ──
    # Three-auditor decision: the analyzeHeadless *subprocess* logic in
    # ghidra_headless is NOT integrated (heavyweight external tool, blocking,
    # orthogonal to offline single-file analysis). Only the cheap, dependency-
    # free environment detection `detect_ghidra()` is surfaced so the analyst
    # knows whether deeper decompilation is locally available. Isolated so a
    # missing module never kills the pipeline.
    ghidra_status: dict = {}
    try:
        from ghidra_headless import detect_ghidra as _detect_ghidra
        ghidra_status = _detect_ghidra()
    except Exception as exc:  # noqa: BLE001 — isolation: never kill pipeline
        ghidra_status = {"error": f"ghidra detection failed: {exc}"}

    # ── ROP gadget enumeration (rop_finder, executables only) ─────────────
    # RE: enumerate ROP gadgets from executable sections. capstone is optional
    # (categorize_gadgets falls back to 'uncategorized' on ImportError). Input
    # is capped at 50MB to avoid pathological slowdown on large binaries.
    rop_gadgets: dict = {}
    _exec_types = ("PE", "ELF", "Mach-O")
    if any(t in ftype for t in _exec_types):
        try:
            from rop_finder import (
                detect_arch as _rf_arch,
                extract_executable_sections as _rf_sections,
                find_gadgets as _rf_find,
                categorize_gadgets as _rf_categorize,
            )
            _rf_src = (full_data if full_data is not None else data)[:50 * 1024 * 1024]
            _rf_arch_name = _rf_arch(_rf_src, path)
            _rf_sections = _rf_sections(_rf_src, _rf_arch_name)
            _rf_all = []
            for _vaddr, _sec in _rf_sections:
                _rf_all.extend(_rf_find(_sec, _vaddr, _rf_arch_name, 5, {"00"}))
            _rf_cat = _rf_categorize(_rf_all, _rf_arch_name)
            rop_gadgets = {
                "arch": _rf_arch_name,
                "sections": len(_rf_sections),
                "total_gadgets": len(_rf_all),
                "categories": {
                    cat: {
                        "count": len(glist),
                        "samples": [
                            {"vaddr": g.get("vaddr_hex"),
                             "text": g.get("text", g.get("bytes"))}
                            for g in glist[:3]
                        ],
                    }
                    for cat, glist in _rf_cat.items()
                },
            }
        except Exception as exc:  # noqa: BLE001 — isolation: never kill pipeline
            rop_gadgets = {"error": f"rop scan failed: {exc}"}

    # ── Obfuscated-string detection (shellcode_tools.find_obfuscated_strings) ─
    # RE: detect stack-string / XOR-decode / encrypted-table obfuscation
    # patterns. Pure heuristic, no external deps. Scans the complete file.
    obfuscation_findings: list = []
    try:
        from shellcode_tools import find_obfuscated_strings as _sc_obf
        _obf_src = full_data if full_data is not None else data
        obfuscation_findings = _sc_obf(_obf_src)
    except Exception as exc:  # noqa: BLE001 — isolation: never kill pipeline
        obfuscation_findings = [{"error": f"obfuscation scan failed: {exc}"}]

    # ── Debugger script generation (debugger_bridge, PE only, conditional) ──
    # Three-auditor decision: debugger_bridge *generates* x64dbg automation
    # scripts (offline, no external dep) but does NOT execute them. We pre-
    # generate recommended scripts for PE when packing/anti-debug signals are
    # present, saving them to the output dir and recording references. The
    # actual x64dbg run remains a manual, standalone step.
    debugger_scripts: dict = {}
    if "PE" in ftype:
        try:
            from debugger_bridge import render_template as _dbg_render, \
                TEMPLATES as _dbg_tpls
            _dbg_want = []
            if packer.get("packed"):
                _dbg_want += ["unpack_esp", "memory_dump"]
            if anti_analysis:
                _dbg_want += ["anti_debug_bypass", "breakpoint_trace"]
            _dbg_want = list(dict.fromkeys(_dbg_want))
            if _dbg_want:
                _dbg_generated = []
                os.makedirs(out_dir, exist_ok=True)
                for _tpl in _dbg_want:
                    if _tpl not in _dbg_tpls:
                        continue
                    _script = _dbg_render(_tpl, path)
                    _out_name = f"debugger_{_tpl}_{Path(path).stem}.py"
                    _out_path = os.path.join(out_dir, _out_name)
                    with open(_out_path, "w", encoding="utf-8") as _df:
                        _df.write(_script)
                    _dbg_generated.append({
                        "template": _tpl,
                        "title": _dbg_tpls[_tpl]["title"],
                        "requires": _dbg_tpls[_tpl]["requires"],
                        "script_path": _out_path,
                    })
                if _dbg_generated:
                    debugger_scripts = {
                        "generated": _dbg_generated,
                        "note": ("Scripts require x64dbg + x64dbg_automate to run; "
                                 "generated offline."),
                    }
        except Exception as exc:  # noqa: BLE001 — isolation: never kill pipeline
            debugger_scripts = {"error": f"debugger script generation failed: {exc}"}

    # YARA rule (supplemental: prefer the richer yara_gen generator)
    yara_rule = None
    yara_meta: dict = {}
    if not skip_yara:
        try:
            from yara_gen import (
                extract_strings as _yg_extract,
                extract_unicode_strings as _yg_unicode,
                select_strings as _yg_select,
                extract_byte_patterns as _yg_patterns,
                generate_rule as _yg_generate,
            )
            _yg_src = full_data if full_data is not None else data
            _yg_ascii = _yg_extract(_yg_src)
            _yg_uni = _yg_unicode(_yg_src)
            _yg_selected = _yg_select(_yg_ascii + _yg_uni)
            _yg_patterns = _yg_patterns(_yg_src)
            yara_rule = _yg_generate(path, _yg_selected, _yg_patterns,
                                     hashes, file_type["type"])
            yara_meta = {
                "generator": "yara_gen",
                "strings_selected": len(_yg_selected),
                "byte_patterns": len(_yg_patterns),
            }
        except Exception as exc:  # noqa: BLE001 — isolation: never kill pipeline
            yara_rule = generate_yara_rule(data, strings, sample_name, hashes,
                                           file_type["type"])
            yara_meta = {"generator": "inline_fallback", "error": str(exc)}

    # Entropy by segments (first 256 bytes each for large files)
    segment_entropy = {}
    if len(data) > 256:
        seg_count = min(8, len(data) // 256)
        for i in range(seg_count):
            seg = data[i*256:(i+1)*256]
            segment_entropy[f"offset_{i*256:#x}"] = round(shannon_entropy(seg), 2)

    result = {
        "analysis_time": datetime.now(timezone.utc).isoformat(),
        "sample": {
            "name": sample_name,
            "path": os.path.abspath(path),
            "size": file_size,
            "size_human": format_size(file_size),
            "hashes": hashes,
        },
        "identification": file_type,
        "entropy": round(entropy, 3),
        "entropy_segments": segment_entropy,
        "strings": {
            "count": len(strings),
            "ascii_count": len([s for s in strings if all(0x20 <= ord(c) <= 0x7E for c in s)]),
            "top_20": sorted(set(strings), key=len, reverse=True)[:20],
        },
        "crypto_indicators": crypto,
        "packer_detection": packer,
        "anti_analysis_strings": anti_analysis,
        "suspicious_api_calls": {k: v[:5] for k, v in suspicious_api.items()},  # top 5 per category
        "risk_indicators": [],
        "deep_analysis": deep_analysis,
        "triage": triage,
        "crypto_scan": crypto_scan,
        "risk_hint": risk_hint,
        "yara_rule_meta": yara_meta,
        "ghidra_status": ghidra_status,
        "rop_gadgets": rop_gadgets,
        "obfuscation_findings": obfuscation_findings,
        "debugger_scripts": debugger_scripts,
    }

    # Build risk indicators
    if packer["packed"]:
        result["risk_indicators"].append({
            "level": "medium", "category": "packing",
            "detail": f"Binary appears packed: {'; '.join(packer['indicators'])}"
        })
    if anti_analysis:
        result["risk_indicators"].append({
            "level": "medium", "category": "anti_analysis",
            "detail": f"Found {len(anti_analysis)} anti-analysis indicators"
        })
    if suspicious_api.get("injection"):
        result["risk_indicators"].append({
            "level": "high", "category": "process_injection",
            "detail": f"Process injection APIs: {', '.join(suspicious_api['injection'][:3])}"
        })
    if suspicious_api.get("persistence"):
        result["risk_indicators"].append({
            "level": "medium", "category": "persistence",
            "detail": "Persistence mechanisms detected"
        })

    # Save results
    os.makedirs(out_dir, exist_ok=True)

    json_path = os.path.join(out_dir, "auto_analysis.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)

    md_path = os.path.join(out_dir, "auto_analysis.md")
    write_markdown_report(result, md_path)

    if yara_rule:
        yara_path = os.path.join(out_dir, f"{sample_name}.yar")
        with open(yara_path, "w", encoding="utf-8") as f:
            f.write(yara_rule)

    print(f"[+] Analysis complete: {json_path}")
    print(f"[+] Report: {md_path}")
    if yara_rule:
        print(f"[+] YARA rule: {yara_path}")

    return result


def format_size(size: int) -> str:
    for unit in ["B", "KB", "MB", "GB"]:
        if size < 1024:
            return f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TB"


def write_markdown_report(result: dict, path: str):
    s = result["sample"]
    i = result["identification"]

    lines = [
        f"# Auto Analysis Report: {s['name']}",
        "",
        f"**Analysis time:** {result['analysis_time']}",
        "",
        "## Sample Information",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| File | {s['name']} |",
        f"| Size | {s['size_human']} ({s['size']:,} bytes) |",
        f"| Type | {i['type']} |",
        f"| MD5 | {s['hashes']['md5']} |",
        f"| SHA-1 | {s['hashes']['sha1']} |",
        f"| SHA-256 | {s['hashes']['sha256']} |",
        f"| Entropy | {result['entropy']:.3f} |",
        "",
        "## Identification",
        "",
        f"- **Magic bytes:** `{i['magic_hex']}`",
        f"- **Extension:** {i['extension']}",
        f"- **Detected type:** {i['type']}",
    ]

    if result.get("packer_detection", {}).get("packed"):
        p = result["packer_detection"]
        lines += [
            "",
            "## Packer/Protector Detection",
            "",
            f"- **Confidence:** {p['confidence']}",
        ]
        for ind in p["indicators"]:
            lines.append(f"- {ind}")

    if result.get("crypto_indicators"):
        lines += [
            "",
            "## Cryptographic Indicators",
        ]
        for c in result["crypto_indicators"]:
            lines.append(f"- **{c['algorithm']}** ({c['confidence']}): {c['indicator']}")

    if result.get("risk_indicators"):
        lines += [
            "",
            "## Risk Indicators",
        ]
        for r in result["risk_indicators"]:
            lines.append(f"- **[{r['level'].upper()}]** {r['category']}: {r['detail']}")

    if result.get("anti_analysis_strings"):
        lines += [
            "",
            f"## Anti-Analysis Indicators ({len(result['anti_analysis_strings'])})",
        ]
        for a in result["anti_analysis_strings"][:20]:
            lines.append(f"- `{a}`")

    lines += [
        "",
        "## Strings Overview",
        "",
        f"- Total strings extracted: {result['strings']['count']}",
        "",
        "### Longest unique ASCII strings:",
    ]
    for s_text in result["strings"]["top_20"][:20]:
        truncated = s_text[:100] + "..." if len(s_text) > 100 else s_text
        lines.append(f"- `{truncated}`")

    da = result.get("deep_analysis")
    if da:
        lines += [
            "",
            "## Deep Structural Analysis",
            "",
        ]
        if da.get("_error"):
            lines.append(f"- **Error:** {da['_error']}")
        for k, v in da.items():
            if k == "_error":
                continue
            if isinstance(v, dict) and v.get("error"):
                lines.append(f"- **{k}:** parse error — {v['error']}")
            else:
                lines.append(f"- **{k}:** parsed successfully")

    # Deep crypto scan (find_crypto)
    cs = result.get("crypto_scan")
    if cs:
        if cs.get("_error"):
            lines += ["", "## Deep Crypto Scan (find_crypto)", "",
                      f"- **Error:** {cs['_error']}"]
        elif cs.get("constants") or cs.get("imports") or cs.get("high_entropy_regions"):
            lines += ["", "## Deep Crypto Scan (find_crypto)", ""]
            for c in cs.get("constants", []):
                lines.append(f"- **{c.get('algorithm')}** ({c.get('confidence')}): "
                             f"{c.get('description')} @ {c.get('offset_hex')} "
                             f"(x{c.get('occurrences')})")
            for api in cs.get("imports", []):
                lines.append(f"- crypto API (weak): `{api}`")
            for r in cs.get("high_entropy_regions", [])[:20]:
                lines.append(f"- High-entropy @ {r.get('offset_hex')} "
                             f"({r.get('size')}B, {r.get('entropy')})")

    # Triage enrichment summary
    tr = result.get("triage")
    if tr:
        lines += ["", "## Triage & Next Steps", ""]
        if tr.get("_error"):
            lines.append(f"- **Error:** {tr['_error']}")
        inds = tr.get("triage_indicators") or {}
        for key in ("urls", "ipv4", "emails", "registry", "windows_paths",
                    "unix_paths", "suspicious_terms"):
            vals = inds.get(key)
            if vals:
                shown = vals if isinstance(vals, list) else [vals]
                preview = ", ".join(str(v) for v in shown[:5])
                lines.append(f"- **{key}:** {len(shown)} found"
                             + (f" — {preview}" if preview else ""))
        steps = tr.get("recommended_next_steps") or []
        if steps:
            lines.append("- **Recommended next steps:**")
            for s in steps[:10]:
                lines.append(f"  - {s}")

    # Risk hint (report_from_triage.risk_hint adapter)
    rh = result.get("risk_hint")
    if rh:
        if rh.get("_error"):
            lines += ["", "## Risk Hint", "", f"- **Error:** {rh['_error']}"]
        elif rh.get("rating") or rh.get("reasons"):
            lines += [
                "", "## Risk Hint", "",
                f"- **Rating:** {rh.get('rating', 'unknown')}",
                "- **Reasons:**",
            ]
            for reason in rh.get("reasons", []):
                lines.append(f"  - {reason}")

    # YARA rule reference (generator + output file)
    yr = result.get("yara_rule_meta")
    if yr:
        lines += [
            "", "## YARA Rule", "",
            f"- **Generated by:** {yr.get('generator')}",
            f"- **Output file:** `{result['sample']['name']}.yar`",
        ]
        if yr.get("generator") == "yara_gen":
            lines.append(f"- **Strings selected:** {yr.get('strings_selected')}")
            lines.append(f"- **Byte patterns:** {yr.get('byte_patterns')}")
        if yr.get("error"):
            lines.append(f"- **Fallback error:** {yr['error']}")

    # Ghidra availability (ghidra_headless.detect_ghidra)
    gh = result.get("ghidra_status")
    if gh:
        if gh.get("error"):
            lines += ["", "## Ghidra Availability", "", f"- **Error:** {gh['error']}"]
        else:
            lines += [
                "", "## Ghidra Availability", "",
                f"- **Installed:** {'yes' if gh.get('installed') else 'no'}",
                f"- **analyzeHeadless:** {gh.get('analyzeHeadless') or 'N/A'}",
                f"- **Java available:** {'yes' if gh.get('java') else 'no'}",
            ]
            if gh.get("error"):
                lines.append(f"- **Note:** {gh['error']}")

    # ROP gadgets (rop_finder)
    rg = result.get("rop_gadgets")
    if rg:
        if rg.get("error"):
            lines += ["", "## ROP Gadgets", "", f"- **Error:** {rg['error']}"]
        elif rg.get("total_gadgets") is not None:
            lines += [
                "", "## ROP Gadgets", "",
                f"- **Architecture:** {rg.get('arch')}",
                f"- **Executable sections:** {rg.get('sections')}",
                f"- **Total gadgets:** {rg.get('total_gadgets')}",
            ]
            for cat, info in rg.get("categories", {}).items():
                lines.append(f"- **{cat}:** {info['count']}")
                for s in info.get("samples", [])[:3]:
                    lines.append(f"  - `{s.get('vaddr')}`: {s.get('text')}")

    # Obfuscation findings (shellcode_tools.find_obfuscated_strings)
    obf = result.get("obfuscation_findings")
    if obf:
        if isinstance(obf, list) and obf and obf[0].get("error"):
            lines += ["", "## Obfuscation Findings", "",
                      f"- **Error:** {obf[0]['error']}"]
        else:
            lines += ["", "## Obfuscation Findings", ""]
            if not obf:
                lines.append("- No obfuscation patterns detected")
            for f_item in obf:
                lines.append(f"- **[{f_item.get('type')}]** "
                             f"({f_item.get('confidence')}): {f_item.get('detail')}")

    # Debugger scripts (debugger_bridge)
    dbs = result.get("debugger_scripts")
    if dbs:
        if dbs.get("error"):
            lines += ["", "## Debugger Scripts", "", f"- **Error:** {dbs['error']}"]
        elif dbs.get("generated"):
            lines += ["", "## Debugger Scripts (generated offline)", "",
                      f"- {dbs.get('note')}"]
            for g in dbs["generated"]:
                lines.append(f"- **{g['template']}** ({g['title']}) "
                             f"-> `{os.path.basename(g['script_path'])}`")
                lines.append(f"  - requires: {', '.join(g['requires'])}")

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="auto_analyze — one-click comprehensive binary analysis")
    parser.add_argument("artifact", help="Path to the artifact to analyze")
    parser.add_argument("--out", "-o", default="./auto_analysis",
                        help="Output directory (default: ./auto_analysis)")
    parser.add_argument("--skip-yara", action="store_true",
                        help="Skip YARA rule generation")
    parser.add_argument("--json", action="store_true",
                        help="Print JSON to stdout")
    parser.add_argument("--quick", action="store_true",
                        help="Quick mode: only read first 2MB")

    args = parser.parse_args()

    if not os.path.exists(args.artifact):
        print(f"Error: file not found: {args.artifact}", file=sys.stderr)
        sys.exit(1)

    result = analyze_artifact(args.artifact, args.out, args.skip_yara, args.quick)

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
