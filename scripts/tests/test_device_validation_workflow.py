import pathlib
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "device-validation.yml"
RELEASE_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "release.yml"


class DeviceValidationWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_connected_lane_executes_and_retains_every_instrumentation_owner_adr_0109(self) -> None:
        self.assertIn("script: bash scripts/verify_ci_emulator.sh debug", self.workflow)
        wrapper = (REPOSITORY_ROOT / "scripts/verify_ci_emulator.sh").read_text(encoding="utf-8")
        self.assertIn("scripts/verify_debug_device.sh all", wrapper)
        self.assertIn("shard: [1, 2]", self.workflow)
        self.assertIn("DEVICE_TEST_SHARD: ${{ matrix.shard }}/2", self.workflow)
        lane = (REPOSITORY_ROOT / "scripts/verify_debug_device.sh").read_text(encoding="utf-8")
        self.assertIn(":app:connectedDebugAndroidTest", lane)
        for owner, module in (("app", "app"), ("data", "core-data-android"), ("media", "media-kit"), ("viewer", "feature-viewer")):
            self.assertRegex(
                lane,
                rf'if \[\[ "\$owner" == {owner} \|\| "\$owner" == all \]\]; then\n  connected\+=\(":{module}:connectedDebugAndroidTest"\)',
            )
        for module in ("core-data-android", "media-kit", "feature-viewer"):
            self.assertIn(f":{module}:connectedDebugAndroidTest", lane)
            self.assertIn(f":{module}:assembleDebugAndroidTest", self.workflow)
            self.assertIn(f"{module}/build/outputs/androidTest-results/connected/", self.workflow)
            self.assertIn(f"{module}/build/reports/androidTests/connected/", self.workflow)

    def test_only_an_attempt_without_a_verdict_is_retried(self) -> None:
        for owner, step in (("debug", "device-tests"), ("acceptance", "acceptance")):
            self.assertIn(f"id: {step}\n        continue-on-error: true", self.workflow)
            self.assertIn(f"if: steps.{step}.outcome == 'failure' && "
                          f"hashFiles('build/reports/ci-emulator/{owner}/verdict') == ''", self.workflow)
            self.assertIn(f"if: steps.{step}.outcome == 'failure' && steps.{step}-retry.outcome == 'skipped'\n"
                          "        run: exit 1", self.workflow)

    def test_release_acceptance_artifact_matches_emulator_api(self) -> None:
        self.assertIn("name: R8 release acceptance (API ${{ matrix.api-level }})", self.workflow)
        self.assertIn("name: release-acceptance-api-${{ matrix.api-level }}", self.workflow)
        self.assertEqual(2, self.workflow.count('api-level: ["36", "37.0"]'))
        self.assertEqual(2, self.workflow.count("api-level: ${{ matrix.api-level }}"))
        self.assertEqual(2, self.workflow.count("disk-size: 6G"))
        self.assertEqual(2, self.workflow.count("ram-size: 4096M"))
        self.assertEqual(2, self.workflow.count("run: bash scripts/prepare_ci_android_sdk.sh"))

    def test_acceptance_signing_directory_is_initialized_before_build(self) -> None:
        acceptance = self.workflow.split("  release-acceptance:", 1)[1]
        self.assertIn('echo "ANDROID_USER_HOME=$android_user_home" >> "$GITHUB_ENV"', acceptance)
        self.assertLess(acceptance.index('mkdir -p "$android_user_home"'),
                        acceptance.index("run: ./gradlew :app:assembleReleaseAcceptance"))

    def test_gradle_daemons_are_stopped_between_apk_build_and_emulator_boot(self) -> None:
        connected = self.workflow.split("  connected-tests:", 1)[1].split("  release-acceptance:", 1)[0]
        acceptance = self.workflow.split("  release-acceptance:", 1)[1].split("  record:", 1)[0]
        for name, job, build_cmd, boot_step in (
            (
                "connected-tests",
                connected,
                "run: ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :core-data-android:assembleDebugAndroidTest :media-kit:assembleDebugAndroidTest",
                "uses: reactivecircus/android-emulator-runner@v2",
            ),
            (
                "release-acceptance",
                acceptance,
                "run: ./gradlew :app:assembleReleaseAcceptance",
                "uses: reactivecircus/android-emulator-runner@v2",
            ),
        ):
            with self.subTest(job=name):
                self.assertIn("run: ./gradlew --stop", job)
                build_pos = job.index(build_cmd)
                stop_pos = job.index("run: ./gradlew --stop")
                boot_pos = job.index(boot_step)
                self.assertLess(build_pos, stop_pos)
                self.assertLess(stop_pos, boot_pos)

    def test_release_runs_only_the_results_that_are_missing(self) -> None:
        release = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        verify = release.split("  verify:", 1)[1].split("\n  device-validation:", 1)[0]
        device = release.split("  device-validation:", 1)[1].split("\n  build-release:", 1)[0]
        self.assertIn("uses: ./.github/workflows/verify.yml", verify)
        self.assertIn("check: ${{ needs.gate.outputs.verified != 'true' }}", verify)
        self.assertIn("release: ${{ needs.gate.outputs.release != 'true' }}", verify)
        self.assertIn("uses: ./.github/workflows/device-validation.yml", device)
        self.assertIn("if: ${{ needs.gate.outputs.device != 'true' }}", device)

    def test_device_lane_never_blocks_ordinary_changes(self) -> None:
        trigger = self.workflow.split("on:", 1)[1].split("permissions:", 1)[0]
        self.assertNotIn("pull_request:", trigger)
        self.assertNotIn("push:", trigger)

    def test_unpublished_signed_apk_is_discarded(self) -> None:
        release = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        discard = release.split("  discard-release-apk:", 1)[1]
        self.assertIn("needs: [build-release, publish]", discard)
        self.assertIn("needs.publish.result != 'success'", discard)
        self.assertIn("actions/artifacts/${id}", discard)

    def test_tag_is_created_only_after_every_lane_passes_on_main(self) -> None:
        release = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        publish = release.split("\n  publish:", 1)[1].split("\n  # A signed APK", 1)[0]
        self.assertIn("needs: [gate, verify, device-validation, build-release]", publish)
        self.assertIn("github.ref == 'refs/heads/main'", publish)
        self.assertIn("needs.device-validation.result == 'success'", publish)
        self.assertIn("needs.build-release.result == 'success'", publish)
        self.assertIn("target_commitish: ${{ github.sha }}", publish)
        self.assertEqual(1, release.count("softprops/action-gh-release"))
        self.assertNotIn("push:", release.split("permissions:", 1)[0])


if __name__ == "__main__":
    unittest.main()
