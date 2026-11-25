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
                    [
                        "PostMedia.kt's delivery plans must decide from the media, not from SourceKey",
                        "PostMedia.kt's delivery plans must not decide from a source id or isCustom",
                    ],
                    check_architecture.delivery_plans_ignore_source_names(),
                )


    def test_reports_a_preview_candidate_that_decides_from_a_source_id_adr_0074(self) -> None:
        with main_sources({"media-kit/src/main/kotlin/com/theoriacodex/app/media/PostMedia.kt": (
            "fun postPreviewImageCandidate(post: Post): Candidate? {\n"
            "    if (post.id.source.id == \"danbooru\") return null\n"
            "}\n"
            "fun postPlaybackMediaCandidate() = Unit\n"
            "fun previewMediaDeliveryPlan() = Unit\n"
            "private fun mediaDeliveryPlan() = hasLighterRenditions()\n"
            "private fun ImageRef.hasLighterRenditions() = true\n"
        )}):
            self.assertEqual(
                ["PostMedia.kt's delivery plans must not decide from a source id or isCustom"],
                check_architecture.delivery_plans_ignore_source_names(),
            )

    def test_allows_only_the_named_pixiv_medium_first_exception_adr_0074(self) -> None:
        with main_sources({"media-kit/src/main/kotlin/com/theoriacodex/app/media/PostMedia.kt": (
            "fun postPreviewImageCandidate(post: Post): Candidate? {\n"
            "    if (post.id.source == SourceKey.PIXIV && full != null) {\n"
            "    }\n"
            "}\n"
            "fun postPlaybackMediaCandidate() = Unit\n"
            "fun previewMediaDeliveryPlan() = Unit\n"
            "private fun mediaDeliveryPlan() = hasLighterRenditions()\n"
            "private fun ImageRef.hasLighterRenditions() = true\n"
        )}):
            self.assertEqual([], check_architecture.delivery_plans_ignore_source_names())


class CleartextTest(unittest.TestCase):
    def test_reports_cleartext_beyond_loopback_in_any_source_set_adr_0048(self) -> None:
        android = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools"'
        applied = f'<manifest {android}><application android:networkSecurityConfig="@xml/network_security_config" /></manifest>'
        safe = (
            '<network-security-config><base-config cleartextTrafficPermitted="false" />'
            '<domain-config cleartextTrafficPermitted="true"><domain>localhost</domain></domain-config></network-security-config>'
        )
        files = {
            "app/src/main/AndroidManifest.xml": applied,
            "app/src/main/res/xml/network_security_config.xml": safe,
            "app/src/debug/AndroidManifest.xml": f'<manifest {android}><application android:networkSecurityConfig="@xml/debug_network" /></manifest>',
            "app/src/release/AndroidManifest.xml": f'<manifest {android}><application tools:remove="android:networkSecurityConfig" /></manifest>',
            "app/src/benchmarkRelease/AndroidManifest.xml": f'<manifest {android}><application tools:node="replace" android:usesCleartextTraffic="true" /></manifest>',
            "app/src/release/res/xml-v28/network_security_config.xml": (
                '<network-security-config><base-config cleartextTrafficPermitted="true" />'
                '<domain-config cleartextTrafficPermitted="true"><domain>localhost</domain><domain>cdn.example</domain>'
                "<domain-config><domain>media.example</domain></domain-config></domain-config></network-security-config>"
            ),
            "media-kit/src/main/AndroidManifest.xml": f'<manifest {android}><application tools:node="remove" android:usesCleartextTraffic="True" /></manifest>',
            "media-kit/src/main/res/xml/network_security_config.xml": (
                '<network-security-config><base-config cleartextTrafficPermitted="False" />'
                '<domain-config cleartextTrafficPermitted="false"><domain>quiet.example</domain>'
                '<domain-config cleartextTrafficPermitted="true"><domain>nested.example</domain></domain-config>'
                '<domain-config cleartextTrafficPermitted="yes"><domain>unparsed.example</domain></domain-config></domain-config>'
                '<domain-config cleartextTrafficPermitted="true"><domain>LocalHost</domain><domain>::1</domain>'
                "<domain>127.0.0.2</domain><domain>[::1]</domain></domain-config></network-security-config>"
            ),
            "media-kit/src/androidTest/AndroidManifest.xml": f'<manifest {android}><application android:usesCleartextTraffic="true" /></manifest>',
            "app/src/test/AndroidManifest.xml": f'<manifest {android}><application android:usesCleartextTraffic="true" /></manifest>',
            "app/src/test/res/xml/network_security_config.xml": '<network-security-config><domain-config cleartextTrafficPermitted="true"><domain>fixture.example</domain></domain-config></network-security-config>',
            "app/src/androidTestDebug/res/xml/network_security_config.xml": '<network-security-config><base-config cleartextTrafficPermitted="true" /></network-security-config>',
        }
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            for relative, text in files.items():
                (root / relative).parent.mkdir(parents=True, exist_ok=True)
                (root / relative).write_text(text, encoding="utf-8")
            self.assertIn(check_architecture.provider_media_never_loads_in_cleartext, check_architecture.RULES)
            adr = "(docs/adr/0048-provider-media-urls.md)"
            with mock.patch.object(check_architecture, "ROOT", root):
                self.assertEqual(
                    [
                        f"app/src/benchmarkRelease/AndroidManifest.xml must not set usesCleartextTraffic {adr}",
                        f"app/src/benchmarkRelease/AndroidManifest.xml must not drop the application's network security config {adr}",
                        f"app/src/debug/AndroidManifest.xml must apply @xml/network_security_config {adr}",
                        f"app/src/release/AndroidManifest.xml must not drop the application's network security config {adr}",
                        f"media-kit/src/main/AndroidManifest.xml must not set usesCleartextTraffic {adr}",
                        f"media-kit/src/main/AndroidManifest.xml must not drop the application's network security config {adr}",
                        f"app/src/release/res/xml-v28/network_security_config.xml must refuse cleartext in its base-config {adr}",
                        f"app/src/release/res/xml-v28/network_security_config.xml allows cleartext to cdn.example; only loopback may {adr}",
                        f"app/src/release/res/xml-v28/network_security_config.xml allows cleartext to media.example; only loopback may {adr}",
                        f'media-kit/src/main/res/xml/network_security_config.xml sets cleartextTrafficPermitted="False"; write true or false {adr}',
                        f'media-kit/src/main/res/xml/network_security_config.xml sets cleartextTrafficPermitted="yes"; write true or false {adr}',
                        f"media-kit/src/main/res/xml/network_security_config.xml must refuse cleartext in its base-config {adr}",
                        f"media-kit/src/main/res/xml/network_security_config.xml allows cleartext to nested.example; only loopback may {adr}",
                        f"media-kit/src/main/res/xml/network_security_config.xml allows cleartext to unparsed.example; only loopback may {adr}",
                    ],
                    check_architecture.provider_media_never_loads_in_cleartext(),
                )
                (root / "app/src/main/AndroidManifest.xml").write_text(f"<manifest {android}><application /></manifest>", encoding="utf-8")
                self.assertIn(
                    f"app/src/main/AndroidManifest.xml must apply @xml/network_security_config {adr}",
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
