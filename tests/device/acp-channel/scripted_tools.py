"""Scripted multi-step tool use for the fake model (A12).

The fake model (fake_model.py) used to know one tool script: `{"tool": NAME}` = one tool_use with empty arguments. The acceptance
driver (sample_apps_e2e.py) needs deterministic multi-step runs against real tools, so a prompt can now be

    {"toolCalls": [{"name": "mcp__alarm__alarm__alarm_create", "arguments": {"time": "07:00"}},
                   {"mcp": ["alarm", "alarm", "alarm_set_enabled"], "arguments": {"id": "$result[0].id", "enabled": false}}],
     "final": "done: ${result[0].id}"}

Round `r` of the turn (the number of assistant messages since the user prompt, = tool results that came back) streams:
  - r < len(toolCalls): one tool_use for toolCalls[r], arguments with placeholders resolved from the earlier results;
  - r >= len(toolCalls): the `final` text (placeholders resolved too; default empty) and end_turn.
A step whose placeholders cannot be resolved does not send a tool call: the round streams "script-error: <why>" and ends the turn,
so the driver sees the failure (and which step) instead of the app receiving a half-resolved call.

Tool name: `name` is the final model-facing name; `mcp: [plugin, server, tool]` (or `{"plugin","server","tool"}`) is the raw triple and
the name is computed here with the same rule as ToolNaming.nameOf in core/extensions (docs/extensions.md 5.3):
`mcp__<plugin>__<server>__<tool>`, every segment limited to [A-Za-z0-9_-] (anything else, counted in UTF-16 units like Kotlin, becomes `_`),
and when longer than 64: the first 57 characters + `_` + the first 6 hex digits of SHA-256("plugin\\0server\\0tool"). Collisions inside one
catalog (ToolNaming.assign) cannot be known here: the sample apps do not collide (SamplePluginsTest). Golden vectors shared with the Kotlin
side: tool_naming_golden.json (ToolNamingGoldenTest in core/extensions).

Placeholders (only inside `arguments` and `final`; results are the tool results of this turn, in order, parsed as JSON):
  - a string that is exactly `$result[N]` + path is replaced by the value itself, keeping its type (number, boolean, list, object, null);
    `N` may be negative (-1 = the latest result); a path is any number of `.key` and `[index]` steps, e.g. `$result[2].events[0].id`;
  - `${result[N]<path>}` inside a longer string is replaced by the text of the value (strings as they are, everything else as compact JSON);
  - a string that starts with `$$` is literal: the first `$` is dropped and nothing in it is replaced.
A result that was an error (isError) or is not JSON, a missing key, an index out of range or an unknown result number are failures.
"""
import hashlib
import json
import re

MAX_NAME = 64
_ALLOWED = re.compile(r"[A-Za-z0-9_-]")
_PATH = r"((?:\.[A-Za-z_][A-Za-z0-9_-]*|\[-?\d+\])*)"
_WHOLE = re.compile(r"^\$result\[(-?\d+)\]" + _PATH + r"$")
_INLINE = re.compile(r"\$\{result\[(-?\d+)\]" + _PATH + r"\}")
_STEP = re.compile(r"\.([A-Za-z_][A-Za-z0-9_-]*)|\[(-?\d+)\]")


def sanitize(segment):
    """ToolNaming.sanitize: every UTF-16 unit outside [A-Za-z0-9_-] becomes `_` (an astral character is two units, so two underscores)."""
    if not segment:
        return "_"
    out = []
    for ch in segment:
        if _ALLOWED.fullmatch(ch):
            out.append(ch)
        else:
            out.append("_" * (len(ch.encode("utf-16-le")) // 2))
    return "".join(out)


def tool_name(plugin, server, tool):
    """ToolNaming.nameOf(ToolId(plugin, server, tool)) (the catalog-level collision rule is not reproduced)."""
    if not plugin or not server or not tool:
        raise ValueError("plugin, server and tool must not be empty")
    plain = "mcp__%s__%s__%s" % (sanitize(plugin), sanitize(server), sanitize(tool))
    if len(plain) <= MAX_NAME:
        return plain
    digest = hashlib.sha256(("%s\0%s\0%s" % (plugin, server, tool)).encode("utf-8")).hexdigest()[:6]
    return plain[:MAX_NAME - 1 - len(digest)] + "_" + digest


class ScriptError(Exception):
    """The script cannot produce this round (bad shape or an unresolvable placeholder)."""


def _lookup(results, index, path, where):
    n = len(results)
    i = index + n if index < 0 else index
    if not 0 <= i < n:
        raise ScriptError("%s: result[%d] does not exist yet (%d result%s so far)" % (where, index, n, "" if n == 1 else "s"))
    r = results[i]
    if r.get("isError"):
        raise ScriptError("%s: result[%d] was an error (%s)" % (where, index, _short(r.get("text"))))
    try:
        value = json.loads(r.get("text") or "")
    except ValueError:
        raise ScriptError("%s: result[%d] is not JSON (%s)" % (where, index, _short(r.get("text")))) from None
    pos = 0
    walked = "result[%d]" % index
    while pos < len(path):
        m = _STEP.match(path, pos)
        pos = m.end()
        if m.group(1) is not None:
            key = m.group(1)
            if not isinstance(value, dict) or key not in value:
                have = sorted(value) if isinstance(value, dict) else type(value).__name__
                raise ScriptError("%s: %s has no key '%s' (has %s)" % (where, walked, key, have))
            value = value[key]
            walked += "." + key
        else:
            k = int(m.group(2))
            if not isinstance(value, list) or not -len(value) <= k < len(value):
                size = len(value) if isinstance(value, list) else type(value).__name__
                raise ScriptError("%s: %s[%d] is out of range (%s)" % (where, walked, k, size))
            value = value[k]
            walked += "[%d]" % k
    return value


def _short(text, n=120):
    t = (text or "").replace("\n", " ")
    return t if len(t) <= n else t[:n] + "..."


def _as_text(value):
    return value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def resolve(value, results, where="arguments"):
    """Replace the placeholders in a JSON value (strings, lists, objects); everything else is returned as it is."""
    if isinstance(value, str):
        if value.startswith("$$"):
            return value[1:]
        m = _WHOLE.match(value)
        if m:
            return _lookup(results, int(m.group(1)), m.group(2), where)
        return _INLINE.sub(lambda mm: _as_text(_lookup(results, int(mm.group(1)), mm.group(2), where)), value)
    if isinstance(value, list):
        return [resolve(v, results, "%s[%d]" % (where, i)) for i, v in enumerate(value)]
    if isinstance(value, dict):
        return {k: resolve(v, results, "%s.%s" % (where, k)) for k, v in value.items()}
    return value


def is_plan_script(script):
    return isinstance(script, dict) and ("toolCalls" in script or "final" in script)


def call_name(call, index):
    if not isinstance(call, dict):
        raise ScriptError("toolCalls[%d] is not an object" % index)
    if "name" in call:
        name = call["name"]
        if not isinstance(name, str) or not name:
            raise ScriptError("toolCalls[%d].name must be a non-empty string" % index)
        return name
    mcp = call.get("mcp")
    if isinstance(mcp, dict):
        mcp = [mcp.get("plugin"), mcp.get("server"), mcp.get("tool")]
    if isinstance(mcp, list) and len(mcp) == 3 and all(isinstance(x, str) and x for x in mcp):
        return tool_name(*mcp)
    raise ScriptError("toolCalls[%d] needs `name` or `mcp: [plugin, server, tool]`" % index)


def validate(script):
    """None when the script is well formed, otherwise one sentence."""
    calls = script.get("toolCalls", [])
    if not isinstance(calls, list):
        return "toolCalls must be a list"
    try:
        for i, c in enumerate(calls):
            call_name(c, i)
            args = c.get("arguments", {})
            if not isinstance(args, dict):
                return "toolCalls[%d].arguments must be an object" % i
    except ScriptError as e:
        return str(e)
    if "final" in script and not isinstance(script["final"], str):
        return "final must be a string"
    return None


def plan_round(script, round_index, results):
    """What this round of the turn streams.

    results: [{"isError": bool, "text": str}, ...] the tool results of this turn so far, in order.
    Returns {"kind": "tool", "name", "arguments"} | {"kind": "final", "text"} | {"kind": "error", "text": "script-error: ..."}.
    """
    problem = validate(script)
    if problem:
        return {"kind": "error", "text": "script-error: " + problem}
    calls = script.get("toolCalls", [])
    try:
        if round_index < len(calls):
            call = calls[round_index]
            where = "toolCalls[%d].arguments" % round_index
            return {"kind": "tool", "name": call_name(call, round_index),
                    "arguments": resolve(call.get("arguments", {}), results, where)}
        return {"kind": "final", "text": _as_text(resolve(script.get("final", ""), results, "final"))}
    except ScriptError as e:
        return {"kind": "error", "text": "script-error: step %d: %s" % (round_index, e)}
