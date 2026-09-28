# AgentOS ACP Profile v1

> **迁入说明（W4）**：本文从 workspace 根目录的 `agentos-acp-profile-v1.md` 原样迁入，是对外协议的权威文档；除本段和指向 `pi-acp-adapter/` 的相对链接外，正文未改。在本项目（root + zip 形态）里读本文时：
>
> - 文中的 **`sideagentd`** 对应 AgentOS App 的 **`:agent` 运行时**（Kotlin 宿主层 + Pi Agent core，architecture 4.1），不是系统守护进程；
> - **Pi 的接入用 `@earendil-works/pi-agent-core`（连同 `pi-ai`，固定 0.86.1），跑在 `:agent` 内嵌的 QuickJS 里**，而不是文中提到的 `pi-coding-agent`（它自带编码工具和会话文件，依赖 Node，手机上用不到）；`AgentSession` / `pi --mode rpc` 的讨论只作背景；
> - 传输绑定：Android 本机用 Binder（`core/protocol/binder-channel-v1.md`），电脑端经 `adb forward` 到抽象 socket（W9），电脑上的测试用 stdio；
> - 本 Profile 在 AgentOS 里的具体落地——启用的方法、内部事件到 `session/update` 的映射、错误码——见 [acp-mapping.md](acp-mapping.md)；已冻结的扩展见 [acp-extensions.schema.json](acp-extensions.schema.json)；
> - `pi-acp-adapter/` 的源码已作为行为参考放进 `reference/pi-acp-adapter/`。

本文件记录协议分层与扩展边界，作为后续接口定义的依据。当前只确定设计方向，具体扩展方法、字段、错误码和传输绑定尚未冻结，也不代表已经实现。

AgentOS Profile 的版本与 ACP 协议版本独立。

**采用 ACP 作为前端与 Agent 服务之间的对外交互协议；AgentOS Profile 只补充必要的实现约束和扩展，不再维护平行的 `agent-bus/1` 业务协议。**

```text
AionUI / Android App / IDE / Mobile Web
                  ⇅
      ACP JSON-RPC，经双方支持的传输
                  ⇅
sideagentd
  ├── ACP 标准接口及能力协商
  ├── AgentOS Profile 可选扩展
  ├── 身份与权限检查
  ├── Session / Event Store
  ├── 幂等与恢复
  └── Agent Runtime
```

`sideagentd` 对外扮演 ACP Agent，前端扮演 ACP Client。客户端与服务的 ACP 版本、能力和传输匹配时直接通信，不额外增加协议转换层。

标准 ACP 客户端使用标准能力；理解 AgentOS Profile 的客户端通过协商使用额外能力。采用 ACP 不意味着支持所有可选能力，也不意味着现成客户端自动支持我们的传输或扩展。

**ACP 标准负责会话交互。**

直接复用所选 ACP 版本的初始化、会话创建与发现、输入提交、输出更新、取消、历史加载或恢复、权限请求和结构化用户输入。内容块、工具展示、配置选项以及可选的文件和终端能力也优先使用标准定义。

标准权限请求是 `session/request_permission`，客户端通过对应的 JSON-RPC response 回答，不另造 `permission/request` 和 `permission/respond`。

版本基线依据 2026-09-21 查阅的官方资料确定：

| 项目 | 设计约束 |
|---|---|
| ACP v1 | 作为稳定兼容基线，具体可选能力按协商结果启用 |
| ACP v2 | 当时仍为 Draft，仅作为实验性支持候选；接入时复核其状态 |
| 标准方法 | 保持协商版本规定的参数、返回值、事件和生命周期 |
| AgentOS 扩展 | 单独协商，不改变普通 ACP 客户端对标准方法的理解 |

ACP v1 的 `session/prompt` 在本轮结束后返回停止原因，不能改成收到请求就返回入队回执。v1 的 `session/load` 可重放历史，`session/resume` 恢复会话而不重放历史。v2 草案的 Prompt 在消息插入会话后返回 `messageId`，但不承诺持久化或安全重试；其历史恢复也不能直接等同于逐条事件游标恢复。[v1 Prompt](https://agentclientprotocol.com/protocol/v1/prompt-turn)、[v1 Session](https://agentclientprotocol.com/protocol/v1/session-setup)、[v2 Prompt](https://agentclientprotocol.com/protocol/v2/prompt-lifecycle)、[v2 状态](https://agentclientprotocol.com/announcements/acp-v2-draft)。

**AgentOS Profile 只定义客户端必须共同理解的额外约定。**

扩展的判断标准是：服务能否在不要求客户端提供额外信息、遵守额外行为的情况下完成需求。能独立完成的留在服务内部；确需双方配合且标准未覆盖的，才定义扩展。

| 需求 | Profile 要解决的问题 | 当前状态 |
|---|---|---|
| 持久化提交与安全重试 | 客户端复用提交标识；服务持久化后确认；重复提交关联到同一逻辑输入 | 保留为扩展需求，线协议待定 |
| 精确增量恢复 | 客户端报告事件位置，服务补发缺失事件；约定顺序、重复、保留期限和游标失效行为 | 保留为扩展需求，线协议待定 |
| 多前端控制 | 若需要可见的 controller/observer 角色、控制权交接或审批转交，定义双方行为 | 根据实际交互需求收敛，不预设完整方法集 |
| 独立任务控制 | 若需要在一个 Session 中分别查询或取消多个任务，补充标准 Session 控制未覆盖的语义 | 待确认是否进入首版 |

幂等去重不等于工具副作用恰好执行一次。服务崩溃后的外部动作恢复仍需运行时和工具自身的策略。

持久化回执应有独立、明确的确认语义，不能借用 ACP v1 的 Prompt 成功响应提前结束本轮。实现形式在后续接口设计中确定。

扩展遵守 ACP 的现有机制：

- 在规范允许的 `_meta` 中放置有命名空间的扩展数据。
- 自定义方法使用 `_agentos/…` 一类以下划线开头的名称。
- 初始化时声明扩展能力，客户端确认支持后使用。
- 不在标准对象根部随意增加字段，不重新定义标准方法的含义。
- 普通 ACP 客户端保留标准体验；需要扩展保证的操作不能在协商失败时静默降级。

本文件不冻结 `requestId`、`cursor` 或能力声明的具体 JSON 结构。扩展规则依据 [ACP Extensibility](https://agentclientprotocol.com/protocol/v1/extensibility)。

**服务内部负责系统行为。**

- 从可信连接上下文获得调用者身份，完成权限检查；不相信客户端自行声明的 UID、包名或权限。
- 管理 Session、数据库、事件存储、队列、Agent 进程和故障恢复。
- 管理模型、工具、MCP、Plugin 和 Hooks 配置，不将其内部类型直接变成公共协议。
- 处理 Android 生命周期、资源限制和系统监管。
- 将内部运行状态映射为客户端需要的标准更新，必要时使用已协商的扩展。

数据库结构、重试算法、进程监管方式不进入公共协议；持久化确认、可恢复范围等对外保证需要写入契约。

前端断开不等于关闭 Session，也不意味着停止 `sideagentd`。ACP `session/close` 按所选版本的标准语义处理，不拿它代替单纯的连接断开。系统服务的启动、停止和监管属于系统控制面，不向普通 ACP 前端开放守护进程关闭操作。

**传输绑定承载同一套 ACP 消息。**

| 传输 | 目标场景 | 约束 |
|---|---|---|
| stdio / JSONL | 本地测试，以及通过子进程接入的客户端 | 遵守 ACP stdio 约定；连接常驻服务时可由轻量桥接程序转发 |
| Binder / AIDL | Android 本机前端 | 自定义 ACP 传输绑定；AIDL 描述承载方式，不另造一套业务接口 |
| WebSocket | 手机或其他设备远程连接 | 双方需支持同一传输约定，并落实认证与连接管理 |
| HTTP / SSE | 有实际需求时的远程部署 | 作为后续候选，不要求首版同时实现 |

ACP 允许保留 JSON-RPC 格式及生命周期语义的自定义双向传输；截至上述调研日期，网络传输规范仍在推进中。Binder 和自选 WebSocket 绑定不能宣称是现成 ACP 客户端普遍支持的标准入口。[ACP Transports](https://agentclientprotocol.com/protocol/v1/transports)。

设备配对、TLS 和连接身份验证优先在接入层处理；只有需要客户端共同理解的 Agent 业务能力才进入 Profile。普通 App UID 检查本身不构成新增 ACP 方法的理由。

**只在实际存在不匹配时引入桥接或适配。**

- 同为 ACP、传输不同：转发消息，解决连接方式差异，保留协议语义。
- 接入只提供其他协议的 Agent：按需增加协议转换 Adapter。
- AionUI 通过外部 Agent 入口接入：实现它实际支持的 ACP 版本与传输。
- 直接替换 AionUI WebUI 所依赖的 AionCore 后端：属于另一项集成，需要兼容其 HTTP/WebSocket 接口；不属于本 Profile 的默认目标。

AionUI 的 ACP Agent 接入与其前端到 AionCore 的通信是两个不同接口。[AionUI ACP Setup](https://github.com/iOfficeAI/AionUi/wiki/ACP-Setup)、[AionCore Architecture](https://github.com/iOfficeAI/AionCore/blob/main/ARCHITECTURE.md)。

**Pi 接入采用独立的薄适配层，优先复用 SDK。**

2026-09-21 核对的 Pi 官方主线（`3390bd93630965a12a0a1a5c36ce890ec22f7e1d`，包版本 `0.86.1`）提供 `AgentSession` SDK 和 `pi --mode rpc`，未内建 ACP 入口。此前 ACP mode PR 未合并；独立适配器是已有的集成路径。这是调研时的事实，正式接入时固定并复核依赖版本。[Pi SDK](https://github.com/earendil-works/pi/blob/3390bd93630965a12a0a1a5c36ce890ec22f7e1d/packages/coding-agent/docs/sdk.md)、[Pi RPC](https://github.com/earendil-works/pi/blob/3390bd93630965a12a0a1a5c36ce890ec22f7e1d/packages/coding-agent/docs/rpc.md)、[ACP PR #836](https://github.com/earendil-works/pi/pull/836)。

采用以下职责划分；这是接入决策，不代表已实现：

| 层次 | 职责 |
|---|---|
| `sideagentd` | 对外提供 ACP；负责身份、权限策略、Session / Event Store、幂等、恢复、连接及进程监管 |
| Pi 适配层 | 将 ACP 操作映射到 Pi SDK，转换输入内容、输出和工具事件，关联会话，并桥接权限请求与结果 |
| Pi Runtime | 复用模型调用、Agent 循环、工具执行、上下文管理及会话能力 |

Pi 适配层属于 `sideagentd` 的内部实现，不要求前端理解 Pi 类型，也不要求再增加一个 ACP 中转服务。优先通过 SDK 嵌入 Pi；进程隔离和 worker 部署方式后续确定。若改用 `pi --mode rpc`，协议转换仍留在服务内部。

适配层必须保留 ACP v1 的生命周期：普通 prompt 等待 Pi 完成本轮后才返回 `stopReason`；Pi RPC 的接收回执不能作为 ACP 完成响应。RPC 集成需区分 `agent_end` 与完整运行结束的 `agent_settled`。取消必须正确结束对应请求；需要用户授权的工具通过 Pi 的执行前钩子接入 `session/request_permission`，客户端的授权结果仍受服务端权限策略约束。

首版以初始化、创建会话、输入、流式更新、取消和权限桥接为最小范围。输入至少覆盖 ACP 基线的文本与资源链接；图片及其他可选能力验证后再声明。会话发现、历史加载、恢复、文件和终端代理按实际完成情况启用。Pi 会话文件可供内部复用，但不直接等同于 AgentOS 的持久化提交保证或逐条事件恢复契约。

**MCP、Plugin、Hooks 配置先留最小占位。**

接入设计必须保留这三类配置的入口，本轮只记录 TODO，不实现加载器、安装器、执行器或配置管理 API。配置由 `sideagentd` 管理，在创建 Pi Runtime 时注入；具体类型、存储方式和更新时机待实际需求确定。

| 配置项 | 占位约定 |
|---|---|
| MCP | TODO：接入标准 MCP Server 配置与连接管理；ACP 客户端提供的服务器配置复用标准 `mcpServers` 参数，由服务校验后交给运行时。支持的 MCP 传输在实现时确定。 |
| Plugin | TODO：支持选定插件生态的标准 manifest 和配置格式，通过运行时加载入口接入；具体生态、版本与兼容范围尚未确定，不另造公共插件协议，也不承诺不同生态格式通用。 |
| Hooks | TODO：提供运行时生命周期和工具执行钩子的配置入口，优先复用 Pi 的扩展机制；具体事件、处理器格式和执行策略后续确定。权限桥接所需的内部钩子独立于这项通用配置能力。 |

实现前只保留一个**内部、非线协议、非稳定类型**作为接入占位；`unknown` 表示尚未决定格式，不能被实现当作可执行配置：

```ts
interface PiRuntimeConfigPlaceholder {
  mcp?: unknown;
  plugins?: unknown;
  hooks?: unknown;
}
```

占位不代表能力已实现。实现前，非空的 MCP / Plugin / Hooks 配置应明确返回不支持，不能静默忽略或报告已生效；ACP 能力声明与实际支持保持一致。普通运行时配置留在服务内部，只有确需客户端共同理解的行为才考虑标准配置选项或已协商的 Profile 扩展。

初始适配器已放在 [`pi-acp-adapter/`](../../reference/pi-acp-adapter/)：它以独立 Node 包提供 ACP v1 stdio 入口，复用 Pi `AgentSession`，并覆盖初始化、创建会话、Prompt、取消、关闭、输出事件和权限桥接。它不是 `sideagentd`，也不承担 Android 传输、身份、持久化回执或恢复扩展；这些边界仍按本文件执行。

后续接口设计先核对目标客户端的版本和能力，再定义持久化提交、增量恢复与必要的多端控制扩展。MCP、Plugin、Hooks 当前只有内部占位类型和显式拒绝逻辑，不生成正式 schema；需要实现时再冻结配置契约。
