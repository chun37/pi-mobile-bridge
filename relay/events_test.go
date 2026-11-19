package main

import (
	"context"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type lockedOutput struct {
	mu sync.Mutex
	b  strings.Builder
}

func (o *lockedOutput) Write(p []byte) (int, error) {
	o.mu.Lock()
	defer o.mu.Unlock()
	return o.b.Write(p)
}
func (o *lockedOutput) text() string { o.mu.Lock(); defer o.mu.Unlock(); return o.b.String() }
func TestEventsLostRebuildsSnapshot(t *testing.T) {
	var lost atomic.Bool
	socket := fakeSocket(t, "events.sock", func(q obj) obj {
		if q["method"] == "events.subscribe" {
			lost.Store(true)
			return obj{"error": obj{"code": "events_lost", "message": "test overflow"}}
		}
		state := "idle"
		if lost.Load() {
			state = "blocked"
		}
		return obj{"result": obj{"snapshot": obj{"panes": []any{obj{"pane_id": "test", "agent_status": state}}}}}
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	out := &lockedOutput{}
	r := &relay{ctx: ctx, herdr: socket, dir: t.TempDir(), out: out, bridges: map[string]bridge{}, sessions: map[string]obj{}, subscriptions: map[string]net.Conn{}, generation: map[string]uint64{}}
	r.discover()
	done := make(chan struct{})
	go func() { defer close(done); r.watchHerdr() }()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		text := out.text()
		if strings.Contains(text, "herdr_events_lost") && strings.Contains(text, "blocked") {
			cancel()
			<-done
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	cancel()
	<-done
	t.Fatal(out.text())
}
