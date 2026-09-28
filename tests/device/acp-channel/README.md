# tests/device/acp-channel：ACP 通道的设备测试

adb 驱动，在模拟器或真机上跑。只用于测试，不进 zip。

| 模块 | 是什么 |
|---|---|
| `common/` | 公共部分：场景执行框架（`ScenarioActivityBase`）、ACP 连接封装（`AcpConn`，经 `BinderAcpTransport.connect`）、通道场景（`ChannelScenarios`）、测量工具、测试 Agent 的探针 AIDL |
| `agent/` | 测试 Agent App（`org.agentos.test.acp.agent`）：官方 SDK 的 Agent 端跑在 `:agent` 进程，经 `sdk:acp-android` 导出 `IAcpService`；假 Agent 按 prompt 里的 JSON 指令流式输出；探针可以读统计、调通道参数、自杀 |
| `client/` | 第三方身份的测试客户端（`org.agentos.test.acp.client`）：对测试 Agent 跑全部通道场景；W6 里用它以第三方 UID 调 AgentOS 的 `IAcpService`，验证被拒 |
| `inapp/` | W6：注入 AgentOS **debug 包**的场景执行器（`:acptest` 进程，AgentOS 自己的 UID），对真正的 `:agent` 跑通道和 `AgentService` 用例。release 包里没有 |
| `run.py` | 主机端驱动：安装、逐个执行场景、拼回 logcat 里的结果（tag `ACPTEST`）、汇总 |

## 两套用例

**`--suite sdk`（W5 回归）**：测试 Agent ↔ 测试客户端，两个都是第三方 App，覆盖 `sdk:binder-channel` 和 `sdk:acp-android`。由 `spikes/S3` 的测试 App 改写：去掉了只为测量存在的部分（原始 oneway 压测、电脑端网关），测量仍在 `spikes/S3` 里做。

| 用例 | 通过标准 |
|---|---|
| handshake | initialize → session/new → prompt（20 条，顺序正确）→ 本端 close，关闭原因为 `local` |
| stream-realtime / stream-peak-bp / stream-bigchunks-bp / stream-cjk-bp / stream-window-8x16384 | 条数和顺序正确、以 `END_TURN` 结束；两端主线程上的 Binder 调用为 0 |
| cancel-realtime / cancel-peak-bp | 本轮 `CANCELLED`，同一会话下一轮 `END_TURN` |
| reconnect（× 20）/ reconnect-noclose（× 5，不调 close 直接丢弃） | 每轮都能对话；结束后服务端连接、通道、协程、在跑的 prompt 都为 0，本端通道为 0 |
| server-kill | 流式中途 SIGKILL 服务端：本端通道以 `peer_died` 关闭、prompt 不挂住，服务重建后能重新对话，本端无残留 |
| client-kill | 流式中途客户端自杀：服务端以 `peer_died` 关闭通道，连接、通道、在跑的 prompt 归零 |
| oversize | 本端超长 prompt 本地报错；上限内的 prompt 正常；服务端超长通知被丢弃、本轮照常；绕过 SDK 塞超长消息，服务端判违规关闭 |
| window-violation | 绕过流控连发，服务端以 `window exceeded` 关闭 |
| stream-noflow（负向） | 关掉流控打满：允许写爆，但必须在超时内结束，不能挂住（S3 问题 1） |

**`--suite app`（W6）**：见 `inapp/` 和 `run.py` 的 `app_cases()`。

## 怎么跑

```sh
# 仓库根目录
./gradlew :tests:device:acp-channel:agent:assembleDebug :tests:device:acp-channel:client:assembleDebug \
          :tests:device:acp-channel:agent:assembleRelease :tests:device:acp-channel:client:assembleRelease
python3 tests/device/acp-channel/run.py --serial $ANDROID_SERIAL --suite sdk --build debug
python3 tests/device/acp-channel/run.py --serial $ANDROID_SERIAL --suite sdk --build release

./gradlew :app:assembleDebug :tests:device:acp-channel:client:assembleDebug
python3 tests/device/acp-channel/run.py --serial $ANDROID_SERIAL --suite app
```

`--only a,b` 只跑指定用例。结果写在 `results/raw/`（不进仓库），定稿的结果复制到 `results/` 提交。全部通过时退出码为 0。
