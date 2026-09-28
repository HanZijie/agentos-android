import { createSideagentd } from './index.mjs';

const dbPath = process.env.AGENTOS_SESSION_DB ?? './sideagentd.sqlite';
const daemon = createSideagentd({ dbPath });
const stop = async () => { await daemon.shutdown(); process.exit(0); };
process.once('SIGTERM', stop);
process.once('SIGINT', stop);
console.error(`sideagentd scheduler ready (db=${dbPath})`);
