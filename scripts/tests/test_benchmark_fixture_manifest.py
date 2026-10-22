from __future__ import annotations

from contextlib import redirect_stdout
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "verify_benchmark_fixture_apk", Path(__file__).resolve().parents[1] / "verify_benchmark_fixture_apk.py",
)
assert SPEC is not None and SPEC.loader is not None
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)
ANDROID = "{http://schemas.android.com/apk/res/android}"
ET.register_namespace("android", ANDROID[1:-1])

MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android">
  <application>
    <activity android:name="com.theoriacodex.app.MainActivity" android:exported="true">
      <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
      </intent-filter>
    </activity>
    <activity android:name="com.theoriacodex.app.benchmark.BenchmarkFixtureActivity"
              android:exported="true" android:process=":benchmarkFixture">
      <intent-filter><action android:name="com.theoriacodex.action.BENCHMARK_FIXTURE"/></intent-filter>
    </activity>
    <receiver android:name="com.theoriacodex.app.benchmark.BenchmarkDurationStartReceiver"
              android:exported="true" android:process=":benchmarkFixture">
      <intent-filter><action android:name="com.theoriacodex.action.BENCHMARK_DURATION_START"/></intent-filter>
    </receiver>
    <receiver android:name="Neighbor" android:exported="true" android:process=":benchmarkFixture"/>
  </application>
</manifest>"""


class BenchmarkFixtureManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.apk = self.directory / "isolated fixture.apk"
        self.video = self.directory / "loop.mp4"
        self.analyzer = self.directory / "fake-apkanalyzer"
        self.analyzer.touch()
        self.video.write_bytes(b"exact packaged fixture bytes")
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("res/raw/loop.mp4", self.video.read_bytes())
        self.root = ET.fromstring(MANIFEST)
        self.package = VERIFIER.EXPECTED_APPLICATION_ID
        self.dex = "\n".join((VERIFIER.EXPECTED_ACTIVITY, VERIFIER.EXPECTED_DURATION_RECEIVER))

    def component(self, name: str) -> ET.Element:
        return next(node for node in self.root.iter() if node.get(ANDROID + "name") == name)

    def verify(self, manifest: str | None = None) -> None:
        responses = {
            ("manifest", "application-id", str(self.apk)): self.package,
            ("manifest", "print", str(self.apk)): manifest if manifest is not None else ET.tostring(self.root, encoding="unicode"),
            ("dex", "packages", str(self.apk)): self.dex,
            ("resources", "value", "--config", "default", "--type", "raw", "--name", "benchmark_loop", str(self.apk)): "res/raw/loop.mp4",
        }
        with patch.object(VERIFIER, "run", side_effect=lambda _tool, *args: responses[args]), \
                patch.object(VERIFIER.sys, "argv", ["verify", str(self.apk), str(self.video), str(self.analyzer)]), \
                redirect_stdout(io.StringIO()):
            VERIFIER.main()

    def test_valid_components_accept_long_attributes_and_attribute_reordering(self) -> None:
        self.verify()
        for name in (VERIFIER.EXPECTED_ACTIVITY, VERIFIER.EXPECTED_DURATION_RECEIVER):
            component = self.component(name)
            component.attrib = {
                ANDROID + "name": name, ANDROID + "label": "long label " * 100,
                ANDROID + "process": VERIFIER.EXPECTED_PROCESS, ANDROID + "exported": "true",
            }
        self.verify()
        for name in (VERIFIER.EXPECTED_ACTIVITY, VERIFIER.EXPECTED_DURATION_RECEIVER):
            component = self.component(name)
            component.attrib = dict(reversed(list(component.attrib.items())))
        self.verify()

    def test_exported_flags_cannot_be_borrowed_from_neighboring_components(self) -> None:
        for name in (VERIFIER.EXPECTED_ACTIVITY, VERIFIER.EXPECTED_DURATION_RECEIVER):
            for value in (None, "false"):
                with self.subTest(component=name, exported=value):
                    self.root = ET.fromstring(MANIFEST)
                    component = self.component(name)
                    if value is None:
                        del component.attrib[ANDROID + "exported"]
                    else:
                        component.set(ANDROID + "exported", value)
                    with self.assertRaisesRegex(SystemExit, "not exported"):
                        self.verify()

    def test_each_fixture_component_must_run_in_its_isolated_process(self) -> None:
        for name in (VERIFIER.EXPECTED_ACTIVITY, VERIFIER.EXPECTED_DURATION_RECEIVER):
            with self.subTest(component=name):
                self.root = ET.fromstring(MANIFEST)
                self.component(name).set(ANDROID + "process", ":unrelated")
                with self.assertRaisesRegex(SystemExit, "outside fixture process"):
                    self.verify()

    def test_actions_must_belong_to_the_expected_component(self) -> None:
        for name in (VERIFIER.EXPECTED_ACTIVITY, VERIFIER.EXPECTED_DURATION_RECEIVER):
            with self.subTest(component=name):
                self.root = ET.fromstring(MANIFEST)
                component = self.component(name)
                intent_filter = component.find("intent-filter")
                component.remove(intent_filter)
                self.component("Neighbor").append(intent_filter)
                with self.assertRaisesRegex(SystemExit, "missing action"):
                    self.verify()

    def test_launcher_action_and_category_must_share_the_launcher_filter(self) -> None:
        launcher = self.component(VERIFIER.EXPECTED_LAUNCHER)
        intent_filter = launcher.find("intent-filter")
        category = intent_filter.find("category")
        intent_filter.remove(category)
        ET.SubElement(launcher, "intent-filter").append(category)
        with self.assertRaisesRegex(SystemExit, "launcher filter"):
            self.verify()

    def test_duplicate_component_identity_is_rejected(self) -> None:
        application = self.root.find("application")
        application.append(ET.fromstring(ET.tostring(self.component(VERIFIER.EXPECTED_ACTIVITY))))
        with self.assertRaisesRegex(SystemExit, "exactly one"):
            self.verify()

    def test_malformed_or_missing_application_is_rejected(self) -> None:
        for manifest in ("<manifest", "<manifest/>"):
            with self.subTest(manifest=manifest), self.assertRaises(SystemExit):
                self.verify(manifest)

    def test_forbidden_surfaces_still_fail_closed(self) -> None:
        for name in VERIFIER.FORBIDDEN_MANIFEST_SURFACES:
            with self.subTest(surface=name):
                self.root = ET.fromstring(MANIFEST)
                ET.SubElement(self.root, "uses-permission", {ANDROID + "name": name})
                with self.assertRaisesRegex(SystemExit, "forbidden surface"):
                    self.verify()

    def test_valid_manifest_does_not_bypass_package_dex_or_exact_bytes(self) -> None:
        self.package = "com.theoriacodex"
        with self.assertRaisesRegex(SystemExit, "unexpected application ID"):
            self.verify()
        self.package = VERIFIER.EXPECTED_APPLICATION_ID
        self.dex = VERIFIER.EXPECTED_ACTIVITY
        with self.assertRaisesRegex(SystemExit, "removed from dex"):
            self.verify()
        self.dex += "\n" + VERIFIER.EXPECTED_DURATION_RECEIVER
        self.video.write_bytes(b"different source bytes")
        with self.assertRaisesRegex(SystemExit, "video bytes differ"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
