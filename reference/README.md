# reference/：只作行为参考和契约测试的原型代码

这里的文件从原型原样复制过来，**不参与构建，不被任何模块引用**，也不再维护。本项目的实现以 `core/contracts/` 为准，用 Kotlin 在 `core/runtime/` 重写（docs/implementation-plan.md 第 6 节）。

| 目录 | 来源 | 用途 |
|---|---|---|
| `sideagentd/` | agenriod `platform/aosp-integration/overlay/system/agent/sideagentd/`（C++） | 原型的系统守护进程：追加写与 sequence、重启后把运行中的任务围栏为 unknown、会话自动选择（Jev）。`core/runtime/store`、`scheduler/Recovery`、`router` 按它的行为重写；模型调用和工具循环不再参考（由 Pi Agent core 负责） |
| `daemon/` | agenriod `system/agent/daemon/`（Node，含 `test/`、`workers/`） | 调度契约的参考实现：同会话串行、并发上限、取消宽限、执行超时、幂等提交、选择元数据与 brief、Jev 回退。`core/runtime/scheduler`、`router` 的测试用例按它的测试改写 |
| `contracts/` | agenriod `system/agent/contracts/` 的 `session-scheduling-v1.md`、`session-selection-v1.md`、`output-stream-v1.md`、`agent-bus-v1.md` | 迁移前的原文，便于对照 `core/contracts/` 里改写后的版本 |
| `pi-acp-adapter/` | workspace 根目录的 `pi-acp-adapter/`（只取 `src/`、`test/`、`README.md`、`package.json`、`tsconfig.json`） | ACP ↔ Pi 映射的经验（它接的是 `pi-coding-agent`，本项目接 `pi-agent-core`），写进 `core/protocol/acp-mapping.md`（W4） |

来源版本：

- agenriod：`ade5f7e4d137e8596ac4b4ca0417a9a1aa382b01`（2026-09-27），MIT，与本仓库同一作者。
- pi-acp-adapter：workspace 里的本地工程，没有 git 历史；复制于 2026-09-29，依赖 `@agentclientprotocol/sdk` 1.4.0、`@earendil-works/pi-coding-agent` 0.86.1。

没有复制的：原型的构建产物（`dist/`、`node_modules/`）、部署用的密钥模板（`jev.env.example`）、ROM 方向的 AIDL、SELinux、init rc 等（implementation-plan.md 第 6 节：不迁移）。
