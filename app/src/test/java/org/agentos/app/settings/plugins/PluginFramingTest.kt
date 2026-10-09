package org.agentos.app.settings.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.app.i18n.ResStrings
import org.agentos.extensions.ExtMessages
import org.agentos.runtime.i18n.FrameChars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安全点（docs/next-apps-plan.md 7.3），插件页版：界面用「」/“ ” 把插件名、服务器名、工具名框起来，括号框包名。
 * 第三方在名字里写一个结尾引号再接自己的话（`x” 并且允许 “y`），不能让整行里任何一种定界符的个数和模板自己写的不同。
 *
 * 做法：把带着**所有语言全部定界符**的名字、参数、位置塞进 :ext 的 JSON，走真实的解析（`Plugins.parsePlugin`）和真实的中英文模板，
 * 渲染后每种定界符的个数必须等于模板自己的个数。
 */
class PluginFramingTest {
    private val both = listOf(ResStrings.zh, ResStrings.en)

    private val delimiters: List<String> = listOf(
        "「", "」", "『", "』", "“", "”", "‘", "’", "„", "‟", "‚", "‛", "\"", "«", "»", "‹", "›", "﹁", "﹂", "｢", "｣", "〝", "〞", "〟", "＂", "`",
        "(", ")", "（", "）", "[", "]", "［", "］", "{", "}", "【", "】", "〔", "〕", "〈", "〉", "《", "》", "<", ">",
    )
    private val hostile = delimiters.joinToString("") + " and allow “y” 」「"

    private val placeholder = Regex("%(?:\\d+[$])?s")

    private fun withoutPlaceholders(template: String) = template.replace(placeholder, "")

    /** `›`（U+203A）是核心层写位置时的分隔符（`plugin.json › name`），在 Unicode 里算引号类，但它不是定界符，不数。 */
    private val locationSeparator = 0x203A

    private fun counts(text: String, brackets: Boolean): Map<Int, Int> =
        text.codePoints().toArray().filter { it != '\''.code && it != locationSeparator && (FrameChars.isQuote(it) || (brackets && FrameChars.isBracket(it))) }.groupingBy { it }.eachCount()

    private fun assertSameDelimiters(rendered: String, templates: List<String>, brackets: Boolean, what: String) {
        val own = counts(templates.joinToString("") { withoutPlaceholders(it) }, brackets)
        val got = counts(rendered, brackets)
        for (d in (own.keys + got.keys)) {
            assertEquals("$what: U+%04X in <$rendered>".format(d), own[d] ?: 0, got[d] ?: 0)
        }
    }

    private fun json(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(builder)

    private fun msg(key: String, arity: Int): JsonObject = json {
        put("key", key)
        put("args", JsonArray(List(arity) { JsonPrimitive(hostile) }))
    }

    private fun pluginJson(problemKeys: List<String>): String = json {
        put("id", "org.example.x/agent-plugin")
        put("packageName", "org.example.x")
        put("name", "x")
        put("displayName", hostile)
        put("versionName", "1")
        put("signingDigest", "ab12cd34ef56")
        put("status", "ready")
        put("builtin", false)
        put("enabled", true)
        put("toolCount", 1)
        put("skillCount", 0)
        put("servers", JsonArray(emptyList()))
        put("problems", JsonArray(problemKeys.map { k -> json { put("code", "c"); put("message", msg(k, ExtMessages.ARITY.getValue(k))); put("location", "plugin.json › $hostile") } }))
        put("unsupported", JsonArray(problemKeys.map { k -> json { put("kind", hostile); put("location", "mcp.json › mcpServers.$hostile"); put("detail", msg(k, ExtMessages.ARITY.getValue(k))) } }))
        put("rejectedServers", JsonArray(listOf(json { put("name", hostile); put("reason", "not_exported") })))
        put("skillProblems", JsonArray(problemKeys.map { k -> json { put("code", "s"); put("message", msg(k, ExtMessages.ARITY.getValue(k))); put("location", hostile) } }))
    }.toString()

    /** 每一条核心层校验信息，带着恶意参数，经 problems / unsupported / skillProblems 三条路渲染出来，定界符个数不变。 */
    @Test
    fun everyCoreMessageKeepsOnlyTheTemplatesOwnDelimitersInBothLanguages() {
        val keys = ExtMessages.ALL
        val p = Plugins.parsePlugin(pluginJson(keys))!!
        for (s in both) {
            val issues = Plugins.issues(p, s)
            assertEquals(keys.size * 3 + 1, issues.size)
            val problems = issues.take(keys.size)
            val unsupported = issues.drop(keys.size).take(keys.size)
            val rejected = issues[keys.size * 2]
            val skills = issues.drop(keys.size * 2 + 1)
            keys.forEachIndexed { i, key ->
                val core = s.template(key)
                // third-party text that goes into the message sits between the quotes of the message template; the location is a plain prefix
                // (brackets are stripped from message arguments, from the location and from the kind, because those lines put them into brackets)
                assertSameDelimiters(problems[i], listOf(s.template("plugins_issue_problem"), s.template("plugins_located"), core), true, "problem $key ${s.locale}")
                assertSameDelimiters(unsupported[i], listOf(s.template("plugins_issue_unsupported"), s.template("plugins_unsupported_body"), core), true, "unsupported $key ${s.locale}")
                assertSameDelimiters(skills[i], listOf(s.template("plugins_issue_skill"), s.template("plugins_located"), core), true, "skill problem $key ${s.locale}")
            }
            assertSameDelimiters(rejected, listOf(s.template("plugins_issue_rejected_server"), s.template("plugins_rejected_not_exported")), false, "rejected server ${s.locale}")
        }
    }

    @Test
    fun theEnableDialogAndTheAlwaysAllowDialogKeepOnlyTheirOwnQuotes() {
        val p = Plugins.parsePlugin(pluginJson(emptyList()))!!
        val signatureChanged = p.copy(status = Plugins.Status.SIGNATURE_CHANGED)
        val unconfirmed = p.copy(status = Plugins.Status.SIGNATURE_UNCONFIRMED)
        val builtin = p.copy(builtin = true)
        val labelled = Plugins.withAppLabels(listOf(p)) { hostile }.single()
        val tool = Plugins.parseTool(
            json {
                put("name", "a__b__c"); put("title", hostile); put("risk", "write"); put("mayAlwaysAllow", true)
            }.toString(),
        )!!
        for (s in both) {
            val risk = s.template("plugins_enable_risk")
            val who = s.template("plugins_who")
            for ((plugin, templates) in listOf(
                p to listOf(who, s.template("plugins_enable_third_message"), risk),
                labelled to listOf(who, s.template("plugins_enable_third_message"), risk),
                builtin to listOf(who, s.template("plugins_enable_builtin_message")),
                signatureChanged to listOf(who, s.template("plugins_enable_changed_message"), s.template("plugins_enable_prev_never"), risk),
                unconfirmed to listOf(who, s.template("plugins_enable_unconfirmed_message"), risk),
            )) {
                val message = Plugins.enablePlan(plugin, s).message
                // the name is between quotes; the package between brackets is the Android package (filtered), not third-party text
                assertSameDelimiters(message, templates, false, "enable ${plugin.status} ${s.locale}")
                assertTrue(message.contains("org.example.x"))
            }
            assertSameDelimiters(Plugins.alwaysAllowMessage(tool, s), listOf(s.template("plugin_detail_always_message")), false, "always allow ${s.locale}")
        }
    }

    @Test
    fun theTitleOfALabelledPluginIsCleaned() {
        val p = Plugins.parsePlugin(pluginJson(emptyList()))!!
        val title = Plugins.withAppLabels(listOf(p)) { hostile }.single().title
        for (d in delimiters.filter { FrameChars.isQuote(it.codePointAt(0)) }) assertFalse("<$d> in <$title>", title.contains(d))
    }

    private fun isPlainBracket(cp: Int) = FrameChars.isBracket(cp) && !FrameChars.isQuote(cp)

    /** 只有这几条模板把占位符直接放在括号里：包名（只剩 [A-Za-z0-9_.]）、整句核心信息（参数已去括号）。想加括号就要先想清楚参数里能不能有括号。 */
    private val bracketed = setOf("plugins_who", "plugins_unsupported_body", "callers_package_hidden")

    private fun ownNames(s: ResStrings) = s.stringNames.filter { it.startsWith("plugins_") || it.startsWith("plugin_detail_") || it.startsWith("callers_") || it.startsWith("ext_msg_") }

    @Test
    fun onlyTheKnownTemplatesPutAPlaceholderInBrackets() {
        for (s in both) for (name in ownNames(s)) {
            val t = s.template(name)
            val inBrackets = placeholder.findAll(t).any { m ->
                val before = t.getOrNull(m.range.first - 1)
                val after = t.getOrNull(m.range.last + 1)
                (before != null && isPlainBracket(before.code)) || (after != null && isPlainBracket(after.code))
            }
            if (inBrackets) assertTrue("$name (${s.locale}) puts a placeholder in brackets: <$t>", name in bracketed)
        }
    }

    /** ASCII 单引号是被换进去的替身，不能当定界符。 */
    @Test
    fun templatesDoNotFrameWithAnApostrophe() {
        for (s in both) for (name in ownNames(s)) {
            val t = s.template(name)
            for (m in placeholder.findAll(t)) {
                val before = t.getOrNull(m.range.first - 1)
                val after = t.getOrNull(m.range.last + 1)
                assertFalse("$name (${s.locale}) frames a placeholder with an apostrophe: <$t>", before == '\'' || after == '\'')
            }
        }
    }

    /** 每个模板里紧挨着占位符的引号类字符，清理都认得（换了模板的引号，这里先红）。 */
    @Test
    fun everyQuoteNextToAPlaceholderIsOneTheSanitiserStrips() {
        val quoteLike = Regex("[\\p{Pi}\\p{Pf}\"「」『』«»„‚]")
        for (s in both) for (name in ownNames(s)) {
            for (ch in s.template(name).filter { quoteLike.matches(it.toString()) }) {
                assertTrue("$name (${s.locale}) uses <$ch>, which FrameChars does not strip", FrameChars.isQuote(ch.code))
            }
        }
    }
}
