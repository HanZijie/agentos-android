package org.agentos.app.ext

import org.agentos.app.R
import org.agentos.app.i18n.Strings
import org.agentos.extensions.ExtMessages
import org.agentos.runtime.i18n.MessageRef

/**
 * `core/extensions` 的校验信息（[ExtMessages] 的 key + 参数）→ app 资源（`values/strings_p3.xml`、`values-en/strings_p3.xml`）。
 * 和 [org.agentos.app.i18n.CoreMessages] 同一个做法，单独一张表，因为这些 key 属于插件子系统。加一个 key，就在 [ExtMessages] 里登记，
 * 在这里加一行，并在中英文资源里各加一条（`ExtCoreMessagesTest` 逐项检查：资源齐全、占位符个数一致）。
 *
 * 参数是第三方文字（插件名、服务器名、目录名…），**渲染前必须已经清理**（`Plugins.frame`：控制字符、双向控制符、截断，所有语言的引号换成 `'`、括号去掉），
 * 这里不再处理；`Plugins` 解析 :ext 的 JSON 时统一清理。
 */
object ExtCoreMessages {
    class Entry(val id: Int, val arity: Int)

    private val table: Map<String, Entry> = mapOf(
        ExtMessages.NOT_JSON to Entry(R.string.ext_msg_not_json, 0),
        ExtMessages.ROOT_NOT_OBJECT to Entry(R.string.ext_msg_root_not_object, 0),
        ExtMessages.MISSING_REQUIRED to Entry(R.string.ext_msg_missing_required, 1),
        ExtMessages.MUST_BE_VALUE to Entry(R.string.ext_msg_must_be_value, 1),
        ExtMessages.MUST_BE_STRING to Entry(R.string.ext_msg_must_be_string, 0),
        ExtMessages.MUST_BE_OBJECT to Entry(R.string.ext_msg_must_be_object, 0),
        ExtMessages.MUST_BE_STRING_ARRAY to Entry(R.string.ext_msg_must_be_string_array, 0),
        ExtMessages.MUST_BE_NONEMPTY_STRING to Entry(R.string.ext_msg_must_be_nonempty_string, 0),
        ExtMessages.MUST_BE_STRING_MAP to Entry(R.string.ext_msg_must_be_string_map, 0),
        ExtMessages.FIELD_NOT_ALLOWED to Entry(R.string.ext_msg_field_not_allowed, 0),
        ExtMessages.PLUGIN_FIELD_NOT_ALLOWED to Entry(R.string.ext_msg_plugin_field_not_allowed, 0),
        ExtMessages.STDIO_FIELD_NOT_ALLOWED to Entry(R.string.ext_msg_stdio_field_not_allowed, 0),
        ExtMessages.NAME_LENGTH to Entry(R.string.ext_msg_name_length, 1),
        ExtMessages.NAME_PATTERN to Entry(R.string.ext_msg_name_pattern, 0),
        ExtMessages.SECTION_MUST_BE_OBJECT to Entry(R.string.ext_msg_section_must_be_object, 0),
        ExtMessages.BINDER_NOT_ALLOWED to Entry(R.string.ext_msg_binder_not_allowed, 0),
        ExtMessages.ORG_SERVERS_SHAPE to Entry(R.string.ext_msg_org_servers_shape, 0),
        ExtMessages.ORG_SERVER_SHAPE to Entry(R.string.ext_msg_org_server_shape, 0),
        ExtMessages.SERVER_NAME_EMPTY to Entry(R.string.ext_msg_server_name_empty, 0),
        ExtMessages.SERVICE_CLASS_NAME to Entry(R.string.ext_msg_service_class_name, 0),
        ExtMessages.DUPLICATE_SERVER to Entry(R.string.ext_msg_duplicate_server, 1),
        ExtMessages.SERVER_TYPE_MISSING to Entry(R.string.ext_msg_server_type_missing, 0),
        ExtMessages.SERVER_TYPE_UNKNOWN to Entry(R.string.ext_msg_server_type_unknown, 1),
        ExtMessages.ENV_RESERVED to Entry(R.string.ext_msg_env_reserved, 0),
        ExtMessages.CWD_PREFIX to Entry(R.string.ext_msg_cwd_prefix, 0),
        ExtMessages.ICON_PATH_UNSAFE to Entry(R.string.ext_msg_icon_path_unsafe, 1),
        ExtMessages.STDIO_UNSUPPORTED to Entry(R.string.ext_msg_stdio_unsupported, 0),
        ExtMessages.SSE_UNSUPPORTED to Entry(R.string.ext_msg_sse_unsupported, 0),
        ExtMessages.URL_INVALID to Entry(R.string.ext_msg_url_invalid, 0),
        ExtMessages.URL_NOT_HTTPS to Entry(R.string.ext_msg_url_not_https, 0),
        ExtMessages.URL_NO_HOST to Entry(R.string.ext_msg_url_no_host, 0),
        ExtMessages.URL_USERINFO to Entry(R.string.ext_msg_url_userinfo, 0),
        ExtMessages.ASSETS_NOT_DECLARED to Entry(R.string.ext_msg_assets_not_declared, 0),
        ExtMessages.ASSETS_NO_PLUGIN_JSON to Entry(R.string.ext_msg_assets_no_plugin_json, 1),
        ExtMessages.SERVER_REJECTED_NOT_IN_PACKAGE to Entry(R.string.ext_msg_server_rejected_not_in_package, 2),
        ExtMessages.SERVER_REJECTED_NOT_EXPORTED to Entry(R.string.ext_msg_server_rejected_not_exported, 2),
        ExtMessages.SERVER_REJECTED_MISSING_PERMISSION to Entry(R.string.ext_msg_server_rejected_missing_permission, 3),
        ExtMessages.SIGNATURE_CHANGED to Entry(R.string.ext_msg_signature_changed, 0),
        ExtMessages.SIGNATURE_UNCONFIRMED to Entry(R.string.ext_msg_signature_unconfirmed, 0),
        ExtMessages.NO_USABLE_SERVER to Entry(R.string.ext_msg_no_usable_server, 0),
        ExtMessages.NAME_CONFLICT to Entry(R.string.ext_msg_name_conflict, 1),
        ExtMessages.SKILL_NO_FRONTMATTER to Entry(R.string.ext_msg_skill_no_frontmatter, 0),
        ExtMessages.SKILL_FRONTMATTER_UNCLOSED to Entry(R.string.ext_msg_skill_frontmatter_unclosed, 0),
        ExtMessages.SKILL_KEY_DUPLICATE to Entry(R.string.ext_msg_skill_key_duplicate, 1),
        ExtMessages.SKILL_NAME_MISSING to Entry(R.string.ext_msg_skill_name_missing, 0),
        ExtMessages.SKILL_DESCRIPTION_MISSING to Entry(R.string.ext_msg_skill_description_missing, 0),
        ExtMessages.SKILL_DOUBLE_QUOTE_UNCLOSED to Entry(R.string.ext_msg_skill_double_quote_unclosed, 1),
        ExtMessages.SKILL_SINGLE_QUOTE_UNCLOSED to Entry(R.string.ext_msg_skill_single_quote_unclosed, 1),
        ExtMessages.SKILL_FILE_UNREADABLE to Entry(R.string.ext_msg_skill_file_unreadable, 0),
        ExtMessages.SKILL_FILE_ERROR to Entry(R.string.ext_msg_skill_file_error, 0),
        ExtMessages.SKILL_FILE_MISSING to Entry(R.string.ext_msg_skill_file_missing, 0),
        ExtMessages.SKILL_FILE_NOT_TEXT to Entry(R.string.ext_msg_skill_file_not_text, 0),
        ExtMessages.SKILL_NAME_INVALID to Entry(R.string.ext_msg_skill_name_invalid, 1),
        ExtMessages.SKILL_DIR_INVALID to Entry(R.string.ext_msg_skill_dir_invalid, 1),
    )

    fun entry(key: String): Entry? = table[key]

    /** 登记了的全部 key（测试用）。 */
    val keys: Set<String> get() = table.keys

    /** 一句话：key 不认识时显示 key 本身（不崩，也一眼看得出）；参数不够补空串。 */
    fun render(ref: MessageRef, strings: Strings): String {
        val entry = table[ref.key] ?: return ref.key
        val args = ref.args + List((entry.arity - ref.args.size).coerceAtLeast(0)) { "" }
        return strings.get(entry.id, *args.toTypedArray())
    }
}
