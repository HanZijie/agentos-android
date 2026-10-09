# 开发指南

这一篇讲怎么在本机构建、测试和打包 AgentOS：环境、常用命令、模块与源码位置、示例 App，以及项目发布证书。项目介绍和接入方式见 [README](../README.md)。

目录：[环境](#环境) · [常用命令](#常用命令) · [打包模块 zip](#打包模块-zip) · [模块与源码位置](#模块与源码位置) · [示例 App](#示例-app) · [项目发布证书](#项目发布证书)

## 环境

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | 运行 Gradle 和编译都用它，根 `build.gradle.kts` 会检查。Android Studio 里把 Gradle JDK 设为 21（它自带的 JBR 版本更高，Gradle 8.14 跑不了） |
| Android SDK | platform 36，build-tools 35 及以上 | 用 `ANDROID_HOME` 或 `local.properties` 的 `sdk.dir` 指定；`local.properties` 不进仓库 |
| Gradle | 仓库自带 wrapper（8.14.5） | 不需要单独安装 |
| Node | 22.19 及以上 | 只在构建机上用：打包 `core/pi-runtime`、跑 ACP 一致性测试。手机上不需要 Node |

所有依赖版本都锁定在 `gradle/libs.versions.toml`，与 [implementation-plan.md 第 2 节](implementation-plan.md#2-构建与产物)一致，不要擅自升级。

## 常用命令

```bash
./gradlew test lint -Pagentos.skipPiBundle=true        # 电脑上能跑的单元测试和 Lint，与 CI 的 portable-tests 相同
./gradlew :core:runtime:test                            # 只测运行时宿主层
./gradlew :app:assembleDebug :runner:assembleDebug      # 调试包，用 Android 默认的 debug 证书
./gradlew :app:assembleRelease :runner:assembleRelease  # 发布包，需要下面的项目发布证书
python3 tools/check-i18n.py                             # 中英文资源对齐与字面量门禁，不需要 Android SDK，秒级
```

`:app` 构建前会调用 `core/pi-runtime/build.mjs`，生成 `app/src/main/assets/pi-agent.js` 和 `model-catalog.json`（W3 起；第一次先在 `core/pi-runtime` 里执行 `npm ci`）。这两个文件是生成物，不手改，不进仓库。只跑单元测试时加 `-Pagentos.skipPiBundle=true` 跳过。

## 打包模块 zip

目前还没有发布版本（仓库的 Releases 为空），要刷机用的 `agentos-<ver>.zip` 需要从源码构建：

```bash
python3 tools/package-module.py --variant debug   # debug 变体用 Android 的 debug 证书签名，可以安装
python3 tools/package-module.py --check build/module/agentos-<ver>.zip   # 只对已有的 zip 做离线检查
```

脚本依次做：打包 `core/pi-runtime`（缺 `node_modules` 时先 `npm ci`）→ Gradle 构建 App → 按白名单组装模块 → 离线检查（文件白名单、脚本语法、`module.prop`、支持矩阵、`apks.list` 哈希）→ 生成确定性的 zip，并写出 `.sha256` 和 `build-info.json`。默认输出到 `build/module/`。

正式发布（`--variant release`，默认）要用下面的项目发布证书签名；未签名的 APK 不能被 `pm` 安装，脚本会拒绝，只有 `--allow-unsigned` 能放行，且只用于 CI 里的离线检查（产物名为 `*-unsigned.zip`）。CI 产出的 zip 是测试产物，不是发布版。

## 模块与源码位置

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

## 示例 App

`plugins/samples/` 下有闹钟、日历、备忘录、待办、短信五个示例 App（后两个见 [next-apps-plan.md](next-apps-plan.md)），各自带一个 MCP 服务（Binder，不开 HTTP 端口），用来演示和验收“AgentOS 通过 MCP 完整操作一个 App”。备忘录和短信都引入了 `:sdk:acp-android`，各带一个“让 AgentOS 安排”按钮，是“App 调用 Agent”的参考实现（短信的在会话页，提示词可编辑，见 [third-party-acp.md](third-party-acp.md) 5b）。工具清单、数据与构建见 [sample-apps.md](sample-apps.md) 和各 App 目录下的 README。

```bash
./gradlew :plugins:samples:alarm:assembleDebug :plugins:samples:calendar:assembleDebug :plugins:samples:notes:assembleDebug \
          :plugins:samples:todo:assembleDebug :plugins:samples:sms:assembleDebug
./gradlew :plugins:samples:calendar:testDebugUnitTest
python3 tests/device/acp-channel/sample_apps_e2e.py --serial <序列号>          # 脚本模式；--live 用真实模型
python3 tools/package-samples.py --allow-unsigned --out dist-samples      # 示例 APK 的发布检查（无证书干跑；带证书时去掉 --allow-unsigned），见 next-apps-plan.md 7.4
```

写操作的确认走真实界面：前台是 AgentOS 里的对话框，后台是通知；release 和 debug 用同一套。debug 构建额外带自动应答的调试接收器，供上面的脚本无人值守地跑，release 包里没有。验收结果见 [m1-acceptance.md](m1-acceptance.md) 三之三、三之四。

## 项目发布证书

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
