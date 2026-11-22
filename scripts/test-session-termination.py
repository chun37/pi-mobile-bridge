#!/usr/bin/env python3
"""Opt-in live test: create two Pi tabs, close only owned panes, never prompt a model."""
import argparse
import json
import os
import queue
import socket
import subprocess
import threading
import time


def rpc(path, method, params):
    with socket.socket(socket.AF_UNIX) as sock:
        sock.settimeout(30)
        sock.connect(path)
        sock.sendall((json.dumps({"id": "termination-test", "method": method, "params": params}) + "\n").encode())
        with sock.makefile() as stream:
            result = json.loads(stream.readline())
        if "error" in result:
            raise RuntimeError(result["error"])
        return result["result"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay", required=True)
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--allow-create-close", action="store_true", required=True)
    args = parser.parse_args()
    assert os.environ.get("HERDR_ENV") == "1"
    herdr = os.environ["HERDR_SOCKET_PATH"]
    proc = subprocess.Popen([args.relay, "relay"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    responses = queue.Queue()
    def read_responses():
        for line in proc.stdout:
            responses.put(json.loads(line))

    threading.Thread(target=read_responses, daemon=True).start()
    seq = 0

    def request(method, params=None):
        nonlocal seq
        seq += 1
        request_id = str(seq)
        proc.stdin.write(json.dumps({"id": request_id, "method": method, "params": params or {}}) + "\n")
        proc.stdin.flush()
        deadline = time.monotonic() + 80
        while True:
            response = responses.get(timeout=max(.1, deadline - time.monotonic()))
            if response.get("id") == request_id:
                if "error" in response:
                    raise RuntimeError(response["error"])
                return response["result"]

    def snapshot():
        return rpc(herdr, "session.snapshot", {})["snapshot"]

    owned = []
    proofs = []
    try:
        assert "sessionTerminate" in request("hello", {"protocol": 1})["capabilities"]
        for split in [True, False]:
            import uuid
            created = request("session.create", {"workspaceId": args.workspace, "requestId": str(uuid.uuid4())})
            owned.append(created["sessionKey"])
            assert created["started"], created
            pane, tab = created["sessionKey"], created["tabId"]
            deadline = time.monotonic() + 25
            while True:
                sessions = request("sessions.list")["sessions"]
                current = next((s for s in sessions if s["sessionKey"] == pane and s["bridge"]), None)
                if current:
                    break
                assert time.monotonic() < deadline
                time.sleep(.2)
            # Find only this new Pi's bridge info, retaining no conversation text.
            info = None
            import glob
            for path in glob.glob(os.path.expanduser("~/.pi/agent/bridge/*.sock")):
                try:
                    candidate = rpc(path, "info", {})
                    if candidate.get("paneId") == pane:
                        info = candidate
                        break
                except (OSError, RuntimeError):
                    pass
            assert info and info["sessionId"] == current["sessionId"]
            # Pi may not create a file until the first actual message.
            # Do not prompt a model just to make this empty test session persistent.
            history_before = os.path.isfile(info["sessionFile"])
            sibling = None
            if split:
                result = rpc(herdr, "pane.split", {"target_pane_id": pane, "direction": "right", "focus": False, "cwd": "/tmp"})
                sibling = result["pane"]["pane_id"]
                owned.append(sibling)
                assert result["pane"]["tab_id"] == tab, "test split escaped owned tab"
            import sys
            before_close = next((p for p in snapshot()["panes"] if p["pane_id"] == pane), {})
            print(json.dumps({"target": {k: current.get(k) for k in ["sessionKey", "sessionId", "instanceId", "tabId"]},
                              "livePane": {k: before_close.get(k) for k in ["pane_id", "tab_id", "agent", "agent_status"]}}), file=sys.stderr)
            result = request("session.terminate", {
                "sessionKey": pane, "expectedSessionId": current["sessionId"],
                "expectedInstanceId": current["instanceId"], "expectedTabId": tab,
            })
            assert result["closed"]
            after = snapshot()
            assert all(p["pane_id"] != pane for p in after["panes"])
            assert any(t["tab_id"] == tab for t in after["tabs"]) == split
            if split:
                assert any(p["pane_id"] == sibling for p in after["panes"])
            assert not history_before or os.path.isfile(info["sessionFile"]), "saved history was removed"
            deadline = time.monotonic() + 10
            while os.path.exists(f'/proc/{info["pid"]}') and time.monotonic() < deadline:
                time.sleep(.1)
            assert not os.path.exists(f'/proc/{info["pid"]}'), "Pi survived pane close"
            proofs.append({"paneId": pane, "tabId": tab, "siblingPreserved": bool(sibling), "tabRemoved": not split,
                           "piExited": True, "historyExistedBefore": history_before})
            if sibling:
                rpc(herdr, "pane.close", {"pane_id": sibling})
        print(json.dumps({"passed": proofs}, ensure_ascii=False))
    finally:
        # Never touch pre-existing panes, even if a test failed midway.
        try:
            live = {p["pane_id"] for p in snapshot()["panes"]}
            for pane in owned:
                if pane in live:
                    rpc(herdr, "pane.close", {"pane_id": pane})
        finally:
            proc.terminate()
            proc.wait(timeout=10)


if __name__ == "__main__":
    main()
