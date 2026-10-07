package org.agentos.app.ext.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.agentos.extensions.host.ConnectionLost
import org.agentos.extensions.host.McpContentPart
import org.agentos.extensions.host.NotSent
import org.agentos.extensions.host.ServerError
import org.agentos.extensions.host.TimedOut
import org.agentos.plugin.McpClosedException
import org.agentos.plugin.McpException
import org.agentos.plugin.McpRequestTooLargeException
import org.agentos.plugin.McpRpcException
import org.agentos.plugin.McpTimeoutException
import org.agentos.plugin.McpTool
import org.agentos.plugin.McpToolAnnotations
import org.agentos.plugin.McpToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SDK 的失败与结果 → A 的接缝类型（决定 ToolPort 的三种结局）。 */
class BinderMcpMappingTest {

    @Test
    fun failureClassification() {
        val notSent = mapFailure(McpClosedException("local(closed)", dispatched = false))
        assertTrue(notSent is NotSent)
        assertFalse(notSent.mayHaveBeenSent)

        val lost = mapFailure(McpClosedException("peer_died(peer died)", dispatched = true))
        assertTrue(lost is ConnectionLost)
        assertTrue(lost.mayHaveBeenSent)

        val timeout = mapFailure(McpTimeoutException("tools/call", 1_500))
        assertTrue(timeout is TimedOut && timeout.timeoutMillis == 1_500L && timeout.mayHaveBeenSent)

        val rpc = mapFailure(McpRpcException(-32602, "Unknown tool: x"))
        assertTrue(rpc is ServerError && rpc.code == -32602)

        val tooLarge = mapFailure(McpRequestTooLargeException("tools/call", 70_000, 65_536))
        assertTrue(tooLarge is NotSent)

        val malformed = mapFailure(McpException("tools/call: result is not an object"))
        assertTrue(malformed is ServerError && malformed.code == -32603)

        val unexpected = mapFailure(IllegalStateException("boom"))
        assertTrue("unknown failures are treated as possibly sent (never replayed)", unexpected is ConnectionLost && unexpected.mayHaveBeenSent)
    }

    @Test
    fun resultConversion() {
        val ok = McpToolResult.json(buildJsonObject { put("id", "7") }).toCallResult()
        assertFalse(ok.isError)
        assertEquals(listOf<McpContentPart>(McpContentPart.Text("{\"id\":\"7\"}")), ok.content)
        assertEquals("7", ok.structuredContent!!["id"].toString().trim('"'))

        val err = McpToolResult.error("note not found").toCallResult()
        assertTrue(err.isError)
        assertNull(err.structuredContent)
        assertEquals(McpContentPart.Text("note not found"), err.content.single())
    }

    @Test
    fun toolConversion() {
        val schema = buildJsonObject { put("type", "object") }
        val plain = McpTool("note_list", "", schema).toToolInfo()
        assertNull("empty description becomes null", plain.description)
        assertNull("no annotations at all becomes null", plain.annotations)

        val annotated = McpTool("note_delete", "Deletes a note.", schema, McpToolAnnotations(destructiveHint = true), title = "Delete").toToolInfo()
        assertEquals("Deletes a note.", annotated.description)
        assertEquals("Delete", annotated.title)
        assertEquals(true, annotated.annotations!!.destructiveHint)
        assertNull(annotated.annotations!!.readOnlyHint)
    }
}
