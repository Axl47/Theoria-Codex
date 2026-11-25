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
    def test_reports_a_delivery_plan_that_decides_from_a_source_adr_0074_adr_0105(self) -> None:
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


class DiagnosticsRulesTest(unittest.TestCase):
    def test_reports_a_manifest_that_asks_to_read_other_apps_logs_adr_0103(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            manifests = {
                "app/src/debug": '<uses-permission android:name="android.permission.INTERNET" />\n'
                                 '<uses-permission\n    android:name="android.permission.READ_LOGS" />',
                "app/src/release": '<uses-permission-sdk-23 android:name="android.permission.READ_LOGS"/>',
                "media-kit/src/main": '<uses-permission android:name="android.permission.READ_LOGS" tools:node="remove" />',
                "feature-viewer/src/main": '<uses-permission android:name="android.permission.INTERNET" />',
                "feature-search/src/main": '<!-- <uses-permission android:name="android.permission.READ_LOGS" /> -->',
                "feature-settings/src/main": '<uses-permission android:name="android.permission.READ_LOGS" tools:node = "remove" />',
            }
            for source_set, text in manifests.items():
                manifest = root / source_set / "AndroidManifest.xml"
                manifest.parent.mkdir(parents=True)
                manifest.write_text(text + "\n", encoding="utf-8")
            self.assertIn(check_architecture.no_manifest_reads_other_apps_logs, check_architecture.RULES)
            with mock.patch.object(check_architecture, "ROOT", root):
                self.assertEqual(
                    [f"app/src/{source_set}/AndroidManifest.xml requests READ_LOGS; the diagnostic report reads only the app's own log "
                     "(docs/adr/0103-diagnostics-log-and-usage.md)" for source_set in ("debug", "release")],
                    check_architecture.no_manifest_reads_other_apps_logs(),
                )

class HttpClientRuleTest(unittest.TestCase):
    def test_reports_a_client_built_outside_the_shared_one_adr_0102(self) -> None:
        files = {
            "feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/Thumbnails.kt": "val client = OkHttpClient()\n",
            "app/src/personalRelease/java/com/theoriacodex/app/Probe.java": "OkHttpClient client = new OkHttpClient.Builder().build();\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/network/AppHttpClients.kt": "val shared = OkHttpClient.Builder().build()\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/viewer/PixivUgoiraClient.kt": "val download: OkHttpClient = OkHttpClient()\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/media/Covers.kt": "val covers = shared.newBuilder().build()\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoCache.kt": "val cache = OkHttpClient()\n",
            "feature-settings/src/main/kotlin/com/theoriacodex/app/settings/About.kt":
                'val label = "OkHttpClient()"\n// OkHttpClient.Builder() is not used here\n',
            "media-kit/src/test/kotlin/com/theoriacodex/app/network/ClientTest.kt": "val client = OkHttpClient()\n",
            "media-kit/src/sharedTest/kotlin/com/theoriacodex/app/viewer/Fixture.kt": "val client = OkHttpClient()\n",
        }
        with main_sources(files):
            self.assertIn(check_architecture.http_clients_derive_from_the_shared_client, check_architecture.RULES)
            self.assertEqual(
                [f"{path} builds its own OkHttpClient; derive it from AppHttpClients' shared client with newBuilder() "
                 "(docs/adr/0102-app-http-clients.md)"
                 for path in [
                     "app/src/personalRelease/java/com/theoriacodex/app/Probe.java",
                     "feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/Thumbnails.kt",
                     "media-kit/src/main/kotlin/com/theoriacodex/app/viewer/VideoCache.kt",
                 ]],
                check_architecture.http_clients_derive_from_the_shared_client(),
            )


class DownloadManagerRuleTest(unittest.TestCase):
    def test_reports_animation_export_through_download_manager_adr_0110(self) -> None:
        files = {
            "media-kit/src/main/kotlin/com/theoriacodex/app/viewer/UgoiraMp4Export.kt":
                "val queue = context.getSystemService(DownloadManager::class.java)\n",
            "feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/Export.kt":
                "val queue = context.getSystemService(Context.DOWNLOAD_SERVICE)\n",
            "app/src/personalRelease/java/com/theoriacodex/app/Export.java":
                "DownloadManager queue = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadService.kt":
                "import android.app.DownloadManager\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadManagerHints.kt": "val hint = 1\n",
            "app/src/main/java/com/theoriacodex/app/update/Updater.kt": "val apk = ApkDownloadManager(context)\n",
            "app/src/androidTest/java/com/theoriacodex/app/DownloadsDeviceTest.kt": "val queue: DownloadManager? = null\n",
            "media-kit/src/test/kotlin/com/theoriacodex/app/media/QueueTest.kt": "val queue: DownloadManager? = null\n",
            "media-kit/src/sharedTest/kotlin/com/theoriacodex/app/viewer/Fixture.kt": "val queue: DownloadManager? = null\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadAnimationExport.kt":
                "val queue = context.getSystemService(DownloadManager::class.java)\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/media/downloads/PostDownloads.kt":
                "val queue = context.getSystemService(DownloadManager::class.java)\n",
            "feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/Quotes.kt":
                'val quotes = text.count { it == \'"\' } + DOWNLOAD_SERVICE.length + "".length\n',
            "feature-settings/src/main/kotlin/com/theoriacodex/app/settings/About.kt":
                'val label = "Android DownloadManager"\n// DownloadManager is not used here\n',
        }
        with main_sources(files):
            self.assertIn(check_architecture.only_post_downloads_use_download_manager, check_architecture.RULES)
            self.assertEqual(
                [f"{path} uses Android's DownloadManager; only post downloads queue there, and animation export uses the "
                 "app's own transport (docs/adr/0110-offline-media-jobs.md)"
                 for path in [
                     "app/src/personalRelease/java/com/theoriacodex/app/Export.java",
                     "feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/Export.kt",
                     "feature-viewer/src/main/kotlin/com/theoriacodex/app/viewer/Quotes.kt",
                     "media-kit/src/main/kotlin/com/theoriacodex/app/media/PostDownloadAnimationExport.kt",
                     "media-kit/src/main/kotlin/com/theoriacodex/app/viewer/UgoiraMp4Export.kt",
                 ]],
                check_architecture.only_post_downloads_use_download_manager(),
            )


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


class VisionCommitRuleTest(unittest.TestCase):
    def test_reports_vision_results_written_outside_the_tagger_adr_0097(self) -> None:
        self.assertIn(check_architecture.vision_results_are_written_under_commits, check_architecture.RULES)
        opt_in = "@OptIn(VisionCommit::class)\nfun writeAround() = Unit\n"
        with main_sources({
            check_architecture.VISION_TAGGER: (
                "@OptIn(ExperimentalCoroutinesApi::class)\nclass PictureTagger {\n    @OptIn(VisionCommit::class)\n    suspend fun forget() = Unit\n}\n"
            ),
            check_architecture.VISION_PROPAGATORS[0]: "annotation class VisionCommit\n@VisionCommit suspend fun clearVision()\n",
            check_architecture.VISION_PROPAGATORS[1]: "@VisionCommit override suspend fun clearVision() = Unit\n",
            "app/src/main/java/com/theoriacodex/app/vision/AppPictures.kt": opt_in,
            "app/src/debug/java/com/theoriacodex/app/DebugTools.kt": opt_in,
            "app/src/main/java/com/theoriacodex/app/Writer.java": "@kotlin.OptIn(markerClass = VisionCommit.class) class Writer {}\n",
            "media-kit/src/main/kotlin/com/theoriacodex/app/media/Backfill.kt": "@file:OptIn(com.theoriacodex.data.repository.VisionCommit::class)\n",
            "vision-kit/src/main/kotlin/com/theoriacodex/app/vision/Sibling.kt": opt_in,
            "core-data/src/main/kotlin/com/theoriacodex/data/repository/Other.kt": "@VisionCommit fun passOn() = Unit\n",
            "vision-kit/build.gradle.kts": "kotlin { compilerOptions { optIn.add(\"com.theoriacodex.data.repository.VisionCommit\") } }\n",
            "media-kit/build.gradle": "kotlinOptions { freeCompilerArgs += '-opt-in=com.theoriacodex.data.repository.VisionCommit' }\n",
            "app/src/main/java/com/theoriacodex/app/Message.kt": "val why = \"VisionCommit is held by the tagger\"\n",
            "app/src/test/java/com/theoriacodex/app/FakeTest.kt": opt_in,
            "app/src/androidTest/java/com/theoriacodex/app/DeviceTest.kt": opt_in,
            "core-data-android/src/sharedTest/kotlin/Scenario.kt": opt_in,
            "vision-kit/build/generated/Stale.gradle.kts": "VisionCommit\n",
        }):
            failures = check_architecture.vision_results_are_written_under_commits()
            self.assertEqual(
                [
                    "app/src/debug/java/com/theoriacodex/app/DebugTools.kt",
                    "app/src/main/java/com/theoriacodex/app/Writer.java",
                    "app/src/main/java/com/theoriacodex/app/vision/AppPictures.kt",
                    "core-data/src/main/kotlin/com/theoriacodex/data/repository/Other.kt",
                    "media-kit/src/main/kotlin/com/theoriacodex/app/media/Backfill.kt",
                    "vision-kit/src/main/kotlin/com/theoriacodex/app/vision/Sibling.kt",
                    "media-kit/build.gradle",
                    "vision-kit/build.gradle.kts",
                ],
                [failure.split(" ", 1)[0] for failure in failures],
            )
            (check_architecture.ROOT / check_architecture.VISION_PROPAGATORS[1]).write_text(
                "@OptIn(VisionCommit::class)\nclass RoomVisualSignatureRepository\n", encoding="utf-8",
            )
            self.assertIn(
                check_architecture.VISION_PROPAGATORS[1],
                [failure.split(" ", 1)[0] for failure in check_architecture.vision_results_are_written_under_commits()],
            )
            tagger = check_architecture.ROOT / check_architecture.VISION_TAGGER
            for wide in (
                "@OptIn(ExperimentalCoroutinesApi::class, VisionCommit::class)\nclass PictureTagger\n",
                "@OptIn(VisionCommit::class)\n@Suppress(\"x\")\ninternal class PictureTagger\n",
                "@file:OptIn(com.theoriacodex.data.repository.VisionCommit::class)\npackage x\n",
            ):
                tagger.write_text(wide, encoding="utf-8")
                self.assertIn(
                    check_architecture.VISION_TAGGER,
                    [failure.split(" ", 1)[0] for failure in check_architecture.vision_results_are_written_under_commits()],
                    wide,
                )


if __name__ == "__main__":
    unittest.main()
