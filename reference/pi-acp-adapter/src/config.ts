/**
 * Internal runtime configuration seam.
 *
 * These fields deliberately have no public wire shape yet.  Keeping the seam
 * here lets sideagentd pass configuration later without coupling ACP to Pi's
 * package format today.
 */
export interface PiRuntimeConfigPlaceholder {
	mcp?: unknown;
	plugins?: unknown;
	hooks?: unknown;
}

export class UnsupportedRuntimeConfigurationError extends Error {
	constructor(kind: "MCP" | "Plugin" | "Hooks") {
		super(`${kind} configuration is not implemented by the Pi ACP adapter`);
		this.name = "UnsupportedRuntimeConfigurationError";
	}
}

export function assertRuntimeConfigurationSupported(config: PiRuntimeConfigPlaceholder | undefined): void {
	if (!config) return;
	if (config.mcp !== undefined) throw new UnsupportedRuntimeConfigurationError("MCP");
	if (config.plugins !== undefined) throw new UnsupportedRuntimeConfigurationError("Plugin");
	if (config.hooks !== undefined) throw new UnsupportedRuntimeConfigurationError("Hooks");
}
