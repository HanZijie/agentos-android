// npm test / npm run test:device 的入口：包一层 node --test（--test-concurrency=1，各测试文件依次跑）。
//
// 为什么要包一层：node --test 被打断（Ctrl-C，或外层工具超时发 SIGTERM）时只打印“Interrupted while running: <文件>”，
// 之后不再转出测试文件进程的 stderr。agent.mjs 在中断时把原因（哪一例、跑了多久、设备状态）和清理结果写进
// AGENTOS_ACP_REPORT 指向的文件，这里在 runner 退出后把它打印出来。
//
// 用法：node test/run.mjs [--device] [传给 node --test 的其他选项，如 --test-name-pattern=…]
import { spawn } from "node:child_process";
import { existsSync, readFileSync, unlinkSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const packageDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const argv = process.argv.slice(2);
const device = argv.includes("--device");
const passThrough = argv.filter((a) => a !== "--device");
const files = device ? ["test/conformance.test.mjs", "test/device.test.mjs", "test/gateway.test.mjs"] : ["test/*.test.mjs"];

const reportFile = path.join(os.tmpdir(), `agentos-acp-conformance-${process.pid}.log`);
const runner = spawn(process.execPath, ["--test", "--test-concurrency=1", ...passThrough, ...files], {
  cwd: packageDir,
  stdio: "inherit",
  env: { ...process.env, AGENTOS_ACP_REPORT: reportFile },
});

let interruptedBy = null;
for (const sig of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  process.on(sig, () => {
    interruptedBy ??= sig;
    // Ctrl-C 时 runner 自己也收到了；外层工具只给这个进程发信号时靠这里转给它（runner 再转给测试文件进程）
    try {
      runner.kill(sig);
    } catch {
      // 已经退出
    }
  });
}

const readReport = () => (existsSync(reportFile) ? readFileSync(reportFile, "utf8") : "");
const count = (text, word) => text.split("\n").filter((l) => l.includes(`] ${word} `)).length;

runner.on("exit", async (code, signal) => {
  // runner 收到信号后马上退出，不等测试文件进程；被中断的测试文件先写原因、再清理（结束子进程、恢复设备状态，要跑几条 adb）。
  // 知道发生了中断时，先最多等 5 秒让报告出现，再最多等 30 秒让清理完成
  const sleep = () => new Promise((r) => setTimeout(r, 200));
  let text = readReport();
  if (interruptedBy || signal) {
    for (const deadline = Date.now() + 5_000; !text && Date.now() < deadline; text = readReport()) await sleep();
  }
  for (const deadline = Date.now() + 30_000; count(text, "INTERRUPTED") > count(text, "CLEANED") && Date.now() < deadline; text = readReport()) {
    await sleep();
  }
  if (text) {
    process.stderr.write(`\n${text}`);
    if (count(text, "INTERRUPTED") > count(text, "CLEANED")) {
      process.stderr.write("[acp-conformance] cleanup did not finish within 30s; the next device run clears leftover state (README: 中断与残留状态)\n");
    }
  } else if (interruptedBy || signal) {
    process.stderr.write(
      `\n[acp-conformance] interrupted by ${interruptedBy ?? signal}; no test file reported what it was doing ` +
        "(it was still starting, or was killed before it could write the report). The next device run clears leftover state.\n",
    );
  }
  try {
    unlinkSync(reportFile);
  } catch {
    // 没有报告
  }
  if (signal) process.exitCode = 128 + (os.constants.signals[signal] ?? 1);
  else process.exitCode = interruptedBy && code === 0 ? 1 : code ?? 1;
});
