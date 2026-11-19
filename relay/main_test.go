package main

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func fakeSocket(t *testing.T, name string, handle func(obj) obj) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), name)
	ln, e := net.Listen("unix", p)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, e := ln.Accept()
			if e != nil {
				return
			}
			go func() {
				defer c.Close()
				sc := bufio.NewScanner(c)
				sc.Buffer(make([]byte, 4096), maxFrame)
				for sc.Scan() {
					var q obj
					json.Unmarshal(sc.Bytes(), &q)
					r := handle(q)
					if r != nil {
						r["id"] = q["id"]
						json.NewEncoder(c).Encode(r)
					}
				}
			}()
		}
	}()
	return p
}
func fixture(t *testing.T) *relay {
	dir := t.TempDir()
	bs := fakeSocket(t, "bridge.sock", func(q obj) obj {
		switch q["method"] {
		case "info":
			return obj{"result": obj{"paneId": "w1:p1", "sessionId": "a", "idle": true, "model": "test"}}
		case "history":
			return obj{"result": obj{"entries": []any{obj{"entryId": "u1", "message": obj{"role": "user", "content": "hello\u2028world"}}}, "leafId": "u1", "seq": 0}}
		case "subscribe":
			return obj{"result": obj{"seq": 0}}
		case "send":
			return obj{"result": obj{"status": "queued"}}
		case "abort":
			return obj{"result": obj{"aborted": true}}
		}
		return obj{"error": obj{"code": "unsupported", "message": "unsupported"}}
	})
	os.Symlink(bs, filepath.Join(dir, "linked.sock")) // Discovery must ignore symlinks.
	// Use actual listener's directory, not symlink, for discovery.
	dir = filepath.Dir(bs)
	hs := fakeSocket(t, "herdr.sock", func(q obj) obj {
		switch q["method"] {
		case "session.snapshot":
			return obj{"result": obj{"snapshot": obj{"workspaces": []any{obj{"workspace_id": "w1", "label": "work"}}, "tabs": []any{obj{"tab_id": "w1:t1", "label": "main"}}, "panes": []any{obj{"pane_id": "w1:p1", "workspace_id": "w1", "tab_id": "w1:t1", "agent_status": "idle", "agent": "pi"}, obj{"pane_id": "w1:p2", "workspace_id": "w1", "tab_id": "w1:t1", "agent_status": "unknown"}}}}}
		case "pane.read":
			return obj{"result": obj{"text": "terminal content"}}
		case "pane.send_keys", "pane.send_input":
			return obj{"result": obj{"queued": true}}
		}
		return obj{"error": obj{"code": "denied", "message": "denied"}}
	})
	r := &relay{ctx: context.Background(), herdr: hs, dir: dir, out: &strings.Builder{}, bridges: map[string]bridge{}, sessions: map[string]obj{}, subscriptions: map[string]net.Conn{}, generation: map[string]uint64{}}
	t.Cleanup(func() {
		r.mu.Lock()
		defer r.mu.Unlock()
		for _, c := range r.subscriptions {
			c.Close()
		}
	})
	r.discover()
	return r
}
func TestDiscovery(t *testing.T) {
	r := fixture(t)
	if len(r.sessions) != 2 {
		t.Fatal(r.sessions)
	}
	s := r.sessions["w1:p1"]
	if s["bridge"] != true || s["workspace"] != "work" || s["model"] != "test" {
		t.Fatal(s)
	}
	if r.sessions["w1:p2"]["bridge"] != false {
		t.Fatal("fallback unavailable")
	}
}
func TestAllowlist(t *testing.T) {
	r := fixture(t)
	for _, method := range []string{"server.stop", "pane.close", "shell", "workspace.create"} {
		_, e := r.handle(obj{"method": method})
		if e == nil {
			t.Fatal(method)
		}
	}
	_, e := r.handle(obj{"method": "terminal.read", "params": obj{"paneId": "missing"}})
	if e == nil {
		t.Fatal("unknown pane accepted")
	}
}
func TestHello(t *testing.T) {
	r := fixture(t)
	_, e := r.handle(obj{"method": "hello", "params": obj{"protocol": float64(2)}})
	if e == nil {
		t.Fatal("protocol accepted")
	}
	v, e := r.handle(obj{"method": "hello", "params": obj{"protocol": float64(1)}})
	if e != nil || v.(obj)["protocol"] != 1 {
		t.Fatal(v, e)
	}
}
func TestOpenCloseSendAbort(t *testing.T) {
	r := fixture(t)
	for _, method := range []string{"session.open", "session.send", "session.abort", "session.close"} {
		v, e := r.handle(obj{"method": method, "params": obj{"sessionKey": "w1:p1", "text": "日本語\u2029"}})
		if e != nil {
			t.Fatal(method, e)
		}
		if method == "session.open" && v.(obj)["leafId"] != "u1" {
			t.Fatal(v)
		}
	}
}
func TestTerminal(t *testing.T) {
	r := fixture(t)
	for _, method := range []string{"terminal.read", "terminal.keys", "terminal.input"} {
		_, e := r.handle(obj{"method": method, "params": obj{"paneId": "w1:p2", "keys": []any{"enter"}, "text": "test"}})
		if e != nil {
			t.Fatal(e)
		}
	}
	_, e := r.handle(obj{"method": "terminal.read", "params": obj{"paneId": "w1:p1", "source": "invalid"}})
	if e == nil {
		t.Fatal("bad source accepted")
	}
}
func TestRPCErrorTimeout(t *testing.T) {
	p := fakeSocket(t, "error.sock", func(obj) obj { return obj{"error": obj{"code": "test", "message": "error"}} })
	_, e := rpc(p, "x", obj{})
	if e == nil || !strings.Contains(e.Error(), "test") {
		t.Fatal(e)
	}
	_, e = rpc(filepath.Join(t.TempDir(), "missing"), "x", obj{})
	if e == nil {
		t.Fatal("missing socket")
	}
}
func TestLFUnicode(t *testing.T) {
	data := "{\"text\":\"a\u2028b\u2029c\"}\n"
	sc := bufio.NewScanner(strings.NewReader(data))
	count := 0
	for sc.Scan() {
		var p obj
		if e := json.Unmarshal(sc.Bytes(), &p); e != nil {
			t.Fatal(e)
		}
		if p["text"] != "a\u2028b\u2029c" {
			t.Fatal(p)
		}
		count++
	}
	if count != 1 {
		t.Fatal(count)
	}
}
func TestRunEOF(t *testing.T) {
	r := fixture(t)
	var out strings.Builder
	e := run(strings.NewReader("{\"id\":\"1\",\"method\":\"ping\"}\n"), &out, r.herdr, r.dir)
	if e != nil || !strings.Contains(out.String(), "pong") {
		t.Fatal(out.String(), e)
	}
	time.Sleep(20 * time.Millisecond)
}
