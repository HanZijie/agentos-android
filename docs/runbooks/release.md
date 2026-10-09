# 发布流程（维护者）

> 这份是给**发版的人**看的，不是给用户的（用户看 [install.md](../install.md)）。W28 的 `release.yml` 做完之前，发版是手工的：这里写手工怎么做、做之前怎么检查。
> **什么只有维护者能做**：用项目发布证书签名（私钥在仓库外）、推 tag、在 GitHub 上建 Release 并上传。AI Agent 不碰私钥，也不自己公开发布。

## 0. 前提

- 项目发布证书已经生成并离线备份（README / `docs/development.md` “项目发布证书”）。**证书一旦发布就不能换**：换了，已安装的用户模块会因签名不一致停止升级。
- 版本号：`gradle.properties` 的 `agentos.version` / `agentos.versionCode`，模块 zip、AgentOS App、五个示例 APK **共用**（D10）。发版前确认它们是这次要发的版本。
- `docs/release-notes/<version>.md` 存在，中英文都有，**“已知限制 / Known limitations” 如实**。`package-samples.py` 和 `package-release.py` 都会检查。
- 想发的提交已经在 `main` 上，CI 全绿，真机测试做完（AGENTS.md 第 4 节）。

## 1. 先做一次不签名的干跑

不需要证书，确认流程和文件齐全：

```sh
python3 tools/package-release.py --allow-unsigned --out /tmp/agentos-dryrun
python3 tools/package-release.py --check /tmp/agentos-dryrun
```

干跑的文件名带 `-unsigned`，`install.sh` 会拒绝它们，**不能发布**。

## 2. 签名构建

```sh
export AGENTOS_SIGNING_STORE_FILE=~/.agentos/signing/agentos-release.p12
export AGENTOS_SIGNING_KEY_ALIAS=agentos-release
read -rs AGENTOS_SIGNING_STORE_PASSWORD && export AGENTOS_SIGNING_STORE_PASSWORD
python3 tools/package-release.py --out dist-release        # --out 必须是空目录
```

脚本做的事：打包 pi-agent（`core/pi-runtime`）→ `package-module.py`（AgentOS App 签名、模块 zip）→ `package-samples.py`（五个示例 APK 签名，证书必须等于 AgentOS App 的，权限与 `package-samples.permissions.json` 完全一致，release 里没有 debug 接收器）→ 组装文件夹 → 统一的 `SHA256SUMS` → 自检。**任何一步失败都不会产出文件夹。**

**证书核对**（把指纹写进 Release 说明，用户可以对照）：

```sh
"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs dist-release/AgentOS-Sample-todo-*.apk | grep SHA-256
```

## 3. 在一台没有 root 的流程之外，至少再验一遍

1. `cd dist-release && sha256sum -c SHA256SUMS`（macOS：`shasum -a 256 -c SHA256SUMS`）全部 `OK`。
2. `sh install.sh --check-only -s <专用测试机序列号>`：前检通过。
3. **在专用测试机上**完整跑一遍：`sh install.sh -s <序列号>`，然后在手机上走完 `docs/install.md` 第三部分。**这会刷模块、重启，不要在日常用的手机上做**（AGENTS.md 4.1）。
4. 从上一个版本覆盖安装：插件无需重新确认签名、数据保留（发布前检查清单里的一条，**之前一直没在真机上做过**）。
5. 英文和中文系统各看一遍确认框、授权框、设置页。

## 4. 建 tag 并上传

```sh
git tag -a v<version> -m "AgentOS <version>"      # 打在已经通过 CI 的 main 提交上
git push origin v<version>
gh release create v<version> dist-release/* \
  --title "AgentOS <version>" --notes-file docs/release-notes/<version>.md
```

- 文件**全部**上传（`install.sh`、`check-device.sh`、`INSTALL.md`、`RELEASE-NOTES.md`、`SHA256SUMS`、`build-info.json`、模块 zip、五个 APK）。少一个，`install.sh` 会停。
- 先用 `--draft` 建草稿，在网页上看一遍，再发布。
- `gh release create` 之前先看一眼 `gh auth status` 登录的是不是你要的账号。

## 5. 发布后

- 用**从 GitHub 下载下来的文件**（不是本地的 `dist-release/`）在测试机上再装一次，确认下载没坏。
- Release 说明里写清楚：这是**原型**；**已验证的只有 Pixel 8 · Android 15 · Magisk 30.7**；**短信 App 需要“允许受限制的设置”**；不是默认短信应用。
- 出问题的回滚：Release 页把它标成 pre-release 或删除；已经装了的用户按 `docs/install.md` “想彻底卸载” 处理。**不要重打同一个 tag**（用户已经下载了旧文件）。

## 已知缺口（如实）

- 没有 `release.yml`（W28）：签名构建只能在维护者本机做。
- 示例 APK 不保证字节级可复现，模块 zip 是确定性的。
- `verified_fingerprints` 现在是空的：用户的前检会显示“未验证设备（社区适配）”。登记指纹要真机验收之后再改 `module/support-matrix.yaml`，不要为了让提示消失而先加。
