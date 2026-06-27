# `demo_out/`

This directory holds the artefacts produced by the most recent
`demo_adb_ui.py` / `adb_automator.py run` execution. They exist so you can
inspect what the automation actually did without re-running the script.

| File                              | What it shows                                                          |
| --------------------------------- | ---------------------------------------------------------------------- |
| `demo_full_run.log`               | Full console log of `demo_adb_ui.py` (9 steps, every line).            |
| `02_display.png`                  | Screenshot taken right after tapping "Sound & vibration".              |
| `03_after_swipe.png`              | Screenshot taken after the demo's vertical swipe.                      |
| `04_after_unicode.png`            | Screenshot taken after the ADBKeyboard broadcast of `你好，老白助手`. |
| `ui.json`                         | The full UI tree that `demo_adb_ui.py` dumped in Step 4.               |
| `dsl_run.txt`                     | Output of an inline Python script that ran an Automator DSL.           |
| `dsl_json_run.txt`                | Output of `adb_automator.py run examples/open_settings_sound.json`.    |
| `dsl_ui.json`                     | UI tree captured by the DSL's `dump` action.                           |
| `dsl_demo.png`                    | Screenshot taken by the DSL run after tapping into Sound.              |
| `sound_settings.png`              | Screenshot taken by the JSON DSL after entering Sound & vibration.     |
| `sound_settings_scrolled.png`     | Screenshot taken after the JSON DSL scrolled the Sound page.           |

To reproduce any of these, re-run:

```
python ../demo_adb_ui.py --device emulator-5554
python ../adb_automator.py --device emulator-5554 run ../examples/open_settings_sound.json
```
