# Elva Gemma 端云 Agent 两个核心 Case 开发文档

## 1. 文档目标

本文档用于指导 Elva LaoBai 项目的两个黑客松核心 demo case 开发：

1. `Always-on 固定表单填写助手`
2. `Trigger 看病咨询与挂号助手`

这两个 case 都围绕老年用户使用手机时的真实困难展开：不会填表、担心误操作、看不懂挂号流程、害怕隐私泄露。技术上优先复用 Elva 当前 Android 工程已有的 Agent 管线，而不是另起一套架构。

核心原则：

- 原始屏幕、身份证、手机号、验证码、完整病历、家庭联系人等敏感信息只留在端侧。
- 云端 Gemma 31B 只接收严格脱敏后的结构化摘要，用于复杂规划。
- 所有高风险动作必须经过确认，V1 不自动提交表单、不自动付款、不自动完成挂号确认。

## 2. 当前 Elva 技术栈

Elva 当前主体是 Android 原生项目，基于 Google AI Edge Gallery 扩展。

主要技术栈：

- Android Kotlin
- Jetpack Compose
- Google AI Edge Gallery
- LiteRT LLM / MediaPipe LLM Inference
- 本地 Gemma / Qwen 模型运行能力
- Android `AccessibilityService`
- Android `NotificationListenerService`
- `SKILL.md` 技能系统
- `run_intent` Native Skill 调用路径
- MCP Kotlin SDK 与 Ktor，作为未来外部工具和云端服务扩展基础
- DataStore、Proto、Moshi、Security Crypto，作为状态、配置、敏感字段存储基础

Elva 已有的关键模块：

- `AlwaysOnSentinel`：事件驱动哨兵，接收 Accessibility 和用户 Trigger 事件。
- `ScreenObserver`：读取当前 UI tree，生成屏幕观察结果。
- `PrivacyFirewall`：脱敏身份证、手机号、银行卡、验证码、邮箱、地址等敏感信息。
- `LocalSensitivityDetector`：补充检测健康、金融、地址、姓名等语义敏感信息。
- `LocalRouter`：判断本地处理、上云规划、询问用户、停止。
- `CloudPlanner`：云端规划适配层，目前通过 `ElvaInferenceBridge.inferWithFunctions` 获取结构化动作。
- `ElvaInferenceBridge`：连接本地模型推理与 function-style 输出解析。
- `ElvaFunctions` / `OutputValidator`：定义可调用函数和解析 `NextAction`。
- `SafetyGuard`：对动作做 allow / require_confirmation / deny 判断。
- `ActionExecutor`：执行白名单动作，如点击、输入、滚动、返回、打开应用、语音播报。
- `A11yTaskExecutor`：执行多步骤 Accessibility 自动化任务。
- `BookHospitalTask`：挂号任务脚本，目标路径是微信搜索医院小程序并进入挂号。
- `book-hospital/SKILL.md`：用于 Agent Skills 的挂号技能说明。

现有 Agent 数据流可以概括为：

```text
EdgeEvent
  -> ScreenObservation
  -> PrivacyFirewall
  -> RoutingDecision
  -> NextAction
  -> SafetyGuard / GuardDecision
  -> ActionExecutor 或 A11yTaskExecutor
```

## 3. Case 1：Always-on 固定表单填写助手

### 3.1 用户故事

老人打开一个固定类型的表单或问卷页面，例如社区登记、活动报名、就诊前信息表。老白在端侧识别到这是一个可辅助填写的页面，轻量提醒：

> 我看到这是一个信息表，要不要我帮您填写常用信息？

用户确认后，老白从本地记忆读取已授权保存的字段，自动填写姓名、电话、地址等低风险字段。遇到身份证、验证码、付款、授权、提交按钮时停止，要求用户确认或手动完成。

### 3.2 V1 范围

V1 只做固定模板，不做任意表单泛化。

推荐先做 1 到 2 个稳定模板：

- 社区活动报名表
- 就诊前基础信息表

固定模板的好处是 demo 稳定，字段映射可控，风险边界清楚。通用表单识别可以作为 V2。

### 3.3 端侧流程

```text
Accessibility event
  -> AlwaysOnSentinel.onAccessibilityEvent
  -> ScreenObserver.observe
  -> PrivacyFirewall.createScreenObservation
  -> FormTemplateMatcher
  -> ask user confirmation
  -> LocalUserMemory.readAllowedFields
  -> build fill steps
  -> SafetyGuard
  -> ActionExecutor / A11yTaskExecutor
  -> stop before submit
```

### 3.4 需要新增的工程概念

#### LocalUserMemory

用于端侧保存用户授权过的常用信息。

建议字段类型：

- `display_name`
- `phone_masked`
- `address_label`
- `emergency_contact_label`
- `medical_card_label`
- `preferred_hospital`
- `preferred_department`

注意：

- 原始敏感值只允许端侧读取。
- 云端请求只能看到字段类别、是否存在、脱敏摘要，不能看到原值。
- 推荐使用 DataStore 保存结构化数据，对敏感字段使用 Android Security Crypto 加密。

#### FormTemplate

用于固定模板识别和字段映射。

建议内容：

- `template_id`
- `page_keywords`
- `required_fields`
- `field_mapping`
- `blocked_targets`
- `confirmation_targets`

`blocked_targets` 至少包括：

- 提交
- 支付
- 授权
- 删除
- 发送验证码
- 输入验证码

### 3.5 安全策略

Always-on 不能静默执行高风险动作。

规则：

- 识别表单后只弹轻量提醒，不自动开始填写。
- 填写前必须获得用户确认。
- 身份证号、医保号等强敏感字段默认不自动填，除非 V2 单独做强确认。
- 验证码不读取、不上传、不自动填写。
- 点击提交前必须停住。
- 页面不匹配固定模板时，只解释当前页面或询问用户，不执行填写。

## 4. Case 2：Trigger 看病咨询与挂号助手

### 4.1 用户故事

用户主动说：

> 小白，我胃不舒服，帮我看看怎么办。

老白先在端侧进行非诊断式问询，了解症状、持续时间、严重程度、是否伴随危险信号。它可以给出就医建议和注意事项，但不做医疗诊断。

如果用户说想去医院，系统进入端云协同：

1. 端侧整理脱敏症状摘要。
2. 云端 Gemma 31B 规划挂哪个科室、哪天去、需要准备什么。
3. 端侧调用挂号技能，打开微信或医院小程序。
4. 在最终确认挂号、支付、授权前停止，让用户确认。

### 4.2 本地优先流程

```text
User says "小白，我胃不舒服"
  -> ElvaVoiceViewModel
  -> AlwaysOnSentinel.triggerFullPipeline
  -> local health clarification
  -> LocalSensitivityDetector detects health info
  -> PrivacyFirewall redacts
  -> LocalRouter decides local_only or cloud_planner
```

本地可以完成：

- 症状澄清
- 风险提示
- 是否需要紧急就医的保守提醒
- 读取本地日程和用户偏好
- 准备脱敏摘要

本地不能做：

- 明确诊断
- 开药建议
- 替代医生判断
- 自动提交挂号或支付

### 4.3 上云边界

云端 Gemma 31B 只能接收严格脱敏后的 payload。

允许上云：

- 年龄段，例如 `70s`
- 症状类别，例如 `stomach_discomfort`
- 持续时间，例如 `2 days`
- 严重程度，例如 `mild / moderate / severe`
- 伴随症状标签，例如 `nausea`
- 用户目标，例如 `book_hospital`
- 大致城市或区域，例如 `Shanghai district-level`，必要时也可以不传
- 可用工具列表，例如 `book_hospital`
- 已脱敏的日程空闲摘要

禁止上云：

- 原始截图
- 姓名
- 身份证号
- 手机号
- 验证码
- 完整家庭住址
- 完整病历原文
- 家庭联系人原始信息
- 医保卡号

### 4.4 云端规划职责

云端 Gemma 31B 只负责规划，不直接执行。

输出应该是：

- 建议科室
- 是否建议线下就医
- 挂号参数建议
- 可选时间段
- 用户需要准备的材料
- 下一步动作建议

云端不应该输出：

- 原始 GUI 坐标
- 自动提交指令
- 支付指令
- 验证码处理指令
- 医疗诊断结论

### 4.5 挂号执行路径

现有 Elva 已有挂号能力，可以作为 V1 的执行基础：

```text
book-hospital/SKILL.md
  -> run_intent(intent = "book_hospital")
  -> IntentHandler
  -> A11yTaskExecutor.TaskType.BOOK_HOSPITAL
  -> BookHospitalTask.buildSteps
  -> AccessibilityService click/type
```

当前 `BookHospitalTask` 的策略是：

```text
Open WeChat
  -> Search hospital
  -> Open hospital mini-program/page
  -> Click 预约挂号
  -> Select department
  -> Select date
  -> Click 预约 / 确认挂号
  -> Final confirmation step
```

V1 建议调整为：

- 可以进入挂号页。
- 可以选择医院、科室、日期。
- 到最终确认挂号、支付、医保授权时必须停止。
- 失败时提示用户手动选择，不强行继续。

## 5. 端云接口建议

### 5.1 CloudPlannerRequest

```json
{
  "request_id": "uuid",
  "case_type": "health_booking",
  "user_goal": "book_hospital",
  "redaction_level": "strict",
  "cloud_safe": true,
  "health_summary": {
    "age_band": "70s",
    "symptoms": ["stomach_discomfort"],
    "duration": "2_days",
    "severity": "moderate",
    "risk_flags": []
  },
  "local_context_summary": {
    "preferred_hospital_available": true,
    "preferred_department": null,
    "free_time_windows": ["tomorrow_morning"]
  },
  "available_tools": ["book_hospital", "read_calendar_events"]
}
```

### 5.2 CloudPlannerResponse

```json
{
  "decision": "plan",
  "reason": "User wants hospital booking for stomach discomfort. Recommend outpatient gastroenterology if available.",
  "recommended_department": "消化内科",
  "task": {
    "intent": "book_hospital",
    "parameters": {
      "hospital": "preferred_hospital",
      "department": "消化内科",
      "date": "tomorrow"
    }
  },
  "risk_level": "medium",
  "requires_confirmation": true,
  "user_explanation": "我可以帮您打开挂号页面并选择消化内科，最后确认挂号时会停下来让您自己确认。"
}
```

### 5.3 FormFillPlan

```json
{
  "case_type": "always_on_form",
  "template_id": "community_registration_v1",
  "source": "local_only",
  "fields": [
    {
      "field_key": "display_name",
      "target_label": "姓名",
      "value_source": "local_memory",
      "risk_level": "medium"
    }
  ],
  "stop_before": ["提交", "支付", "授权", "验证码"]
}
```

## 6. Prompt 与输出约束

### 6.1 端侧表单助手 Prompt 重点

端侧模型或规则系统必须遵守：

- 你只能处理当前固定模板。
- 你不能上传原始表单内容。
- 你只能建议或填写本地授权字段。
- 碰到验证码、付款、授权、提交时停止。
- 输出必须是一个结构化 `NextAction` 或 `ask_user`。

### 6.2 云端看病挂号 Prompt 重点

云端 Gemma 31B 必须遵守：

- 你不是医生，不能给诊断结论。
- 你只基于脱敏摘要做挂号规划。
- 你不能要求用户上传身份证、手机号、验证码、原始截图。
- 你只能输出计划，不执行动作。
- 高风险或不确定时，建议线下就医或询问用户。

## 7. 开发里程碑

### Milestone 1：文档和固定 demo 方案

- 完成两个 case 的开发文档。
- 固定 demo 表单和挂号 demo 路径。
- 明确端侧、云侧、安全边界。

### Milestone 2：Always-on 表单 V1

- 新增固定表单模板识别。
- 新增本地记忆读取接口。
- 新增表单填充 action sequence。
- 提交前确认。

### Milestone 3：Trigger 看病 V1

- 新增健康问询状态机。
- 新增脱敏 `HealthTriageSummary`。
- 接入云端 planner adapter。
- 复用 `book_hospital` 执行挂号流程。

### Milestone 4：演示稳定性

- 固定两条 demo script。
- 增加失败兜底。
- 连续运行测试。
- 准备路演解释材料。

## 8. 测试计划

### 8.1 Always-on 表单测试

- 固定表单页面能触发轻量提醒。
- 非目标页面不触发强提醒。
- 用户拒绝后不执行。
- 用户确认后只填写允许字段。
- 缺字段时询问用户。
- 提交、支付、授权、验证码前停止。

### 8.2 Trigger 看病测试

- “胃不舒服”能进入健康问询。
- 本地问询不产生诊断式结论。
- 用户要求挂号后生成脱敏摘要。
- 云端 payload 不包含姓名、身份证、手机号、验证码、原始截图、完整病历。
- 云端 planner 能返回科室和挂号参数。
- 端侧能调用 `book_hospital` 路径。
- 最终确认挂号前停止。

### 8.3 安全测试

- `SafetyGuard` 对点击提交、确认、付款、授权返回 `REQUIRE_CONFIRMATION` 或 `DENY`。
- 验证码相关操作不自动执行。
- `cloudSafe=false` 时不上云。
- 诈骗关键词和付款组合触发阻断或强提醒。

### 8.4 演示稳定性测试

- 两条 case 每条连续跑 5 次。
- Accessibility 未开启时给出明确提示。
- 模型未就绪时走本地 fallback。
- 微信未安装或页面找不到时提示手动操作。

## 9. 当前限制

- 当前 Elva 已有挂号 skill 和 Accessibility 自动化基础，但真实医院小程序 UI 差异很大，V1 必须固定 demo 路径。
- 当前云端 planner 仍需要接入真实 Gemma 31B 服务或明确 mock adapter。
- 当前本地记忆需要补齐结构化存储与授权 UI。
- OCR fallback 目前是集成点，完整 OCR 能力需要补齐。
- 医疗场景必须定位为就医辅助和挂号辅助，不能定位为诊断或治疗。

## 10. 推荐比赛叙事

一句话：

> 老白不是替老人做决定，而是在复杂手机页面前帮老人看懂、填好、停住，并在关键一步提醒确认。

技术亮点：

- 端侧 Always-on 不是持续录屏，而是事件驱动、低打扰的辅助哨兵。
- 原始屏幕和敏感信息留在手机本地。
- 云端大模型只做复杂规划，不接触敏感原文。
- Android Accessibility 自动化负责真实 GUI 操作。
- `SafetyGuard` 让 Agent 在提交、付款、验证码、授权前停下来。

