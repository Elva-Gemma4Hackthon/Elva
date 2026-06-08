<h1 align="center">
  🧠<br>
  Elva LaoBai
</h1>

<p align="center">
  <strong>专为老年人设计的语音优先 AI 助手</strong>
</p>

<p align="center">
  <a href="https://github.com/your-org/Elva/blob/main/LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License"></a>
  <a href="#"><img src="https://img.shields.io/badge/Platform-Android%2012%2B-brightgreen" alt="Platform"></a>
  <a href="#"><img src="https://img.shields.io/badge/Language-Kotlin-purple" alt="Language"></a>
  <a href="#"><img src="https://img.shields.io/badge/Min%20SDK-31-orange" alt="Min SDK"></a>
  <a href="#"><img src="https://img.shields.io/badge/Target%20SDK-35-blue" alt="Target SDK"></a>
</p>

<hr>

<h2>概述</h2>

<p>
  <strong>Elva LaoBai（老白）</strong> 是一款运行在 Android 设备上的 <strong>完全离线、隐私优先</strong> 的语音 AI 助手，
  专为中国老年用户设计。基于 <a href="https://github.com/google-ai-edge/gallery">Google AI Edge Gallery</a> 开源项目，
  利用 <strong>MediaPipe LLM Inference API</strong> 在设备本地运行 <strong>Gemma</strong> 系列 AI 模型，
  无需联网即可完成智能对话、自动化操作等任务。
</p>

<p>
  Elva 在 Google AI Edge Gallery 的基础上进行了深度定制，新增了专为老年人设计的
  <strong>五层安全防护架构</strong>，涵盖隐私脱敏、诈骗检测、操作风险管控等关键能力，
  确保在提供便利的同时，最大限度地保护老年用户的隐私和财产安全。
</p>

<blockquote>
  <p><strong>核心理念：</strong>所有用户数据默认保留在设备上，不上传云端。</p>
</blockquote>

<hr>

<h2>核心特性</h2>

<h3>🛡️ 五层安全防护架构</h3>

<p>Elva 独创的五层管道架构，从事件捕获到最终执行，层层把关：</p>

<table>
  <tr>
    <th>层级</th>
    <th>名称</th>
    <th>功能</th>
  </tr>
  <tr>
    <td><strong>Layer 01</strong></td>
    <td>Edge Event</td>
    <td>设备端触发事件捕获（无障碍事件、语音输入、通知等）</td>
  </tr>
  <tr>
    <td><strong>Layer 02</strong></td>
    <td>Screen Observation</td>
    <td>结构化 UI 观察，PII（身份证/手机/银行卡等）自动脱敏</td>
  </tr>
  <tr>
    <td><strong>Layer 03</strong></td>
    <td>Routing Decision</td>
    <td>智能路由：本地处理 / 云端规划 / 询问用户 / 紧急停止</td>
  </tr>
  <tr>
    <td><strong>Layer 04</strong></td>
    <td>Next Action</td>
    <td>语义层动作建议（点击、输入、滚动等），非原始坐标</td>
  </tr>
  <tr>
    <td><strong>Layer 05</strong></td>
    <td>Guard Decision</td>
    <td>三道防线：允许 / 需确认 / 拒绝</td>
  </tr>
</table>

<h3>🔐 核心功能模块</h3>

<table>
  <tr>
    <td width="50%">
      <h4>🔍 Always On Sentinel</h4>
      <p>后台事件驱动的智能监控系统。不持续录屏，仅在检测到风险或用户停滞时主动触发，保护隐私的同时提供及时协助。</p>
    </td>
    <td width="50%">
      <h4>🚨 ScamGuard 诈骗守护</h4>
      <p>识别 <strong>6 类</strong>常见诈骗模式（冒充公检法、中奖诈骗、付款诈骗、钓鱼、家人紧急、投资诈骗），自动语音警告并引导拨打 <strong>96110</strong> 反诈热线。</p>
    </td>
  </tr>
  <tr>
    <td>
      <h4>🔒 PrivacyFirewall 隐私防火墙</h4>
      <p>身份证号、手机号、银行卡号、邮箱地址 <strong>自动脱敏</strong>。识别敏感字段名，检测支付/验证码/授权等高风险关键词。</p>
    </td>
    <td>
      <h4>⚖️ SafetyGuard 安全守护</h4>
      <p>三级安全输出（事实 + 推断 + 建议），分级的操作风险评估，确保每一步操作都在安全边界内。</p>
    </td>
  </tr>
  <tr>
    <td>
      <h4>🧭 LocalRouter 本地路由</h4>
      <p>基于关键词和上下文的智能路由引擎，快速判断用户意图，决定处理路径。</p>
    </td>
    <td>
      <h4>👨‍👩‍👧 FamilyAssist 家人协助</h4>
      <p>自动生成<strong>脱敏</strong>的求助卡片发送给家人，原始敏感信息不会离开设备。</p>
    </td>
  </tr>
  <tr>
    <td>
      <h4>🤖 跨应用自动化</h4>
      <p>通过 <strong>AccessibilityService</strong> 实现代点按钮、输入文字、验证码识别等跨应用操作，支持微信挂号、支付宝缴费等场景。</p>
    </td>
    <td>
      <h4>🎙️ 语音优先 UI</h4>
      <p><strong>160dp 大麦克风按钮</strong>、高对比度、脉冲动画、大字体，专为老人设计的语音交互界面，支持中文 TTS 语音播报。</p>
    </td>
  </tr>
</table>

<h3>🧩 Skills 技能系统</h3>

<p>Elva 支持通过 <code>SKILL.md</code> 文件扩展能力，三种执行路径：</p>

<table>
  <tr>
    <th>类型</th>
    <th>说明</th>
    <th>示例</th>
  </tr>
  <tr>
    <td><strong>纯文本 Skills</strong></td>
    <td>修改 LLM 上下文，无需特殊权限</td>
    <td>诈骗检测、厨房冒险游戏</td>
  </tr>
  <tr>
    <td><strong>JavaScript Skills</strong></td>
    <td>在隐藏 WebView 中执行 JS</td>
    <td>计算哈希、查询维基百科、生成二维码</td>
  </tr>
  <tr>
    <td><strong>Native Intents</strong></td>
    <td>通过 Android Intent 调用系统功能</td>
    <td>发送邮件、打电话、打开相机</td>
  </tr>
</table>

<p>内置 <strong>20 个</strong>技能（挂号、缴费、地图、心情追踪等），支持从社区精选列表、URL 或本地加载更多。</p>

<h3>🔌 MCP 集成</h3>

<p>支持 <strong>Model Context Protocol</strong>，可连接本地和云端 MCP 服务器，扩展 AI 能力边界。支持自定义认证、工具开关管理和权限控制。</p>

<hr>

<h2>技术栈</h2>

<table>
  <tr>
    <th>类别</th>
    <th>技术</th>
    <th>用途</th>
  </tr>
  <tr>
    <td rowspan="2"><strong>语言 &amp; 平台</strong></td>
    <td>Kotlin 2.2.0</td>
    <td>主要编程语言</td>
  </tr>
  <tr>
    <td>Android SDK 35 (minSdk 31)</td>
    <td>目标平台</td>
  </tr>
  <tr>
    <td rowspan="2"><strong>UI 框架</strong></td>
    <td>Jetpack Compose (BOM 2026.02)</td>
    <td>声明式 UI</td>
  </tr>
  <tr>
    <td>Compose RichText + CommonMark</td>
    <td>Markdown 渲染</td>
  </tr>
  <tr>
    <td rowspan="2"><strong>AI 推理</strong></td>
    <td>MediaPipe LLM Inference API (v0.11.0)</td>
    <td>本地 LLM 推理引擎</td>
  </tr>
  <tr>
    <td>Gemma 4 / Gemma 3n</td>
    <td>设备端 AI 模型（LiteRT-LM / int4 量化）</td>
  </tr>
  <tr>
    <td rowspan="3"><strong>网络 &amp; 数据</strong></td>
    <td>Ktor 3.4.3</td>
    <td>HTTP 客户端</td>
  </tr>
  <tr>
    <td>Moshi</td>
    <td>JSON 解析</td>
  </tr>
  <tr>
    <td>DataStore</td>
    <td>本地键值存储</td>
  </tr>
  <tr>
    <td rowspan="2"><strong>架构</strong></td>
    <td>Hilt 2.58</td>
    <td>依赖注入</td>
  </tr>
  <tr>
    <td>Protobuf 4.26.1</td>
    <td>数据序列化</td>
  </tr>
  <tr>
    <td><strong>MCP</strong></td>
    <td>MCP Kotlin SDK 0.8.0</td>
    <td>Model Context Protocol 客户端</td>
  </tr>
  <tr>
    <td><strong>TTS</strong></td>
    <td>Android TTS</td>
    <td>中文语音合成</td>
  </tr>
</table>

<hr>

<h2>项目结构</h2>

<pre>
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
    ├── Android/                   # <strong>Android 应用源码</strong>
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
    ├── skills/                    # <strong>Agent Skills 系统</strong>
    │   ├── built-in/              # 16 个内置技能（APK 内置 20 个）
    │   └── featured/              # 3 个精选社区技能
    │
    ├── mcp/                       # <strong>MCP 协议集成</strong>
    │
    ├── model_allowlists/          # 历史模型白名单版本
    │
    └── .github/                   # Issue 模板 + CI/CD
        └── workflows/
            ├── build_android.yaml # 自动构建 APK
            └── static.yml         # Skills 部署到 GitHub Pages
</pre>

<hr>

<h2>快速开始</h2>

<h3>环境要求</h3>

<ul>
  <li><strong>Android Studio</strong>（推荐最新稳定版）</li>
  <li><strong>JDK 21</strong></li>
  <li><strong>Android SDK 35</strong></li>
  <li><strong>Android 12+ (API 31+)</strong> 设备</li>
  <li><strong>HuggingFace OAuth App</strong>（用于模型下载认证）</li>
</ul>

<h3>构建步骤</h3>

<ol>
  <li>
    <p><strong>克隆仓库</strong></p>
    <pre><code>git clone https://github.com/your-org/Elva.git
cd Elva</code></pre>
  </li>
  <li>
    <p><strong>配置 HuggingFace OAuth</strong></p>
    <p>在 <code>ProjectConfig.kt</code> 中设置 <code>clientId</code> 和 <code>redirectUri</code>，并在 <code>app/build.gradle.kts</code> 中更新 <code>manifestPlaceholders["appAuthRedirectScheme"]</code>。</p>
    <p>详细说明请参考 <a href="src/DEVELOPMENT.md">DEVELOPMENT.md</a>。</p>
  </li>
  <li>
    <p><strong>构建 APK</strong></p>
    <pre><code>cd src/Android/src
gradlew assembleRelease</code></pre>
  </li>
  <li>
    <p><strong>安装到设备</strong></p>
    <pre><code>gradlew installDebug</code></pre>
  </li>
</ol>

<h3>CI/CD</h3>

<p>项目使用 GitHub Actions 实现持续集成与部署：</p>
<ul>
  <li><strong>build_android.yaml</strong>：自动构建 Android APK</li>
  <li><strong>static.yml</strong>：自动部署 Skills 到 GitHub Pages</li>
</ul>

<hr>

<h2>可用 AI 模型</h2>

<table>
  <tr>
    <th>模型</th>
    <th>大小</th>
    <th>最低内存</th>
    <th>量化</th>
    <th>支持能力</th>
  </tr>
  <tr>
    <td><strong>Gemma-4-E4B-it</strong> 🆕</td>
    <td>~3.4 GB</td>
    <td>≥ 12 GB</td>
    <td>LiteRT-LM</td>
    <td>对话 · 图像理解 · 音频理解 · 思维链 · 32K 上下文</td>
  </tr>
  <tr>
    <td><strong>Gemma-3n-E2B-it</strong></td>
    <td>~2.9 GB</td>
    <td>≥ 6 GB</td>
    <td>int4</td>
    <td>对话 · 图像理解 · 4K 上下文</td>
  </tr>
  <tr>
    <td><strong>Gemma-3n-E4B-it</strong></td>
    <td>~4.1 GB</td>
    <td>≥ 8 GB</td>
    <td>int4</td>
    <td>对话 · 图像理解 · 4K 上下文</td>
  </tr>
  <tr>
    <td><strong>Gemma3-1B-IT</strong></td>
    <td>~557 MB</td>
    <td>≥ 2 GB</td>
    <td>int4</td>
    <td>对话 · 提示实验</td>
  </tr>
</table>

<hr>

<h2>文档</h2>

<table>
  <tr>
    <th>文档</th>
    <th>说明</th>
  </tr>
  <tr>
    <td><a href="src/DEVELOPMENT.md">DEVELOPMENT.md</a></td>
    <td>开发环境搭建与 HuggingFace OAuth 配置</td>
  </tr>
  <tr>
    <td><a href="src/CONTRIBUTING.md">CONTRIBUTING.md</a></td>
    <td>贡献指南</td>
  </tr>
  <tr>
    <td><a href="src/Function_Calling_Guide.md">Function Calling Guide</a></td>
    <td>自定义 Function Calling 实现指南</td>
  </tr>
  <tr>
    <td><a href="src/Bug_Reporting_Guide.md">Bug Reporting Guide</a></td>
    <td>Android Bug 报告流程</td>
  </tr>
  <tr>
    <td><a href="src/skills/README.md">Skills README</a></td>
    <td>Skills 系统完整文档</td>
  </tr>
  <tr>
    <td><a href="src/mcp/README.md">MCP README</a></td>
    <td>MCP 协议集成文档</td>
  </tr>
</table>

<hr>

<h2>从源码构建的模型白名单</h2>

<p>
  如需自定义模型白名单，修改 <code>src/model_allowlist.json</code> 文件即可。
  历史版本存档在 <code>src/model_allowlists/</code> 目录下。
</p>

<hr>

<h2>特别致谢</h2>

<p>
  本项目基于 <a href="https://github.com/google-ai-edge/gallery">Google AI Edge Gallery</a> 开源项目构建。
  感谢 Google AI Edge 团队提供的优秀基础设施。
</p>

<hr>

<h2>许可证</h2>

<p>
  本项目采用 <a href="LICENSE">Apache License 2.0</a> 开源许可证。
</p>

<p align="center">
  <br>
  <sub>Made with ❤️ for the elderly</sub>
</p>
