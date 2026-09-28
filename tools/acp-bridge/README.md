# tools/acp-bridge：电脑端的 stdio ↔ 手机桥接命令

把手机上 AgentOS 的 ACP Agent 变成电脑上的一个普通 **stdio Agent 命令**：以子进程方式启动 Agent 的 ACP 客户端（Zed 以及各种 CLI）直接把 `acp-bridge` 配成 Agent 命令即可。

它做三件事：

1. 自动执行 `adb forward tcp:<端口> localabstract:agentos-acp`（端口默认由 adb 选空闲的），退出时移除；
2. 完成配对握手：第一次用手机上显示的**一次性配对码**，之后用配对时拿到的**令牌**（存在本机）；
3. 之后原样转发字节：stdin → 手机，手机 → stdout。stdout 上只有 ACP 消息，诊断信息一律走 stderr。

协议见 [core/protocol/acp-mapping.md](../../core/protocol/acp-mapping.md) 第 10 节。只用 Node 自带模块（Node ≥ 22.19），没有依赖。

## 用法

```bash
# 手机：设置 → 电脑端接入，打开开关，记下显示的 6 位配对码（5 分钟有效，只能用一次）
# 电脑：USB 连上手机（adb devices 能看到），配对一次
node tools/acp-bridge/acp-bridge.mjs pair 482913

# 之后：作为 ACP Agent 命令使用
node tools/acp-bridge/acp-bridge.mjs
```

Zed 的 `settings.json` 示例：

```json
{
  "agent_servers": {
    "AgentOS (phone)": {
      "command": "node",
      "args": ["/path/to/agentos-android/tools/acp-bridge/acp-bridge.mjs", "--quiet"]
    }
  }
}
```

| 选项 | 说明 |
|---|---|
| `-s, --serial <serial>` | adb 设备；默认 `$ANDROID_SERIAL`，或唯一连着的设备 |
| `--port <n>` | adb forward 用的本机端口；默认 0（adb 选） |
| `--code <code>` | 这次连接用的配对码（也可以用 `$AGENTOS_PAIRING_CODE`）；只在第一次需要 |
| `--connect <host:port>` | 不用 adb，直接连这个 TCP 地址（测试、远程转发） |
| `--state <file>` | 令牌存储；默认 `$AGENTOS_BRIDGE_STATE` 或 `~/.config/agentos/acp-bridge.json`（权限 0600） |
| `--adb <path>` | adb；默认 `$ADB`、`$ANDROID_HOME/platform-tools/adb`、PATH 里的 `adb` |
| `--label <name>` | 手机设置页里显示的这台电脑的名字；默认 `acp-bridge@<主机名>` |
| `--timeout <ms>` | 握手超时，默认 10,000 |
| `-q, --quiet` | 只打印错误 |

`acp-bridge unpair` 删掉本机为这台设备保存的令牌。在手机上撤销配对或关掉开关，令牌同样失效；桥接命令收到 `invalid_token` 时会自动删掉它，并提示重新配对。

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 正常（stdin 结束后连接关闭；`pair` / `unpair` 成功） |
| 2 | 参数错误 |
| 3 | 没有配对，或手机拒绝了配对码 / 令牌（stderr 里有原因和下一步：`invalid_code`、`code_expired`、`too_many_attempts`、`invalid_token`…） |
| 4 | adb 或设备的问题（没有设备、多个设备没指定、forward 失败） |
| 5 | 手机上没有在监听：电脑端接入没打开，或 AgentOS（`:agent`）没在运行 |
| 6 | 连接中途被手机关闭（例如关掉了开关、撤销了配对） |

## 安全

- 手机上的开关默认关闭；关闭时连不上，关闭会断开所有电脑端连接并作废全部配对。
- 配对码一次性、5 分钟过期、输错 5 次作废；握手成功之前手机不处理任何 ACP 消息。
- 令牌只保存在本机的状态文件里（0600），不打印到终端；手机上只存它的 SHA-256。
- 电脑端发起的工具调用照常在手机上确认，客户端无法替用户同意。

## 测试

`tests/acp-conformance` 经这个命令跑一致性用例：电脑上的网关（`--connect`，CI 里跑）和手机（`AGENTOS_ACP_DEVICE=<serial>`）。
