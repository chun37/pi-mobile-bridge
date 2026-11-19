package main

import (
	"context"
	"path/filepath"
	"strings"
	"testing"
)

func TestUnavailableHerdrDoesNotCreateResyncFeedbackLoop(t *testing.T) {
	r := fixture(t)
	output := r.out.(*strings.Builder)
	output.Reset()
	r.herdr = filepath.Join(t.TempDir(), "missing.sock")
	for i := 0; i < 4; i++ {
		r.discover()
	}
	if got := strings.Count(output.String(), "herdr_unavailable"); got != 1 {
		t.Fatalf("expected one resync, got %d: %s", got, output.String())
	}
	if _, e := r.handle(obj{"method": "sessions.list"}); e == nil {
		t.Fatal("stale list returned while Herdr unavailable")
	}
}
func TestResetSurvivesTemporaryMissingBridge(t *testing.T) {
	r := fixture(t)
	output := r.out.(*strings.Builder)
	output.Reset()
	r.identities["w1:p1"] = "previous-session|previous-instance"
	r.discover()
	if !strings.Contains(output.String(), "session.reset") {
		t.Fatal(output.String())
	}
}
func TestTextOnlyInputUsesEmptyKeysNotNull(t *testing.T) {
	calls := make(chan obj, 1)
	socket := fakeSocket(t, "herdr.sock", func(q obj) obj { calls <- object(q["params"]); return obj{"result": obj{"queued": true}} })
	r := &relay{ctx: context.Background(), herdr: socket, sessions: map[string]obj{"owned-pane": {}}}
	if _, e := r.handle(obj{"method": "terminal.input", "params": obj{"paneId": "owned-pane", "text": "draft"}}); e != nil {
		t.Fatal(e)
	}
	p := <-calls
	if p["keys"] == nil || len(array(p["keys"])) != 0 {
		t.Fatal(p)
	}
}
