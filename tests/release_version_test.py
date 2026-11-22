import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("sync_release", ROOT / "scripts/sync-release.py")
sync = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sync)


class ReleaseVersionTest(unittest.TestCase):
    def fixture(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / "release.json").write_text(json.dumps({"version": "0.4.0", "androidVersionCode": 4}))
        (root / "package.json").write_text(json.dumps({"version": "0.1.0", "name": "fixture"}))
        (root / "package-lock.json").write_text(json.dumps({"version": "0.1.0", "packages": {"": {"version": "0.1.0"}, "dependency": {"version": "9.0.0"}}}))
        return root

    def test_repository_mirrors_match(self):
        self.assertEqual([], sync.synchronize(ROOT))

    def test_write_uses_requested_release_without_incrementing(self):
        root = self.fixture()
        before = (root / "release.json").read_bytes()
        self.assertTrue(sync.synchronize(root, True))
        self.assertEqual([], sync.synchronize(root, True))
        self.assertEqual(before, (root / "release.json").read_bytes())
        self.assertEqual("9.0.0", json.loads((root / "package-lock.json").read_text())["packages"]["dependency"]["version"])

    def test_check_detects_drift_without_writing(self):
        root = self.fixture()
        sync.synchronize(root, True)
        path = root / "relay/version_generated.go"
        path.write_text(path.read_text().replace("0.4.0", "0.4.1"))
        before = path.read_bytes()
        self.assertEqual(["relay/version_generated.go"], sync.synchronize(root))
        self.assertEqual(before, path.read_bytes())

    def test_invalid_versions_fail(self):
        for version in ["0.4", "v0.4.0", "0.4.0\nmalicious", "00.4.0", None]:
            root = self.fixture()
            (root / "release.json").write_text(json.dumps({"version": version, "androidVersionCode": 4}))
            with self.assertRaises(ValueError):
                sync.synchronize(root, True)

    def test_invalid_android_codes_fail(self):
        for code in [0, -1, True, 2.1, 2100000001]:
            root = self.fixture()
            (root / "release.json").write_text(json.dumps({"version": "0.4.0", "androidVersionCode": code}))
            with self.assertRaises(ValueError):
                sync.synchronize(root, True)

    def test_android_reads_canonical_release_and_does_not_auto_increment(self):
        gradle = (ROOT / "android/app/build.gradle.kts").read_text()
        self.assertIn('rootProject.file("../release.json")', gradle)
        self.assertIn('versionName = bridgeRelease["version"] as String', gradle)
        self.assertIn('versionCode = (bridgeRelease["androidVersionCode"] as Number).toInt()', gradle)
        self.assertIn('BRIDGE_REVISION', gradle)


if __name__ == "__main__":
    unittest.main()
