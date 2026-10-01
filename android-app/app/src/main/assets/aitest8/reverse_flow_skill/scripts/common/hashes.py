"""哈希计算 — 统一 5 处独立 hashlib 调用。"""

from __future__ import annotations

import hashlib


def compute_hashes(data: bytes) -> dict[str, str]:
    """Compute MD5, SHA-1, SHA-256 hashes of data."""
    return {
        "md5": hashlib.md5(data).hexdigest(),
        "sha1": hashlib.sha1(data).hexdigest(),
        "sha256": hashlib.sha256(data).hexdigest(),
    }
