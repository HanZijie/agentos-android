package org.agentos.app.settings

import org.agentos.app.R
import org.agentos.app.i18n.ResStrings
import org.agentos.app.onboarding.Onboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLogicTest {
    // Shapes copied from the IAgentControl v2 / v3 documentation (app/src/main/aidl/.../IAgentControl.aidl)
    private val catalogJson = """{"schemaVersion":1,"piAi":"0.86.1","customApis":["anthropic-messages","openai-completions"],
        "thinkingLevels":["off","minimal","low","medium","high"],
        "providers":[{"id":"minimax","name":"MiniMax","apis":["anthropic-messages"],"baseUrls":["https://api.minimax.io/anthropic"],"keyLabel":"MiniMax API key","modelCount":3},
                     {"id":"minimax-cn","name":"MiniMax (China)","apis":["anthropic-messages"],"baseUrls":["https://api.minimaxi.com/anthropic"],"keyLabel":"MiniMax CN API key","modelCount":3}]}"""
    private val modelsJson = """{"provider":{"id":"minimax-cn","name":"MiniMax (China)","models":[
        {"id":"MiniMax-M2.7","name":"MiniMax M2.7","api":"anthropic-messages","baseUrl":"https://api.minimaxi.com/anthropic","reasoning":true,"input":["text"],"contextWindow":204800,"maxTokens":131072}]}}"""
    private val presetSource = """{"configured":true,"usable":true,"kind":"preset","provider":"minimax-cn","providerName":"MiniMax (China)",
        "model":"MiniMax-M2.7","modelName":"MiniMax M2.7","api":"anthropic-messages","baseUrl":"https://api.minimaxi.com/anthropic",
        "thinkingLevel":"off","key":{"set":true,"masked":"sk-a…wxyz","label":"MiniMax CN API key","endpoints":["https://api.minimaxi.com/anthropic"]},
        "updatedAt":1,"problems":[]}"""

    @Test
    fun parsesCatalogModelsAndSource() {
        val cat = Byok.parseCatalog(catalogJson)
        assertEquals(listOf("minimax", "minimax-cn"), cat.providers.map { it.id })
        assertEquals("MiniMax CN API key", cat.providers[1].keyLabel)
        assertEquals(5, cat.thinkingLevels.size)
        val models = Byok.parseModels(modelsJson)
        assertEquals("MiniMax-M2.7", models.single().id)
        assertTrue(models.single().reasoning)
        val src = Byok.parseSource(presetSource)
        assertTrue(src.configured && src.usable && src.keySet)
        assertEquals("sk-a…wxyz", src.keyMasked)
        val none = Byok.parseSource("""{"configured":false,"usable":false,"problems":["key_unreadable"]}""")
        assertFalse(none.configured)
        assertEquals(listOf("key_unreadable"), none.problems)
    }

    @Test
    fun endpointRuleIsHttpsOrLoopbackHttp() {
        assertTrue(Byok.isAllowedEndpoint("https://api.deepseek.com/v1"))
        assertTrue(Byok.isAllowedEndpoint("https://gw.example.com:8443/anthropic"))
        assertTrue(Byok.isAllowedEndpoint("http://127.0.0.1:11434/v1"))
        assertTrue(Byok.isAllowedEndpoint("http://localhost:8080/v1"))
        assertTrue(Byok.isAllowedEndpoint("http://[::1]:8080/v1"))
        assertFalse("only the documented loopback addresses", Byok.isAllowedEndpoint("http://127.0.0.2/v1"))
        assertFalse(Byok.isAllowedEndpoint("http://192.168.1.10:11434/v1"))
        assertFalse(Byok.isAllowedEndpoint("http://api.example.com/v1"))
        assertFalse("no user info", Byok.isAllowedEndpoint("https://user:pass@api.example.com/v1"))
        assertFalse("no query", Byok.isAllowedEndpoint("https://api.example.com/v1?key=x"))
        assertFalse("no fragment", Byok.isAllowedEndpoint("https://api.example.com/v1#x"))
        assertFalse(Byok.isAllowedEndpoint("ftp://api.example.com"))
        assertFalse(Byok.isAllowedEndpoint("api.example.com/v1"))
    }

    @Test
    fun emptyKeyIsOnlyAllowedForTheSameEndpoint() {
        val src = Byok.parseSource(presetSource)
        assertNull(Byok.validate(Byok.Form.Preset("minimax-cn", "MiniMax-M2.5"), keyEntered = false, current = src))
        assertEquals("key", Byok.validate(Byok.Form.Preset("minimax", "MiniMax-M2.7"), keyEntered = false, current = src)?.field)
        assertNull(Byok.validate(Byok.Form.Preset("minimax", "MiniMax-M2.7"), keyEntered = true, current = src))
        assertEquals("key", Byok.validate(Byok.Form.Preset("minimax-cn", "MiniMax-M2.7"), keyEntered = false, current = null)?.field)
        val custom = Byok.Form.Custom(Byok.OPENAI, "http://192.168.1.2/v1", "m")
        assertEquals("baseUrl", Byok.validate(custom, keyEntered = true, current = null)?.field)
        assertEquals("model", Byok.validate(custom.copy(baseUrl = "https://x.example/v1", model = ""), true, null)?.field)
    }

    @Test
    fun buildsTheDocumentedSourceJson() {
        assertEquals(
            """{"kind":"preset","provider":"minimax-cn","model":"MiniMax-M2.7","thinkingLevel":"medium"}""",
            Byok.sourceJson(Byok.Form.Preset("minimax-cn", "MiniMax-M2.7", "medium")),
        )
        assertEquals(
            """{"kind":"custom","api":"openai-completions","baseUrl":"https://api.deepseek.com/v1","model":"deepseek-chat","input":["text"],"thinkingLevel":"off"}""",
            Byok.sourceJson(Byok.Form.Custom(Byok.OPENAI, " https://api.deepseek.com/v1 ", " deepseek-chat ")),
        )
    }

    @Test
    fun byokErrorsNeverEchoInput() {
        assertEquals("换了厂商或地址，需要重新填写 key", Byok.errorText("agentos.byok.key_required: endpoint changed"))
        assertTrue(Byok.errorText("agentos.byok.invalid_endpoint: …").startsWith("API 地址不合法"))
        assertEquals("保存失败，请重试", Byok.errorText("sk-secret-should-not-appear"))
        assertEquals("保存失败（brand_new）", Byok.errorText("agentos.byok.brand_new: x"))
    }

    private val runtimeJson = """{"pid":1234,"phase":"READY","tasks":1,"foreground":true,"state":"busy","serviceRunning":true,"foregroundDenied":false,"uptimeMs":125000}"""
    private val okJson = """{"protocol":1,"state":"ok","reason":"boot","seq":1,"module_version":"0.1.0","module_version_code":1}"""
    private val staleOkJson = """{"protocol":1,"state":"ok","reason":"runtime_up","seq":3,"module_version":"v0.1.0","module_version_code":1}"""
    private val oldBootJson = """{"protocol":1,"state":"ok","reason":"runtime_up","seq":3,"boot_count":11}"""

    @Test
    fun runtimeStatusTextInChinese() {
        val s = ResStrings.zh
        val rt = StatusText.runtime(runtimeJson, s)
        assertEquals("运行时", rt[0].label)
        assertEquals("就绪（pid 1234，已运行 2 分钟）", rt[0].value)
        assertEquals("进行中的任务", rt[1].label)
        assertEquals("1 个，前台运行", rt[1].value)
        assertEquals("读不到状态", StatusText.runtime("not json", s)[0].value)
        assertEquals("无", StatusText.runtime("""{"phase":"READY","tasks":0}""", s)[1].value)
        assertEquals("2 个", StatusText.runtime("""{"phase":"READY","tasks":2,"foreground":false}""", s)[1].value)
        assertEquals("59 秒", StatusText.duration(59_000, s))
        assertEquals("1 小时 1 分钟", StatusText.duration(3_660_000, s))
        assertEquals("2 天 3 小时", StatusText.duration(2 * 86_400_000L + 3 * 3_600_000L, s))
    }

    @Test
    fun runtimeStatusTextInEnglish() {
        val s = ResStrings.en
        val rt = StatusText.runtime(runtimeJson, s)
        assertEquals("Runtime", rt[0].label)
        assertEquals("Ready (pid 1234, up 2 min)", rt[0].value)
        assertEquals("Running tasks", rt[1].label)
        assertEquals("1 task, running in the foreground", rt[1].value)
        assertEquals("Can't read status", StatusText.runtime("not json", s)[0].value)
        assertEquals("None", StatusText.runtime("""{"phase":"READY","tasks":0}""", s)[1].value)
        assertEquals("2 tasks", StatusText.runtime("""{"phase":"READY","tasks":2,"foreground":false}""", s)[1].value)
        assertEquals("59 s", StatusText.duration(59_000, s))
        assertEquals("1 h 1 min", StatusText.duration(3_660_000, s))
        assertEquals("2 d 3 h", StatusText.duration(2 * 86_400_000L + 3 * 3_600_000L, s))
    }

    @Test
    fun supervisorStatusTextInBothLanguages() {
        for (s in listOf(ResStrings.zh, ResStrings.en)) {
            val ok = StatusText.supervisor(okJson, false, s)
            assertEquals(s.get(R.string.status_supervisor_ok), ok[0].value)
            assertFalse(ok[0].warn)
            assertEquals(s.get(R.string.status_module_version_label), ok[1].label)
            val safe = StatusText.supervisor("""{"protocol":1,"state":"safe_mode","reason":"crash_loop","seq":3,"deaths":5}""", false, s)
            assertTrue(safe[0].value.contains(s.get(R.string.status_safe_reason_crash_loop)))
            assertTrue(safe[0].warn)
            val missing = StatusText.supervisor("{}", supervisorMissing = true, s)
            assertEquals(s.get(R.string.status_supervisor_missing), missing[0].value)
            // a status stored in an earlier boot is not shown as the current state (no "OK" next to "not running")
            val stale = StatusText.supervisor(staleOkJson, supervisorMissing = true, s)
            assertEquals(s.get(R.string.status_supervisor_missing), stale[0].value)
            assertTrue(stale[0].warn)
            assertEquals(s.get(R.string.status_supervisor_last_label), stale[1].label)
            assertEquals(s.get(R.string.status_supervisor_ok), stale[1].value)
            assertFalse(stale[1].warn)
            // within the first 30 s of a new boot (not yet "missing") the old status is not current either
            assertEquals(s.get(R.string.status_supervisor_waiting), StatusText.supervisor(oldBootJson, false, s, currentBootCount = 12)[0].value)
            assertEquals(s.get(R.string.status_supervisor_ok), StatusText.supervisor(oldBootJson, false, s, currentBootCount = 11)[0].value)
            // every stopped / safe-mode reason has a sentence of its own in this language (none falls back to the raw code)
            val stopped = mapOf(
                "user_stopped" to R.string.status_stopped_reason_user_stopped, "not_launched" to R.string.status_stopped_reason_not_launched,
                "module_disabled" to R.string.status_stopped_reason_module_disabled, "supervisor_exited" to R.string.status_stopped_reason_supervisor_exited,
                "install_failed" to R.string.status_stopped_reason_install_failed,
            )
            for ((reason, id) in stopped) {
                assertEquals(s.get(R.string.status_supervisor_stopped, s.get(id)), StatusText.supervisor("""{"state":"stopped","reason":"$reason"}""", false, s)[0].value)
            }
            val safeReasons = mapOf(
                "crash_loop" to R.string.status_safe_reason_crash_loop, "manual" to R.string.status_safe_reason_manual,
                "unsupported_api" to R.string.status_safe_reason_unsupported_api,
            )
            for ((reason, id) in safeReasons) {
                assertEquals(s.get(R.string.status_supervisor_safe_mode, s.get(id)), StatusText.supervisor("""{"state":"safe_mode","reason":"$reason"}""", false, s)[0].value)
            }
        }
        assertTrue(StatusText.supervisorMissing("""{"supervisorMissing":true}"""))
    }

    @Test
    fun supervisorStatusSentencesInChinese() {
        val s = ResStrings.zh
        assertEquals("正常", StatusText.supervisor(okJson, false, s)[0].value)
        val safe = StatusText.supervisor("""{"protocol":1,"state":"safe_mode","reason":"crash_loop","seq":3,"deaths":5}""", false, s)
        assertTrue(safe[0].value.startsWith("安全模式：运行时短时间内反复崩溃"))
        assertTrue(StatusText.supervisor("{}", supervisorMissing = true, s)[0].value.startsWith("未运行"))
        assertEquals("等待本次开机的监督状态", StatusText.supervisor(oldBootJson, false, s, currentBootCount = 12)[0].value)
        assertEquals("运行时异常退出，正在按退避重新拉起（10 分钟内 3 次）", StatusText.supervisor("""{"state":"backoff","deaths":3}""", false, s)[0].value)
    }

    @Test
    fun supervisorStatusSentencesInEnglish() {
        val s = ResStrings.en
        assertEquals("OK", StatusText.supervisor(okJson, false, s)[0].value)
        val safe = StatusText.supervisor("""{"protocol":1,"state":"safe_mode","reason":"crash_loop","seq":3,"deaths":5}""", false, s)
        assertTrue(safe[0].value.startsWith("Safe mode: the runtime crashed repeatedly in a short time."))
        assertTrue(StatusText.supervisor("{}", supervisorMissing = true, s)[0].value.startsWith("Not running"))
        assertEquals("Waiting for the supervisor status of this boot", StatusText.supervisor(oldBootJson, false, s, currentBootCount = 12)[0].value)
        assertEquals("The runtime exited unexpectedly and is being restarted with backoff (1 time in the last 10 minutes)", StatusText.supervisor("""{"state":"backoff","deaths":1}""", false, s)[0].value)
        assertEquals("The runtime exited unexpectedly and is being restarted with backoff (3 times in the last 10 minutes)", StatusText.supervisor("""{"state":"backoff","deaths":3}""", false, s)[0].value)
    }

    private val accessJson = """{"enabled":true,"listening":true,"socket":"agentos-acp","listenError":null,
            "code":{"expiresAtMs":99,"attemptsLeft":4},"pairings":[{"id":"dp_1","label":"mac","pairedAtMs":1,"lastSeenMs":2}],
            "connections":[{"id":"c1","pairingId":"dp_1","label":"mac","peerUid":2000,"openedAtMs":3,"transport":{}}]}"""
    private val offJson = """{"enabled":false,"listening":false,"pairings":[],"connections":[]}"""

    @Test
    fun desktopAccessLogic() {
        val a = Desktop.parseAccess(accessJson)
        assertEquals(4, a.codeAttemptsLeft)
        assertEquals("mac", a.pairings.single().label)
        assertEquals("482913", Desktop.parseCode("""{"code":"482913","expiresAtMs":5,"ttlMs":300000}""").code)
        // F11 item 4 (M1): ask for the battery optimisation exemption only while desktop access is on and it is missing
        assertTrue(Desktop.needsBatteryExemption(enabled = true, exempt = false))
        assertFalse(Desktop.needsBatteryExemption(enabled = true, exempt = true))
        assertFalse(Desktop.needsBatteryExemption(enabled = false, exempt = false))
        assertTrue(StatusText.foregroundDenied("""{"foregroundDenied":true}"""))
        assertFalse(StatusText.foregroundDenied("""{"foregroundDenied":false}"""))
        assertEquals("node tools/acp-bridge/acp-bridge.mjs pair 482913", Desktop.pairCommand("482913"))
        // a computer that did not name itself gets the default name of the screen's language
        val unnamed = Desktop.parseAccess("""{"enabled":true,"pairings":[{"id":"dp_2","pairedAtMs":1}]}""").pairings.single()
        assertNull(unnamed.label)
        assertEquals("电脑", Desktop.pairingLabel(unnamed, ResStrings.zh))
        assertEquals("Computer", Desktop.pairingLabel(unnamed, ResStrings.en))
        assertEquals("mac", Desktop.pairingLabel(a.pairings.single(), ResStrings.en))
    }

    @Test
    fun desktopAccessTextInChinese() {
        val s = ResStrings.zh
        val a = Desktop.parseAccess(accessJson)
        assertEquals("已打开：1 台已配对，1 个连接", Desktop.summary(a, s))
        assertEquals("关闭", Desktop.summary(Desktop.parseAccess(offJson), s))
        assertEquals("已打开，正在启动监听", Desktop.summary(a.copy(listening = false), s))
        assertEquals("打不开监听（EADDRINUSE）：可能有别的 App 占用了 agentos-acp", Desktop.summary(a.copy(listenError = "EADDRINUSE"), s))
        assertEquals("请先打开电脑端接入", Desktop.errorText("agentos.desktop.disabled: off", s))
        assertEquals("操作失败，请重试", Desktop.errorText("boom", s))
        assertEquals("电脑端接入已开启", Desktop.notificationTitle(s))
        assertEquals("关闭", Desktop.notificationAction(s))
        // F11 item 4: the note next to the switch must use the same words as the foreground notification
        assertTrue(Desktop.foregroundNote(s).contains("常驻通知“电脑端接入已开启”"))
        assertTrue(Desktop.foregroundNote(s).contains("用完记得关闭"))
        assertTrue(Desktop.foregroundNote(s).contains("点通知上的“关闭”"))
        assertEquals("2 台电脑", Desktop.computers(2, s))
        assertEquals("5 分钟", Desktop.minutes(5, s))
    }

    @Test
    fun desktopAccessTextInEnglish() {
        val s = ResStrings.en
        val a = Desktop.parseAccess(accessJson)
        assertEquals("On: 1 computer paired, 1 connection", Desktop.summary(a, s))
        assertEquals("On: 2 computers paired, 0 connections", Desktop.summary(a.copy(pairings = a.pairings + a.pairings, connections = 0), s))
        assertEquals("Off", Desktop.summary(Desktop.parseAccess(offJson), s))
        assertEquals("On, starting to listen", Desktop.summary(a.copy(listening = false), s))
        assertEquals("Can't listen (EADDRINUSE): another app may be using agentos-acp", Desktop.summary(a.copy(listenError = "EADDRINUSE"), s))
        assertEquals("Turn on desktop access first", Desktop.errorText("agentos.desktop.disabled: off", s))
        assertEquals("That didn't work. Please try again.", Desktop.errorText("boom", s))
        assertEquals("Desktop access is on", Desktop.notificationTitle(s))
        assertEquals("Turn off", Desktop.notificationAction(s))
        assertTrue(Desktop.foregroundNote(s).contains("an ongoing notification, “Desktop access is on”"))
        assertTrue(Desktop.foregroundNote(s).contains("Remember to turn it off when you're done"))
        assertTrue(Desktop.foregroundNote(s).contains("tap “Turn off” on the notification"))
        assertEquals("1 computer", Desktop.computers(1, s))
        assertEquals("2 computers", Desktop.computers(2, s))
        assertEquals("1 minute", Desktop.minutes(1, s))
        assertEquals("5 minutes", Desktop.minutes(5, s))
    }

    @Test
    fun desktopHowToKeepsTheSecurityStatementInBothLanguages() {
        for (s in listOf(ResStrings.zh, ResStrings.en)) {
            val howTo = Desktop.howTo(s)
            // acp-bridge pair is the way to connect; a bare adb forward is explained as not enough
            assertTrue("${s.locale}: $howTo", howTo.indexOf("acp-bridge pair") in 0 until howTo.indexOf("adb forward"))
            // it names the button it tells you to tap
            assertTrue(howTo.contains(s.get(R.string.desktop_generate_code)))
            assertTrue(howTo.contains("tcp:8765 localabstract:agentos-acp"))
        }
        // the last sentence: confirmations on the phone still appear and the computer cannot bypass them
        assertTrue(Desktop.howTo(ResStrings.zh).endsWith("手机上的确认照常出现，电脑端无法绕过。"))
        assertTrue(Desktop.howTo(ResStrings.en).endsWith("Confirmations on the phone still appear as usual; the computer can't bypass them."))
        // the pairing dialogs: what turning off / revoking does, and that pairing has to be redone
        val zh = ResStrings.zh
        val en = ResStrings.en
        assertEquals("会断开 1 个连接，并作废 2 台电脑的配对；以后再打开需要重新配对。", zh.get(R.string.desktop_disable_message, Desktop.connections(1, zh), Desktop.computers(2, zh)))
        assertEquals("This will disconnect 1 connection and void the pairing of 2 computers. You'll need to pair again if you turn it on later.", en.get(R.string.desktop_disable_message, Desktop.connections(1, en), Desktop.computers(2, en)))
        assertEquals("撤销全部 3 台电脑的配对？", zh.get(R.string.desktop_revoke_all_title, Desktop.computers(3, zh)))
        assertEquals("Revoke pairing for all 3 computers?", en.get(R.string.desktop_revoke_all_title, Desktop.computers(3, en)))
        assertEquals("它们的连接会立即断开，需要重新配对才能再连。", zh.get(R.string.desktop_revoke_all_message))
        assertEquals("Their connections will be dropped immediately, and they must pair again to reconnect.", en.get(R.string.desktop_revoke_all_message))
        val command = Desktop.pairCommand("482913")
        assertTrue(zh.get(R.string.desktop_code_message, command, Desktop.minutes(5, zh)).contains("约 5 分钟内有效，配对成功或输错 5 次后作废"))
        assertTrue(en.get(R.string.desktop_code_message, command, Desktop.minutes(5, en)).contains("Valid for about 5 minutes; it is voided after a successful pairing or 5 wrong attempts."))
    }

    @Test
    fun batteryTextInBothLanguages() {
        val zh = ResStrings.zh
        val en = ResStrings.en
        assertEquals("允许忽略电池优化", BatteryText.action(zh))
        assertEquals("Allow ignoring battery optimization", BatteryText.action(en))
        assertEquals("还没有允许忽略电池优化：运行时在后台被重新拉起后可能进不了前台、被系统冻结，电脑端会连不上", BatteryText.desktopWarning(zh))
        assertTrue(BatteryText.desktopWarning(en).contains("it may not reach the foreground and get frozen, and your computer won't be able to connect."))
        for (s in listOf(zh, en)) {
            // the system dialog follows: both texts tell the user what to pick there and that it can be revoked
            assertTrue(BatteryText.desktopDialogMessage(s).contains(if (s === zh) "选择“允许”" else "Choose “Allow”"))
            assertTrue(BatteryText.guideDialogMessage(s).contains(if (s === zh) "选择“允许”" else "Choose “Allow”"))
            assertTrue(BatteryText.desktopDialogMessage(s).contains(if (s === zh) "以后可以在系统设置里撤销" else "revoke it later in system settings"))
        }
    }

    @Test
    fun securityTextOnlyClaimsRootWhenTheSupervisorReportedThisBoot() {
        val thisBoot = """{"protocol":1,"state":"stopped","reason":"not_launched","seq":1,"boot_count":12}"""
        val oldBoot = """{"protocol":1,"state":"ok","reason":"runtime_up","seq":9,"boot_count":11}"""
        assertTrue(StatusText.supervisorThisBoot(thisBoot, 12))
        assertFalse("stored in an earlier boot", StatusText.supervisorThisBoot(oldBoot, 12))
        assertFalse("no report", StatusText.supervisorThisBoot("{}", 12))
        assertFalse("boot count unknown: do not claim", StatusText.supervisorThisBoot(thisBoot, null))
    }

    @Test
    fun securityTextInChinese() {
        val s = ResStrings.zh
        assertEquals("best_effort（尽力而为）", SecurityText.level(s))
        val rooted = SecurityText.settings(true, s)
        assertTrue(rooted.startsWith("这台手机已经 root"))
        // the warning is carried whole: who can read what, and what AgentOS cannot do about it
        assertTrue(rooted.contains("其他获得 root 权限的应用可以读取 AgentOS 的全部数据，包括加密前后的 key、对话和日志"))
        assertTrue(rooted.contains("但它防不住拥有 root 权限的应用。请只给信任的应用授予 root 权限。"))
        assertTrue(rooted.contains("Agent 的代码不以 root 运行，模型和插件接触不到 root。"))
        val unknown = SecurityText.settings(false, s)
        assertFalse(unknown.contains("这台手机已经 root"))
        assertTrue(unknown.contains("如果已经 root") && unknown.contains("如果没有 root"))
        assertTrue(unknown.contains("其他获得 root 权限的应用可以读取 AgentOS 的全部数据，包括加密前后的 key、对话和日志"))
        assertTrue(SecurityText.welcome(true, s).contains("这台手机已经 root"))
        for (r in listOf(false, null)) assertFalse(SecurityText.welcome(r, s).contains("已经 root"))
        assertTrue(SecurityText.welcome(false, s).contains("没有检测到 AgentOS 模块"))
        // the first-run welcome card follows the same rule
        val base = Onboarding.Facts(null, null, false, false, false, 0)
        assertFalse(Onboarding.steps(base, s)[0].detail.contains("已经 root"))
        assertTrue(Onboarding.steps(base.copy(rooted = true), s)[0].detail.contains("这台手机已经 root"))
    }

    @Test
    fun securityTextInEnglishKeepsEveryWarning() {
        val s = ResStrings.en
        assertEquals("best_effort (best-effort protection)", SecurityText.level(s))
        val rooted = SecurityText.settings(true, s)
        assertTrue(rooted.startsWith("This phone is rooted"))
        // the same warnings as the Chinese text: who can read what, and that AgentOS cannot protect against it
        assertTrue(rooted.contains("Other apps that hold root can read all of AgentOS's data, including the key before and after encryption, conversations and logs."))
        assertTrue(rooted.contains("But it can't protect against apps that hold root. Only grant root to apps you trust."))
        assertTrue(rooted.contains("the agent's code doesn't run as root, so models and plugins can't reach root."))
        val unknown = SecurityText.settings(false, s)
        assertFalse(unknown.contains("This phone is rooted"))
        assertTrue(unknown.contains("we can't tell whether this phone is rooted"))
        assertTrue(unknown.contains("If it is rooted: other apps that hold root can read all of AgentOS's data, including the key before and after encryption, conversations and logs. Only grant root to apps you trust."))
        assertTrue(unknown.contains("If it isn't rooted: AgentOS's data is protected by Android's app isolation and other apps can't read it."))
        assertTrue(SecurityText.welcome(true, s).contains("This phone is rooted"))
        for (r in listOf(false, null)) assertFalse(SecurityText.welcome(r, s).contains("is rooted"))
        assertTrue(SecurityText.welcome(false, s).contains("The AgentOS module wasn't detected in this boot"))
        assertTrue(SecurityText.welcome(true, s).contains("can read AgentOS's data (including the key)"))
        val base = Onboarding.Facts(null, null, false, false, false, 0)
        assertFalse(Onboarding.steps(base, s)[0].detail.contains("is rooted"))
        assertTrue(Onboarding.steps(base.copy(rooted = true), s)[0].detail.contains("This phone is rooted"))
    }

    @Test
    fun onboardingSteps() {
        for (s in listOf(ResStrings.zh, ResStrings.en)) {
            val fresh = Onboarding.Facts(null, null, notificationsGranted = false, assistantHeld = false, batteryExempt = false, pluginCount = 0)
            val steps = Onboarding.steps(fresh, s)
            assertEquals(Onboarding.Id.entries.toList(), steps.map { it.id })
            assertNull("model state unknown until :agent answers", steps.first { it.id == Onboarding.Id.MODEL }.done)
            assertEquals(listOf(Onboarding.Id.NOTIFICATIONS, Onboarding.Id.ASSISTANT, Onboarding.Id.BATTERY), Onboarding.pending(fresh, s))
            val ready = fresh.copy(modelConfigured = true, modelUsable = true, notificationsGranted = true, batteryExempt = true)
            assertEquals(listOf(Onboarding.Id.ASSISTANT), Onboarding.pending(ready, s))
            assertTrue(steps.first { it.id == Onboarding.Id.WELCOME }.detail.contains("best_effort"))
            // the supervisor is only mentioned as guarding the runtime when it reported this boot
            assertTrue(Onboarding.steps(fresh.copy(rooted = true), s).first { it.id == Onboarding.Id.WELCOME }.detail.contains(s.get(R.string.security_welcome_rooted, SecurityText.level(s))))
            // the guide always asks for the battery exemption: the step, and once more on "Get started" if skipped
            assertEquals(BatteryText.guideStep(s), steps.first { it.id == Onboarding.Id.BATTERY }.detail)
            assertTrue(Onboarding.askBatteryOnFinish(fresh, alreadyAsked = false))
            assertFalse(Onboarding.askBatteryOnFinish(fresh, alreadyAsked = true))
            assertFalse(Onboarding.askBatteryOnFinish(fresh.copy(batteryExempt = true), alreadyAsked = false))
            // the model step's button follows the state
            assertEquals(s.get(R.string.onboarding_model_set), steps.first { it.id == Onboarding.Id.MODEL }.action)
            assertEquals(s.get(R.string.onboarding_model_change), Onboarding.steps(ready, s).first { it.id == Onboarding.Id.MODEL }.action)
        }
    }

    @Test
    fun onboardingTextInBothLanguages() {
        val fresh = Onboarding.Facts(null, null, notificationsGranted = false, assistantHeld = false, batteryExempt = false, pluginCount = 0)
        val zh = Onboarding.steps(fresh, ResStrings.zh)
        val en = Onboarding.steps(fresh, ResStrings.en)
        assertEquals(listOf("欢迎使用 AgentOS", "选择模型并填写 key", "允许通知", "设为默认助理（可选）", "允许忽略电池优化", "已发现的插件"), zh.map { it.title })
        assertEquals(listOf("Welcome to AgentOS", "Choose a model and enter your key", "Allow notifications", "Set as default assistant (optional)", "Allow ignoring battery optimization", "Discovered plugins"), en.map { it.title })
        assertEquals("正在读取当前设置…", zh[1].detail)
        assertEquals("Reading the current settings…", en[1].detail)
        assertEquals("去设置", zh[1].action)
        assertEquals("Set up", en[1].action)
        assertEquals("发现了 3 个插件，可以在设置页里启用。", Onboarding.steps(fresh.copy(pluginCount = 3), ResStrings.zh).last().detail)
        assertEquals("Found 1 plugin. You can enable it in Settings.", Onboarding.steps(fresh.copy(pluginCount = 1), ResStrings.en).last().detail)
        assertEquals("Found 3 plugins. You can enable them in Settings.", Onboarding.steps(fresh.copy(pluginCount = 3), ResStrings.en).last().detail)
        val configured = fresh.copy(modelConfigured = true, modelUsable = false)
        assertEquals("已保存，但暂时不可用，打开看看原因。", Onboarding.steps(configured, ResStrings.zh)[1].detail)
        assertEquals("Saved, but not usable right now. Open it to see why.", Onboarding.steps(configured, ResStrings.en)[1].detail)
    }
}
