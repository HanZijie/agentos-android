# i18n P1 设备证据（确认框、授权框、错误提示）

设备：模拟器 `laneI18n_api36`（API 36，Google APIs Play 镜像，arm64），**系统语言是英文**，只用 `cmd locale set-app-locales org.agentos.app --locales zh|en` 切应用语言，每次切换后 `force-stop` 再启动（正在运行的界面不会重绘）。
构建：`./gradlew :app:assembleDebug -Pagentos.skipPiBundle=true`。触发：确认框 / 授权框用 debug 接收器 `ConsentDebugReceiver`（`inject`、`auth_inject`），错误提示是在聊天页没配置模型时真实发送一条消息（`model_not_configured`），通知是应用在后台时 `:agent` 发的。
下面的文字是 `uiautomator dump` 读出的界面文字，不是手抄。

| 文件 | 内容 |
|---|---|
| `en-consent-write.png` / `zh-consent-write.png` | 写类工具确认（第三方 App 发起）。en：`Changes data` · `Allow “note_create”?` · `Requested by com.example.notes` · `From plugin “notes” · server “notes”` · `Arguments` · `This action changes data on your phone.` · `… seconds left. No answer counts as Deny` · `Allow once` / `Don't ask again this chat` / `Always allow this tool` / `Deny`。zh：`会修改数据` · `要允许「note_create」吗？` · `由 com.example.notes 发起` · `来自插件「notes」 · 服务器「notes」` · `参数` · `这个操作会修改手机上的数据。` · `还剩 N 秒；不回答将按拒绝处理` · `允许一次` / `本次对话内不再询问` / `始终允许这个工具` / `拒绝` |
| `en-consent-high.png` / `zh-consent-high.png` | 高风险确认：只有 Allow once / Deny（允许一次 / 拒绝），没有“始终允许”；`High risk` / `高风险`，说明文字写明可能不可恢复 |
| `en-authorization.png` / `zh-authorization.png` | 第三方 App 授权（签名变了的版本，文字最长）：`Third-party app requests access` · `Allow “Notes” to use AgentOS?` · `Package: …` · `Signature: 7920 a1b2 c3d4…` · 签名变化警告 · 说明 · `Allow` / `Deny`；zh：`第三方 App 请求授权` · `允许「Notes」使用 AgentOS 吗？` · `包名：…` · `签名：…` |
| `en-error.png` / `zh-error.png` | 错误提示：`No model is configured yet` / `Open Settings, choose a model provider and enter your key`；zh：`还没有配置模型` / `到设置页选择模型厂商并填写 key` |
| `en-notification.png` | 后台时的确认通知（英文）：标题 `Allow “note_create”?`，按钮 `Deny` / `Allow once` |
| `*-font1.3.png` | 系统字体 1.3 倍（`settings put system font_scale 1.3`，看完恢复 1.0）：授权框、写类确认框、错误提示，英文都换行完整，没有截断，按钮都在屏内 |

## 设备上发现的一个坑（已修，需要整合人知道）

默认资源（`values/`）是中文、英文在 `values-en/` 时，**APK 里没有登记“有中文”**。系统解析多个语言时（`ResourcesImpl.updateConfiguration` 按 APK 里登记的语言挑最合适的）：
应用语言设成 `zh`、系统语言是 `en-US`，或系统语言列表是 `[zh-CN, en-US]`，都会改选 `values-en`——**中文用户看到英文**。在这台系统语言为英文的模拟器上，加了 `values-en` 之后设 `zh` 仍然是英文（`New chat` / `Settings`），实测复现。
修法：APK 里放一个带 zh 限定的资源，`res/xml-zh/agentos_locale_marker.xml`（默认版 `res/xml/agentos_locale_marker.xml`，内容不同，aapt2 不会去重）。**不能用 `values-zh/`**：lint 的 `MissingTranslation`（R9 里是 error）会把它当成中文翻译，要求每个 key 都有（实测 158 个错）。修完同一台设备上：`zh` → `新对话` / `设置`，`en` → `New chat` / `Settings`；`aapt2 dump configurations` 在 debug 和 release 里都有 `zh`。`LocaleSetupTest` 防回退。
五个示例 App 是同一个结构（默认中文 + `values-en`），**有同样的问题**，建议各加一份。

未验证：中文通知截图（只截了英文通知）；真机没有用（任务不要求）；非中英文系统语言的回落。
