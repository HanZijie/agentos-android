<div align="center">

<img src="docs/assets/brand/banner.svg" alt="AgentOS for Android" width="100%">

<p>
  <a href="README.md">简体中文</a> &nbsp;|&nbsp; <b>English</b>
</p>

<h3>Let apps call the Agent — and let apps give the Agent its capabilities.</h3>

<p>An Agent can be an entry point, and it can also be a basic system service.<br>
Many apps share one Agent runtime instead of each embedding a full Agent.</p>

<p>
  <a href="#status-and-boundaries"><img alt="Status: prototype" src="https://img.shields.io/badge/status-prototype-f97316?style=flat-square"></a>
  <a href="#quick-start"><img alt="Android 15+" src="https://img.shields.io/badge/Android-15%2B-3ddc84?style=flat-square&logo=android&logoColor=white"></a>
  <a href="#architecture-and-design"><img alt="Protocols: ACP · MCP" src="https://img.shields.io/badge/protocol-ACP%20%C2%B7%20MCP-8b5cf6?style=flat-square"></a>
  <a href="#quick-start"><img alt="Deploy: root module" src="https://img.shields.io/badge/deploy-root%20module-475569?style=flat-square"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/github/license/HanZijie/agentos-android?style=flat-square&color=blue"></a>
</p>

<p>
  <a href="#demos">Demos</a> &nbsp;·&nbsp;
  <a href="#quick-start">Quick start</a> &nbsp;·&nbsp;
  <a href="#for-developers">For developers</a> &nbsp;·&nbsp;
  <a href="#architecture-and-design">Architecture</a> &nbsp;·&nbsp;
  <a href="#status-and-boundaries">Status</a> &nbsp;·&nbsp;
  <a href="#documentation">Docs</a> &nbsp;·&nbsp;
  <a href="#contributing-and-feedback">Contributing</a>
</p>

</div>

---

## Core capabilities

<table>
<tr>
<td width="50%" valign="top">
<a href="#demo-app-to-agent"><img src="docs/assets/brand/card-app-to-agent.svg" alt="App → Agent: an app calls the Agent over ACP" width="100%"></a>
<p><b>Apps call the Agent</b></p>
<p>An app starts a task over ACP, the Agent handles it, and progress and results come back to the app that started it. The app embeds no Agent and needs no calendar or alarm permissions or model key of its own.</p>
<p><sub>Integration: <code>acp-android</code> SDK · Binder · authorized by the user on first use, revocable at any time</sub></p>
</td>
<td width="50%" valign="top">
<a href="#demo-agent-to-app"><img src="docs/assets/brand/card-agent-to-app.svg" alt="Agent → App: the Agent calls tools that apps expose over MCP" width="100%"></a>
<p><b>Apps give the Agent capabilities</b></p>
<p>An app exposes its abilities as MCP tools through a plugin, and the Agent calls them during a task. Write operations ask for confirmation first, listing the tool, its source and its arguments. Cross-app actions go through MCP, not simulated taps on the screen.</p>
<p><sub>Integration: <code>plugin-sdk</code> embedded plugin · Binder · no interpreter needed</sub></p>
</td>
</tr>
</table>

One app can play both roles. Users start tasks from the app they are already in, without first going to a central chat entry.

> [!NOTE]
> This is a **prototype**. It is deployed as a Magisk / KernelSU module on a rooted Android phone, with no ROM change. Root is the current way to deploy and supervise the process, not the point of the project. Both integration paths have real-device demos, but the sample apps are provided by this project and permission control for third-party apps is still coarse. See [Status and boundaries](#status-and-boundaries).

---

## Demos

Two real-device demos, one for each relationship above. The recordings were made with the Chinese UI, and quoted UI text is translated.

<a id="demo-app-to-agent"></a>

### Main demo: App → Agent

The Notes app is installed after the fact. It holds a note; one tap on a button hands the text to AgentOS, and the Agent reads the time and creates a calendar event and an alarm. Notes itself has no calendar or alarm permission, and no model key.

[![Key frames: confirm the text to send in Notes, authorize on first use, the result returns to Notes, and the event appears in the calendar. Click to watch the full recording](docs/assets/demo3-stills.png)](docs/assets/demo3.mp4)

Key frames, left to right: after tapping the AgentOS button, confirm the text to send; **on first use, AgentOS asks "Allow 'Notes' to use AgentOS?"** (showing the package name and signature digest, with focus on "Deny" by default); back in Notes, the panel shows 1 event and 1 alarm created; in the calendar, "Meeting with Mr. Wang (Room 3)" on Saturday at 15:00.

Click the image to watch the full recording (real device, real time, not sped up, 44 seconds): [demo3.mp4](docs/assets/demo3.mp4). The flow: open the note "This week's plan" → tap the AgentOS button → confirm the text → authorize on first use and tap Allow → back in Notes to see the result → jump to the calendar and to the alarm clock (a weekly "Running" alarm at 07:00 on Mondays) to check.

> **What this demo shows:** an app does not need to embed an Agent to get cross-app task handling from a shared runtime. The task starts in the original app, and progress and results come back to it.

A few things to know:

- Notes plays both roles: it calls the Agent through the `acp-android` SDK, and through an embedded plugin it offers its own note tools to the Agent. It is a sample app from this project, not an outside third party.
- Authorization is asked once. You can revoke it later in AgentOS settings, and revoking cancels that app's running tasks immediately.
- Notes only requested two tools from the Agent, `event_create` and `alarm_create`. That is the minimal scope it chose for itself, to guard against prompt injection hidden in note text. AgentOS does not force third parties to do this (see [Status and boundaries](#status-and-boundaries)).
- For the recording, the calendar and alarm creation tools were set to "always allow", so there are no per-call confirmation dialogs. Without that setting, every creation still asks for confirmation.

Design and acceptance: [docs/third-party-acp.md](docs/third-party-acp.md) (Chinese).

<a id="demo-agent-to-app"></a>

### Second demo: Agent → App

This one goes the other way. Type an instruction into AgentOS's own UI:

> 给我设置一个备忘录，记录一下下周我需要写三个 prd，并且在下周四下午 3 点需要开需求评审会。在需求评审会之前，提前半个小时设置闹钟提醒我参会

*(Translation: "Create a note saying I need to write three PRDs next week, and that I have a requirements review meeting next Thursday at 3 p.m. Set an alarm 30 minutes before the review to remind me.")*

Through the MCP tools of three sample apps, the Agent creates a note, a calendar event and an alarm. Calls that need confirmation first show a dialog with the tool name, the source plugin and the arguments, and run only after the user confirms.

<table>
<tr>
<th align="center">One sentence in AgentOS</th>
<th align="center">The results in the three apps</th>
</tr>
<tr>
<td align="center"><img src="docs/assets/demo.gif" width="300" alt="Typing the instruction in AgentOS, confirming tool calls, then looking at settings and plugin management"></td>
<td align="center"><img src="docs/assets/demo2.gif" width="300" alt="The matching event, alarm and note appear in the calendar, alarm and notes apps"></td>
</tr>
</table>

Results:

- Notes: a note titled "Next week's work memo" is created.
- Calendar: a "Requirements review" event is created for Thursday at 15:00.
- Alarm: a "Requirements review reminder" alarm is created for 14:30.

> **What this demo shows:** apps can offer capabilities to the Agent through explicitly declared tools. The Agent breaks a natural-language task into tool calls, and the results are stored in each app. Cross-app actions go through MCP, not simulated taps on the screen.

> [!NOTE]
> All three apps are sample apps from this project, already wired to plugins and MCP. This demo does not mean AgentOS can directly operate arbitrary Android apps that have not been adapted.

<details>
<summary>Recording notes and key frames</summary>

<br>

The second half of the recording shows the settings page (model and key, security level) and plugin management (discovered plugins). In the GIFs, typing is sped up 4× and the rest 2×, with real time around the send tap. Full recordings at normal speed (720p): [demo.mp4](docs/assets/demo.mp4), [demo2.mp4](docs/assets/demo2.mp4).

Key frames, left to right: the instruction typed before sending; the Thursday 15:00 review in the calendar; the 14:30 alarm; the "Next week's work memo" note.

![Key frames: the instruction before sending, and the results in the calendar, alarm and notes apps](docs/assets/demo-stills.png)

</details>

---

## Quick start

> [!IMPORTANT]
> You need a **rooted** Android 15–17 phone with Magisk or KernelSU installed. The combination verified on a real device is a Pixel 8 (Magisk 30.7, Android 15). Android 15 / 16 / 17 have run on emulators. KernelSU and Android 16 / 17 real devices are not verified yet.

**1. Build the module zip.** There is no published release yet, so build it from source (JDK 21, Android SDK and Node 22.19+; see the [development guide](docs/development.md), in Chinese):

```bash
python3 tools/package-module.py --variant debug   # output: build/module/agentos-<ver>.zip
```

**2. Flash and reboot.** Flash `agentos-<ver>.zip` in your root manager, then reboot. The module installs the AgentOS app and the Runner and starts the Agent runtime.

**3. Finish first-run setup.** Open the AgentOS app: pick a model vendor and enter a key (presets such as MiniMax, or a custom compatible endpoint), allow notifications, choose the default assistant, allow ignoring battery optimization, and look over the discovered plugins.

After that you can long-press the power button to bring up the assistant, or call it over ACP from other apps.

---

## For developers

The two directions are independent. An app can do either one, or both.

### Call the Agent

Verified on a real device (Android 15). Add the `acp-android` SDK to your app, open a session, send a prompt and receive streamed events. On the first call, the user authorizes it in AgentOS. The SDK is currently the Gradle module `:sdk:acp-android` in this repository and is not published to Maven. It is built on the official ACP Kotlin SDK (pinned at 0.30.1) plus a Binder transport.

```kotlin
// Call inside a coroutine. Failures throw AgentOsException (not installed, denied, no model configured, rate limit, ...).
if (AgentOs.isInstalled(context)) {
    val connection = AgentOs.connect(context) { waiting -> /* waiting for the user to authorize in AgentOS */ }
    val session = connection.newSession(
        toolScope = listOf(ToolRef("calendar", "event_create"), ToolRef("alarm", "alarm_create")), // optional: narrow it yourself
    )
    session.prompt("Meeting with Mr. Wang tomorrow at 3 p.m.").collect { event ->
        when (event) {
            is AgentOsEvent.Text -> { /* text from the Agent */ }
            is AgentOsEvent.ToolCall -> { /* tool state: awaiting confirmation / running / done / denied / failed */ }
            is AgentOsEvent.Done -> { /* finished */ }
        }
    }
}
```

A complete example is in [`plugins/samples/notes`](plugins/samples/notes). The interface contract is in section 4.7 of [docs/third-party-acp.md](docs/third-party-acp.md) (Chinese).

### Give capabilities to the Agent

Verified on emulators and real devices; all three sample apps use this path. Embed a standard Agent Plugins 1.0 plugin in your app under `assets/agent-plugin/` (`plugin.json`, `skills/`, `mcp.json`, Hooks). Implement the MCP server with `plugin-sdk` inside your own app process and declare it under `extensions."org.agentos"` in `plugin.json`. AgentOS connects over Binder, with no interpreter needed.

- **Distributing plugin packages:** standard plugin packages (zip) can be imported into AgentOS directly. Their MCP servers must be remote Streamable HTTP (`https://`); Hooks must be command-type and run with the phone's own `sh`.
- **Debugging from a computer:** connect any ACP client to the Agent on the phone through `adb forward`.
- **Sample apps:** `plugins/samples/` has alarm, calendar and notes. Tool lists and builds are in [docs/sample-apps.md](docs/sample-apps.md) (Chinese).

For building, testing, packaging and the release certificate, see the [development guide](docs/development.md) (Chinese).

---

## Architecture and design

![AgentOS architecture](docs/assets/architecture.svg)

### Let entry points, capabilities and the runtime evolve separately

| Role | Responsibility |
|---|---|
| **Task entry** | Receives the user's intent. It can be a notes app, a calendar, a standalone assistant or any other client |
| **Capability provider** | Does the actual work. Apps or services expose tools and keep their own business logic and data |
| **Agent runtime** | Handles model calls, task execution and sessions, and enforces authorization, confirmation and audit rules during tool calls |

The split exists so that an app does not have to maintain a full runtime just to use an Agent, and so that adding a capability does not always mean changing the main Agent program. AgentOS can have its own chat UI, but it should not be the only way to use the Agent.

### ACP, MCP, Plugin and Skill solve different problems

| Part | Role |
|---|---|
| ACP | Connects the task initiator to the Agent; carries the session, input and execution progress |
| MCP | Connects the Agent to capability providers; carries tool calls and results |
| Plugin | Organizes and distributes tool configuration, Skills, Hooks and other extension content |
| Skill | A guide written for the model, describing how to use existing tools for a class of tasks. It grants no permission by itself |

An app can request Agent service over ACP, and it can offer MCP tools to the Agent through a plugin. These are two independent directions, and an app does not have to implement both.

<details>
<summary><b>Why are tools and Skills separate? Why does ACP run over Binder?</b></summary>

<br>

**Keeping tools and Skills apart lets their interface and usage evolve independently.** Tools must be stable, verifiable and authorizable, so every tool has a schema, a risk level and a confirmation flow, and every call is logged. Operating experience is written as Skills, and changing a sentence needs no release. Conversely, hiding a capability inside a Skill's script, or stuffing a procedure into a tool description, blurs the permission boundary. AgentOS handles it this way: inside one plugin, MCP and Skills take two separate paths. Tool calls go through the Extension Host, risk policy and user confirmation. A Skill is read as text through `read_skill` and handed to the model as untrusted input; if it ships a script to run, that must still go through the shell tool, the Runner and confirmation. Details are in [docs/extensions.md](docs/extensions.md) (Chinese).

**On a phone, ACP looks more like an invocation protocol for a system service.** In editors and terminals, ACP is usually the wiring layer of an Agent shell, and the Agent is a child process started by the client. When the Agent stays resident on the phone, has an identity and state, and can work across apps, an app no longer "integrates an Agent" but "requests the Agent's service" from the system. The caller's identity comes from the kernel (the Binder caller UID), the user decides whether a call is allowed, usage is visible, and access can be revoked at any time. That is why ACP runs over Binder in AgentOS, and only debugging from a computer uses the stdio semantics of `adb forward`.

</details>

### A shared runtime does not mean shared context and permissions

Several apps using the same Agent service does not mean they can read each other's sessions.

The design goal is for the runtime to identify the caller and isolate session boundaries, and for the user's authorization and confirmation to decide whether a tool may be used. The model can propose an action, but it cannot gain extra permission from the text it generates.

Already in place: caller identification, session isolation, first-use authorization and revocation, rate limits, and confirmation of write operations. **Finer-grained permissions per app and plugin are still being designed:** a third-party app that has been authorized can, by default, use the tools of all enabled plugins. The trade-off and its risk are described in [Status and boundaries](#status-and-boundaries).

### Implementation and key trade-offs

The AgentOS runtime is a separate process, `:agent`, inside the AgentOS app, not part of Android's `system_server`. The outer layer is a Kotlin host that handles ACP access, identity, storage, scheduling and recovery. The Agent loop reuses the upstream **Pi Agent core** (`@earendil-works/pi-agent-core`), which runs in an embedded QuickJS inside the process.

| Choice | Reason and cost |
|---|---|
| A root module instead of a custom ROM | The earlier prototype built the Agent into the system image, so users had to compile and flash it themselves. As a module it runs on an existing device after flashing one zip, which makes deployment and iteration much cheaper. Cost: it still needs a rooted device, and unrooted phones do not get the same capability |
| Reuse the Pi Agent core instead of writing our own Agent loop | Spend the effort on the Android host, cross-app integration and task governance. Cost: Pi is still 0.x and its API changes quickly, so the version is pinned and upgrades are evaluated separately |
| Run the Agent core in QuickJS | The APK brings its own JS runtime, so Node is not needed on the phone. Cost: the Node-dependent parts of the official SDK have to be replaced at packaging time. Model networking, keys and retries stay in the Kotlin host, so keys never enter QuickJS |
| Carry ACP and MCP over Binder | The caller UID comes from the kernel, and ACP and MCP share one message channel. Protocol semantics are kept separate from the Android transport, so debugging from a computer can still use `adb forward` |
| Tool calls are confirmed in AgentOS's own UI | A calling app can only add a denial, never approve on the user's behalf. Otherwise a malicious app could use the Agent to act on other apps or on user data |
| Root only installs, supervises and launches | AgentOS's code, models and plugins do not run as root. Cost: the device is already rooted, so other root apps may still read AgentOS's data. The security level is therefore fixed at `best_effort`, and the settings page says so honestly |

This repository continues the earlier [agenroid prototype](https://github.com/HanZijie/agenroid). Moving from a system image to a root module was meant to validate the product relationship on existing devices first, and does not mean real system-level integration is done. The full component relationships, protocols and decision records are in the [architecture document](docs/architecture.md) (Chinese).

<details>
<summary><b>Repository layout</b></summary>

```text
agentos-android/
├── core/      # contracts · protocol (ACP Profile, Binder message channel, Hooks, Agent Plugins schema) · runtime (Kotlin host and Pi adapter) · pi-runtime (Pi Agent core bundled into the APK) · extensions
├── app/       # AgentOS app: main process (UI, confirmation, settings) · :agent (runtime, ACP entry) · :ext (Extension Host)
├── runner/    # Runner: separate UID; runs Hooks, the shell tool and Skill scripts with sh
├── sdk/       # binder-channel · acp-android (for installed-later apps) · plugin-sdk (embed a plugin in an app)
├── module/    # scripts of agentos.zip: pre-install checks, root supervisor, safe mode
├── plugins/   # samples (sample apps with embedded plugins)
├── tools/  reference/  tests/  docs/  .github/
```

Module and package names are listed in the [development guide](docs/development.md#模块与源码位置) (Chinese).

</details>

---

## Status and boundaries

AgentOS is currently a prototype for validating the product shape and the technical path.

![AgentOS progress: foundation and M1 done, M2 to M5 in progress, M6 not started](docs/assets/progress.svg)

| Status | Details |
|---|---|
| Real-device demos | The later-installed sample Notes app starts a task over ACP, authorizes on first use, and the event and alarm results come back to Notes; a task started in AgentOS operates the Notes, Calendar and Alarm sample apps through MCP |
| Passed real-device acceptance (Pixel 8, Magisk 30.7, Android 15) | The full module lifecycle: install, reboot, disable, enable, uninstall; ACP conformance tests on a computer (38 cases: 33 passed, 0 failed, 5 skipped); automated acceptance of the third-party ACP flow (34 checks, all passed in two consecutive runs, covering authorization, denial, revocation and prompt injection) |
| Not verified yet | KernelSU; Android 16 / 17 real devices; screen-off and 24-hour residency; third-party apps other than this project's samples; conformance tests for the third-party channel; "first conversation within 10 minutes of flashing" (needs timing with beta users) |
| Not implemented yet | Third-party permissions per (app, plugin, action); publishing `acp-android` to Maven |

Detailed verification records are in the [acceptance checklist](docs/m1-acceptance.md), and the full plan is in the [implementation plan](docs/implementation-plan.md) (both in Chinese).

### How to read this prototype

**"Local Agent" means the runtime is on the phone, not that model inference is fully on the phone.** Inference currently comes from a model API that the user configures.

**"Apps can integrate" does not mean "any app can be operated directly".** Callers need to integrate ACP, and capability providers need to expose the matching plugin or tool interface.

**"System-service shape" is a direction being explored, not the current permission level.** The runtime currently lives in an ordinary app's process. It is not registered in `system_server` and does not have a full system-level trust boundary.

**The security level on a rooted device is `best_effort`.** AgentOS does not run the Agent as root, but it cannot stop other root apps on the device from reading its data.

**Third-party app permissions are currently "open by default once authorized", not least privilege.** The constraints in place: the user must allow the app in AgentOS on first use and can revoke at any time, and revoking cancels its running tasks; sessions are isolated per caller; each app can have only one running task at a time, at most 30 per hour, and at most 16,000 characters of text per task; write operations are confirmed under the same rules as AgentOS's own. But once approved, an app can by default have the Agent use every tool of every enabled plugin: read tools show no confirmation, and write tools the user set to "always allow" no longer ask. This means an authorized app may be able to read, through the Agent, data of other apps that it could not read itself. A caller can narrow its own scope with `toolScope` (the sample Notes app does), but AgentOS does not enforce it today. The plan for per-(app, plugin, action) authorization and the questions that still need decisions are in [section 9 of the third-party integration design](docs/third-party-acp.md#9-权限管控机制设计讨论不实现) (Chinese). It is under discussion only, not implemented.

**Out of scope:** flashing ROMs, AOSP integration, a `system_server` service, platform signing, and unrooted devices.

<details>
<summary><b>Milestones</b></summary>

<br>

Work packages start by dependency and do not wait for the previous milestone to be accepted. Milestones are acceptance checkpoints in the order M1 → M2 → M3a → M3b ∥ M4 → M5. See the [dependency graph](docs/implementation-plan.md#4-依赖顺序) (Chinese).

| Milestone | Goal |
|---|---|
| Foundation | Repository and build, runtime host, Pi Agent core integration, ACP Agent side, Binder channel (the old M0 was dissolved; validation items are inserted before the work they unlock) |
| M1 One zip, one conversation | First conversation within 10 minutes of flashing the zip; an ACP client on a computer can converse and cancel |
| M2 Reliability | Durable commit and recovery, stronger supervision and safe mode, offline and upgrade handling |
| M3a Entry and local plugins | Assistant entry, Extension Host, app-embedded plugins (MCP over Binder), AgentOS's own plugins, tool confirmation |
| M3b Plugin packages, Skills, Hooks, remote MCP | Import standard plugin packages, remote Streamable HTTP MCP, Skills, command Hooks, the Runner, the shell tool |
| M4 Open ACP to the outside | Installed-later apps call through the SDK; authorization, limits, session isolation |
| M5 Developer ecosystem and release | plugin-sdk annotations and templates, KernelSU, device matrix, official release |
| M6 Advanced | AppFunctions, GUI fallback, Memory, multi-user experiments |

</details>

---

## Documentation

> The detailed design documents are currently written in Chinese.

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Scope and decision records, design principles, components, protocols (ACP, Binder message channel, in-app interfaces, extensions), how 13 features were implemented |
| [docs/extensions.md](docs/extensions.md) | Extension module (Extension Host): plugin format, plugin sources, MCP over Binder, remote Streamable HTTP, Skills, Hooks, the Runner |
| [docs/third-party-acp.md](docs/third-party-acp.md) | Third-party apps on ACP: authorization, identity, limits, the `acp-android` SDK, the sample Notes app spec, and the discussion of permission-control risks (section 9) |
| [docs/development.md](docs/development.md) | Development guide: environment, common commands, packaging the module zip, modules and source layout, sample apps, the release certificate |
| [docs/implementation-plan.md](docs/implementation-plan.md) | File-level layout, build and artifacts (with pinned dependency versions), 8 validations, dependency graph, 28 work packages and M1–M6 exit criteria, migration from agenroid, risks |
| [docs/m1-acceptance.md](docs/m1-acceptance.md) | Acceptance checklist: real-device and emulator verification records, defects found and fixed, what is not verified |
| [docs/sample-apps.md](docs/sample-apps.md) | The alarm, calendar and notes sample apps: tool lists, data, build |
| [docs/spikes/](docs/spikes/) | Conclusions of each validation: S1 (module installs an APK), S2 (keep-alive and root supervision), S3 (ACP over Binder), S8 (Pi Agent core in QuickJS). The experiment projects live in `spikes/` and are not part of the main build |
| [core/protocol/acp-profile-v1.md](core/protocol/acp-profile-v1.md) | The external protocol; this is the source of truth |
| [Request-flow diagram](docs/assets/request-flow.svg) | The core path: an installed-later app calls the Agent over ACP, and tools are provided over Binder by a plugin embedded in another app (including the optional Hook step) |
| [Dependency graph](docs/assets/dependency-graph.svg) | Ordering between work packages and validation items |

---

## Contributing and feedback

**Feedback.** For bugs, ideas and problems while integrating, please open an [issue](https://github.com/HanZijie/agentos-android/issues). Include the device model, Android version, root solution and version (for example Magisk 30.7) and the steps to reproduce. For ACP / MCP problems, add the package names of the calling app and the plugin.

**Contributing.** Pull requests are welcome:

1. Fork the repository and branch from `main`.
2. Before committing, run the same checks as CI:

   ```bash
   ./gradlew test lint -Pagentos.skipPiBundle=true
   python3 tools/check-i18n.py
   ```

3. A few conventions: dependency versions are pinned in `gradle/libs.versions.toml`, so do not upgrade them casually; UI strings go in `values/` (Chinese) and `values-en/` (English), and string literals in `src/main` must not contain Chinese (`check-i18n.py` enforces this); keys and private keys never go into the repository.
4. Open the PR against `main`, and describe the change and how you verified it. For real-device verification, state the device and system version. Commit messages follow the prefixes already in the history, such as `docs:`, `tools:` and `feat(…)`.

**Good places to help** (all from the "not verified / not implemented" items in [Status and boundaries](#status-and-boundaries)):

- Real-device verification on KernelSU and Android 16 / 17, and screen-off and 24-hour residency tests;
- Third-party permissions per (app, plugin, action); the design discussion is in [section 9 of the third-party integration design](docs/third-party-acp.md#9-权限管控机制设计讨论不实现) (Chinese);
- Integrating a real third-party app (not one of the samples) end to end, and adding conformance tests for the third-party channel.

---

## Star History

<a href="https://star-history.com/#HanZijie/agentos-android&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=HanZijie/agentos-android&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=HanZijie/agentos-android&type=Date" />
    <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=HanZijie/agentos-android&type=Date" />
  </picture>
</a>

---

## License

[MIT License](LICENSE), consistent with [agenroid](https://github.com/HanZijie/agenroid).

<sub>Android is a trademark of Google LLC. This project is not affiliated with or endorsed by Google.</sub>
