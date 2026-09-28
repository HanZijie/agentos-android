import * as acp from "@agentclientprotocol/sdk";
import type { AgentSession, AgentSessionEvent } from "@earendil-works/pi-coding-agent";
import {
	assertRuntimeConfigurationSupported,
	type PiRuntimeConfigPlaceholder,
} from "./config.js";
import { createDefaultPiSession, type PiSessionFactory } from "./pi-session.js";

type AgentConnection = Pick<acp.AgentSideConnection, "sessionUpdate" | "requestPermission">;

interface SessionRecord {
	id: string;
	session: AgentSession;
	connection: AgentConnection;
	cancelRequested: boolean;
	toolUnsubscribe?: () => void;
	promptActive: boolean;
}

export interface PiAcpAgentOptions {
	version?: string;
	piSessionFactory?: PiSessionFactory;
	runtimeConfig?: PiRuntimeConfigPlaceholder;
}

type PiImageContent = { type: "image"; data: string; mimeType: string };

function toolKind(name: string): acp.ToolKind {
	if (name === "read" || name === "ls") return "read";
	if (name === "edit" || name === "write") return "edit";
	if (name === "grep" || name === "find") return "search";
	if (name === "bash" || name === "powershell") return "execute";
	return "other";
}

function toolTitle(name: string, args: Record<string, unknown>): string {
	if (name === "bash" || name === "powershell") return `Run ${String(args.command ?? "command")}`;
	if (typeof args.path === "string") return `${name} ${args.path}`;
	return name;
}

function contentToPrompt(blocks: acp.ContentBlock[]): { message: string; images: PiImageContent[] } {
	const parts: string[] = [];
 const images: PiImageContent[] = [];
	for (const block of blocks) {
		switch (block.type) {
			case "text":
				parts.push(block.text);
				break;
			case "image":
				images.push({ type: "image", data: block.data, mimeType: block.mimeType });
				break;
			case "resource_link":
				parts.push(`[resource: ${block.uri}]${block.name ? ` ${block.name}` : ""}`);
				break;
			case "resource":
				if ("text" in block.resource) parts.push(block.resource.text);
				else parts.push(`[embedded resource: ${block.resource.mimeType ?? "unknown"}]`);
				break;
			case "audio":
				throw new Error("Pi ACP adapter does not support audio prompts yet");
		}
	}
	return { message: parts.join("\n"), images };
}

function stopReason(record: SessionRecord): acp.StopReason {
	if (record.cancelRequested) return "cancelled";
	const error = record.session.agent.state.errorMessage?.toLowerCase() ?? "";
	if (error.includes("max_tokens") || error.includes("maxtokens")) return "max_tokens";
	if (error.includes("max_turn")) return "max_turn_requests";
	if (error.includes("refusal")) return "refusal";
	return "end_turn";
}

export class PiAcpAgent implements acp.Agent {
	private readonly sessions = new Map<string, SessionRecord>();
	private readonly factory: PiSessionFactory;
	private readonly version: string;
	private readonly runtimeConfig?: PiRuntimeConfigPlaceholder;

	constructor(private readonly connection: AgentConnection, options: PiAcpAgentOptions = {}) {
		this.factory = options.piSessionFactory ?? createDefaultPiSession;
		this.version = options.version ?? "0.1.0";
		this.runtimeConfig = options.runtimeConfig;
	}

	async initialize(params: acp.InitializeRequest): Promise<acp.InitializeResponse> {
		return {
			protocolVersion: params.protocolVersion === acp.PROTOCOL_VERSION ? acp.PROTOCOL_VERSION : acp.PROTOCOL_VERSION,
			agentInfo: { name: "agentos-pi", version: this.version },
			agentCapabilities: {
				loadSession: false,
				promptCapabilities: { image: true, audio: false, embeddedContext: true },
				mcpCapabilities: { http: false, sse: false },
				sessionCapabilities: { close: {} },
			},
			authMethods: [],
		};
	}

	async newSession(params: acp.NewSessionRequest): Promise<acp.NewSessionResponse> {
		if (!params.cwd.startsWith("/")) throw new Error("cwd must be an absolute path");
		if (params.mcpServers.length > 0) throw new Error("MCP configuration is not implemented by the Pi ACP adapter");
		assertRuntimeConfigurationSupported(this.runtimeConfig);
		const session = await this.factory({ cwd: params.cwd, runtimeConfig: this.runtimeConfig });
		const record: SessionRecord = {
			id: session.sessionId,
			session,
			connection: this.connection,
			cancelRequested: false,
			promptActive: false,
		};
		this.installPermissionBridge(record);
		record.toolUnsubscribe = session.subscribe((event) => {
			void this.translateEvent(record, event);
		});
		this.sessions.set(record.id, record);
		return { sessionId: record.id };
	}

	async prompt(params: acp.PromptRequest): Promise<acp.PromptResponse> {
		const record = this.requireSession(params.sessionId);
		if (record.promptActive) throw new Error("session/prompt is already running");
		const prompt = contentToPrompt(params.prompt);
		record.cancelRequested = false;
		record.promptActive = true;
		try {
			await record.session.prompt(prompt.message, {
				images: prompt.images.length > 0 ? prompt.images : undefined,
				source: "rpc",
				expandPromptTemplates: true,
			});
			return { stopReason: stopReason(record) };
		} finally {
			record.promptActive = false;
		}
	}

	async cancel(params: acp.CancelNotification): Promise<void> {
		const record = this.sessions.get(params.sessionId);
		if (!record) return;
		record.cancelRequested = true;
		await record.session.abort();
	}

	async closeSession(params: acp.CloseSessionRequest): Promise<acp.CloseSessionResponse> {
		const record = this.sessions.get(params.sessionId);
		if (!record) return {};
		record.cancelRequested = true;
		if (record.promptActive) await record.session.abort();
		record.toolUnsubscribe?.();
		record.session.dispose();
		this.sessions.delete(params.sessionId);
		return {};
	}

	async authenticate(_params: acp.AuthenticateRequest): Promise<acp.AuthenticateResponse> {
		return {};
	}

	dispose(): void {
		for (const record of this.sessions.values()) {
			record.toolUnsubscribe?.();
			record.session.dispose();
		}
		this.sessions.clear();
	}

	private requireSession(id: string): SessionRecord {
		const record = this.sessions.get(id);
		if (!record) throw new Error(`Session not found: ${id}`);
		return record;
	}

	private installPermissionBridge(record: SessionRecord): void {
		const original = record.session.agent.beforeToolCall;
		record.session.agent.beforeToolCall = async (context) => {
			const existing = await original?.(context);
			if (existing?.block) return existing;
			const acpId = context.toolCall.id;
			const response = await record.connection.requestPermission({
				sessionId: record.id,
				toolCall: {
					toolCallId: acpId,
					title: toolTitle(context.toolCall.name, context.args as Record<string, unknown>),
					kind: toolKind(context.toolCall.name),
					status: "pending",
					name: context.toolCall.name,
					rawInput: context.args,
				},
				options: [
					{ optionId: "allow_once", name: "Allow once", kind: "allow_once" },
					{ optionId: "reject_once", name: "Reject", kind: "reject_once" },
				],
			});
			if (response.outcome.outcome !== "selected" || response.outcome.optionId.startsWith("reject")) {
				return { block: true, terminate: true, reason: "Tool execution was not approved" };
			}
			return undefined;
		};
	}

	private async translateEvent(record: SessionRecord, event: AgentSessionEvent): Promise<void> {
		if (event.type === "message_update") {
			const update = event.assistantMessageEvent;
			if (update.type === "text_delta") {
				await record.connection.sessionUpdate({ sessionId: record.id, update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: update.delta } } });
			} else if (update.type === "thinking_delta") {
				await record.connection.sessionUpdate({ sessionId: record.id, update: { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: update.delta } } });
			}
			return;
		}
		if (event.type === "tool_execution_start") {
			await record.connection.sessionUpdate({
				sessionId: record.id,
				update: {
					sessionUpdate: "tool_call",
					toolCallId: event.toolCallId,
					title: toolTitle(event.toolName, event.args as Record<string, unknown>),
					name: event.toolName,
					kind: toolKind(event.toolName),
					status: "in_progress",
					rawInput: event.args,
				},
			});
			return;
		}
		if (event.type === "tool_execution_update") {
			await record.connection.sessionUpdate({
				sessionId: record.id,
				update: { sessionUpdate: "tool_call_update", toolCallId: event.toolCallId, rawOutput: event.partialResult },
			});
			return;
		}
		if (event.type === "tool_execution_end") {
			await record.connection.sessionUpdate({
				sessionId: record.id,
				update: {
					sessionUpdate: "tool_call_update",
					toolCallId: event.toolCallId,
					status: event.isError ? "failed" : "completed",
					rawOutput: event.result,
				},
			});
		}
	}
}
