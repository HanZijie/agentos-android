# AgentOS for Android

在已 root 的 Android 手机上，**刷入一个 zip**，就能得到一个常驻的 Agent 服务：

- 任意 App 都能通过 **ACP** 调用它，包括之后才安装的 App；
- 已安装的 App 可以内嵌一个标准的 Agent Plugin（Skills、MCP 工具、Hooks），把能力提供给 Agent；也可以导入第三方插件包、配置第三方 MCP 服务器；
- 不需要刷 ROM。

Agent 运行时是 AgentOS App 里的一个独立进程 `:agent`：外层是 Kotlin 宿主层，负责 ACP、身份、存储、调度和恢复；Agent 循环用上游的 **Pi Agent core**（`@earendil-works/pi-agent-core`），跑在进程内嵌的 QuickJS 里。root 只运行模块脚本：安装 App、开机拉起运行时、在它有任务时被杀后重新拉起、崩溃循环时进入 safe mode。AgentOS 自己的代码不以 root 运行，模型和插件也接触不到 root。设备已经 root，其他 root 应用仍可能读取 AgentOS 的数据，所以安全等级固定为 `best_effort`，并在设置页如实告知。

本仓库接替原型 [agenriod](https://github.com/HanZijie/agenroid)。原型把 Agent 做进了系统镜像，本仓库改为 Magisk / KernelSU 模块。

![AgentOS 开发进度：基础与 M1 已完成，M2 至 M5 进行中，M6 未开始](docs/assets/progress.svg)

![AgentOS 架构图](docs/assets/architecture.svg)

## 演示

一句话，跨三个 App 办完事。真机录屏，左边是下指令和过程，右边是结果。

| 在 AgentOS 里说一句话 | 三个 App 里的结果 |
|:---:|:---:|
| <img src="docs/assets/demo.gif" width="300" alt="在 AgentOS 里输入指令，确认工具调用，查看设置和插件管理"> | <img src="docs/assets/demo2.gif" width="300" alt="日历、闹钟、备忘录里出现了对应的日程、闹钟和备忘"> |

**左：** 输入的指令是：

> 给我设置一个备忘录，记录一下下周我需要写三个 prd，并且在下周四下午 3 点需要开需求评审会。在需求评审会之前，提前半个小时设置闹钟提醒我参会

Agent 通过 MCP 依次调用备忘录、日历、闹钟三个示例 App 的工具。需要确认的调用会先弹确认框，列出工具名、来源插件和参数。录屏后半段是设置页（模型与 key、安全等级）和插件管理（已发现的插件）。动图里打字部分加速了 4 倍，其余 2 倍，点发送前后是原速。

**右：** 周四 15:00 的日程「需求评审会」，14:30 的闹钟「需求评审会提醒」，以及备忘录「下周工作备忘」，都是上面那一句话产生的。

关键帧，从左到右：点发送前输完的指令；日历里周四 15:00 的「需求评审会」；14:30 的闹钟；备忘录「下周工作备忘」。

![关键帧：发送前的指令，以及日历、闹钟、备忘录里的结果](docs/assets/demo-stills.png)

没加速的完整录屏（720p）：[demo.mp4](docs/assets/demo.mp4)、[demo2.mp4](docs/assets/demo2.mp4)。

### 第三方 App 借 AgentOS 办事

上面的指令是在 AgentOS 里输入的。第二段演示换成一个后装的第三方 App：备忘录里有一条备忘，点一下按钮，把文字交给 AgentOS，Agent 读出时间，建出日程和闹钟。备忘录自己没有日历、闹钟的任何权限，也没有模型 key。

<img src="docs/assets/demo3.gif" width="300" alt="在备忘录里点 AgentOS 按钮，首次授权，Agent 建出日程和闹钟，再到日历和闹钟里确认">

真机录屏，原速，没有加速。流程是：打开备忘「本周安排」→ 点 AgentOS 按钮 → 底部面板里确认要发送的文字 → **第一次使用，AgentOS 弹出“允许「Notes」使用 AgentOS 吗？”**（显示包名和签名摘要，默认焦点在“拒绝”），点允许 → 回到备忘录，面板显示已建 1 个日程、1 个闹钟 → 分别跳到日历（周六 15:00 的「和王总开会（3号会议室）」）和闹钟（每周一 07:00 的「跑步」）确认。

授权只问这一次，之后可以在 AgentOS 设置里撤销，撤销后这个 App 进行中的任务立即取消。录制时日历、闹钟两个创建工具设成了“始终允许”，所以没有逐次确认框；没设的话，每次创建仍会弹出确认。设计和验收见 [docs/third-party-acp.md](docs/third-party-acp.md)。

## 设计理念

下面几条是做 AgentOS 时反复回到的判断，后面的架构决策大多从它们推出来。

### Plugin / MCP 是通道，Skill 是操作指南

Agent 用外部能力，其实是两件事：能做什么，以及怎么做好。

- **Plugin / MCP 解决“能做什么”。** 它是 Agent 伸向外部的通道：App 或服务把自己的能力包成工具，Agent 调用，拿回结果。通道要稳定、可校验、可授权，所以每个工具都有 schema、风险等级和确认流程，每次调用都有记录。
- **Skill 解决“怎么做好”。** 它不带来任何新能力，只是写给模型看的操作指南：这类事该用哪几个工具、按什么顺序、哪里容易踩坑、做到什么程度算完。Skill 是文本，按需读取，本身不授予任何权限。

两者分开，接口和用法就能各自演进。App 开发者把工具做窄做准；编排经验写成 Skill，改一句话不用发版。反过来，把能力藏进 Skill 的脚本里，或者把流程塞进工具描述里，权限边界会变模糊，两边都难维护。

AgentOS 也是这么处理的：同一个插件里，MCP 和 Skills 走两条路。工具调用经 Extension Host，过风险策略和用户确认；Skill 通过 `read_skill` 读成文字，按不可信输入交给模型，它附带的脚本要执行，还得过 shell 工具、Runner 和确认。细节见 [docs/extensions.md](docs/extensions.md)。

### ACP 在手机上是系统服务的接口，不只是接线层

ACP 目前最常见的用法，是 Agent 外壳的通用接线层：编辑器、终端通过它接上各家 Agent。Agent 是编辑器拉起的一个子进程，生命周期跟着客户端走。

当 Agent 成为系统服务（常驻、有身份、有状态、能跨 App 办事），它就不该再被某个 App 包着，而是系统向上层 App 提供的服务，和定位、通知、语音识别处在同一个位置。App 不再“集成一个 Agent”，而是向系统“请求 Agent 的服务”：调用方身份由内核给出（Binder 的调用方 UID），能不能调用由用户决定，用量看得见，随时能撤。这时 ACP 承担的是这项系统服务的调用协议。所以 AgentOS 里 ACP 走 Binder，只有电脑端调试才走 `adb forward` 的 stdio 语义。

### 开放的 Agent 系统，而不是超级 App

顺着这两点，产品形态也就清楚了。如果让我们做 AI 手机，或者“豆包手机”这一类产品，我们更愿意把它做成一个支持 Agent 生态的开放手机系统，而不是嵌在手机里的一个超级 App。

超级 App 的路线里，Agent 要操作别的 App，主要靠 GUI 模拟、无障碍服务，或者和少数几家做定向合作。能力边界由这个 App 的厂商划定，别的 App 想接入只能谈合作；Agent 越强，入口就越集中在这一家。

开放系统的路线里，能力由各个 App 自己声明，Agent 通过通道调用，App 也可以反过来调用 Agent。身份、授权、确认、审计由系统统一承担，这几件事恰好是任何单个 App 都做不好、也不该由它一家掌握的。Agent 本身也应该可以换：模型和 Agent 循环是实现，不是系统的一部分，系统定义的是接口，用户像选默认浏览器、输入法那样选默认助理。

本仓库不改 ROM，只能在现有手机上用 root + 一个 zip 把这个形态走通：Agent 常驻，任意 App 经 ACP 调用，App 内嵌插件提供能力。因为注册不进 `system_server`，只能用普通 App 权限加用户授权去近似：走系统的默认助理角色，靠 Binder 的调用方 UID 认身份，安全等级也只能标为 `best_effort`。这和真正做进系统有差距，这一点不回避。如果由手机厂商来做，注册为系统服务、系统级的权限模型、统一的确认界面，这几部分本来就该是系统自带的，方向和这里一致。

## 用户怎么用

1. 手机已解锁并装好 Magisk 或 KernelSU，系统是 Android 15–17。
2. 在 root 管理器里刷入 `agentos-<ver>.zip`，然后重启。模块会自动安装 AgentOS App 和 Runner，并拉起 Agent 运行时。
3. 打开 AgentOS App 完成首次引导：选择模型厂商并填写 key（MiniMax 等预设，或自定义兼容端点）、允许通知、选择默认助理、允许忽略电池优化、查看已发现的插件。

之后可以长按电源键唤起助手，也可以在其他 App 里通过 ACP 调用它。

## 开发者怎么接入

- **调用 Agent**：在 App 里引入 `acp-android` SDK，用标准的 ACP 客户端接口建会话、发 prompt、接收流式更新。首次调用时，由用户在 AgentOS 里授权。`acp-android` 基于官方 ACP Kotlin SDK（固定 0.30.1），加上 Binder 传输；已在 Android 15 / 16 / 17 模拟器上跑通 ACP over Binder（S3），真机验证待做。
- **把能力提供给 Agent**：在 App 里内嵌一个标准的 Agent Plugins 1.0 插件，放在 `assets/agent-plugin/`（`plugin.json`、`skills/`、`mcp.json`、Hooks）。MCP 服务用 `plugin-sdk` 在你自己的 App 进程里实现，在 `plugin.json` 的 `extensions."org.agentos"` 里声明，AgentOS 通过 Binder 连接，不需要任何解释器。
- **分发插件包**：标准插件包（zip）可以直接导入 AgentOS。其中的 MCP 只支持远端 Streamable HTTP（`https://`）；Hooks 只支持命令型，用手机自带的 `sh` 执行。
- **在电脑上调试**：通过 `adb forward` 用任意 ACP 客户端连接手机上的 Agent。

## 阅读顺序

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 范围与决策记录、设计原则、组件、协议（ACP、Binder 消息通道、App 内部接口、扩展）、13 个功能的实现过程 |
| [docs/extensions.md](docs/extensions.md) | 扩展模块（Extension Host）：插件格式、插件来源、MCP over Binder、远端 Streamable HTTP、Skills、Hooks、Runner |
| [docs/implementation-plan.md](docs/implementation-plan.md) | 文件级目录、构建与产物（含依赖版本锁定）、8 项验证、依赖顺序图、28 个工作包与 M1–M6 出口条件、从 agenriod 迁移、风险 |
| [docs/spikes/](docs/spikes/) | 各项验证的结论：S1（模块安装 APK）、S2（保活与 root 监督）、S3（ACP over Binder）、S8（Pi Agent core 在 QuickJS 里）；实验工程在 `spikes/`，不参与主构建 |
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

## 开发

### 环境

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 运行 Gradle 和编译都用它，根 `build.gradle.kts` 会检查。Android Studio 里把 Gradle JDK 设为 21（它自带的 JBR 版本更高，Gradle 8.14 跑不了） |
| Android SDK | platform 36，build-tools 35 及以上 | 用 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir` 指定；`local.properties` 不进仓库 |
| Gradle | 仓库自带 wrapper（8.14.5） | 不需要单独安装 |
| Node | 22.19 及以上 | 只在构建机上用：打包 `core/pi-runtime`、跑 ACP 一致性测试。手机上不需要 Node |

所有依赖版本都锁定在 `gradle/libs.versions.toml`，与 [implementation-plan.md 第 2 节](docs/implementation-plan.md#2-构建与产物)一致，不要擅自升级。

### 常用命令

```bash
./gradlew test lint -Pagentos.skipPiBundle=true        # 电脑上能跑的单元测试和 Lint，与 CI 的 portable-tests 相同
./gradlew :core:runtime:test                            # 只测运行时宿主层
./gradlew :app:assembleDebug :runner:assembleDebug      # 调试包，用 Android 默认的 debug 证书
./gradlew :app:assembleRelease :runner:assembleRelease  # 发布包，需要下面的项目发布证书
```

`:app` 构建前会调用 `core/pi-runtime/build.mjs`，生成 `app/src/main/assets/pi-agent.js` 和 `model-catalog.json`（W3 起；第一次先在 `core/pi-runtime` 里执行 `npm ci`）。这两个文件是生成物，不手改，不进仓库。只跑单元测试时加 `-Pagentos.skipPiBundle=true` 跳过。

### 模块与源码位置

| Gradle 模块 | 类型 | 包名 |
|---|---|---|
| `:core:runtime` | Kotlin/JVM | `org.agentos.runtime` |
| `:core:extensions` | Kotlin/JVM | `org.agentos.extensions` |
| `:sdk:binder-channel` | Android 库 | `org.agentos.channel` |
| `:sdk:acp-android` | Android 库 | `org.agentos.acp` |
| `:sdk:plugin-sdk` | Android 库 | `org.agentos.plugin` |
| `:app` | Android App | `org.agentos.app` |
| `:runner` | Android App | `org.agentos.runner` |
| `:plugins:samples:<name>` | Android App | `plugins/samples/<name>/` 下有 `build.gradle.kts` 就自动加入构建 |

源码用 Gradle 的标准布局。implementation-plan.md 里的简写路径按包名展开，例如 `core/runtime/ports/AgentCore.kt` 就是 `core/runtime/src/main/kotlin/org/agentos/runtime/ports/AgentCore.kt`。SDK 级别（`compileSdk` / `targetSdk` 36，`minSdk` 35）、字节码版本和 release 签名由根 `build.gradle.kts` 统一配置，各模块只写 `namespace` 和依赖。

### 示例 App

`plugins/samples/` 下有闹钟、日历、备忘录三个示例 App，各自带一个 MCP 服务（Binder，不开 HTTP 端口），用来演示和验收“AgentOS 通过 MCP 完整操作一个 App”。工具清单、数据与构建见 [docs/sample-apps.md](docs/sample-apps.md) 和各 App 目录下的 README。

```bash
./gradlew :plugins:samples:alarm:assembleDebug :plugins:samples:calendar:assembleDebug :plugins:samples:notes:assembleDebug
./gradlew :plugins:samples:calendar:testDebugUnitTest
python3 tests/device/acp-channel/sample_apps_e2e.py --serial <序列号>          # 脚本模式；--live 用真实模型
```

目前只有 debug 构建能放行需要确认的写操作（真实确认界面还在做），验收结果见 [docs/m1-acceptance.md](docs/m1-acceptance.md) 三之三。

### 项目发布证书

AgentOS App 和 Runner 用同一张项目发布证书签名。模块的 `service.sh` 升级 App 时要求签名一致，所以证书发布后就不能更换；私钥丢了，已安装的用户就无法再升级。**私钥只放在仓库外**，不进仓库，不进 CI 日志。

生成（只做一次，由项目维护者执行）。密码由 `keytool` 交互式输入，不要写在命令行里：

```bash
mkdir -p ~/.agentos/signing && chmod 700 ~/.agentos/signing
keytool -genkeypair \
  -keystore ~/.agentos/signing/agentos-release.p12 -storetype PKCS12 \
  -alias agentos-release -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10000 \
  -dname "CN=AgentOS Release, O=AgentOS"
chmod 600 ~/.agentos/signing/agentos-release.p12
```

查看证书的 SHA-256 指纹（发布说明和签名核对时用）：

```bash
keytool -list -v -keystore ~/.agentos/signing/agentos-release.p12 -alias agentos-release | grep SHA256
```

构建发布包时用环境变量传入，构建脚本只从环境变量读取：

```bash
export AGENTOS_SIGNING_STORE_FILE=~/.agentos/signing/agentos-release.p12
export AGENTOS_SIGNING_KEY_ALIAS=agentos-release
read -rs AGENTOS_SIGNING_STORE_PASSWORD && export AGENTOS_SIGNING_STORE_PASSWORD
./gradlew :app:assembleRelease :runner:assembleRelease
"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

- 三个变量都不设时，release 构建不签名（产出 `*-unsigned.apk`）；只设了一部分会直接报错。
- keystore 放在仓库目录里会直接报错。`.gitignore` 也排除了 `*.jks`、`*.keystore`、`*.p12` 等文件。
- `AGENTOS_SIGNING_KEY_PASSWORD` 可选；PKCS12 的 key 密码默认与 store 密码相同。
- keystore 和密码要离线备份。CI 发布（W28）时通过 GitHub Secrets 注入，不写进仓库和日志。

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

MIT License，与 agenriod 保持一致，见 [LICENSE](LICENSE)。
