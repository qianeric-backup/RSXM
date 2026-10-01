"""字符串提取 — 支持 ASCII 和 UTF-16LE。"""

from __future__ import annotations


def extract_ascii_strings(data: bytes, min_len: int = 4) -> list[str]:
    """Extract printable ASCII strings (0x20-0x7E) from binary data."""
    result: list[str] = []
    current: list[str] = []
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


def extract_unicode_strings(data: bytes, min_len: int = 4) -> list[str]:
    """Extract UTF-16LE strings from binary data."""
    result: list[str] = []
    if len(data) < 2:
        return result
    current: list[str] = []
    for i in range(0, len(data) - 1, 2):
        lo = data[i]
        hi = data[i + 1]
        cp = lo | (hi << 8)
        if 0x20 <= cp <= 0x7E:
            current.append(chr(cp))
        elif cp == 0x0A or cp == 0x0D:
            if len(current) >= min_len:
                result.append("".join(current))
            current = []
        else:
            if len(current) >= min_len:
                result.append("".join(current))
            current = []
    if len(current) >= min_len:
        result.append("".join(current))
    return result


def extract_strings(data: bytes, min_len: int = 4) -> list[str]:
    """Extract both ASCII and UTF-16LE strings, return unique sorted."""
    ascii_strings = extract_ascii_strings(data, min_len)
    unicode_strings = extract_unicode_strings(data, min_len)
    return sorted(set(ascii_strings + unicode_strings), key=lambda s: (len(s), s))
