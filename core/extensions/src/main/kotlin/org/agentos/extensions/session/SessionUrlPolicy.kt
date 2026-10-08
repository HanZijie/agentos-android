package org.agentos.extensions.session

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.agentos.runtime.net.HostFetch
import org.agentos.runtime.ports.SessionMcpRejected
import org.agentos.runtime.ports.SessionMcpServer
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale

/**
 * 会话级 MCP 服务器（ACP `mcpServers`）的准入规则：整批校验，**调用方是不可信的**，URL 和头都是他给的。
 * 失败抛 [SessionMcpRejected]：`reason` 是短代码，`message` 说明哪条规则（可以带第几个服务器），**不回显**调用方传入的任何值。
 *
 * ## 规则
 * - 个数 ≤ [maxServers]；名字 `[A-Za-z0-9_.-]{1,48}`，忽略大小写后唯一；
 * - URL：长度 ≤ 2048；没有空白和控制字符；绝对 URL；scheme 是 `https`；没有 userinfo；host 不是 `localhost`、`*.localhost`、`*.local`、
 *   `*.localdomain`、`*.internal`、`*.lan`、`*.home.arpa`，也不是没有点的单标签主机名；
 * - host 是 IP 字面量（包括 `2130706433`、`127.1`、`0x7f.0.0.1` 这类写法：最后一段是数字的 host 一律按 IPv4 处理，
 *   只接受规范的点分十进制）时必须是**公网地址**（[isPublicAddress]）；IPv6 的 IPv4 映射地址按内嵌的 IPv4 判断；
 * - 头：≤ 16 个；名字是 HTTP token（≤ 128）；不得是 [FORBIDDEN_HEADERS] 里的（调用方不能伪造传输层的头）；值 ≤ 4096 个字符，
 *   只含可见 ASCII 和空格（不含 CR/LF/其他控制字符，也不含非 ASCII：OkHttp 同样不接受）；同名（忽略大小写）不得重复。
 *
 * [allowLoopbackHttp]（**默认 false，只给测试用**）为 true 时，回环地址（[HostFetch.isLoopback]：`127.0.0.1`、`localhost`、`::1`）
 * 不受 scheme 和 host 规则约束（可以用 http）。其他规则照常。
 *
 * ## DNS
 * 域名在连接时才解析，攻击者可以让域名先解析成公网地址通过检查、连接时再解析成内网地址（DNS rebinding）。所以连接用 [dns]：
 * 解析结果里**只要有一个**非公网地址就整个拒绝。IP 字面量不经过 DNS（OkHttp 直接连），由上面的 URL 规则挡住。
 * 重定向在链接层关闭（`followRedirects(false)`），不会被 3xx 带到内网。
 *
 * @param resolver 系统解析；测试注入假的
 */
class SessionUrlPolicy(
    val maxServers: Int = DEFAULT_MAX_SERVERS,
    val allowLoopbackHttp: Boolean = false,
    private val resolver: Dns = Dns.SYSTEM,
) {
    /** 整批校验。任何一条不满足就抛 [SessionMcpRejected]；通过时没有副作用。 */
    fun validate(servers: List<SessionMcpServer>) {
        if (servers.size > maxServers) reject("too_many_servers", "at most $maxServers MCP servers can be attached to a session")
        val names = HashSet<String>()
        servers.forEachIndexed { i, server ->
            val at = "server #${i + 1}"
            if (!NAME.matches(server.name)) reject("server_name", "$at: name must match [A-Za-z0-9_.-]{1,48}")
            if (!names.add(server.name.lowercase(Locale.ROOT))) reject("duplicate_server", "$at: name is not unique (compared ignoring case)")
            checkServer(server, at)
        }
    }

    /** 校验单个服务器（链接层建立连接前再确认一次）；返回规范化后的 URL。 */
    fun validateOne(server: SessionMcpServer): HttpUrl = checkServer(server, "server")

    /**
     * 解析主机名并拒绝非公网结果（防 DNS rebinding）。解析失败原样抛 [UnknownHostException]（消息里没有主机名）；
     * [allowLoopbackHttp] 为 true 且 host 是回环地址时放行。
     */
    fun dns(): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = resolver.lookup(hostname)
            if (allowLoopbackHttp && HostFetch.isLoopback(hostname)) return addresses
            if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) }) throw UnknownHostException("non_public_address")
            return addresses
        }
    }

    // ------------------------------------------------------------------ URL

    private fun checkServer(server: SessionMcpServer, at: String): HttpUrl {
        val url = checkUrl(server.url, at)
        checkHeaders(server.headers, at)
        return url
    }

    private fun checkUrl(raw: String, at: String): HttpUrl {
        if (raw.length > MAX_URL_CHARS) reject("url_too_long", "$at: url is longer than $MAX_URL_CHARS characters")
        if (raw.any { it <= ' ' || it == '\u007f' }) reject("url_invalid", "$at: url contains whitespace or control characters")
        val url = raw.toHttpUrlOrNull() ?: reject("url_invalid", "$at: url must be an absolute http(s) URL")
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || authorityOf(raw).contains('@')) {
            reject("url_userinfo", "$at: url must not contain credentials (put them in headers)")
        }
        val loopback = allowLoopbackHttp && HostFetch.isLoopback(url.host)
        if (loopback) return url
        if (url.scheme != "https") reject("url_scheme", "$at: url must use https")
        checkHost(url.host, at)
        return url
    }

    /** `scheme://` 之后到第一个 `/ ? # \` 之前的部分（原始字符串，OkHttp 的解析可能把 userinfo 吃掉）。 */
    private fun authorityOf(raw: String): String {
        val start = raw.indexOf("://").let { if (it < 0) 0 else it + 3 }
        var end = start
        while (end < raw.length && raw[end] !in "/?#\\") end++
        return raw.substring(start, end)
    }

    private fun checkHost(rawHost: String, at: String) {
        val host = rawHost.trimEnd('.')
        if (host.isEmpty()) reject("url_host", "$at: url has no host")
        if (host.contains(':')) {
            val address = parseIpv6(host) ?: reject("url_host", "$at: url host is not a valid IP address")
            if (!isPublicAddress(address)) reject("url_ip", "$at: url host must be a public address")
            return
        }
        val last = host.substringAfterLast('.')
        if (last.all { it in '0'..'9' } || (last.startsWith("0x", ignoreCase = true) && last.drop(2).all { it in HEX })) {
            // 域名的最后一段不可能是纯数字：这是 IPv4 的某种写法，只接受规范的点分十进制（其余写法各家解析器的结果不一样）
            val address = parseStrictIpv4(host) ?: reject("url_host", "$at: url host is not a canonical IPv4 address")
            if (!isPublicAddress(address)) reject("url_ip", "$at: url host must be a public address")
            return
        }
        if (!host.contains('.')) reject("url_host", "$at: url host must be a fully qualified domain name")
        if (host == "localhost" || LOCAL_SUFFIXES.any { host.endsWith(it) }) reject("url_host", "$at: url host is a local name")
    }

    // ------------------------------------------------------------------ 头

    private fun checkHeaders(headers: List<Pair<String, String>>, at: String) {
        if (headers.size > MAX_HEADERS) reject("header_count", "$at: at most $MAX_HEADERS headers are allowed")
        val seen = HashSet<String>()
        for ((name, value) in headers) {
            if (!TOKEN.matches(name)) reject("header_name", "$at: a header name is not a valid HTTP token")
            val lower = name.lowercase(Locale.ROOT)
            if (lower in FORBIDDEN_HEADERS) reject("header_forbidden", "$at: a header is reserved for the transport and cannot be set")
            if (!seen.add(lower)) reject("header_duplicate", "$at: a header name is repeated")
            if (value.length > MAX_HEADER_VALUE_CHARS) reject("header_value", "$at: a header value is longer than $MAX_HEADER_VALUE_CHARS characters")
            if (value.any { it < ' ' || it > '~' }) reject("header_value", "$at: a header value contains control or non-ASCII characters")
        }
    }

    private fun reject(reason: String, message: String): Nothing = throw SessionMcpRejected(reason, message)

    companion object {
        const val DEFAULT_MAX_SERVERS = 4
        const val MAX_URL_CHARS = 2048
        const val MAX_HEADERS = 16
        const val MAX_HEADER_VALUE_CHARS = 4096

        private val NAME = Regex("[A-Za-z0-9_.-]{1,48}")
        private val TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
        private const val HEX = "0123456789abcdefABCDEF"
        private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".localdomain", ".internal", ".lan", ".home.arpa")

        /** 调用方不能设的头（小写）：传输层自己写，让调用方设会伪造会话、协议版本、来源，或破坏报文边界。 */
        val FORBIDDEN_HEADERS: Set<String> = setOf(
            "host", "content-length", "content-type", "accept", "mcp-session-id", "mcp-protocol-version",
            "transfer-encoding", "connection", "upgrade", "te", "trailer", "proxy-authorization", "expect", "origin",
        )

        /**
         * 公网地址（[address] 是 4 或 16 字节）。拒绝：
         * - IPv4：0/8、10/8、100.64/10（CGNAT）、127/8、169.254/16（link-local，含云元数据）、172.16/12、192.0.0/24、192.0.2/24、
         *   192.88.99/24、192.168/16、198.18/15、198.51.100/24、203.0.113/24、224/4（multicast）及以上（保留、广播）；
         * - IPv6：只放行 2000::/3（全球单播），其中再拒绝 Teredo（2001::/32）、文档地址（2001:db8::/32、3fff::/20）、基准测试（2001:2::/48）；
         *   IPv4 映射（::ffff:a.b.c.d）、NAT64（64:ff9b::/96）、6to4（2002::/16）按内嵌的 IPv4 判断；`::/96`（含未指定、回环）、
         *   link-local（fe80::/10）、ULA（fc00::/7）、multicast（ff00::/8）都不在 2000::/3 里。
         */
        fun isPublicAddress(address: ByteArray): Boolean = when (address.size) {
            4 -> isPublicV4(address[0].toInt() and 0xff, address[1].toInt() and 0xff, address[2].toInt() and 0xff)
            16 -> isPublicV6(address)
            else -> false
        }

        fun isPublicAddress(address: InetAddress): Boolean = isPublicAddress(address.address)

        private fun isPublicV4(a: Int, b: Int, c: Int): Boolean = when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 && (c == 0 || c == 2) -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 192 && b == 168 -> false
            a == 198 && (b == 18 || b == 19) -> false
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a >= 224 -> false
            else -> true
        }

        private fun isPublicV6(x: ByteArray): Boolean {
            fun u(i: Int) = x[i].toInt() and 0xff
            fun zeros(from: Int, to: Int) = (from until to).all { x[it].toInt() == 0 }
            // ::ffff:a.b.c.d
            if (zeros(0, 10) && u(10) == 0xff && u(11) == 0xff) return isPublicV4(u(12), u(13), u(14))
            // 64:ff9b::/96（NAT64 的众所周知前缀）
            if (u(0) == 0x00 && u(1) == 0x64 && u(2) == 0xff && u(3) == 0x9b && zeros(4, 12)) return isPublicV4(u(12), u(13), u(14))
            if (u(0) and 0xe0 != 0x20) return false // 不在 2000::/3
            if (u(0) == 0x20 && u(1) == 0x02) return isPublicV4(u(2), u(3), u(4)) // 6to4
            if (u(0) == 0x20 && u(1) == 0x01) {
                if (u(2) == 0x00 && u(3) == 0x00) return false // Teredo
                if (u(2) == 0x0d && u(3) == 0xb8) return false // 文档
                if (u(2) == 0x00 && u(3) == 0x02 && u(4) == 0x00 && u(5) == 0x00) return false // 基准测试
            }
            if (u(0) == 0x3f && u(1) == 0xff && u(2) and 0xf0 == 0) return false // 文档（3fff::/20）
            return true
        }

        /** 只接受 `a.b.c.d`：四段、每段 1–3 位十进制、没有前导 0（有的解析器把它当八进制）、≤ 255。 */
        private fun parseStrictIpv4(host: String): ByteArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for ((i, p) in parts.withIndex()) {
                if (p.isEmpty() || p.length > 3 || p.any { it !in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
                val v = p.toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }

        /** [host] 是 OkHttp 规范化过的 IPv6 字面量（没有方括号）。用括号包起来保证 JDK 只做字面量解析、不查 DNS。 */
        private fun parseIpv6(host: String): ByteArray? = try {
            InetAddress.getByName("[$host]").address
        } catch (e: UnknownHostException) {
            null
        }
    }
}
