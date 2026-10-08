package org.agentos.extensions.session

import okhttp3.Dns
import org.agentos.runtime.ports.SessionMcpRejected
import org.agentos.runtime.ports.SessionMcpServer
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionUrlPolicyTest {
    private val policy = SessionUrlPolicy()
    private val loopbackPolicy = SessionUrlPolicy(allowLoopbackHttp = true)

    private fun server(url: String, name: String = "s1", headers: List<Pair<String, String>> = emptyList()) = SessionMcpServer(name, url, headers)

    private fun rejected(p: SessionUrlPolicy = policy, vararg servers: SessionMcpServer): SessionMcpRejected =
        assertFailsWith<SessionMcpRejected> { p.validate(servers.toList()) }

    private fun assertRejectedUrl(url: String, reason: String, p: SessionUrlPolicy = policy) {
        assertEquals(reason, rejected(p, server(url)).reason, "url -> $reason")
    }

    private fun assertAccepted(url: String, p: SessionUrlPolicy = policy) {
        p.validate(listOf(server(url)))
    }

    // ------------------------------------------------------------------ 通过的

    @Test
    fun `a public https server with ordinary headers is accepted`() {
        policy.validate(listOf(server("https://mcp.example.com/v1/mcp?x=1", headers = listOf("Authorization" to "Bearer abc.def", "X-Api-Key" to "k"))))
    }

    @Test
    fun `an empty batch is accepted`() {
        policy.validate(emptyList())
    }

    @Test
    fun `public IPv4 and IPv6 literals are accepted`() {
        for (url in listOf(
            "https://8.8.8.8/mcp", "https://1.1.1.1:8443/mcp", "https://172.32.0.1/", "https://172.15.255.255/", "https://100.63.255.255/", "https://100.128.0.1/",
            "https://[2606:4700:4700::1111]/mcp", "https://[::ffff:8.8.8.8]/mcp", "https://[64:ff9b::808:808]/",
        )) assertAccepted(url)
    }

    // ------------------------------------------------------------------ 个数、名字

    @Test
    fun `at most four servers`() {
        val four = (1..4).map { server("https://h$it.example.com/", name = "s$it") }
        policy.validate(four)
        assertEquals("too_many_servers", rejected(policy, *(four + server("https://h5.example.com/", "s5")).toTypedArray()).reason)
    }

    @Test
    fun `maxServers is configurable`() {
        val two = SessionUrlPolicy(maxServers = 2)
        two.validate(listOf(server("https://a.example.com/", "a"), server("https://b.example.com/", "b")))
        assertEquals("too_many_servers", rejected(two, server("https://a.example.com/", "a"), server("https://b.example.com/", "b"), server("https://c.example.com/", "c")).reason)
    }

    @Test
    fun `server names must match the allowed pattern`() {
        for (name in listOf("", "a b", "a/b", "a:b", "名字", "a".repeat(49), "a\nb", "a@b")) {
            assertEquals("server_name", rejected(policy, SessionMcpServer(name, "https://a.example.com/")).reason, "name '${name.take(10)}'")
        }
        for (name in listOf("a", "A_b-c.d", "a".repeat(48), "0", ".", "-")) {
            policy.validate(listOf(SessionMcpServer(name, "https://a.example.com/")))
        }
    }

    @Test
    fun `server names are unique ignoring case`() {
        val e = rejected(policy, server("https://a.example.com/", "Notes"), server("https://b.example.com/", "nOTES"))
        assertEquals("duplicate_server", e.reason)
    }

    // ------------------------------------------------------------------ URL

    @Test
    fun `url length is limited to 2048`() {
        val base = "https://a.example.com/"
        assertAccepted(base + "a".repeat(2048 - base.length))
        assertRejectedUrl(base + "a".repeat(2049 - base.length), "url_too_long")
    }

    @Test
    fun `url must be an absolute http url`() {
        for (url in listOf("", "mcp.example.com/mcp", "/mcp", "ftp://a.example.com/", "file:///etc/passwd", "javascript:alert(1)", "wss://a.example.com/", "https://", "not a url")) {
            assertRejectedUrl(url, "url_invalid")
        }
        assertRejectedUrl("https:///x", "url_host") // OkHttp reads the host as "x"
    }

    @Test
    fun `url must not contain whitespace or control characters`() {
        for (url in listOf(" https://a.example.com/", "https://a.example.com/ ", "https://a.example.com/\n", "https://a.exam\tple.com/", "https://a.example.com/\u0000", "https://a.example.com/\u007f")) {
            assertRejectedUrl(url, "url_invalid")
        }
    }

    @Test
    fun `plain http is rejected by default`() {
        assertRejectedUrl("http://a.example.com/mcp", "url_scheme")
        assertRejectedUrl("HTTP://a.example.com/mcp", "url_scheme")
        assertRejectedUrl("http://127.0.0.1:8080/mcp", "url_scheme")
    }

    @Test
    fun `userinfo is rejected`() {
        for (url in listOf("https://user:pass@a.example.com/", "https://user@a.example.com/", "https://:pass@a.example.com/", "https://@a.example.com/", "https://a.example.com@evil.example.com/")) {
            assertRejectedUrl(url, "url_userinfo")
        }
    }

    @Test
    fun `local host names are rejected`() {
        for (host in listOf("localhost", "LOCALHOST", "localhost.", "foo.localhost", "a.b.localhost", "printer.local", "nas.local.", "x.localdomain", "db.internal", "router.lan", "x.home.arpa")) {
            assertRejectedUrl("https://$host/mcp", "url_host")
        }
    }

    @Test
    fun `single label host names are rejected`() {
        for (host in listOf("intranet", "nas", "a")) assertRejectedUrl("https://$host/mcp", "url_host")
    }

    @Test
    fun `non public IPv4 literals are rejected`() {
        for (ip in listOf(
            "127.0.0.1", "127.255.255.254", "0.0.0.0", "0.1.2.3", "169.254.169.254", "169.254.0.1", "10.0.0.1", "10.255.255.255",
            "172.16.0.1", "172.31.255.255", "192.168.0.1", "192.168.255.255", "100.64.0.1", "100.127.255.255", "224.0.0.1", "239.255.255.255",
            "240.0.0.1", "255.255.255.255", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.19.255.255", "198.51.100.1", "203.0.113.1",
        )) assertRejectedUrl("https://$ip/mcp", "url_ip")
    }

    @Test
    fun `non public IPv6 literals are rejected`() {
        for (ip in listOf("::1", "::", "fe80::1", "fc00::1", "fd12:3456:789a::1", "ff02::1", "2001:db8::1", "2001::1", "3fff::1", "100::1", "fec0::1")) {
            assertRejectedUrl("https://[$ip]/mcp", "url_ip")
        }
    }

    @Test
    fun `IPv4 mapped IPv6 is judged by the embedded IPv4`() {
        for (ip in listOf("::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254", "::ffff:192.168.1.1", "::ffff:100.64.0.1", "::ffff:7f00:1")) {
            assertRejectedUrl("https://[$ip]/mcp", "url_ip")
        }
        assertAccepted("https://[::ffff:8.8.8.8]/mcp")
    }

    @Test
    fun `NAT64 and 6to4 addresses are judged by the embedded IPv4`() {
        assertRejectedUrl("https://[64:ff9b::a00:1]/", "url_ip") // 10.0.0.1
        assertRejectedUrl("https://[64:ff9b::7f00:1]/", "url_ip") // 127.0.0.1
        assertRejectedUrl("https://[2002:a00:1::1]/", "url_ip") // 6to4 of 10.0.0.1
        assertRejectedUrl("https://[2002:a9fe:a9fe::1]/", "url_ip") // 6to4 of 169.254.169.254
        assertAccepted("https://[2002:808:808::1]/") // 6to4 of 8.8.8.8
    }

    @Test
    fun `unusual IPv4 spellings are rejected whatever they resolve to`() {
        for (host in listOf("2130706433", "127.1", "0x7f.0.0.1", "0x7f000001", "0177.0.0.1", "127.0.0.01", "1.2.3", "1.2.3.4.5", "999.1.1.1", "8.8.8")) {
            assertRejectedUrl("https://$host/mcp", "url_host")
        }
    }

    @Test
    fun `isPublicAddress classifies raw bytes`() {
        assertTrue(SessionUrlPolicy.isPublicAddress(byteArrayOf(8, 8, 8, 8)))
        assertFalse(SessionUrlPolicy.isPublicAddress(byteArrayOf(10, 0, 0, 1)))
        assertFalse(SessionUrlPolicy.isPublicAddress(byteArrayOf(1, 2, 3)), "wrong length is never public")
        val mappedPrivate = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 10; it[15] = 1 }
        assertFalse(SessionUrlPolicy.isPublicAddress(mappedPrivate))
        val mappedPublic = ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 8; it[13] = 8; it[14] = 8; it[15] = 8 }
        assertTrue(SessionUrlPolicy.isPublicAddress(mappedPublic))
    }

    // ------------------------------------------------------------------ 头

    @Test
    fun `at most sixteen headers`() {
        val sixteen = (1..16).map { "X-H$it" to "v" }
        policy.validate(listOf(server("https://a.example.com/", headers = sixteen)))
        assertEquals("header_count", rejected(policy, server("https://a.example.com/", headers = sixteen + ("X-H17" to "v"))).reason)
    }

    @Test
    fun `header names must be http tokens`() {
        for (name in listOf("", "Bad Name", "Bad:Name", "Bad\nName", "名", "(x)", "a,b", "a/b", "A".repeat(129))) {
            assertEquals("header_name", rejected(policy, server("https://a.example.com/", headers = listOf(name to "v"))).reason, "header '${name.take(8)}'")
        }
        policy.validate(listOf(server("https://a.example.com/", headers = listOf("X-Api-Key_1.2~" to "v", "!#$%&'*+^`|" to "v"))))
    }

    @Test
    fun `transport headers cannot be set by the caller whatever their case`() {
        val forbidden = listOf(
            "host", "content-length", "content-type", "accept", "mcp-session-id", "mcp-protocol-version", "transfer-encoding",
            "connection", "upgrade", "te", "trailer", "proxy-authorization", "expect", "origin",
        )
        for (name in forbidden) {
            for (variant in listOf(name, name.uppercase(), name.replaceFirstChar { it.uppercase() })) {
                assertEquals("header_forbidden", rejected(policy, server("https://a.example.com/", headers = listOf(variant to "v"))).reason, variant)
            }
        }
        assertEquals(forbidden.toSet(), SessionUrlPolicy.FORBIDDEN_HEADERS)
    }

    @Test
    fun `authorization is an ordinary header`() {
        policy.validate(listOf(server("https://a.example.com/", headers = listOf("Authorization" to "Bearer x", "Cookie" to "a=b"))))
    }

    @Test
    fun `header values are at most 4096 characters`() {
        policy.validate(listOf(server("https://a.example.com/", headers = listOf("X-A" to "a".repeat(4096)))))
        assertEquals("header_value", rejected(policy, server("https://a.example.com/", headers = listOf("X-A" to "a".repeat(4097)))).reason)
    }

    @Test
    fun `header values cannot contain CR LF or other control characters`() {
        for (value in listOf("a\r\nInjected: 1", "a\nb", "a\rb", "a\u0000b", "a\tb", "a\u007fb", "\u001b[0m")) {
            assertEquals("header_value", rejected(policy, server("https://a.example.com/", headers = listOf("X-A" to value))).reason, value.length.toString())
        }
    }

    @Test
    fun `header values cannot contain non ASCII characters`() {
        assertEquals("header_value", rejected(policy, server("https://a.example.com/", headers = listOf("X-A" to "caf\u00e9"))).reason)
        policy.validate(listOf(server("https://a.example.com/", headers = listOf("X-A" to "a b ~!"))))
    }

    @Test
    fun `the same header name twice is rejected ignoring case`() {
        assertEquals("header_duplicate", rejected(policy, server("https://a.example.com/", headers = listOf("X-Key" to "1", "x-key" to "2"))).reason)
    }

    // ------------------------------------------------------------------ 回环例外（只给测试）

    @Test
    fun `loopback is refused by default whatever the scheme`() {
        assertRejectedUrl("https://127.0.0.1/", "url_ip")
        assertRejectedUrl("https://localhost/", "url_host")
        assertRejectedUrl("https://[::1]/", "url_ip")
        assertRejectedUrl("http://localhost:8080/", "url_scheme")
    }

    @Test
    fun `allowLoopbackHttp lets loopback hosts use http`() {
        for (url in listOf("http://127.0.0.1:8080/mcp", "http://localhost:3000/mcp", "http://[::1]:9/mcp", "https://127.0.0.1/mcp")) assertAccepted(url, loopbackPolicy)
    }

    @Test
    fun `allowLoopbackHttp does not open anything else`() {
        assertRejectedUrl("http://10.0.0.1/", "url_scheme", loopbackPolicy)
        assertRejectedUrl("http://example.com/", "url_scheme", loopbackPolicy)
        assertRejectedUrl("https://10.0.0.1/", "url_ip", loopbackPolicy)
        assertRejectedUrl("https://printer.local/", "url_host", loopbackPolicy)
        assertRejectedUrl("http://127.0.0.2/", "url_scheme", loopbackPolicy) // only the exact loopback spellings
        assertRejectedUrl("http://user:p@127.0.0.1/", "url_userinfo", loopbackPolicy)
        assertEquals("header_forbidden", rejected(loopbackPolicy, server("http://127.0.0.1/", headers = listOf("Host" to "x"))).reason)
    }

    // ------------------------------------------------------------------ 错误信息不回显

    @Test
    fun `rejection messages never echo caller supplied values`() {
        val marker = "MARKER-7f3a"
        val cases = listOf(
            listOf(SessionMcpServer("bad name $marker", "https://a.example.com/")),
            listOf(SessionMcpServer("ok", "ftp://$marker.example.com/path-$marker")),
            listOf(SessionMcpServer("ok", "http://$marker.example.com/")),
            listOf(SessionMcpServer("ok", "https://user:$marker@a.example.com/")),
            listOf(SessionMcpServer("ok", "https://$marker.local/")),
            listOf(SessionMcpServer("ok", "https://a.example.com/", listOf("Host" to marker))),
            listOf(SessionMcpServer("ok", "https://a.example.com/", listOf("X-$marker" to "a\r\n$marker"))),
            listOf(SessionMcpServer("ok", "https://a.example.com/", listOf("Bad Name $marker" to "v"))),
            listOf(SessionMcpServer("ok", "https://a.example.com/", listOf("X-A" to "a\n$marker"))),
            listOf(SessionMcpServer("Dup", "https://a.example.com/"), SessionMcpServer("dup", "https://$marker.example.com/")),
            listOf(SessionMcpServer("ok", "https://a.example.com/" + marker.repeat(300))),
        )
        for (servers in cases) {
            val e = assertFailsWith<SessionMcpRejected> { policy.validate(servers) }
            assertFalse(e.message!!.contains(marker), "message echoes a value: ${e.message}")
            assertFalse(e.reason.contains(marker))
            assertTrue(e.reason.matches(Regex("[a-z_]{1,32}")), "reason is a short code: ${e.reason}")
        }
    }

    @Test
    fun `validateOne returns the normalized url`() {
        val url = policy.validateOne(server("https://MCP.Example.com:8443/a/b?q=1"))
        assertEquals("mcp.example.com", url.host)
        assertEquals(8443, url.port)
        assertEquals("https", url.scheme)
    }

    // ------------------------------------------------------------------ DNS

    private fun resolverOf(vararg addresses: String) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.map { InetAddress.getByName(it) }
    }

    @Test
    fun `dns lets public resolutions through`() {
        val dns = SessionUrlPolicy(resolver = resolverOf("8.8.8.8", "2606:4700:4700::1111")).dns()
        assertEquals(2, dns.lookup("mcp.example.com").size)
    }

    @Test
    fun `dns refuses a private resolution`() {
        for (ip in listOf("10.0.0.5", "192.168.1.1", "172.16.0.9", "169.254.169.254", "127.0.0.1", "100.64.0.1", "0.0.0.0", "fd00::1", "fe80::1", "::1")) {
            val dns = SessionUrlPolicy(resolver = resolverOf(ip)).dns()
            val e = assertFailsWith<UnknownHostException>(ip) { dns.lookup("rebind.example.com") }
            assertFalse(e.message!!.contains("rebind"), "the host name is not echoed")
        }
    }

    @Test
    fun `dns refuses when any resolved address is private`() {
        val dns = SessionUrlPolicy(resolver = resolverOf("8.8.8.8", "10.0.0.5")).dns()
        assertFailsWith<UnknownHostException> { dns.lookup("mixed.example.com") }
    }

    @Test
    fun `dns refuses an empty resolution`() {
        val dns = SessionUrlPolicy(resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = emptyList()
        }).dns()
        assertFailsWith<UnknownHostException> { dns.lookup("nothing.example.com") }
    }

    @Test
    fun `dns passes resolver failures through`() {
        val dns = SessionUrlPolicy(resolver = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("nxdomain")
        }).dns()
        assertFailsWith<UnknownHostException> { dns.lookup("gone.example.com") }
    }

    @Test
    fun `dns lets loopback names through only when allowLoopbackHttp is set`() {
        val system = SessionUrlPolicy().dns()
        assertFailsWith<UnknownHostException> { system.lookup("localhost") }
        val loopback = SessionUrlPolicy(allowLoopbackHttp = true).dns()
        assertTrue(loopback.lookup("localhost").isNotEmpty())
    }

    @Test
    fun `allowLoopbackHttp does not let an ordinary name resolve to loopback`() {
        val dns = SessionUrlPolicy(allowLoopbackHttp = true, resolver = resolverOf("127.0.0.1")).dns()
        assertFailsWith<UnknownHostException> { dns.lookup("evil.example.com") }
    }

    @Test
    fun `dns returns the resolver addresses unchanged`() {
        val dns = SessionUrlPolicy(resolver = resolverOf("8.8.4.4")).dns()
        assertContentEquals(byteArrayOf(8, 8, 4, 4), dns.lookup("x.example.com").single().address)
        assertNotNull(dns)
    }
}

class SessionToolNamingTest {
    private fun assign(vararg ids: Pair<String, String>) = SessionToolNaming.assign(ids.toList())

    @Test
    fun `a plain name is ses__server__tool`() {
        assertEquals("ses__notes__note_create", assign("notes" to "note_create").getValue("notes" to "note_create"))
    }

    @Test
    fun `characters outside the model name alphabet become underscores`() {
        assertEquals("ses__my_server__a_b_c", assign("my.server" to "a b/c").values.single())
    }

    @Test
    fun `names are at most 64 characters and keep a hash of the original text when cut`() {
        val long = "t".repeat(100)
        val name = assign("server" to long).values.single()
        assertEquals(64, name.length)
        assertTrue(name.startsWith("ses__server__ttt"))
        assertTrue(name.matches(Regex(".*_[0-9a-f]{6}")))
        val other = assign("server" to long + "x").values.single()
        assertTrue(name != other, "different original names get different hashes")
    }

    @Test
    fun `a name of exactly 64 characters is not cut`() {
        val tool = "t".repeat(64 - "ses__s__".length)
        assertEquals("ses__s__$tool", assign("s" to tool).values.single())
    }

    @Test
    fun `colliding names all get a hash suffix and stay unique`() {
        val names = assign("s" to "a.b", "s" to "a_b", "s" to "a b", "s" to "other")
        assertEquals(4, names.values.toSet().size)
        assertEquals("ses__s__other", names.getValue("s" to "other"))
        for (id in listOf("a.b", "a_b", "a b")) assertTrue(names.getValue("s" to id).matches(Regex("ses__s__a_b_[0-9a-f]{6}")), names.getValue("s" to id))
    }

    @Test
    fun `servers whose names collide after sanitizing are told apart`() {
        val names = assign("x.y" to "t", "x_y" to "t")
        assertEquals(2, names.values.toSet().size)
    }

    @Test
    fun `the result does not depend on the order`() {
        val ids = listOf("s" to "a.b", "s" to "a_b", "t" to "z", "s" to "k".repeat(80))
        assertEquals(SessionToolNaming.assign(ids), SessionToolNaming.assign(ids.reversed()))
    }

    @Test
    fun `names never start with mcp__ and never equal read_skill`() {
        val names = assign("mcp" to "x", "mcp__a" to "b", "s" to "read_skill", "read_skill" to "read_skill", "_" to "mcp__x")
        for (name in names.values) {
            assertTrue(name.startsWith("ses__"), name)
            assertFalse(name.startsWith("mcp__"))
            assertTrue(name != "read_skill")
            assertTrue(name.matches(Regex("[A-Za-z0-9_-]{1,64}")), name)
        }
    }

    @Test
    fun `the same pair twice is a caller error`() {
        assertFailsWith<IllegalArgumentException> { SessionToolNaming.assign(listOf("s" to "t", "s" to "t")) }
    }
}
