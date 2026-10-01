"""Smoke tests for all reverse-flow scripts.

Verifies: Python compilation, --help output, common module imports,
graceful error handling for bad input, and dependency-aware skip logic.
"""

from __future__ import annotations

import importlib
import os
import pytest
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent.parent / "scripts"
PROJECT_ROOT = SCRIPTS_DIR.parent

# Scripts that require capstone/unicorn for meaningful operation
REQUIRES_CAPSTONE = {"rop_finder.py", "shellcode_tools.py"}

# Scripts that need a binary/artifact as first argument (test with --help only)
CLI_SCRIPTS = [
    "apk_deep_scan.py",
    "auto_analyze.py",
    "create_case.py",
    "dex_analyze.py",
    "debugger_bridge.py",
    "dotnet_analyze.py",
    "elf_deep_scan.py",
    "find_crypto.py",
    "ghidra_headless.py",
    "macho_scan.py",
    "pe_deep_scan.py",
    "report_from_triage.py",
    "rop_finder.py",
    "shellcode_tools.py",
    "tool_audit.py",
    "triage_artifact.py",
    "yara_gen.py",
]

COMMON_MODULES = [
    "common.entropy",
    "common.filetype",
    "common.hashes",
    "common.io_utils",
    "common.strings",
    "common.crypto_constants",
]


def _check_capstone() -> bool:
    try:
        importlib.import_module("capstone")
        return True
    except ImportError:
        return False


def _run_py_compile(script: str) -> tuple[bool, str]:
    path = SCRIPTS_DIR / script
    try:
        result = subprocess.run(
            [sys.executable, "-m", "py_compile", str(path)],
            capture_output=True, text=True, timeout=30,
        )
        return result.returncode == 0, result.stderr.strip()
    except subprocess.TimeoutExpired:
        return False, "timeout"
    except (OSError, ValueError) as e:
        return False, str(e)


def _run_help(script: str) -> tuple[bool, str]:
    path = SCRIPTS_DIR / script
    try:
        result = subprocess.run(
            [sys.executable, str(path), "--help"],
            capture_output=True, text=True, timeout=30,
        )
        return result.returncode == 0, result.stdout[:200]
    except subprocess.TimeoutExpired:
        return False, "timeout"
    except (OSError, ValueError) as e:
        return False, str(e)


def _run_bad_input(script: str) -> tuple[bool, str]:
    """Test that the script handles garbage input gracefully (no crash)."""
    path = SCRIPTS_DIR / script
    # Scripts that error out in argparse before touching the file
    scripts_no_file_check = {"create_case.py", "report_from_triage.py", "tool_audit.py", "yara_gen.py"}
    if script in scripts_no_file_check:
        return True, "skipped (no positional file arg)"

    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
        f.write(b"NOT_A_VALID_BINARY_XXXX")
        tmp_path = f.name
    try:
        result = subprocess.run(
            [sys.executable, str(path), tmp_path],
            capture_output=True, text=True, timeout=60,
            cwd=str(SCRIPTS_DIR),
        )
        # non-zero exit is fine — it should NOT crash with a traceback
        crashed = "Traceback (most recent call last)" in result.stderr
        return not crashed, result.stderr[:400] if crashed else result.stdout[:200]
    except subprocess.TimeoutExpired:
        return False, "timeout"
    except (OSError, ValueError) as e:
        return False, str(e)
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


def test_all_scripts_compile():
    """Every standalone script must pass py_compile."""
    has_capstone = _check_capstone()
    failed = []
    for script in CLI_SCRIPTS:
        if script in REQUIRES_CAPSTONE and not has_capstone:
            continue
        ok, err = _run_py_compile(script)
        if not ok:
            failed.append(f"{script}: {err}")
    assert not failed, "Compilation failures:\n" + "\n".join(failed)


def test_all_scripts_help():
    """Every script must display --help without error."""
    has_capstone = _check_capstone()
    failed = []
    for script in CLI_SCRIPTS:
        if script in REQUIRES_CAPSTONE and not has_capstone:
            continue
        ok, out = _run_help(script)
        if not ok:
            failed.append(f"{script}: --help failed ({out})")
        elif "usage:" not in out.lower() and "usage：" not in out.lower():
            failed.append(f"{script}: --help output missing 'usage:'")
    assert not failed, "Help failures:\n" + "\n".join(failed)


def test_all_scripts_bad_input():
    """Every script must handle garbage input without crashing."""
    has_capstone = _check_capstone()
    failed = []
    for script in CLI_SCRIPTS:
        if script in REQUIRES_CAPSTONE and not has_capstone:
            continue
        ok, detail = _run_bad_input(script)
        if not ok:
            failed.append(f"{script}: crashed on bad input — {detail}")
    assert not failed, "Crash-on-bad-input:\n" + "\n".join(failed)


def test_elf_deep_scan_truncated_elf_no_crash():
    """Truncated ELF (valid magic, short body) must exit non-zero, not crash."""
    script = SCRIPTS_DIR / "elf_deep_scan.py"
    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
        f.write(b"\x7fELF" + b"\x02\x01\x01\x00" + b"\x00" * 12)
        tmp_path = f.name
    try:
        result = subprocess.run(
            [sys.executable, str(script), tmp_path],
            capture_output=True, text=True, timeout=60,
            cwd=str(SCRIPTS_DIR),
        )
        assert result.returncode != 0, "expected non-zero exit on truncated ELF"
        assert "Traceback (most recent call last)" not in result.stderr, \
            "truncated ELF must not crash with a Traceback"
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


def test_common_modules_import():
    """All common modules must be importable from the scripts directory."""
    original_cwd = os.getcwd()
    os.chdir(str(SCRIPTS_DIR))
    failed = []
    try:
        if str(SCRIPTS_DIR) not in sys.path:
            sys.path.insert(0, str(SCRIPTS_DIR))
        for mod_name in COMMON_MODULES:
            try:
                importlib.import_module(mod_name)
            except ImportError as e:
                failed.append(f"{mod_name}: {e}")
    finally:
        os.chdir(original_cwd)
    assert not failed, "Common module import failures:\n" + "\n".join(failed)


def test_common_entropy():
    from common.entropy import shannon_entropy
    assert shannon_entropy(b"aaaa") == 0.0  # all-same bytes = zero entropy
    assert shannon_entropy(b"") == 0.0
    assert shannon_entropy(bytes(range(256))) > 7.0


def test_common_hashes():
    from common.hashes import compute_hashes
    result = compute_hashes(b"test")
    assert "md5" in result
    assert "sha1" in result
    assert "sha256" in result
    assert len(result["md5"]) == 32
    assert len(result["sha256"]) == 64


def test_common_filetype():
    from common.filetype import identify_file_type
    # PE
    pe = b"MZ\x00\x00" + b"\x00" * 56 + b"\x40\x00\x00\x00" + b"PE\x00\x00"
    r = identify_file_type(pe)
    assert "PE" in r["type"]

    # ELF
    elf = b"\x7fELF\x02\x01\x01\x00" + b"\x00" * 8 + b"\x03\x00"
    r = identify_file_type(elf)
    assert "ELF" in r["type"]

    # Text file
    r = identify_file_type(b"Hello World")
    assert r["type"] == "unknown"


def test_common_io_utils():
    from common.io_utils import read_file
    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
        f.write(b"A" * 500)
        tmp_path = f.name
    try:
        data = read_file(tmp_path, max_size=1000)
        assert len(data) == 500
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


def test_common_strings():
    from common.strings import extract_ascii_strings
    data = b"Hello\x00World\x00\xFF\xFEtest\x00\x00"
    result = extract_ascii_strings(data, min_len=3)
    assert "Hello" in result
    assert "World" in result
    assert "test" in result


def test_common_crypto_constants():
    from common.crypto_constants import AES_SBOX, MD5_IV, SHA1_IV, SHA256_IV
    assert len(AES_SBOX) == 16  # first 16 of 256 (full S-box is 256 bytes)
    assert len(MD5_IV) == 16    # 4 dwords × 4 bytes, little-endian
    assert len(SHA1_IV) == 20   # 5 dwords × 4 bytes, big-endian
    assert len(SHA256_IV) == 32  # 8 dwords × 4 bytes, big-endian


def test_debugger_bridge_sizeof_image_offset():
    """Regression guard: generated debugger scripts must read SizeOfImage at e_lfanew+0x50."""
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "debugger_bridge", SCRIPTS_DIR / "debugger_bridge.py"
    )
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    templates = mod.TEMPLATES

    mem = templates["memory_dump"]["script"]
    assert 'size_of_image = struct.unpack_from("<I", pe_header, 0x50)' in mem, \
        "memory_dump: SizeOfImage must be read at e_lfanew + 0x50"

    esp = templates["unpack_esp"]["script"]
    assert 'struct.unpack_from("<I", client.read_memory(base + pe_off, 0x54), 0x50)' in esp, \
        "unpack_esp: SizeOfImage must be read at e_lfanew + 0x50"


def test_auto_analyze_deep_elf_integration():
    """Integration: analyze_artifact mounts deep_analysis['elf'] for a min ELF."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    elf_bytes = (b"\x7fELF" + b"\x02\x01\x01\x00" + b"\x00" * 56
                 + b"\x03\x00" + b"\x00" * 100)
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".elf", delete=False) as f:
            f.write(elf_bytes)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            da = result["deep_analysis"]
            assert isinstance(da, dict)
            assert "elf" in da, "deep_analysis must contain 'elf' key"
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_deep_text_no_crash():
    """Text file: deep branch stays empty dict, no crash."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"just some plain text, not a binary")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result["deep_analysis"], dict)
            assert result["deep_analysis"] == {}
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_debugger_bridge_no_utcnow_deprecation():
    """Regression guard: no deprecated datetime.utcnow(); use timezone-aware now."""
    src = (SCRIPTS_DIR / "debugger_bridge.py").read_text(encoding="utf-8")
    assert "utcnow" not in src, "datetime.utcnow() is deprecated; use datetime.now(timezone.utc)"
    assert "datetime.now(timezone.utc)" in src, "expected timezone-aware now() usage"
    assert "from datetime import datetime, timezone" in src, \
        "import must include 'timezone' for timezone-aware timestamps"


def test_common_io_utils_truncation_and_error():
    """Cover truncation branch (allow_truncation=True) and FileTooLargeError (False)."""
    from common.io_utils import read_file, FileTooLargeError

    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
        f.write(b"A" * 200)
        tmp_path = f.name
    try:
        # a) default allow_truncation=True -> truncated head, no raise
        data = read_file(tmp_path, max_size=100)
        assert len(data) == 100, "should return truncated 100 bytes"

        # b) allow_truncation=False -> FileTooLargeError
        with pytest.raises(FileTooLargeError):
            read_file(tmp_path, max_size=100, allow_truncation=False)

        # c) max_size above actual size -> full content returned
        data = read_file(tmp_path, max_size=300)
        assert len(data) == 200, "should return full 200 bytes when under limit"
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


def test_parser_handles_file_too_large(monkeypatch):
    """Parser must fail cleanly (FileTooLargeError) on oversized input via monkeypatch."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))

    def _load(name, filename):
        spec = importlib.util.spec_from_file_location(name, SCRIPTS_DIR / filename)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        return mod

    import common.io_utils as io_utils
    # read_file's positional default max_size is bound at def time; override it
    # via __defaults__ so an oversized file triggers FileTooLargeError.
    monkeypatch.setattr(io_utils.read_file, "__defaults__", (10,))

    with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
        f.write(b"\x7fELF" + b"\x00" * 200)  # 207 bytes > 10
        tmp_path = f.name
    try:
        elf_mod = _load("elf_deep_scan", "elf_deep_scan.py")
        with pytest.raises(io_utils.FileTooLargeError):
            elf_mod.read_file(tmp_path, allow_truncation=False)
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


def test_auto_analyze_triage_integration():
    """Integration: analyze_artifact mounts triage indicators + next steps."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    sample = (b"download from http://evil.example.com beacon 8.8.8.8 "
              b"and run CreateProcess to spawn a shell")
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(sample)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            tri = result["triage"]
            assert isinstance(tri, dict)
            inds = tri["triage_indicators"]
            assert "urls" in inds and "ipv4" in inds
            assert isinstance(tri["recommended_next_steps"], list)
            assert len(tri["recommended_next_steps"]) > 0
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_triage_text_no_crash():
    """Plain text: triage branch runs and deep_analysis stays empty dict."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"just some plain text, not a binary")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert result["deep_analysis"] == {}
            assert "triage" in result and isinstance(result["triage"], dict)
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_triage_import_failure_isolated(monkeypatch):
    """If triage_artifact import fails, pipeline still returns a result."""
    import builtins
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "triage_artifact":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(b"http://x.example.com 1.2.3.4")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result, dict)
            assert result["triage"].get("_error", "").startswith(
                "triage enrichment failed:")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_crypto_scan_integration():
    """Integration: analyze_artifact mounts crypto_scan with AES S-box sample."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    aes_sbox = bytes([0x63,0x7c,0x77,0x7b,0xf2,0x6b,0x6f,0xc5,0x30,0x01,0x67,0x2b,0xfe,0xd7,0xab,0x76,
                     0xca,0x82,0xc9,0x7d,0xfa,0x59,0x47,0xf0,0xad,0xd4,0xa2,0xaf,0x9c,0xa4,0x72,0xc0])
    sample = b"preamble" + aes_sbox + b"trailer_data_here"

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(sample)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            cs = result["crypto_scan"]
            assert isinstance(cs, dict)
            assert "_error" not in cs, f"unexpected crypto scan error: {cs.get('_error')}"
            assert isinstance(cs["constants"], list)
            assert len(cs["constants"]) > 0, "AES S-box must be detected"
            algos = [c["algorithm"] for c in cs["constants"]]
            assert any("AES" in a for a in algos), f"expected AES in {algos}"
            # Legacy inline indicators must remain intact (non-regression)
            assert "crypto_indicators" in result
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_crypto_scan_text_no_crash():
    """Plain text: crypto_scan returns empty lists, no crash."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"just some plain text, not a binary")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            cs = result["crypto_scan"]
            assert isinstance(cs, dict)
            assert cs.get("constants", []) == []
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_crypto_scan_import_failure_isolated(monkeypatch):
    """If find_crypto import fails, pipeline still returns a result."""
    import builtins
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "find_crypto":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(b"http://x.example.com 1.2.3.4")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result, dict)
            assert result["crypto_scan"].get("_error", "").startswith(
                "crypto scan failed:")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_deep_pe_integration():
    """Integration: analyze_artifact mounts deep_analysis['pe'] for a min PE (MZ header)."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    # Minimal "MZ" file: analyze_pe returns a dict (possibly with "error"),
    # but it must still be mounted under deep_analysis["pe"].
    pe_bytes = b"MZ" + b"\x00" * 58 + b"\x3c\x00\x00\x00" + b"\x00" * 64
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".exe", delete=False) as f:
            f.write(pe_bytes)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            da = result["deep_analysis"]
            assert isinstance(da, dict)
            assert "pe" in da, "deep_analysis must contain 'pe' key"
            assert isinstance(da["pe"], dict)
            if "error" in da["pe"]:
                assert isinstance(da["pe"]["error"], str)
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_deep_pe_import_failure_isolated(monkeypatch):
    """If pe_deep_scan import fails, pipeline still returns a result (no crash)."""
    import builtins
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "pe_deep_scan":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".exe", delete=False) as f:
            f.write(b"MZ" + b"\x00" * 128)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result, dict)
            assert result["deep_analysis"].get("_error", "").startswith(
                "deep analysis failed:")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_deep_apk_integration():
    """Integration: analyze_artifact mounts deep_analysis['apk'] for a min APK (valid zip)."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    import zipfile
    with tempfile.TemporaryDirectory() as out_dir:
        apk_path = os.path.join(out_dir, "sample.apk")
        with zipfile.ZipFile(apk_path, "w") as z:
            z.writestr("AndroidManifest.xml",
                       b'<manifest package="com.example.test" '
                       b'android:versionCode="1"></manifest>')
        result = mod.analyze_artifact(apk_path, out_dir, skip_yara=True)
        da = result["deep_analysis"]
        assert isinstance(da, dict)
        assert "apk" in da, "deep_analysis must contain 'apk' key"
        assert isinstance(da["apk"], dict)
        if "error" in da["apk"]:
            assert isinstance(da["apk"]["error"], str)


def test_auto_analyze_deep_apk_invalid_zip_no_crash():
    """Invalid APK (PK header but corrupt): analyze_apk returns error dict, still mounted."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as f:
            f.write(b"PK\x03\x04" + b"\x00" * 100)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            da = result["deep_analysis"]
            assert isinstance(da.get("apk"), dict), \
                "even invalid APK must mount a dict under deep_analysis['apk']"
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_deep_apk_import_failure_isolated(monkeypatch):
    """If apk_deep_scan import fails, pipeline still returns a result (no crash)."""
    import builtins
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "apk_deep_scan":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as f:
            f.write(b"PK\x03\x04" + b"\x00" * 100)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result, dict)
            assert result["deep_analysis"].get("_error", "").startswith(
                "deep analysis failed:")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_risk_hint_signature_and_no_crash():
    """Module 5 source: risk_hint returns (rating, reasons); high-signal -> Medium, low -> Unknown/Low."""
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "report_from_triage", SCRIPTS_DIR / "report_from_triage.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    high = {
        "prefix_entropy": 7.9,
        "indicators": {
            "urls": ["http://x.example.com"],
            "ipv4": ["1.2.3.4"],
            "suspicious_terms": ["CreateProcess"],
        },
        "magic_hints": ["PE x64 (Windows executable)"],
    }
    rating, reasons = mod.risk_hint(high)
    assert isinstance(rating, str)
    assert isinstance(reasons, list)
    assert rating == "Medium", f"expected Medium, got {rating}"
    assert len(reasons) >= 3

    # Low-signal input must not crash and returns Unknown/Low.
    low_rating, low_reasons = mod.risk_hint({})
    assert low_rating == "Unknown/Low"
    assert "limited evidence from offline triage" in low_reasons


def test_auto_analyze_risk_hint_integration():
    """Module 5 integration: high-signal sample mounts normalized risk_hint."""
    import importlib.util
    import os as _os
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    sample = _os.urandom(4096) + (
        b" http://evil.example.com 8.8.8.8 CreateProcess beacon here")
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(sample)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            rh = result["risk_hint"]
            assert isinstance(rh, dict)
            assert "_error" not in rh, f"unexpected risk hint error: {rh.get('_error')}"
            assert rh["rating"] == "medium", f"expected 'medium', got {rh.get('rating')}"
            assert isinstance(rh["reasons"], list)
            assert len(rh["reasons"]) >= 3
            # Verbatim original rating preserved for downstream tooling.
            assert rh["rating_raw"] == "Medium"
        finally:
            try:
                _os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_risk_hint_text_no_crash():
    """Module 5 no-regression: plain text yields risk_hint without breaking schema."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"just some plain text, not a binary")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result["risk_hint"], dict)
            # No regression: existing top-level keys remain present and untouched.
            for key in ("deep_analysis", "triage", "crypto_scan", "risk_indicators"):
                assert key in result, f"schema regression: missing '{key}'"
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_risk_hint_import_failure_isolated(monkeypatch):
    """If report_from_triage import fails, pipeline still returns a result (no crash)."""
    import builtins
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "report_from_triage":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(b"http://x.example.com 1.2.3.4")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result, dict)
            assert result["risk_hint"].get("_error", "").startswith(
                "risk hint failed:")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_yara_gen_integration():
    """Module 6: yara_gen generates the rule (not the inline fallback)."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    elf_bytes = (b"\x7fELF" + b"\x02\x01\x01\x00" + b"\x00" * 56
                 + b"\x03\x00" + b"\x00" * 100)
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".elf", delete=False) as f:
            f.write(elf_bytes)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=False)
            meta = result["yara_rule_meta"]
            assert meta["generator"] == "yara_gen", meta
            yara_file = os.path.join(out_dir, f"{Path(tmp_path).name}.yar")
            assert os.path.isfile(yara_file), "YARA rule file must be written"
            content = Path(yara_file).read_text(encoding="utf-8")
            assert "rule AutoGen_" in content
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_yara_gen_text_no_crash():
    """Module 6 no-regression: plain text still yields a YARA rule, no crash."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"just some plain text, not a binary")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=False)
            assert "yara_rule_meta" in result
            yara_file = os.path.join(out_dir, f"{Path(tmp_path).name}.yar")
            assert os.path.isfile(yara_file), "YARA rule file must still be written"
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_yara_gen_import_failure_isolated(monkeypatch):
    """If yara_gen import fails, fall back to the inline generator."""
    import builtins
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "yara_gen":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(b"http://x.example.com 1.2.3.4")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=False)
            assert result["yara_rule_meta"]["generator"] == "inline_fallback"
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_ghidra_status_integration():
    """Module 7: cheap detect_ghidra() surfaced; subprocess logic stays standalone."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(b"http://x.example.com 1.2.3.4")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            gh = result["ghidra_status"]
            assert isinstance(gh, dict)
            assert "installed" in gh, "detect_ghidra result must carry 'installed'"
            assert isinstance(gh["installed"], bool)
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_ghidra_import_failure_isolated(monkeypatch):
    """If ghidra_headless import fails, pipeline still returns a result (no crash)."""
    import builtins
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "ghidra_headless":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(b"http://x.example.com 1.2.3.4")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result, dict)
            assert result["ghidra_status"].get("error", "").startswith(
                "ghidra detection failed:")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_rop_integration():
    """Module 8: ROP gadget summary mounted for an executable (ELF)."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    elf_bytes = (b"\x7fELF" + b"\x02\x01\x01\x00" + b"\x00" * 56
                 + b"\x03\x00" + b"\x00" * 100)
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".elf", delete=False) as f:
            f.write(elf_bytes)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            rg = result["rop_gadgets"]
            assert isinstance(rg, dict)
            assert "_error" not in rg, f"unexpected rop error: {rg.get('_error')}"
            assert isinstance(rg.get("total_gadgets"), int)
            assert isinstance(rg.get("categories"), dict)
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_rop_text_no_crash():
    """Module 8: non-executable text yields empty rop_gadgets, no crash."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"just some plain text, not a binary")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert result["rop_gadgets"] == {}
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_rop_import_failure_isolated(monkeypatch):
    """If rop_finder import fails, pipeline still returns a result (no crash)."""
    import builtins
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "rop_finder":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    elf_bytes = (b"\x7fELF" + b"\x02\x01\x01\x00" + b"\x00" * 56
                 + b"\x03\x00" + b"\x00" * 100)
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".elf", delete=False) as f:
            f.write(elf_bytes)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result, dict)
            assert result["rop_gadgets"].get("error", "").startswith(
                "rop scan failed:")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_obfuscation_integration():
    """Module 9: obfuscation findings mounted; stack-string pattern detected."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    # >10 0xC6 bytes triggers the stack_string heuristic in find_obfuscated_strings
    sample = b"\xc6" * 20 + b"shellcode_marker_here"
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(sample)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            obf = result["obfuscation_findings"]
            assert isinstance(obf, list)
            assert len(obf) >= 1, "stack_string pattern must be detected"
            assert obf[0].get("type") == "stack_string"
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_obfuscation_text_no_crash():
    """Module 9: plain text yields a list (possibly empty), no crash."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"just some plain text, not a binary")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result["obfuscation_findings"], list)
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_obfuscation_import_failure_isolated(monkeypatch):
    """If shellcode_tools import fails, pipeline still returns a result (no crash)."""
    import builtins
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "shellcode_tools":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".bin", delete=False) as f:
            f.write(b"\xc6" * 20)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result, dict)
            obf = result["obfuscation_findings"]
            assert isinstance(obf, list) and obf and obf[0].get("error", "").startswith(
                "obfuscation scan failed:")
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_debugger_scripts_packed_pe():
    """Module 10: packed PE triggers offline generation of unpack/debugger scripts."""
    import importlib.util
    import os as _os
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    # MZ + high-entropy random body -> packer detected -> unpack_esp generated
    sample = b"MZ" + _os.urandom(200000)
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".exe", delete=False) as f:
            f.write(sample)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            dbs = result["debugger_scripts"]
            assert isinstance(dbs, dict)
            assert "_error" not in dbs, f"unexpected error: {dbs.get('error')}"
            generated = dbs.get("generated", [])
            assert generated, "packed PE must generate debugger scripts"
            templates = [g["template"] for g in generated]
            assert "unpack_esp" in templates
            # The generated script file must actually exist on disk.
            for g in generated:
                assert _os.path.isfile(g["script_path"]), \
                    f"script not written: {g['script_path']}"
        finally:
            try:
                _os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_debugger_scripts_text_no_crash():
    """Module 10: non-PE text yields empty debugger_scripts, no crash."""
    import importlib.util
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".txt", delete=False) as f:
            f.write(b"just some plain text, not a binary")
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert result["debugger_scripts"] == {}
        finally:
            try:
                os.unlink(tmp_path)
            except OSError:
                pass


def test_auto_analyze_debugger_import_failure_isolated(monkeypatch):
    """If debugger_bridge import fails on a PE, pipeline still returns a result."""
    import builtins
    import importlib.util
    import os as _os
    if str(SCRIPTS_DIR) not in sys.path:
        sys.path.insert(0, str(SCRIPTS_DIR))
    spec = importlib.util.spec_from_file_location(
        "auto_analyze", SCRIPTS_DIR / "auto_analyze.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    real_import = builtins.__import__
    def _fake_import(name, *a, **k):
        if name == "debugger_bridge":
            raise ImportError("simulated missing module")
        return real_import(name, *a, **k)
    monkeypatch.setattr(builtins, "__import__", _fake_import)

    sample = b"MZ" + _os.urandom(200000)
    with tempfile.TemporaryDirectory() as out_dir:
        with tempfile.NamedTemporaryFile(suffix=".exe", delete=False) as f:
            f.write(sample)
            tmp_path = f.name
        try:
            result = mod.analyze_artifact(tmp_path, out_dir, skip_yara=True)
            assert isinstance(result, dict)
            assert result["debugger_scripts"].get("error", "").startswith(
                "debugger script generation failed:")
        finally:
            try:
                _os.unlink(tmp_path)
            except OSError:
                pass


if __name__ == "__main__":
    import pytest
    sys.exit(pytest.main([__file__, "-v", "--tb=short"]))
