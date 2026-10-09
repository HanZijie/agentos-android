package org.agentos.extensions

/**
 * 插件校验、扫描和 Skill 读取产生的、**给用户看**的文案 key（[org.agentos.runtime.i18n.MessageRef.key]）。核心层只给 key 和参数，
 * 用哪种语言、用什么引号框参数由 app 层定（`org.agentos.app.ext.ExtCoreMessages` 把 key 映射到 `values/strings_p3.xml` 与
 * `values-en/strings_p3.xml` 的同名资源）。名字和资源名相同；参数顺序就是模板里 `%1$s`、`%2$s` 的顺序。
 *
 * **参数里的第三方文字**（插件名、服务器名、目录名、frontmatter 的键、图标路径、App 里的类名）这里不清理：app 层渲染前统一清理
 * （控制字符、双向控制符、截断，以及所有语言的引号和括号，见 `Plugins.frame`），所以模板可以用任何引号把它们框起来。
 *
 * 不在这里的：给**模型**看的文字（`read_skill` 的错误、工具结果）一律英文，不本地化（docs/next-apps-plan.md R2）。
 */
object ExtMessages {
    private val arity = LinkedHashMap<String, Int>()

    private fun def(key: String, args: Int = 0): String {
        check(arity.put(key, args) == null) { "duplicate message key $key" }
        return key
    }

    // ------------------------------------------------------------------ ManifestReader：plugin.json / mcp.json

    /** 不是合法的 JSON。 */
    val NOT_JSON = def("ext_msg_not_json")

    /** 根必须是 JSON 对象。 */
    val ROOT_NOT_OBJECT = def("ext_msg_root_not_object")

    /** 缺少必填项。参数：字段名（`$schema`、`name`、`command`…，规范里的固定名字）。 */
    val MISSING_REQUIRED = def("ext_msg_missing_required", 1)

    /** 必须是某个固定值（schema 的地址）。参数：这个值。 */
    val MUST_BE_VALUE = def("ext_msg_must_be_value", 1)
    val MUST_BE_STRING = def("ext_msg_must_be_string")
    val MUST_BE_OBJECT = def("ext_msg_must_be_object")
    val MUST_BE_STRING_ARRAY = def("ext_msg_must_be_string_array")
    val MUST_BE_NONEMPTY_STRING = def("ext_msg_must_be_nonempty_string")

    /** 必须是 字符串 → 字符串 的对象。 */
    val MUST_BE_STRING_MAP = def("ext_msg_must_be_string_map")

    /** 不允许的字段（通用）。 */
    val FIELD_NOT_ALLOWED = def("ext_msg_field_not_allowed")

    /** plugin.json 顶层不允许的字段（带说明：客户端专属内容放在 extensions 下）。 */
    val PLUGIN_FIELD_NOT_ALLOWED = def("ext_msg_plugin_field_not_allowed")

    /** stdio 服务器不允许的字段。 */
    val STDIO_FIELD_NOT_ALLOWED = def("ext_msg_stdio_field_not_allowed")

    /** name 的长度。参数：上限。 */
    val NAME_LENGTH = def("ext_msg_name_length", 1)
    val NAME_PATTERN = def("ext_msg_name_pattern")
    val SECTION_MUST_BE_OBJECT = def("ext_msg_section_must_be_object")

    /** 导入的插件包不能声明本地 App 的 Binder 服务。 */
    val BINDER_NOT_ALLOWED = def("ext_msg_binder_not_allowed")

    /** `org.agentos.mcpServers` 必须是对象：服务器名 → { "service": … }。 */
    val ORG_SERVERS_SHAPE = def("ext_msg_org_servers_shape")

    /** 其中一个服务器必须是 { "service": … }。 */
    val ORG_SERVER_SHAPE = def("ext_msg_org_server_shape")
    val SERVER_NAME_EMPTY = def("ext_msg_server_name_empty")
    val SERVICE_CLASS_NAME = def("ext_msg_service_class_name")

    /** 同名服务器出现了不止一次。参数：服务器名。 */
    val DUPLICATE_SERVER = def("ext_msg_duplicate_server", 1)
    val SERVER_TYPE_MISSING = def("ext_msg_server_type_missing")

    /** 未知的服务器类型。参数：类型的原文。 */
    val SERVER_TYPE_UNKNOWN = def("ext_msg_server_type_unknown", 1)
    val ENV_RESERVED = def("ext_msg_env_reserved")
    val CWD_PREFIX = def("ext_msg_cwd_prefix")

    /** 图标路径不安全。参数：路径原文。 */
    val ICON_PATH_UNSAFE = def("ext_msg_icon_path_unsafe", 1)
    val STDIO_UNSUPPORTED = def("ext_msg_stdio_unsupported")
    val SSE_UNSUPPORTED = def("ext_msg_sse_unsupported")
    val URL_INVALID = def("ext_msg_url_invalid")
    val URL_NOT_HTTPS = def("ext_msg_url_not_https")
    val URL_NO_HOST = def("ext_msg_url_no_host")
    val URL_USERINFO = def("ext_msg_url_userinfo")

    // ------------------------------------------------------------------ PluginScanLogic

    /** Service 没有声明 `org.agentos.plugin.assets`。 */
    val ASSETS_NOT_DECLARED = def("ext_msg_assets_not_declared")

    /** `assets/<目录>` 里没有 plugin.json。参数：目录名。 */
    val ASSETS_NO_PLUGIN_JSON = def("ext_msg_assets_no_plugin_json", 1)

    /** 服务器被拒绝：Service 不属于这个 App。参数：服务器名、Service 类名。 */
    val SERVER_REJECTED_NOT_IN_PACKAGE = def("ext_msg_server_rejected_not_in_package", 2)

    /** 服务器被拒绝：Service 没有导出。参数：服务器名、Service 类名。 */
    val SERVER_REJECTED_NOT_EXPORTED = def("ext_msg_server_rejected_not_exported", 2)

    /** 服务器被拒绝：Service 没有要求绑定权限。参数：服务器名、Service 类名、权限名。 */
    val SERVER_REJECTED_MISSING_PERMISSION = def("ext_msg_server_rejected_missing_permission", 3)
    val SIGNATURE_CHANGED = def("ext_msg_signature_changed")
    val SIGNATURE_UNCONFIRMED = def("ext_msg_signature_unconfirmed")
    val NO_USABLE_SERVER = def("ext_msg_no_usable_server")

    /** 插件名已被占用或是保留的名字。参数：插件名。 */
    val NAME_CONFLICT = def("ext_msg_name_conflict", 1)

    // ------------------------------------------------------------------ Skill（SKILL.md 的 frontmatter 与读取）

    val SKILL_NO_FRONTMATTER = def("ext_msg_skill_no_frontmatter")
    val SKILL_FRONTMATTER_UNCLOSED = def("ext_msg_skill_frontmatter_unclosed")

    /** frontmatter 里的键重复。参数：键。 */
    val SKILL_KEY_DUPLICATE = def("ext_msg_skill_key_duplicate", 1)
    val SKILL_NAME_MISSING = def("ext_msg_skill_name_missing")
    val SKILL_DESCRIPTION_MISSING = def("ext_msg_skill_description_missing")

    /** 双引号 / 单引号没有闭合。参数：键。 */
    val SKILL_DOUBLE_QUOTE_UNCLOSED = def("ext_msg_skill_double_quote_unclosed", 1)
    val SKILL_SINGLE_QUOTE_UNCLOSED = def("ext_msg_skill_single_quote_unclosed", 1)
    val SKILL_FILE_UNREADABLE = def("ext_msg_skill_file_unreadable")
    val SKILL_FILE_ERROR = def("ext_msg_skill_file_error")
    val SKILL_FILE_MISSING = def("ext_msg_skill_file_missing")
    val SKILL_FILE_NOT_TEXT = def("ext_msg_skill_file_not_text")

    /** name 不合法，改用目录名。参数：name（已截断）。 */
    val SKILL_NAME_INVALID = def("ext_msg_skill_name_invalid", 1)

    /** 目录名也不能当 Skill 名，这个 Skill 被跳过。参数：目录名（已截断）。 */
    val SKILL_DIR_INVALID = def("ext_msg_skill_dir_invalid", 1)

    /** 本对象产出的全部 key 与各自的参数个数：app 层的完整性测试遍历它（资源齐全、占位符个数一致）。 */
    val ARITY: Map<String, Int> get() = arity

    val ALL: List<String> get() = arity.keys.toList()
}
