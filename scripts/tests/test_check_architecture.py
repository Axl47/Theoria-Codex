from __future__ import annotations

import inspect
import pathlib
import sys
import typing
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import check_architecture  # noqa: E402


class BareComposeWaitTest(unittest.TestCase):
    def test_flags_every_spelling_of_a_bare_wait(self) -> None:
        for line in [
            "compose.waitUntil(10_000) { ready }",
            "        waitUntil { ready }",
            "with(compose) { waitUntil { ready } }",
            "val done = waitUntil(timeoutMillis = 1_000) { ready }",
            "runComposeUiTest { waitUntil { ready } }",
        ]:
            self.assertTrue(check_architecture.is_bare_compose_wait(line), line)

    def test_allows_the_settled_helpers_and_declarations(self) -> None:
        for line in [
            "compose.waitUntilSettled { ready }",
            "compose.waitUntilWithoutIdling(5_000) { parked }",
            "compose.waitUntilAtLeastOneExists(hasText(\"A\"))",
            "private fun waitUntil(condition: () -> Boolean) = Unit",
        ]:
            self.assertFalse(check_architecture.is_bare_compose_wait(line), line)


class RulesTest(unittest.TestCase):
    def test_every_rule_is_registered(self) -> None:
        rules = {
            function
            for _, function in inspect.getmembers(check_architecture, inspect.isfunction)
            if function.__module__ == check_architecture.__name__ and typing.get_type_hints(function).get("return") == list[str]
        }
        self.assertTrue(rules)
        self.assertEqual(rules, set(check_architecture.RULES))


if __name__ == "__main__":
    unittest.main()
