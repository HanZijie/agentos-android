import {
	createAgentSession,
	SessionManager,
	type AgentSession,
} from "@earendil-works/pi-coding-agent";

export interface PiSessionFactoryOptions {
	cwd: string;
	/** Internal configuration seam; currently validated and rejected when non-empty. */
	runtimeConfig?: import("./config.js").PiRuntimeConfigPlaceholder;
}

export type PiSessionFactory = (options: PiSessionFactoryOptions) => Promise<AgentSession>;

/** Create a Pi session with Pi's own tools and session persistence. */
export const createDefaultPiSession: PiSessionFactory = async ({ cwd }) => {
	const { session } = await createAgentSession({
		cwd,
		sessionManager: SessionManager.create(cwd),
	});
	return session;
};
