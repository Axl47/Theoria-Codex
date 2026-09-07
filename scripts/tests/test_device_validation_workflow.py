import pathlib
import unittest


REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "device-validation.yml"


class DeviceValidationWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")

    def test_connected_lane_executes_and_retains_both_instrumentation_owners(self) -> None:
        self.assertIn("script: scripts/verify_debug_device.sh all", self.workflow)
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
        self.assertIn("name: R8 release acceptance (API 35)", self.workflow)
        self.assertIn("name: release-acceptance-api-35", self.workflow)
        self.assertNotIn("name: release-acceptance-api-37", self.workflow)


if __name__ == "__main__":
    unittest.main()
