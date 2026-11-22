package main

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
)

const testCreationID = "01234567-89ab-4cde-8fab-0123456789ab"

func creationFixture(t *testing.T, startFails, tabFails bool, busyAttempts ...int) (*relay, *int, *int, *sync.Mutex) {
	t.Helper()
	var mu sync.Mutex
	tabs, starts := 0, 0
	workspaceRoot := t.TempDir()
	socket := fakeSocket(t, "herdr.sock", func(q obj) obj {
		mu.Lock()
		defer mu.Unlock()
		p := object(q["params"])
		switch q["method"] {
		case "session.snapshot":
			return obj{"result": obj{"snapshot": obj{
				"workspaces": []any{obj{"workspace_id": "w1", "label": "work"}},
				"tabs":       []any{}, "panes": []any{},
			}}}
		case "tab.create":
			tabs++
			if p["workspace_id"] != "w1" || p["focus"] != false {
				t.Error("wrong workspace or stolen focus")
			}
			if p["env"] != nil || p["cwd"] != workspaceRoot {
				t.Error("must use server workspace directory, not client or desktop focus")
			}
			if tabFails {
				return obj{"error": obj{"code": "unknown", "message": "transport failed after creating tab"}}
			}
			return obj{"result": obj{"tab": obj{"tab_id": "w1:t9"}, "root_pane": obj{"pane_id": "w1:p9"}}}
		case "agent.start":
			starts++
			if p["kind"] != "pi" || p["pane_id"] != "w1:p9" || len(array(p["args"])) != 0 {
				t.Error("must start only Pi in the newly created pane")
			}
			if len(str(p["name"])) > 32 {
				t.Error("invalid agent name")
			}
			if len(busyAttempts) > 0 && starts <= busyAttempts[0] {
				return obj{"error": obj{"code": "agent_pane_busy", "message": "agent target pane w1:p9 is not an available shell"}}
			}
			if startFails {
				return obj{"error": obj{"code": "agent_not_ready", "message": "startup blocked"}}
			}
			return obj{"result": obj{"ready": true}}
		default:
			return obj{"error": obj{"code": "unsupported", "message": "unsupported"}}
		}
	})
	data, _ := json.Marshal(obj{"version": 3, "workspaces": []any{obj{"id": "w1", "identity_cwd": workspaceRoot}}})
	if err := os.WriteFile(filepath.Join(filepath.Dir(socket), "session.json"), data, 0600); err != nil {
		t.Fatal(err)
	}
	r := &relay{ctx: context.Background(), herdr: socket, dir: t.TempDir(), out: &strings.Builder{},
		bridges: map[string]bridge{}, sessions: map[string]obj{}, subscriptions: nil,
		generation: map[string]uint64{}}
	return r, &tabs, &starts, &mu
}

func TestCreateNewTabAndReplayAcrossRelayConnections(t *testing.T) {
	r, tabs, starts, mu := creationFixture(t, false, false)
	params := obj{"workspaceId": "w1", "requestId": testCreationID, "command": "unsafe", "paneId": "existing", "cwd": "/client-supplied/should-not-be-used"}
	first, err := r.createSession(params)
	if err != nil {
		t.Fatal(err)
	}
	if first["tabId"] != "w1:t9" || first["sessionKey"] != "w1:p9" || first["started"] != true {
		t.Fatal(first)
	}
	other := &relay{ctx: context.Background(), herdr: r.herdr, dir: r.dir}
	replay, err := other.createSession(params)
	if err != nil || replay["sessionKey"] != "w1:p9" {
		t.Fatal(replay, err)
	}
	mu.Lock()
	defer mu.Unlock()
	if *tabs != 1 || *starts != 1 {
		t.Fatalf("tabs=%d starts=%d", *tabs, *starts)
	}
	info, err := os.Stat(filepath.Join(r.dir, "creations", testCreationID+".json"))
	if err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("private creation record", err)
	}
}

func TestCreationRejectsInvalidOrUnknownWorkspace(t *testing.T) {
	r, tabs, _, mu := creationFixture(t, false, false)
	for _, p := range []obj{
		{"workspaceId": "w1", "requestId": "../../escape"},
		{"workspaceId": "missing", "requestId": testCreationID},
		{"requestId": testCreationID},
	} {
		if _, err := r.createSession(p); err == nil {
			t.Fatal("accepted invalid request")
		}
	}
	mu.Lock()
	defer mu.Unlock()
	if *tabs != 0 {
		t.Fatal("created unexpected tab")
	}
}

func TestFailedStartupKeepsNewTabAndDoesNotRestartOnReplay(t *testing.T) {
	r, tabs, starts, mu := creationFixture(t, true, false)
	params := obj{"workspaceId": "w1", "requestId": testCreationID}
	result, err := r.createSession(params)
	if err != nil || result["started"] != false || str(result["startupError"]) == "" || result["tabId"] != "w1:t9" {
		t.Fatal(result, err)
	}
	if _, err := r.createSession(params); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if *tabs != 1 || *starts != 1 {
		t.Fatal("repeated mutation")
	}
}

func TestUnknownCreationOutcomeIsNeverReplayed(t *testing.T) {
	r, tabs, starts, mu := creationFixture(t, false, true)
	p := obj{"workspaceId": "w1", "requestId": testCreationID}
	if _, err := r.createSession(p); err == nil {
		t.Fatal("accepted unknown outcome")
	}
	if _, err := r.createSession(p); err == nil || !strings.Contains(err.Error(), "creation_pending") {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if *tabs != 1 || *starts != 0 {
		t.Fatal("repeated uncertain mutation")
	}
}

func TestCreationIDCannotMoveToAnotherWorkspace(t *testing.T) {
	r, _, _, _ := creationFixture(t, false, false)
	if _, err := r.createSession(obj{"workspaceId": "w1", "requestId": testCreationID}); err != nil {
		t.Fatal(err)
	}
	if _, err := r.createSession(obj{"workspaceId": "w2", "requestId": testCreationID}); err == nil {
		t.Fatal("accepted reused ID for another workspace")
	}
	if _, err := recordedCreation([]byte("partial"), "w1"); err == nil {
		t.Fatal(errors.New("accepted partial reservation"))
	}
}

func TestConcurrentCreationOnlyCreatesOneTab(t *testing.T) {
	r, tabs, starts, mu := creationFixture(t, false, false)
	other := &relay{ctx: context.Background(), herdr: r.herdr, dir: r.dir, out: &strings.Builder{},
		bridges: map[string]bridge{}, sessions: map[string]obj{}, generation: map[string]uint64{}}
	var workers sync.WaitGroup
	for _, current := range []*relay{r, other} {
		workers.Add(1)
		go func(current *relay) {
			defer workers.Done()
			_, err := current.createSession(obj{"workspaceId": "w1", "requestId": testCreationID})
			if err != nil && !strings.Contains(err.Error(), "creation_pending") {
				t.Error(err)
			}
		}(current)
	}
	workers.Wait()
	mu.Lock()
	defer mu.Unlock()
	if *tabs != 1 || *starts != 1 {
		t.Fatal("duplicate creation")
	}
}
