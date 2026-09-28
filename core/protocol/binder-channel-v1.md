# binder-channel-v1：Binder 消息通道

- **状态**：草案。参数由 S3 第二部分测定（2026-09-29，API 35 / 36 / 37 模拟器）；W5 已按本文实现并在模拟器上回归，**真机结果补齐后冻结**（M1 验收前的关口）。
- **用途**：ACP（第三方 App、AgentOS 自带界面 → `:agent`）和 MCP（`:ext` → 插件 App）共用的 Binder 消息通道。
- **实现**：`sdk/binder-channel/`（`IChannel`、`BinderChannel`、`ChannelConfig`，流控账本在 `FlowWindow.kt`）；ACP 的 Transport 在 `sdk/acp-android/`（`BinderAcpTransport`、`JsonRpcCodec`、`AcpAndroid`、`AcpServiceContract`），MCP 的在 `sdk/plugin-sdk/`（W15）。
- **依据**：[docs/spikes/S3.md](../../docs/spikes/S3.md)；实验工程 `spikes/S3/`；回归测试 `tests/device/acp-channel/`。

通道只承载消息，不解析内容，ACP 和 MCP 的语义由各自的 SDK 处理。

## 1. AIDL

```aidl
package org.agentos.channel;

/** 通道的接收端。通信双方各实现一个，在 open 时交换。 */
oneway interface IChannel {
    void send(String message);   // 事务号 1：一条完整的 JSON-RPC 消息
    void close(String reason);   // 事务号 2：关闭通道；在它之前发出的 send 都会先到达
    void ack(long consumed);     // 事务号 3：流控回执，已处理完对方发来的前 consumed 条（累计值）
}

/** AgentOS App 导出，运行在 :agent 进程；intent action org.agentos.intent.action.ACP。 */
interface IAcpService {
    IChannel open(IChannel client);   // 传入客户端的接收端，返回 Agent 的接收端
}

/** 提供插件的 App 导出，要求 org.agentos.permission.BIND_MCP_SERVICE（W15）。 */
interface IMcpService {
    IChannel open(IChannel client);   // 传入 AgentOS 的接收端，返回 MCP 服务的接收端
}
```

- 方法顺序决定事务号，冻结后只能在末尾追加，不能改顺序、改签名。
- 与 architecture.md 5.2 的草图相比，`IChannel` 多了 `ack`：没有回执，发送方无法知道对方处理到哪里，也就无法限制在途消息（见第 5 节）。

## 2. 建立通道

1. 客户端先创建自己的接收端（此时已经能接收消息），再 `bindService()` 并调用 `open(client)`。
2. 服务端在 `open` 里用 `Binder.getCallingUid()` 取调用方 UID 并绑定到这条通道，对客户端的 `IChannel` 调用 `linkToDeath`，返回自己的接收端。`open` 返回前服务端就可以开始发消息。
3. 客户端拿到返回值后对它 `linkToDeath`，开始发送。客户端校验的对端 UID 是服务所在 App 的 UID（`PackageManager.getPackageUid`）。
4. 服务端拒绝时，`open` 抛 `SecurityException`，message 形如 `agentos.acp.<原因码>: <说明>`（`AcpServiceContract.reasonOf` 取原因码）。目前的原因码：
   - `agentos.acp.not_open`：这个版本还不接受第三方 App（M1–M3 对非本 App 的 UID 一律如此；M4 起由授权流程决定）。
5. SDK 的封装：客户端用 `BinderAcpTransport.connect(service, serviceUid, scope)`（Binder 调用在 IO 线程），服务端在 `open` 里用 `BinderAcpTransport.accept(client, Binder.getCallingUid(), scope)`，返回它的 `channel.binder`。两端都要用 `BinderAcpTransport.bindTo(protocol, transport)` 把 SDK 的 `Protocol` 和 Transport 一起关闭。

## 3. 消息

- 一次 `send` 就是一条完整的 JSON-RPC 2.0 消息，语义等同 stdio 下的一行，不含换行符；不使用 JSON-RPC batch。
- 只写标准字段（`jsonrpc`、`id`、`method`、`params`、`result`、`error`），不得带实现相关的字段。接收方忽略未知字段。
  - ACP Kotlin SDK 0.30.1 按接口类型编码时会多出 `"type":"com.agentclientprotocol.rpc.JsonRpcRequest"`，`BinderAcpTransport` 按具体类型编码以避免它（S3.md 问题 3）。
- **长度一律按 `String.length`（UTF-16 code unit，下称“字符”）计**。Java AIDL 的 `String` 在 Parcel 里是 UTF-16，线上占用约为字符数的 2 倍字节，与内容是否为中文无关。
- **单条上限 65,536 字符**（约 128 KiB）。发送方不发超长消息；接收方收到超长消息视为违规，关闭通道。
- 图片等大内容用 `resource_link` 传 `content://` URI，并临时授予读权限。
- **顺序**：同一个 `IChannel` 上的调用按发送顺序、逐个到达。发送方必须由单一写者串行调用 `send`，多线程并发调用时到达顺序等于进入内核的顺序。
- 同一进程内的调用（本地 Binder）不经过内核，`oneway` 退化为同步调用；两端在同一进程时实现不能依赖异步语义。

## 4. 身份

- 每个入站调用（`send`、`close`、`ack`）都用 `Binder.getCallingUid()` 校验，必须等于建立时绑定的 UID；不一致就关闭通道。
- 调用方身份只以这个 UID 为准，不相信消息里自报的任何身份。

## 5. 流控

接收方进程的 Binder 异步缓冲约 508 KiB（(1 MiB − 8 KiB) / 2），由所有向它发 oneway 调用的进程共享，包括 system_server。S3 实测：不加流控时，发送方几毫秒内就能把它写满（8 KiB 的消息 0.3 ms），此后对它的 oneway 调用（包括 `close`）都会失败，直到缓冲排空（S3.md 问题 1）。

**发送方**：

- 在途 = 已经交给 Binder、对方还没 ack 的消息。一条新消息只有满足下面任一条件才能发出，否则挂起等待 ack：
  - 在途为 0（所以不超过单条上限的消息总能发出）；
  - 在途条数 < 32，且在途字符数 + 本条字符数 ≤ 32,768。
- 一条通道在 Binder 缓冲里最多占 max(32,768, 65,536) 字符，约 128 KiB，即接收方缓冲的 1/4；正常流式时不超过 64 KiB（1/8）。32 条也低于内核 oneway spam 判定里“单个发送方 50 个缓冲”的阈值。
- `send` 调用本身不阻塞：消息先进入本地队列，由写协程在后台线程发出，Binder 调用不在主线程发生。
- 本地积压（还没交给 Binder）超过 4,194,304 字符（8 MiB），说明对方长时间不消费，关闭通道。
- 窗口满后 30 s 收不到任何 ack，关闭通道。
- 给生产者的背压：实现提供 `awaitWritable(maxQueuedChars)`，流式输出的生产者（`:agent` 的 `session/update`）每发一条之前等本地积压降到 16,384 字符以下。

**接收方**：

- `send` 在 Binder 线程上只做检查和入队，立即返回（Binder 缓冲随之释放）；解码和处理在别的线程。
- 消息交给上层（如 ACP SDK）之后计为“已处理”。每处理 8 条、或 8,192 字符、或把已收到的都处理完时，回一次 `ack(累计已处理条数)`。
- 未 ack 的条数 > 32，或（未 ack 条数 > 1 且未 ack 字符数 > 32,768），视为对方违反流控，关闭通道。收到的 `ack` 超过本端已发条数，同样视为违规。
- `ack` 调用失败（对方缓冲暂满）时不关闭通道：`ack` 是累计值，按 10 / 50 / 200 / 1,000 / 3,000 ms 退避补发最新值。

注意：窗口只限制 Binder 缓冲和发送方内存。ACP Kotlin SDK 内部的队列是无界的，接收方 SDK 处理不过来时，积压会留在 SDK 里（S3.md 问题 6）。

## 6. 关闭与进程死亡

- **本端关闭**：先把本地积压发完（最多等 2 s），再调用对端的 `close(reason)`。`close` 与之前的 `send` 在同一个对象上，保证在它们之后到达。
- **对端关闭**：收到 `close` 后停止发送；已经收到的消息照常交给上层，然后通道结束。
- **对端死亡**：经 `linkToDeath` 感知，本端关闭通道、释放写协程和队列，不再尝试通知对端。
- **通知关闭失败**：对端缓冲满时 `close` 也会失败；这时按 10 / 50 / 200 / 1,000 / 3,000 ms 退避重试，直到成功或对端死亡，否则对端会永远等下去（S3.md 问题 1）。
- **判断死亡**：oneway 事务因对端缓冲不足失败时，Java 层同样抛 `DeadObjectException`（“Transaction failed on small parcel; remote process probably died, but this could also be caused by running out of binder buffer space”），但对端仍然活着。只能用 `IBinder.isBinderAlive()` 或死亡通知判断对端是否死亡。
- **外层作用域取消**（例如 Service 销毁）也要走完关闭流程并通知对端，不能只丢弃引用：对端持有本端接收端的引用，本端进程不死，对端的连接就不会结束。
- 关闭原因分为：`local`（本端关闭）、`remote`（对端发来 close）、`peer_died`、`violation`（对端违反本规范：UID、超长、超窗口、非法 ack）、`failure`（本端发送失败、ack 超时、积压超限），供上层区分和记录。

## 7. 超长消息在 ACP 层的处理

`BinderAcpTransport` 发送前检查长度，超过单条上限时不发出：

| 消息 | 处理 |
|---|---|
| 请求 | 在本地合成一个错误响应（`-32603`，message 以 `binder-channel-v1: message of N chars exceeds 65536` 开头），调用方的请求立即失败 |
| 响应 | 改发同一 id 的错误响应（`-32603`） |
| 通知 | 丢弃并计数；本轮其他消息照常 |

`:agent` 的更新映射（W4）应自行切分超长的 `agent_message_chunk`、截断或改用 `resource_link` 传大的工具结果，不要依赖这里的丢弃。

## 8. 参数一览

| 参数 | 值 | 依据（S3 模拟器实测） |
|---|---|---|
| 单条上限 | 65,536 字符（约 128 KiB） | 接收方空闲时单个 oneway 事务最多 520,192 字节，String 为 259,072 字符；取其 1/4，留出缓冲给其他调用方 |
| 在途条数上限 | 32 | release 下打满时吞吐约 1.95 万条/秒，与 64 条相当，瞬态积压更小；低于内核 oneway spam 的 50 个缓冲阈值 |
| 在途字符上限 | 32,768（约 64 KiB） | 缓冲的 1/8；16,000 字符的大块流式约 1,200 条/秒 |
| ack 频率 | 每 8 条、8,192 字符，或已处理完 | 打满时约每 6–8 条一个 ack；100 条/秒时几乎每条一个 |
| 本地积压上限 | 4,194,304 字符 | 不带生产者背压打满时积压曾到约 96 万字符 |
| ack 超时 | 30 s | — |
| 关闭前刷出积压 | 最多 2 s | — |
| 生产者背压高水位（建议） | 16,384 字符 | 带背压时积压稳定在 1.7 万字符以内 |
| close / ack 重试 | 10、50、200、1,000、3,000 ms | 无流控实验里对端缓冲在毫秒级内恢复 |
