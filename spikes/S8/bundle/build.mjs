// S8 bundle build: pi-agent-core + pi-ai (anthropic-messages, openai-completions only)
// as a single IIFE for QuickJS, plus model-catalog.json exported from pi-ai's own catalog.
//
//   node build.mjs [--target es2020] [--minify] [--out-dir dist]
import * as esbuild from "esbuild";
import { gzipSync } from "node:zlib";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const outDir = path.resolve(here, opt("--out-dir", "dist"));
const target = opt("--target", "es2020");
const minify = args.includes("--minify");

const PI_AI_DIR = path.resolve(here, "node_modules/@earendil-works/pi-ai/dist");
const PI_CORE_DIR = path.resolve(here, "node_modules/@earendil-works/pi-agent-core");
const SUPPORTED_APIS = ["anthropic-messages", "openai-completions"];

// ---------------------------------------------------------------------------
// 1. Bundle
// ---------------------------------------------------------------------------
const stubbed = [];
const stub = (file) => path.resolve(here, "src/node-stubs", file);
const nodeStubs = {
  name: "agentos-node-stubs",
  setup(build) {
    build.onResolve({ filter: /(^|\/)internal\/node\.mjs$/ }, (a) => {
      if (!a.importer.includes("@anthropic-ai/sdk")) return undefined;
      stubbed.push(`@anthropic-ai/sdk/internal/node.mjs  <- ${path.relative(here, a.importer)}`);
      return { path: stub("anthropic-internal-node.js") };
    });
    build.onResolve({ filter: /agent-toolset\/node\.mjs$/ }, (a) => {
      stubbed.push(`@anthropic-ai/sdk/tools/agent-toolset/node.mjs  <- ${path.relative(here, a.importer)}`);
      return { path: stub("anthropic-agent-toolset-node.js") };
    });
    build.onResolve({ filter: /(^|\/)provider-env\.js$/ }, (a) => {
      if (!a.importer.includes("pi-ai")) return undefined;
      stubbed.push(`@earendil-works/pi-ai/dist/utils/provider-env.js  <- ${path.relative(here, a.importer)}`);
      return { path: stub("pi-ai-provider-env.js") };
    });
    build.onResolve({ filter: /^node:/ }, (a) => {
      stubbed.push(`UNEXPECTED ${a.path}  <- ${path.relative(here, a.importer)}`);
      return { path: stub("node-builtin.js") };
    });
  },
};

fs.mkdirSync(outDir, { recursive: true });
const started = Date.now();
const result = await esbuild.build({
  entryPoints: [path.resolve(here, "src/entry.js")],
  bundle: true,
  format: "iife",
  platform: "neutral",
  target,
  minify,
  mainFields: ["module", "main"],
  conditions: ["import", "default"],
  alias: { "pi-agent-core/agent": path.join(PI_CORE_DIR, "dist/agent.js") },
  plugins: [nodeStubs],
  outfile: path.join(outDir, "pi-agent.js"),
  metafile: true,
  legalComments: "none",
  logLevel: "warning",
});

const bundlePath = path.join(outDir, "pi-agent.js");
const bundle = fs.readFileSync(bundlePath);
fs.writeFileSync(path.join(outDir, "meta.json"), JSON.stringify(result.metafile));

const byPackage = new Map();
for (const [file, info] of Object.entries(result.metafile.outputs[path.relative(process.cwd(), bundlePath)]?.inputs ?? Object.values(result.metafile.outputs)[0].inputs)) {
  const m = /node_modules\/((?:@[^/]+\/)?[^/]+)/.exec(file);
  const key = m ? m[1] : file.startsWith("src/") || file.includes("/src/") ? "(agentos src)" : file;
  byPackage.set(key, (byPackage.get(key) ?? 0) + info.bytesInOutput);
}

const unexpected = stubbed.filter((s) => s.startsWith("UNEXPECTED"));
const versions = Object.fromEntries(
  ["@earendil-works/pi-agent-core", "@earendil-works/pi-ai", "@anthropic-ai/sdk", "openai", "typebox"].map((p) => [
    p,
    JSON.parse(fs.readFileSync(path.resolve(here, "node_modules", p, "package.json"), "utf8")).version,
  ]),
);

// ---------------------------------------------------------------------------
// 2. model-catalog.json (vendor presets) from pi-ai's catalog, two families only
// ---------------------------------------------------------------------------
const PRESET_ORDER = ["minimax", "minimax-cn"]; // MiniMax international / China first (F9)
const providersDir = path.join(PI_AI_DIR, "providers");
const providerFiles = fs
  .readdirSync(providersDir)
  .filter((f) => f.endsWith(".js") && !f.endsWith(".models.js") && !f.endsWith(".map"));
const providers = [];
const skipped = [];
for (const file of providerFiles) {
  const mod = await import(pathToFileURL(path.join(providersDir, file)).href);
  const factories = Object.entries(mod).filter(([name, fn]) => typeof fn === "function" && /Provider$/.test(name) && fn.length === 0);
  for (const [, factory] of factories) {
    let p;
    try { p = factory(); } catch { continue; }
    if (!p || typeof p.getModels !== "function" || !p.id) continue;
    const models = p.getModels().filter((m) => SUPPORTED_APIS.includes(m.api));
    if (models.length === 0) continue;
    if (!p.auth?.apiKey) { skipped.push(`${p.id}: no API-key auth`); continue; }
    if (typeof p.headers === "function") { skipped.push(`${p.id}: dynamic provider headers`); continue; }
    const baseUrls = [...new Set(models.map((m) => m.baseUrl))];
    if (baseUrls.some((u) => !/^https:\/\//.test(u ?? "") || /[{}]/.test(u))) { skipped.push(`${p.id}: templated/unknown baseUrl`); continue; }
    providers.push({
      id: p.id,
      name: p.name,
      apis: [...new Set(models.map((m) => m.api))],
      baseUrls,
      auth: { type: "api-key", label: p.auth.apiKey.name ?? `${p.name} API key` },
      models,
    });
  }
}
providers.sort((a, b) => {
  const ia = PRESET_ORDER.indexOf(a.id), ib = PRESET_ORDER.indexOf(b.id);
  if (ia >= 0 || ib >= 0) return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
  return a.id.localeCompare(b.id);
});

const catalog = {
  schemaVersion: 1,
  generatedFrom: { "@earendil-works/pi-ai": versions["@earendil-works/pi-ai"] },
  apis: SUPPORTED_APIS,
  // Templates for "custom compatible endpoint": the settings page fills id/name/baseUrl.
  customTemplates: {
    "anthropic-messages": { api: "anthropic-messages", provider: "custom", reasoning: false, input: ["text", "image"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 8192 },
    "openai-completions": { api: "openai-completions", provider: "custom", reasoning: false, input: ["text", "image"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 8192 },
  },
  providers,
};
const catalogJson = JSON.stringify(catalog);
fs.writeFileSync(path.join(outDir, "model-catalog.json"), catalogJson);

// ---------------------------------------------------------------------------
// 3. Report
// ---------------------------------------------------------------------------
const kb = (n) => `${(n / 1024).toFixed(1)} KB`;
const report = {
  target,
  minify,
  buildMs: Date.now() - started,
  versions,
  bundleBytes: bundle.length,
  bundleGzipBytes: gzipSync(bundle).length,
  catalogBytes: Buffer.byteLength(catalogJson),
  catalogGzipBytes: gzipSync(catalogJson).length,
  catalogProviders: providers.length,
  catalogModels: providers.reduce((a, p) => a + p.models.length, 0),
  stubbed: [...new Set(stubbed)],
  skippedProviders: skipped,
  sizeByPackage: Object.fromEntries([...byPackage.entries()].sort((a, b) => b[1] - a[1])),
};
fs.writeFileSync(path.join(outDir, "build-report.json"), JSON.stringify(report, null, 2));
console.log(`pi-agent.js       ${kb(report.bundleBytes)} (gzip ${kb(report.bundleGzipBytes)})  target=${target} minify=${minify}`);
console.log(`model-catalog.json ${kb(report.catalogBytes)} (gzip ${kb(report.catalogGzipBytes)})  ${report.catalogProviders} providers / ${report.catalogModels} models`);
console.log("size by package:");
for (const [k, v] of Object.entries(report.sizeByPackage)) console.log(`  ${k.padEnd(36)} ${kb(v)}`);
console.log("stubbed modules:");
for (const s of report.stubbed) console.log(`  ${s}`);
if (skipped.length) console.log(`providers skipped: ${skipped.join("; ")}`);
if (unexpected.length) {
  console.error("Unexpected Node built-in imports were stubbed; review before shipping.");
  process.exitCode = 2;
}
