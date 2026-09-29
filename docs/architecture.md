# AgentOS 架构

![AgentOS 架构图](assets/architecture.svg)

> **结论**：AgentOS 只有一种交付形态：**已 root 的手机 + 一个 zip**。用户在 Magisk 或 KernelSU 里刷入 `agentos.zip` 并重启，模块会自动装好 AgentOS App，并由 root 监督进程拉起和守护 Agent 运行时。运行时是 AgentOS App 里的一个独立进程 `:agent`：外层是 Kotlin 宿主层，负责 ACP、身份、存储、调度和恢复；Agent 循环用上游的 **Pi Agent core**（`@earendil-works/pi-agent-core`），跑在进程内嵌的 QuickJS 里。**root 只负责安装、守护和拉起，不运行 AgentOS 自己的任何代码。** 对外只有一种协议：**ACP**。AgentOS App 自带的界面、后装的第三方 App、电脑上的 ACP 客户端，都通过 ACP 调用同一个运行时。

项目计划、目录结构、依赖顺序和任务清单见 [implementation-plan.md](implementation-plan.md)；扩展模块（插件、MCP、Skills、Hooks）的完整设计见 [extensions.md](extensions.md)。

---

## 1. 背景与范围

原型 [agenriod](https://github.com/HanZijie/agenroid) 把 Agent 做进了 AOSP 系统镜像：`init` 拉起 `sideagentd`，`system_server` 里的 `AgentManagerService` 做控制面。这证明了“Agent 作为系统级常驻服务”行得通，但用户必须自己编译镜像并刷机，极客几乎玩不起来。

本项目要做的是：**不刷 ROM，靠 root 权限在用户现有的系统上提供同样的体验**，包括常驻的 Agent、任意 App 都能调用、App 能力以插件形式接入。

**不在本项目范围内：**
- 刷 ROM、AOSP 源码集成、在 `system_server` 里注册服务、平台签名。原型 agenriod 保留这个方向的历史实现，本项目不再维护。
- 没有 root 的设备。
- 多用户。M6 可以做实验，但不作为承诺。

**支持范围**：Android 15–17（API 35–37）。首批只承诺 Pixel 官方系统和 LineageOS，其他设备在支持矩阵里标为“社区适配”。

---

## 2. 决策记录

| 议题 | 决定 | 理由 |
|---|---|---|
| 交付形态 | 只做 root + zip | 目标用户是已经 root 的极客；刷 ROM 门槛太高，维护机型的成本也太高 |
| 运行时放在哪里 | AgentOS App 的独立进程 `:agent`；root 只负责安装、守护和拉起 | 所有入口（自带界面、第三方 App、电脑端）和出口（工具调用、用户确认）本来就要经过 AgentOS App，把运行时单独放进 root 进程换不来真正的独立性。放在 App 里可以直接使用官方 ACP / MCP Kotlin SDK 和 Binder，不需要自己写 C++ ACP 服务端和内部 socket 协议；root 进程也不再解析模型输出、工具结果这类不可信输入 |
| Agent 核心 | 上游 Pi Agent core（`@earendil-works/pi-agent-core`，固定 0.86.1），连同 `pi-ai` 打成单文件放进 APK，跑在 `:agent` 进程内嵌的 QuickJS 里；Kotlin 宿主层包在它外面（4.1 节） | Profile 已经确定 Pi 接入的分层：服务负责 ACP、身份、存储、幂等、恢复和监管，Pi 负责模型调用、Agent 循环、工具执行和上下文。原型 agenriod 已经在 APK 内嵌的 QuickJS 里跑通上游 Agent 循环，不需要 Node。用 core 而不是 Profile 里提到的 `pi-coding-agent`，是因为后者自带编码工具和会话文件，依赖 Node 运行环境，手机上用不到 |
| 模型接入 | BYOK，采用混合方案：协议适配用 `pi-ai`，只打包 `anthropic-messages` 和 `openai-completions` 两个协议族；网络、key、重试和取消都留在 Kotlin 宿主层。设置页提供两种来源：厂商预设（来自 `pi-ai` 的模型目录，MiniMax 国际 / 国内排第一）和自定义兼容端点。首版不做订阅账号登录（OAuth） | `pi-ai` 已经维护好流式工具调用、thinking、图片、prompt cache、用量计算和几十家厂商的模型目录，自己重写代价大；但它依赖的官方 SDK 不保证能在 QuickJS 里运行，所以只取协议适配，I/O 不交给 JS。两个协议族已覆盖 MiniMax 和主要的国内外厂商。订阅登录依赖 Node 的本地回调服务，还需要逐家确认条款 |
| root 的用途 | 开机后安装或升级 App 和 Runner；开机拉起运行时；运行时在有任务时被杀，按退避拉起；崩溃循环时进入 safe mode | 普通 App 做不到，又直接影响“刷完就能用”的，只有这几件事 |
| 对外协议 | ACP，按 [AgentOS ACP Profile v1](../../agentos-acp-profile-v1.md) 执行 | Profile 已经确定：ACP 是前端与 Agent 服务之间唯一的对外协议，不另维护 `agent-bus/1`；有现成的各语言 SDK 和客户端生态 |
| ACP 的实现 | 客户端和 Agent 端都用官方 Kotlin SDK，固定为 `com.agentclientprotocol:acp:0.30.1`；Android 上只用基于 Binder 的自定义 Transport | 已验证在 `compileSdk` 35 / 36 下编译、D8、R8 都能通过；R8 需要一条 `-dontwarn org.slf4j.**`（由 `acp-android` 的 consumer 规则带入），运行时要在 SDK 第一次记日志之前设置 `kotlin-logging-to-android-native=true`，否则第一次 prompt 就因缺 slf4j 崩溃。S3 第二部分已在 API 35 / 36 / 37 模拟器上跑通 ACP over Binder，真机待做（[spikes/S3.md](spikes/S3.md)）。SDK 没有声明 Android target，属于 JVM 兼容性落地；master 分支的 Transport API 已经变了，所以固定版本 |
| QuickJS 运行时与会话 | 一个 QuickJS 运行时承载全部会话，由“常驻泵”驱动：宿主只求值一次入口，JS 在 `__host_next()` 上取命令、异步分发，结果和事件经 `__host_emit()` 回到 Kotlin。绑定 `quickjs-kt` 固定 1.0.15，因此 Kotlin 不低于 2.3 | quickjs-kt 把同一实例上的顶层求值串行化，“每轮一次 evaluate”会让其他会话和 `abort` 排队；每多一个运行时 native 堆约 +5 MB，每多一个会话只 +18 KB。未处理的 Promise rejection 会终止泵，按运行时崩溃处理（F8），重建约 70 ms（[spikes/S8.md](spikes/S8.md)） |
| App 之间的连接 | 统一用 Binder 消息通道 `binder-channel-v1`：ACP（第三方 App → AgentOS）和 MCP（AgentOS → 插件 App）共用同一种 AIDL 形状 | Binder 自带内核认证的调用方 UID；ACP 和 MCP 都是 JSON-RPC 消息流，不需要两套传输 |
| App 内部各进程之间 | 不导出的 AIDL 服务，只接受本 App 的 UID | 同一个 App 内部的接口，随 App 一起升级，不作为对外契约 |
| 电脑端接入 | `adb forward` 到 `:agent` 进程的抽象 socket，按行分隔的 ACP JSON，与 stdio 语义相同；一次性配对码 | 电脑上的 ACP 客户端大多只支持 stdio，桥接命令只需要转发字节 |
| 控制面 | 普通 App（AgentOS App），不需要平台签名和特权权限 | root 模块拿不到平台签名；需要的能力用普通 App 权限加用户授权就能覆盖 |
| App 的安装 | APK 打进 zip，由模块在开机后自动安装和升级 | 用户只操作一次：刷 zip |
| 调用方认证 | 以 Binder 取得的调用方 UID 为准；电脑端用一次性配对码 | 不相信客户端自报的包名或 UID |
| 插件格式 | Agent Plugins 1.0（OpenAI 等多家公司共同发起的开放标准）：根 `plugin.json` + `skills/` + `mcp.json`；Hooks 按 OpenAI 的扩展方式读取；本地 App 的 Binder MCP 端点声明在 `plugin.json` 的 `extensions."org.agentos"` 里。不采用 MiniMax Code 的插件格式，也不兼容 `.codex-plugin` 等旧清单 | 一次打包，多个客户端通用。`mcp.json` 的 schema 只允许 `stdio`、`streamable-http`、`sse` 三种条目，放不下 Binder 端点；`extensions` 是规范留给客户端专属内容的位置 |
| MCP 传输 | 本地 App：Binder（自定义传输）；远端：只支持 Streamable HTTP（`https://`）。不支持 `stdio`，也不支持旧的 HTTP+SSE | `stdio` 需要在手机上启动子进程和解释器；HTTP+SSE 从 MCP 2025-03-26 修订版起已被弃用 |
| 扩展模块的位置 | AgentOS App 里的独立进程 `:ext`（Extension Host） | 需要 `bindService()`、PackageManager、Keystore 等 Android 框架能力；第三方插件包和远端流的解析与运行时隔离，崩溃不影响运行时和界面 |
| 命令的执行 | Hooks、shell 工具、Skill 脚本一律在 Runner（独立 UID 的 APK）里用 `/system/bin/sh` 执行 | 不能用 root；也不能用 AgentOS App 的 UID，否则命令可以读到运行时的数据和密钥、调用 App 的内部接口 |
| 本地 MCP 服务的访问控制 | `org.agentos.permission.BIND_MCP_SERVICE`，由 AgentOS App 定义，signature 级 | 只有 AgentOS 能 bind；提供插件的 App 不需要和 AgentOS 同证书，也不需要平台签名 |
| 工具调用的确认 | 由 AgentOS 自己的界面确认；调用方 App 只能追加拒绝 | 否则恶意 App 可以替用户同意操作其他 App 或用户数据的工具 |
| 安全等级 | 固定为 `best_effort`，在设置页如实告知 | 设备已经 root，其他 root 应用可以读取 AgentOS App 的数据 |
| 电源键唤起 | 只走系统标准的默认助理角色 | 不用 root 改系统设置，不 hook framework |
| 安装、升级、卸载 | 安装前检查、safe mode、崩溃循环保护、支持矩阵 | 对 root 模块来说，不让手机出问题是第一优先级 |

---

## 3. 设计原则

1. **对外只有 ACP**。标准能力按 ACP 标准实现；AgentOS 特有的能力只通过 Profile 规定的方式扩展（`_meta` 和 `_agentos/…` 方法），并且在初始化时协商。
2. **root 只运行模块脚本**。root 用来：安装前检查、安装 App、开机拉起运行时、判活与退避拉起、safe mode。AgentOS 的任何代码都不以 root 运行；模型、插件和 ACP 客户端都接触不到 root。助理角色、通知权限和电池优化豁免走系统标准的用户授权流程。
3. **事实在运行时**。会话、任务和事件的事实只存在 `:agent` 进程的 Store 里，界面不持有任务事实。断开连接不等于关闭会话；结果未知的副作用不自动重放。
4. **身份来自内核**。调用方身份一律取自 Binder 的调用方 UID，电脑端用一次性配对码，不相信客户端自报。
5. **用户掌握授权**。第三方 App 首次调用要经过用户同意；工具调用由 AgentOS 确认；授权都可以随时撤销，每个 App 的用量都能看到。
6. **安全等级如实告知**。设备已经 root，其他 root 应用可以读取 key 和日志，这一点直接写在设置页。
7. **先保证不出问题，再谈功能**。模块只有脚本：不往 `/system` 挂载任何文件，不加载 SELinux 规则，不在 `post-fs-data` 阶段做任何事，因此模块本身不会导致开不了机。安装前检查、safe mode、崩溃循环保护，都排在功能之前。
8. **扩展只用标准格式，命令只在 Runner 里执行**。插件按 Agent Plugins 1.0 解析；第三方内容只在 `:ext` 进程里处理；所有命令只在 Runner 里执行。Hooks 是扩展点，不是安全边界，安全边界是 AgentOS 的风险策略和确认。`:agent` 里的 QuickJS 只运行打包进 APK 的 Pi Agent core，不执行插件或模型提供的任何代码。

---

## 4. 组件

| 组件 | 是什么 | 负责什么 |
|---|---|---|
| `agentos.zip` | Magisk / KernelSU 模块，只含脚本、两个 APK 和支持矩阵，不含任何原生二进制 | 安装前检查；开机后安装或升级 AgentOS App 和 Runner；root 监督进程负责开机拉起运行时、判活、退避拉起、崩溃循环时进入 safe mode、向 App 报告监督状态；在 root 管理器里显示运行状态 |
| AgentOS App · `:agent` 进程 | 运行时：Kotlin 宿主层 + Pi Agent core（QuickJS） | 宿主层：ACP Agent（官方 SDK 的 Agent 端，实现 AgentOS Profile）；对外 ACP 入口（Binder 服务、调用方授权、限额、会话隔离）；电脑端网关；Session / Event Store；调度与恢复；会话自动选择；Capability Broker；风险策略；确认的发起；Hook 触发点；Skill 目录注入；密钥存储；网络出口。Pi Agent core：模型调用、Agent 循环、工具调用校验与调度、上下文。有任务时以前台服务运行 |
| AgentOS App · 主进程 | 界面 | 自带前端（助理浮层、对话、快捷开关、悬浮球）；确认界面；设置（BYOK、用量、插件与第三方 MCP、Hook 审核、安全等级）；诊断；首次引导 |
| AgentOS App · `:ext` 进程 | Extension Host | 插件发现与导入、MCP 连接（Binder、Streamable HTTP）、Skills、Hooks 调度，见 [extensions.md](extensions.md) |
| Runner | 独立 APK，随 zip 安装，有自己的 UID，不申请任何权限 | 用 `/system/bin/sh` 执行 Hook 命令、shell 工具和 Skill 脚本；看不到 AgentOS App 的数据，也调用不了它的内部接口 |
| `acp-android` SDK | 给后装 App 用的库，基于官方 ACP Kotlin SDK（固定 0.30.1）加 Binder Transport | 发现 AgentOS、建立 Binder 通道、处理授权引导；对上提供标准 ACP 客户端接口 |
| `plugin-sdk` | 给 App 开发者用的库，基于官方 MCP Kotlin SDK | 在自己的 App 里实现 Binder MCP 服务；校验和打包 `assets/agent-plugin/` |
| 插件（Agent Plugin） | 已安装 App 内嵌的插件、用户导入的插件包、用户配置的第三方 MCP、AgentOS 自带插件 | 按 Agent Plugins 1.0 标准提供 Skills、MCP 工具和 Hooks；本地 App 的 MCP 服务运行在它自己的进程里 |

**用户的操作步骤**：在 root 管理器里刷入 `agentos.zip` → 重启 → 打开 AgentOS App 完成首次引导。

### 4.1 `:agent` 运行时的分层

按 Profile 对 Pi 接入的职责划分，`:agent` 分三层：

| 层 | 实现 | 负责 |
|---|---|---|
| Kotlin 宿主层 | `core/runtime/`（Kotlin/JVM）+ `app/.../agent/` | 对外 ACP（官方 SDK 的 Agent 端）；身份、会话隔离、授权与限额；Session / Event Store、调度、幂等与恢复；会话自动选择（Jev）；Capability Broker、风险策略、确认、Hook 触发点、Skill 目录；密钥；网络出口 |
| Pi 适配层 | `core/runtime/pi/`（Kotlin）+ `core/pi-runtime/src/`（JS 入口） | 把一轮 ACP prompt 交给 Pi 的 `Agent`；把 Pi 的事件转成内部事件，再由宿主层映射成 `session/update`；按会话保存和恢复 Pi 的 messages；把 Pi 的工具调用接到 Broker，把 `beforeToolCall` / `afterToolCall` 接到风险策略、确认和 Hooks；取消时调用 `abort` |
| Pi Runtime | 上游 `@earendil-works/pi-agent-core`，以及 `pi-ai` 的 `anthropic-messages`、`openai-completions` 两个协议族；用 esbuild 打成单文件放在 APK 的 assets 里，由 QuickJS 执行 | 模型调用的协议适配、Agent 循环、工具调用参数校验与调度、steering / abort、上下文与生命周期事件 |

规则：

- **JS 里不做 I/O**。Pi 发出的网络请求一律经宿主层提供的 `fetch`（底层是 OkHttp）；模型 key 由宿主层按目标 endpoint 注入请求头，JS 运行时里看不到 key。工具只有目录里的 MCP 工具和内置的 `read_skill`，执行一律回到 Kotlin 的 Broker。
- **模型调用走混合方案：协议适配用 `pi-ai`，网络和 key 留在 Kotlin。**
  - 只打包 `pi-ai` 的 `anthropic-messages` 和 `openai-completions` 两个协议族及其官方 SDK（`@anthropic-ai/sdk`、`openai`）；Google、Bedrock、Azure 等不进包。MiniMax 用 `pi-ai` 自带的 `minimax` / `minimax-cn` 预设（Anthropic 协议，MiniMax 官方推荐的路线）。用户也可以用 OpenAI 兼容路线接 MiniMax（`https://api.minimax.cn/v1`，B7 已实测），但 M2.x 的思考内容会以 `<think>…</think>` 混在正文里：在请求里带 `reasoning_split: true` 才会拆到 `reasoning_content` 字段，由 B8 通过模型的 compat 设置解决，响应侧另加开头 `<think>` 段的兜底解析。DeepSeek、Kimi、Qwen、智谱等国内厂商都落在这两个协议族上。
  - 调用 `pi-ai` 时一律传入宿主层的 `fetch`、占位 key 和 `maxRetries: 0`：真 key 在请求经过 `fetch` 时由 Kotlin 注入；重试、deadline、取消和“不重放”统一由宿主层负责，不让 SDK 或 `pi-ai` 自己重试。
  - 官方 SDK 和 `pi-ai` 里引用 Node 内置模块（`node:fs`、`node:child_process` 等）的文件，打包时用 esbuild 替换成空实现。
  - 退路：如果 S8 证明官方 SDK 在 QuickJS 里跑不通，就由宿主层实现这两个协议族，经自定义的 `streamFn` 推给 Pi；Agent 循环仍然是 Pi 的，模型目录仍然直接读 `pi-ai` 的数据文件，不自己维护。
- **ACP v1 生命周期**：一轮 `session/prompt` 要等 Pi 的 `agent_end` 之后才返回 `stopReason`。
- **工具轮次上限**：12 轮，由适配层计数；超过就 `abort`，并返回 `stopReason: max_turn_requests`。
- **会话与 Agent 实例**：每个活跃会话对应一个 Pi `Agent` 实例，全部由同一个 QuickJS 运行时承载，由常驻泵驱动（S8 结论，见决策记录）。以后需要隔离时，把会话分片到少数几个运行时，不做一会话一个。
- **版本**：Pi 还在 0.x，API 变化快。固定版本和 lockfile，升级要单独评估，并重跑 S8 和契约测试。

---

## 5. 协议

### 5.1 连接总览

先区分两个容易混淆的词：

| 术语 | 是什么 | 本项目在哪里用 |
|---|---|---|
| Android Binder | Android 的内核级 IPC 机制：跨进程方法调用，被调用方可以用 `Binder.getCallingUid()` 拿到内核认证的调用方 UID | 除电脑端和云端模型之外的全部连接 |
| `bindService()` | Android 框架 API：连接另一个 App（或本 App 另一个进程）导出的 Service，成功后拿到它的 `IBinder`，之后的调用都走 Binder | 第三方 App 连接 AgentOS 的 ACP 服务；Extension Host 连接本地 App 的 MCP 服务和 Runner；App 内部各进程互连 |

每一段连接用什么：

| 连接 | 机制 | 身份校验 | 验证项 |
|---|---|---|---|
| 自带界面（主进程）→ 运行时（`:agent`） | Binder：与第三方 App 相同的 ACP 服务（5.2、5.3） | 调用方 UID 等于本 App | S3 |
| 第三方 App → 运行时 | Binder：`IAcpService.open` 建立通道（5.2、5.3） | `Binder.getCallingUid()` + 首次授权 | S3 |
| 主进程 ↔ 运行时（设置、确认、诊断） | 不导出的 AIDL `IAgentControl`（5.4） | 只接受本 App 的 UID | — |
| 运行时 ↔ Extension Host（`:ext`） | 不导出的 AIDL `IExtensionHost`（5.4） | 只接受本 App 的 UID | — |
| Extension Host → 本地 App 的 MCP 服务 | Binder：`IMcpService.open` 建立通道（5.2，extensions.md 第 5 节） | Service 要求 `BIND_MCP_SERVICE`；消息到达时校验对方 App 的 UID | S4、S5 |
| Extension Host → 远端 MCP 服务 | HTTPS 上的 Streamable HTTP | TLS 证书校验；凭据用 Keystore 加密保存 | S5 |
| Extension Host ↔ Runner | Binder | Runner 的 Service 要求 `RUN_COMMANDS`，并校验调用方 UID | S6 |
| 电脑 → 运行时 | `adb forward` 到 `:agent` 的抽象 socket `agentos-acp`，按行分隔的 ACP JSON | 一次性配对码 | S3 |
| 运行时 → 云端模型 | HTTPS：Pi Agent core 发起，经宿主层的网络出口（OkHttp）发出，key 由宿主层注入 | TLS 证书校验 | S8 |
| root 监督进程 → App | 用 `am start-foreground-service` 拉起运行时；判活看进程（`pidof` + UID 校验），心跳文件（App 的 DE 存储）只提供“死的时候有没有任务”；用显式广播报告监督状态 | 广播接收器不导出，并要求 signature 级权限，其他 App 发不进来（root 不受权限检查限制） | S2 |

凡是和其他 Android App 打交道的连接都走 Binder；凡是要执行命令的地方都交给 Runner；root 监督进程只做进程管理，不和运行时交换业务数据。

### 5.2 Binder 消息通道（binder-channel-v1）

ACP 和 MCP 都是 JSON-RPC 消息流，所以两种协议共用同一种 Binder 通道。通道的完整说明（含 AIDL、流控、关闭规则）在 [`core/protocol/binder-channel-v1.md`](../core/protocol/binder-channel-v1.md)，目前是 S3 定参后的草案，W5 在真机结果补齐后冻结。

```aidl
package org.agentos.channel;

oneway interface IChannel {               // 每一端各实现一个，用来接收对方的消息
    void send(String message);            // 一条完整的 JSON-RPC 消息
    void close(String reason);
    void ack(long consumed);              // 流控回执：已处理完对方发来的前 consumed 条（累计值）
}

interface IAcpService {                   // AgentOS App 导出，运行在 :agent 进程
    IChannel open(IChannel client);       // 传入客户端的接收端，返回 Agent 的接收端
}

interface IMcpService {                   // 提供插件的 App 导出，要求 BIND_MCP_SERVICE
    IChannel open(IChannel client);       // 传入 AgentOS 的接收端，返回 MCP 服务的接收端
}
```

规则（参数由 S3 在 API 35 / 36 / 37 模拟器上测定）：

- 每次 `send` 就是一条完整的 JSON-RPC 消息，语义等同 stdio 下的一行。同一个 `IChannel` 上的 oneway 调用按发送顺序到达。方法顺序决定事务号，冻结后只能在末尾追加。
- 单条消息上限 65,536 字符（按 `String.length` 即 UTF-16 计，约 128 KiB）。图片等大内容用 `resource_link` 传 `content://` URI，并临时授予读权限。
- 背压：发送方的在途消息不超过 32 条且 32,768 字符，接收方处理后回累计 `ack`，超过就等待，避免把接收进程的 Binder 异步缓冲（约 508 KiB，所有调用方共享）耗尽。
- 对端缓冲满时，oneway 调用同样抛 `DeadObjectException`，但对端其实还活着；所以 `close` 和 `ack` 失败时要退避重试，判断死亡只看 `linkToDeath` 或 `isBinderAlive()`。
- 接收方在 `send` 里用 `Binder.getCallingUid()` 校验对方 UID 与 `open` 时一致，不一致就关闭通道。
- 双方对对方的 Binder 调用 `linkToDeath`；任何一端进程死亡，另一端关闭通道并释放协程。
- 通道只承载消息，不解析内容。ACP 和 MCP 的语义完全由各自的 SDK 处理。

### 5.3 对外协议：ACP

ACP 的服务端（Agent）是 `:agent` 进程里的运行时，所有前端都是 ACP 客户端（Client）。协议基线、扩展规则、生命周期约束都以 [AgentOS ACP Profile v1](../../agentos-acp-profile-v1.md) 为准；Profile 中的 `sideagentd` 在本项目里对应 `:agent` 运行时。Profile 在 W4 时迁入本仓库的 `core/protocol/`。下面只写 root + zip 形态下的具体落地方式。

**传输绑定**

| 客户端 | 传输 | 身份来源 |
|---|---|---|
| AgentOS App 自带界面 | Binder 通道，与第三方 App 相同 | 本 App 的 UID，视为用户的控制中心 |
| 后装的第三方 App | Binder 通道 | Binder 调用方 UID |
| 电脑上的 ACP 客户端 | `adb forward` 到 `:agent` 的抽象 socket `agentos-acp`；另提供一个 stdio 桥接命令，供以子进程方式启动 Agent 的客户端使用 | 一次性配对码 |

**两端的实现**：

| 位置 | 实现 |
|---|---|
| 客户端（`acp-android`、自带界面） | 官方 Kotlin SDK 的 Client + `BinderAcpTransport` |
| Agent 端（`:agent`） | 官方 Kotlin SDK 的 Agent 端 + 同一个 `BinderAcpTransport`；每条通道绑定一个可信 UID，交给运行时 |
| 电脑端网关（`:agent`） | 同一个 Agent 端，Transport 按行收发 socket 上的 JSON；基于 `JsonRpcCodec` 编码，不直接用 SDK 的 `StdioTransport`（它输出的每条消息多一个 `"type"` 字段），并限制单行长度 |

`BinderAcpTransport` 实现 SDK 0.30.x 的 Transport 接口：Binder 入站的字符串 → `JsonRpcMessage` → `onMessage`；`send(JsonRpcMessage)` → 按具体类型做 JSON 编码（`JsonRpcCodec`）→ `IChannel.send(String)`；负责 start、close、error 和背压（`awaitWritable`，流式 `session/update` 的生产者每发一条前等待）；不在主线程阻塞；对端进程死亡时关闭协程和通道。SDK 的 `Protocol` 要随 Transport 一起关闭（`BinderAcpTransport.bindTo`），否则通道断开后挂起的请求不会结束。SDK 自带的 `StdioTransport` 只用于电脑上的测试。

**方法启用范围**

| ACP 能力 | 状态 | 说明 |
|---|---|---|
| `initialize` | M1 | 声明 AgentOS Profile 版本和已启用的扩展 |
| `session/new` | M1 | `cwd` 仅作标签；`mcpServers` 非空时按 Profile 明确返回“不支持” |
| `session/prompt` | M1 | 本轮结束才返回 `stopReason`，不提前返回入队回执 |
| `session/update` | M1 | 见 5.6 的事件映射 |
| `session/cancel` | M1 | — |
| Profile 扩展：自动选择会话 | M1 | 用户不指定会话时，由 Jev 在**调用方自己的**会话里选择，或新建一个。W4 已冻结：客户端在 `session/new` 的 `_meta."org.agentos".autoSelect` 里带上问题，选中结果经随后的 `session_info_update` 告知（`core/protocol/acp-extensions.schema.json`）。没有做成单独的方法，因为 SDK 只在 `session/new` 里把会话登记到连接上 |
| `session/load` | M2 | 只能加载调用方自己的会话 |
| Profile 扩展：持久化提交、增量恢复 | M2 | 按 Profile 的要求，由客户端和服务端协商后启用；方法名和字段在 W10 冻结 |
| `session/request_permission` | M3a | 见下方“权限规则” |
| 客户端的文件系统、终端能力 | 不启用 | Android 上没有对应的工作目录语义 |

**第三方 App 的开放时间**：M1 起 ACP 服务已经导出，但只接受 AgentOS App 自己和已配对的电脑端；第三方 App 的通道在 M4（授权、限额、会话隔离就绪后）才放开，之前一律返回“未开放”。

**会话隔离**：会话归属于创建它的调用方 UID。第三方 App 只能列出、加载、续写、被自动选中自己的会话。AgentOS App 作为用户的控制中心，可以查看所有会话，用于管理和审计。

**权限规则**：
- 需要确认的工具调用，一律由 AgentOS 的确认界面向用户询问，调用方 App 不能替用户同意。
- 如果调用方在 `initialize` 时声明支持权限请求，运行时也会向它发 `session/request_permission`，但它的回答只能追加拒绝，不能代替 AgentOS 的确认。
- 这与 Profile 中“客户端的授权结果仍受服务端权限策略约束”一致。

**第三方 App 的授权与限额**：
- 首次调用时，AgentOS 弹窗询问“是否允许 X 使用 AgentOS”，结果可以在设置页撤销；
- 同一个 App 被拒绝后，短时间内不再弹窗，防止骚扰；
- 每个 App 有并发、频率和用量上限，用量在设置页可见。第三方 App 消耗的是用户自己的模型额度。

### 5.4 App 内部接口

三个进程之间用不导出（`android:exported="false"`）的 AIDL 服务通信，服务端校验调用方 UID 等于本 App。AIDL 放在 `app/src/main/aidl/org/agentos/internal/`，随 App 一起升级，不作为对外契约。

| 接口 | 服务端 | 调用方 | 用途 |
|---|---|---|---|
| `IAgentControl` | `:agent` | 主进程 | BYOK 配置、设置、会话管理与审计、诊断信息、监督状态；注册确认界面回调，提交确认结果。v1（W6）：版本、运行状态、诊断、监督状态，返回 JSON；v2（W6）末尾追加 BYOK：`getModelPresets(providerId)`（不传参数返回厂商列表，传厂商返回其模型，控制 Binder 返回值大小）、`getModelSource()`、`setModelSource(sourceJson, apiKey)`、`clearModelSource()`；之后的确认、电脑端接入按版本继续在末尾追加 |
| `IExtensionHost` | `:ext` | `:agent` | 取工具与 Skill 目录并订阅变化；工具调用与取消；读取 Skill；触发 Hook 事件并取回合并后的决定 |
| `IExtensionCallback` | `:agent` | `:ext` | 目录变化、工具结果、连接状态 |

`:agent` 用 `BIND_AUTO_CREATE` 绑定 `:ext`，所以只要运行时活着，`:ext` 被杀后由系统自动重建。主进程只在界面打开时绑定 `:agent`。

### 5.5 扩展：Agent Plugins、MCP、Skills、Hooks

完整设计见 [extensions.md](extensions.md)，这里只列要点：

- **格式**：Agent Plugins 1.0 标准插件，即根 `plugin.json` + `skills/` + `mcp.json`；Hooks 按 OpenAI 的方式读取（`extensions."com.openai".hooks`，没写时读默认的 `hooks/hooks.json`）。
- **来源**：已安装 App 内嵌的插件（`assets/agent-plugin/`）、用户导入的插件包、用户配置的第三方 MCP、AgentOS 自带插件。
- **MCP**：
  - 本地 App 的 MCP 服务在 `plugin.json` 的 `extensions."org.agentos".mcpServers` 里声明，AgentOS 用 `bindService()` 连接，经 `binder-channel-v1` 收发 MCP 消息；
  - 远端第三方服务在 `mcp.json` 里写 `"type": "streamable-http"`，只允许 `https://`；
  - `stdio` 和 `sse` 条目标为不支持，所以不需要为插件在手机上准备任何解释器。
- **运行时不直接连接任何插件**：工具调用经 `IExtensionHost` 交给 Extension Host，再由它通过 Binder 或 HTTPS 调用 MCP 服务。
- **Hooks**：只支持命令型，在 Runner 里用 `/system/bin/sh` 执行；首次启用要逐条审核，按内容哈希记录信任。Hook 可以拒绝、要求确认、改写输入、补充上下文，但不能替用户同意。
- **安全**：
  - 本地 MCP 服务要求 `BIND_MCP_SERVICE`，消息到达时校验对方 App 的 UID；
  - MCP 工具默认按“写”处理，服务端注解只能把风险等级调高；
  - 工具描述、返回结果和 Skill 内容一律作为不可信输入交给模型。

### 5.6 内部事件与 ACP 更新的映射

内部事件就是 Pi Agent core 的生命周期事件（`agent_start`、`turn_start`、`message_*`、`tool_execution_*`、`turn_end`、`agent_end`），由适配层补上会话和任务标识后写入事件日志；宿主层自己的事件（任务、确认、Hook、监督）与它们放在同一个日志里。

| 内部事件 | 对客户端表现为 |
|---|---|
| `message_start` / `message_update` / `message_end` | `session/update` 的 `agent_message_chunk`；其中的 thinking 内容映射为 `agent_thought_chunk` |
| `tool_execution_start` | `session/update` 的 `tool_call`（pending） |
| `tool.dispatched`（宿主层已把调用交给工具提供方） | `session/update` 的 `tool_call_update`（in_progress） |
| `tool_execution_update` / `tool_execution_end` | `session/update` 的 `tool_call_update`（in_progress / completed / failed） |
| `agent_end` | `session/prompt` 返回 `stopReason: end_turn` |
| `task.cancelled` | `session/prompt` 返回 `stopReason: cancelled` |
| 工具轮次超过上限（`tool_round_limit`） | `session/prompt` 返回 `stopReason: max_turn_requests` |
| `task.failed`，以及结果未知而暂停的任务 | `session/prompt` 返回 JSON-RPC 错误，统一为 `-32051`，具体原因在 `data.agentosCode`（见 `core/contracts/errors.md`） |
| `session.selected` | 仅对协商了“自动选择会话”扩展的客户端，经 `session_info_update` 告知选中的会话 |
| `consent.*`、`hook.*`、`supervisor.*`、扩展的连接状态 | 不对外暴露，只写入事件日志，并在 AgentOS App 的诊断页展示 |

---

## 6. 功能实现过程

### F1 安装

| 步骤 | 执行者 | 做什么 |
|---|---|---|
| 1 | 用户 | 在 Magisk / KernelSU 管理器里刷入 `agentos.zip` |
| 2 | `customize.sh` | API 不在 35–37、root 管理器版本不满足就**拒绝安装**；fingerprint 不在支持矩阵里，允许安装但标记为“未验证设备”；另外检查剩余空间和冲突模块 |
| 3 | `customize.sh` | 写入模块版本和支持矩阵版本 |
| 4 | 用户 | 重启 |

zip 里没有原生二进制，所以不按 ABI 区分；支持矩阵只列出已经验证过的设备。

### F2 开机与首次配置

1. **`service.sh`**（root 监督进程）：
   - 等待 `sys.boot_completed=1`；
   - 如果存在 safe mode 标记，或上次开机后运行时连续崩溃被判为崩溃循环，就进入 safe mode：不拉起运行时，只报告状态；
   - AgentOS App 或 Runner 没装，或版本低于 zip 里的版本，就执行 `pm install` 安装或升级；签名不符则停止，并在 root 管理器里显示原因（安装不必等用户解锁，规则见 [spikes/S1.md](spikes/S1.md)）；
   - 再检查一次 API 和 fingerprint，OTA 后版本超出支持范围就进入 safe mode；
   - 等用户 0 解锁（`sys.user.0.ce_available=true`，并兜底查询 `am get-started-user-state 0`）：App 不是 directBootAware，解锁前系统不会启动它的组件；
   - App 从没打开过，或被用户强行停止（包处于 stopped 状态）时，开机不拉起运行时，状态报 `stopped / not_launched` 或 `user_stopped`，等用户打开 App 后再由监督进程守护；
   - 用 `am start-foreground-service` 拉起 `:agent` 的 `AgentService` 一次（原因为 `boot`；模块刚升级了 App 时为 `upgrade`），让运行时执行恢复流程（F8），然后进入判活循环；
   - 向 App 发一条显式广播，报告监督状态（正常、safe mode 及原因）。
2. **`:agent` 运行时**：
   - 打开 Store，执行恢复流程（F8）；有未完成的任务时进入前台，没有就退出前台，进程交给系统管理；
   - 把运行状态（是否有进行中的任务、是否在前台、上次错误）写入 App DE 存储里的心跳文件。监督进程判活看进程是否存在，心跳只用来判断“死的时候有没有任务”（契约见 [spikes/S2.md](spikes/S2.md)）。
3. **用户首次打开 App**：查看安全等级和监督状态 → 配置模型和 key（F9）→ 允许通知（前台服务和确认通知需要）→ 跳转系统设置选择默认助理 → 通过系统弹窗请求忽略电池优化 → 查看已发现的插件。

### F3 扩展的发现、导入与启用

详见 [extensions.md](extensions.md) 第 10 节（E1–E3）。概括：

1. `:ext` 进程启动，或收到安装、升级、卸载广播时，查找声明了 `org.agentos.intent.action.PLUGIN` 的 Service，读取对方 APK 里的 `assets/agent-plugin/`，按 Agent Plugins 1.0 校验。Manifest 里要声明对应的 `<queries>`，否则 Android 11 及以上看不到其他包。**这一步不 bind**。
2. 用户也可以导入插件包（zip），或在设置页添加第三方 MCP 服务器（`https://`，Streamable HTTP）。
3. 默认状态：AgentOS 自带插件默认启用（shell 除外）；其他插件默认关闭，由用户启用；含 Hooks 的插件要先逐条审核。
4. 启用后，Extension Host 生成新的工具和 Skill 目录，通过 `IExtensionCallback` 通知运行时。
5. 禁用、卸载、签名变化时：立即从目录移除，关闭连接，取消进行中的调用；Hook 内容变化时回到待审核。

### F4 一次带工具调用的完整请求

完整时序见 [assets/request-flow.svg](assets/request-flow.svg)。场景：后装的 App 通过 ACP 调用 Agent，工具由一个内嵌了插件的日历 App 提供。AgentOS App 自带界面走同一套流程，只是不需要首次授权。

| # | 执行者 | 接口 |
|---|---|---|
| ①② | 调用方 App → `:agent` | `bindService` → `IAcpService.open`；首次调用由 AgentOS 弹窗授权 |
| ③④ | 同上 | `initialize`，运行时声明 Profile 扩展 |
| ⑤⑥ | 同上 | `session/new`，返回的 sessionId 归属调用方 UID（也可以用自动选择会话扩展） |
| ⑦ | 同上 | `session/prompt`，这一轮结束才返回；运行时进入前台，适配层把输入交给该会话的 Pi `Agent` |
| ⑧⑨ | `:agent`（Pi Agent core）→ 模型 | Pi 经宿主层的网络出口发送 messages 和 tools，模型返回 `tool_use` |
| ⑩ | `:agent` → 调用方 | Pi 发出 `tool_execution_start` → `session/update`：`tool_call`（pending） |
| （可选） | `:agent` ↔ `:ext` ↔ Runner | Pi 的 `beforeToolCall` 触发已信任的 `PreToolUse` Hook：`IExtensionHost` → Runner 执行 → 返回合并后的决定；只能拒绝、要求确认、改写输入或补充上下文，不能跳过确认 |
| ⑪–⑬ | `:agent` → 用户 | 风险策略判定为写操作 → App 在前台时弹出确认界面，否则发带“允许 / 拒绝”按钮的通知 → 用户同意 |
| ⑭–⑰ | `:agent` → `:ext` → 日历 App | `IExtensionHost` 发起工具调用 → 还没连接就 `bindService()` → `IMcpService.open` → MCP 初始化 → `tools/call` → 结果经通道回传 → `IExtensionCallback` 返回结果 |
| ⑱–⑳ | `:agent` → 调用方 | `tool_call_update`（completed）→ `tool_result` 交回模型 → `agent_message_chunk` 流式输出 |
| ㉑ | `:agent` → 调用方 | Pi 发出 `agent_end` → `session/prompt` 返回 `stopReason: end_turn`；没有其他任务时运行时退出前台 |
| ㉒ | `:ext` → 日历 App | 空闲 30 秒后关闭 MCP 通道并 unbind |

### F5 高风险操作确认

| 等级 | 例子 | 行为 |
|---|---|---|
| 读 | 查日历、读通知 | 直接执行 |
| 写 | 建日程、发消息、分享 | 每次确认，可选“本会话内不再询问” |
| 高风险 | Shell、无障碍点击、支付类 | 每次确认，不提供“记住”；对应工具默认关闭 |

MCP 工具默认按“写”处理，服务端注解只能把等级调高；用户可以把读、写级的工具设为“始终允许”，高风险工具不行。Hook 返回的 allow 不能跳过这里的确认（见 [extensions.md](extensions.md) 第 5.4、7.3 节）。

确认由 `:agent` 发起：App 在前台时弹出确认界面，不在前台时发带“允许 / 拒绝”按钮的通知。60 秒没有响应视为拒绝。

拒绝后，结果作为 `is_error` 的 `tool_result` 交回模型，这一轮不会因此失败。确认界面会写明是哪个调用方 App 发起的请求。所有确认结果都写入事件日志。

### F6 取消

客户端发 `session/cancel` 后，运行时按任务当前所处的阶段处理：

- 排队中：直接移出队列；
- 等模型返回：调用该会话 Pi `Agent` 的 `abort`，宿主层关闭对应的 HTTPS 连接；
- 工具调用中：`abort` 之外，再经 `IExtensionHost` 取消，由 Extension Host 转成 MCP 的取消通知，尽力取消。如果对方已经完成了副作用，照实上报。

这一轮的 `session/prompt` 返回 `stopReason: cancelled`。`session/cancel` 会等运行中的任务真正停下再返回这一结果，最多等 12 秒。

### F7 断开与恢复

- **断开连接不等于关闭会话**（Profile 规定）。调用方 App 被杀或主动断开，任务照常跑完，结果保留；运行时在有任务期间保持前台。
- 调用方回来后有两种方式接回：
  - 用 `session/load` 重放历史；
  - 如果协商了增量恢复扩展，就从上次读到的位置接着读。
- 通道断开时，挂起的权限请求改由 AgentOS 的确认界面处理，或按拒绝处理。
- 运行时进程被杀后，调用方的通道随之关闭；调用方重新 bind 时，系统会拉起 `:agent`，再按上面两种方式接回。

### F8 故障与恢复

| 故障 | 处理 |
|---|---|
| 运行时（`:agent`）崩溃或被杀 | 有未完成的任务时，监督进程按 1s → 2s → 4s … 最长 60s 退避拉起（运行时连续存活 60 秒后退避复位）；10 分钟内有任务时异常退出（崩溃、被杀或拉起失败）5 次，就进入 safe mode 并记录原因。没有任务时不主动拉起，下一个调用方 bind 时由系统拉起。重启后，已入队但未开始的任务重新排队；已开始但没有结束的任务标记为需要恢复，**不自动重放**；各会话的 Pi `Agent` 按 Store 里保存的 messages 重建 |
| Extension Host（`:ext`）被杀 | 系统按绑定关系自动重建。已经发出、没有回执的工具调用标记为“结果未知”；需要工具的步骤先等待重连，最多等到任务的 deadline |
| 本地 App 的 MCP 服务进程死亡 | Extension Host 经 `linkToDeath` 感知；进行中的调用标记为“结果未知”，下次用到时重新 bind |
| 主进程被杀 | 不影响运行时；确认改走通知 |
| 监督进程退出 | 运行时照常工作，但失去开机拉起和崩溃后拉起的能力，下次开机恢复；诊断页显示这个状态 |
| 断网 | 宿主层的网络出口把错误分为可重试和不可重试；可重试的按退避重试，直到任务的 deadline；已经执行过的工具调用不会重复执行 |
| OTA 升级 | 开机时重新检查 API 和 fingerprint，超出支持范围就进入 safe mode，并提示用户安装匹配版本的模块 |

**需要恢复的任务的过渡期限（整合人 2026-09-29 决定，W10 之前）**：“重试或放弃”的界面要到 W10 才有，M1 又没有 `session/load`，所以标记为需要恢复的任务没有人处理，每次 `:agent` 被杀都会多出几条。C4 的设备用例连跑 20 次后，`recoveryPending` 从 4 涨到 42。过渡规则如下：

- 运行时每次启动时，把需要恢复满 24 小时的任务按“放弃”结束：发出 `task.recovery_resolved`（决定为放弃，原因 `recovery_expired`），再发出终态事件。
- 需要恢复的任务最多保留 50 条，超出的从最旧的开始按同样方式放弃。
- 被放弃的任务不重放，结果未知的副作用照旧不重放。
- W10 用户可以选择之后，保留这两条规则作为兜底。
- 已实现（A7）：
  - 需要恢复的起始时间取最近一次 `task.recovery_required` 事件的时间，不改 Store 的 schema。
  - 过期结束的任务错误码为 `recovery_expired`（-32051），`runtime.recovered` 里新增 `expired` 计数。
  - 期限和上限都是 `SchedulerConfig` 的参数。

### F9 自带模型 key（BYOK）

1. 用户在设置页选择模型来源：
   - **厂商预设**：列表来自 `pi-ai` 的模型目录，MiniMax（国际 / 国内）排在最前。用户只需填写 key，endpoint、协议和模型参数（上下文长度、最大输出、是否支持推理）都已预置。
   - **自定义兼容端点**：填写 URL、协议（Anthropic Messages 或 OpenAI Chat Completions）、模型名和 key，适用于自建网关和预设里没有的厂商。URL 必须是 `https://`；唯一的例外是回环地址（`127.0.0.1`、`localhost`、`::1`）允许 `http://`，用于手机上本地运行的模型服务（整合人 2026-09-29 决定，release 包同样生效）。
   首版不支持用订阅账号登录（Claude Pro/Max、ChatGPT、Copilot 等 OAuth）。
2. 主进程经 `IAgentControl`（v2 的 `setModelSource` 等）交给运行时。宿主层用 Android Keystore 里的 AES-256-GCM 主密钥（不要求用户认证）加密 key，与模型来源一起写入 `:agent` 的 CE 私有目录（`files/byok/model-source.json`，一次原子写入）。key 绑定端点：厂商预设绑定该厂商的全部 baseUrl，自定义端点只绑定它自己的 baseUrl，加密时把 baseUrl 作为附加认证数据，所以换端点必须重新输入 key。
   - **更换**：热加载，下一次模型请求生效，不打断正在运行的这一轮。
   - **清除**：立即作废（整合人 2026-09-29 决定）：删除文件和 Keystore 主密钥，之后的模型请求（包括同一轮里的下一次）一律拿不到 key，以 `model_not_configured` 结束，不再用旧 key 跑完这一轮（C3.1 已实现）。正在传输的那一次 HTTP 响应也要中止，分三处实现，不阻塞 M1：
     - `SecretPort` 追加 `revocations: Flow<Credential>`，默认空流（A，已完成）。清除时发出被撤销的 Credential，按对象身份比较；更换不发。
     - HostFetch 在构造时订阅撤销流，按对象身份找到携带该 Credential 的在途调用，等响应头和读流两个阶段都会中止，抛 `NetErrorKind.KEY_REVOKED`，不重试（B5，已完成）。它还记下最近撤销的 32 个 Credential，所以“刚取到 key、还没登记请求”这几毫秒里撤销的，请求也不会发出去。
     - `KeystoreSecrets.revoke()` 发出撤销信号（C5，已完成）。设备实测（API 35 / 36 / 37 × debug / releaseTest，66/66）：从调用清除到这一轮结束 10–135 ms（只有第一层时约 930 ms），假端点看到在途连接被断开。发出的必须是 `credentialFor` 当初返回的那个对象，包括换下来但还留在内存里的旧 key。
     被中止的这一轮同样以 `model_not_configured` 结束，`details.reason=key_revoked`（整合人 2026-09-29 决定，不归入 `model_auth_failed`）。
   - 错误以 `agentos.byok.<code>` 返回，错误消息里不含 key。
3. key 只在 Kotlin 宿主层里使用：Pi 发出的模型请求经过宿主层的 `fetch` 时才注入请求头，QuickJS 里的 Pi Agent core 看不到 key。
4. key 不会出现在事件、快照、日志、诊断输出和模型输入里。界面上只显示首尾各 4 位。

### F10 后装的 App 通过 ACP 调用 Agent

1. 开发者在 App 里引入 `acp-android`，并在 Manifest 的 `<queries>` 里声明 AgentOS 的 ACP intent action（`org.agentos.intent.action.ACP`）。不需要声明任何权限，所以 App 和 AgentOS 谁先安装都不影响。
2. SDK 找到 AgentOS，bind 上它的 ACP 服务，打开通道。运行时没在运行时，由系统按 bind 拉起。AgentOS 没装时，SDK 返回明确的“未安装”状态，由 App 自己决定如何提示用户。
3. 运行时取 Binder 调用方 UID。首次调用时弹窗授权，结果记录下来，可以在设置页撤销。M1 只接受 AgentOS App 自己，其他调用方在 `open` 时被拒，原因码 `agentos.acp.not_open`（W25 起改为授权流程）。
4. 之后就是标准 ACP：`initialize` → `session/new` → `session/prompt` → 接收 `session/update`。会话隔离、确认规则、限额按 5.3 执行。
5. 调用方所在的 App 在后台也可以调用；它需要一直保持绑定，才能收到流式更新。

### F11 电脑端通过 ACP 接入

1. 用户在设置页开启“电脑端接入”，页面上显示一次性配对码。
2. 电脑执行 `adb forward tcp:8765 localabstract:agentos-acp`；也可以直接用项目提供的 stdio 桥接命令，它会自动完成转发。
3. 客户端第一次连接时先提交配对码。之后按标准 ACP 使用；电脑端作为一个独立的调用方，有自己的会话空间。手机上的确认照常出现，电脑端无法绕过。
4. **开关打开期间，`:agent` 以前台服务运行**（`specialUse`），并显示常驻通知“电脑端接入已开启”，通知上带“关闭”按钮（整合人 2026-09-29 决定）。
   - 原因：`:agent` 空闲时（没有任务，也没有被 Binder 绑定）是 cached 进程，大约 10 秒后会被系统的 cached-apps freezer 冻结。抽象 socket 上的连接不会解冻进程，所以电脑端的新连接和已建立会话的请求都得不到响应（A6 在 API 36 模拟器上复现）。
   - 关闭开关后退出前台，回到“有任务才前台”。
   - 长时间没有电脑端连接时是否自动关闭开关，留到 W11 再定。

### F12 升级

- **模块升级**：在 root 管理器里刷新版的 zip，重启后监督进程自动升级 AgentOS App 和 Runner。
- **App 升级**：升级会结束 App 的所有进程；运行时重启后执行恢复流程（F8）。
- **数据迁移**：Store 里记录 schema 版本。运行时启动时只做向前迁移；迁移前先备份，失败就回滚，并停在 safe mode 等用户处理。
- **ACP 协议升级**：`initialize` 时按 Profile 协商 ACP 和 Profile 的版本，老客户端继续按原版本工作。
- **插件升级**：App 或插件包升级后重新校验；签名或 Hook 内容有变化的，回到待审核（见 [extensions.md](extensions.md) E6）。

### F13 禁用、卸载与 safe mode

- **禁用模块**：在 root 管理器里关掉模块后，监督进程 5 秒内退出，并广播 `stopped` / `module_disabled`，不用等到下次开机。AgentOS App 仍是一个普通 App，可以按需被调用，但失去开机拉起和崩溃后拉起；App 显示“监督进程未运行”。
- **卸载模块**：`uninstall.sh` 停止监督进程，删除 `/data/adb/agentos/` 下的监督状态。AgentOS App 和 Runner 不会被自动卸载，用户可以像普通 App 一样删除它们，数据随 App 一起删除。
- **safe mode**：可以在 root 管理器里用模块的“动作”按钮手动切换；出现崩溃循环或版本超出支持范围时会自动进入。safe mode 下监督进程不拉起运行时，运行时也不自动继续恢复出来的任务，App 显示原因和退出方式。模块本身只有脚本，不会导致开不了机。
