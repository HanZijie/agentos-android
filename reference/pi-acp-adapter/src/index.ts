import { AgentSideConnection, ndJsonStream } from "@agentclientprotocol/sdk";
import { Readable, Writable } from "node:stream";
import { PiAcpAgent } from "./adapter.js";

const input = Readable.toWeb(process.stdin) as ReadableStream<Uint8Array>;
const output = Writable.toWeb(process.stdout) as WritableStream<Uint8Array>;
const stream = ndJsonStream(output, input);
const connection = new AgentSideConnection((agentConnection) => new PiAcpAgent(agentConnection), stream);
void connection;
process.stdin.resume();
