"""文件类型识别 — 统一 4 处相互独立的魔数检测实现。"""

from __future__ import annotations

import struct
from pathlib import Path


def identify_file_type(data: bytes, path: str | Path = "") -> dict:
    """Magic-byte identification for PE/ELF/Mach-O/ZIP/PNG/JPEG/PDF/Java/.NET/APK.

    Returns dict with keys: extension, magic_hex, type, and format-specific fields.
    """
    magic = data[:16]
    ext = Path(path).suffix.lower() if path else ""
    result: dict = {"extension": ext, "magic_hex": magic[:8].hex(), "type": "unknown"}

    # PE
    if magic[:2] == b"MZ":
        result["type"] = "PE (Windows executable)"
        pe_offset = struct.unpack_from("<I", data, 0x3C)[0] if len(data) > 0x3E else 0
        if pe_offset and len(data) > pe_offset + 4:
            pe_sig = data[pe_offset : pe_offset + 4]
            if pe_sig == b"PE\x00\x00":
                machine = struct.unpack_from("<H", data, pe_offset + 4)[0]
                machine_map = {
                    0x14C: "x86",
                    0x8664: "x64",
                    0xAA64: "ARM64",
                    0x1C0: "ARM",
                    0x1C4: "ARM NT",
                }
                result["type"] = f"PE{machine_map.get(machine, '?')} (Windows executable)"
                result["pe_offset"] = pe_offset
                result["machine"] = machine_map.get(machine, hex(machine))
        # .NET detection — check CLR header reference
        if b"mscoree" in data[:2048].lower():
            result["is_dotnet"] = True
            result["type"] = ".NET " + result["type"]

    # ELF
    elif magic[:4] == b"\x7fELF":
        bits = "64" if data[4] == 2 else "32"
        endian = "LE" if data[5] == 1 else "BE"
        ei_type = {2: "executable", 3: "shared library", 1: "relocatable"}
        e_type = struct.unpack_from("<H", data, 16)[0]
        result["type"] = f"ELF{bits} {endian} ({ei_type.get(e_type, 'type=' + str(e_type))})"
        result["elf_class"] = bits
        result["elf_endian"] = endian

    # Mach-O (thin and fat/universal)
    elif magic[:4] in (
        b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe",
        b"\xfe\xed\xfa\xcf", b"\xfe\xed\xfa\xce",
    ):
        result["type"] = "Mach-O (macOS/iOS binary)"
    elif magic[:4] in (b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca"):
        # 0xCAFEBABE shared by Java class and Mach-O fat binary
        # Use extension as discriminator
        if ext == ".class":
            result["type"] = "Java class file"
        else:
            result["type"] = "Mach-O Universal/Fat binary (macOS/iOS)"

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

    # APK (ZIP-based, detected by extension)
    elif ext == ".apk":
        result["type"] = "APK (Android application package)"

    # Java
    elif magic[:4] == b"\xca\xfe\xba\xbe":
        result["type"] = "Java class file"

    return result
