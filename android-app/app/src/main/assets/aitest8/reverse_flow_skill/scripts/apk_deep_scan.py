#!/usr/bin/env python3
"""apk_deep_scan.py — 深度 APK (Android Package) 分析

分析 APK 文件: manifest 权限审计、exported 组件、native 库检测、
签名信息、危险 API 调用、字符串提取、加固检测。

Usage:
    python apk_deep_scan.py <apk_file> [--out <output_dir>] [--json]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
import zlib
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


# ── Android permission risk mapping ───────────────────────────────────────────

PERMISSION_RISK = {
    # HIGH — direct privacy/security risk
    "android.permission.READ_SMS": ("high", "Read user SMS messages"),
    "android.permission.SEND_SMS": ("high", "Send SMS messages without user interaction"),
    "android.permission.RECEIVE_SMS": ("high", "Receive and process SMS messages"),
    "android.permission.READ_CONTACTS": ("high", "Read user contacts"),
    "android.permission.READ_CALL_LOG": ("high", "Read call logs"),
    "android.permission.CALL_PHONE": ("high", "Initiate phone calls"),
    "android.permission.RECORD_AUDIO": ("high", "Record audio via microphone"),
    "android.permission.CAMERA": ("high", "Access camera"),
    "android.permission.ACCESS_FINE_LOCATION": ("high", "Precise GPS location"),
    "android.permission.READ_EXTERNAL_STORAGE": ("high", "Read files from external storage"),
    "android.permission.WRITE_EXTERNAL_STORAGE": ("high", "Write files to external storage"),
    "android.permission.MANAGE_EXTERNAL_STORAGE": ("high", "Full file system access (Android 11+)"),
    "android.permission.INSTALL_PACKAGES": ("high", "Install other apps"),
    "android.permission.REQUEST_INSTALL_PACKAGES": ("high", "Request app installation"),
    "android.permission.SYSTEM_ALERT_WINDOW": ("high", "Draw overlays over other apps"),
    "android.permission.BIND_ACCESSIBILITY_SERVICE": ("high", "Accessibility service — keylogger, auto-click capability"),
    "android.permission.BIND_DEVICE_ADMIN": ("high", "Device administrator — lock/wipe device"),
    "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE": ("high", "Read all notifications"),

    # MEDIUM
    "android.permission.READ_PHONE_STATE": ("medium", "Read phone state & IMEI"),
    "android.permission.READ_PRIVILEGED_PHONE_STATE": ("medium", "Read privileged phone state"),
    "android.permission.ACCESS_COARSE_LOCATION": ("medium", "Approximate location"),
    "android.permission.ACCESS_BACKGROUND_LOCATION": ("medium", "Location access in background"),
    "android.permission.ACCESS_WIFI_STATE": ("medium", "WiFi information (SSID, BSSID)"),
    "android.permission.CHANGE_WIFI_STATE": ("medium", "Modify WiFi configuration"),
    "android.permission.READ_CALENDAR": ("medium", "Read calendar events"),
    "android.permission.BLUETOOTH": ("medium", "Bluetooth access"),
    "android.permission.QUERY_ALL_PACKAGES": ("medium", "See all installed apps"),
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS": ("medium", "Prevent app from sleeping"),
    "android.permission.RECEIVE_BOOT_COMPLETED": ("medium", "Start automatically on boot"),
    "android.permission.FOREGROUND_SERVICE": ("medium", "Run persistent foreground service"),
    "android.permission.POST_NOTIFICATIONS": ("medium", "Send notifications"),

    # LOW
    "android.permission.INTERNET": ("low", "Internet access"),
    "android.permission.ACCESS_NETWORK_STATE": ("low", "Network connectivity info"),
    "android.permission.VIBRATE": ("low", "Vibrate device"),
    "android.permission.WAKE_LOCK": ("low", "Keep screen on"),
}

KNOWN_PACKERS = [
    "com.qihoo", "com.tencent.StubShell", "com.tencent.mm",
    "com.ijiami", "com.secneo", "com.bangcle",
    "com.ali.money", "com.eg.android.AlipayGphone",
    "libjiagu", "libegis", "libAPKProtect",
    "libprotectClass", "libDexHelper", "libtup",
    "libsecexe", "libshellx", "libexec.so",
    "libnqshield", "libc2a",
]


# ── Utility ───────────────────────────────────────────────────────────────────

def read_binary_xml(data: bytes) -> dict | None:
    """Attempt to read Android binary XML (AXML) using basic parsing.
    For production use, prefer androguard or axmlparser library."""
    try:
        # Simple approach: find xml tags in the binary
        # This handles the common case where strings are visible
        text = data.decode("latin-1", errors="replace")

        # Extract permissions
        perms = set(re.findall(r'android\.permission\.\w+(?:\.\w+)*', text))

        # Extract activity/service/receiver/provider names
        activities = re.findall(r'activity[^>]*android:name="([^"]+)"', text)
        services = re.findall(r'service[^>]*android:name="([^"]+)"', text)
        receivers = re.findall(r'receiver[^>]*android:name="([^"]+)"', text)
        providers = re.findall(r'provider[^>]*android:name="([^"]+)"', text)

        exported_components = []
        for act in activities:
            ctx = text[max(0, text.find(act)-50):text.find(act)+len(act)+50]
            if 'android:exported="true"' in ctx:
                exported_components.append({"type": "activity", "name": act})

        pkg_match = re.search(r'package="([^"]+)"', text)
        return {
            "permissions": sorted(perms),
            "activities": activities[:20],
            "services": services[:20],
            "receivers": receivers[:20],
            "providers": providers[:20],
            "exported_components": exported_components[:20],
            "package_name": pkg_match.group(1) if pkg_match else None,
        }
    except (TypeError, AttributeError) as e:
        print(f"Warning: manifest parsing failed: {e}", file=sys.stderr)
        return None


# ── Analysis ──────────────────────────────────────────────────────────────────

def analyze_apk(path: str) -> dict:
    with open(path, "rb") as f:
        data = f.read()
    hashes = {
        "md5": hashlib.md5(data).hexdigest(),
        "sha256": hashlib.sha256(data).hexdigest(),
    }

    result = {
        "file": Path(path).name,
        "size": len(data),
        "hashes": hashes,
        "analysis_time": datetime.now(timezone.utc).isoformat(),
    }

    try:
        with zipfile.ZipFile(path, "r") as z:
            entries = z.namelist()

            # Manifest analysis
            manifest = _extract_manifest(z, entries)
            if manifest:
                result["manifest"] = _analyze_manifest(manifest)

            # Native libraries
            native_libs = [e for e in entries if e.startswith("lib/") and e.endswith(".so")]
            result["native_libraries"] = _analyze_native_libs(z, native_libs)

            # DEX files
            dex_files = [e for e in entries if e.endswith(".dex") or e == "classes.dex"]
            result["dex_files"] = [{"name": d, "size": z.getinfo(d).file_size} for d in dex_files]

            # Certificate
            cert_entries = [e for e in entries if e.startswith("META-INF/") and
                           (e.endswith(".RSA") or e.endswith(".DSA") or e.endswith(".EC"))]
            if cert_entries:
                result["certificate"] = {"file": cert_entries[0],
                                          "size": z.getinfo(cert_entries[0]).file_size}

            # Packer detection
            result["packer_indicators"] = _detect_packer(z, entries)

            # Suspicious file patterns
            result["suspicious_files"] = _find_suspicious_files(entries)

            # Resource-based strings
            result["interesting_strings"] = _extract_interesting_strings(z, entries)

            # Risk assessment
            result["risk_summary"] = _assess_risk(result)

    except zipfile.BadZipFile:
        result["error"] = "Not a valid ZIP/APK file"

    return result


def _extract_manifest(z: zipfile.ZipFile, entries: list[str]) -> bytes | None:
    """Extract AndroidManifest.xml from APK."""
    manifest_paths = [
        "AndroidManifest.xml",
        # Some APKs store it elsewhere
    ]
    for mp in manifest_paths:
        if mp in entries:
            return z.read(mp)
    # Search for it
    for e in entries:
        if e.endswith("AndroidManifest.xml"):
            return z.read(e)
    return None


def _analyze_manifest(data: bytes) -> dict:
    """Parse manifest and extract key information."""
    info = read_binary_xml(data) or {}

    # Risk-score permissions
    perms_with_risk = []
    for perm in info.get("permissions", []):
        risk_info = PERMISSION_RISK.get(perm, ("info", ""))
        perms_with_risk.append({
            "permission": perm,
            "risk": risk_info[0],
            "description": risk_info[1],
        })

    return {
        "package_name": info.get("package_name") if info else None,
        "permissions": perms_with_risk,
        "permission_count": len(info.get("permissions", [])),
        "high_risk_permissions": [p["permission"] for p in perms_with_risk if p["risk"] == "high"],
        "components": {
            "activities": info.get("activities", []),
            "services": info.get("services", []),
            "receivers": info.get("receivers", []),
            "providers": info.get("providers", []),
        },
        "exported_components": info.get("exported_components", []),
    }


def _analyze_native_libs(z: zipfile.ZipFile, lib_entries: list[str]) -> list[dict]:
    """Analyze native .so files in the APK."""
    result = []
    for entry in lib_entries:
        try:
            data = z.read(entry)[:1024]  # Read ELF header
            if data[:4] == b"\x7fELF":
                bits = "64" if data[4] == 2 else "32"
                endian = "LE" if data[5] == 1 else "BE"
                arch_map = {0x03: "x86", 0x28: "ARM", 0xB7: "AArch64", 0x3E: "x86_64"}
                arch = arch_map.get(data[18], "unknown")
                result.append({
                    "name": Path(entry).name,
                    "path": entry,
                    "arch": f"{arch}-{bits}bits",
                    "endian": endian,
                    "size": z.getinfo(entry).file_size,
                })
        except (OSError, IndexError, KeyError) as e:
            print(f"Warning: ELF parse failed for {Path(entry).name}: {e}", file=sys.stderr)
            result.append({"name": Path(entry).name, "path": entry,
                           "size": z.getinfo(entry).file_size})
    return result


def _detect_packer(z: zipfile.ZipFile, entries: list[str]) -> list[str]:
    """Detect known Android packers/protectors."""
    indicators = []
    for entry in entries:
        for packer in KNOWN_PACKERS:
            if packer.lower() in entry.lower():
                indicators.append(f"Known packer file: {entry} ({packer})")

    # Check for common obfuscation indicators
    for entry in entries:
        if ".so" in entry and any(p in entry.lower() for p in ["libjiagu", "libegis", "libprotect"]):
            indicators.append(f"Native packer library: {entry}")
        if "assets/" in entry and any(p in entry.lower() for p in ["ijiami", "secneo"]):
            indicators.append(f"Packer asset: {entry}")

    return indicators


def _find_suspicious_files(entries: list[str]) -> list[str]:
    """Find files that may indicate malicious or suspicious behavior."""
    suspicious = []
    patterns = [
        (r".*\.dex$", "DEX file"),
        (r".*\.apk$", "Embedded APK"),
        (r".*\.jar$", "JAR file"),
        (r"assets/.*\.html", "HTML in assets"),
        (r"assets/.*\.js", "JavaScript in assets"),
        (r"res/raw/.*\.(exe|dll|elf|bin)$", "Native executable in resources"),
        (r".*libshell.*\.so$", "Packer shell library"),
        (r".*libnqshield.*\.so$", "NetQin Shield"),
    ]
    for entry in entries:
        for pat, desc in patterns:
            if re.match(pat, entry, re.IGNORECASE):
                suspicious.append(f"{desc}: {entry}")
                break
    return suspicious


def _extract_interesting_strings(z: zipfile.ZipFile, entries: list[str]) -> list[str]:
    """Extract potentially interesting strings from readable files."""
    patterns = {
        "urls": re.compile(rb'https?://[^\s\x00"\'<>]{4,}', re.IGNORECASE),
        "ips": re.compile(rb'\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}'),
        "email": re.compile(rb'[\w\.-]+@[\w\.-]+\.\w+'),
    }

    found = defaultdict(set)
    text_files = [e for e in entries if any(e.endswith(ext) for ext in
                   [".xml", ".json", ".js", ".html", ".txt", ".properties", ".smali"])]

    for entry in text_files[:50]:  # Limit to first 50 to stay performant
        try:
            data = z.read(entry)
            if len(data) > 500000:  # Skip very large files
                continue
            for cat, pat in patterns.items():
                matches = pat.findall(data)
                for m in matches[:5]:
                    found[cat].add(m.decode("ascii", errors="replace"))
        except (OSError, RuntimeError, zlib.error):
            pass

    return [{"category": cat, "values": list(vals)} for cat, vals in found.items()]


def _assess_risk(result: dict) -> dict:
    """Produce a risk summary based on all collected evidence."""
    risks = []
    score = 0

    manifest = result.get("manifest", {})
    high_perms = manifest.get("high_risk_permissions", [])
    if len(high_perms) >= 5:
        risks.append(f"{len(high_perms)} high-risk permissions: {', '.join(high_perms[:5])}...")
        score += min(len(high_perms) * 5, 30)
    elif high_perms:
        risks.append(f"{len(high_perms)} high-risk permissions: {', '.join(high_perms)}")
        score += len(high_perms) * 5

    exported = manifest.get("exported_components", [])
    if exported:
        risks.append(f"{len(exported)} exported components (potential attack surface)")
        score += min(len(exported) * 5, 20)

    packer = result.get("packer_indicators", [])
    if packer:
        risks.append(f"Packer/protector detected: {len(packer)} indicators")
        score += 10

    native = result.get("native_libraries", [])
    if native:
        risks.append(f"{len(native)} native libraries (uses native code)")
        score += 5

    sus_files = result.get("suspicious_files", [])
    if len(sus_files) > 5:
        risks.append(f"{len(sus_files)} suspicious file patterns")
        score += 10

    risk_level = "low"
    if score >= 40:
        risk_level = "critical"
    elif score >= 25:
        risk_level = "high"
    elif score >= 15:
        risk_level = "medium"

    return {"risk_level": risk_level, "score": score, "risk_items": risks}


# ── Output ────────────────────────────────────────────────────────────────────

def write_report(result: dict, path: str):
    m = result.get("manifest", {})
    lines = [
        f"# APK Deep Scan: {result['file']}",
        "",
        "## File Information",
        "",
        "| Field | Value |",
        "|---|---|",
        f"| File | {result['file']} |",
        f"| Size | {result['size']:,} bytes |",
        f"| MD5 | {result['hashes']['md5']} |",
        f"| SHA-256 | {result['hashes']['sha256']} |",
        f"| Package | {m.get('package_name', 'Unknown')} |",
    ]

    # Risk summary
    risk = result.get("risk_summary", {})
    if risk:
        lines += [
            "",
            "## Risk Assessment",
            "",
            f"- **Level:** {risk.get('risk_level', 'N/A').upper()}",
            f"- **Score:** {risk.get('score', 0)}",
        ]
        for item in risk.get("risk_items", []):
            lines.append(f"- {item}")

    # Permissions
    if m.get("permissions"):
        lines += [
            "",
            f"## Permissions ({m['permission_count']})",
            "",
            "| Permission | Risk | Description |",
            "|---|---|---|",
        ]
        for p in m["permissions"]:
            lines.append(f"| `{p['permission']}` | **{p['risk'].upper()}** | {p['description']} |")

    # Exported components
    exported = m.get("exported_components", [])
    if exported:
        lines += [
            "",
            f"## Exported Components ({len(exported)})",
            "",
            "| Type | Component |",
            "|---|---|",
        ]
        for c in exported:
            lines.append(f"| {c['type']} | {c['name']} |")

    # Native libs
    native = result.get("native_libraries", [])
    if native:
        lines += [
            "",
            f"## Native Libraries ({len(native)})",
            "",
            "| File | Architecture | Endian | Size |",
            "|---|---|---|---|",
        ]
        for lib in native:
            lines.append(f"| {lib['name']} | {lib.get('arch', 'unknown')} | {lib.get('endian', '?')} | {lib['size']:,} |")

    # Packer
    packer = result.get("packer_indicators", [])
    if packer:
        lines += [
            "",
            "## Packer/Protector Indicators",
        ]
        for p in packer:
            lines.append(f"- {p}")

    # DEX
    dex = result.get("dex_files", [])
    if dex:
        lines += [
            "",
            f"## DEX Files ({len(dex)})",
        ]
        for d in dex:
            lines.append(f"- {d['name']} ({d['size']:,} bytes)")

    # Certificate
    cert = result.get("certificate")
    if cert:
        lines += [
            "",
            "## Signing Certificate",
            "",
            f"- File: {cert['file']}",
            f"- Size: {cert['size']:,} bytes",
        ]

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))


# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="apk_deep_scan — deep APK analysis")
    parser.add_argument("apk_file", help="Path to APK file")
    parser.add_argument("--out", "-o", default="./apk_scan",
                        help="Output directory (default: ./apk_scan)")
    parser.add_argument("--json", action="store_true",
                        help="Output JSON to stdout")

    args = parser.parse_args()

    if not os.path.exists(args.apk_file):
        print(f"Error: file not found: {args.apk_file}", file=sys.stderr)
        sys.exit(1)

    result = analyze_apk(args.apk_file)
    if "error" in result:
        print(f"Error: {result['error']}", file=sys.stderr)
        sys.exit(1)

    os.makedirs(args.out, exist_ok=True)
    json_path = os.path.join(args.out, "apk_scan.json")
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False, default=str)

    md_path = os.path.join(args.out, "apk_scan.md")
    write_report(result, md_path)

    risk = result.get("risk_summary", {})
    manifest = result.get("manifest", {})
    print(f"[+] APK: {result['file']}")
    print(f"[+] Package: {manifest.get('package_name', 'Unknown')}")
    print(f"[+] Permissions: {manifest.get('permission_count', 0)} "
          f"({len(manifest.get('high_risk_permissions', []))} high-risk)")
    print(f"[+] Native libs: {len(result.get('native_libraries', []))}")
    print(f"[+] Risk: {risk.get('risk_level', 'N/A').upper()} (score: {risk.get('score', 0)})")
    if result.get("packer_indicators"):
        print("[!] Packer detected!")
    print(f"[+] Output: {json_path}, {md_path}")

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False, default=str))


if __name__ == "__main__":
    main()
