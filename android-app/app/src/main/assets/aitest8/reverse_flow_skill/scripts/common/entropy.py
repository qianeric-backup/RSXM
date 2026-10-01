"""熵计算 — 统一实现，消除 5 处代码重复。"""

from __future__ import annotations

import math
from collections import Counter
from typing import Sequence


def shannon_entropy(data: bytes | Sequence[int]) -> float:
    """Calculate Shannon entropy of byte data."""
    if not data:
        return 0.0
    counts = Counter(data)
    total = len(data)
    return -sum((c / total) * math.log2(c / total) for c in counts.values())
