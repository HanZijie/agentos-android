# i18n P3 设备证据（插件管理页、插件详情、已授权的应用）

设备：模拟器 `laneI18nP3_api36`（emulator-5622，API 36，Google APIs Play 镜像，arm64），**系统语言是英文**，只用 `cmd locale set-app-locales org.agentos.app --locales zh|en` 切应用语言，每次切换后 `force-stop` 再启动。
包：AgentOS debug、三个示例 App 的 debug 包、`tests/device/mcp-plugin` 的测试插件、`tests/device/acp-channel` 的 client。界面文字用 `uiautomator dump` 读出核对。

造“被拒绝的插件”：**临时改了示例 App 的 manifest 再装到模拟器，改动没有提交**。
- 日历（`org.agentos.sample.calendar`）：去掉 `plugin.json` 的 `$schema`，加一个多余顶层字段 `extra`，名字写成大写带空格 → 插件被拒绝（`不可用：插件清单不符合要求` / `Unavailable: The plugin manifest does not meet the requirements`），问题列表三条。
- 闹钟（`org.agentos.sample.alarm`）：加一个 `mcp.json`，里面放 stdio、旧 SSE、`http://` 三种不支持的服务器 → 插件可用，但有三条“不支持”；详情页里“问题”和“不支持”两组各列一遍（这是原来的行为，没有改）。
- 测试插件（`org.agentos.test.mcp.plugin`）：带一个坏的 SKILL.md（没有 frontmatter）→ 详情页里的 Skill 问题。

已授权的应用：用 debug 接收器 `AcpCallerDebugReceiver`（`allow` / `deny`）造出“已允许”和“已拒绝”的记录；没有跑过真实的 prompt，所以用量都是 0（用量的文字有 JVM 测试覆盖，复数 `one` / `other` 各一例）。

| 文件 | 内容 |
|---|---|
| `{en,zh}-plugins-list.png` | 插件列表：App 标签做名字（`Alarm` / `闹钟`，`plugin.json` 里 `displayName` 写死的是中文“闹钟”，英文界面显示的是 App 自己的标签，D12），包名同行显示；“另有 3 项，点开查看”是复数资源 |
| `{en,zh}-plugin-detail-rejected.png` | 被拒绝插件的详情：状态、问题列表（位置 `plugin.json › 字段`，原因按语言渲染） |
| `{en,zh}-plugin-detail-problems.png` | 可用但有不支持项的插件详情 |
| `{en,zh}-plugin-enable-dialog.png` | 启用第三方插件的确认：写明“来自另一个 App”“默认关闭”“写操作每次确认、高风险每次都要确认”；插件名放在引号里，包名在括号里 |
| `{en,zh}-plugin-detail-tools.png` | 已启用插件的详情：服务状态、工具的风险（写操作 / Write）、审批方式、“设为始终允许” |
| `{en,zh}-always-allow-dialog.png` | “设为始终允许”的确认：说明工具会直接修改数据、行为由第三方决定 |
| `{en,zh}-plugin-detail-highrisk.png` | 高风险工具：`高风险（可能不可恢复）` / `High risk (may be irreversible)`，“每次确认（高风险，不能设为始终允许）” |
| `{en,zh}-authorized-apps.png` | 已授权的应用：已允许 / 已拒绝（含冷却说明）/ 最近使用 / 用量；`撤销授权` / `Revoke access` |
| `{en,zh}-authorized-revoke-dialog.png` | 撤销授权确认：名字、包名、签名、后果（立即断开、取消进行中的任务、10 分钟内再请求直接拒绝） |
| `{en,zh}-authorized-allow-dialog.png` | “改为允许”确认：可以让 AgentOS 替你回答问题；它用到的工具每次都会再问；只有确认来源可信才允许 |
| `{en,zh}-*-font1.3.png` | 系统字体 1.3 倍（`settings put system font_scale 1.3`，拍完恢复 1.0）：插件列表、被拒绝插件详情、启用确认、已授权的应用、撤销授权确认。英文全部换行、没有截断，按钮在屏内 |
