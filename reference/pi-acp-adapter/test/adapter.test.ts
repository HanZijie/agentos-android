import test from "node:test";
import assert from "node:assert/strict";
import type * as acp from "@agentclientprotocol/sdk";
import { PiAcpAgent } from "../src/adapter.js";
import {
	UnsupportedRuntimeConfigurationError,
	type PiRuntimeConfigPlaceholder,
} from "../src/config.js";

type Listener = (event: any) => void;

class FakeSession {
	sessionId = "pi-session-1";
	listeners = new Set<Listener>();
	promptText = "";
	promptImages: unknown[] | undefined;
	aborted = false;
	disposed = false;
	agent: any = {
		state: { errorMessage: undefined },
		beforeToolCall: undefined,
	};

	subscribe(listener: Listener): () => void {
		this.listeners.add(listener);
		return () => this.listeners.delete(listener);
	}

	emit(event: unknown): void {
		for (const listener of this.listeners) listener(event);
	}

	async prompt(message: string, options: { images?: unknown[] }): Promise<void> {
		this.promptText = message;
		this.promptImages = options.images;
		this.emit({ type: "message_update", assistantMessageEvent: { type: "text_delta", delta: "hello" } });
		const decision = await this.agent.beforeToolCall?.({
			toolCall: { id: "tool-1", name: "bash" },
			args: { command: "echo hi" },
		});
		this.emit({ type: "tool_execution_start", toolCallId: "tool-1", toolName: "bash", args: { command: "echo hi" } });
		this.emit({
			type: "tool_execution_end",
			toolCallId: "tool-1",
			toolName: "bash",
			args: { command: "echo hi" },
			isError: Boolean(decision?.block),
			result: decision?.reason ?? { content: [{ type: "text", text: "ok" }] },
		});
	}

	async abort(): Promise<void> {
		this.aborted = true;
		this.agent.state.errorMessage = "aborted";
	}

	dispose(): void {
		this.disposed = true;
	}
}

function makeHarness(options: { permission?: "allow" | "reject" } = {}) {
	const sessions: FakeSession[] = [];
	const updates: acp.SessionNotification[] = [];
	const permissions: acp.RequestPermissionRequest[] = [];
	const connection = {
		sessionUpdate: async (params: acp.SessionNotification) => {
			updates.push(params);
		},
		requestPermission: async (params: acp.RequestPermissionRequest) => {
			permissions.push(params);
			return {
				outcome:
					options.permission === "reject"
						? { outcome: "selected" as const, optionId: "reject_once" }
						: { outcome: "selected" as const, optionId: "allow_once" },
			};
		},
	};
	const agent = new PiAcpAgent(connection, {
		piSessionFactory: async () => {
			const session = new FakeSession();
			sessions.push(session);
			return session as never;
		},
	});
	return { agent, sessions, updates, permissions };
}

test("negotiates ACP v1 without advertising unfinished capabilities", async () => {
	const { agent } = makeHarness();
	const response = await agent.initialize({ protocolVersion: 1 });
	assert.equal(response.protocolVersion, 1);
	assert.equal(response.agentCapabilities?.loadSession, false);
	assert.equal(response.agentCapabilities?.sessionCapabilities?.close !== undefined, true);
	assert.equal(response.agentCapabilities?.promptCapabilities?.image, true);
	assert.equal(response.agentCapabilities?.promptCapabilities?.audio, false);
});

test("rejects unsupported MCP servers and placeholder runtime configuration", async () => {
	const { agent } = makeHarness();
	await assert.rejects(
		agent.newSession({ cwd: "/tmp", mcpServers: [{ name: "local", command: "mcp", args: [], env: [] }] }),
		/MCP configuration is not implemented/,
	);

	const config: PiRuntimeConfigPlaceholder = { plugins: [] };
	const configured = new PiAcpAgent({} as never, { runtimeConfig: config, piSessionFactory: async () => new FakeSession() as never });
	await assert.rejects(configured.newSession({ cwd: "/tmp", mcpServers: [] }), UnsupportedRuntimeConfigurationError);
});

test("maps prompts, streams output and bridges tool permission", async () => {
	const { agent, sessions, updates, permissions } = makeHarness();
	const created = await agent.newSession({ cwd: "/tmp", mcpServers: [] });
	const result = await agent.prompt({
		sessionId: created.sessionId,
		prompt: [
			{ type: "text", text: "inspect this" },
			{ type: "image", data: "aW1hZ2U=", mimeType: "image/png" },
			{ type: "resource_link", name: "notes", uri: "file:///tmp/notes.txt" },
		],
	});
	assert.deepEqual(result, { stopReason: "end_turn" });
	assert.equal(sessions[0]?.promptText, "inspect this\n[resource: file:///tmp/notes.txt] notes");
	assert.deepEqual(sessions[0]?.promptImages, [{ type: "image", data: "aW1hZ2U=", mimeType: "image/png" }]);
	assert.equal(permissions.length, 1);
	assert.equal(permissions[0]?.toolCall.toolCallId, "tool-1");
	assert.deepEqual(
		updates.map((update) => update.update.sessionUpdate),
		["agent_message_chunk", "tool_call", "tool_call_update"],
	);
});

test("cancels and closes a session", async () => {
	const { agent, sessions } = makeHarness();
	const created = await agent.newSession({ cwd: "/tmp", mcpServers: [] });
	await agent.cancel({ sessionId: created.sessionId });
	assert.equal(sessions[0]?.aborted, true);
	await agent.closeSession({ sessionId: created.sessionId });
	assert.equal(sessions[0]?.disposed, true);
	await assert.rejects(agent.prompt({ sessionId: created.sessionId, prompt: [{ type: "text", text: "again" }] }), /Session not found/);
});
