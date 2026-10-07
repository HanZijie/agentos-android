package org.agentos.app.settings.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginsLogicTest {
    private val digest = "ab12cd34ef56" + "0".repeat(52)

    private fun plugin(
        id: String = "org.example.notes", status: String = "ready", enabled: Boolean = false, builtin: Boolean = false,
        toolCount: Int = 0, extra: String = "", servers: String = "[]", trusted: String = "null",
    ) = """{"id":"$id","packageName":"org.example.notes","name":"notes","displayName":"备忘录","versionName":"1.2","signingDigest":"$digest","trustedSigningDigest":$trusted,
        "status":"$status","builtin":$builtin,"enabled":$enabled,"toolCount":$toolCount,"skillCount":0,"servers":$servers,"problems":[],"unsupported":[]$extra}"""

    private fun one(json: String) = Plugins.parsePlugin(json)!!

    // ------------------------------------------------------------ 解析

    @Test
    fun parsesAFullPlugin() {
        val p = one(
            """{"id":"org.example.notes","packageName":"org.example.notes","name":"notes","displayName":"备忘录","versionName":"1.2","signingDigest":"$digest",
            "status":"ready","builtin":false,"enabled":true,"approval":"ask","toolCount":3,"skillCount":1,
            "problems":[{"code":"x","message":"bad thing"}],"unsupported":[{"kind":"resources","location":"server:a","detail":"not supported"}],
            "servers":[{"name":"main","state":"connected","toolCount":3},{"name":"b","state":"unreachable","error":"timeout","toolCount":0}],
            "rejectedServers":[{"name":"evil","reason":"not_exported"}],"skillProblems":[{"code":"s","message":"skill broken"}]}""",
        )
        assertEquals("备忘录", p.displayName)
        assertEquals(Plugins.Status.READY, p.status)
        assertTrue(p.enabled)
        assertEquals(2, p.servers.size)
        assertEquals("连不上：timeout", Plugins.serverStateText(p.servers[1]))
        assertEquals(4, Plugins.issues(p).size)
        assertTrue(Plugins.issues(p).any { it.contains("evil") && it.contains("没有对 AgentOS 开放") })
        assertTrue(Plugins.issues(p).any { it.contains("不支持") && it.contains("resources") })
    }

    @Test
    fun malformedInputYieldsNothingNotACrash() {
        assertTrue(Plugins.parsePlugins("not json").isEmpty())
        assertTrue(Plugins.parsePlugins("{}").isEmpty())
        assertNull(Plugins.parsePlugin("[1]"))
        assertTrue(Plugins.parsePlugins("""[1,"x",{"noid":true},{"id":""}]""").isEmpty())
        assertTrue(Plugins.parseTools(null).isEmpty())
        assertNull(Plugins.parsePolicy("nope"))
    }

    @Test
    fun unknownStatusIsUnavailableAndCannotBeSwitchedOn() {
        val p = one(plugin(status = "something_new"))
        assertEquals(Plugins.Status.UNAVAILABLE, p.status)
        assertFalse(p.switchEnabled)
        assertFalse(p.switchOn)
        assertTrue(Plugins.statusLine(p).second)
    }

    @Test
    fun untrustedTextIsCleaned() {
        val evil = "Notes\u202Eevil\u0000\n\n  with   spaces\u200B" + "x".repeat(500)
        val p = one(plugin(extra = ""","description":${kotlinx.serialization.json.JsonPrimitive(evil)}"""))
        assertFalse(p.description.contains('\u202E'))
        assertFalse(p.description.contains('\u0000'))
        assertFalse(p.description.contains('\n'))
        assertTrue(p.description.endsWith("…"))
        assertTrue(p.description.length <= 401)
        assertEquals("a b", Plugins.clean("a \t\n b"))
        assertEquals("", Plugins.clean(null))
    }

    @Test
    fun htmlAndFormatCharactersStayLiteral() {
        // 不解析：保持字面（界面只用 setText(String)）
        assertEquals("<b>bold</b> %s %1\$d {0}", Plugins.clean("<b>bold</b> %s %1\$d {0}"))
    }

    @Test
    fun digestIsReducedToHexAndGrouped() {
        val p = one(plugin().replace(digest, "AB12-CD34 ef56zz"))
        assertEquals("ab12cd34ef56", p.signingDigest)
        assertEquals("ab12 cd34 ef56", Plugins.digestHead(p.signingDigest))
        assertEquals("（无）", Plugins.digestHead(""))
        assertEquals("（无）", Plugins.digestHead(null))
    }

    // ------------------------------------------------------------ 状态和开关

    @Test
    fun statusLinesAndSwitchRules() {
        val ready = one(plugin(status = "ready", enabled = true))
        assertEquals("已启用" to false, Plugins.statusLine(ready))
        assertTrue(ready.switchOn)
        assertEquals("已发现，未启用" to false, Plugins.statusLine(one(plugin(enabled = false))))

        val changed = one(plugin(status = "signature_changed", enabled = true))
        assertFalse("a plugin that needs reconfirmation is never shown as on", changed.switchOn)
        assertTrue(changed.switchEnabled)
        assertTrue(changed.needsReconfirm)
        assertTrue(Plugins.statusLine(changed).first.contains("签名变了"))
        assertTrue(Plugins.statusLine(changed).second)

        val unconfirmed = one(plugin(status = "signature_unconfirmed"))
        assertTrue(unconfirmed.needsReconfirm)
        assertTrue(Plugins.statusLine(unconfirmed).first.contains("记不清"))

        val unavailable = one(plugin(status = "unavailable", extra = ""","unavailableReason":"assets_missing""""))
        assertFalse(unavailable.switchEnabled)
        assertTrue(Plugins.statusLine(unavailable).first.contains("没有找到插件包"))
    }

    @Test
    fun enabledButAllServersUnreachableIsFlagged() {
        val p = one(plugin(enabled = true, servers = """[{"name":"a","state":"unreachable","error":"x","toolCount":0}]"""))
        assertTrue(Plugins.statusLine(p).first.contains("连不上"))
    }

    @Test
    fun disabledPluginNeverReadsAsZeroTools() {
        assertEquals("启用后可查看工具", Plugins.toolSummary(one(plugin(enabled = false, toolCount = 0))))
        assertEquals("3 个工具", Plugins.toolSummary(one(plugin(enabled = true, toolCount = 3))))
        // enabled and really empty: say so
        assertEquals("0 个工具", Plugins.toolSummary(one(plugin(enabled = true, toolCount = 0))))
        assertEquals("", Plugins.toolSummary(one(plugin(status = "signature_changed"))))
        // enabled but the servers have not connected yet: tools are not known, do not say 0
        val idle = one(plugin(enabled = true, servers = """[{"name":"a","state":"idle","toolCount":0}]"""))
        assertTrue(Plugins.toolSummary(idle).contains("第一次连接后"))
    }

    @Test
    fun signatureProblemIsNotRepeatedInIssues() {
        val p = one(plugin(status = "signature_changed", extra = ""","problems2":1""").replace(""""problems":[]""", """"problems":[{"code":"signature_changed","message":"sig"},{"code":"other","message":"x"}]"""))
        assertEquals(listOf("问题：x"), Plugins.issues(p))
    }

    // ------------------------------------------------------------ 启用对话框

    @Test
    fun thirdPartyEnableDialogStatesTheRiskAndNamesThePackage() {
        val plan = Plugins.enablePlan(one(plugin()))
        assertEquals("启用", plan.confirmLabel)
        assertFalse(plan.confirmSignatureFirst)
        assertTrue(plan.message.contains("org.example.notes"))
        assertTrue(plan.message.contains("来自第三方"))
        assertTrue(plan.message.contains("写操作之前向你确认"))
        assertTrue(plan.message.contains("ab12 cd34 ef56"))
    }

    @Test
    fun signatureChangedIsConfirmedInTheSameDialog() {
        val plan = Plugins.enablePlan(one(plugin(status = "signature_changed", enabled = true)))
        assertTrue(plan.confirmSignatureFirst)
        assertEquals("确认新签名并启用", plan.confirmLabel)
        assertTrue(plan.message.contains("签名与之前不同"))
        assertTrue(plan.message.contains("org.example.notes"))
        assertTrue(plan.message.contains("ab12 cd34 ef56"))
        // never confirmed: said so, no invented value
        assertTrue(plan.message.contains("之前的签名：从未确认过"))
    }

    @Test
    fun signatureChangedShowsPreviousAndCurrentDigestsSideBySide() {
        val old = "ffff00001111" + "2".repeat(52)
        val plan = Plugins.enablePlan(one(plugin(status = "signature_changed", trusted = "\"$old\"")))
        assertTrue(plan.message.contains("之前的签名：ffff 0000 1111…"))
        assertTrue(plan.message.contains("现在的签名：ab12 cd34 ef56…"))
        assertTrue(plan.confirmSignatureFirst)
        // a hostile or malformed value is reduced to hex
        val bad = Plugins.enablePlan(one(plugin(status = "signature_changed", trusted = "\"ZZ<x>ff\"")))
        assertTrue(bad.message.contains("之前的签名：ff…"))
        // empty string is treated as never confirmed
        assertTrue(Plugins.enablePlan(one(plugin(status = "signature_changed", trusted = "\"\""))).message.contains("从未确认过"))
        assertNull(one(plugin()).trustedSigningDigest)
    }

    @Test
    fun unconfirmedSignatureAlsoNeedsTheExplicitDialog() {
        val plan = Plugins.enablePlan(one(plugin(status = "signature_unconfirmed")))
        assertTrue(plan.confirmSignatureFirst)
        assertTrue(plan.message.contains("记不清"))
    }

    @Test
    fun builtinPluginNeedsNoThirdPartyWarning() {
        val plan = Plugins.enablePlan(one(plugin(builtin = true)))
        assertFalse(plan.confirmSignatureFirst)
        assertTrue(plan.message.contains("自带"))
    }

    @Test
    fun hostileNameCannotBreakTheDialogFormatting() {
        val p = one(plugin().replace("备忘录", "%s%1\$d<b>x</b>\u202E"))
        val plan = Plugins.enablePlan(p)
        assertTrue(plan.message.contains("%s%1\$d<b>x</b>"))
        assertFalse(plan.message.contains('\u202E'))
    }

    // ------------------------------------------------------------ 工具

    private fun tool(risk: String, approval: String = "ask", may: Boolean = true, extra: String = "") =
        Plugins.parseTool(
            """{"name":"notes__main__add","pluginId":"org.example.notes","source":{"plugin":"notes","server":"main","tool":"add"},
            "title":"添加备忘录","description":"adds a note","risk":"$risk","enabled":true,"approval":"$approval","mayAlwaysAllow":$may$extra}""",
        )!!

    @Test
    fun alwaysAllowOnlyForWriteWhenPolicyAllowsAndCurrentlyAsk() {
        assertTrue(Plugins.canSetAlways(tool("write")))
        assertFalse(Plugins.canSetAlways(tool("write", may = false)))
        assertFalse(Plugins.canSetAlways(tool("write", approval = "always")))
        assertFalse(Plugins.canSetAlways(tool("read")))
        assertFalse("high risk never offers always, even if the host says mayAlwaysAllow", Plugins.canSetAlways(tool("high")))
        assertFalse(Plugins.canSetAlways(tool("high", may = true)))
    }

    @Test
    fun canRevokeAlways() {
        assertTrue(Plugins.canRemoveAlways(tool("write", approval = "always")))
        assertFalse(Plugins.canRemoveAlways(tool("write")))
    }

    @Test
    fun highRiskAlwaysShowsAsConfirmEveryTimeEvenIfPolicySaysAlways() {
        val t = tool("high", approval = "always")
        assertTrue(Plugins.approvalText(t).startsWith("每次确认"))
        assertFalse(Plugins.approvalText(t).contains("始终允许") && !Plugins.approvalText(t).contains("不能"))
        assertEquals("始终允许", Plugins.approvalText(tool("write", approval = "always")))
        assertEquals("每次确认", Plugins.approvalText(tool("write")))
        assertTrue(Plugins.approvalText(tool("read")).contains("只读"))
    }

    @Test
    fun unknownRiskAndMissingFieldsAreTreatedAsHigh() {
        val unknown = tool("cosmic")
        assertEquals(Plugins.Risk.HIGH, unknown.risk)
        assertFalse(Plugins.canSetAlways(unknown))
        val bare = Plugins.parseTool("""{"name":"a__b__c"}""")!!
        assertEquals(Plugins.Risk.HIGH, bare.risk)
        assertFalse(bare.mayAlwaysAllow)
        assertFalse(bare.enabled)
        assertNull(Plugins.parseTool("""{"title":"no name"}"""))
    }

    @Test
    fun toolDisplayPrefersTitleThenToolName() {
        assertEquals("添加备忘录", tool("write").display)
        val t = Plugins.parseTool("""{"name":"p__s__t","source":{"plugin":"p","server":"s","tool":"raw_name"},"risk":"read"}""")!!
        assertEquals("raw_name", t.display)
    }

    @Test
    fun parsesToolList() {
        val list = Plugins.parseTools(
            """[{"name":"a","risk":"read","enabled":true},{"name":"b","risk":"write","enabled":false,"approval":"always","mayAlwaysAllow":true},5]""",
        )
        assertEquals(listOf("a", "b"), list.map { it.name })
        assertEquals(Plugins.Approval.ALWAYS, list[1].approval)
    }

    // ------------------------------------------------------------ 策略和错误

    @Test
    fun policyStatesAreExplained() {
        val ok = Plugins.parsePolicy("""{"health":"ok","using":null,"reason":null,"failClosed":false}""")!!
        assertNull(Plugins.policyText(ok))
        val prev = Plugins.parsePolicy("""{"health":"corrupt","using":"previous","reason":"parse error","failClosed":false}""")!!
        assertTrue(Plugins.policyText(prev)!!.contains("上一份"))
        assertEquals("parse error", prev.reason)
        val backup = Plugins.parsePolicy("""{"health":"corrupt","using":"backup","failClosed":false}""")!!
        assertTrue(Plugins.policyText(backup)!!.contains("备份"))
        val closed = Plugins.parsePolicy("""{"health":"corrupt","using":"fail_closed","reason":"io","failClosed":true}""")!!
        assertTrue(Plugins.policyText(closed)!!.contains("一律按停用"))
        assertNotNull(Plugins.policyText(closed))
        // an unknown health value is not "ok"
        assertTrue(Plugins.parsePolicy("""{"health":"weird"}""")!!.corrupt)
    }

    @Test
    fun errorCodesAreMappedToUserText() {
        assertEquals("not_ready", Plugins.errorCode("agentos.ext.not_ready: scan not finished"))
        assertTrue(Plugins.errorText("agentos.ext.not_found: no such plugin").contains("找不到"))
        assertTrue(Plugins.errorText("agentos.ext.high_risk: x").contains("高风险"))
        assertEquals("操作失败，请稍后再试", Plugins.errorText("boom"))
        assertEquals("操作失败，请稍后再试", Plugins.errorText(null))
        assertFalse("raw exception text is not shown", Plugins.errorText("agentos.ext.internal: secret path /data/x").contains("/data"))
    }
}
