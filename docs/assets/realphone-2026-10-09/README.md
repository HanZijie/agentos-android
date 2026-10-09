# 真机检查（Pixel 8，Android 15 / API 35，2026-10-09）

- 设备：Pixel 8 · Android 15 (API 35) · `ro.build.fingerprint` = `google/shiba/shiba:15/BP1A.250505.005.B1/13277630:user/release-keys`；系统语言 zh-Hans-CN。
- 被测提交：见 PR 说明里的 `git rev-parse HEAD`（已合入当时最新的 main）；装的是该提交构建的 **debug** 包，`adb install -r` 覆盖安装，**没有 `pm clear`**。
- 没有碰：真实短信（没读、没发）、真实日程（只数了个数）、用户的模型配置与授权记录（备忘录的授权保持 `allowed`，没撤销）。
- 还原：卸载了我新装的待办和短信；包列表、已授予权限、三个旧 App 的数据条数、插件启用状态、字体 / 动画 / 锁屏超时 / 系统语言、电池白名单、确认模式、`adb reverse` 都和测试前一致（对照 `/tmp` 里测试前后的快照，未入库）。

| 检查 | 结果 |
|---|---|
| 覆盖安装 AgentOS + 闹钟 / 日历 / 备忘录，新装待办、短信 | 全部 `Success`；三个旧插件仍是启用状态（签名不变，无需重新确认）；待办、短信默认关闭 |
| 旧数据保留 | 闹钟、日历、备忘录各 1 条，升级前后数量相同（内容没读） |
| 闹钟：`SET_ALARM` Intent（`skip_ui`，10 分钟后） | 建出闹钟，`registered: true`，系统下一个闹钟归属本 App；界面里滑动删除后 `scheduled` 只剩原来那条 |
| 待办：自测 | `SelfTestReceiver` 32/32，经真实 Binder 的 MCP；自测后 `total: 0` |
| 待办：中英文 | 中文系统下是中文；`cmd locale set-app-locales … --locales en` 后是英文；已还原 |
| 短信：权限与模式 | `READ_SMS` / `SEND_SMS` 未授予 → `compose_only`，界面是“仅撰写模式”和三步“允许受限制的设置”引导（`sms-zh-compose-only.png`）；**没有授予、没有发送、没有读取** |
| 日历：无账号 | 系统日历 0 个、别人的日程 0 条，只有本机日历；`system_access: true`（升级前该权限就已授予） |
| 主 App 英文：对话页、确认框（写级、高风险）、已授权 App 页 | 全英文，文字完整；写级四个按钮，高风险只有 Allow once / Deny；已授权页有 Revoke access（没点）。两个注入的确认都选了 Deny，没有任何真实工具执行 |

## 没做 / 没验证（真机上）

- 第三方授权框：测试客户端的真机脚本 `third_party.py` 会 `pm clear` 主 App，会抹掉模型源和配对，按 AGENTS.md 4.2 需要维护者明确同意，所以没跑；授权框的英文只有模拟器证据（`docs/assets/i18n-p1/`）。
- 真实模型：真机上没有跑（`--live` 只在模拟器上跑过）。
- 日历写入、同步到 Google / 飞书：真机没有账号，未验证。
- 短信：真实 SIM 发送、读取真实短信：未验证（也不应该在没有明确同意时做）。
- 中文下的主 App 界面走查、1.3 倍字体：真机上没走（模拟器上有）。
- 覆盖安装的“从发布证书签名的上一版”：这次装的是 debug 包覆盖 debug 包。
