# Elva ADB UI Automation Tools

> 一组基于 **ADB（Android Debug Bridge）** 的 UI 自动化工具，**不依赖 Android 的
> AccessibilityService**，即可识别任意应用的 UI 元素并执行点击 / 长按 / 滑动 /
> 输入等动作。本目录是 Elva 项目的"开发机侧"脚本集合，可以从外部机器控制任意
> Android 设备 / 模拟器。

---

## 1. 与 AccessibilityService 的对比

| 维度              | AccessibilityService            | 本目录 ADB 工具                                          |
| ----------------- | -------------------------------- | --------------------------------------------------------- |
| 部署位置          | 设备内（要随 App 安装）          | 开发机（外发命令）                                        |
| 权限要求          | 用户手动开启"无障碍"             | 设备开启 **USB 调试** / 网络 ADB 即可                     |
| 可见性            | 仅限持有该 Service 的 App         | 可对任意 App 操控（只要能 `dumpsys window`）             |
| 反应速度          | 事件驱动，毫秒级                 | 轮询 `uiautomator dump`，通常 0.3–0.5s                    |
| 稳定性            | 受 OEM ROM 影响                  | 跨 OEM 行为一致                                          |
| 文本输入（中文）  | 走 IME                           | 走 **ADBKeyboard** 或 `input text`（ASCII only）          |
| 适合场景          | 老年人助手长期运行                | 自动化测试 / 录制回放 / 单次任务                          |

简而言之：**AccessibilityService 适合"长在 App 里、一直跑"，ADB 工具适合"开发机
侧、要控制任意 App、做测试或临时任务"**。两者并不冲突，本项目里 Elva 用
AccessibilityService 守在设备端，本目录的工具则给开发 / QA 流程使用。

---

## 2. 目录构成

| 文件                  | 作用                                                                 |
| --------------------- | -------------------------------------------------------------------- |
| `adb_touch.py`        | 核心：**UI dump / 节点查找 / 点击 / 长按 / 滑动 / 拖拽 / 输入 / 截图 / 启动 App** |
| `adb_input.py`        | **Unicode / 中文输入**（ADBKeyboard 集成）+ 设备环境查询             |
| `adb_automator.py`    | **DSL 脚本执行**（YAML / JSON）+ **录制回放**（getevent 解析）        |
| `demo_adb_ui.py`      | 一键跑通完整演示流程                                                 |

三者可以独立使用，也可以在 Python 代码里自由组合。

---

## 3. 前置条件

1. **Android SDK Platform Tools** 已安装；本项目自带在 `src/Android/sdk/platform-tools/`。
2. **Python ≥ 3.8**（仅标准库；YAML 支持额外需要 `pip install pyyaml`）。
3. **Android 设备**：
   - **模拟器**：用 SDK 自带的 `emulator -avd <name>` 启动。
   - **真机**：开启 **开发者选项 → USB 调试**，用数据线连接后授权。
4. **中文输入**：需要安装 [ADBKeyboard](https://github.com/senzhk/ADBKeyBoard)，
   工具会自动从 GitHub 下载并安装。

Windows 上把 ADB 加入 PATH（或每次传 `--adb "d:\...\adb.exe"`）。

---

## 4. adb_touch.py — 核心 UI 操作

### 4.1 CLI 用法

```bash
# 把 ADB 加入 PATH 后，下面命令可以省略 --adb
python adb_touch.py --device emulator-5554 dump-all ui.json

# 通过文字查找并点击
python adb_touch.py tap --text "提交"

# 等待元素出现再点击
python adb_touch.py tap --text "支付成功" --timeout 15

# 用坐标点击 / 长按 / 滑动
python adb_touch.py tap 540 1200
python adb_touch.py longpress 540 1200 --duration 1500
python adb_touch.py swipe 540 1800 540 600 --duration 400

# 正则匹配（忽略大小写）
python adb_touch.py find-regex text "^设[置置]$" --ignore-case

# 屏幕稳定后再继续（避免点到动画中间）
python adb_touch.py wait-stable --stable-runs 3

# 应用启动 / 停止 / 当前焦点
python adb_touch.py start com.android.settings
python adb_touch.py stop com.android.settings
python adb_touch.py current

# 截图
python adb_touch.py screencap out.png

# 输入（自动走 ADBKeyboard；未启用则降级 input text）
python adb_touch.py type-unicode "你好，世界"
```

### 4.2 Python API

```python
from adb_touch import AdbTouch

with AdbTouch(device="emulator-5554") as bot:
    # 1) 启动应用（自动找 LAUNCHER activity）
    bot.start_activity("com.android.settings")

    # 2) 等待并点击元素
    if bot.tap_text("显示", timeout=10):
        # 3) 稳定后再继续
        bot.wait_for_stable(stable_runs=2)

        # 4) 滑动 / 长按
        bot.swipe(540, 1800, 540, 600, duration_ms=400)
        bot.long_press(540, 1200, duration_ms=1500)

        # 5) 智能文本输入（中文走 ADBKeyboard）
        bot.shell_unicode("给爸妈发短信：今天天气不错")

        # 6) 截图留档
        bot.screencap("after.png")
```

### 4.3 节点模型

`dump_ui()` 返回的 `uiautomator` XML 被解析为 `NodeInfo`，核心字段：

| 字段              | 含义                              |
| ----------------- | --------------------------------- |
| `text`            | 元素显示的文字                    |
| `resource_id`     | `android:id/...` 形式的资源 ID    |
| `class_name`      | `android.widget.Button` 等类名    |
| `content_desc`    | `contentDescription`              |
| `package`         | 元素所属包名                      |
| `bounds`          | `(x1, y1, x2, y2)` 屏幕像素坐标   |
| `clickable`       | 是否可点击                        |
| `long_clickable`  | 是否支持长按                      |
| `scrollable`      | 是否可滚动                        |

`center` 属性返回 `(x, y)` 中心点，可直接喂给 `tap()`。

---

## 5. adb_input.py — 中文 / Unicode 输入

Android 自带的 `input text` **只支持 ASCII**，中文会被静默丢弃。`adb_input.py`
通过 [ADBKeyboard](https://github.com/senzhk/ADBKeyBoard) 解决：

```
am broadcast -a ADB_INPUT_TEXT --es msg "你好"
```

### 5.1 CLI

```bash
# 查看设备环境
python adb_input.py info

# 屏幕分辨率 / 当前焦点 / 当前 IME
python adb_input.py screen            # e.g. 1080x2400
python adb_input.py focus             # com.android.settings/.Settings
python adb_input.py ime               # 当前 IME
python adb_input.py ime --list        # 已安装的全部 IME

# 切换 IME
python adb_input.py set-ime adbkeyboard
python adb_input.py set-ime default   # 切回系统默认

# 输入中文（首次会自动从 GitHub 下载 ADBKeyboard.apk 并安装）
python adb_input.py type "你好，世界"

# 手动安装
python adb_input.py install-kb
python adb_input.py install-kb --apk /path/to/ADBKeyboard.apk
```

### 5.2 Python API

```python
from adb_input import AdbInput

with AdbInput() as kb:
    kb.set_adb_keyboard()                       # 切到 ADBKeyboard
    kb.focus_a_text_field() if False else None  # 你的代码负责先点输入框
    kb.type_unicode("给爸妈发短信")             # 真正发送中文
    kb.restore_ime()                            # 还原系统默认 IME
```

### 5.3 中文输入流程图

```
[Python] --am broadcast-->[ADBKeyboard IME]--text via IME--> [Focused EditText]
   ^                                                                |
   |                                                                v
[type_unicode()]                                          [用户在屏幕上看到中文]
   |
   v
[若 ADBKeyboard 未安装] --download--> [GitHub] --adb install--> [设备]
```

---

## 6. adb_automator.py — DSL 脚本 + 录制回放

### 6.1 DSL 步格式（JSON / YAML）

每个 step 是**单键 dict**，key 是动作名，value 是参数：

```json
[
  {"start":  "com.android.settings"},
  {"wait":   {"text": "设置", "timeout": 10}},
  {"tap":    {"text": "显示", "fuzzy": true}},
  {"swipe":  {"x1": 540, "y1": 1800, "x2": 540, "y2": 600, "duration": 400}},
  {"long-press": {"x": 540, "y": 1200, "duration": 1500}},
  {"type":   "你好"},
  {"key":    "home"},
  {"screenshot": "after.png"},
  {"sleep":  1.0},
  {"dump":   "ui.json"}
]
```

支持的动作：

| Action        | 说明                                       |
| ------------- | ------------------------------------------ |
| `tap`         | 坐标 / text / resource-id                  |
| `long-press`  | 长按                                       |
| `swipe`       | 滑动                                       |
| `drag`        | Android 12+ 拖拽（保持按下状态）           |
| `type`        | 智能文本输入（ADBKeyboard / ASCII）        |
| `key`         | `keyevent`，接受名字 (`home`/`back`) 或数字 |
| `start`       | 启动 App                                   |
| `stop`        | `force-stop` App                           |
| `wait`        | 等到元素出现                               |
| `wait-stable` | 等到屏幕停止变化                           |
| `screenshot`  | 截图                                       |
| `sleep`       | 等待秒数                                   |
| `dump`        | 把整个 UI tree 写到 JSON                   |

### 6.2 运行

```bash
python adb_automator.py run examples/open_settings.json
python adb_automator.py run examples/open_settings.yaml   # 需要 pyyaml
```

任意步骤失败时，工具会自动截图到 `tools/crash_dumps/fail_NNN.png`。

### 6.3 录制与回放

```bash
# 录：在设备上随便操作，Ctrl+C 停止
python adb_automator.py record demo.jsonl

# 放：把录到的动作原样重放到另一台设备
python adb_automator.py replay demo.jsonl --device emulator-5556
```

`getevent` 的原始输出经过启发式解析：单点 (x, y) 当作 `tap`，多点轨迹当作
`swipe`，时长由事件时间戳算出。

> 注意：录制依赖 /dev/input 设备的驱动路径，跨设备型号通常不可移植；DSL 脚本
> 在这一点上更稳健（基于 text / resource-id 而非绝对坐标）。

---

## 7. 实战示例：操作"支付宝 → 缴电费"

```python
from adb_touch import AdbTouch
from adb_input import AdbInput

with AdbTouch() as bot, AdbInput() as kb:
    bot.start_activity("com.eg.android.AlipayGphone")
    bot.wait_for(text="首页", timeout=10)

    bot.tap_text("市民中心", fuzzy=True, timeout=8)
    bot.tap_text("生活缴费", fuzzy=True, timeout=8)

    # 切到 ADBKeyboard 后才能输中文
    kb.set_adb_keyboard()
    bot.tap_text("电费", fuzzy=True, timeout=8)
    bot.shell_unicode("北京")
    kb.restore_ime()

    bot.screencap("alipay_electricity.png")
```

---

## 8. 常见问题

| 问题                                       | 解决方案                                                                 |
| ------------------------------------------ | ------------------------------------------------------------------------ |
| `adb: error: no devices/emulators found`   | 检查 USB 调试；`adb kill-server && adb start-server`；换数据线           |
| `uiautomator dump` 输出被截断              | 工具已自动补全闭合标签；如仍异常，加大 `timeout` 或重试                   |
| `input text "中文"` 显示为空               | 改用 `python adb_input.py type "中文"`                                   |
| 点击落到错的元素                           | 先 `dump-all` 看看 `text` 是否唯一；用 `find-regex` 加模糊；加 `wait-stable` |
| 动画中间点了没反应                         | `wait_for_stable(stable_runs=3)` 后再点                                   |
| 屏幕旋转后 bounds 变了                     | 用 `text` / `resource-id` 而非坐标                                       |
| AVD 启动后立刻崩溃（ACCESS_VIOLATION）     | `emulator -avd <name> -gpu host` 而非默认 swiftshader_indirect           |

---

## 9. 与 Elva 项目的集成

* **开发期**：CI / QA 在 Windows 上跑 `demo_adb_ui.py` 验证 Elva 主流程。
* **运行期**：Elva App 内部仍用 AccessibilityService（要 24×7 守在设备端），
  本目录工具不参与设备端运行时。
* **回放辅助**：把 AccessibilityService 抓到的"用户操作序列"导出后，可以
  在 QA 模拟器上用 `adb_automator.py replay` 重放。

---

## 10. 速查表

```text
adb_touch.py dump           --text "X"          # 查找节点
adb_touch.py dump-all       ui.json             # 全量 dump
adb_touch.py tap            --text "X"
adb_touch.py tap            X Y                 # 坐标点击
adb_touch.py longpress      X Y --duration 1500
adb_touch.py swipe          X1 Y1 X2 Y2
adb_touch.py wait           --text "X" --timeout 15
adb_touch.py wait-stable    --stable-runs 3
adb_touch.py find-regex     text "^设[置置]$"
adb_touch.py start          com.android.settings
adb_touch.py stop           com.android.settings
adb_touch.py current
adb_touch.py screencap      out.png
adb_touch.py type-unicode   "中文"

adb_input.py info                                # JSON 设备环境
adb_input.py screen                              # 屏幕分辨率
adb_input.py focus                               # 当前焦点
adb_input.py ime                                 # 当前 IME
adb_input.py set-ime adbkeyboard                 # 切到 ADBKeyboard
adb_input.py type "中文"                         # 输入
adb_input.py install-kb                          # 安装 ADBKeyboard

adb_automator.py run        script.json
adb_automator.py record     demo.jsonl
adb_automator.py replay     demo.jsonl
```
