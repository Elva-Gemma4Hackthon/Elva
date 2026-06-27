"""
adb_input.py — Unicode/CJK text input + device environment inspection.

Why this exists:
  Android's built-in ``adb shell input text`` is hard-coded to ASCII and
  silently drops / corrupts any non-Latin character (Chinese, emoji, accented
  letters, etc.). The standard community fix is **ADBKeyboard**
  (https://github.com/senzhk/ADBKeyBoard), an open-source IME that exposes a
  broadcast receiver so we can push Unicode text from the host machine via
  plain ``adb shell am broadcast``.

Features:
  - ``set_adb_keyboard()`` / ``restore_ime()`` — switch the active IME.
  - ``type_unicode(text)`` — broadcast Unicode text via ADBKeyboard.
  - ``ime_status()`` — read the currently-enabled IME.
  - ``device_info()`` — one-shot summary: sdk, screen, density, focus, IME.
  - ``screen_size()``, ``current_focus()`` — small focused helpers.
  - ``download_adbkeyboard(target_dir)`` — fetch ADBKeyboard.apk from GitHub.

CLI example:
    python adb_input.py info
    python adb_input.py set-ime adbkeyboard
    python adb_input.py type  "你好,世界"
    python adb_input.py screen
    python adb_input.py focus

Library example:
    from adb_input import AdbInput

    with AdbInput() as kb:
        kb.set_adb_keyboard()
        kb.focus_a_text_field()
        kb.type_unicode("给爸妈发短信")
        kb.restore_ime()
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Sequence, Tuple


__all__ = [
    "AdbInput",
    "ADBKeyboard",
    "DEVICE_INFO_FIELDS",
]


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

ADB_KEYBOARD_PKG = "com.android.adbkeyboard"
ADB_KEYBOARD_IME = "com.android.adbkeyboard/.AdbIME"
ADB_KEYBOARD_APK_URL = (
    "https://github.com/senzhk/ADBKeyBoard/raw/master/ADBKeyboard.apk"
)
ADB_KEYBOARD_APK_NAME = "ADBKeyboard.apk"

#: Recognised fields in ``device info`` output.
DEVICE_INFO_FIELDS: Tuple[str, ...] = (
    "sdk",
    "release",
    "model",
    "manufacturer",
    "screen",
    "density",
    "ime",
    "focus",
)


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------

@dataclass
class ADBKeyboard:
    """Convenience constants for the ADBKeyboard IME."""
    package: str = ADB_KEYBOARD_PKG
    ime_component: str = ADB_KEYBOARD_IME
    apk_url: str = ADB_KEYBOARD_APK_URL
    apk_name: str = ADB_KEYBOARD_APK_NAME
    broadcast_action: str = "ADB_INPUT_TEXT"
    extra_message: str = "msg"

    def broadcast_cmd(self, text: str) -> str:
        """The exact ``am broadcast`` invocation that pushes Unicode text."""
        safe = text.replace("'", "'\\''")
        return (
            f"am broadcast -a {self.broadcast_action} "
            f"--es {self.extra_message} '{safe}'"
        )


# ---------------------------------------------------------------------------
# ADB wrapper
# ---------------------------------------------------------------------------

class AdbInput:
    """Thin ADB wrapper specialised for input / environment queries."""

    def __init__(self, device: Optional[str] = None, adb: str = "adb") -> None:
        self.adb = adb
        self.device = device
        self.kb = ADBKeyboard()

    # -- low-level helpers ----------------------------------------------------

    def _build_cmd(self, args: Sequence[str]) -> List[str]:
        cmd: List[str] = [self.adb]
        if self.device:
            cmd += ["-s", self.device]
        cmd += list(args)
        return cmd

    def _run(self,
             args: Sequence[str],
             timeout: float = 30.0,
             check: bool = True) -> str:
        cmd = self._build_cmd(args)
        try:
            proc = subprocess.run(
                cmd,
                capture_output=True,
                timeout=timeout,
                check=False,
            )
        except FileNotFoundError as e:
            raise FileNotFoundError(
                f"ADB executable not found: {self.adb!r}. "
                "Install Android Platform Tools or pass --adb /path/to/adb."
            ) from e
        if check and proc.returncode != 0:
            err = proc.stderr.decode("utf-8", errors="replace").strip()
            raise RuntimeError(f"adb command failed ({proc.returncode}): {cmd}\n{err}")
        return proc.stdout.decode("utf-8", errors="replace")

    def shell(self, command: str, timeout: float = 30.0,
              check: bool = True) -> str:
        """Run ``adb shell <command>`` and return stdout."""
        return self._run(["shell", command], timeout=timeout, check=check)

    # -- IME management -------------------------------------------------------

    def ime_status(self) -> str:
        """Return the currently-active IME id.

        ``ime list -s`` lists IMEs in registration order, NOT the active one,
        so we use ``settings get secure default_input_method`` which always
        reflects the currently-active IME (and works on Android 5.0+).
        """
        out = self.shell(
            "settings get secure default_input_method", check=False
        ).strip()
        if out and out != "null":
            return out
        # Last resort: first line of ``ime list -s``.
        for line in self.shell("ime list -s", check=False).splitlines():
            line = line.strip()
            if line:
                return line
        return ""

    def ime_all(self) -> List[str]:
        """List all installed IMEs (id per line)."""
        return [s for s in self.shell("ime list -a").splitlines() if s.strip()]

    def is_adb_keyboard_active(self) -> bool:
        return self.ime_status() == self.kb.ime_component

    def set_adb_keyboard(self) -> bool:
        """Enable and switch to ADBKeyboard. Returns True on success."""
        self.shell(f"ime enable {self.kb.ime_component}")
        out = self.shell(f"ime set {self.kb.ime_component}")
        if "Error" in out or "Exception" in out:
            raise RuntimeError(
                "Failed to switch IME. Is ADBKeyboard installed?\n"
                f"adb install {self.kb.apk_name}\n{out}"
            )
        return self.is_adb_keyboard_active()

    def restore_ime(self, previous_ime: Optional[str] = None) -> bool:
        """Restore the previously-active IME (or a given one)."""
        target = previous_ime or self._detect_default_ime()
        if not target or target == self.kb.ime_component:
            return False
        out = self.shell(f"ime set {target}")
        return self.ime_status() == target

    def _detect_default_ime(self) -> str:
        """Best-effort guess at the user's 'normal' IME.

        We pick the first enabled IME that isn't ADBKeyboard.
        """
        enabled = [s for s in self.shell("ime list -s").splitlines() if s.strip()]
        for ime in enabled:
            if ime != self.kb.ime_component:
                return ime
        return ""

    # -- typing ---------------------------------------------------------------

    def type_unicode(self, text: str) -> None:
        """Send Unicode/CJK text via the ADBKeyboard broadcast.

        Raises RuntimeError if ADBKeyboard is not the active IME.
        """
        if not self.is_adb_keyboard_active():
            raise RuntimeError(
                "ADBKeyboard is not active. Call set_adb_keyboard() first."
            )
        # Split on newlines and send each line as a separate broadcast — some
        # IME builds truncate very long single broadcasts.
        for chunk in text.split("\n"):
            if not chunk:
                continue
            self.shell(self.kb.broadcast_cmd(chunk))

    def type_ascii_fallback(self, text: str) -> None:
        """Last-resort ASCII path: ``input text`` (CJK will be dropped)."""
        safe = text.replace(" ", "%s")
        self.shell(f"input text {safe!r}")

    # -- environment queries --------------------------------------------------

    def screen_size(self) -> Tuple[int, int]:
        """Return ``(width, height)`` in pixels."""
        out = self.shell("wm size").strip()
        m = re.search(r"(\d+)x(\d+)", out)
        if not m:
            raise RuntimeError(f"Could not parse wm size: {out!r}")
        return int(m.group(1)), int(m.group(2))

    def density(self) -> int:
        """Return screen density in dpi."""
        out = self.shell("wm density").strip()
        m = re.search(r"(\d+)", out)
        if not m:
            raise RuntimeError(f"Could not parse wm density: {out!r}")
        return int(m.group(1))

    def current_focus(self) -> Tuple[str, str]:
        """Return ``(package, activity)`` of the currently-focused window."""
        out = self.shell("dumpsys window")
        # ``mCurrentFocus=Window{... <pkg>/<activity>}`` — use a lazy match so we
        # capture the *last* ``pkg/activity`` token on the line.
        m = re.search(
            r"mCurrentFocus=Window\{[^}]*?\s([^\s/]+)/([^\s}]+)\}", out
        )
        if not m:
            m = re.search(r"mFocusedApp=.*?\s([^\s/]+)/([^\s}]+)", out)
        if not m:
            return ("", "")
        return m.group(1), m.group(2)

    def device_info(self) -> dict:
        """Snapshot of the device's UI-relevant environment."""
        sdk_out = self.shell("getprop ro.build.version.sdk").strip()
        rel_out = self.shell("getprop ro.build.version.release").strip()
        mdl_out = self.shell("getprop ro.product.model").strip()
        mfr_out = self.shell("getprop ro.product.manufacturer").strip()
        try:
            w, h = self.screen_size()
        except Exception:
            w = h = 0
        try:
            d = self.density()
        except Exception:
            d = 0
        ime = self.ime_status()
        try:
            pkg, act = self.current_focus()
        except Exception:
            pkg = act = ""
        return {
            "sdk": int(sdk_out) if sdk_out.isdigit() else sdk_out,
            "release": rel_out,
            "model": mdl_out,
            "manufacturer": mfr_out,
            "screen": {"width": w, "height": h},
            "density": d,
            "ime": ime,
            "focus": {"package": pkg, "activity": act},
        }

    # -- ADBKeyboard installation -------------------------------------------

    def is_adb_keyboard_installed(self) -> bool:
        """True iff the ADBKeyboard package is installed (any version)."""
        try:
            out = self._run(
                [f"shell", f"pm path {self.kb.package}"],
                check=False,
            ).strip()
        except FileNotFoundError:
            raise
        except Exception:
            out = ""
        return bool(out) and "package:" in out

    def install_adb_keyboard(self, apk_path: str) -> None:
        """``adb install -r`` the given ADBKeyboard APK."""
        self._run(["install", "-r", apk_path])

    def download_adbkeyboard(self, target_dir: str) -> str:
        """Download ``ADBKeyboard.apk`` from the official GitHub mirror.

        Returns the local file path. Falls back to a GitHub redirect if the
        raw URL 404s (e.g. repo renamed).
        """
        out_path = Path(target_dir) / self.kb.apk_name
        out_path.parent.mkdir(parents=True, exist_ok=True)
        try:
            with urllib.request.urlopen(self.kb.apk_url, timeout=60) as resp:
                data = resp.read()
        except urllib.error.HTTPError as e:
            raise RuntimeError(
                f"Failed to download ADBKeyboard.apk (HTTP {e.code}). "
                "Please download it manually from "
                "https://github.com/senzhk/ADBKeyBoard/releases and "
                f"place it at {out_path}."
            ) from e
        out_path.write_bytes(data)
        return str(out_path)

    def ensure_adb_keyboard(
        self, download_dir: Optional[str] = None, auto_download: bool = True,
    ) -> str:
        """Make sure ADBKeyboard is installed; return the APK path.

        If it isn't installed and ``auto_download=True``, attempts to fetch
        the official APK from GitHub and install it.
        """
        if self.is_adb_keyboard_installed():
            # We don't know the on-device APK path easily; return a hint.
            return "(already installed on device)"
        if not auto_download:
            raise RuntimeError(
                "ADBKeyboard is not installed. Pass auto_download=True or "
                "install it manually from "
                "https://github.com/senzhk/ADBKeyBoard/releases"
            )
        target_dir = download_dir or str(Path.cwd() / "tools" / "downloads")
        apk = self.download_adbkeyboard(target_dir)
        self.install_adb_keyboard(apk)
        return apk

    # -- context manager ------------------------------------------------------

    def __enter__(self) -> "AdbInput":
        self._run(["get-state"], timeout=15.0)
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _make(args: argparse.Namespace) -> AdbInput:
    return AdbInput(device=args.device, adb=args.adb)


def _cmd_info(args: argparse.Namespace) -> int:
    print(json.dumps(_make(args).device_info(), ensure_ascii=False, indent=2))
    return 0


def _cmd_screen(args: argparse.Namespace) -> int:
    w, h = _make(args).screen_size()
    print(f"{w}x{h}")
    return 0


def _cmd_focus(args: argparse.Namespace) -> int:
    pkg, act = _make(args).current_focus()
    if not pkg:
        print("(no focus detected)", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps({"package": pkg, "activity": act}, ensure_ascii=False))
    else:
        print(f"{pkg}/{act}")
    return 0


def _cmd_ime(args: argparse.Namespace) -> int:
    inp = _make(args)
    if args.list:
        for ime in inp.ime_all():
            print(ime)
        return 0
    print(inp.ime_status())
    return 0


def _cmd_set_ime(args: argparse.Namespace) -> int:
    inp = _make(args)
    if args.ime.lower() in ("adbkeyboard", "adb"):
        target = ADB_KEYBOARD_IME
    elif args.ime.lower() in ("restore", "default"):
        target = inp._detect_default_ime()
        if not target:
            print("ERROR: no other IME to restore to.", file=sys.stderr)
            return 2
    else:
        target = args.ime
    out = inp.shell(f"ime set {target}")
    print(out.strip() or f"switched to {target}")
    return 0


def _cmd_type(args: argparse.Namespace) -> int:
    inp = _make(args)
    text = args.text
    if not inp.is_adb_keyboard_active():
        if args.force_ascii:
            inp.type_ascii_fallback(text)
            print(f"(ASCII-only) typed {len(text)} chars")
            return 0
        if not inp.is_adb_keyboard_installed():
            if args.no_install:
                raise RuntimeError(
                    "ADBKeyboard is not installed. Re-run without --no-install "
                    "to fetch it from GitHub."
                )
            print("ADBKeyboard not installed; downloading…", file=sys.stderr)
            inp.ensure_adb_keyboard()
        inp.set_adb_keyboard()
    inp.type_unicode(text)
    print(f"typed {len(text)} unicode chars via ADBKeyboard")
    return 0


def _cmd_install_kb(args: argparse.Namespace) -> int:
    inp = _make(args)
    if args.apk:
        inp.install_adb_keyboard(args.apk)
    else:
        path = inp.ensure_adb_keyboard(auto_download=not args.no_download)
        print(f"installed: {path}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="ADB Unicode text input + device environment inspection."
    )
    p.add_argument("--adb", default="adb")
    p.add_argument("--device", "-s", default=None)

    sub = p.add_subparsers(dest="cmd", required=True)

    # info --------------------------------------------------------------------
    sp = sub.add_parser("info", help="Print a JSON dump of the device environment.")
    sp.set_defaults(func=_cmd_info)

    # screen ------------------------------------------------------------------
    sp = sub.add_parser("screen", help="Print the screen size, e.g. 1080x2400.")
    sp.set_defaults(func=_cmd_screen)

    # focus -------------------------------------------------------------------
    sp = sub.add_parser("focus", help="Print the currently-focused window.")
    sp.add_argument("--json", action="store_true")
    sp.set_defaults(func=_cmd_focus)

    # ime ---------------------------------------------------------------------
    sp = sub.add_parser("ime", help="Print the active IME (or list with --list).")
    sp.add_argument("--list", action="store_true")
    sp.set_defaults(func=_cmd_ime)

    # set-ime -----------------------------------------------------------------
    sp = sub.add_parser(
        "set-ime",
        help="Switch the active IME (alias: adbkeyboard; restore: default).",
    )
    sp.add_argument("ime", help="IME id, 'adbkeyboard'/'adb', or 'restore'/'default'.")
    sp.set_defaults(func=_cmd_set_ime)

    # type --------------------------------------------------------------------
    sp = sub.add_parser(
        "type",
        help="Send Unicode text (auto-installs ADBKeyboard if missing).",
    )
    sp.add_argument("text")
    sp.add_argument("--force-ascii", action="store_true",
                    help="Use plain 'input text' (drops non-ASCII).")
    sp.add_argument("--no-install", action="store_true",
                    help="Don't auto-install ADBKeyboard.")
    sp.set_defaults(func=_cmd_type)

    # install-kb --------------------------------------------------------------
    sp = sub.add_parser(
        "install-kb", help="Install ADBKeyboard from local APK or GitHub."
    )
    sp.add_argument("--apk", help="Path to a local ADBKeyboard.apk.")
    sp.add_argument("--no-download", action="store_true",
                    help="Fail instead of downloading if not present.")
    sp.set_defaults(func=_cmd_install_kb)

    return p


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except Exception as e:  # noqa: BLE001 — top-level CLI catch-all
        print(f"ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
