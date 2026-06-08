<h1 align="center">
  Elva LaoBai（老白）
</h1>

<p align="center">
  <strong>专为老年人设计的语音优先、完全离线的 AI 安全助手</strong>
</p>

<p align="center">
  <strong>赛道 D: Social Good</strong> · Gemma 4 开发者大赛 2026
</p>

<p align="center">
  🎥 <a href="https://github.com/Elva-Gemma4Hackthon/Elva/releases"><strong>演示视频</strong></a> · 📄 <a href="https://github.com/Elva-Gemma4Hackthon/Elva/blob/main/README.md"><strong>技术报告</strong></a> · 🎤 <a href="docs/路演演示.html"><strong>路演PPT</strong></a> · 💻 <a href="https://github.com/Elva-Gemma4Hackthon/Elva"><strong>源码仓库</strong></a>
</p>

<p align="center">
  <a href="https://github.com/Elva-Gemma4Hackthon/Elva/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <img src="https://img.shields.io/badge/Platform-Android%2012%2B-brightgreen" alt="Platform">
  <img src="https://img.shields.io/badge/Language-Kotlin-purple" alt="Language">
  <img src="https://img.shields.io/badge/Min%20SDK-31-orange" alt="Min SDK">
  <img src="https://img.shields.io/badge/Model-Gemma%204-red" alt="Model">
</p>

<hr>

## 目录

- [背景与问题](#背景与问题)
- [概述](#概述)
- [为什么选择 Gemma 4](#为什么选择-gemma-4)
- [系统架构](#系统架构)
- [核心特性](#核心特性)
- [社会影响力](#社会影响力)
- [数据合规与隐私保护](#数据合规与隐私保护)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
- [可用 AI 模型](#可用-ai-模型)
- [文档](#文档)
- [团队](#团队)
- [致谢](#致谢)
- [许可证](#许可证)

<hr>

## 背景与问题

中国有超过 **2.97 亿** 60 岁以上老年人（2023年国家统计局数据），他们面临严峻的数字鸿沟和安全威胁：

- **电信诈骗重灾区**：老年人是电信诈骗的首要目标群体，2023年全国电信诈骗案件造成损失超千亿元
- **数字排斥**：复杂的 App 操作将老年人排除在数字化服务之外（挂号、缴费、出行）
- **隐私泄露风险**：老年人缺乏隐私保护意识，容易在操作中泄露身份证号、银行卡等敏感信息
- **情感孤立**：独居老人缺乏及时的智能陪伴和求助渠道

Elva LaoBai 正是为解决这些问题而生。

<hr>

## 概述

**Elva LaoBai（老白）** 是一款运行在 Android 设备上的 **完全离线、隐私优先** 的语音 AI 助手，专为中国老年用户设计。

基于 [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) 开源项目，利用 **MediaPipe LLM Inference API** 在设备本地运行 **Gemma** 系列 AI 模型，无需联网即可完成智能对话、诈骗检测、跨应用自动化操作等任务。

Elva 在 Google AI Edge Gallery 的基础上进行了深度定制，新增了专为老年人设计的 **五层安全防护架构**，涵盖隐私脱敏、诈骗检测、操作风险管控等关键能力，确保在提供便利的同时，最大限度地保护老年用户的隐私和财产安全。

> **核心理念：** 所有用户数据默认保留在设备上，不上传云端。

<hr>

## 为什么选择 Gemma 4

| 需求 | Gemma 4 如何满足 |
| --- | --- |
| **完全离线运行** | Gemma 4 提供从 1B 到 4B 的多种规格，可在主流 Android 手机本地运行，无需联网 |
| **原生函数调用** | Gemma 4 的 Native Function Calling 能力使 Elva 的 Skills 系统可以直接调用系统功能（拨打电话、发送消息、打开应用等） |
| **多模态理解** | 支持图像理解和音频理解，结合语音 UI 实现语音优先交互，降低老年用户使用门槛 |
| **长上下文** | 32K 上下文窗口支持复杂的多轮对话和完整的诈骗场景分析 |
| **思维链推理** | 内置 Chain-of-Thought 能力，使安全守护模块能够进行分级风险评估 |
| **高效量化** | LiteRT-LM 格式优化，Gemma-4-E4B 仅 ~3.4GB，12GB 设备即可流畅运行 |

<hr>

## 系统架构

Elva 采用独创的 **五层管道架构**，从事件捕获到最终执行，层层把关：

```
┌─────────────────────────────────────────────────────────────────┐
│                      用户层 (User Layer)                         │
│            🎙️ 语音输入 · 👆 触屏操作 · 📱 通知触发               │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 01: Edge Event                                           │
│  设备端触发事件捕获（无障碍事件、语音输入、通知等）                 │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 02: Screen Observation                                   │
│  结构化 UI 观察 → PII 自动脱敏（身份证/手机号/银行卡）            │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 03: Routing Decision (本地 Gemma 4 推理)                  │
│  智能路由：本地处理 / 云端规划 / 询问用户 / 紧急停止              │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 04: Next Action                                          │
│  语义层动作建议（点击、输入、滚动等），非原始坐标                  │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│  Layer 05: Guard Decision (Gemma 4 安全评估)                     │
│  三道防线：✅ 允许 / ⚠️ 需确认 / 🚫 拒绝                         │
└──────────────────────────┬──────────────────────────────────────┘
                           ▼
┌─────────────────────────────────────────────────────────────────┐
│                    执行层 (Action Layer)                          │
│    📱 AccessibilityService · 📞 电话 · 💬 短信 · 🔊 TTS · 🏥 健康  │
└─────────────────────────────────────────────────────────────────┘
```

<hr>

## 核心特性

### 🛡️ 五层安全防护架构

| 层级 | 名称 | 功能 |
| --- | --- | --- |
| **Layer 01** | Edge Event | 设备端触发事件捕获（无障碍事件、语音输入、通知等） |
| **Layer 02** | Screen Observation | 结构化 UI 观察，PII（身份证/手机/银行卡等）自动脱敏 |
| **Layer 03** | Routing Decision | 智能路由：本地处理 / 云端规划 / 询问用户 / 紧急停止 |
| **Layer 04** | Next Action | 语义层动作建议（点击、输入、滚动等），非原始坐标 |
| **Layer 05** | Guard Decision | 三道防线：允许 / 需确认 / 拒绝 |

### 🔐 核心功能模块

|  |  |
| --- | --- |
| 🔍 **Always On Sentinel** 后台事件驱动的智能监控系统。不持续录屏，仅在检测到风险或用户停滞时主动触发，保护隐私的同时提供及时协助。 | 🚨 **ScamGuard 诈骗守护** 识别 **6 类**常见诈骗模式（冒充公检法、中奖诈骗、付款诈骗、钓鱼、家人紧急、投资诈骗），自动语音警告并引导拨打 **96110** 反诈热线。 |
| 🔒 **PrivacyFirewall 隐私防火墙** 身份证号、手机号、银行卡号、邮箱地址 **自动脱敏**。识别敏感字段名，检测支付/验证码/授权等高风险关键词。 | ⚖️ **SafetyGuard 安全守护** 三级安全输出（事实 + 推断 + 建议），分级的操作风险评估，确保每一步操作都在安全边界内。 |
| 🧭 **LocalRouter 本地路由** 基于关键词和上下文的智能路由引擎，快速判断用户意图，决定处理路径。 | 👨‍👩‍👧 **FamilyAssist 家人协助** 自动生成**脱敏**的求助卡片发送给家人，原始敏感信息不会离开设备。 |
| 🤖 **跨应用自动化** 通过 **AccessibilityService** 实现代点按钮、输入文字、验证码识别等跨应用操作，支持微信挂号、支付宝缴费等场景。 | 🎙️ **语音优先 UI** **160dp 大麦克风按钮**、高对比度、脉冲动画、大字体，专为老人设计的语音交互界面，支持中文 TTS 语音播报。 |
| 🏥 **健康分诊** 基于症状的健康分诊与云端规划，帮助老年人快速获取就医建议。 | 📝 **表单自动填充** 智能表单模板匹配与自动填充，简化老年人填表操作。 |

### 🧩 Skills 技能系统

Elva 支持通过 `SKILL.md` 文件扩展能力，三种执行路径：

| 类型 | 说明 | 示例 |
| --- | --- | --- |
| **纯文本 Skills** | 修改 LLM 上下文，无需特殊权限 | 诈骗检测、厨房冒险游戏 |
| **JavaScript Skills** | 在隐藏 WebView 中执行 JS | 计算哈希、查询维基百科、生成二维码 |
| **Native Intents** | 通过 Android Intent 调用系统功能 | 发送邮件、打电话、打开相机 |

内置 **20 个**技能（挂号、缴费、地图、心情追踪等），支持从社区精选列表、URL 或本地加载更多。

### 🔌 MCP 集成

支持 **Model Context Protocol**，可连接本地和云端 MCP 服务器，扩展 AI 能力边界。支持自定义认证、工具开关管理和权限控制。

<hr>

## 社会影响力

Elva LaoBai 面向的是一个真实且紧迫的社会问题：

- **反诈防线**：通过本地 AI 实时识别诈骗，在老年人受害前拦截，预计可覆盖 6 类高发诈骗场景，联动 96110 国家反诈中心
- **数字包容**：语音优先 + 跨应用自动化，让不会操作智能手机的老人也能享受挂号、缴费等基本数字服务
- **隐私标杆**：完全离线架构意味着用户数据永远不离开设备，为老年智能设备的隐私保护树立了新标准
- **家庭关怀**：脱敏求助卡片机制让家人能及时了解老人状况，同时不暴露敏感信息
- **健康守护**：本地健康分诊 + 用户记忆系统，提供个性化的健康建议和日常陪伴
- **可扩展性**：Skills 系统 + MCP 协议使 Elva 可以被社区扩展到更多适老化场景

<hr>

## 数据合规与隐私保护

作为赛道 D (Social Good) 的参赛项目，Elva 将隐私保护作为核心设计原则：

### 数据最小化

- **完全离线运行**：所有 AI 推理在设备本地完成，无需将任何用户数据上传到云端
- **不收集用户数据**：不包含任何遥测、分析或用户行为追踪代码
- **不持久化敏感数据**：对话历史和屏幕观察数据仅在处理过程中临时存在

### 自动隐私脱敏 (Layer 02)

| 敏感数据类型 | 脱敏策略 |
| --- | --- |
| 身份证号 | 正则匹配后替换为 `***` |
| 手机号码 | 保留前 3 后 4 位，中间掩码 |
| 银行卡号 | 完全掩码处理 |
| 邮箱地址 | 本地部分掩码 |
| 密码/验证码 | 检测到相关上下文时触发告警 |

### 安全操作管控

- **三道防线机制**（Layer 05）：涉及资金转账、敏感信息发送等高风险操作时，必须经用户确认或直接拒绝
- **家人求助脱敏**：FamilyAssist 生成的求助卡片自动移除所有 PII 信息，确保敏感信息不会以任何形式离开设备

### 合规说明

- 遵循《个人信息保护法》最小必要原则
- 符合《网络安全法》数据安全要求
- AccessibilityService 权限仅在用户明确授权后启用，且数据不外传

<hr>

## 技术栈

| 类别 | 技术 | 用途 |
| --- | --- | --- |
| **语言 & 平台** | Kotlin 2.2.0 | 主要编程语言 |
| | Android SDK 35 (minSdk 31) | 目标平台 |
| **UI 框架** | Jetpack Compose (BOM 2026.02) | 声明式 UI |
| | Compose RichText + CommonMark | Markdown 渲染 |
| **AI 推理** | MediaPipe LLM Inference API (v0.11.0) | 本地 LLM 推理引擎 |
| | Gemma 4 / Gemma 3n | 设备端 AI 模型（LiteRT-LM / int4 量化） |
| **网络 & 数据** | Ktor 3.4.3 | HTTP 客户端 |
| | Moshi | JSON 解析 |
| | DataStore | 本地键值存储 |
| **架构** | Hilt 2.58 | 依赖注入 |
| | Protobuf 4.26.1 | 数据序列化 |
| **MCP** | MCP Kotlin SDK 0.8.0 | Model Context Protocol 客户端 |
| **TTS** | Android TTS | 中文语音合成 |

<hr>

## 项目结构

```
Elva/
├── README.md
├── LICENSE                        # Apache 2.0
└── src/
    ├── CONTRIBUTING.md            # 贡献指南
    ├── DEVELOPMENT.md             # 开发配置说明
    ├── Function_Calling_Guide.md  # 自定义 Function Calling 指南
    ├── Bug_Reporting_Guide.md     # Bug 报告指南
    ├── model_allowlist.json       # 可用 AI 模型列表（5个）
    │
    ├── Android/                   # Android 应用源码
    │   └── src/
    │       ├── app/               # 主应用模块
    │       │   └── src/main/java/
    │       │       ├── com/elva/laobai/      # Elva 定制代码
    │       │       │   ├── guard/            # 安全守护（诈骗/安全/家人协助）
    │       │       │   ├── privacy/          # 隐私防火墙
    │       │       │   ├── router/           # 本地路由
    │       │       │   ├── sentinel/         # Always On 监控
    │       │       │   ├── accessibility/    # 无障碍服务
    │       │       │   ├── inference/        # 模型推理桥接
    │       │       │   ├── executor/         # 执行层（动作执行/技能/工具注册）
    │       │       │   ├── health/           # 健康分诊与云端规划
    │       │       │   ├── forms/            # 表单模板匹配与自动填充
    │       │       │   ├── memory/           # 本地用户记忆
    │       │       │   ├── contacts/         # 联系人解析
    │       │       │   ├── observer/         # 屏幕观察
    │       │       │   ├── model/            # 模型任务模块
    │       │       │   ├── ui/               # 语音 UI 界面
    │       │       │   └── models/           # 五层数据模型
    │       │       └── com/google/ai/edge/gallery/  # Google 基础代码
    │       ├── gradle/libs.versions.toml      # 依赖版本管理
    │       └── build.gradle.kts              # 根构建配置
    │
    ├── skills/                    # Agent Skills 系统
    │   ├── built-in/              # 16 个内置技能（APK 内置 20 个）
    │   └── featured/              # 3 个精选社区技能
    │
    ├── mcp/                       # MCP 协议集成
    │
    ├── model_allowlists/          # 历史模型白名单版本
    │
    └── .github/                   # Issue 模板 + CI/CD
        └── workflows/
            ├── build_android.yaml # 自动构建 APK
            └── static.yml         # Skills 部署到 GitHub Pages
```

<hr>

## 快速开始

### 环境要求

- **Android Studio**（推荐最新稳定版）
- **JDK 21**
- **Android SDK 35**
- **Android 12+ (API 31+)** 设备（建议 6GB+ 内存）
- **HuggingFace OAuth App**（用于模型下载认证）

### 构建步骤

1. **克隆仓库**

   ```bash
   git clone https://github.com/Elva-Gemma4Hackthon/Elva.git
   cd Elva
   ```

2. **配置 HuggingFace OAuth**

   在 `ProjectConfig.kt` 中设置 `clientId` 和 `redirectUri`，并在 `app/build.gradle.kts` 中更新 `manifestPlaceholders["appAuthRedirectScheme"]`。

   详细说明请参考 [DEVELOPMENT.md](src/DEVELOPMENT.md)。

3. **构建 APK**

   ```bash
   cd src/Android/src
   ./gradlew assembleRelease
   ```

4. **安装到设备**

   ```bash
   ./gradlew installDebug
   ```

### CI/CD

项目使用 GitHub Actions 实现持续集成与部署：

- **build_android.yaml**：自动构建 Android APK
- **static.yml**：自动部署 Skills 到 GitHub Pages

<hr>

## 可用 AI 模型

| 模型 | 大小 | 最低内存 | 量化 | 支持能力 |
| --- | --- | --- | --- | --- |
| **Gemma-4-E4B-it** | ~3.4 GB | ≥ 12 GB | LiteRT-LM | 对话 · 图像理解 · 音频理解 · 思维链 · 32K 上下文 |
| **Gemma-3n-E2B-it** | ~2.9 GB | ≥ 6 GB | int4 | 对话 · 图像理解 · 4K 上下文 |
| **Gemma-3n-E4B-it** | ~4.1 GB | ≥ 8 GB | int4 | 对话 · 图像理解 · 4K 上下文 |
| **Gemma3-1B-IT** | ~557 MB | ≥ 2 GB | int4 | 对话 · 提示实验 |

> 推荐配置：日常使用 Gemma-3n-E2B-it（2.9GB，6GB 设备可运行），高端设备可选 Gemma-4-E4B 获得更强推理能力。

<hr>

## 文档

| 文档 | 说明 |
| --- | --- |
| [DEVELOPMENT.md](src/DEVELOPMENT.md) | 开发环境搭建与 HuggingFace OAuth 配置 |
| [CONTRIBUTING.md](src/CONTRIBUTING.md) | 贡献指南 |
| [Function Calling Guide](src/Function_Calling_Guide.md) | 自定义 Function Calling 实现指南 |
| [Bug Reporting Guide](src/Bug_Reporting_Guide.md) | Android Bug 报告流程 |
| [Skills README](src/skills/README.md) | Skills 系统完整文档 |
| [MCP README](src/mcp/README.md) | MCP 协议集成文档 |

<hr>

## 团队

**队伍：Elva**

| 成员 | |
| --- | --- |
| River | |
| Xavier | |
| 雷雨 | |
| ichi | |
| Asandstar | |

<hr>

## 致谢

本项目基于 [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) 开源项目构建。感谢 Google AI Edge 团队提供的优秀基础设施。

<hr>

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源许可证。

<p align="center">
  <br>
  <sub>Made with ❤️ for the elderly</sub>
</p>
