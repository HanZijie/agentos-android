# 安装 AgentOS / Installing AgentOS

> 中文在前，英文在后（内容一致）。English follows the Chinese part.
>
> **脚本能做的**（`install.sh`）：检查文件、选手机、前检、刷入模块、重启、等 AgentOS App 装好、装示例 App。
> **脚本做不了、要你自己做的**：第 1–3 步（解锁、root、USB 调试）和第 5 步之后（授权、模型 key、启用插件、短信权限）。每一条都在下面。

# 中文

## 你需要什么

| | |
|---|---|
| 手机 | **已 root** 的 Android 15–17（API 35–37）。真机验证过的只有 **Pixel 8 · Android 15 · Magisk 30.7**；模拟器上跑过 Android 15 / 16 / 17。KernelSU、Android 16 / 17 真机、国内厂商 ROM **没有验证过** |
| root 管理器 | Magisk（最低 20.4）或 KernelSU。**不支持 APatch** |
| 电脑 | macOS / Linux / Windows（WSL 或 Git Bash）。要有 `adb`（Android platform-tools）、`unzip`；校验用 `sha256sum` / `shasum` / `openssl` 之一 |
| 空间 | 手机 `/data` 至少剩 300 MB |
| 文件 | Release 页的**所有文件**下载到**同一个文件夹**（缺一个脚本就会停下） |

> **“未验证设备（社区适配）”不是报错。** 支持矩阵里目前没有任何已登记的设备指纹（连 Pixel 8 也没有），所以前检和刷入时都会显示这一行。它只表示这台机型没被项目正式登记，不会阻止安装。

## 一、脚本解决不了的部分（先做完这些）

### 1. 解锁 Bootloader 并 root（只在没有 root 时需要）

脚本**不会**也**不能**替你做这一步。它因手机品牌和型号而不同，**本项目没有调研国内机型**（厂商是否允许解锁、怎么解锁、能不能 root，各家策略不一）。

- **Pixel / 原生 Android**：开发者选项里打开“OEM 解锁” → `fastboot flashing unlock`（**会清空手机所有数据**）→ 按 [Magisk 官方说明](https://topjohnwu.github.io/Magisk/install.html) 给 `boot` 或 `init_boot` 镜像打补丁并刷回。
- **KernelSU**：按 [KernelSU 官方说明](https://kernelsu.org/) 装 GKI 内核或 LKM。
- **其他品牌**：先确认你的机型能不能解锁 BL、有没有现成的 root 方案，再动手。**解锁和刷机有变砖风险，数据会被清空，请先备份。**

做完后，在手机上打开 Magisk（或 KernelSU）的 App，确认它显示“已安装”、版本不低于 20.4。

### 2. 打开 USB 调试

1. 设置 → 关于手机 → 连点 **版本号** 7 次，开启开发者选项。
2. 设置 → 系统 → 开发者选项 → 打开 **USB 调试**。
3. 用数据线连电脑，手机上弹出“**允许 USB 调试吗？**”，勾选“一律允许”并点 **允许**。

没点“允许”时，脚本会提示 `unauthorized`，这就是原因。

### 3. 给 adb shell 授权 root

脚本用 `su -c` 刷模块。第一次执行时，手机上会弹出 **“超级用户请求 / Superuser request”**（Magisk / KernelSU 的弹窗）：点 **允许**。弹窗大约几秒后会自己消失并按“拒绝”处理，所以**运行脚本时请盯着手机屏幕**。

如果一直没弹：打开 Magisk → 超级用户 → 找到 **Shell**，确认是“允许”；KernelSU 在“超级用户”页同样处理。

### 4. 记下“救援方法”（在你需要之前）

模块只含脚本，不往系统分区挂载任何东西，**设计上不会导致手机开不了机**。但万一出问题：

- **能进系统**：Magisk / KernelSU App 里关掉 AgentOS 模块，重启。
- **进不了系统，但 adb 能连**：`adb shell su -c 'touch /data/adb/modules/agentos/disable'`，然后 `adb reboot`。
- **adb 也连不上**：**Magisk 安全模式**——开机时按住音量下键，Magisk 会禁用所有模块；**KernelSU 安全模式**——开机时连续按几次音量下键。
- 这些方法**没有在本项目支持的所有机型上逐一验证**；前检会确认 root 能写 `/data/adb/modules`（救援方法 2 的前提）。

## 二、运行脚本

下载 Release 页的所有文件到同一个文件夹，打开终端：

```sh
cd 你下载的文件夹
sh install.sh                 # 只连了一台手机时
sh install.sh -s <序列号>     # 连了多台时（adb devices 能看到序列号）
```

常用选项（`sh install.sh --help` 看全部）：

| 选项 | 作用 |
|---|---|
| `--check-only` | 只做检查，不改手机 |
| `--samples all` / `--samples todo,sms` / `--samples none` | 选示例 App。**默认装闹钟、日历、备忘录、待办；短信只有你点名才装** |
| `--samples-only` | 模块已经刷过，只装示例 App |
| `--no-reboot` | 刷完不重启（模块要重启后才生效） |
| `-y` | 不再逐步确认（脚本要在没有终端的环境里跑时必须加） |
| `--no-verify` / `--skip-checks` | 跳过校验 / 前检（**不建议**） |

脚本做的事，按顺序：

1. 用 `SHA256SUMS` 校验每个文件。**校验失败会立刻停止，不会碰手机。**
2. 找手机（多台时要你用 `-s` 指定）。
3. 前检：Android 版本、root、root 管理器版本、空间、冲突模块、救援路径、手机上已有的 AgentOS 与 zip 的**签名是否一致**。
4. 让你确认（`-y` 可跳过）。
5. 刷入模块、重启、等开机；然后**读模块自己写的安装结果**（不是只看 App 在不在），最多等 3 分钟。
6. 装示例 App（`adb install -r`，不清数据）。

退出码：`0` 成功；`1` 失败；`2` 用法错误；`3` 模块已刷入但 AgentOS App 没装上（见下面“如果它没装上”）。

## 三、脚本之后，要你自己在手机上做的

这些**脚本做不到**，也不应该替你做。

1. **打开 AgentOS，完成首次引导**：选模型厂商、**填你自己的模型 key**（项目不提供 key）、允许通知、选默认助理、允许忽略电池优化。key 只保存在手机上。
2. **允许通知和电池优化**：否则后台任务可能被系统冻结，闹钟/日历提醒也可能延迟。国内厂商 ROM 还要在系统设置里允许“自启动 / 后台活动”，各家位置不同，**本项目没有逐家验证**。
3. **启用插件**：AgentOS → 设置 → 插件。**第三方插件默认关闭**，示例 App 也一样。闹钟、日历、备忘录、待办、短信都要你自己打开。
4. **闹钟 / 日历 / 通知权限**：示例 App 第一次打开时按提示授权。日历要“日历”权限才能读写系统日历（账号日历需要先在系统里登录账号，比如 Google）。
5. **短信（只有装了才要做）**：
   - 打开“短信”App，点“授予短信权限”。
   - **Android 15 起，不是从应用商店安装的 App 申请短信权限会被系统拦下**。这是系统保护，不是出错。按 App 页面上的三步：**应用信息 → 右上角 ⋮ → “允许受限制的设置”**（可能要验证锁屏密码）→ 回来再点“授予”。
   - 实测（debug 包，真机和模拟器）：用 `adb install` 装的包**不受这条限制**，本脚本用的就是这条路径；用文件管理器点安装的（模拟器上测的）会受限。**Release 签名的包没有单独测过**，如果你遇到限制，按上面三步做。不管哪种，都**不要用 root 去绕过**。
   - 没授权时它只在“仅撰写模式”：只能把草稿交给系统短信界面，由你自己点发送。
   - **发送短信永远每次都要你确认**；读短信的内容会进入对话历史并发给你配置的模型端点；验证码默认遮蔽。
6. **电脑端接入（可选）**：要在电脑上用 ACP 客户端连手机，在 AgentOS 设置里打开“电脑端接入”并按页面配对，见 [README](../README.md)。

## 四、从测试版升级（重要）

如果手机上已经装过 **debug 测试版**（比如自己用 `adb install` 装的），它和 Release 用**不同的签名证书**，Android 不允许直接覆盖：

- 模块**不会**卸载它、不会动它的数据，前检会报“签名不一致”，刷入后安装结果是 `sig_mismatch`，AgentOS App 不会被换掉。
- 要换成 Release 版，只能**先卸载旧的**（`adb uninstall org.agentos.app`）——**它的数据会一起删除**（对话、模型配置、配对）——然后再重启一次，模块会装上新版。
- 示例 App 同理：脚本会提示该 App `签名不同`，卸载旧的再重跑 `sh install.sh --samples-only`。
- **从 Release 到更新的 Release**（同一张证书）：直接覆盖，数据保留。**这条路径还没有在真机上演练过。**

## 五、如果它没装上 / 手机起不来

**脚本退出码 3，或重启后找不到 AgentOS App：**

```sh
adb -s <序列号> shell su -c 'tail -n 40 /data/adb/agentos/supervisor.log'
```

找 `install: org.agentos.app result=` 那行，常见值：

| result | 意思 | 怎么办 |
|---|---|---|
| `sig_mismatch` | 手机上已有签名不同的 AgentOS | 见上一节：先卸载旧的 |
| `corrupt` | zip 里的 APK 与清单的 SHA-256 不符 | 重新下载，重跑脚本 |
| `failed` | 安装失败，下次开机会重试 | 看这行后面的错误码，再重启一次；仍失败请提 Issue 并附这段日志 |
| `bad_list` | zip 的 `app/apks.list` 有问题 | 重新下载 |

模块在 Magisk / KernelSU App 里显示“[启动中]”之外的状态说明时，那一行就是它的原因。

**手机卡在开机 / 起不来：** 按上面第一部分第 4 条的救援方法。

**想彻底卸载：** 在 root 管理器里卸载模块（会停止监督进程并清掉 `/data/adb/agentos/`）。**AgentOS App 和示例 App 不会被自动卸载**，像普通 App 一样删除即可，数据随 App 一起删除。

## 提 Issue 时请附上

手机型号、Android 版本、root 方案和版本（如 Magisk 30.7）、脚本的完整输出、上面那段 `supervisor.log`。**不要附带模型 key 或短信内容。**

---

# English

> **What the script does** (`install.sh`): checks the files, picks the phone, pre-flight, flashes the module, reboots, waits for the AgentOS App, installs the sample apps.
> **What it cannot do and you must do yourself**: steps 1–3 (unlock, root, USB debugging) and everything after step 5 (grants, model key, enabling plugins, SMS permission). Each is listed below.

## What you need

| | |
|---|---|
| Phone | A **rooted** Android 15–17 (API 35–37). The only real device verified is a **Pixel 8 · Android 15 · Magisk 30.7**; emulators ran Android 15 / 16 / 17. KernelSU, Android 16 / 17 on a real phone and Chinese OEM ROMs are **not verified** |
| Root manager | Magisk (20.4 or newer) or KernelSU. **APatch is not supported** |
| Computer | macOS / Linux / Windows (WSL or Git Bash) with `adb` (Android platform-tools), `unzip`, and one of `sha256sum` / `shasum` / `openssl` |
| Space | at least 300 MB free on the phone's `/data` |
| Files | **All files** of the release page in **one folder** (the script stops if one is missing) |

> **"Unverified device (community support)" is not an error.** The support matrix lists no device fingerprint yet (not even the Pixel 8), so the pre-flight and the flash always print this line. It only means the model is not officially registered; it does not block the install.

## 1. What the script cannot do (do these first)

### 1. Unlock the bootloader and root (only if the phone is not rooted)

The script **does not and cannot** do this. It differs by brand and model, and **this project has not researched Chinese OEM phones** (whether the vendor allows unlocking, how, and whether root is possible differs by brand).

- **Pixel / stock Android**: enable "OEM unlocking" in developer options → `fastboot flashing unlock` (**wipes all data on the phone**) → patch the `boot` or `init_boot` image following the [official Magisk guide](https://topjohnwu.github.io/Magisk/install.html) and flash it back.
- **KernelSU**: follow the [official KernelSU guide](https://kernelsu.org/) (GKI kernel or LKM).
- **Other brands**: first confirm your model can be unlocked and has a known root method. **Unlocking and flashing can brick the phone and wipes data: back up first.**

Afterwards open the Magisk (or KernelSU) app and confirm it shows "installed", version 20.4 or newer.

### 2. Turn on USB debugging

1. Settings → About phone → tap **Build number** 7 times to enable developer options.
2. Settings → System → Developer options → turn on **USB debugging**.
3. Connect the cable; the phone asks "**Allow USB debugging?**": tick "Always allow" and tap **Allow**.

If you did not tap "Allow", the script reports `unauthorized`: that is why.

### 3. Grant root to the adb shell

The script flashes with `su -c`. On the first run the phone shows a **Superuser request** (Magisk / KernelSU dialog): tap **Grant**. The dialog disappears after a few seconds and counts as "Deny", so **watch the phone screen while the script runs**.

If it never appears: Magisk → Superuser → find **Shell** and make sure it is "Allow"; KernelSU has the same page.

### 4. Learn the rescue path (before you need it)

The module is scripts only and mounts nothing into the system partitions, so **by design it cannot stop the phone from booting**. If something goes wrong anyway:

- **System boots**: switch the AgentOS module off in the Magisk / KernelSU app and reboot.
- **System does not boot but adb works**: `adb shell su -c 'touch /data/adb/modules/agentos/disable'`, then `adb reboot`.
- **adb does not work either**: **Magisk safe mode**: hold volume-down while booting (Magisk disables all modules); **KernelSU safe mode**: press volume-down several times during boot.
- These methods have **not been verified on every supported model**; the pre-flight confirms that root can write `/data/adb/modules` (the precondition of method 2).

## 2. Run the script

Download all files of the release page into one folder, open a terminal:

```sh
cd the-folder-you-downloaded-to
sh install.sh                 # when exactly one phone is connected
sh install.sh -s <serial>     # with several phones (adb devices shows the serials)
```

Common options (`sh install.sh --help` lists all):

| Option | Effect |
|---|---|
| `--check-only` | only check, change nothing on the phone |
| `--samples all` / `--samples todo,sms` / `--samples none` | choose sample apps. **Default: alarm, calendar, notes, todo; the messages app is installed only when you name it** |
| `--samples-only` | the module is already flashed, install only the sample apps |
| `--no-reboot` | do not reboot after flashing (the module only takes effect after a reboot) |
| `-y` | do not ask for confirmation (required when there is no terminal) |
| `--no-verify` / `--skip-checks` | skip checksums / pre-flight (**not recommended**) |

In order, the script:

1. Verifies every file against `SHA256SUMS`. **A mismatch stops it at once, before touching the phone.**
2. Finds the phone (several phones: pick one with `-s`).
3. Pre-flight: Android version, root, root manager version, space, conflicting modules, rescue path, and whether the AgentOS already on the phone is **signed with the same certificate** as the zip.
4. Asks you to confirm (`-y` skips it).
5. Flashes the module, reboots, waits for boot, then **reads the module's own install result** (not just "is the App there"), up to 3 minutes.
6. Installs the sample apps (`adb install -r`, no data cleared).

Exit codes: `0` ok; `1` failed; `2` bad usage; `3` module flashed but the AgentOS App did not appear (see "If it does not work" below).

## 3. After the script: what you do on the phone

The script **cannot** do these and should not.

1. **Open AgentOS and finish the first-run guide**: pick a model provider, **enter your own model key** (the project does not provide one), allow notifications, choose the default assistant, allow ignoring battery optimisation. The key stays on the phone.
2. **Allow notifications and battery optimisation**, or background work may be frozen and alarm / calendar reminders delayed. Chinese OEM ROMs also need "auto-start / background activity" allowed in system settings; the location differs by brand and **has not been verified per brand**.
3. **Enable the plugins**: AgentOS → Settings → Plugins. **Third-party plugins are off by default**, sample apps included. Turn on alarm, calendar, notes, todo and messages yourself.
4. **Alarm / calendar / notification permissions**: grant them when the sample app asks on first run. The calendar needs the Calendar permission to read and write the system calendar (an account calendar needs the account signed in in system settings first, e.g. Google).
5. **Messages (only if installed)**:
   - Open the Messages app and tap "Grant SMS permission".
   - **From Android 15, SMS permissions of an app not installed from an app store are blocked**. That is Android protecting you, not an error. Follow the three steps on the app's page: **App info → ⋮ at the top right → "Allow restricted settings"** (it may ask for your screen-lock) → come back and tap "Grant".
   - Measured (debug build, real phone and emulator): a package installed with `adb install` is **not** under that restriction, and that is the path this script uses; one installed by tapping it in a file manager (tested on an emulator) is. **A release-signed build was not tested separately**: if you hit the restriction, follow the three steps above. Either way, **do not work around it with root**.
   - Without the permission it stays in "compose only" mode: it can only hand a draft to the system messaging screen, and you press send yourself.
   - **Sending always asks for your confirmation**; message content goes into the chat history and to your model endpoint; verification codes are masked by default.
6. **Desktop access (optional)**: to connect an ACP client on your computer, turn on "Desktop access" in AgentOS settings and pair; see the [README](../README.md).

## 4. Upgrading from a test build (important)

If a **debug test build** is already on the phone (for example installed with `adb install` yourself), it is signed with a **different certificate** than the release, and Android refuses to install over it:

- The module **does not** uninstall it and **does not** touch its data. The pre-flight says "signed with a different certificate"; after flashing the install result is `sig_mismatch`, and the AgentOS App is not replaced.
- To switch to the release you must **uninstall the old one first** (`adb uninstall org.agentos.app`) - **its data is deleted with it** (chats, model config, pairings) - and reboot once; the module then installs the new one.
- Sample apps are the same: the script says that app has a different signature; uninstall it and run `sh install.sh --samples-only` again.
- **Release to a newer release** (same certificate): installs over, data kept. **This path has not been rehearsed on a real phone yet.**

## 5. If it does not work

**Exit code 3, or no AgentOS App after the reboot:**

```sh
adb -s <serial> shell su -c 'tail -n 40 /data/adb/agentos/supervisor.log'
```

Look for the `install: org.agentos.app result=` line. Common values:

| result | Meaning | What to do |
|---|---|---|
| `sig_mismatch` | an AgentOS with a different signature is on the phone | see the previous section: uninstall it first |
| `corrupt` | an APK in the zip does not match its manifest SHA-256 | download again, run the script again |
| `failed` | install failed, retried at the next boot | read the error code after it, reboot once more; if it still fails open an Issue with this log |
| `bad_list` | `app/apks.list` in the zip is broken | download again |

**The phone hangs while booting:** use the rescue path (part 1, step 4).

**To remove everything:** uninstall the module in the root manager (it stops the supervisor and removes `/data/adb/agentos/`). **The AgentOS App and the sample apps are not uninstalled automatically**: delete them like any app; their data goes with them.

## When you open an Issue

Include the phone model, Android version, root method and version (e.g. Magisk 30.7), the full script output and the `supervisor.log` lines above. **Do not include a model key or message content.**
