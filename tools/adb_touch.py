"""
adb_touch.py — A lightweight wrapper around ADB for inspecting and controlling
the Android UI from a developer machine.

Features:
  - Dump the current UI tree (uiautomator dump) and parse it into typed nodes
  - Find nodes by text / resource-id / class / content-desc (exact or fuzzy)
  - Wait for an element to appear
  - Tap, long-press, swipe, drag, keyevent
  - Take screenshots

CLI example:

    # Tap a button labelled "提交" and dump the resulting screen
    python adb_touch.py tap --text 提交
    python adb_touch.py dump --text 提交 --json

    # Long-press on coordinates
    python adb_touch.py longpress 540 1200 --duration 1500

    # Swipe between two visible elements
    python adb_touch.py swipe --from-text "列表项1" --to-text "列表项2"

    # Wait for an element to appear
    python adb_touch.py wait --text "支付成功" --timeout 15

Library example:

    from adb_touch import AdbTouch

    with AdbTouch() as bot:
        bot.tap_text("提交")
        bot.wait_for(text="支付成功", timeout=15)
        bot.screencap("after.png")

Notes:
  - ADB must be in your PATH (or pass --adb /path/to/adb).
  - For Chinese text input via `input text`, install ADBKeyboard on the device:
      https://github.com/senzhk/ADBKeyBoard
    Then call  adb shell ime set com.android.adbkeyboard/.AdbIME
    and       adb shell am broadcast -a ADB_INPUT_TEXT --es msg "中文"
"""
from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterator, List, Optional, Sequence, Tuple


__all__ = [
    "AdbTouch",
    "AdbError",
    "NodeInfo",
    "DEFAULT_TIMEOUT",
    "WAIT_DEFAULT_TIMEOUT",
    "KEY_NAME_TO_CODE",
    "EXIT_ADB_ERROR",
    "EXIT_USER_ERROR",
    "FIELD_TEXT",
    "FIELD_RESOURCE_ID",
    "FIELD_CLASS",
    "FIELD_CONTENT_DESC",
    "FIELD_PACKAGE",
]


#: Mapping of human-friendly field name -> NodeInfo attribute used by
#: :meth:`AdbTouch.find_regex` and the ``find-regex`` CLI subcommand.
REGEX_FIELDS = {
    "text": "text",
    "resource-id": "resource_id",
    "rid": "resource_id",
    "class": "class_name",
    "class-name": "class_name",
    "content-desc": "content_desc",
    "desc": "content_desc",
    "package": "package",
}
FIELD_TEXT = "text"
FIELD_RESOURCE_ID = "resource_id"
FIELD_CLASS = "class_name"
FIELD_CONTENT_DESC = "content_desc"
FIELD_PACKAGE = "package"


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

#: Default adb subprocess timeout (seconds).
DEFAULT_TIMEOUT = 30.0

#: Default timeout for ``wait_for`` polling (seconds).
WAIT_DEFAULT_TIMEOUT = 10.0

#: Default interval between polls in ``wait_for`` (seconds).
WAIT_POLL_INTERVAL = 0.4

#: Default timeout for a one-shot adb get-state check (seconds).
GET_STATE_TIMEOUT = 10.0

# ---------------------------------------------------------------------------
# Exit codes
# ---------------------------------------------------------------------------

#: An adb subprocess failed or returned a non-zero exit code.
EXIT_ADB_ERROR = 1
#: The caller asked for something the device did not have on screen.
EXIT_USER_ERROR = 2

#: Human-friendly names for common keyevent codes.
KEY_NAME_TO_CODE = {
    "home": 3,
    "back": 4,
    "call": 5,
    "endcall": 6,
    "enter": 66,
    "tab": 61,
    "space": 62,
    "delete": 67,
    "recent": 187,
    "power": 26,
    "volume_up": 24,
    "volume_down": 25,
}


# ---------------------------------------------------------------------------
# Regular expressions
# ---------------------------------------------------------------------------

_BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------

@dataclass
class NodeInfo:
    """A single UI node parsed from a uiautomator dump."""

    text: str
    resource_id: str
    class_name: str
    content_desc: str
    package: str
    bounds: Tuple[int, int, int, int]  # (x1, y1, x2, y2) in screen pixels
    clickable: bool
    enabled: bool
    focused: bool
    long_clickable: bool
    scrollable: bool

    @property
    def center(self) -> Tuple[int, int]:
        """Center point of the node in screen pixels."""
        x1, y1, x2, y2 = self.bounds
        return ((x1 + x2) // 2, (y1 + y2) // 2)

    @property
    def width(self) -> int:
        return self.bounds[2] - self.bounds[0]

    @property
    def height(self) -> int:
        return self.bounds[3] - self.bounds[1]

    def to_dict(self) -> dict:
        return {
            "text": self.text,
            "resource_id": self.resource_id,
            "class": self.class_name,
            "content_desc": self.content_desc,
            "package": self.package,
            "bounds": list(self.bounds),
            "center": list(self.center),
            "clickable": self.clickable,
            "enabled": self.enabled,
            "focused": self.focused,
            "long_clickable": self.long_clickable,
            "scrollable": self.scrollable,
        }


# ---------------------------------------------------------------------------
# ADB wrapper
# ---------------------------------------------------------------------------

class AdbError(RuntimeError):
    """Raised when an ADB command fails or returns a non-zero exit code."""


class AdbTouch:
    """A small wrapper that runs ``adb`` against a single device."""

    def __init__(self, device: Optional[str] = None, adb: str = "adb") -> None:
        self.adb = adb
        self.device = device

    # -- low-level helpers ----------------------------------------------------

    def _build_cmd(self, args: Sequence[str]) -> List[str]:
        cmd: List[str] = [self.adb]
        if self.device:
            cmd += ["-s", self.device]
        cmd += list(args)
        return cmd

    def _run(self,
             args: Sequence[str],
             timeout: float = DEFAULT_TIMEOUT,
             check: bool = True,
             input_bytes: Optional[bytes] = None) -> str:
        cmd = self._build_cmd(args)
        try:
            proc = subprocess.run(
                cmd,
                input=input_bytes,
                capture_output=True,
                timeout=timeout,
                check=False,
            )
        except FileNotFoundError as e:
            raise AdbError(
                f"ADB executable not found: {self.adb!r}. "
                "Install Android Platform Tools or pass --adb /path/to/adb."
            ) from e
        if check and proc.returncode != 0:
            err = proc.stderr.decode("utf-8", errors="replace").strip()
            raise AdbError(f"adb command failed ({proc.returncode}): {cmd}\n{err}")
        return proc.stdout.decode("utf-8", errors="replace")

    def shell(self, command: str, timeout: float = DEFAULT_TIMEOUT,
              check: bool = True) -> str:
        """Run ``adb shell <command>`` and return stdout."""
        return self._run(["shell", command], timeout=timeout, check=check)

    def screencap(self, save_path: Optional[str] = None) -> bytes:
        """Take a PNG screenshot. If ``save_path`` is given, also write to disk."""
        # ``exec-out screencap -p`` returns raw PNG bytes; we deliberately bypass
        # :meth:`_run` so we don't decode them to ``str``.
        cmd = self._build_cmd(["exec-out", "screencap", "-p"])
        try:
            proc = subprocess.run(
                cmd, capture_output=True, timeout=DEFAULT_TIMEOUT, check=False
            )
        except FileNotFoundError as e:
            raise AdbError(
                f"ADB executable not found: {self.adb!r}. "
                "Install Android Platform Tools or pass --adb /path/to/adb."
            ) from e
        if proc.returncode != 0:
            err = proc.stderr.decode("utf-8", errors="replace").strip()
            raise AdbError(f"adb command failed ({proc.returncode}): {cmd}\n{err}")
        png = proc.stdout
        if isinstance(png, str):
            # Defensive: encode back if the platform somehow returned text.
            png = png.encode("latin-1", errors="replace")
        if save_path:
            Path(save_path).write_bytes(png)
        return png

    # -- UI dump / search -----------------------------------------------------

    @classmethod
    def _parse_bounds(cls, b: str) -> Tuple[int, int, int, int]:
        """Parse a bounds string like ``"[108,540][396,672]"`` into a 4-tuple."""
        m = _BOUNDS_RE.match(b or "")
        if not m:
            return (0, 0, 0, 0)
        return (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4)))

    @classmethod
    def _node_from_element(cls, el: ET.Element) -> NodeInfo:
        return NodeInfo(
            text=(el.get("text") or ""),
            resource_id=(el.get("resource-id") or ""),
            class_name=(el.get("class") or ""),
            content_desc=(el.get("content-desc") or ""),
            package=(el.get("package") or ""),
            bounds=cls._parse_bounds(el.get("bounds") or ""),
            clickable=(el.get("clickable") == "true"),
            enabled=(el.get("enabled") == "true"),
            focused=(el.get("focused") == "true"),
            long_clickable=(el.get("long-clickable") == "true"),
            scrollable=(el.get("scrollable") == "true"),
        )

    def iter_nodes(self, root: ET.Element) -> Iterator[NodeInfo]:
        """Yield :class:`NodeInfo` for every ``<node>`` element under ``root``."""
        for el in root.iter("node"):
            yield self._node_from_element(el)

    def dump_ui(self) -> ET.Element:
        """Run ``uiautomator dump``, pull the XML, return its root element."""
        self.shell("uiautomator dump /sdcard/ui_dump.xml")
        xml_bytes = self._run(["exec-out", "cat", "/sdcard/ui_dump.xml"])
        # ``_run`` decodes stdout to ``str`` on success, but in some Python /
        # platform combinations (e.g. when stdout was already decoded by an
        # earlier wrapper) it can come back as ``bytes``. Handle both.
        if isinstance(xml_bytes, bytes):
            text = xml_bytes.decode("utf-8", errors="replace")
        else:
            text = xml_bytes
        # Some shells truncate the very last byte; close the root tag if missing.
        if "</hierarchy>" not in text:
            text += "</hierarchy>"
        try:
            return ET.fromstring(text)
        except ET.ParseError as e:
            raise AdbError(
                f"Failed to parse UI dump: {e}\nFirst 500 chars: {text[:500]}"
            ) from e

    def find(self,
             text: Optional[str] = None,
             resource_id: Optional[str] = None,
             class_name: Optional[str] = None,
             content_desc: Optional[str] = None,
             exact: bool = True,
             clickable_only: bool = False,
             ) -> List[NodeInfo]:
        """Find nodes that match all the given criteria.

        - ``text`` / ``resource_id`` / ``class_name`` / ``content_desc``
          use substring matching unless ``exact=True``.
        - ``clickable_only`` filters to nodes that can actually receive taps.
        """
        root = self.dump_ui()
        results: List[NodeInfo] = []
        for n in self.iter_nodes(root):
            if clickable_only and not n.clickable:
                continue
            if not _matches(n.text, text, exact):
                continue
            if not _matches(n.resource_id, resource_id, exact):
                continue
            if not _matches(n.class_name, class_name, exact):
                continue
            if not _matches(n.content_desc, content_desc, exact):
                continue
            results.append(n)
        return results

    def find_one(self, **kwargs) -> Optional[NodeInfo]:
        results = self.find(**kwargs)
        return results[0] if results else None

    def find_regex(self,
                   field: str,
                   pattern: str,
                   clickable_only: bool = False,
                   flags: int = 0,
                   ) -> List[NodeInfo]:
        """Find nodes whose ``field`` matches the regex ``pattern``.

        ``field`` is one of ``text``, ``resource-id``, ``class``,
        ``content-desc``, ``package`` (see :data:`REGEX_FIELDS`).
        Use ``flags=re.IGNORECASE`` for case-insensitive matches.
        """
        attr = REGEX_FIELDS.get(field)
        if attr is None:
            raise ValueError(
                f"Unknown field {field!r}; choose from {sorted(REGEX_FIELDS)}."
            )
        rx = re.compile(pattern, flags)
        root = self.dump_ui()
        results: List[NodeInfo] = []
        for n in self.iter_nodes(root):
            if clickable_only and not n.clickable:
                continue
            value = getattr(n, attr) or ""
            if rx.search(value):
                results.append(n)
        return results

    def wait_for(self,
                 text: Optional[str] = None,
                 resource_id: Optional[str] = None,
                 class_name: Optional[str] = None,
                 content_desc: Optional[str] = None,
                 exact: bool = True,
                 timeout: float = WAIT_DEFAULT_TIMEOUT,
                 poll_interval: float = WAIT_POLL_INTERVAL,
                 ) -> Optional[NodeInfo]:
        """Poll the UI until a matching node appears or ``timeout`` elapses.

        Raises :class:`KeyboardInterrupt` immediately if the user presses Ctrl+C
        instead of waiting for the next poll to expire.
        """
        deadline = time.time() + timeout
        while time.time() < deadline:
            try:
                node = self.find_one(
                    text=text,
                    resource_id=resource_id,
                    class_name=class_name,
                    content_desc=content_desc,
                    exact=exact,
                )
            except KeyboardInterrupt:
                raise
            except AdbError:
                # Transient adb hiccup; keep polling until the deadline.
                node = None
            if node is not None:
                return node
            try:
                time.sleep(poll_interval)
            except KeyboardInterrupt:
                raise
        return None

    def wait_for_stable(self,
                        predicate: Optional[Callable[[List[NodeInfo]], bool]] = None,
                        stable_runs: int = 2,
                        poll_interval: float = 0.4,
                        timeout: float = WAIT_DEFAULT_TIMEOUT,
                        min_nodes: int = 0,
                        ) -> Optional[List[NodeInfo]]:
        """Poll until the UI dump stops changing for ``stable_runs`` consecutive polls.

        Useful to avoid tapping mid-animation: tap once the screen has been
        visually stable for a moment.

        - ``predicate`` is called with the current node list; if it returns
          ``False`` the wait continues (the screen may be in the middle of a
          transition we don't care about). If ``None``, any non-empty dump
          counts as "ready".
        - ``stable_runs`` controls how many identical consecutive dumps are
          required before we return.
        - ``min_nodes`` requires the dump to contain at least this many nodes
          before stability is accepted (useful for "wait until Settings
          finishes loading").
        """
        deadline = time.time() + timeout
        last_signature: Optional[Tuple] = None
        stable_count = 0
        while time.time() < deadline:
            try:
                root = self.dump_ui()
                nodes = list(self.iter_nodes(root))
            except AdbError:
                nodes = []
            signature = tuple(
                (n.text, n.resource_id, n.class_name, n.bounds)
                for n in nodes
            )
            if predicate and not predicate(nodes):
                last_signature = None
                stable_count = 0
            elif signature == last_signature and signature and len(nodes) >= min_nodes:
                stable_count += 1
                if stable_count >= stable_runs:
                    return nodes
            else:
                last_signature = signature
                stable_count = 1 if signature and len(nodes) >= min_nodes else 0
            try:
                time.sleep(poll_interval)
            except KeyboardInterrupt:
                raise
        return None

    # -- actions --------------------------------------------------------------

    def tap(self, x: int, y: int) -> None:
        """Tap at screen coordinates ``(x, y)``."""
        self.shell(f"input tap {x} {y}")

    def tap_node(self, node: NodeInfo) -> None:
        """Tap the center of a :class:`NodeInfo`."""
        self.tap(*node.center)

    def tap_text(self,
                 text: str,
                 exact: bool = True,
                 timeout: float = WAIT_DEFAULT_TIMEOUT) -> bool:
        """Wait for an element with the given text and tap it. Returns True on success."""
        node = self.wait_for(text=text, exact=exact, timeout=timeout)
        if node is None:
            return False
        self.tap_node(node)
        return True

    def long_press(self, x: int, y: int, duration_ms: int = 1000) -> None:
        """Long-press at ``(x, y)`` by holding a zero-distance swipe.

        Uses ``input swipe x y x y duration`` which is the most reliable
        cross-OEM trick; some vendor ROMs ignore plain ``input tap``-based
        long-press attempts.
        """
        self.shell(f"input swipe {x} {y} {x} {y} {duration_ms}")

    def swipe(self,
              x1: int, y1: int, x2: int, y2: int,
              duration_ms: int = 300) -> None:
        """Swipe from ``(x1, y1)`` to ``(x2, y2)`` over ``duration_ms``."""
        self.shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")

    def drag(self,
             x1: int, y1: int, x2: int, y2: int,
             duration_ms: int = 1000) -> None:
        """Android 12+ drag-and-drop gesture (stays pressed across the path)."""
        self.shell(f"input draganddrop {x1} {y1} {x2} {y2} {duration_ms}")

    def keyevent(self, code: int) -> None:
        """Send an Android ``input keyevent`` with the given numeric code."""
        self.shell(f"input keyevent {code}")

    def press_back(self) -> None:
        self.keyevent(4)

    def press_home(self) -> None:
        self.keyevent(3)

    def press_recent(self) -> None:
        self.keyevent(187)

    def type_text(self, text: str) -> None:
        """Type ASCII text via ``input text``. Use ADBKeyboard for Unicode / Chinese."""
        safe = text.replace(" ", "%s")
        self.shell(f"input text {shlex.quote(safe)}")

    def shell_unicode(self, text: str, force_ascii: bool = False) -> str:
        """Smart text input: ADBKeyboard broadcast when available, else ASCII fallback.

        Returns the strategy used (``"adbkeyboard"`` or ``"ascii"``).
        Raises :class:`AdbError` if ADBKeyboard is required but not active and
        ``force_ascii`` is False.
        """
        def _current_ime() -> str:
            # ``cmd input_method get-current`` (Android 12+) returns the
            # currently focused IME id directly; if it's unavailable on this
            # build we fall back to ``settings get secure default_input_method``
            # which is always there from API 16+.
            try:
                out = self.shell(
                    "cmd input_method get-current", check=False
                ).strip()
                if out and "Unknown command" not in out and "Not found" not in out:
                    return out
            except AdbError:
                pass
            try:
                out = self.shell(
                    "settings get secure default_input_method", check=False
                ).strip()
                # ``settings get`` on a missing key prints "null" on some images
                # — normalise to empty so callers can detect "no IME active".
                if out and out != "null":
                    return out
            except AdbError:
                pass
            return ""

        current_ime = _current_ime()
        if force_ascii or "adbkeyboard" not in current_ime.lower():
            self.type_text(text)
            return "ascii"
        # Mirror what adb_input.py does; kept self-contained to avoid a
        # cross-import cycle with adb_input.ADKeyboard.
        for chunk in text.split("\n"):
            if not chunk:
                continue
            safe = chunk.replace("'", "'\\''")
            self.shell(
                f"am broadcast -a ADB_INPUT_TEXT --es msg '{safe}'"
            )
        return "adbkeyboard"

    # -- app management -------------------------------------------------------

    def start_activity(self, package: str, activity: Optional[str] = None) -> str:
        """Launch an app via ``am start``.

        If ``activity`` is omitted, the LAUNCHER activity is resolved
        automatically with ``cmd package resolve-activity``.
        Returns the resolved component.
        """
        if activity:
            component = f"{package}/{activity.lstrip('/')}"
            self.shell(f"am start -W -n {component}")
            return component
        out = self.shell(
            f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {package}"
        )
        # ``resolve-activity --brief`` prints the component on the last non-empty line.
        component = next(
            (line.strip() for line in reversed(out.splitlines()) if line.strip()),
            "",
        )
        if not component or "/" not in component:
            raise AdbError(
                f"Could not resolve launcher activity for {package!r}: {out!r}"
            )
        self.shell(f"am start -W -n {component}")
        return component

    def stop_app(self, package: str) -> None:
        """``am force-stop`` the given package."""
        self.shell(f"am force-stop {package}")

    def current_package(self) -> str:
        """Return the package name of the currently-focused window (best effort)."""
        out = self.shell("dumpsys window")
        # Use lazy match + word-character class so we don't bleed across
        # spaces or extra slashes inside the window descriptor.
        m = re.search(
            r"mCurrentFocus=Window\{[^}]*?\s([^\s/]+)/[^\s}]+\}", out
        )
        if not m:
            m = re.search(r"mFocusedApp=.*?\s([^\s/]+)/[^\s}]+", out)
        return m.group(1) if m else ""

    # -- context manager ------------------------------------------------------

    def __enter__(self) -> "AdbTouch":
        # Sanity-check that the device is reachable.
        self._run(["get-state"], timeout=GET_STATE_TIMEOUT)
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        return None


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _matches(value: str, pattern: Optional[str], exact: bool) -> bool:
    """Return True iff ``value`` matches ``pattern`` (None matches everything)."""
    if pattern is None:
        return True
    return value == pattern if exact else pattern in value


def _format_bounds(b: Tuple[int, int, int, int]) -> str:
    """Render bounds as ``"[x1,y1][x2,y2]"`` for compact CLI output."""
    x1, y1, x2, y2 = b
    return f"[{x1},{y1}][{x2},{y2}]"


def _make_bot(args: argparse.Namespace) -> AdbTouch:
    """Build an :class:`AdbTouch` from CLI ``--adb`` and ``--device`` flags."""
    return AdbTouch(device=args.device, adb=args.adb)


# ---------------------------------------------------------------------------
# CLI handlers
# ---------------------------------------------------------------------------

def _cmd_dump(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    nodes = bot.find(
        text=args.text,
        resource_id=args.resource_id,
        class_name=args.class_,
        content_desc=args.content_desc,
        exact=not args.fuzzy,
    )
    if args.json:
        print(json.dumps([n.to_dict() for n in nodes], ensure_ascii=False, indent=2))
        return 0

    for n in nodes:
        bounds = _format_bounds(n.bounds)
        print(
            f"{bounds:>24}  text={n.text!r}  rid={n.resource_id!r}  "
            f"class={n.class_name}  clickable={n.clickable}"
        )
    return 0


def _cmd_tap(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    if args.text:
        ok = bot.tap_text(args.text, exact=not args.fuzzy, timeout=args.timeout)
        if not ok:
            print(f"ERROR: could not find element with text {args.text!r}", file=sys.stderr)
            return EXIT_USER_ERROR
        return 0

    if args.x is None or args.y is None:
        print("ERROR: either --text or X Y coordinates are required", file=sys.stderr)
        return EXIT_USER_ERROR
    bot.tap(args.x, args.y)
    return 0


def _cmd_longpress(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    bot.long_press(args.x, args.y, duration_ms=args.duration)
    return 0


def _cmd_swipe(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    if args.from_text and args.to_text:
        a = bot.find_one(text=args.from_text, exact=not args.fuzzy)
        b = bot.find_one(text=args.to_text, exact=not args.fuzzy)
        if not a or not b:
            print("ERROR: could not locate one of the swipe endpoints", file=sys.stderr)
            return EXIT_USER_ERROR
        bot.swipe(*a.center, *b.center, duration_ms=args.duration)
        return 0

    missing = [n for n in ("x1", "y1", "x2", "y2") if getattr(args, n) is None]
    if missing:
        print(f"ERROR: missing coordinates: {', '.join(missing)}", file=sys.stderr)
        return EXIT_USER_ERROR
    bot.swipe(args.x1, args.y1, args.x2, args.y2, duration_ms=args.duration)
    return 0


def _cmd_wait(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    node = bot.wait_for(
        text=args.text,
        resource_id=args.resource_id,
        class_name=args.class_,
        content_desc=args.content_desc,
        exact=not args.fuzzy,
        timeout=args.timeout,
    )
    if node is None:
        print("TIMEOUT", file=sys.stderr)
        return EXIT_USER_ERROR
    print(json.dumps(node.to_dict(), ensure_ascii=False))
    return 0


def _cmd_keyevent(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    raw = args.name
    code = KEY_NAME_TO_CODE.get(raw.lower(), None)
    if code is None:
        try:
            code = int(raw)
        except ValueError:
            print(
                f"ERROR: unknown key name {raw!r}. Use one of "
                f"{sorted(KEY_NAME_TO_CODE)} or a numeric code.",
                file=sys.stderr,
            )
            return EXIT_USER_ERROR
    bot.keyevent(code)
    return 0


def _cmd_screencap(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    bot.screencap(args.output)
    print(f"Saved to {args.output}")
    return 0


def _cmd_dump_all(args: argparse.Namespace) -> int:
    """Dump every node on screen to a JSON file (for debugging)."""
    bot = _make_bot(args)
    root = bot.dump_ui()
    nodes = [n.to_dict() for n in bot.iter_nodes(root)]
    Path(args.output).write_text(
        json.dumps(nodes, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"Dumped {len(nodes)} nodes to {args.output}")
    return 0


def _cmd_find_regex(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    flags = re.IGNORECASE if args.ignore_case else 0
    nodes = bot.find_regex(
        args.field, args.pattern,
        clickable_only=args.clickable_only,
        flags=flags,
    )
    if args.json:
        print(json.dumps([n.to_dict() for n in nodes], ensure_ascii=False, indent=2))
        return 0
    for n in nodes:
        bounds = _format_bounds(n.bounds)
        print(
            f"{bounds:>24}  text={n.text!r}  rid={n.resource_id!r}  "
            f"class={n.class_name}  clickable={n.clickable}"
        )
    return 0


def _cmd_wait_stable(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    nodes = bot.wait_for_stable(
        stable_runs=args.stable_runs,
        poll_interval=args.poll_interval,
        timeout=args.timeout,
    )
    if nodes is None:
        print("TIMEOUT (screen never stabilised)", file=sys.stderr)
        return EXIT_USER_ERROR
    print(f"stable after polling; {len(nodes)} nodes visible")
    return 0


def _cmd_start(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    component = bot.start_activity(args.package, args.activity)
    print(f"launched {component}")
    return 0


def _cmd_stop(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    bot.stop_app(args.package)
    print(f"force-stopped {args.package}")
    return 0


def _cmd_current(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    pkg = bot.current_package()
    if not pkg:
        print("(no focus detected)", file=sys.stderr)
        return EXIT_USER_ERROR
    print(pkg)
    return 0


def _cmd_type_unicode(args: argparse.Namespace) -> int:
    bot = _make_bot(args)
    strategy = bot.shell_unicode(args.text, force_ascii=args.ascii)
    print(f"typed {len(args.text)} chars via {strategy}")
    return 0


# ---------------------------------------------------------------------------
# Argument parser
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="ADB-powered UI inspection and touch automation for Android.",
    )
    parser.add_argument("--adb", default="adb", help="Path to the adb binary.")
    parser.add_argument(
        "--device", "-s", default=None,
        help="Target device serial (`adb devices` to list).",
    )

    sub = parser.add_subparsers(dest="cmd", required=True)

    # dump — print matching nodes --------------------------------------------
    sp = sub.add_parser("dump", help="Find UI nodes matching filters and print them.")
    sp.add_argument("--text")
    sp.add_argument("--resource-id")
    sp.add_argument("--class", dest="class_")
    sp.add_argument("--content-desc")
    sp.add_argument("--fuzzy", action="store_true",
                    help="Use substring matching instead of exact.")
    sp.add_argument("--json", action="store_true", help="Emit JSON instead of text.")
    sp.set_defaults(func=_cmd_dump)

    # tap ---------------------------------------------------------------------
    sp = sub.add_parser("tap", help="Tap coordinates, or an element identified by --text.")
    sp.add_argument("x", nargs="?", type=int, help="X coordinate (omit if --text is given).")
    sp.add_argument("y", nargs="?", type=int, help="Y coordinate (omit if --text is given).")
    sp.add_argument("--text", help="Find an element by visible text and tap it.")
    sp.add_argument("--fuzzy", action="store_true")
    sp.add_argument("--timeout", type=float, default=WAIT_DEFAULT_TIMEOUT,
                    help="Seconds to wait when --text is used.")
    sp.set_defaults(func=_cmd_tap)

    # longpress ---------------------------------------------------------------
    sp = sub.add_parser("longpress", help="Long-press on coordinates.")
    sp.add_argument("x", type=int)
    sp.add_argument("y", type=int)
    sp.add_argument("--duration", type=int, default=1000, help="Hold duration in ms.")
    sp.set_defaults(func=_cmd_longpress)

    # swipe -------------------------------------------------------------------
    sp = sub.add_parser("swipe", help="Swipe between two points.")
    sp.add_argument("x1", nargs="?", type=int)
    sp.add_argument("y1", nargs="?", type=int)
    sp.add_argument("x2", nargs="?", type=int)
    sp.add_argument("y2", nargs="?", type=int)
    sp.add_argument("--duration", type=int, default=300, help="Swipe duration in ms.")
    sp.add_argument("--from-text", help="Swipe from the centre of this text element.")
    sp.add_argument("--to-text", help="...to the centre of this text element.")
    sp.add_argument("--fuzzy", action="store_true")
    sp.set_defaults(func=_cmd_swipe)

    # wait --------------------------------------------------------------------
    sp = sub.add_parser("wait", help="Block until a matching element appears.")
    sp.add_argument("--text")
    sp.add_argument("--resource-id")
    sp.add_argument("--class", dest="class_")
    sp.add_argument("--content-desc")
    sp.add_argument("--fuzzy", action="store_true")
    sp.add_argument("--timeout", type=float, default=WAIT_DEFAULT_TIMEOUT,
                    help="Max seconds to wait.")
    sp.set_defaults(func=_cmd_wait)

    # keyevent ----------------------------------------------------------------
    sp = sub.add_parser(
        "keyevent",
        help="Send a key event (e.g. 'back', 'home', or a numeric code).",
    )
    sp.add_argument("name", help="Key name (e.g. back, home, recent) or numeric code.")
    sp.set_defaults(func=_cmd_keyevent)

    # screencap ---------------------------------------------------------------
    sp = sub.add_parser("screencap", help="Take a screenshot.")
    sp.add_argument("output", help="Path to save the PNG.")
    sp.set_defaults(func=_cmd_screencap)

    # dump-all ----------------------------------------------------------------
    sp = sub.add_parser("dump-all", help="Dump every node on screen to a JSON file.")
    sp.add_argument("output", help="Path to save the JSON.")
    sp.set_defaults(func=_cmd_dump_all)

    # find-regex --------------------------------------------------------------
    sp = sub.add_parser(
        "find-regex",
        help="Find nodes whose field matches a regex.",
    )
    sp.add_argument(
        "field",
        choices=sorted(REGEX_FIELDS),
        help="Field to match against (text / resource-id / class / content-desc / package).",
    )
    sp.add_argument("pattern", help="Regex pattern (Python re syntax).")
    sp.add_argument("--ignore-case", action="store_true")
    sp.add_argument("--clickable-only", action="store_true")
    sp.add_argument("--json", action="store_true")
    sp.set_defaults(func=_cmd_find_regex)

    # wait-stable -------------------------------------------------------------
    sp = sub.add_parser(
        "wait-stable",
        help="Block until the UI dump stops changing for N consecutive polls.",
    )
    sp.add_argument("--stable-runs", type=int, default=2)
    sp.add_argument("--poll-interval", type=float, default=0.4)
    sp.add_argument("--timeout", type=float, default=WAIT_DEFAULT_TIMEOUT)
    sp.set_defaults(func=_cmd_wait_stable)

    # start -------------------------------------------------------------------
    sp = sub.add_parser("start", help="Launch an app (auto-resolves LAUNCHER activity).")
    sp.add_argument("package", help="e.g. com.android.settings")
    sp.add_argument("activity", nargs="?", help="Optional component suffix, e.g. .Settings")
    sp.set_defaults(func=_cmd_start)

    # stop --------------------------------------------------------------------
    sp = sub.add_parser("stop", help="Force-stop an app.")
    sp.add_argument("package")
    sp.set_defaults(func=_cmd_stop)

    # current -----------------------------------------------------------------
    sp = sub.add_parser("current", help="Print the package of the currently-focused window.")
    sp.set_defaults(func=_cmd_current)

    # type-unicode ------------------------------------------------------------
    sp = sub.add_parser(
        "type-unicode",
        help="Type text, preferring ADBKeyboard; --ascii falls back to 'input text'.",
    )
    sp.add_argument("text")
    sp.add_argument("--ascii", action="store_true",
                    help="Use plain 'input text' regardless of IME.")
    sp.set_defaults(func=_cmd_type_unicode)

    return parser


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except AdbError as e:
        print(f"ADB ERROR: {e}", file=sys.stderr)
        return EXIT_ADB_ERROR


if __name__ == "__main__":
    sys.exit(main())