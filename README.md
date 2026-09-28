# AgentOS for Android

在已 root 的 Android 手机上，**刷入一个 zip**，就能得到一个常驻的 Agent 服务：

- 任意 App 都能通过 **ACP** 调用它，包括之后才安装的 App；
- 已安装的 App 可以内嵌一个标准的 Agent Plugin（Skills、MCP 工具、Hooks），把能力提供给 Agent；也可以导入第三方插件包、配置第三方 MCP 服务器；
- 不需要刷 ROM。

Agent 运行时是 AgentOS App 里的一个独立进程 `:agent`：外层是 Kotlin 宿主层，负责 ACP、身份、存储、调度和恢复；Agent 循环用上游的 **Pi Agent core**（`@earendil-works/pi-agent-core`），跑在进程内嵌的 QuickJS 里。root 只运行模块脚本：安装 App、开机拉起运行时、在它有任务时被杀后重新拉起、崩溃循环时进入 safe mode。AgentOS 自己的代码不以 root 运行，模型和插件也接触不到 root。设备已经 root，其他 root 应用仍可能读取 AgentOS 的数据，所以安全等级固定为 `best_effort`，并在设置页如实告知。

本仓库接替原型 [agenriod](https://github.com/HanZijie/agenroid)。原型把 Agent 做进了系统镜像，本仓库改为 Magisk / KernelSU 模块，**当前处于规划阶段，还没有代码**。

![AgentOS 架构图](docs/assets/architecture.svg)

## 用户怎么用

1. 手机已解锁并装好 Magisk 或 KernelSU，系统是 Android 15–17。
2. 在 root 管理器里刷入 `agentos-<ver>.zip`，然后重启。模块会自动安装 AgentOS App 和 Runner，并拉起 Agent 运行时。
3. 打开 AgentOS App 完成首次引导：选择模型厂商并填写 key（MiniMax 等预设，或自定义兼容端点）、允许通知、选择默认助理、允许忽略电池优化、查看已发现的插件。

之后可以长按电源键唤起助手，也可以在其他 App 里通过 ACP 调用它。

## 开发者怎么接入

- **调用 Agent**：在 App 里引入 `acp-android` SDK，用标准的 ACP 客户端接口建会话、发 prompt、接收流式更新。首次调用时，由用户在 AgentOS 里授权。`acp-android` 基于官方 ACP Kotlin SDK（固定 0.30.1），加上 Binder 传输；在 Android 15 / 16 上编译打包已验证通过，真机验证待做。
- **把能力提供给 Agent**：在 App 里内嵌一个标准的 Agent Plugins 1.0 插件，放在 `assets/agent-plugin/`（`plugin.json`、`skills/`、`mcp.json`、Hooks）。MCP 服务用 `plugin-sdk` 在你自己的 App 进程里实现，在 `plugin.json` 的 `extensions."org.agentos"` 里声明，AgentOS 通过 Binder 连接，不需要任何解释器。
- **分发插件包**：标准插件包（zip）可以直接导入 AgentOS。其中的 MCP 只支持远端 Streamable HTTP（`https://`）；Hooks 只支持命令型，用手机自带的 `sh` 执行。
- **在电脑上调试**：通过 `adb forward` 用任意 ACP 客户端连接手机上的 Agent。

## 阅读顺序

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 范围与决策记录、设计原则、组件、协议（ACP、Binder 消息通道、App 内部接口、扩展）、13 个功能的实现过程 |
| [docs/extensions.md](docs/extensions.md) | 扩展模块（Extension Host）：插件格式、插件来源、MCP over Binder、远端 Streamable HTTP、Skills、Hooks、Runner |
| [docs/implementation-plan.md](docs/implementation-plan.md) | 文件级目录、构建与产物（含依赖版本锁定）、8 项验证、依赖顺序图、28 个工作包与 M1–M6 出口条件、从 agenriod 迁移、风险 |
| [docs/spikes/](docs/spikes/) | 各项验证的结论；目前已有 S3 第一部分（ACP Kotlin SDK 在 Android 上的编译与打包） |
| [docs/assets/request-flow.svg](docs/assets/request-flow.svg) | 核心链路时序图：后装的 App 通过 ACP 调用 Agent，工具由另一个 App 内嵌的插件经 Binder 提供（含可选的 Hook 步骤） |
| [docs/assets/dependency-graph.svg](docs/assets/dependency-graph.svg) | 实现依赖顺序图：工作包和验证项的前后关系 |

对外协议以 workspace 根目录的 `agentos-acp-profile-v1.md` 为准，W4 时迁入本仓库的 `core/protocol/`。

## 顶层目录（规划）

```text
agentos-android/
├── core/      # contracts · protocol（ACP Profile、Binder 消息通道、Hooks、Agent Plugins schema）· runtime（Kotlin 宿主层与 Pi 适配层）· pi-runtime（打包进 APK 的 Pi Agent core）· extensions
├── app/       # AgentOS App：主进程（界面、确认、设置）· :agent（运行时、ACP 入口）· :ext（Extension Host）
├── runner/    # Runner：独立 UID，用 sh 执行 Hooks、shell 工具、Skill 脚本
├── sdk/       # binder-channel · acp-android（给后装 App）· plugin-sdk（在 App 里内嵌插件）
├── module/    # agentos.zip 的脚本：安装前检查、root 监督进程、safe mode
├── plugins/   # samples（内嵌插件的示例 App）
├── tools/  reference/  tests/  docs/  .github/
```

## 里程碑

工作包按依赖开工，不等上一个里程碑验收；里程碑是验收检查点，顺序为 M1 → M2 → M3a → M3b ∥ M4 → M5。依赖关系见 [依赖顺序图](docs/implementation-plan.md#4-依赖顺序)。

| 里程碑 | 目标 |
|---|---|
| 基础 | 仓库与构建、运行时宿主层、Pi Agent core 接入、ACP Agent 端、Binder 通道（原 M0 已拆散，验证项按需插在各自解锁的工作之前） |
| M1 一个 zip 跑通对话 | 刷 zip 后 10 分钟内完成第一次对话；电脑端 ACP 客户端能对话和取消 |
| M2 可靠性 | 持久化提交与恢复、监督强化与 safe mode、断网与升级 |
| M3a 入口与本地插件 | 助理入口、Extension Host、App 内嵌插件（MCP over Binder）、AgentOS 自带插件、工具确认 |
| M3b 插件包、Skills、Hooks、远端 MCP | 导入标准插件包、远端 Streamable HTTP MCP、Skills、命令型 Hooks、Runner、shell 工具 |
| M4 对外开放 ACP | 后装 App 通过 SDK 调用；授权、限额、会话隔离 |
| M5 开发者生态与发布 | plugin-sdk 注解与模板、KernelSU、设备矩阵、正式发布 |
| M6 进阶 | AppFunctions、GUI 兜底、Memory、多用户实验 |

## 不在范围内

刷 ROM、AOSP 集成、system_server 服务、平台签名、未 root 的设备。

## 许可证

MIT License，与 agenriod 保持一致。`LICENSE` 文件在 W1 建仓时加入。
