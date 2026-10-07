"""The fake model over HTTP (no device): the old scripts keep working and the new toolCalls scripts run end to end."""
import http.client
import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import fake_model  # noqa: E402

KEY = "agtest-fake-model-key"


def parse_sse(raw):
    """-> {"text", "tools": [{"id","name","input"}], "stop"}"""
    text, tools, stop, cur = [], [], None, {}
    for block in raw.split("\n\n"):
        ev = data = None
        for line in block.splitlines():
            if line.startswith("event: "):
                ev = line[7:]
            elif line.startswith("data: "):
                data = json.loads(line[6:])
        if ev == "content_block_start":
            cb = data["content_block"]
            if cb["type"] == "tool_use":
                cur[data["index"]] = {"id": cb["id"], "name": cb["name"], "json": ""}
        elif ev == "content_block_delta":
            d = data["delta"]
            if d["type"] == "text_delta":
                text.append(d["text"])
            elif d["type"] == "input_json_delta":
                cur[data["index"]]["json"] += d["partial_json"]
        elif ev == "message_delta":
            stop = data["delta"]["stop_reason"]
    for t in cur.values():
        tools.append({"id": t["id"], "name": t["name"], "input": json.loads(t["json"] or "{}")})
    return {"text": "".join(text), "tools": tools, "stop": stop}


class FakeModelHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fm = fake_model.FakeModel({KEY: "test"}).start()

    @classmethod
    def tearDownClass(cls):
        cls.fm.stop()

    def post(self, messages, key=KEY):
        c = http.client.HTTPConnection("127.0.0.1", self.fm.port, timeout=10)
        c.request("POST", "/v1/messages", json.dumps({"model": "m", "messages": messages, "stream": True}),
                  {"x-api-key": key, "content-type": "application/json"})
        r = c.getresponse()
        body = r.read().decode()
        c.close()
        return r.status, body

    def ask(self, messages):
        status, body = self.post(messages)
        self.assertEqual(200, status, body)
        return parse_sse(body.replace("\r\n", "\n"))

    @staticmethod
    def user(text):
        return {"role": "user", "content": text}

    @staticmethod
    def assistant_tool(tool):
        return {"role": "assistant", "content": [{"type": "tool_use", "id": tool["id"], "name": tool["name"], "input": tool["input"]}]}

    @staticmethod
    def result(tool, payload, is_error=False):
        text = payload if isinstance(payload, str) else json.dumps(payload)
        return {"role": "user", "content": [{"type": "tool_result", "tool_use_id": tool["id"], "content": [{"type": "text", "text": text}], "is_error": is_error}]}

    # ------------------------------------------------------------------ the scripts that existed before keep their behaviour

    def test_echo_and_chunk_scripts_are_unchanged(self):
        r = self.ask([self.user("hello")])
        self.assertEqual("end_turn", r["stop"])
        self.assertEqual(20, r["text"].count("echo["))
        r = self.ask([self.user(json.dumps({"chunks": 3, "intervalMs": 0}))])
        self.assertEqual("chunk 0 chunk 1 chunk 2 ", r["text"])

    def test_the_old_tool_script_still_sends_an_empty_tool_use_then_echoes_the_result(self):
        script = json.dumps({"tool": "fs_read", "chunks": 1, "intervalMs": 0})
        r1 = self.ask([self.user(script)])
        self.assertEqual("tool_use", r1["stop"])
        self.assertEqual([("fs_read", {})], [(t["name"], t["input"]) for t in r1["tools"]])
        r2 = self.ask([self.user(script), self.assistant_tool(r1["tools"][0]), self.result(r1["tools"][0], "boom", True)])
        self.assertEqual("end_turn", r2["stop"])
        self.assertEqual("after-tool:error:boom", r2["text"])

    def test_wrong_key_is_401(self):
        status, _ = self.post([self.user("x")], key="nope")
        self.assertEqual(401, status)

    # ------------------------------------------------------------------ toolCalls scripts

    def run_script(self, script, results):
        """Play a whole turn; `results` = what each tool call returns, in order ([payload, is_error]). -> (tools called, final text)"""
        msgs = [self.user(json.dumps(script))]
        called = []
        for i in range(len(script.get("toolCalls", [])) + 1):
            r = self.ask(msgs)
            if r["stop"] == "end_turn":
                return called, r["text"]
            self.assertEqual(1, len(r["tools"]), "one tool_use per round")
            tool = r["tools"][0]
            called.append((tool["name"], tool["input"]))
            payload, is_error = results[i]
            msgs += [self.assistant_tool(tool), self.result(tool, payload, is_error)]
        self.fail("the script did not end")

    def test_a_multi_step_script_resolves_placeholders_from_earlier_results(self):
        script = {
            "toolCalls": [
                {"mcp": ["alarm", "alarm", "alarm_create"], "arguments": {"time": "07:00", "days": ["mon", "tue"]}},
                {"mcp": ["alarm", "alarm", "alarm_set_enabled"], "arguments": {"id": "$result[0].id", "enabled": False}},
                {"name": "mcp__alarm__alarm__alarm_delete", "arguments": {"id": "${result[0].id}"}},
            ],
            "final": "alarm ${result[0].id} created, switched off and deleted",
        }
        called, final = self.run_script(script, [({"id": "a-1", "time": "07:00"}, False), ({"id": "a-1", "enabled": False}, False), ({"deleted": True}, False)])
        self.assertEqual([
            ("mcp__alarm__alarm__alarm_create", {"time": "07:00", "days": ["mon", "tue"]}),
            ("mcp__alarm__alarm__alarm_set_enabled", {"id": "a-1", "enabled": False}),
            ("mcp__alarm__alarm__alarm_delete", {"id": "a-1"}),
        ], called)
        self.assertEqual("alarm a-1 created, switched off and deleted", final)

    def test_the_record_shows_the_plan_and_the_results_that_came_back(self):
        self.fm.log.clear()
        script = {"toolCalls": [{"name": "t1", "arguments": {"a": 1}}], "final": "x"}
        self.run_script(script, [({"ok": True}, False)])
        recs = self.fm.records()
        self.assertEqual(["tool", "final"], [r["plan"]["kind"] for r in recs])
        self.assertEqual({"kind": "tool", "name": "t1", "arguments": {"a": 1}, "round": 0}, recs[0]["plan"])
        self.assertEqual([], recs[0]["toolResults"])
        self.assertEqual([{"isError": False, "text": '{"ok": true}'}], recs[1]["toolResults"])

    def test_a_failed_step_is_reported_as_a_script_error_and_nothing_half_resolved_is_sent(self):
        script = {"toolCalls": [{"name": "t1", "arguments": {}}, {"name": "t2", "arguments": {"id": "$result[0].id"}}], "final": "never"}
        called, final = self.run_script(script, [("Error: id is required", True)])
        self.assertEqual([("t1", {})], called, "t2 was not sent")
        self.assertTrue(final.startswith("script-error: step 1:"), final)
        self.assertIn("was an error", final)

    def test_error_results_flow_through_when_the_script_does_not_use_them(self):
        script = {"toolCalls": [{"name": "t1", "arguments": {"id": "missing"}}, {"name": "t2", "arguments": {}}], "final": "done"}
        called, final = self.run_script(script, [("Error: no such id", True), ({"ok": 1}, False)])
        self.assertEqual(["t1", "t2"], [c[0] for c in called])
        self.assertEqual("done", final)

    def test_non_ascii_arguments_survive_the_split_json_stream(self):
        script = {"toolCalls": [{"name": "t1", "arguments": {"text": "新品发布 · 备忘 😀", "tags": ["工作"]}}], "final": "完成"}
        called, final = self.run_script(script, [({"id": "n1"}, False)])
        self.assertEqual({"text": "新品发布 · 备忘 😀", "tags": ["工作"]}, called[0][1])
        self.assertEqual("完成", final)

    def test_a_second_prompt_in_the_same_conversation_starts_over(self):
        script1 = {"toolCalls": [{"name": "t1", "arguments": {}}], "final": "one"}
        script2 = {"toolCalls": [{"name": "t2", "arguments": {}}], "final": "two"}
        r1 = self.ask([self.user(json.dumps(script1))])
        msgs = [self.user(json.dumps(script1)), self.assistant_tool(r1["tools"][0]), self.result(r1["tools"][0], {}),
                {"role": "assistant", "content": [{"type": "text", "text": "one"}]}, self.user(json.dumps(script2))]
        r2 = self.ask(msgs)
        self.assertEqual("t2", r2["tools"][0]["name"], "the new prompt's round counter restarts; the old turn's results are not mixed in")


if __name__ == "__main__":
    unittest.main()
