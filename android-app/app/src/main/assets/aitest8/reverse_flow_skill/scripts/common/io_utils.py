"""文件 I/O 工具 — 带内存限制的安全文件读取。"""

from __future__ import annotations

import sys
from pathlib import Path

DEFAULT_MAX_READ = 100 * 1024 * 1024  # 100 MB
HARD_LIMIT = 500 * 1024 * 1024       # 500 MB hard cap


class FileTooLargeError(Exception):
    """Raised when a file exceeds the read limit and truncation is disabled."""


def read_file(path: str | Path, max_size: int | None = DEFAULT_MAX_READ,
              *, allow_truncation: bool = True) -> bytes:
    """Read file with optional size limit.

    When the file exceeds ``max_size`` (capped at HARD_LIMIT):
      * if ``allow_truncation`` is True (default), read and return the
        truncated head with a stderr warning;
      * if ``allow_truncation`` is False, raise ``FileTooLargeError`` so
        callers that need complete bytes (e.g. binary parsers) fail loudly
        instead of producing a wrong analysis on partial data.
    """
    path = Path(path)
    actual_size = path.stat().st_size

    effective_limit = max_size if max_size is not None else HARD_LIMIT
    if effective_limit > HARD_LIMIT:
        effective_limit = HARD_LIMIT

    if actual_size > effective_limit:
        if not allow_truncation:
            raise FileTooLargeError(
                f"{path.name} ({actual_size:,} bytes) exceeds limit "
                f"{effective_limit:,} bytes (truncation disabled)"
            )
        reason = f"limit={max_size:,}b" if max_size is not None else f"hard_limit={HARD_LIMIT:,}b"
        print(
            f"Warning: {path.name} ({actual_size:,} bytes) exceeds {reason}, "
            f"reading truncated {effective_limit:,} bytes",
            file=sys.stderr,
        )

    with open(path, "rb") as f:
        return f.read(effective_limit)
