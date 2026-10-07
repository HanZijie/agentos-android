# AgentOS 扩展模块：Extension Host

> **一句话**：AgentOS 用标准的 **Agent Plugins 1.0** 格式接入插件，包括 Skills、MCP 和 Hooks。本地 App 的 MCP 服务通过 **Binder** 提供；远端第三方 MCP 只支持 **Streamable HTTP**（`https://`）；不支持 `stdio`，所以不需要为插件在手机上准备任何解释器。负责这一切的模块叫 **Extension Host**，运行在 AgentOS App 的独立进程 `:ext` 里。所有命令（Hooks、shell 工具、Skill 脚本）只在独立 UID 的 **Runner** 里执行。

相关文档：[architecture.md](architecture.md)（整体架构）、[implementation-plan.md](implementation-plan.md)（目录、验证项、依赖顺序、里程碑）。

---

## 1. 范围

**支持**

| 项目 | 内容 |
|---|---|
| 插件格式 | Agent Plugins 1.0：根目录 `plugin.json` + `skills/` + `mcp.json`；Hooks 按 OpenAI 的扩展方式读取；本地 Binder MCP 端点写在 `extensions."org.agentos"` 里 |
| 插件来源 | ① 已安装 App 内嵌的插件；② 用户导入的插件包（zip）；③ 用户手动配置的第三方 MCP 服务器；④ AgentOS 自带插件 |
| MCP 传输 | 本地 App：Binder（`binder-channel-v1`）；远端：`streamable-http`，只允许 `https://` |
| MCP 能力 | tools |
| Skills | 标准 `SKILL.md`，按需加载 |
| Hooks | 只支持 `command` 类型，用 Android 自带的 `/system/bin/sh` 执行 |

**首版不支持**。导入时明确标出，不静默忽略；插件里其余可用的部分照常工作。

| 项目 | 原因或去向 |
|---|---|
| `stdio` MCP | 需要在手机上启动子进程和解释器（node、python 等），不做 |
| `sse` MCP（旧的 HTTP+SSE 传输） | MCP 从 2025-03-26 修订版起已弃用这种传输，只支持它的旧服务连不上 |
| Hook 的 `mcp_tool`、`prompt`、`agent` 类型 | 只做命令型 Hook，其他类型跳过 |
| `.codex-plugin/plugin.json`、Claude 等旧清单 | 只认标准的根 `plugin.json` |
| `extensions."com.openai".apps`（ChatGPT 连接器） | 与手机无关，忽略 |
| MCP 的 resources、prompts，以及服务端发起的 sampling、elicitation | 首版只接 tools，其余放到 M6 |
| 需要 OAuth 的远端 MCP | 首版只支持在请求头里填写凭据 |
| ACP 客户端在 `session/new` 里带的 `mcpServers` | 保持原决定：返回“不支持” |

---

## 2. 放在哪一层

```text
:agent 运行时（AgentOS App 的进程，App UID：Kotlin 宿主层 + QuickJS 里的 Pi Agent core）
   │  工具目录、Skill 目录、Hook 决定，都由宿主层向 Extension Host 要；Pi 只看到宿主层交给它的工具
   │  IExtensionHost / IExtensionCallback（不导出的 AIDL）
Extension Host（AgentOS App 的 :ext 进程，App UID，Kotlin）
   ├── Binder ──▶ 本地 App 的 MCP 服务（binder-channel-v1；运行在各 App 自己的进程里，生命周期由 Android 管理）
   ├── HTTPS ───▶ 远端 Streamable HTTP MCP 服务
   └── Binder ──▶ Runner（独立 UID）──▶ /system/bin/sh：Hook 命令、shell 工具、Skill 脚本
```

**放在 AgentOS App 的独立进程 `:ext`**，原因有三条：

1. **需要 Android 框架能力。** 扫描已安装的 App、`bindService()`、读取其他 App 的 assets、用 Keystore 加密凭据，这些都要在 App 进程里做。
2. **和运行时隔离。** 它要解析第三方插件包和远端流，同时维持多条 Binder 连接。放在独立进程里，崩溃不会拖垮运行时和界面，内存也能单独回收。运行时用 `BIND_AUTO_CREATE` 绑定它，被杀后由系统自动重建。
3. **命令不能以 AgentOS App 的身份执行。** AgentOS App 的 UID 能读到运行时的数据、使用 Keystore 里的密钥、绑定 App 的内部服务。如果 Hook 命令以这个 UID 运行，它就拥有这一切。所以命令一律交给独立 UID 的 Runner。

---

## 3. 插件格式（Agent Plugins 1.0）

### 3.1 目录结构

```text
my-plugin/
├── plugin.json             # 必需
├── skills/<名字>/SKILL.md   # 可选，可带 scripts/、references/
├── mcp.json                # 可选：远端 MCP 服务
├── hooks/hooks.json        # 可选，OpenAI 扩展的默认位置
└── assets/                 # 可选：图标等
```

规则：

- 只认根目录的 `plugin.json`。`$schema` 和 `name` 必填：
  - `$schema` 固定为 `https://agent-plugins.org/schemas/1.0.0/plugin.schema.json`；
  - `name` 最长 64 个字符，只能用小写字母、数字、`.`、`-`，首尾必须是字母或数字，不能出现 `--` 或 `..`（以固定的 schema 为准，`ManifestReader` 的测试逐项核对）。
- Skills 固定放在 `skills/`，MCP 固定放在 `mcp.json`，这是 Agent Plugins 的可移植约定。
- 客户端专属的内容放在 `extensions` 下，按反向域名分区。规范不规定分区里的内容：
  - `extensions."com.openai"`：读取 `hooks` 和 `interface`（显示名、图标、简介，用于插件页展示），忽略 `apps`；
  - `extensions."org.agentos"`：AgentOS 的分区，首版只定义 `mcpServers`，用来声明本地 App 的 Binder MCP 服务（3.2 节）。
- 校验时使用 `core/protocol/agent-plugins-1.0/` 里固定的 schema 副本，运行时不联网拉取 schema。

示例（一个内嵌在笔记 App 里的插件）：

```json
{
  "$schema": "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json",
  "name": "notes",
  "version": "1.2.0",
  "description": "读写本机笔记",
  "extensions": {
    "com.openai": {
      "hooks": "./hooks/hooks.json",
      "interface": { "displayName": "笔记", "composerIcon": "./assets/icon.png" }
    },
    "org.agentos": {
      "mcpServers": {
        "notes": { "service": "com.example.notes.agent.NotesMcpService" }
      }
    }
  }
}
```

### 3.2 MCP 服务的声明

两个位置，各管一种传输：

| 写在哪里 | 形式 | 允许出现在 | 含义 |
|---|---|---|---|
| `plugin.json` → `extensions."org.agentos".mcpServers.<名字>` | `{ "service": "<Service 完整类名>" }` | 只能在 App 内嵌的插件里；Service 必须属于这个 App 自己 | 用 Binder 连接该 App 导出的 MCP 服务（5.1 节） |
| `mcp.json` → `mcpServers.<名字>` | `{ "type": "streamable-http", "url": "https://…", "headers": {…} }` | 所有来源 | 远端 Streamable HTTP 服务（5.2 节） |
| `mcp.json` 里的 `stdio`、`sse` 条目，以及非 `https://` 的地址 | — | — | 标为“不支持” |

Binder 端点不写进 `mcp.json`，因为它的 schema 是封闭的：顶层只有 `$schema` 和 `mcpServers`，每个服务器只能是 `stdio`、`streamable-http`、`sse` 三种之一，没有位置表达 Binder 端点。放在 `extensions."org.agentos"` 里完全符合规范，其他客户端会忽略它，所以带 Binder 端点的插件属于 Android 专用插件，但其中的 Skills、Hooks 和远端 MCP 在其他客户端里照样可用。

同一个插件里，两处的服务器名字不能重复，重复时整个插件校验失败。

远端服务的 `mcp.json` 示例：

```json
{
  "$schema": "https://agent-plugins.org/schemas/1.0.0/mcp.schema.json",
  "mcpServers": {
    "docs": { "type": "streamable-http", "url": "https://mcp.example.com/mcp" }
  }
}
```

`headers` 里的敏感值（token、key）不写进插件包。插件只声明需要哪些请求头，用户启用时在 AgentOS 里填写，值用 Android Keystore 加密保存。

### 3.3 Skills

标准 `SKILL.md`：frontmatter 里写 `name` 和 `description`，正文是说明，同一目录下可以带 `scripts/`、`references/` 等文件。加载方式见第 6 节。

### 3.4 Hooks

按 OpenAI 的方式读取 `extensions."com.openai".hooks`，它可以是一个路径、路径数组、内联对象或内联对象数组。写了这个字段就不再读默认文件；没写才读默认的 `hooks/hooks.json`。路径必须以 `./` 开头，而且不能跑出插件根目录。

文件结构与 OpenAI Codex 相同：事件 → matcher 组 → handler 列表。执行方式见第 7 节。

---

## 4. 插件来源

### 4.1 已安装 App 内嵌的插件

App 开发者在 APK 里做两件事：

1. 把插件包放在 `assets/agent-plugin/`，结构同第 3.1 节；
2. 在 Manifest 里导出 MCP 服务。它同时是 AgentOS 发现插件的锚点：

```xml
<service
    android:name=".agent.NotesMcpService"
    android:exported="true"
    android:permission="org.agentos.permission.BIND_MCP_SERVICE">
    <intent-filter>
        <action android:name="org.agentos.intent.action.PLUGIN" />
    </intent-filter>
    <meta-data android:name="org.agentos.plugin.assets" android:value="agent-plugin" />
</service>
```

`NotesMcpService` 继承 `plugin-sdk` 的 `McpBinderService`，对外实现 `IMcpService`；开发者只需要注册工具。

AgentOS 这一侧：

- 在 Manifest 的 `<queries>` 里声明这个 action，否则 Android 11 及以上看不到其他 App；
- 用 `queryIntentServices(org.agentos.intent.action.PLUGIN)` 找候选，再通过 `createPackageContext(包名, 0).assets` 读取插件包。**这一步不 bind**；
- 插件身份记录为“包名 + 签名证书摘要 + versionCode”（实现：`core/extensions` 的 `registry/PluginScanLogic`，纯函数，A8）。规则：
  - **签名变化**：变化的那一次发出 `SignatureChanged` 和 `Revoke`（关闭该插件已有的 MCP 连接），用户策略清空并停用，插件状态为“需要重新确认”，没有可用服务器。用户确认（`confirmSignature`）后插件恢复为可用，但**仍然停用**，要再启用一次（整合人 2026-10-07 确认：安全优先；插件页把“确认”和“启用”做在同一个对话框里）。签名变回之前信任过的那一个时自动恢复。自带插件免重新确认。
  - **升级**（versionCode 变、签名不变）：重新读清单，保留用户策略；升级后改名则清掉旧名字的策略并撤销旧连接。
  - **卸载**：插件自动移除，已有的连接和授权全部撤销，策略清掉；
  - **第三方插件默认关闭**（plugin 级 `enabled=false`，不覆盖用户已写的值）；自带插件例外；
  - **声明了服务器却一个都不能用**（三项检查：属于本包、已导出、要求 `BIND_MCP_SERVICE`，任一项不满足就拒绝这个服务器）、并且没有 Skills 和 Hooks 时，整个插件标为不可用（`NO_USABLE_SERVER`）；还有可用的 https 远端服务器、或只有 Skills / Hooks 的插件正常；
  - 清单被拒绝、assets 缺失、名字冲突的插件以“不可用”保留在注册表里，带原因和全部问题，插件页显示；
  - 插件名唯一（自带 > 原来的主人 > id 小的，与扫描顺序无关），`user.` 前缀保留给用户配置的 MCP；一个 App 有多个 assets 目录时每个目录是独立插件（id = 包名/目录）；
  - 注册表记忆（`PersistedRegistry`，JSON version=1）读不出来时**不能当空继续**（空记忆 = 把当前签名当可信）：调用方只传 `previous = null`（从来没有过记忆文件的首次运行传 `PersistedRegistry.EMPTY`，文件存在但读不出来才传 `null`），所有第三方插件按“签名未确认”处理（状态 `SIGNATURE_UNCONFIRMED`、没有可用服务器、策略清空并停用，发 `MemoryLost` 和每个插件一条 `Revoke`），自带插件不受影响；确认后恢复可用但仍停用，流程同签名变化；
- `extensions."org.agentos".mcpServers` 里的每个 Service 都必须属于本包、已导出、要求 `BIND_MCP_SERVICE`，任何一项不满足就拒绝这个服务器。

`org.agentos.permission.BIND_MCP_SERVICE` 由 AgentOS App 定义，保护级别是 signature，只有 AgentOS 自己持有。所以提供插件的 App 不需要和 AgentOS 用同一个证书，而其他 App 也 bind 不了它的 MCP 服务。

### 4.2 用户导入的插件包

- **来源**：在 AgentOS App 里选择 zip 文件，或者从其他 App 分享过来。插件市场放到 M6。
- **校验**，任何一项不通过就拒绝导入：
  - 总大小不超过 64 MiB，文件不超过 1,024 个；
  - 只允许普通文件和目录，不能有符号链接、设备文件等特殊条目；路径都是相对路径，不能出现 `..`；
  - `plugin.json`、`mcp.json` 通过 Agent Plugins 1.0 schema 校验；
  - 不能出现 `extensions."org.agentos".mcpServers`，Binder 端点只属于已安装的 App。
- **部分不支持**：比如含 `stdio`、`sse` 服务器或非命令型 Hook，也允许导入，但在插件页标出哪些部分不可用。
- **存放**：导入后的插件放在 `:ext` 进程的私有目录；含 Hooks 或脚本的，同时把一份副本同步给 Runner（第 8 节）。

### 4.3 用户配置的第三方 MCP 服务器

在设置页填写名称、`https://` 地址和请求头，传输固定为 Streamable HTTP。每一项在内部当作一个只含一个 MCP 服务器的“虚拟插件”，名称统一加前缀 `user.`，和其他插件走同一套启用、审批和工具命名规则。

### 4.4 AgentOS 自带插件

AgentOS App 自己也按第 4.1 节的方式内嵌一个插件：`assets/agent-plugin/` 加一个 `BuiltinMcpService`。它提供手机上最基本的能力，保证装上当天就能用：

| 工具组 | 风险等级 | 说明 |
|---|---|---|
| Intent / 分享 | 写 | 打开 App、发起分享 |
| 通知 | 读 / 写 | 读取、回复通知，需要用户授予通知使用权 |
| 日历、联系人 | 读 / 写 | 通过系统 Content Provider |
| shell | 高风险 | 默认关闭；命令在 Runner 里执行，不在 AgentOS App 里执行 |

---

## 5. MCP

### 5.1 本地 App：MCP over Binder

MCP 规范允许自定义传输，前提是保持 JSON-RPC 消息格式和生命周期要求，并写明连接建立和消息交换的方式。AgentOS 的 Binder 传输就是 [architecture.md](architecture.md) 5.2 节的 `binder-channel-v1`，与 ACP 共用：每条 MCP JSON-RPC 消息是一次 `IChannel.send`。它不模仿 HTTP+SSE 或 Streamable HTTP 的形状，只是一条双向的消息通道。

```aidl
package org.agentos.channel;

interface IMcpService {                   // 提供插件的 App 导出，要求 BIND_MCP_SERVICE
    IChannel open(IChannel client);       // 传入 AgentOS 的接收端，返回 MCP 服务的接收端
}
```

规则：

- 单条消息上限与 ACP 相同（初定 128 KiB，S3 测定后冻结）。图片等大内容用 `content://` URI 并临时授予读权限，由 Extension Host 读取后按需压缩，再交给运行时。
- 插件 App 每次调用 `IChannel.send` 时，Extension Host 用 `Binder.getCallingUid()` 校验它等于这个 App 的 UID。
- **连接生命周期**：第一次用到时 `bindService(BIND_AUTO_CREATE)` → `open` → MCP 初始化 → `tools/list` 并缓存（收到 `tools/list_changed` 时刷新）→ 空闲 30 秒后 `close` 并 unbind，App 进程可以被系统回收。
- **App 进程死亡**：通过 `linkToDeath` 感知。进行中的调用返回错误；已经发出、结果未知的，按 [architecture.md](architecture.md) F8 标为“结果未知”；下次用到时重新连接。
- **SDK**：两端都用官方 MCP Kotlin SDK。`McpBinderTransport` 在 `binder-channel` 上实现 SDK 的 Transport 接口，Extension Host（客户端）和 `plugin-sdk` 的 `McpBinderService`（服务端）共用它。
- **协议版本**：MCP 协议修订版本和 SDK 版本在 S5 一起固定。2026-07-28 修订版改动较大（去掉了协议层会话，改为每个请求自带元数据）；一条 Binder 通道天然对应一次连接，按 SDK 支持的修订版实现生命周期即可。

### 5.2 远端：Streamable HTTP

- 用 MCP Kotlin SDK 的 `StreamableHttpClientTransport`，底层 HTTP 引擎用 Ktor 的 OkHttp 引擎；版本在 S5 选定。
- 只允许 `https://`，按系统默认方式校验证书；不支持 OAuth。
- 请求头里的凭据来自 Keystore 加密存储，不写进插件包、日志、事件和模型输入。
- 断线后按退避重连；正在进行的调用按“结果未知”处理，不自动重放。
- 只提供旧 HTTP+SSE 传输的服务连不上，插件页标为“不支持”。

### 5.3 工具目录与命名

Extension Host 汇总所有已启用服务器的 `tools/list`，经 `IExtensionCallback` 整体推送给运行时。每个工具包括：工具名、描述、输入 schema、风险等级、来源插件。

工具名格式为 `mcp__<插件名>__<服务器名>__<工具名>`，并做以下处理，以兼容各家模型 API 对工具名的限制：

- 不在 `[A-Za-z0-9_-]` 里的字符（包括插件名里的 `.`）一律换成 `_`，保留单词边界，不删除字符（整合人 2026-10-07 确认）；
- 总长超过 64 个字符时截断，再加 6 位哈希后缀，保证唯一；哈希取自插件名、服务器名、原始工具名的原文；
- 目录内重名的工具**全部**加后缀（不偏袒任何一个），结果只取决于目录的内容、与顺序无关；仍然重名时哈希从 6 位加长到 8 / 12 / 16 / 24 位。规则见 `ToolNaming.assign`，要拿整个目录一起算。目录里新增一个会撞名的工具，会让原来不带后缀的那个改名：用户策略按（插件、服务器、原始工具名）记，不受影响，只有按工具名写的 Hook `matcher` 会受影响；
- Hook 的 `matcher` 按处理后的最终名字匹配。

工具描述和返回结果都来自第三方，一律当作不可信输入处理。

### 5.4 审批与风险

三层叠加，最终以 AgentOS 的风险策略和确认为准：

1. **默认等级**：MCP 工具默认按“写”处理，每次调用都要确认。
2. **服务端注解只能调高等级**：`destructiveHint=true` 升为“高风险”；`readOnlyHint=true` 不会降低等级，因为注解是服务端自报的，不可信。
3. **用户策略**：可以按插件、服务器、工具分别启用或禁用；审批方式可设为“每次确认”（默认）或“始终允许”，但“高风险”工具不能设为始终允许。这一层参照了 OpenAI 的 `enabled`、`default_tools_approval_mode`、`enabled_tools`、`approval_mode` 设计。

---

## 6. Skills

- **目录**：Extension Host 收集已启用插件的 Skill（名字、描述、来源插件），随工具目录一起推送给运行时。运行时把这份目录写进系统提示，总长度有上限，超出时按启用顺序截断，并在设置页提示。
- **按需读取**：模型通过内置工具 `read_skill(name, path?)` 读取 Skill 正文，或同一 Skill 目录里的其他文件；运行时经 `IExtensionHost` 取内容。只能读该 Skill 目录内的文件，单次读取有大小上限。
- **同名冲突**：用 `<插件名>:<Skill 名>` 区分。
- **脚本**：Skill 附带的脚本只能通过内置 shell 工具执行（在 Runner 里执行、默认关闭、每次确认），写法是 `sh <脚本路径>`。依赖 python、node 等解释器的脚本不能运行。
- **信任**：Skill 内容来自第三方，当作不可信输入处理；第三方插件默认关闭，需要用户主动启用。

---

## 7. Hooks

### 7.1 支持范围

- handler 只支持 `type: "command"`，识别 `command`、`timeout`（或 `timeoutSec`）、`statusMessage`（在界面上显示）。其他类型和字段跳过，并在插件页标出。
- 事件、输入字段和输出格式按 OpenAI Codex hooks 实现，在 W22 对照官方文档和官方 schema 冻结为 `core/protocol/hooks-v1.md`。在 AgentOS 里的触发时机如下：

| 事件 | AgentOS 什么时候触发 | matcher 匹配什么 | Hook 能做什么 |
|---|---|---|---|
| `SessionStart` | 会话新建或恢复 | `source`（`startup` / `resume`） | 补充上下文 |
| `SessionEnd` | 会话关闭或空闲回收 | — | 只能观察 |
| `UserPromptSubmit` | 一轮 prompt 交给模型之前 | 不支持 matcher | 拦截，或补充上下文 |
| `PreToolUse` | 工具执行之前（Pi 的 `beforeToolCall`） | 工具名 | 拒绝、要求确认、改写输入、补充上下文 |
| `PermissionRequest` | AgentOS 弹出确认之前 | 工具名 | 只能拒绝（见 7.3） |
| `PostToolUse` | 工具返回之后（Pi 的 `afterToolCall`） | 工具名 | 补充上下文，或用反馈替换结果 |
| `Stop` | 一轮正常结束 | 不支持 matcher | 要求再继续一次 |
| `Interrupt` | 用户取消（`session/cancel`） | 不支持 matcher | 只能观察 |
| `PreCompact` / `PostCompact` | 上下文压缩前后（运行时支持压缩时） | `trigger` | 按官方语义 |
| `SubagentStart` / `SubagentStop` | AgentOS 暂时没有子代理，不会触发 | — | — |

### 7.2 执行环境

- **在 Runner 里执行**：`/system/bin/sh -c "<command>"`。Android 自带的 sh 是 mksh，兼容 POSIX sh 和大部分常见的 bash 写法，但不保证 bash 专有特性。
- **输入输出**：stdin 是一个 JSON 对象（事件输入）。退出码为 0 时，stdout 里的 JSON 就是 Hook 的决定；退出码为 2 表示拦截，stderr 是原因。这和 OpenAI、Claude 的约定一致。
- **环境变量**：
  - `PLUGIN_ROOT`：插件在 Runner 里的只读副本；
  - `PLUGIN_DATA`：该插件的可写目录；
  - 兼容别名 `CLAUDE_PLUGIN_ROOT`、`CLAUDE_PLUGIN_DATA`，OpenAI 也这样设置。
- **工作目录**：`PLUGIN_DATA`，因为手机上没有“项目工作区”。
- **脚本写法**：插件里的脚本要写成 `sh "${PLUGIN_ROOT}/hooks/x.sh"`。Android 10 起，App 不能直接执行自己数据目录下的文件。
- **缺少的程序**：命令里调用 python、node、jq 等手机上没有的程序会失败，诊断页显示原因。常见写法 `bash xxx.sh` 能否兼容（在 Runner 的 PATH 里放一个转发到 `/system/bin/sh` 的 `bash`），由 S6 验证。
- **首版上限**（可调）：

| 项目 | 上限 |
|---|---|
| 单个 Hook 的超时 | 默认 10 秒，最长 30 秒。OpenAI Codex 的默认超时长得多，不适合手机 |
| 同一事件所有 Hook 的总时长 | 30 秒；`SessionEnd` 为 3 秒 |
| 同一事件同时运行的 Hook | 4 个 |
| stdin | 1 MiB |
| stdout / stderr | 各 64 KiB |
| 注入模型的上下文 | 按 `additionalContextLimit`，默认 8,000 字符 |

### 7.3 决定的合并与边界

- **多个 Hook**：同一事件有多个 Hook 时，任何一个拒绝就拒绝；任何一个要求确认，就必须确认；多个 Hook 同时改写输入视为冲突，按拒绝处理，并在诊断页说明。
- **Hook 不能替用户同意**：`PreToolUse` 和 `PermissionRequest` 返回 allow，都只当作“不反对”，不能跳过 AgentOS 的确认。这是 AgentOS 的客户端权限策略；Agent Plugins 规范把权限交给客户端决定。
- **改写输入**：改写后的输入要重新按工具 schema 校验，确认界面显示的是改写后的内容。
- **失败处理**：Hook 超时、崩溃或输出不合法时，忽略它的输出（fail open），并记入诊断页。**Hooks 不是安全边界**，安全边界是风险策略和确认。
- **覆盖范围**：第三方 App 通过 ACP 发起的会话，同样经过这些 Hooks。

### 7.4 信任

- 启用插件不等于信任它的 Hooks。首次启用时，AgentOS 逐条展示每个 Hook 的事件、matcher 和命令原文，用户确认后才会执行。
- 信任按内容哈希记录，范围是 Hook 定义加上它引用的脚本文件。插件升级或内容变化后，变了的 Hook 回到“待审核”，审核前跳过。
- 每个 Hook 都可以单独禁用。

---

## 8. Runner

| 项目 | 设计 |
|---|---|
| 形态 | 独立 APK `org.agentos.runner`，随 zip 安装，用项目证书签名，有自己的 UID |
| 隔离目标 | 身份：命令拿不到 AgentOS App 的 UID，也就读不到运行时的数据和密钥、绑定不了 App 的内部服务 |
| 权限 | 不申请任何权限 |
| 对外接口 | 一个 Service，要求 `org.agentos.permission.RUN_COMMANDS`（AgentOS App 定义，signature 级）；Runner 再校验调用方 UID 等于 AgentOS App |
| 能力 | `syncPlugin`（接收插件文件副本）、`removePlugin`、`run`（输入命令、stdin、环境变量、超时、输出上限；返回退出码、stdout、stderr）。超时时杀掉整个进程组 |
| 能看到什么 | 只有插件副本和各插件的 `PLUGIN_DATA`。看不到 AgentOS App 的数据；App 的内部服务不导出，Runner 绑定不了 |
| 已知不足 | 所有插件的命令共用 Runner 这一个 UID，插件之间能互相读取 `PLUGIN_DATA`。首版靠信任审核兜底，以后研究每次执行都用 `isolatedProcess` 加固 |

---

## 9. 与运行时的接口

运行时（`:agent`）用 `BIND_AUTO_CREATE` 绑定 Extension Host 的 `IExtensionHost`，并注册一个 `IExtensionCallback`。两个接口都不导出，服务端校验调用方 UID 等于本 App。

| 方法 / 回调 | 方向 | 用途 |
|---|---|---|
| `subscribe(callback)` / `onCatalog` | `:agent` → `:ext` / 回调 | 已启用的工具（名字、描述、schema、风险等级、来源插件）和 Skills（名字、描述）；有变化时整体重发 |
| `callTool` / `cancelTool` / `onToolResult` | 双向 | 工具调用、取消（转成 MCP 的取消通知）、结果 |
| `readSkill` | 请求 / 响应 | 读取 Skill 正文或附带文件 |
| `dispatchHook` | 请求 / 响应 | 触发一个 Hook 事件，返回合并后的决定 |
| `onConnectionState` | 回调 | 各 MCP 服务器的连接状态，只进诊断页 |

`:ext` 进程被杀后由系统按绑定关系重建，重建后重新推送目录。重建期间，需要工具的步骤先等待，最多等到任务的 deadline；已经发出、没有回执的调用标记为“结果未知”。

---

## 10. 流程

### E1 发现 App 内嵌的插件

1. 触发：`:ext` 启动，或收到 `PACKAGE_ADDED` / `REPLACED` / `REMOVED` 广播。
2. 查找带 `org.agentos.intent.action.PLUGIN` 的 Service，读取 `assets/agent-plugin/`，按第 3 节校验，并检查 `extensions."org.agentos".mcpServers` 里的 Service（第 4.1 节）。
3. 记录包名、签名摘要、版本；这一步不 bind。
4. 默认状态：AgentOS 自带插件默认启用（shell 除外）；其他 App 的插件默认关闭，在插件页等用户启用。
5. 启用后生成新目录，经 `IExtensionCallback` 推送给运行时。

### E2 导入插件包

1. 用户选择 zip → 按第 4.2 节校验 → 展示插件信息，以及哪些部分不支持。
2. 用户确认导入 → 写入私有目录；有 Hooks 或脚本的，同步一份副本给 Runner。
3. 含 Hooks 的进入审核页（第 7.4 节）。未审核的 Hooks 不执行，插件其余部分可以先用。
4. 用户启用插件 → 需要凭据的 MCP 服务器先填写请求头 → 生成新目录，推送给运行时。

### E3 配置第三方 MCP

在设置页填写名称、`https://` 地址和请求头 → 试连一次（Streamable HTTP 初始化、`tools/list`）→ 成功后保存并启用 → 推送新目录。

### E4 一次工具调用（含 Hooks）

1. 模型返回 `tool_use`，Pi Agent core 校验参数后调用工具，宿主层的 Broker 再按目录校验工具名。
2. Pi 的 `beforeToolCall` 进入宿主层，宿主层经 `dispatchHook(PreToolUse)` 交给 Extension Host → 匹配已信任的 Hook → Runner 执行 → 合并决定 → 返回。
3. 需要确认时，由运行时发起确认（App 在前台时弹出确认界面，否则发通知）；Hook 不能跳过这一步。
4. 运行时经 `callTool` 交给 Extension Host → 找到对应的服务器：本地 App 走 Binder（还没连接就先 bind 并初始化），远端走 Streamable HTTP → `tools/call`。
5. 结果经 `onToolResult` 返回；`PostToolUse` Hook 可以补充上下文或替换结果；然后交回模型。
6. 空闲 30 秒后关闭 Binder 通道并 unbind。

### E5 读取 Skill

模型看到系统提示里的 Skill 目录 → 调用 `read_skill` → 运行时经 `readSkill` 取内容 → 返回正文 → 需要时再读附带文件。

### E6 升级、禁用、卸载

- **App 升级或插件包升级**：重新校验；签名或 Hook 内容有变化的，回到待审核；工具有变化的，重发目录。
- **禁用或卸载**：立即从目录移除，关闭连接，取消进行中的调用，删除 Runner 里的副本；`PLUGIN_DATA` 是否保留由用户选择。

---

## 11. 代码位置

| 位置 | 内容 |
|---|---|
| `core/extensions/` | 扩展的纯逻辑（JVM）：`ManifestReader`、`ToolNaming`、`registry/`（`PluginScanLogic`、`PersistedRegistry`、`ApprovalStore`，A7 / A8）；依赖 `core:runtime`（取 `ApprovalPolicy`），反向没有依赖 |
| `app/src/main/java/org/agentos/app/ext/` | Extension Host（`:ext` 进程）：`registry/`（`AppPluginScanner` 只是 PackageManager → `InstalledAppView` 的薄适配层）、`policy/`（只剩文件路径与接线）、`mcp/`、`skills/`、`hooks/`、`runner/`、`ExtensionHostService` |
| `app/src/main/aidl/org/agentos/internal/` | `IExtensionHost`、`IExtensionCallback` |
| `app/src/main/java/org/agentos/app/builtin/`、`app/src/main/assets/agent-plugin/` | AgentOS 自带插件 |
| `runner/` | Runner APK |
| `sdk/binder-channel/` | `IChannel`、通道的顺序、背压、`linkToDeath` 与 UID 校验 |
| `sdk/plugin-sdk/` | 给 App 开发者：`IMcpService`、`McpBinderTransport`、`McpBinderService` 基类、插件包校验与打包、注解与模板 |
| `core/extensions/` | 纯逻辑，在电脑上测试：清单解析与校验、工具命名、插件包校验、Hook 匹配与决定合并 |
| `core/protocol/` | `binder-channel-v1.md`、`hooks-v1.md`、Agent Plugins 1.0 schema 的固定副本 |
| `core/runtime/` | Hook 触发点（接在 Pi 的 `beforeToolCall` / `afterToolCall` 上）、Skill 目录注入、工具路由 |

完整的文件级目录见 [implementation-plan.md](implementation-plan.md) 第 1 节。

---

## 12. 待验证项与风险

| 项目 | 说明 |
|---|---|
| S4：插件 App 的发现与 Binder 连接 | 读取对方 App 的 assets、后台 bind、跨进程往返、`linkToDeath`、没有权限的 App bind 失败 |
| S5：MCP Kotlin SDK 在 Android 上 | 编译、D8、R8；`McpBinderTransport`；连一个真实的远端 Streamable HTTP 服务；固定 SDK 版本、MCP 协议修订版，以及与 ACP SDK 共用的 Kotlin / Ktor 版本 |
| S6：Runner | 普通 App UID 下用 `/system/bin/sh` 执行命令；超时和进程组清理；输出上限；读不到 AgentOS 的数据、绑定不了内部服务；`bash` 转发是否可行；一次 Hook 往返的耗时 |
| 只支持 Streamable HTTP | 只提供旧 HTTP+SSE 传输的远端服务连不上，导入时标为不支持 |
| 不为插件提供解释器 | `:agent` 里的 QuickJS 只运行 Pi Agent core，不对插件开放。生态里的 stdio MCP 服务，以及依赖 python、node、jq 的 Hook 和 Skill 脚本，在手机上都不能用。导入时标出不支持的部分，Hook 失败进诊断页 |
| Runner 内的插件没有互相隔离 | 靠信任审核兜底，以后用 `isolatedProcess` 加固 |
| 延迟 | 每次工具调用要经过 `:ext` 进程和 Binder 两跳；每个 `PreToolUse` Hook 还要经过 Runner，并启动一次 sh。S6 测量，超标时在有任务期间保持 Runner 常驻 |

---

## 参考

- Agent Plugins 1.0 schema：[plugin.schema.json](https://agent-plugins.org/schemas/1.0.0/plugin.schema.json)、[mcp.schema.json](https://agent-plugins.org/schemas/1.0.0/mcp.schema.json)
- OpenAI 插件打包说明（`extensions."com.openai"`、Hooks 的读取方式、MCP 审批设置）：[Package your plugin](https://developers.openai.com/plugins/build/plugins)
- OpenAI Codex Hooks（事件、信任审核）：[Hooks](https://learn.chatgpt.com/zh-Hans/codex/hooks)
- MCP 传输（自定义传输的要求、HTTP+SSE 的弃用）：[Transports 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)；2026-07-28 修订版的变化：[Streamable HTTP（draft）](https://modelcontextprotocol.io/specification/draft/basic/transports/streamable-http)
- MCP Kotlin SDK 的 Streamable HTTP 客户端：[StreamableHttpClientTransport](https://kotlin.sdk.modelcontextprotocol.io/kotlin-sdk-client/io.modelcontextprotocol.kotlin.sdk.client/-streamable-http-client-transport/-streamable-http-client-transport.html)
