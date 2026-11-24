from __future__ import annotations

import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

import check_architecture  # noqa: E402


class BareComposeWaitTest(unittest.TestCase):
    def test_flags_every_spelling_of_a_bare_wait_adr_0210(self) -> None:
        spellings = [
            "compose.waitUntil(10_000) { ready }",
            "        waitUntil { ready }",
            "with(compose) { waitUntil { ready } }",
            "val done = waitUntil(timeoutMillis = 1_000) { ready }",
            "runComposeUiTest { waitUntil { ready } }",
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            tests = root / "feature/src/test/kotlin"
            tests.mkdir(parents=True)
            (tests / "BareTest.kt").write_text("\n".join(spellings) + "\n", encoding="utf-8")
            (tests / "SettledTest.kt").write_text("compose.waitUntilSettled { ready }\n", encoding="utf-8")
            with mock.patch.object(check_architecture, "ROOT", root):
                self.assertEqual(
                    [f"feature/src/test/kotlin/BareTest.kt:{number} uses a bare waitUntil; use waitUntilSettled (docs/adr/0210-host-test-compose-waits.md)"
                     for number in range(1, len(spellings) + 1)],
                    check_architecture.host_tests_wait_settled(),
                )

    def test_allows_the_settled_helpers_and_declarations(self) -> None:
        for line in [
            "compose.waitUntilSettled { ready }",
            "compose.waitUntilWithoutIdling(5_000) { parked }",
            "compose.waitUntilAtLeastOneExists(hasText(\"A\"))",
            "private fun waitUntil(condition: () -> Boolean) = Unit",
        ]:
            self.assertFalse(check_architecture.is_bare_compose_wait(line), line)


class DeliveryPlanTest(unittest.TestCase):
    def test_reports_a_delivery_plan_that_decides_from_a_source_adr_0074(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            post_media = root / "media-kit/src/main/kotlin/com/theoriacodex/app/media/PostMedia.kt"
            post_media.parent.mkdir(parents=True)
            post_media.write_text(
                "fun previewMediaDeliveryPlan() = Unit\n"
                "private fun mediaDeliveryPlan(post: Post) = post.source == SourceKey.Pixiv || hasLighterRenditions()\n"
                "private fun ImageRef.hasLighterRenditions() = true\n",
                encoding="utf-8",
            )
            with mock.patch.object(check_architecture, "ROOT", root):
                self.assertEqual(
                    ["PostMedia.kt's delivery plans must decide from the media, not from SourceKey"],
                    check_architecture.delivery_plans_ignore_source_names(),
                )


if __name__ == "__main__":
    unittest.main()
