package main

import (
	"encoding/json"
	"os"
	"runtime"
	"strings"
	"sync"
	"testing"
)

func diagnosticRelay(t *testing.T, mutate func(obj, int), malformed bool) *relay {
	t.Helper()
	var mu sync.Mutex
	reads := 0
	bs := fakeSocket(t, "diagnostic-bridge.sock", func(q obj) obj {
		if q["method"] != "info" {
			t.Errorf("unexpected mutation/read: %v", q["method"])
		}
		mu.Lock()
		defer mu.Unlock()
		reads++
		info := obj{"paneId": "w1:p1", "sessionId": "s1", "instanceId": "i1", "bridgeVersion": "0.test", "pid": 1234,
			"model": "test-model", "thinkingLevel": "high", "capabilities": []any{"executionState"},
			"build":   obj{"revision": "loaded-commit", "modified": false, "token": "DO_NOT_LEAK"},
			"runtime": obj{"piVersion": "9.8.7-running", "nodeVersion": "v99.1.0", "platform": "linux", "arch": "arm64", "token": "DO_NOT_LEAK"},
			"token":   "DO_NOT_LEAK", "sessionFile": "/secret/path", "lastResponse": "DO_NOT_LEAK", "cwd": "/secret/cwd"}
		if mutate != nil {
			mutate(info, reads)
		}
		return obj{"result": info}
	})
	hs := fakeSocket(t, "diagnostic-herdr.sock", func(q obj) obj {
		if q["method"] != "session.snapshot" {
			t.Errorf("unexpected method: %v", q["method"])
		}
		if malformed {
			return obj{"result": obj{}}
		}
		return obj{"result": obj{"snapshot": obj{"version": "0.9.5-running", "protocol": 22, "panes": []any{obj{"pane_id": "w1:p1"}}}}}
	})
	return &relay{herdr: hs, bridges: map[string]bridge{"w1:p1": {socket: bs}}}
}
func debugParams() obj {
	return obj{"sessionKey": "w1:p1", "expectedSessionId": "s1", "expectedInstanceId": "i1"}
}
func TestDebugReportsRunningVersionsAndDoesNotLeakOtherInfo(t *testing.T) {
	r := diagnosticRelay(t, nil, false)
	value, err := r.handle(obj{"method": "session.debug", "params": debugParams()})
	if err != nil {
		t.Fatal(err)
	}
	result := object(value)
	if object(object(result["bridge"])["runtime"])["piVersion"] != "9.8.7-running" {
		t.Fatal(result)
	}
	if object(object(result["bridge"])["build"])["revision"] != "loaded-commit" {
		t.Fatal(result)
	}
	if object(result["herdr"])["version"] != "0.9.5-running" {
		t.Fatal(result)
	}
	if object(result["relay"])["pid"] != os.Getpid() || object(result["relay"])["goVersion"] != runtime.Version() {
		t.Fatal(result)
	}
	if object(result["session"])["thinkingLevel"] != "high" {
		t.Fatal(result)
	}
	b, _ := json.Marshal(result)
	for _, secret := range []string{"DO_NOT_LEAK", "/secret", "lastResponse", "sessionFile", "token"} {
		if strings.Contains(string(b), secret) {
			t.Fatalf("leaked %s: %s", secret, b)
		}
	}
}
func TestDebugOldExtensionDoesNotInventPiOrNodeVersions(t *testing.T) {
	r := diagnosticRelay(t, func(info obj, _ int) { delete(info, "runtime") }, false)
	result, err := r.sessionDebug(debugParams())
	if err != nil {
		t.Fatal(err)
	}
	if len(object(object(result["bridge"])["runtime"])) != 0 {
		t.Fatal(result)
	}
	if object(result["bridge"])["bridgeVersion"] != "0.test" {
		t.Fatal(result)
	}
}
func TestDebugRejectsMissingUnknownAndChangedTargets(t *testing.T) {
	for _, field := range []string{"sessionKey", "expectedSessionId", "expectedInstanceId"} {
		t.Run(field, func(t *testing.T) {
			r := diagnosticRelay(t, nil, false)
			p := debugParams()
			delete(p, field)
			if _, err := r.sessionDebug(p); err == nil {
				t.Fatal("missing identity accepted")
			}
		})
	}
	for _, field := range []string{"paneId", "sessionId", "instanceId"} {
		t.Run("changed-"+field, func(t *testing.T) {
			r := diagnosticRelay(t, func(info obj, _ int) { info[field] = "changed" }, false)
			if _, err := r.sessionDebug(debugParams()); err == nil {
				t.Fatal("changed identity accepted")
			}
		})
	}
	r := diagnosticRelay(t, nil, false)
	p := debugParams()
	p["sessionKey"] = "unknown"
	if _, err := r.sessionDebug(p); err == nil {
		t.Fatal("unknown pane accepted")
	}
}
func TestDebugRechecksAfterSnapshotAndRejectsMissingPane(t *testing.T) {
	r := diagnosticRelay(t, func(info obj, n int) {
		if n == 2 {
			info["instanceId"] = "reloaded"
		}
	}, false)
	if _, err := r.sessionDebug(debugParams()); err == nil {
		t.Fatal("reload during collection accepted")
	}
	r = diagnosticRelay(t, nil, true)
	if _, err := r.sessionDebug(debugParams()); err == nil {
		t.Fatal("invalid snapshot accepted")
	}
}
func TestHelloAdvertisesDebugWithoutReadingPackagesOnDisk(t *testing.T) {
	r := fixture(t)
	value, err := r.handle(obj{"method": "hello", "params": obj{"protocol": float64(1)}})
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, cap := range array(object(value)["capabilities"]) {
		if cap == "sessionDebug" {
			found = true
		}
	}
	if !found || object(object(value)["relayRuntime"])["version"] != version {
		t.Fatal(value)
	}
}
