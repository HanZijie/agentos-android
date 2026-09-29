package org.agentos.app.settings

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

    @Test
    fun runtimeAndSupervisorStatusText() {
        val rt = StatusText.runtime("""{"pid":1234,"phase":"READY","tasks":1,"foreground":true,"state":"busy","serviceRunning":true,"foregroundDenied":false,"uptimeMs":125000}""")
        assertEquals("就绪（pid 1234，已运行 2 分钟）", rt[0].value)
        assertEquals("1 个，前台运行", rt[1].value)
        val ok = StatusText.supervisor("""{"protocol":1,"state":"ok","reason":"boot","seq":1,"module_version":"0.1.0","module_version_code":1}""", false)
        assertEquals("正常", ok[0].value)
        assertFalse(ok[0].warn)
        val safe = StatusText.supervisor("""{"protocol":1,"state":"safe_mode","reason":"crash_loop","seq":3,"deaths":5}""", false)
        assertTrue(safe[0].value.startsWith("安全模式：运行时短时间内反复崩溃"))
        assertTrue(safe[0].warn)
        val missing = StatusText.supervisor("{}", supervisorMissing = true)
        assertTrue(missing[0].value.startsWith("未运行"))
        // a status stored in an earlier boot is not shown as the current state ("正常" next to "未运行")
        val stale = StatusText.supervisor("""{"protocol":1,"state":"ok","reason":"runtime_up","seq":3,"module_version":"v0.1.0","module_version_code":1}""", supervisorMissing = true)
        assertTrue(stale[0].value.startsWith("未运行"))
        assertTrue(stale[0].warn)
        assertEquals("上次收到的状态（不是本次开机的）", stale[1].label)
        assertEquals("正常", stale[1].value)
        assertFalse(stale[1].warn)
        // within the first 30 s of a new boot (not yet "missing") the old status is not current either
        val oldBoot = """{"protocol":1,"state":"ok","reason":"runtime_up","seq":3,"boot_count":11}"""
        assertEquals("等待本次开机的监督状态", StatusText.supervisor(oldBoot, false, currentBootCount = 12)[0].value)
        assertEquals("正常", StatusText.supervisor(oldBoot, false, currentBootCount = 11)[0].value)
        assertTrue(StatusText.supervisorMissing("""{"supervisorMissing":true}"""))
        assertEquals("读不到状态", StatusText.runtime("not json")[0].value)
    }

    @Test
    fun desktopAccess() {
        val a = Desktop.parseAccess("""{"enabled":true,"listening":true,"socket":"agentos-acp","listenError":null,
            "code":{"expiresAtMs":99,"attemptsLeft":4},"pairings":[{"id":"dp_1","label":"mac","pairedAtMs":1,"lastSeenMs":2}],
            "connections":[{"id":"c1","pairingId":"dp_1","label":"mac","peerUid":2000,"openedAtMs":3,"transport":{}}]}""")
        assertEquals("已打开：1 台已配对，1 个连接", Desktop.summary(a))
        assertEquals(4, a.codeAttemptsLeft)
        assertEquals("关闭", Desktop.summary(Desktop.parseAccess("""{"enabled":false,"listening":false,"pairings":[],"connections":[]}""")))
        assertEquals("482913", Desktop.parseCode("""{"code":"482913","expiresAtMs":5,"ttlMs":300000}""").code)
        assertEquals("请先打开电脑端接入", Desktop.errorText("agentos.desktop.disabled: off"))
        // F11 item 4: the note next to the switch must use the same words as C's foreground notification
        assertEquals("电脑端接入已开启", Desktop.NOTIFICATION_TITLE)
        assertEquals("关闭", Desktop.NOTIFICATION_ACTION)
        assertTrue(Desktop.FOREGROUND_NOTE.contains("常驻通知“电脑端接入已开启”"))
        assertTrue(Desktop.FOREGROUND_NOTE.contains("用完记得关闭"))
    }

    @Test
    fun securityTextOnlyClaimsRootWhenTheSupervisorReportedThisBoot() {
        val thisBoot = """{"protocol":1,"state":"stopped","reason":"not_launched","seq":1,"boot_count":12}"""
        val oldBoot = """{"protocol":1,"state":"ok","reason":"runtime_up","seq":9,"boot_count":11}"""
        assertTrue(StatusText.supervisorThisBoot(thisBoot, 12))
        assertFalse("stored in an earlier boot", StatusText.supervisorThisBoot(oldBoot, 12))
        assertFalse("no report", StatusText.supervisorThisBoot("{}", 12))
        assertFalse("boot count unknown: do not claim", StatusText.supervisorThisBoot(thisBoot, null))

        assertTrue(SecurityText.settings(true).startsWith("这台手机已经 root"))
        val unknown = SecurityText.settings(false)
        assertFalse(unknown.contains("这台手机已经 root"))
        assertTrue(unknown.contains("如果已经 root") && unknown.contains("如果没有 root"))

        assertTrue(SecurityText.welcome(true).contains("这台手机已经 root"))
        for (r in listOf(false, null)) assertFalse(SecurityText.welcome(r).contains("已经 root"))
        assertTrue(SecurityText.welcome(false).contains("没有检测到 AgentOS 模块"))
        // the first-run welcome card follows the same rule
        val base = Onboarding.Facts(null, null, false, false, false, 0)
        assertFalse(Onboarding.steps(base)[0].detail.contains("已经 root"))
        assertTrue(Onboarding.steps(base.copy(rooted = true))[0].detail.contains("这台手机已经 root"))
    }

    @Test
    fun onboardingSteps() {
        val fresh = Onboarding.Facts(null, null, notificationsGranted = false, assistantHeld = false, batteryExempt = false, pluginCount = 0)
        val steps = Onboarding.steps(fresh)
        assertEquals(Onboarding.Id.entries.toList(), steps.map { it.id })
        assertNull("model state unknown until :agent answers", steps.first { it.id == Onboarding.Id.MODEL }.done)
        assertEquals(listOf(Onboarding.Id.NOTIFICATIONS, Onboarding.Id.ASSISTANT, Onboarding.Id.BATTERY), Onboarding.pending(fresh))
        val ready = fresh.copy(modelConfigured = true, modelUsable = true, notificationsGranted = true, batteryExempt = true)
        assertEquals(listOf(Onboarding.Id.ASSISTANT), Onboarding.pending(ready))
        assertTrue(steps.first { it.id == Onboarding.Id.WELCOME }.detail.contains("best_effort"))
        // the supervisor is only mentioned as guarding the runtime when it reported this boot
        assertTrue(Onboarding.steps(fresh.copy(rooted = true)).first { it.id == Onboarding.Id.WELCOME }.detail.contains("监督进程"))
    }
}
