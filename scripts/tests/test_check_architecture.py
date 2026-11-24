from __future__ import annotations

import contextlib
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
            self.assertIn(check_architecture.host_tests_wait_settled, check_architecture.RULES)
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
            self.assertIn(check_architecture.delivery_plans_ignore_source_names, check_architecture.RULES)
            with mock.patch.object(check_architecture, "ROOT", root):
                self.assertEqual(
                    ["PostMedia.kt's delivery plans must decide from the media, not from SourceKey"],
                    check_architecture.delivery_plans_ignore_source_names(),
                )


class CleartextTest(unittest.TestCase):
    def test_reports_cleartext_beyond_loopback_adr_0048(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manifest = root / "app/src/main/AndroidManifest.xml"
            config = root / "app/src/main/res/xml/network_security_config.xml"
            config.parent.mkdir(parents=True)
            manifest.write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<application android:networkSecurityConfig="@xml/network_security_config" /></manifest>',
                encoding="utf-8",
            )
            config.write_text(
                "<network-security-config><base-config cleartextTrafficPermitted=\"true\" />"
                "<domain-config cleartextTrafficPermitted=\"true\"><domain>localhost</domain><domain>cdn.example</domain>"
                "<domain-config><domain>media.example</domain></domain-config></domain-config>"
                "</network-security-config>",
                encoding="utf-8",
            )
            self.assertIn(check_architecture.provider_media_never_loads_in_cleartext, check_architecture.RULES)
            with mock.patch.object(check_architecture, "ROOT", root):
                self.assertEqual(
                    [
                        "network_security_config.xml must refuse cleartext in its base-config (docs/adr/0048-provider-media-urls.md)",
                        "network_security_config.xml allows cleartext to cdn.example; only loopback may (docs/adr/0048-provider-media-urls.md)",
                        "network_security_config.xml allows cleartext to media.example; only loopback may (docs/adr/0048-provider-media-urls.md)",
                    ],
                    check_architecture.provider_media_never_loads_in_cleartext(),
                )
                manifest.write_text("<manifest><application /></manifest>", encoding="utf-8")
                self.assertEqual(
                    ["app's manifest must apply @xml/network_security_config (docs/adr/0048-provider-media-urls.md)"],
                    check_architecture.provider_media_never_loads_in_cleartext(),
                )


@contextlib.contextmanager
def main_sources(files: dict[str, str]):
    with tempfile.TemporaryDirectory() as directory:
        root = pathlib.Path(directory)
        for relative, text in files.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        with mock.patch.object(check_architecture, "ROOT", root):
            yield


class CoreDomainRulesTest(unittest.TestCase):
    def test_reports_off_main_notes_outside_the_start_of_a_parse_helper_adr_0002(self) -> None:
        self.assertIn(check_architecture.off_main_notes_run_where_work_runs, check_architecture.RULES)
        with main_sources({
            "core-sources/src/main/kotlin/Clean.kt": (
                "internal fun parseJsonArray(\n"
                "    body: String,\n"
                "    onError: () -> Unit = {},\n"
                "): JsonArray {\n"
                "    OffMainWork\n"
                "        .note(\n"
                "            OffMainWork.SOURCE_PARSE,\n"
                "        )\n"
                "    return parse(body)\n"
                "}\n"
                "private fun SourceHttpResponse.jsonBody() = body.takeIf(String::isNotBlank)?.let {\n"
                "    OffMainWork.note(com.theoriacodex.domain.diagnostics.OffMainWork.SOURCE_PARSE)\n"
                "}\n"
                "fun parseHtml(html: String): Document {\n"
                "    com.theoriacodex.domain.diagnostics.OffMainWork.note(label = OffMainWork.SOURCE_PARSE)\n"
                "    return Jsoup.parse(html)\n"
                "}\n"
                "suspend fun explain() {\n"
                "    log(\"call OffMainWork.note(first) in the helper {\")\n"
                "}\n"
            ),
            "core-sources/src/main/kotlin/Broken.kt": (
                "override suspend fun search(\n"
                "    query: Query,\n"
                "): Page {\n"
                "    OffMainWork.note(OffMainWork.SOURCE_PARSE)\n"
                "}\n"
                "fun parse(html: String): Document {\n"
                "    val trimmed = html.trim()\n"
                "    OffMainWork.note(OffMainWork.SOURCE_PARSE)\n"
                "}\n"
                "fun title(post: Post): Document {\n"
                "    OffMainWork.note(\n"
                "        post.title,\n"
                "    )\n"
                "}\n"
            ),
            "media-kit/src/main/kotlin/Imported.kt": (
                "import com.theoriacodex.domain.diagnostics.OffMainWork.note\n"
                "fun parse(body: String) {\n"
                "    note(OffMainWork.SOURCE_PARSE)\n"
                "}\n"
                "suspend fun load(body: String) {\n"
                "    note(OffMainWork.SOURCE_PARSE)\n"
                "}\n"
            ),
        }):
            failures = check_architecture.off_main_notes_run_where_work_runs()
        self.assertEqual(
            ["core-sources/src/main/kotlin/Broken.kt:4", "core-sources/src/main/kotlin/Broken.kt:8",
             "core-sources/src/main/kotlin/Broken.kt:11", "media-kit/src/main/kotlin/Imported.kt:6"],
            [failure.split(" ", 1)[0] for failure in failures],
        )


if __name__ == "__main__":
    unittest.main()
