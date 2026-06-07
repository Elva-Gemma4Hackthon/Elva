---
name: diagnose-gemma4-asr-mismatch
description: Diagnose reports that Gemma 4 cannot call ASR by separating Android SpeechRecognizer flow from Gemma audio-input task flow.
source: auto-skill
extracted_at: '2026-06-07T16:45:05.252Z'
---

# 诊断 "Gemma4 无法调用 ASR" 的方法

当反馈里把 **Gemma 4**、**ASR**、**语音按钮**、**Ask Audio** 混在一起时，不要先假设是单点 bug。先确认项目里到底是：

1. **系统 ASR -> 文本 -> Gemma 4**，还是
2. **音频字节 -> Gemma 4 音频理解/转写**。

这两条链路常被误认为同一功能。

## 适用场景
- 用户反馈“Gemma4 不能转写音频”
- 用户说“Gemma4 调不到 ASR”
- 项目同时有麦克风入口和 Ask Audio / Audio Scribe 页面
- 需要判断是实现缺失、模型配置问题，还是产品描述不准确

## 排查步骤

### 1. 先搜入口和关键字
优先搜索这些词：
- `SpeechRecognizer`
- `RecognizerIntent`
- `LLM_ASK_AUDIO`
- `supportAudio`
- `Content.AudioBytes`
- `genByteArrayForWav`
- `Gemma-4`
- `llmSupportAudio`

目标是快速确认：
- 首页语音入口是不是 Android 原生语音识别
- 音频理解是不是走独立的 LLM ask-audio 任务

### 2. 确认主语音入口的真实链路
检查类似 `ElvaVoiceViewModel` 的类：
- 是否初始化 `SpeechRecognizer`
- `onResults()` 里是否取 `RESULTS_RECOGNITION`
- 是否随后调用 `processWithGemma4(text)` 一类文本推理方法

如果是这条链路，那么实际架构是：

`麦克风 -> Android SpeechRecognizer -> 文本 -> Gemma4`

这说明 **Gemma4 没有直接承担 ASR**，只是消费 ASR 结果。

### 3. 单独检查 Ask Audio / Audio Scribe 链路
检查类似这些位置：
- `LlmAskAudioTask`
- `LlmChatViewModel`
- `LlmChatModelHelper`
- `ChatMessageAudioClip`

重点确认：
- task id 是否是 `LLM_ASK_AUDIO`
- 初始化时是否 `supportAudio = true`
- 推理时是否把录音转换成字节后传给 `audioClips`
- 底层是否构造 `Content.AudioBytes(audioClip)`

如果存在，则说明项目另有一条链路：

`录音字节 -> LLM 音频输入 -> Gemma4`

这条链路才更接近“Gemma4 做音频转写/理解”。

### 4. 检查模型白名单和任务支持
读取 `model_allowlist.json` 或等价配置，确认目标模型：
- `llmSupportAudio: true`
- `taskTypes` 包含 `llm_ask_audio`

特别注意：
- 某些 Gemma 3n / 其它模型可能支持图像但不支持音频 task
- 反馈人常把“装了 Gemma 模型”误认为“任何 Gemma 都支持 ask-audio”

### 5. 检查模型初始化策略
查看导航或入口初始化代码，确认：
- 首页自动初始化的是哪个模型
- 是否优先选 `Gemma-4-E4B-it` / `Gemma-4-E2B-it`
- Ask Audio 页面是否真的绑定到了当前选中的音频模型

常见问题是：
- 首页初始化了 Gemma 4，但主入口依旧只吃文字
- Ask Audio 存在，但当前选中的不是支持音频的模型

## 诊断结论模板

### 结论 A：不是 bug，是链路不同
当首页走 `SpeechRecognizer -> text -> Gemma4`，而 ask-audio 另有独立实现时，应明确说明：

- 现在项目没有“Gemma4 调用 ASR”这条链路
- 当前实现是“系统 ASR 给文字，Gemma4 处理文字”
- 如果需求是“Gemma4 自己转写音频”，那是功能改造，不是小 bug

### 结论 B：Ask Audio 本应可用，但当前不可用
如果 ask-audio 链路完整存在，则继续排查：
- 当前模型是否支持 `llm_ask_audio`
- 是否下载了正确的 Gemma 4 音频模型
- 权限、录音、初始化、运行时错误日志

### 结论 C：文档/对外说法有误
如果 README 或产品文案写成“Gemma4 调用 ASR”，但代码里实际是系统 ASR + Gemma4 文本处理，应优先修正文档，避免误导测试或评审。

## 经验要点
- 不要把“支持 audio input”的 LLM 和“系统 ASR”混为一谈。
- 先分清 **文本语音助手入口** 和 **音频文件/录音理解入口**。
- 这类问题很多时候不是代码坏了，而是 **架构与预期不一致**。
- 给用户反馈时最好明确分成三种可能：实现缺失、配置/模型问题、文案问题。

## 当问题进一步收敛为“语音转文字后无法正常调用 Gemma4”

如果用户已经明确说明：
- 麦克风能录
- 或者语音已经成功转成文字
- 但之后没有真正进入 Gemma4 推理

那么排查重点要从“ASR 是否成功”切换到“**ASR 成功后的 Gemma4 触发条件**”。

### 1. 先确认首页麦克风是否申请了 `RECORD_AUDIO`
即使用户表述为“转文字后调不到 Gemma4”，也要先核对首页入口有没有自己处理运行时权限。

检查点：
- 首页语音按钮对应的 Compose/Activity/Fragment 是否先检查 `Manifest.permission.RECORD_AUDIO`
- 是否在点击麦克风前通过 `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` 或等价方式请求权限
- 是否只有在授权成功后才调用 `SpeechRecognizer.startListening()`

经验上，项目里经常：
- Manifest 里声明了 `RECORD_AUDIO`
- 其它页面（如 Ask Audio）做了权限申请
- 但首页自定义语音入口忘了处理运行时权限

这样会导致首页表现为“语音识别不稳定”或直接失败。

### 2. 查 `onResults()` 后的真实后续分支
重点看类似 `ElvaVoiceViewModel.onResults()`：
- 是否拿到 `SpeechRecognizer.RESULTS_RECOGNITION`
- 是否把文本传给 `processWithGemma4(text)`
- `processWithGemma4()` 内部是否又按路由分流

不要只看到 `processWithGemma4(text)` 就认为一定调用了 Gemma4；很多项目会在里面先经过：
- 本地路由
- 安全审查
- 健康咨询本地逻辑
- 本地 fallback

### 3. 特别检查“模型未就绪时”的处理
这是最容易造成误判的点。

查找类似逻辑：
- `if (!bridge.state.value.isModelReady) { ... }`
- `if (model == null || model.instance == null) { ... }`
- `localFallbackResponse(userText)`
- `generateFallbackAction(userText)`

常见问题模式：
1. ASR 成功拿到文字
2. 进入 `processWithGemma4(text)`
3. 发现 Gemma4 还没初始化完成
4. 代码直接走本地 fallback
5. 用户看到“有回复”，但其实 **Gemma4 根本没被调用**

这种情况不是 ASR bug，而是 **Gemma4 readiness gating** 问题。

### 4. 检查首页的 Gemma4 自动初始化是否足够可靠
查看导航/入口层是否只有“被动自动初始化”，例如：
- `LaunchedEffect(modelDownloadStatus)` 时尝试初始化
- 仅在下载状态变化时触发
- 只要 `isModelReady` 不成立就依赖某个异步初始化流程

这种设计的问题是：
- 用户可能比模型初始化更快地开始说话
- 语音识别先成功
- 但当次请求因为模型未 ready 被静默降级

### 5. 推荐修复策略：补 `ensureReady()` 或等价懒初始化
如果确认问题是“文字拿到了，但 Gemma4 未 ready 就直接 fallback”，优先采用下面的修法：

1. 在推理桥接层增加一个 `ensureReady()` 风格的方法
   - 已 ready：直接继续
   - 正在初始化：明确提示“AI 模型正在加载，请稍候”
   - 未初始化但已有目标模型：立即触发初始化，成功后继续原请求
   - 没有可用模型：明确提示“未找到可用的 Gemma 模型”

2. 在 `processWithGemma4()` 里不要一看到 `!isModelReady` 就直接 `localFallbackResponse()`
   - 先尝试 `ensureReady()`
   - ready 后再执行真正的 `CloudPlanner.plan()` / `infer()`

3. 如果首页麦克风没有权限申请，也一起补上
   - 否则问题会在不同设备上表现成两类：
     - 一类是 ASR 根本起不来
     - 一类是 ASR 成功但 Gemma4 没被调用

### 6. 给用户的结论表达
如果排查结果是这个问题，应明确说：
- “不是语音转文字本身失败，而是转成文字后 Gemma4 尚未 ready，代码直接走了本地兜底。”
- “表现上像没调用 Gemma4，实际上是初始化时机和 fallback 策略导致的。”

### 7. 适合保存的修复经验
这类项目里，**麦克风权限** 和 **模型 readiness** 是两层独立门槛：
- 第一层没过：ASR 失败
- 第二层没过：ASR 成功，但 Gemma4 不执行

因此排查语音链路时要分两段看：

`点击麦克风 -> 录音/识别成功 -> 文本进入 ViewModel -> Gemma4 ready 检查 -> 真正推理`

不要把“用户最后没得到 Gemma4 回复”统称成单一的“语音转文字失败”。
