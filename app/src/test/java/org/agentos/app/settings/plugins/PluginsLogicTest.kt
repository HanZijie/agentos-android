package org.agentos.app.settings.plugins

import org.agentos.app.R
import org.agentos.app.i18n.ResStrings
import org.agentos.app.i18n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件页的纯逻辑。文案来自 [Strings]：中、英各一个实现（`ResStrings.zh` / `.en`，读真实的 `strings_p3.xml`），所以
 * 同一条逻辑在两种语言下都要对；整句用资源 id 取值（逻辑选对了哪一句），关键句另有英文的字面量断言（语义没有被弱化）。
 */
class PluginsLogicTest {
    private val zh = ResStrings.zh
    private val en = ResStrings.en
    private val both: List<Strings> = listOf(zh, en)
    private val digest = "ab12cd34ef56" + "0".repeat(52)

    private fun plugin(
        id: String = "org.example.notes", status: String = "ready", enabled: Boolean = false, builtin: Boolean = false,
        toolCount: Int = 0, extra: String = "", servers: String = "[]", trusted: String = "null", skillCount: Int = 0,
    ) = """{"id":"$id","packageName":"org.example.notes","name":"notes","displayName":"备忘录","versionName":"1.2","signingDigest":"$digest","trustedSigningDigest":$trusted,
        "status":"$status","builtin":$builtin,"enabled":$enabled,"toolCount":$toolCount,"skillCount":$skillCount,"servers":$servers,"problems":[],"unsupported":[]$extra}"""

    private fun one(json: String) = Plugins.parsePlugin(json)!!

    private fun msg(key: String, vararg args: String) = """{"key":"$key","args":[${args.joinToString(",") { "\"$it\"" }}]}"""

    // ------------------------------------------------------------ 解析

    @Test
    fun parsesAFullPlugin() {
        val p = one(
            """{"id":"org.example.notes","packageName":"org.example.notes","name":"notes","displayName":"备忘录","versionName":"1.2","signingDigest":"$digest",
            "status":"ready","builtin":false,"enabled":true,"approval":"ask","toolCount":3,"skillCount":1,
            "problems":[{"code":"x","message":${msg("ext_msg_not_json")},"location":"plugin.json"}],
            "unsupported":[{"kind":"stdio_server","location":"mcp.json › mcpServers.a","detail":${msg("ext_msg_stdio_unsupported")}}],
            "servers":[{"name":"main","state":"connected","toolCount":3},{"name":"b","state":"unreachable","error":"timeout","toolCount":0}],
            "rejectedServers":[{"name":"evil","reason":"not_exported"}],"skillProblems":[{"code":"s","message":${msg("ext_msg_skill_no_frontmatter")},"location":"broken"}]}""",
        )
        assertEquals("备忘录", p.displayName)
        assertEquals(Plugins.Status.READY, p.status)
        assertTrue(p.enabled)
        assertEquals(2, p.servers.size)
        for (s in both) {
            assertEquals(s.get(R.string.plugins_server_unreachable_reason, "timeout"), Plugins.serverStateText(p.servers[1], s))
            val issues = Plugins.issues(p, s)
            assertEquals(4, issues.size)
            // problem: location + the core message rendered in this language
            assertEquals(s.get(R.string.plugins_issue_problem, s.get(R.string.plugins_located, "plugin.json", s.get(R.string.ext_msg_not_json))), issues[0])
            assertTrue(issues.any { it.contains("stdio_server") && it.contains("mcp.json › mcpServers.a") && it.contains(s.get(R.string.ext_msg_stdio_unsupported)) })
            assertTrue(issues.any { it.contains("evil") && it.contains(s.get(R.string.plugins_rejected_not_exported)) })
            assertTrue(issues.any { it.contains("broken") && it.contains(s.get(R.string.ext_msg_skill_no_frontmatter)) })
        }
        assertEquals("Unreachable: timeout", Plugins.serverStateText(p.servers[1], en))
        assertEquals(
            listOf(
                "Problem: plugin.json: Not valid JSON",
                "Not supported: stdio_server mcp.json › mcpServers.a (A stdio server needs a process and an interpreter started on the phone; AgentOS does not support it)",
                "Ignored service “evil”: The service is not open to AgentOS; ignored",
                "Skill problem: broken: SKILL.md has no frontmatter (the file must start with --- and define name and description)",
            ),
            Plugins.issues(p, en),
        )
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
    fun aMessageThatCannotBeReadFallsBackToTheCodeNotACrash() {
        // a string where the message object should be, an unknown key, a missing argument list
        val p = one(
            plugin(
                extra = ""","x":1""",
            ).replace(
                """"problems":[]""",
                """"problems":[{"code":"old_style","message":"a sentence","location":""},{"code":"unknown_key","message":{"key":"ext_msg_from_the_future"}},{"code":"few_args","message":{"key":"ext_msg_duplicate_server","args":[]}}]""",
            ),
        )
        for (s in both) {
            val issues = Plugins.issues(p, s)
            assertEquals(s.get(R.string.plugins_issue_problem, "old_style"), issues[0])
            assertEquals(s.get(R.string.plugins_issue_problem, "ext_msg_from_the_future"), issues[1])
            // a missing argument is an empty one, never a crash
            assertEquals(s.get(R.string.plugins_issue_problem, s.get(R.string.ext_msg_duplicate_server, "")), issues[2])
        }
    }

    @Test
    fun unknownStatusIsUnavailableAndCannotBeSwitchedOn() {
        val p = one(plugin(status = "something_new"))
        assertEquals(Plugins.Status.UNAVAILABLE, p.status)
        assertFalse(p.switchEnabled)
        assertFalse(p.switchOn)
        for (s in both) assertTrue(Plugins.statusLine(p, s).second)
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
    fun packageNameKeepsOnlyWhatAnAndroidPackageCanContain() {
        assertEquals("org.example.notes", one(plugin()).packageName)
        assertEquals("org.evilx", one(plugin().replace("\"packageName\":\"org.example.notes\"", "\"packageName\":\"org.ev\\\"il\\u202E(x)\"")).packageName)
    }

    @Test
    fun digestIsReducedToHexAndGrouped() {
        val p = one(plugin().replace(digest, "AB12-CD34 ef56zz"))
        assertEquals("ab12cd34ef56", p.signingDigest)
        assertEquals("ab12 cd34 ef56", Digest.hex(p.signingDigest))
        assertEquals("ab12 cd34 ef56…", Plugins.digestShown(p.signingDigest, en))
        assertNull(Digest.hex(""))
        assertNull(Digest.hex(null))
        assertEquals("（无）", Plugins.digestShown("", zh))
        assertEquals("(none)", Plugins.digestShown(null, en))
    }

    // ------------------------------------------------------------ 状态和开关

    @Test
    fun statusLinesAndSwitchRules() {
        val ready = one(plugin(status = "ready", enabled = true))
        assertTrue(ready.switchOn)
        val changed = one(plugin(status = "signature_changed", enabled = true))
        assertFalse("a plugin that needs reconfirmation is never shown as on", changed.switchOn)
        assertTrue(changed.switchEnabled)
        assertTrue(changed.needsReconfirm)
        val unconfirmed = one(plugin(status = "signature_unconfirmed"))
        assertTrue(unconfirmed.needsReconfirm)
        val unavailable = one(plugin(status = "unavailable", extra = ""","unavailableReason":"assets_missing""""))
        assertFalse(unavailable.switchEnabled)

        for (s in both) {
            assertEquals(s.get(R.string.plugins_status_enabled) to false, Plugins.statusLine(ready, s))
            assertEquals(s.get(R.string.plugins_status_found_disabled) to false, Plugins.statusLine(one(plugin(enabled = false)), s))
            assertEquals(s.get(R.string.plugins_status_sig_changed) to true, Plugins.statusLine(changed, s))
            assertEquals(s.get(R.string.plugins_status_sig_unconfirmed) to true, Plugins.statusLine(unconfirmed, s))
            assertEquals(s.get(R.string.plugins_status_unavailable, s.get(R.string.plugins_unavailable_assets_missing)) to true, Plugins.statusLine(unavailable, s))
        }
        assertEquals("Enabled" to false, Plugins.statusLine(ready, en))
        assertEquals("Found, not enabled" to false, Plugins.statusLine(one(plugin(enabled = false)), en))
        assertTrue(Plugins.statusLine(changed, en).first.contains("signature changed"))
        assertTrue(Plugins.statusLine(changed, en).first.contains("turned off"))
        assertTrue(Plugins.statusLine(unconfirmed, en).first.contains("no reliable record"))
        assertEquals("Unavailable: No plugin package found in the app" to true, Plugins.statusLine(unavailable, en))
        assertEquals("不可用：App 里没有找到插件包" to true, Plugins.statusLine(unavailable, zh))
    }

    @Test
    fun everyUnavailableReasonAndRejectionHasATextInBothLanguages() {
        for (s in both) {
            val reasons = listOf("assets_missing", "manifest_rejected", "no_usable_server", "name_conflict", "something_new", null).map { Plugins.unavailableText(it, s) }
            // four known reasons, and the unknown one (also for null) shares a text
            assertEquals(5, reasons.distinct().size)
            assertEquals(reasons[4], reasons[5])
            val rejected = listOf("not_in_package", "not_exported", "missing_permission").map { Plugins.rejectedText(it, s) }
            assertEquals(3, rejected.distinct().size)
            assertTrue(Plugins.rejectedText("odd\"reason", s).contains("odd'reason"))
        }
    }

    @Test
    fun enabledButAllServersUnreachableIsFlagged() {
        val p = one(plugin(enabled = true, servers = """[{"name":"a","state":"unreachable","error":"x","toolCount":0}]"""))
        for (s in both) assertEquals(s.get(R.string.plugins_status_unreachable) to true, Plugins.statusLine(p, s))
        assertEquals("Enabled, but the plugin service cannot be reached", Plugins.statusLine(p, en).first)
    }

    @Test
    fun disabledPluginNeverReadsAsZeroTools() {
        for (s in both) {
            assertEquals(s.get(R.string.plugins_summary_enable_to_view), Plugins.toolSummary(one(plugin(enabled = false, toolCount = 0)), s))
            // enabled and really empty: say so
            assertEquals(s.plural(R.plurals.plugins_tools_count, 0, 0), Plugins.toolSummary(one(plugin(enabled = true, toolCount = 0)), s))
            assertEquals("", Plugins.toolSummary(one(plugin(status = "signature_changed")), s))
            // enabled but the servers have not connected yet: tools are not known, do not say 0
            val idle = one(plugin(enabled = true, servers = """[{"name":"a","state":"idle","toolCount":0}]"""))
            assertEquals(s.get(R.string.plugins_summary_after_connect), Plugins.toolSummary(idle, s))
        }
        assertEquals("Enable to see tools", Plugins.toolSummary(one(plugin(enabled = false, toolCount = 0)), en))
        assertEquals("3 tools", Plugins.toolSummary(one(plugin(enabled = true, toolCount = 3)), en))
        assertEquals("1 tool", Plugins.toolSummary(one(plugin(enabled = true, toolCount = 1)), en))
        assertEquals("0 tools", Plugins.toolSummary(one(plugin(enabled = true, toolCount = 0)), en))
        assertEquals("1 tool, 2 Skills", Plugins.toolSummary(one(plugin(enabled = true, toolCount = 1, skillCount = 2)), en))
        assertEquals("1 tool, 1 Skill", Plugins.toolSummary(one(plugin(enabled = true, toolCount = 1, skillCount = 1)), en))
        assertEquals("3 个工具", Plugins.toolSummary(one(plugin(enabled = true, toolCount = 3)), zh))
        assertEquals("3 个工具，1 个 Skill", Plugins.toolSummary(one(plugin(enabled = true, toolCount = 3, skillCount = 1)), zh))
        assertTrue(Plugins.toolSummary(one(plugin(enabled = true, servers = """[{"name":"a","state":"idle","toolCount":0}]""")), en).contains("first connection"))
    }

    @Test
    fun signatureProblemIsNotRepeatedInIssues() {
        val p = one(
            plugin(status = "signature_changed").replace(
                """"problems":[]""",
                """"problems":[{"code":"signature_changed","message":${msg("ext_msg_signature_changed")}},{"code":"other","message":${msg("ext_msg_not_json")}}]""",
            ),
        )
        for (s in both) assertEquals(listOf(s.get(R.string.plugins_issue_problem, s.get(R.string.ext_msg_not_json))), Plugins.issues(p, s))
    }

    // ------------------------------------------------------------ 启用对话框

    @Test
    fun thirdPartyEnableDialogStatesTheRiskAndNamesThePackage() {
        for (s in both) {
            val plan = Plugins.enablePlan(one(plugin()), s)
            assertEquals(s.get(R.string.plugins_enable_confirm), plan.confirmLabel)
            assertFalse(plan.confirmSignatureFirst)
            assertTrue(plan.message.contains("org.example.notes"))
            assertTrue("the warning that the plugin comes from a third party is in the dialog", plan.message.contains(s.get(R.string.plugins_enable_risk)))
            assertTrue(plan.message.contains("ab12 cd34 ef56"))
        }
        val plan = Plugins.enablePlan(one(plugin()), en)
        assertEquals("Enable", plan.confirmLabel)
        assertEquals("Enable third-party plugin?", plan.title)
        assertTrue(plan.message.contains("come from a third party"))
        assertTrue(plan.message.contains("AgentOS asks you before every write action"))
        assertTrue(plan.message.contains("high-risk actions are confirmed every time"))
        assertTrue(plan.message.contains("off by default"))
        assertTrue(Plugins.enablePlan(one(plugin()), zh).message.contains("写操作之前向你确认"))
    }

    @Test
    fun signatureChangedIsConfirmedInTheSameDialog() {
        for (s in both) {
            val plan = Plugins.enablePlan(one(plugin(status = "signature_changed", enabled = true)), s)
            assertTrue(plan.confirmSignatureFirst)
            assertEquals(s.get(R.string.plugins_enable_changed_confirm), plan.confirmLabel)
            assertTrue(plan.message.contains("org.example.notes"))
            assertTrue(plan.message.contains("ab12 cd34 ef56"))
            // never confirmed: said so, no invented value
            assertTrue(plan.message.contains(s.get(R.string.plugins_enable_prev_never)))
            assertTrue(plan.message.contains(s.get(R.string.plugins_enable_risk)))
        }
        val plan = Plugins.enablePlan(one(plugin(status = "signature_changed", enabled = true)), en)
        assertEquals("Confirm new signature and enable", plan.confirmLabel)
        assertTrue(plan.message.contains("differs from before"))
        assertTrue(plan.message.contains("someone may have swapped the installed package"))
        assertTrue(plan.message.contains("Previous signature: never confirmed"))
        assertTrue(plan.message.contains("Continue only if you are sure this app comes from a source you trust."))
    }

    @Test
    fun signatureChangedShowsPreviousAndCurrentDigestsSideBySide() {
        val old = "ffff00001111" + "2".repeat(52)
        for (s in both) {
            val plan = Plugins.enablePlan(one(plugin(status = "signature_changed", trusted = "\"$old\"")), s)
            assertTrue(plan.message.contains(s.get(R.string.plugins_enable_prev_digest, "ffff 0000 1111…")))
            assertTrue(plan.message.contains("ab12 cd34 ef56…"))
            assertTrue(plan.confirmSignatureFirst)
            // a hostile or malformed value is reduced to hex
            val bad = Plugins.enablePlan(one(plugin(status = "signature_changed", trusted = "\"ZZ<x>ff\"")), s)
            assertTrue(bad.message.contains(s.get(R.string.plugins_enable_prev_digest, "ff…")))
            // empty string is treated as never confirmed
            assertTrue(Plugins.enablePlan(one(plugin(status = "signature_changed", trusted = "\"\"")), s).message.contains(s.get(R.string.plugins_enable_prev_never)))
        }
        val plan = Plugins.enablePlan(one(plugin(status = "signature_changed", trusted = "\"$old\"")), en)
        assertTrue(plan.message.contains("Previous signature: ffff 0000 1111…"))
        assertTrue(plan.message.contains("Current signature: ab12 cd34 ef56…"))
        assertNull(one(plugin()).trustedSigningDigest)
    }

    @Test
    fun unconfirmedSignatureAlsoNeedsTheExplicitDialog() {
        for (s in both) {
            val plan = Plugins.enablePlan(one(plugin(status = "signature_unconfirmed")), s)
            assertTrue(plan.confirmSignatureFirst)
            assertEquals(s.get(R.string.plugins_enable_unconfirmed_confirm), plan.confirmLabel)
            assertTrue(plan.message.contains(s.get(R.string.plugins_enable_risk)))
        }
        val plan = Plugins.enablePlan(one(plugin(status = "signature_unconfirmed")), en)
        assertTrue(plan.message.contains("cannot recall the signature"))
        assertTrue(plan.message.contains("need to confirm it again"))
    }

    @Test
    fun builtinPluginNeedsNoThirdPartyWarning() {
        for (s in both) {
            val plan = Plugins.enablePlan(one(plugin(builtin = true)), s)
            assertFalse(plan.confirmSignatureFirst)
            assertEquals(s.get(R.string.plugins_enable_builtin_title), plan.title)
            assertFalse(plan.message.contains(s.get(R.string.plugins_enable_risk)))
        }
        assertTrue(Plugins.enablePlan(one(plugin(builtin = true)), en).message.contains("built into AgentOS"))
        assertTrue(Plugins.enablePlan(one(plugin(builtin = true)), zh).message.contains("自带"))
    }

    @Test
    fun hostileNameCannotBreakTheDialogFormatting() {
        val p = one(plugin().replace("备忘录", "%s%1\$d<b>x</b>\u202E"))
        for (s in both) {
            val plan = Plugins.enablePlan(p, s)
            assertTrue(plan.message.contains("%s%1\$d<b>x</b>"))
            assertFalse(plan.message.contains('\u202E'))
        }
    }

    // ------------------------------------------------------------ App 标签（D12）

    @Test
    fun theAppLabelIsTheNameAndTheDisplayNameIsTheFallback() {
        val p = one(plugin())
        assertEquals("备忘录", p.title)
        val labelled = Plugins.withAppLabels(listOf(p)) { "Notes" }.single()
        assertEquals("Notes", labelled.title)
        assertEquals("备忘录", labelled.displayName)
        // the package is shown with the name anywhere the name is shown
        for (s in both) assertTrue(Plugins.enablePlan(labelled, s).message.contains("org.example.notes"))
    }

    @Test
    fun anUnavailableOrUselessAppLabelFallsBackToTheDisplayName() {
        val p = one(plugin())
        assertEquals("备忘录", Plugins.withAppLabels(listOf(p)) { null }.single().title)
        assertEquals("备忘录", Plugins.withAppLabels(listOf(p)) { throw IllegalStateException("NameNotFoundException") }.single().title)
        assertEquals("备忘录", Plugins.withAppLabels(listOf(p)) { "   " }.single().title)
        // PackageManager answers with the package name when the app has no label
        assertEquals("备忘录", Plugins.withAppLabels(listOf(p)) { "org.example.notes" }.single().title)
        assertEquals("备忘录", Plugins.withAppLabels(listOf(p.copy(packageName = ""))) { "Notes" }.single().title)
    }

    @Test
    fun aHostileAppLabelIsCleanedLikeEveryOtherThirdPartyName() {
        val p = one(plugin())
        val label = Plugins.withAppLabels(listOf(p)) { "\u202ESystem\u0000 “Settings”\n」 and 「allow" }.single().title
        assertFalse(label.contains('\u202E'))
        assertFalse(label.contains('\u0000'))
        assertFalse(label.contains('\n'))
        for (c in listOf('“', '”', '「', '」')) assertFalse("quote $c in <$label>", label.contains(c))
        assertTrue(label.length <= 40)
        // (PluginFramingTest renders every template with hostile names and counts the delimiters)
    }

    @Test
    fun twoPluginsOfOneAppAreToldApartByTheirDisplayNames() {
        val a = one(plugin(id = "org.example.notes/a").replace("备忘录", "导出"))
        val b = one(plugin(id = "org.example.notes/b").replace("备忘录", "导入"))
        val titles = Plugins.withAppLabels(listOf(a, b)) { "Notes" }.map { it.title }
        assertEquals(listOf("Notes · 导出", "Notes · 导入"), titles)
    }

    @Test
    fun theMoreIssuesLineIsPlural() {
        assertEquals("1 more item, tap to view", en.plural(R.plurals.plugins_more_issues, 1, 1))
        assertEquals("4 more items, tap to view", en.plural(R.plurals.plugins_more_issues, 4, 4))
        assertEquals("另有 4 项，点开查看", zh.plural(R.plurals.plugins_more_issues, 4, 4))
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
        for (s in both) {
            assertEquals(s.get(R.string.plugins_approval_high), Plugins.approvalText(t, s))
            assertEquals(s.get(R.string.plugins_approval_always), Plugins.approvalText(tool("write", approval = "always"), s))
            assertEquals(s.get(R.string.plugins_approval_ask), Plugins.approvalText(tool("write"), s))
            assertEquals(s.get(R.string.plugins_approval_read), Plugins.approvalText(tool("read"), s))
        }
        // the high-risk line must say it cannot be "always allow" (it keeps the warning in English too)
        assertEquals("Asks every time (high risk, cannot be set to Always allow)", Plugins.approvalText(t, en))
        assertEquals("Always allow", Plugins.approvalText(tool("write", approval = "always"), en))
        assertEquals("Asks every time", Plugins.approvalText(tool("write"), en))
        assertEquals("No confirmation needed (read-only)", Plugins.approvalText(tool("read"), en))
        assertTrue(Plugins.approvalText(t, zh).contains("不能设为始终允许"))
    }

    @Test
    fun riskLabelsAreTranslated() {
        assertEquals("Read-only", Plugins.riskText(Plugins.Risk.READ, en))
        assertEquals("Write", Plugins.riskText(Plugins.Risk.WRITE, en))
        assertEquals("High risk (may be irreversible)", Plugins.riskText(Plugins.Risk.HIGH, en))
        assertEquals("高风险（可能不可恢复）", Plugins.riskText(Plugins.Risk.HIGH, zh))
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
        val prev = Plugins.parsePolicy("""{"health":"corrupt","using":"previous","reason":"parse error","failClosed":false}""")!!
        val backup = Plugins.parsePolicy("""{"health":"corrupt","using":"backup","failClosed":false}""")!!
        val closed = Plugins.parsePolicy("""{"health":"corrupt","using":"fail_closed","reason":"io","failClosed":true}""")!!
        assertEquals("parse error", prev.reason)
        for (s in both) {
            assertNull(Plugins.policyText(ok, s))
            assertEquals(s.get(R.string.plugins_policy_previous), Plugins.policyText(prev, s))
            assertEquals(s.get(R.string.plugins_policy_backup), Plugins.policyText(backup, s))
            assertEquals(s.get(R.string.plugins_policy_fail_closed), Plugins.policyText(closed, s))
            assertNotNull(Plugins.policyText(closed, s))
        }
        assertTrue(Plugins.policyText(closed, en)!!.contains("all third-party plugins are treated as off"))
        assertTrue(Plugins.policyText(closed, zh)!!.contains("一律按停用"))
        assertTrue(Plugins.policyText(prev, en)!!.contains("last valid policy"))
        assertTrue(Plugins.policyText(backup, en)!!.contains("backup"))
        // an unknown health value is not "ok"
        assertTrue(Plugins.parsePolicy("""{"health":"weird"}""")!!.corrupt)
        for (s in both) assertEquals(s.get(R.string.plugins_policy_other), Plugins.policyText(Plugins.parsePolicy("""{"health":"weird"}""")!!, s))
    }

    @Test
    fun errorCodesAreMappedToUserText() {
        assertEquals("not_ready", Plugins.errorCode("agentos.ext.not_ready: scan not finished"))
        for (s in both) {
            assertEquals(s.get(R.string.plugins_error_not_found), Plugins.errorText("agentos.ext.not_found: no such plugin", s))
            assertEquals(s.get(R.string.plugins_error_high_risk), Plugins.errorText("agentos.ext.high_risk: x", s))
            assertEquals(s.get(R.string.plugins_error_not_ready), Plugins.errorText("agentos.ext.not_ready: x", s))
            assertEquals(s.get(R.string.plugins_error_not_needed), Plugins.errorText("agentos.ext.not_needed: x", s))
            assertEquals(s.get(R.string.plugins_error_bad_mode), Plugins.errorText("agentos.ext.bad_mode: x", s))
            assertEquals(s.get(R.string.plugins_error_generic), Plugins.errorText("boom", s))
            assertEquals(s.get(R.string.plugins_error_generic), Plugins.errorText(null, s))
            assertFalse("raw exception text is not shown", Plugins.errorText("agentos.ext.internal: secret path /data/x", s).contains("/data"))
        }
        assertEquals("High-risk tools cannot be set to “Always allow”", Plugins.errorText("agentos.ext.high_risk: x", en))
        assertEquals("Action failed. Please try again later.", Plugins.errorText("boom", en))
        assertEquals("操作失败，请稍后再试", Plugins.errorText("boom", zh))
    }
}
