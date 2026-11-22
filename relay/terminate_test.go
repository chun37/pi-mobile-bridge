package main

import (
	"context"
	"net"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

type terminationFixture struct {
	mu                        sync.Mutex
	pane, sibling             bool
	sid, instance, tab, agent string
	closes                    int
	failClose                 bool
}

func terminationRelay(t *testing.T) (*relay, *terminationFixture) {
	t.Helper()
	f := &terminationFixture{pane: true, sibling: true, sid: "s1", instance: "i1", tab: "w1:t1", agent: "pi"}
	bs := fakeSocket(t, "bridge.sock", func(q obj) obj {
		f.mu.Lock()
		defer f.mu.Unlock()
		return obj{"result": obj{"paneId": "w1:p1", "sessionId": f.sid, "instanceId": f.instance, "idle": true}}
	})
	hs := fakeSocket(t, "herdr.sock", func(q obj) obj {
		f.mu.Lock()
		defer f.mu.Unlock()
		switch q["method"] {
		case "session.snapshot":
			panes := []any{}
			if f.pane {
				panes = append(panes, obj{"pane_id": "w1:p1", "tab_id": f.tab, "workspace_id": "w1", "agent": f.agent})
			}
			if f.sibling {
				panes = append(panes, obj{"pane_id": "w1:p2", "tab_id": "w1:t1", "workspace_id": "w1", "agent": "shell"})
			}
			return obj{"result": obj{"snapshot": obj{"panes": panes, "tabs": []any{obj{"tab_id": "w1:t1"}}, "workspaces": []any{obj{"workspace_id": "w1"}}}}}
		case "pane.close":
			if str(object(q["params"])["pane_id"]) != "w1:p1" {
				t.Error("wrong pane")
			}
			f.closes++
			if f.failClose {
				return obj{"error": obj{"code": "failed", "message": "outcome unknown"}}
			}
			f.pane = false
			return obj{"result": obj{"closed": true}}
		default:
			t.Errorf("forbidden Herdr method: %v", q["method"])
			return obj{"error": obj{"code": "forbidden"}}
		}
	})
	r := &relay{ctx: context.Background(), herdr: hs, dir: filepath.Dir(bs), out: &strings.Builder{}, bridges: map[string]bridge{}, sessions: map[string]obj{}, subscriptions: map[string]net.Conn{}, generation: map[string]uint64{}}
	r.discover()
	return r, f
}
func terminationParams() obj {
	return obj{"sessionKey": "w1:p1", "expectedSessionId": "s1", "expectedInstanceId": "i1", "expectedTabId": "w1:t1"}
}

func TestTerminateClosesOnlyConfirmedPaneAndPreservesSibling(t *testing.T) {
	r, f := terminationRelay(t)
	result, err := r.handle(obj{"method": "session.terminate", "params": terminationParams()})
	if err != nil || object(result)["closed"] != true {
		t.Fatal(result, err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.pane || !f.sibling || f.closes != 1 {
		t.Fatal(f)
	}
}
func TestTerminateRejectsMissingOrChangedIdentity(t *testing.T) {
	for _, field := range []string{"sessionKey", "expectedSessionId", "expectedInstanceId", "expectedTabId"} {
		t.Run("missing-"+field, func(t *testing.T) {
			r, f := terminationRelay(t)
			p := terminationParams()
			delete(p, field)
			if _, err := r.terminateSession(p); err == nil {
				t.Fatal("accepted missing identity")
			}
			if f.closes != 0 {
				t.Fatal("mutated")
			}
		})
	}
	for _, field := range []string{"sid", "instance", "tab"} {
		t.Run("changed-"+field, func(t *testing.T) {
			r, f := terminationRelay(t)
			f.mu.Lock()
			switch field {
			case "sid":
				f.sid = "new"
			case "instance":
				f.instance = "new"
			case "tab":
				f.tab = "new"
			case "agent":
				f.agent = "shell"
			}
			f.mu.Unlock()
			if _, err := r.terminateSession(terminationParams()); err == nil {
				t.Fatal("accepted changed target")
			}
			if f.closes != 0 {
				t.Fatal("mutated")
			}
		})
	}
}
func TestTerminateUsesBridgeIdentityWhenHerdrDetectionIsUnknown(t *testing.T) {
	r, f := terminationRelay(t)
	f.mu.Lock()
	f.agent = ""
	f.mu.Unlock()
	if _, err := r.terminateSession(terminationParams()); err != nil {
		t.Fatal(err)
	}
	if f.closes != 1 {
		t.Fatal(f.closes)
	}
}
func TestTerminateWithoutLiveBridgeFailsClosed(t *testing.T) {
	r, f := terminationRelay(t)
	r.mu.Lock()
	delete(r.bridges, "w1:p1")
	r.mu.Unlock()
	if _, err := r.terminateSession(terminationParams()); err == nil {
		t.Fatal("accepted missing bridge")
	}
	if f.closes != 0 {
		t.Fatal(f.closes)
	}
}
func TestTerminateAlreadyClosedDoesNotCloseAgain(t *testing.T) {
	r, f := terminationRelay(t)
	for i := 0; i < 2; i++ {
		if _, err := r.terminateSession(terminationParams()); err != nil {
			t.Fatal(err)
		}
	}
	if f.closes != 1 {
		t.Fatal(f.closes)
	}
}
func TestTerminateConcurrentRequestsCloseOnlyOnce(t *testing.T) {
	r, f := terminationRelay(t)
	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := r.terminateSession(terminationParams()); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if f.closes != 1 {
		t.Fatal(f.closes)
	}
}
func TestTerminateUnknownOutcomeIsNotRetried(t *testing.T) {
	r, f := terminationRelay(t)
	f.mu.Lock()
	f.failClose = true
	f.mu.Unlock()
	if _, err := r.terminateSession(terminationParams()); err == nil || !strings.Contains(err.Error(), "unconfirmed") {
		t.Fatal(err)
	}
	if f.closes != 1 {
		t.Fatal(f.closes)
	}
}
func TestTerminateMalformedSnapshotIsNotReportedAsClosed(t *testing.T) {
	r, f := terminationRelay(t)
	r.herdr = fakeSocket(t, "malformed.sock", func(obj) obj { return obj{"result": obj{}} })
	if _, err := r.terminateSession(terminationParams()); err == nil {
		t.Fatal("malformed snapshot accepted")
	}
	if f.closes != 0 {
		t.Fatal(f.closes)
	}
}
func TestSessionCloseStillOnlyUnsubscribes(t *testing.T) {
	r, f := terminationRelay(t)
	if _, err := r.handle(obj{"method": "session.close", "params": obj{"sessionKey": "w1:p1"}}); err != nil {
		t.Fatal(err)
	}
	if f.closes != 0 || !f.pane {
		t.Fatal("subscription close terminated pane")
	}
}
