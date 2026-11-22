#!/usr/bin/env python3
"""Opt-in regression test. Create and close only fresh Pi tabs; never prompt a model."""
import argparse
import json
import os
import queue
import subprocess
import threading
import time
import uuid

from importlib.util import spec_from_file_location, module_from_spec
from pathlib import Path

# Reuse the bounded, single-request Herdr socket helper; do not run its main().
spec = spec_from_file_location("termination_test", Path(__file__).with_name("test-session-termination.py"))
helper = module_from_spec(spec)
spec.loader.exec_module(helper)


def run(relay, workspace, expected, require_fixed):
    herdr = os.environ["HERDR_SOCKET_PATH"]
    initial = helper.rpc(herdr, "session.snapshot", {})["snapshot"]
    existing = {p["pane_id"] for p in initial["panes"]}
    process = subprocess.Popen([relay, "relay"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    responses = queue.Queue()

    def read():
        for line in process.stdout:
            responses.put(json.loads(line))

    threading.Thread(target=read, daemon=True).start()
    sequence = 0
    owned = None

    def request(method, params=None):
        nonlocal sequence
        sequence += 1
        key = str(sequence)
        process.stdin.write(json.dumps({"id": key, "method": method, "params": params or {}}) + "\n")
        process.stdin.flush()
        deadline = time.monotonic() + 80
        while True:
            response = responses.get(timeout=max(.1, deadline - time.monotonic()))
            if response.get("id") == key:
                if "error" in response:
                    raise RuntimeError(response["error"])
                return response["result"]

    try:
        hello = request("hello", {"protocol": 1})
        created = request("session.create", {"workspaceId": workspace, "requestId": str(uuid.uuid4())})
        pane = created["sessionKey"]
        assert pane not in existing, "refuse to own a pre-existing pane"
        owned = pane
        assert created["started"], created
        deadline = time.monotonic() + 25
        while True:
            sessions = request("sessions.list")["sessions"]
            session = next((s for s in sessions if s["sessionKey"] == pane and s["bridge"]), None)
            if session:
                break
            assert time.monotonic() < deadline
            time.sleep(.2)
        if require_fixed:
            assert session["cwd"] == expected, session["cwd"]
            assert created["cwd"] == expected, created
        proof = {"relayVersion": hello["relayVersion"], "sessionKey": pane, "tabId": created["tabId"],
                 "workspaceId": workspace, "cwd": session["cwd"], "expectedCwd": expected,
                 "matchesExpected": session["cwd"] == expected,
                 "focusedPaneBefore": initial.get("focused_pane_id")}
        request("session.terminate", {"sessionKey": pane, "expectedSessionId": session["sessionId"],
                                     "expectedInstanceId": session["instanceId"], "expectedTabId": created["tabId"]})
        final = helper.rpc(herdr, "session.snapshot", {})["snapshot"]
        assert all(p["pane_id"] != pane for p in final["panes"])
        proof["focusedPaneAfter"] = final.get("focused_pane_id")
        proof["testPaneClosed"] = True
        return proof
    finally:
        try:
            if owned:
                live = helper.rpc(herdr, "session.snapshot", {})["snapshot"]["panes"]
                if any(p["pane_id"] == owned for p in live):
                    helper.rpc(herdr, "pane.close", {"pane_id": owned})
        finally:
            process.terminate()
            process.wait(timeout=10)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-relay", required=True)
    parser.add_argument("--after-relay", required=True)
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--expected-cwd", required=True)
    parser.add_argument("--allow-create-close", action="store_true", required=True)
    args = parser.parse_args()
    assert os.environ.get("HERDR_ENV") == "1"
    before = run(args.before_relay, args.workspace, args.expected_cwd, False)
    print(json.dumps({"before": before}, ensure_ascii=False), flush=True)
    after = run(args.after_relay, args.workspace, args.expected_cwd, True)
    print(json.dumps({"after": after}, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
