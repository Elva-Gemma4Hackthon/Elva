# DSL script examples for `adb_automator.py run`.

This directory contains small automation scripts you can feed to:

    python adb_automator.py run examples/<name>.json

| File                              | What it does                                                            |
| --------------------------------- | ----------------------------------------------------------------------- |
| `open_settings_sound.json`        | Open Settings → Sound & vibration, screenshot, scroll, screenshot, back |

## Format

Each script is a JSON array. Every element is a single-key object whose
key is the action and whose value is the action's argument. Actions
include `tap`, `swipe`, `long-press`, `type`, `key`, `start`, `stop`,
`wait`, `wait-stable`, `screenshot`, `dump`, `sleep`.

Full reference lives in [tools/README.md](../README.md).
