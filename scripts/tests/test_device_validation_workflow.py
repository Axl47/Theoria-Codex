import pathlib
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "device-validation.yml"
RELEASE_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "release.yml"


class DeviceValidationWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_connected_lane_executes_and_retains_both_instrumentation_owners(self) -> None:
        self.assertIn("script: bash scripts/verify_ci_emulator.sh debug", self.workflow)
        wrapper = (REPOSITORY_ROOT / "scripts/verify_ci_emulator.sh").read_text(encoding="utf-8")
        self.assertIn("scripts/verify_debug_device.sh all", wrapper)
        lane = (REPOSITORY_ROOT / "scripts/verify_debug_device.sh").read_text(encoding="utf-8")
        self.assertIn(":app:connectedDebugAndroidTest", lane)
        self.assertIn(":core-data-android:connectedDebugAndroidTest", lane)
        self.assertIn(
            "core-data-android/build/outputs/androidTest-results/connected/",
            self.workflow,
        )
        self.assertIn(
            "core-data-android/build/reports/androidTests/connected/",
            self.workflow,
        )

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

    def test_release_reuses_verified_code_but_always_runs_device_acceptance(self) -> None:
        release = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        verify = release.split("  verify:", 1)[1].split("\n  device-validation:", 1)[0]
        device = release.split("  device-validation:", 1)[1].split("\n  build-release:", 1)[0]
        self.assertIn("if: ${{ needs.gate.outputs.verified != 'true' }}", verify)
        self.assertIn("uses: ./.github/workflows/verify.yml", verify)
        self.assertIn("uses: ./.github/workflows/device-validation.yml", device)
        self.assertNotIn("if:", device)

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
