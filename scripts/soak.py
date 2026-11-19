#!/usr/bin/env python3
"""Read-only physical-device foreground-service/PC relay endurance observation."""
import argparse
import datetime as dt
import json
import os
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / 'evidence'
SAMPLES = EVIDENCE / 'soak-samples.jsonl'
START = EVIDENCE / 'soak-start.json'
RESULT = EVIDENCE / 'soak-result.json'


def command(args, timeout=15):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return result.returncode, result.stdout


def sample(serial):
    now = dt.datetime.now(dt.timezone.utc).isoformat()
    try:
        status, pid = command(['adb', '-s', serial, 'shell', 'pidof', 'com.chun.pibridge'])
        available = status == 0 and bool(pid.strip())
        service_status, service = command(['adb', '-s', serial, 'shell', 'dumpsys', 'activity', 'services', 'com.chun.pibridge'])
        _, processes = command(['ps', '-u', str(os.getuid()), '-o', 'comm=,args='])
        relays = [s for s in processes.splitlines() if s.split(None, 1)[0] == 'pibridge' and ' relay' in s]
        return {'time': now, 'deviceAvailable': available,
                'pid': pid.strip() if available else None,
                'foreground': service_status == 0 and 'isForeground=true' in service,
                'remoteMessaging': bool(re.search(r'\b(?:types|foregroundServiceType)=0x0*200\b', service)),
                'relayCount': len(relays)}
    except (subprocess.TimeoutExpired, OSError) as error:
        return {'time': now, 'error': type(error).__name__}


def finalize():
    start = json.loads(START.read_text())
    samples = [json.loads(s) for s in SAMPLES.read_text().splitlines()]
    timestamps = [dt.datetime.fromisoformat(s['time']) for s in samples]
    started = dt.datetime.fromisoformat(start['startedAt'])
    observed = (timestamps[-1] - started).total_seconds()
    gaps = [(b-a).total_seconds() for a,b in zip([started]+timestamps[:-1],timestamps)]
    maximum_gap = max(gaps)
    coverage = len(samples)>1 and maximum_gap <= start['intervalSeconds'] * 2
    # Missing USB evidence is not silently treated as a successful service observation.
    failures = [s for s in samples if not (s.get('deviceAvailable') and s.get('foreground') and s.get('remoteMessaging') and s.get('relayCount', 0) > 0)]
    pids = sorted({s['pid'] for s in samples if s.get('pid')})
    result = {'startedAt': start['startedAt'], 'finishedAt': samples[-1]['time'],
              'requestedSeconds': start['durationSeconds'], 'observedSeconds': observed,
              'samples': len(samples), 'failedSamples': len(failures), 'pids': pids,
              'maximumGapSeconds': maximum_gap, 'coveragePassed': coverage,
              'passed': observed >= start['durationSeconds'] and not failures and coverage,
              'scope': 'Background service and SSH relay over Tailscale; cellular E2E verified separately. Not a claim of a human spending a day outside.'}
    RESULT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['passed'] else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', help='ADB serial; required unless --finalize is used')
    parser.add_argument('--duration', type=int, default=86400)
    parser.add_argument('--interval', type=int, default=60)
    parser.add_argument('--once', action='store_true')
    parser.add_argument('--finalize', action='store_true')
    args = parser.parse_args()
    if args.duration<=0 or args.interval<=0:
        parser.error('duration and interval must be positive')
    if args.finalize:
        return finalize()
    if not args.serial:
        parser.error('--serial is required for device observations')
    if args.once:
        print(json.dumps(sample(args.serial), ensure_ascii=False))
        return 0
    if START.exists() or SAMPLES.exists():
        raise SystemExit('Existing soak evidence found; refusing to overwrite it')
    started = dt.datetime.now(dt.timezone.utc)
    START.write_text(json.dumps({'startedAt': started.isoformat(), 'durationSeconds': args.duration, 'intervalSeconds': args.interval, 'serial': args.serial}, indent=2) + '\n')
    deadline = time.monotonic() + args.duration
    while True:
        value = sample(args.serial)
        with SAMPLES.open('a') as stream:
            stream.write(json.dumps(value, ensure_ascii=False) + '\n')
        left = deadline - time.monotonic()
        if left <= 0:
            break
        time.sleep(min(args.interval, left))
    return finalize()


if __name__ == '__main__':
    raise SystemExit(main())
