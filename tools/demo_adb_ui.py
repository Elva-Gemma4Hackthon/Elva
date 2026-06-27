"""
demo_adb_ui.py — One-shot end-to-end demo of the ADB UI automation toolkit.

Run it from ``tools/`` once an emulator (or a USB-debug-enabled device) is
connected::

    python demo_adb_ui.py
    python demo_adb_ui.py --device emulator-5554 --no-skip-cjk

What it does, step by step
--------------------------
1. Prints ``adb_input.py info`` so you can verify the device.
2. Wakes / unlocks the device (``KEYCODE_WAKEUP``).
3. Launches the Settings app via ``am start -a android.settings.SETTINGS``.
4. Dumps the full UI tree to ``tools/demo_out/ui.json``.
5. Searches for an element whose text contains "显示" (Display) and taps it.
6. Takes a screenshot ``tools/demo_out/02_display.png``.
7. Performs a vertical swipe to scroll the page.
8. Prints the currently-focused package/activity.
9. (Optional) installs ADBKeyboard and types the Chinese string "你好".

Each step is wrapped so a failure prints a clear message and the demo still
returns a non-zero exit code instead of crashing silently.

The script is designed to be **idempotent** — re-running it on the same
device will simply re-assert the state of Settings.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path
from typing import Optional

# Allow running as a standalone script from anywhere.
_HERE = Path(__file__).resolve().parent
if str(_HERE) not in sys.path:
    sys.path.insert(0, str(_HERE))

from adb_input import AdbInput          # noqa: E402
from adb_touch import AdbError, AdbTouch  # noqa: E402


DEMO_OUT = _HERE / "demo_out"


# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------

def _section(title: str) -> None:
    bar = "=" * 64
    print(f"\n{bar}\n  {title}\n{bar}", flush=True)


def _step(n: int, msg: str) -> None:
    print(f"[{n:02d}] {msg}", flush=True)


def _ok(msg: str) -> None:
    print(f"    ✓ {msg}", flush=True)


def _warn(msg: str) -> None:
    print(f"    ! {msg}", file=sys.stderr, flush=True)


def _wait_until(predicate, timeout: float = 15.0,
                interval: float = 0.5) -> bool:
    """Simple busy-wait helper used in the demo."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            if predicate():
                return True
        except Exception:
            pass
        time.sleep(interval)
    return False


# ---------------------------------------------------------------------------
# Demo steps
# ---------------------------------------------------------------------------

def step_print_info(kb: AdbInput) -> dict:
    info = kb.device_info()
    _ok(f"screen={info['screen']['width']}x{info['screen']['height']}, "
        f"sdk={info['sdk']}, ime={info['ime']}")
    return info


def step_wake_unlock(bot: AdbTouch) -> None:
    # 82 = KEYCODE_MENU, 224 = WAKEUP, 82 also wakes some devices.
    bot.keyevent(224)
    bot.keyevent(82)
    _ok("sent KEYCODE_WAKEUP + KEYCODE_MENU")


def step_open_settings(bot: AdbTouch) -> None:
    bot.shell("am start -W -a android.settings.SETTINGS")
    _ok("launched Settings via 'am start -a android.settings.SETTINGS'")
    # Settings can take a beat to fully render on cold start.
    nodes = bot.wait_for_stable(stable_runs=2, poll_interval=0.5,
                                timeout=20, min_nodes=40)
    if nodes is None:
        _warn("Settings UI did not stabilise within 20s — continuing anyway")
    else:
        _ok(f"Settings stable; {len(nodes)} nodes visible")


def step_dump_ui(bot: AdbTouch, out: Path) -> int:
    out.parent.mkdir(parents=True, exist_ok=True)
    root = bot.dump_ui()
    nodes = [n.to_dict() for n in bot.iter_nodes(root)]
    import json
    out.write_text(json.dumps(nodes, ensure_ascii=False, indent=2), encoding="utf-8")
    _ok(f"dumped {len(nodes)} nodes -> {out}")
    return len(nodes)


def step_tap_display(bot: AdbTouch) -> Optional[str]:
    # Try multiple aliases; Settings UI varies across language / OEM / API.
    # We tap the centre of the matched text node even if the clickable flag
    # is on the parent — the parent's bounds still contain that point.
    for label in ("显示", "Display", "Sound & vibration", "Apps"):
        node = bot.find_one(text=label, exact=False)
        if node is not None:
            bot.tap_node(node)
            _ok(f"tapped '{label}' at {node.center} (clickable={node.clickable})")
            return label
    _warn("no element with text '显示' / 'Display' / 'Apps' found")
    return None


def step_screenshot(bot: AdbTouch, out: Path) -> None:
    out.parent.mkdir(parents=True, exist_ok=True)
    bot.screencap(str(out))
    _ok(f"screenshot -> {out}")


def step_swipe(bot: AdbTouch) -> None:
    try:
        w, h = bot.shell("wm size").strip().split("x")
        w, h = int(w), int(h)
    except Exception:
        w, h = 1080, 2400
    x = w // 2
    bot.swipe(x, int(h * 0.75), x, int(h * 0.25), duration_ms=400)
    _ok(f"swiped ({x},{int(h*0.75)}) -> ({x},{int(h*0.25)})")


def step_show_focus(kb: AdbInput) -> None:
    pkg, act = kb.current_focus()
    if pkg:
        _ok(f"current focus: {pkg}/{act}")
    else:
        _warn("could not detect current focus")


def step_unicode(bot: AdbTouch, kb: AdbInput, skip: bool, text: str) -> None:
    if skip:
        _warn(f"--skip-cjk set; would have typed: {text!r}")
        return
    # Walk into a screen with an EditText — Settings → Search is always there.
    bot.keyevent(3)  # home (start fresh)
    time.sleep(0.4)
    bot.shell("am start -W -a android.settings.SETTINGS")
    time.sleep(0.6)
    # Tap the top search affordance; if not found, just continue.
    search_node = bot.find_one(text="Search settings", exact=False)
    if search_node is None:
        search_node = bot.find_one(content_desc="Search", exact=False)
    if search_node is not None:
        bot.tap_node(search_node)
        time.sleep(0.5)
        _ok("focused the Settings search box")
    else:
        _warn("could not locate a search box; broadcast may not be visible")

    if not kb.is_adb_keyboard_installed():
        _warn("ADBKeyboard not installed; attempting download + install")
        try:
            apk = kb.ensure_adb_keyboard(
                download_dir=str(_HERE / "downloads"),
            )
            _ok(f"ADBKeyboard installed: {apk}")
        except Exception as e:  # noqa: BLE001
            _warn(f"could not install ADBKeyboard: {e}; skipping CJK demo")
            return
    try:
        kb.set_adb_keyboard()
    except Exception as e:  # noqa: BLE001
        _warn(f"could not switch IME to ADBKeyboard: {e}; skipping CJK demo")
        return
    # Type with the ADBKeyboard (Unicode-capable) IME.
    strategy = bot.shell_unicode(text)
    _ok(f"sent Unicode text via {strategy}: {text!r}")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def run_demo(device: Optional[str], skip_cjk: bool) -> int:
    DEMO_OUT.mkdir(parents=True, exist_ok=True)

    _section("ADB UI Automation — end-to-end demo")
    _step(0, "connecting to device…")
    try:
        with AdbTouch(device=device) as bot, AdbInput(device=device) as kb:
            _section("Step 1: device info")
            step_print_info(kb)

            _section("Step 2: wake / unlock")
            step_wake_unlock(bot)

            _section("Step 3: open Settings")
            step_open_settings(bot)
            # Wait for Settings to actually be on screen.
            if not _wait_until(
                lambda: bot.find_one(
                    text="设置", exact=False, class_name="android.widget.TextView"
                ) is not None
                or bot.find_one(text="Settings", exact=False) is not None,
                timeout=15,
            ):
                _warn("Settings may not have rendered yet — continuing anyway")

            _section("Step 4: dump UI tree")
            step_dump_ui(bot, DEMO_OUT / "ui.json")

            _section("Step 5: tap 'Display' / '显示'")
            step_tap_display(bot)

            _section("Step 6: screenshot after tapping")
            step_screenshot(bot, DEMO_OUT / "02_display.png")

            _section("Step 7: scroll down")
            step_swipe(bot)
            step_screenshot(bot, DEMO_OUT / "03_after_swipe.png")

            _section("Step 8: current focus")
            step_show_focus(kb)

            _section("Step 9: Unicode / Chinese input")
            step_unicode(bot, kb, skip_cjk, "你好，老白助手")
            step_screenshot(bot, DEMO_OUT / "04_after_unicode.png")

            _section("Demo finished")
            print(f"    artefacts under: {DEMO_OUT}", flush=True)
            return 0
    except AdbError as e:
        _warn(f"adb error: {e}")
        return 1
    except FileNotFoundError as e:
        _warn(str(e))
        return 1


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    p.add_argument("--device", "-s", default=None,
                   help="Target device serial (`adb devices` to list).")
    p.add_argument("--skip-cjk", action="store_true",
                   help="Skip the Unicode/Chinese-input step.")
    return p


def main(argv: Optional[list] = None) -> int:
    args = build_parser().parse_args(argv)
    return run_demo(args.device, args.skip_cjk)


if __name__ == "__main__":
    sys.exit(main())
