# S8 实验工程：Pi Agent core 在 QuickJS 里（混合方案）

一次性实验，不接入主构建。结论见 [docs/spikes/S8.md](../../docs/spikes/S8.md)。

| 目录 | 内容 |
|---|---|
| `bundle/` | Node 打包工程：`pi-agent-core` / `pi-ai` 0.86.1 → `dist/pi-agent.js`（单文件 IIFE）和 `dist/model-catalog.json`；`test/` 里是假模型端点、Node `vm` 宿主和契约测试 |
| `kotlin-host/` | 桌面和 Android 共用的 Kotlin 宿主：`HostFetch`（OkHttp 流式 fetch，按 endpoint 注入 key）、`PiJsEngine`（quickjs-kt + 常驻泵）、`S8Runner`（契约用例与测量） |
| `desktop/` | QuickJS 的 JVM 版（`quickjs-kt-jvm`）运行器 |
| `android/` | 测试 App（`quickjs-kt-android`） |
| `tools/` | `run-android.sh`、`cold-start-android.sh` |

## 运行

```bash
# 1. 打包（需要 Node 20+；国内网络可加 --registry=https://registry.npmmirror.com）
cd bundle && npm ci && node build.mjs          # 默认 es2022 + minify；--no-minify 便于调试

# 2. Node 裸 vm 上下文里跑契约测试（只有 ECMAScript 内置对象，与 QuickJS 环境等价）
node test/contract.mjs

# 3. 假模型端点（桌面和 Android 用例都连它）
node test/fake-llm.mjs --port 8787 &

# 4. QuickJS（JVM）：契约、测量、真实端点
cd .. && ./gradlew :desktop:run --args="contract measure"
MINIMAX_API_KEY=... ./gradlew :desktop:run --args="real"      # 可选 OPENAI_COMPAT_BASE_URL / _API_KEY / _MODEL

# 5. Android（设备由整合人分配）
./gradlew :android:assembleRelease
ANDROID_SERIAL=<serial> tools/run-android.sh release contract,measure
ANDROID_SERIAL=<serial> tools/cold-start-android.sh 5
ANDROID_SERIAL=<serial> tools/run-android.sh release real       # 读取本 shell 的 MINIMAX_API_KEY 等
```

版本矩阵用 Gradle 属性切换。默认是 S8 推荐的 Kotlin 2.3.20 + quickjs-kt 1.0.15（AGP 8.10.1）；计划锁定的 Kotlin 2.2.20 读不了 1.0.15 的元数据，只能配 1.0.5，而 1.0.5 遇到中文 + emoji 会挂住：

```bash
./gradlew :desktop:run -Ps8.kotlin=2.2.20 -Ps8.quickjs=1.0.5 --args=contract    # CJK/emoji 用例挂住
./gradlew :desktop:run -Ps8.kotlin=2.4.10 -Ps8.quickjs=1.0.15 --args=contract   # 通过
./gradlew :desktop:run -Ps8.kotlin=2.2.20 -Ps8.quickjs=1.0.15 --args=contract   # 编译失败（元数据 2.4.0）
```

无 key 的真实端点连通性探测（无效 key 请求 MiniMax 国际 / 国内，预期 401 且不重试）：`./gradlew :desktop:run --args=probe`。

密钥只从环境变量读取；Android 上经 intent extra 传入内存，不落盘、不进日志。结果文件在 `desktop/build/`、`android/build/`，不提交。
