import pathlib
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "device-validation.yml"
RELEASE_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "main-prerelease.yml"


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

    def test_tag_reuses_main_checks_and_still_runs_release_acceptance(self) -> None:
        release = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("uses: ./.github/workflows/verify.yml", release)
        for workflow in ("verify.yml", "device-validation.yml", "dependency-submission.yml"):
            self.assertIn(workflow, release.split("  release-acceptance:", 1)[0])
        self.assertIn("head_sha=${GITHUB_SHA}&event=push&branch=main", release)
        self.assertIn("completed/success", release)
        self.assertIn("release-acceptance-only: true", release)
        self.assertIn("if: ${{ !inputs.release-acceptance-only }}", self.workflow)
        self.assertIn("if: inputs.release-acceptance-only ||", self.workflow)

    def test_tag_publishes_only_after_acceptance_and_parallel_build(self) -> None:
        release = RELEASE_WORKFLOW.read_text(encoding="utf-8")
        build = release.split("  build-release:", 1)[1].split("\n  publish:", 1)[0]
        self.assertIn("needs: preflight", build)
        self.assertIn(":app:assembleRelease", build)
        self.assertIn("needs: [preflight, release-acceptance, build-release]", release)
        self.assertEqual(1, release.count("softprops/action-gh-release"))
        self.assertIn("softprops/action-gh-release", release.split("\n  publish:", 1)[1])


if __name__ == "__main__":
    unittest.main()
