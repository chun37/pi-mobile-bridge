import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('soak', Path(__file__).parents[1] / 'scripts/soak.py')
soak = importlib.util.module_from_spec(spec)
spec.loader.exec_module(soak)


class SoakTest(unittest.TestCase):
    def test_real_dumpsys_field_and_relay(self):
        with patch.object(soak, 'command', side_effect=[(0, '42'), (0, 'isForeground=true types=0x00000200'), (0, 'pibridge /home/u/.local/bin/pibridge relay\n')]):
            sample = soak.sample('test')
        self.assertTrue(sample['foreground'])
        self.assertTrue(sample['remoteMessaging'])
        self.assertEqual(sample['relayCount'], 1)

    def test_missing_usb_is_not_success(self):
        with patch.object(soak, 'command', side_effect=[(1, ''), (1, ''), (0, '')]):
            sample = soak.sample('test')
        self.assertFalse(sample['deviceAvailable'])
        self.assertFalse(sample['foreground'])

    def test_duration_and_observations_are_both_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.multiple(soak, START=root/'start.json', SAMPLES=root/'samples.jsonl', RESULT=root/'result.json'):
                soak.START.write_text(json.dumps({'startedAt': '2026-11-19T00:00:00+00:00', 'durationSeconds': 120, 'intervalSeconds': 60}))
                good = {'time': '2026-11-19T00:02:01+00:00', 'deviceAvailable': True, 'foreground': True, 'remoteMessaging': True, 'relayCount': 1, 'pid': '42'}
                soak.SAMPLES.write_text('\n'.join(json.dumps({**good,'time':t})for t in ['2026-11-19T00:00:00+00:00','2026-11-19T00:01:00+00:00',good['time']])+'\n')
                self.assertEqual(soak.finalize(), 0)
                soak.SAMPLES.write_text('\n'.join(json.dumps({**good,'deviceAvailable':False,'time':t})for t in ['2026-11-19T00:00:00+00:00','2026-11-19T00:01:00+00:00',good['time']])+'\n')
                self.assertEqual(soak.finalize(), 1)


if __name__ == '__main__':
    unittest.main()
