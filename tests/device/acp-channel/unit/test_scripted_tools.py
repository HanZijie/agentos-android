import json
import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import scripted_tools as S  # noqa: E402


def ok(obj):
    return {"isError": False, "text": json.dumps(obj)}


def err(text):
    return {"isError": True, "text": text}


class NamingTest(unittest.TestCase):
    def test_golden_vectors_shared_with_kotlin(self):
        with open(os.path.join(os.path.dirname(HERE), "tool_naming_golden.json"), encoding="utf-8") as f:
            vectors = json.load(f)
        self.assertGreaterEqual(len(vectors), 10)
        for v in vectors:
            self.assertEqual(v["name"], S.tool_name(v["plugin"], v["server"], v["tool"]), v)
            self.assertRegex(v["name"], r"^[A-Za-z0-9_-]{1,64}$")

    def test_plain_form(self):
        self.assertEqual("mcp__alarm__alarm__alarm_create", S.tool_name("alarm", "alarm", "alarm_create"))
        self.assertEqual("mcp__com_example_notes__main__list", S.tool_name("com.example.notes", "main", "list"))

    def test_astral_characters_are_two_underscores_like_utf16(self):
        self.assertEqual("mcp__p__s____x", S.tool_name("p", "s", "😀x"))

    def test_long_names_are_cut_with_a_hash(self):
        n = S.tool_name("p", "s", "t" * 80)
        self.assertEqual(64, len(n))
        self.assertRegex(n, r"_[0-9a-f]{6}$")
        self.assertNotEqual(n, S.tool_name("p", "s", "t" * 81))

    def test_empty_segment_is_rejected(self):
        with self.assertRaises(ValueError):
            S.tool_name("p", "", "t")


class PlaceholderTest(unittest.TestCase):
    def test_whole_string_keeps_the_type(self):
        r = [ok({"id": "a1", "n": 3, "on": True, "tags": ["x", "y"], "nothing": None})]
        self.assertEqual("a1", S.resolve("$result[0].id", r))
        self.assertEqual(3, S.resolve("$result[0].n", r))
        self.assertIs(True, S.resolve("$result[0].on", r))
        self.assertEqual(["x", "y"], S.resolve("$result[0].tags", r))
        self.assertEqual("y", S.resolve("$result[0].tags[1]", r))
        self.assertEqual("y", S.resolve("$result[0].tags[-1]", r))
        self.assertIsNone(S.resolve("$result[0].nothing", r))
        self.assertEqual(r_obj(r), S.resolve("$result[0]", r))

    def test_negative_result_index_is_relative_to_the_end(self):
        r = [ok({"id": "first"}), ok({"id": "second"})]
        self.assertEqual("second", S.resolve("$result[-1].id", r))
        self.assertEqual("first", S.resolve("$result[-2].id", r))

    def test_inline_form_is_text(self):
        r = [ok({"id": "a1", "n": 3, "tags": ["x"]})]
        self.assertEqual("id=a1 n=3 tags=[\"x\"]", S.resolve("id=${result[0].id} n=${result[0].n} tags=${result[0].tags}", r))

    def test_walks_into_lists_and_objects(self):
        r = [ok({"id": "a1"})]
        self.assertEqual({"ids": ["a1", "z"], "deep": {"k": "a1"}}, S.resolve({"ids": ["$result[0].id", "z"], "deep": {"k": "$result[0].id"}}, r))

    def test_other_values_pass_through(self):
        self.assertEqual({"a": 1, "b": [True, None, 1.5, "plain"]}, S.resolve({"a": 1, "b": [True, None, 1.5, "plain"]}, []))

    def test_double_dollar_is_a_literal(self):
        r = [ok({"id": "a1"})]
        self.assertEqual("$result[0].id", S.resolve("$$result[0].id", r))
        self.assertEqual("$5 ${result[0].id}", S.resolve("$$5 ${result[0].id}", r))

    def test_text_that_only_looks_similar_is_untouched(self):
        r = [ok({"id": "a1"})]
        for s in ("$results[0]", "result[0].id", "price $result", "$result[x]", "cost: $5", "${result}"):
            self.assertEqual(s, S.resolve(s, r), s)

    def test_failures_say_what_and_where(self):
        cases = [
            ([], "$result[0].id", "does not exist yet"),
            ([ok({"id": 1})], "$result[1]", "does not exist yet"),
            ([ok({"id": 1})], "$result[0].nope", "has no key 'nope'"),
            ([ok({"a": [1]})], "$result[0].a[3]", "out of range"),
            ([ok({"a": 1})], "$result[0].a[0]", "out of range"),
            ([err("boom: bad id")], "$result[0].id", "was an error"),
            ([{"isError": False, "text": "plain words"}], "$result[0].id", "not JSON"),
            ([ok([1, 2])], "$result[0].id", "has no key"),
        ]
        for results, text, expect in cases:
            with self.assertRaises(S.ScriptError, msg=text) as cm:
                S.resolve(text, results, "arguments")
            self.assertIn(expect, str(cm.exception))
            self.assertIn("arguments", str(cm.exception))


def r_obj(results):
    return json.loads(results[0]["text"])


class PlanTest(unittest.TestCase):
    script = {
        "toolCalls": [
            {"mcp": ["alarm", "alarm", "alarm_create"], "arguments": {"time": "07:00"}},
            {"name": "mcp__alarm__alarm__alarm_set_enabled", "arguments": {"id": "$result[0].id", "enabled": False}},
            {"mcp": {"plugin": "alarm", "server": "alarm", "tool": "alarm_next"}},
        ],
        "final": "created ${result[0].id}",
    }

    def test_rounds_go_in_order_then_the_final_text(self):
        r0 = ok({"id": "a1"})
        p0 = S.plan_round(self.script, 0, [])
        self.assertEqual({"kind": "tool", "name": "mcp__alarm__alarm__alarm_create", "arguments": {"time": "07:00"}}, p0)
        p1 = S.plan_round(self.script, 1, [r0])
        self.assertEqual({"kind": "tool", "name": "mcp__alarm__alarm__alarm_set_enabled", "arguments": {"id": "a1", "enabled": False}}, p1)
        p2 = S.plan_round(self.script, 2, [r0, ok({})])
        self.assertEqual({"kind": "tool", "name": "mcp__alarm__alarm__alarm_next", "arguments": {}}, p2)
        self.assertEqual({"kind": "final", "text": "created a1"}, S.plan_round(self.script, 3, [r0, ok({}), ok({})]))
        self.assertEqual("created a1", S.plan_round(self.script, 9, [r0])["text"])

    def test_final_only_script(self):
        self.assertEqual({"kind": "final", "text": "just text"}, S.plan_round({"final": "just text"}, 0, []))
        self.assertEqual({"kind": "final", "text": ""}, S.plan_round({"toolCalls": []}, 0, []))

    def test_an_error_result_does_not_stop_a_script_that_does_not_use_it(self):
        s = {"toolCalls": [{"name": "t1", "arguments": {"bad": 1}}, {"name": "t2", "arguments": {"x": 1}}], "final": "ok"}
        self.assertEqual("t2", S.plan_round(s, 1, [err("missing id")])["name"])

    def test_an_unresolvable_placeholder_ends_the_turn_with_a_script_error_naming_the_step(self):
        p = S.plan_round(self.script, 1, [err("alarm_create failed: bad time")])
        self.assertEqual("error", p["kind"])
        self.assertTrue(p["text"].startswith("script-error: step 1:"), p["text"])
        self.assertIn("toolCalls[1].arguments.id", p["text"])
        self.assertIn("was an error", p["text"])

    def test_malformed_scripts(self):
        bad = [
            ({"toolCalls": "x"}, "toolCalls must be a list"),
            ({"toolCalls": [5]}, "not an object"),
            ({"toolCalls": [{"arguments": {}}]}, "needs `name`"),
            ({"toolCalls": [{"name": "t", "arguments": []}]}, "arguments must be an object"),
            ({"toolCalls": [{"mcp": ["a", "b"]}]}, "needs `name`"),
            ({"final": 5}, "final must be a string"),
        ]
        for script, expect in bad:
            p = S.plan_round(script, 0, [])
            self.assertEqual("error", p["kind"], script)
            self.assertIn(expect, p["text"])

    def test_is_plan_script(self):
        self.assertTrue(S.is_plan_script({"toolCalls": []}))
        self.assertTrue(S.is_plan_script({"final": "x"}))
        self.assertFalse(S.is_plan_script({"tool": "x"}))
        self.assertFalse(S.is_plan_script({"chunks": 3}))
        self.assertFalse(S.is_plan_script(None))


if __name__ == "__main__":
    unittest.main()
