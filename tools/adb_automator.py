"""
adb_automator.py — Higher-level automation on top of adb_touch / adb_input.

Two related features:

1. **DSL runner** — describe an automation as a JSON / YAML list of steps
   and execute them in order. Failure snapshots are saved automatically so
   you can inspect what the device was showing when something went wrong.

2. **Recording & playback** — ``adb shell getevent -lt`` is the canonical
   Android input recorder (raw /dev/input/event* events).  This module
   parses a getevent log into ``tap`` / ``swipe`` actions and replays them
   through :class:`AdbTouch`.

CLI examples
------------
Run a DSL script::

    python adb_automator.py run examples/open_settings.json

Record raw events from the device (Ctrl+C to stop)::

    python adb_automator.py record recording.log

Replay a saved recording::

    python adb_automator.py replay recording.log

DSL step format (JSON)
----------------------
Each step is a single-key object; the key is the action, the value its
arguments.  Supported actions: ``tap``, ``long-press``, ``swipe``,
``type``, ``key``, ``start``, ``stop``, ``wait``, ``wait-stable``,
``screenshot``, ``sleep``, ``dump``.

::

    [
      {"start": "com.android.settings"},
      {"wait":   {"text": "设置", "timeout": 10}},
      {"tap":    {"text": "显示", "fuzzy": true}},
      {"swipe":  {"x1": 540, "y1": 1800, "x2": 540, "y2": 600, "duration": 400}},
      {"type":   "你好"},
      {"screenshot": "after.png"}
    ]

Library example
---------------
::

    from adb_automator import Automator
    auto = Automator()
    auto.run_script([
        {"start": "com.android.settings"},
        {"wait":  {"text": "显示"}},
        {"tap":   {"text": "显示"}},
    ])
"""
from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence

from adb_touch import AdbError, AdbTouch  # noqa: E402


__all__ = [
    "Automator",
    "Recording",
    "RecordedEvent",
    "DSLDecodeError",
    "DEFAULT_SCREENSHOT_DIR",
]


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

DEFAULT_SCREENSHOT_DIR = "tools/crash_dumps"

#: ``getevent`` output row pattern (timestamps + absolute axis events).
_GETEVENT_LINE_RE = re.compile(
    r"\[\s*(\d+\.\d+)\]\s+(\S+):\s+"
    r"(EV_KEY|EV_ABS|EV_SYN)\s+"
    r"(\S+)\s+"
    r"([0-9a-fA-F]+)"
)


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------

class DSLDecodeError(ValueError):
    """Raised when a DSL step cannot be decoded."""


# ---------------------------------------------------------------------------
# Recording data model
# ---------------------------------------------------------------------------

@dataclass
class RecordedEvent:
    """One raw event from ``getevent`` output."""
    timestamp: float
    device: str
    type: str          # "EV_KEY" / "EV_ABS" / "EV_SYN"
    code: str          # "BTN_TOUCH" / "ABS_MT_POSITION_X" / ...
    value: int

    def to_dict(self) -> dict:
        return {
            "timestamp": self.timestamp,
            "device": self.device,
            "type": self.type,
            "code": self.code,
            "value": self.value,
        }


@dataclass
class Recording:
    """A parsed recording that can be replayed."""
    events: List[RecordedEvent]

    def to_jsonl(self) -> str:
        return "\n".join(
            json.dumps(e.to_dict(), ensure_ascii=False) for e in self.events
        ) + "\n"

    @classmethod
    def from_jsonl(cls, text: str) -> "Recording":
        events: List[RecordedEvent] = []
        for line in text.splitlines():
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            events.append(RecordedEvent(
                timestamp=float(d["timestamp"]),
                device=str(d["device"]),
                type=str(d["type"]),
                code=str(d["code"]),
                value=int(d["value"], 0) if isinstance(d["value"], str) else int(d["value"]),
            ))
        return cls(events=events)

    @classmethod
    def from_getevent_log(cls, text: str) -> "Recording":
        events: List[RecordedEvent] = []
        for line in text.splitlines():
            m = _GETEVENT_LINE_RE.search(line)
            if not m:
                continue
            ts, dev, ev_type, code, raw = m.groups()
            events.append(RecordedEvent(
                timestamp=float(ts),
                device=dev,
                type=ev_type,
                code=code,
                value=int(raw, 16),
            ))
        return cls(events=events)


# ---------------------------------------------------------------------------
# Automator
# ---------------------------------------------------------------------------

class Automator:
    """Drives :class:`AdbTouch` according to a list of DSL steps."""

    SUPPORTED_ACTIONS = {
        "tap", "long-press", "longpress", "swipe", "drag",
        "type", "key", "start", "stop",
        "wait", "wait-stable", "wait-stable-ui",
        "screenshot", "sleep", "dump",
    }

    def __init__(
        self,
        adb_touch: Optional[AdbTouch] = None,
        screenshot_dir: str = DEFAULT_SCREENSHOT_DIR,
        fail_screenshot_prefix: str = "fail_",
    ) -> None:
        self.bot = adb_touch or AdbTouch()
        self.screenshot_dir = Path(screenshot_dir)
        self.screenshot_dir.mkdir(parents=True, exist_ok=True)
        self.fail_prefix = fail_screenshot_prefix

    # -- step dispatch --------------------------------------------------------

    def run_step(self, step: Dict[str, Any]) -> Any:
        """Execute a single step. ``step`` is ``{action: args}``."""
        if not isinstance(step, dict) or len(step) != 1:
            raise DSLDecodeError(f"step must be a single-key dict, got {step!r}")
        action, raw_args = next(iter(step.items()))
        action_norm = self._normalise_action(action)
        if action_norm not in self.SUPPORTED_ACTIONS:
            raise DSLDecodeError(
                f"unknown action {action!r}; "
                f"supported: {sorted(self.SUPPORTED_ACTIONS)}"
            )
        method = getattr(self, f"_do_{action_norm.replace('-', '_')}")
        return method(raw_args)

    def run_script(
        self,
        steps: Iterable[Dict[str, Any]],
        stop_on_failure: bool = True,
    ) -> List[Any]:
        """Run a sequence of steps; returns per-step results."""
        results: List[Any] = []
        for i, step in enumerate(steps, start=1):
            try:
                result = self.run_step(step)
            except Exception as e:  # noqa: BLE001
                snap = self.screenshot_dir / f"{self.fail_prefix}{i:03d}.png"
                try:
                    self.bot.screencap(str(snap))
                    err = f"{type(e).__name__}: {e}; screenshot={snap}"
                except Exception:
                    err = f"{type(e).__name__}: {e}"
                if stop_on_failure:
                    raise RuntimeError(f"step {i} failed: {err}") from e
                results.append({"error": err})
                continue
            results.append(result)
        return results

    @staticmethod
    def _normalise_action(action: str) -> str:
        return action.lower().replace("_", "-")

    # -- actions --------------------------------------------------------------

    def _do_tap(self, args: Any) -> str:
        if isinstance(args, dict):
            if "text" in args:
                ok = self.bot.tap_text(
                    args["text"], exact=not args.get("fuzzy", False),
                    timeout=float(args.get("timeout", 10)),
                )
                if not ok:
                    raise RuntimeError(f"tap: text not found {args['text']!r}")
                return f"tap text={args['text']!r}"
            if "resource-id" in args or "rid" in args:
                rid = args.get("resource-id") or args["rid"]
                node = self.bot.find_one(resource_id=rid, exact=not args.get("fuzzy", False))
                if not node:
                    raise RuntimeError(f"tap: resource-id not found {rid!r}")
                self.bot.tap_node(node)
                return f"tap rid={rid!r}"
            if "x" in args and "y" in args:
                self.bot.tap(int(args["x"]), int(args["y"]))
                return f"tap ({args['x']},{args['y']})"
        if isinstance(args, (list, tuple)) and len(args) == 2:
            self.bot.tap(int(args[0]), int(args[1]))
            return f"tap ({args[0]},{args[1]})"
        raise DSLDecodeError(f"tap: cannot interpret args {args!r}")

    def _do_long_press(self, args: Any) -> str:
        if not isinstance(args, dict) or "x" not in args or "y" not in args:
            raise DSLDecodeError("long-press requires {x, y, duration?}")
        duration = int(args.get("duration", 1000))
        self.bot.long_press(int(args["x"]), int(args["y"]), duration_ms=duration)
        return f"long-press ({args['x']},{args['y']}) {duration}ms"

    def _do_swipe(self, args: Any) -> str:
        if not isinstance(args, dict):
            raise DSLDecodeError("swipe requires a dict")
        for k in ("x1", "y1", "x2", "y2"):
            if k not in args:
                raise DSLDecodeError(f"swipe missing {k}")
        duration = int(args.get("duration", 300))
        self.bot.swipe(
            int(args["x1"]), int(args["y1"]),
            int(args["x2"]), int(args["y2"]),
            duration_ms=duration,
        )
        return (
            f"swipe ({args['x1']},{args['y1']}) -> "
            f"({args['x2']},{args['y2']}) {duration}ms"
        )

    def _do_drag(self, args: Any) -> str:
        if not isinstance(args, dict):
            raise DSLDecodeError("drag requires a dict")
        for k in ("x1", "y1", "x2", "y2"):
            if k not in args:
                raise DSLDecodeError(f"drag missing {k}")
        duration = int(args.get("duration", 1000))
        self.bot.drag(
            int(args["x1"]), int(args["y1"]),
            int(args["x2"]), int(args["y2"]),
            duration_ms=duration,
        )
        return f"drag ({args['x1']},{args['y1']}) -> ({args['x2']},{args['y2']})"

    def _do_type(self, args: Any) -> str:
        text = args if isinstance(args, str) else args.get("text", "")
        strategy = self.bot.shell_unicode(str(text))
        return f"type ({strategy}) {len(text)} chars"

    def _do_key(self, args: Any) -> str:
        if isinstance(args, str):
            from adb_touch import KEY_NAME_TO_CODE
            if args in KEY_NAME_TO_CODE:
                code = KEY_NAME_TO_CODE[args]
            else:
                code = int(args)
        else:
            code = int(args.get("code", args.get("keycode", 0)))
        self.bot.keyevent(code)
        return f"keyevent {code}"

    def _do_start(self, args: Any) -> str:
        if isinstance(args, str):
            return self.bot.start_activity(args)
        if isinstance(args, dict):
            return self.bot.start_activity(args["package"], args.get("activity"))
        raise DSLDecodeError("start requires package string or {package, activity} dict")

    def _do_stop(self, args: Any) -> str:
        if not isinstance(args, str):
            raise DSLDecodeError("stop requires a package string")
        self.bot.stop_app(args)
        return f"force-stop {args}"

    def _do_wait(self, args: Any) -> str:
        if not isinstance(args, dict):
            raise DSLDecodeError("wait requires a dict")
        kwargs = {}
        for src, dst in (("text", "text"), ("resource-id", "resource_id"),
                         ("rid", "resource_id"), ("class", "class_name"),
                         ("content-desc", "content_desc"), ("desc", "content_desc")):
            if src in args:
                kwargs[dst] = args[src]
        kwargs["exact"] = not args.get("fuzzy", False)
        kwargs["timeout"] = float(args.get("timeout", 10))
        node = self.bot.wait_for(**kwargs)
        if node is None:
            raise RuntimeError(f"wait timeout ({args})")
        return f"wait ok: {node.text!r}"

    def _do_wait_stable(self, args: Any) -> str:
        if args is None:
            args = {}
        if not isinstance(args, dict):
            raise DSLDecodeError("wait-stable requires a dict or null")
        nodes = self.bot.wait_for_stable(
            stable_runs=int(args.get("stable-runs", 2)),
            poll_interval=float(args.get("poll-interval", 0.4)),
            timeout=float(args.get("timeout", 10)),
        )
        if nodes is None:
            raise RuntimeError("wait-stable: screen never stabilised")
        return f"stable ({len(nodes)} nodes)"

    def _do_wait_stable_ui(self, args: Any) -> str:
        """Alias for ``wait-stable`` (matches the original plan name)."""
        return self._do_wait_stable(args)

    def _do_screenshot(self, args: Any) -> str:
        path = args if isinstance(args, str) else args.get("path", "screen.png")
        self.bot.screencap(path)
        return f"screenshot -> {path}"

    def _do_sleep(self, args: Any) -> str:
        seconds = float(args) if not isinstance(args, dict) else float(args.get("seconds", 1))
        time.sleep(seconds)
        return f"sleep {seconds}s"

    def _do_dump(self, args: Any) -> str:
        path = args if isinstance(args, str) else args.get("path", "ui.json")
        root = self.bot.dump_ui()
        nodes = [n.to_dict() for n in self.bot.iter_nodes(root)]
        Path(path).write_text(
            json.dumps(nodes, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        return f"dump -> {path} ({len(nodes)} nodes)"


# ---------------------------------------------------------------------------
# Recording helpers
# ---------------------------------------------------------------------------

def record_getevent(adb: str = "adb", device: Optional[str] = None,
                    output: Optional[str] = None,
                    timeout: Optional[float] = None) -> Recording:
    """Run ``adb shell getevent -lt`` until interrupted.

    The raw getevent stream is parsed with :class:`Recording.from_getevent_log`
    and optionally written to ``output`` as JSONL.
    """
    cmd: List[str] = [adb]
    if device:
        cmd += ["-s", device]
    cmd += ["shell", "getevent", "-lt"]
    print(f"Recording from: {' '.join(cmd)}  (Ctrl+C to stop)", file=sys.stderr)
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    chunks: List[str] = []
    assert proc.stdout is not None
    try:
        if timeout is not None:
            end = time.time() + timeout
            while time.time() < end:
                line = proc.stdout.readline()
                if not line:
                    break
                chunks.append(line)
        else:
            for line in proc.stdout:
                chunks.append(line)
    except KeyboardInterrupt:
        print("\nRecording stopped by user.", file=sys.stderr)
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            proc.kill()
    raw = "".join(chunks)
    rec = Recording.from_getevent_log(raw)
    if output:
        Path(output).write_text(rec.to_jsonl(), encoding="utf-8")
        print(f"Saved {len(rec.events)} events to {output}", file=sys.stderr)
    return rec


def replay_recording(rec: Recording, adb_touch: Optional[AdbTouch] = None) -> int:
    """Replay a parsed :class:`Recording` against the device.

    Heuristic:
      - We group events by press/release (``BTN_TOUCH`` / ``BTN_TOOL_FINGER``
        going down = start, going up = end).
      - While pressed we collect ``ABS_MT_POSITION_X`` / ``ABS_MT_POSITION_Y``
        deltas.
      - A single (x, y) pair becomes a tap; 2+ pairs become a swipe with a
        duration derived from the first/last timestamps.
    """
    bot = adb_touch or AdbTouch()
    actions = 0
    pressed = False
    press_ts: Optional[float] = None
    last_x: Optional[int] = None
    last_y: Optional[int] = None
    start_x: Optional[int] = None
    start_y: Optional[int] = None

    def _emit_tap(x: int, y: int) -> None:
        nonlocal actions
        bot.tap(x, y)
        actions += 1

    def _emit_swipe(x1: int, y1: int, x2: int, y2: int, dur_ms: int) -> None:
        nonlocal actions
        bot.swipe(x1, y1, x2, y2, duration_ms=max(50, dur_ms))
        actions += 1

    for ev in rec.events:
        if ev.type == "EV_KEY" and ev.code in ("BTN_TOUCH", "BTN_TOOL_FINGER"):
            if ev.value != 0:
                pressed = True
                press_ts = ev.timestamp
                last_x = last_y = None
                start_x = start_y = None
            else:
                # Release — emit what we collected.
                if pressed and last_x is not None and last_y is not None:
                    dur_ms = int((ev.timestamp - (press_ts or ev.timestamp)) * 1000)
                    if start_x is not None and (start_x != last_x or start_y != last_y):
                        _emit_swipe(start_x, start_y, last_x, last_y, dur_ms)
                    else:
                        _emit_tap(last_x, last_y)
                pressed = False
        elif pressed and ev.type == "EV_ABS":
            if ev.code == "ABS_MT_POSITION_X":
                last_x = ev.value
                if start_x is None:
                    start_x = ev.value
            elif ev.code == "ABS_MT_POSITION_Y":
                last_y = ev.value
                if start_y is None:
                    start_y = ev.value
            elif ev.code == "ABS_MT_TRACKING_ID" and ev.value == -1:
                # Tracking ID -1 means finger lifted between SYN_REPORTs.
                if last_x is not None and last_y is not None:
                    _emit_tap(last_x, last_y)
                pressed = False
    return actions


# ---------------------------------------------------------------------------
# Script loading
# ---------------------------------------------------------------------------

def load_script(path: str) -> List[Dict[str, Any]]:
    """Load a JSON or YAML list-of-steps DSL file."""
    text = Path(path).read_text(encoding="utf-8")
    if path.endswith((".yaml", ".yml")):
        try:
            import yaml  # type: ignore
        except ImportError as e:
            raise RuntimeError(
                "YAML support requires PyYAML: pip install pyyaml"
            ) from e
        data = yaml.safe_load(text)
    else:
        data = json.loads(text)
    if not isinstance(data, list):
        raise DSLDecodeError(f"script root must be a list, got {type(data).__name__}")
    return data


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _cmd_run(args: argparse.Namespace) -> int:
    steps = load_script(args.script)
    auto = Automator(screenshot_dir=args.screenshot_dir)
    try:
        results = auto.run_script(steps)
    except RuntimeError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        return 1
    for i, r in enumerate(results, start=1):
        print(f"[{i:03d}] {r}")
    return 0


def _cmd_record(args: argparse.Namespace) -> int:
    record_getevent(adb=args.adb, device=args.device, output=args.output,
                    timeout=args.timeout)
    return 0


def _cmd_replay(args: argparse.Namespace) -> int:
    text = Path(args.recording).read_text(encoding="utf-8")
    rec = Recording.from_jsonl(text)
    bot = AdbTouch(device=args.device, adb=args.adb)
    n = replay_recording(rec, bot)
    print(f"replayed {n} actions")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="DSL runner + record/replay for ADB UI automation."
    )
    p.add_argument("--adb", default="adb")
    p.add_argument("--device", "-s", default=None)

    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("run", help="Execute a DSL script (JSON or YAML).")
    sp.add_argument("script", help="Path to .json / .yaml script.")
    sp.add_argument("--screenshot-dir", default=DEFAULT_SCREENSHOT_DIR)
    sp.set_defaults(func=_cmd_run)

    sp = sub.add_parser("record",
                        help="Record raw input via 'adb shell getevent -lt'.")
    sp.add_argument("output", help="Path to write the JSONL recording.")
    sp.add_argument("--timeout", type=float, default=None,
                    help="Auto-stop after N seconds (default: until Ctrl+C).")
    sp.set_defaults(func=_cmd_record)

    sp = sub.add_parser("replay", help="Replay a JSONL recording.")
    sp.add_argument("recording")
    sp.set_defaults(func=_cmd_replay)

    return p


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except AdbError as e:
        print(f"ADB ERROR: {e}", file=sys.stderr)
        return 1
    except DSLDecodeError as e:
        print(f"DSL ERROR: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
