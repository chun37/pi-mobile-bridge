package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/fsnotify/fsnotify"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const version = "0.1.0"
const maxFrame = 24 * 1024 * 1024

type obj = map[string]any

func str(v any) string  { s, _ := v.(string); return s }
func array(v any) []any { a, _ := v.([]any); return a }
func object(v any) obj {
	a, _ := v.(map[string]any)
	if a == nil {
		return obj{}
	}
	return a
}

// Keep the server error code separate from its human-readable message so that
// callers can distinguish a rejected operation from an unknown transport outcome.
type rpcError struct {
	code, message string
}

func (e *rpcError) Error() string { return fmt.Sprintf("%s: %s", e.code, e.message) }

func dial(socket string) (net.Conn, error) { return net.DialTimeout("unix", socket, 2*time.Second) }
func rpc(socket, method string, params obj) (obj, error) {
	return rpcWithTimeout(socket, method, params, 8*time.Second)
}
func rpcWithTimeout(socket, method string, params obj, timeout time.Duration) (obj, error) {
	c, e := dial(socket)
	if e != nil {
		return nil, e
	}
	defer c.Close()
	c.SetDeadline(time.Now().Add(timeout))
	if e = json.NewEncoder(c).Encode(obj{"id": "relay", "method": method, "params": params}); e != nil {
		return nil, e
	}
	sc := bufio.NewScanner(c)
	sc.Buffer(make([]byte, 65536), maxFrame)
	for sc.Scan() {
		var r obj
		if e = json.Unmarshal(sc.Bytes(), &r); e != nil {
			return nil, e
		}
		if r["id"] != "relay" {
			continue
		}
		if er := object(r["error"]); len(er) > 0 {
			return nil, &rpcError{code: str(er["code"]), message: str(er["message"])}
		}
		return object(r["result"]), nil
	}
	if sc.Err() != nil {
		return nil, sc.Err()
	}
	return nil, io.EOF
}

type bridge struct {
	socket string
	info   obj
}
type relay struct {
	ctx           context.Context
	herdr, dir    string
	out           io.Writer
	mu            sync.Mutex
	writeMu       sync.Mutex
	eventMu       sync.Mutex
	workers       sync.WaitGroup
	terminateMu   sync.Mutex
	discoverMu    sync.Mutex
	bridges       map[string]bridge
	sessions      map[string]obj
	subscriptions map[string]net.Conn
	generation    map[string]uint64
	herdrVersion  string
	herdrProtocol any
	herdrDown     bool
	identities    map[string]string
}

func (r *relay) write(v obj) {
	r.writeMu.Lock()
	defer r.writeMu.Unlock()
	if e := json.NewEncoder(r.out).Encode(v); e != nil {
		return
	}
}
func (r *relay) discover() {
	r.discoverMu.Lock()
	defer r.discoverMu.Unlock()
	if r.ctx.Err() != nil {
		return
	}
	snap, err := rpc(r.herdr, "session.snapshot", obj{})
	if err != nil {
		r.mu.Lock()
		wasDown := r.herdrDown
		r.herdrDown = true
		r.mu.Unlock()
		if !wasDown {
			r.write(obj{"event": "resync", "reason": "herdr_unavailable"})
		}
		return
	}
	snapshot := object(snap["snapshot"])
	ws, tabs := map[string]string{}, map[string]string{}
	for _, v := range array(snapshot["workspaces"]) {
		p := object(v)
		ws[str(p["workspace_id"])] = str(p["label"])
	}
	for _, v := range array(snapshot["tabs"]) {
		p := object(v)
		tabs[str(p["tab_id"])] = str(p["label"])
	}
	bridges := map[string]bridge{}
	files, _ := filepath.Glob(filepath.Join(r.dir, "*.sock"))
	for _, f := range files {
		if st, e := os.Lstat(f); e != nil || st.Mode()&os.ModeSocket == 0 {
			continue
		}
		info, e := rpc(f, "info", obj{})
		if e == nil && str(info["paneId"]) != "" {
			if reported := str(info["herdrSocket"]); reported != "" && filepath.Clean(reported) != filepath.Clean(r.herdr) {
				continue
			}
			bridges[str(info["paneId"])] = bridge{f, info}
		}
	}
	sessions := map[string]obj{}
	for _, v := range array(snapshot["panes"]) {
		p := object(v)
		id := str(p["pane_id"])
		b, ok := bridges[id]
		s := obj{"sessionKey": id, "paneId": id, "workspace": ws[str(p["workspace_id"])], "workspaceId": p["workspace_id"], "tabId": p["tab_id"], "tab": tabs[str(p["tab_id"])], "state": p["agent_status"], "agent": p["agent"], "bridge": ok, "cwd": p["cwd"]}
		if ok {
			for _, k := range []string{"sessionId", "sessionName", "cwd", "model", "thinkingLevel", "lastEntryId", "lastResponse", "contextUsage", "capabilities", "instanceId", "execution"} {
				s[k] = b.info[k]
			}
			s["lastActivityAt"] = sessionActivity(b.info)
		}
		sessions[id] = s
	}
	r.mu.Lock()
	old := r.sessions
	oldBridges := r.bridges
	r.sessions = sessions
	r.bridges = bridges
	r.herdrVersion = str(snapshot["version"])
	r.herdrProtocol = snapshot["protocol"]
	wasDown := r.herdrDown
	r.herdrDown = false
	added, updated, removed := []any{}, []any{}, []any{}
	resets := []obj{}
	if r.identities == nil {
		r.identities = map[string]string{}
	}
	for key, b := range bridges {
		identity := str(b.info["sessionId"]) + "|" + str(b.info["instanceId"])
		if previous := r.identities[key]; previous != "" && previous != identity {
			reason := str(b.info["startReason"])
			if reason == "" {
				reason = "session_changed"
			}
			resets = append(resets, obj{"event": "session.reset", "sessionKey": key, "reason": reason, "sessionId": b.info["sessionId"], "instanceId": b.info["instanceId"]})
		}
		r.identities[key] = identity
	}
	for k, v := range sessions {
		o, ok := old[k]
		if !ok {
			added = append(added, v)
		} else {
			a, _ := json.Marshal(o)
			b, _ := json.Marshal(v)
			if string(a) != string(b) {
				updated = append(updated, v)
			}
		}
	}
	for k := range old {
		if _, ok := sessions[k]; !ok {
			removed = append(removed, k)
			delete(r.identities, k)
		}
	}
	for k, c := range r.subscriptions {
		b, ok := bridges[k]
		if !ok || b.socket != oldBridges[k].socket || str(b.info["sessionId"]) != str(oldBridges[k].info["sessionId"]) || str(b.info["instanceId"]) != str(oldBridges[k].info["instanceId"]) {
			c.Close()
			delete(r.subscriptions, k)
			r.generation[k]++
			if !ok {
				resets = append(resets, obj{"event": "session.reset", "sessionKey": k, "reason": "bridge_unavailable"})
			}
		}
	}
	r.mu.Unlock()
	for _, reset := range resets {
		r.write(reset)
	}
	if wasDown {
		r.write(obj{"event": "resync", "reason": "herdr_recovered"})
	}
	if len(added)+len(updated)+len(removed) > 0 {
		r.write(obj{"event": "sessions.changed", "added": added, "updated": updated, "removed": removed})
	}
	for _, v := range updated {
		p := object(v)
		oldState := old[str(p["sessionKey"])]["state"]
		if oldState != p["state"] {
			r.write(obj{"event": "agent.state", "sessionKey": p["sessionKey"], "state": p["state"]})
		}
	}
}
func (r *relay) get(key string) (bridge, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	b, ok := r.bridges[key]
	if !ok {
		return b, errors.New("bridge_unavailable")
	}
	return b, nil
}
func (r *relay) open(key, since string) (obj, error) {
	b, e := r.get(key)
	if e != nil {
		return nil, e
	}
	c, e := dial(b.socket)
	if e != nil {
		return nil, e
	}
	r.mu.Lock()
	if old := r.subscriptions[key]; old != nil {
		old.Close()
	}
	r.generation[key]++
	gen := r.generation[key]
	r.subscriptions[key] = c
	r.mu.Unlock()
	// Subscribe before taking the snapshot. Buffer events until history's seq is known.
	c.SetDeadline(time.Now().Add(8 * time.Second))
	json.NewEncoder(c).Encode(obj{"id": "subscribe", "method": "subscribe", "params": obj{}})
	sc := bufio.NewScanner(c)
	sc.Buffer(make([]byte, 65536), maxFrame)
	if !sc.Scan() {
		c.Close()
		return nil, errors.New("subscribe_failed")
	}
	var ack obj
	if e = json.Unmarshal(sc.Bytes(), &ack); e != nil || ack["error"] != nil {
		c.Close()
		return nil, errors.New("subscribe_rejected")
	}
	h, e := rpc(b.socket, "history", obj{"since": since})
	if e != nil {
		c.Close()
		return nil, e
	}
	// New extensions return identity and execution state in the atomic history snapshot.
	// Legacy extensions are supported, but have no sequenced execution snapshot.
	info := object(h["info"])
	if len(info) == 0 {
		info, e = rpc(b.socket, "info", obj{})
		if e != nil {
			c.Close()
			return nil, e
		}
		h["info"] = info
	}
	c.SetDeadline(time.Time{})
	watermark, _ := h["seq"].(float64)
	r.workers.Add(1)
	go func() {
		defer r.workers.Done()
		defer c.Close()
		for sc.Scan() {
			var ev obj
			if json.Unmarshal(sc.Bytes(), &ev) != nil {
				continue
			}
			r.mu.Lock()
			active := r.generation[key] == gen
			r.mu.Unlock()
			if !active {
				return
			}
			seq, _ := ev["seq"].(float64)
			if seq <= watermark {
				continue
			}
			r.eventMu.Lock()
			r.mu.Lock()
			active = r.generation[key] == gen
			r.mu.Unlock()
			if active {
				identity := info
				if str(ev["sessionId"]) != "" && str(ev["instanceId"]) != "" {
					identity = ev // Session switches can happen before the next discovery.
				}
				r.write(obj{"event": "session.event", "sessionKey": key, "sessionId": identity["sessionId"], "instanceId": identity["instanceId"], "seq": ev["seq"], "kind": ev["event"], "payload": ev["payload"], "execution": ev["execution"]})
			}
			r.eventMu.Unlock()
		}
		r.mu.Lock()
		active := r.generation[key] == gen
		if active {
			delete(r.subscriptions, key)
		}
		r.mu.Unlock()
		if active {
			r.write(obj{"event": "resync", "sessionKey": key})
		}
	}()
	return h, nil
}
func (r *relay) handle(req obj) (any, error) {
	p := object(req["params"])
	key := str(p["sessionKey"])
	switch str(req["method"]) {
	case "hello":
		if p["protocol"] != float64(1) {
			return nil, errors.New("unsupported_protocol")
		}
		r.mu.Lock()
		defer r.mu.Unlock()
		bridgeVersions := []any{}
		for _, b := range r.bridges {
			bridgeVersions = append(bridgeVersions, b.info["bridgeVersion"])
		}
		return obj{"protocol": 1, "relayVersion": version, "herdrVersion": r.herdrVersion, "herdrProtocol": r.herdrProtocol, "bridgeVersion": version, "bridgeVersions": bridgeVersions, "capabilities": []any{"sessionTerminate"}}, nil
	case "ping":
		return obj{"pong": true}, nil
	case "workspaces.list":
		return r.listWorkspaces()
	case "session.create":
		return r.createSession(p)
	case "session.terminate":
		return r.terminateSession(p)
	case "sessions.list":
		r.discover()
		r.mu.Lock()
		defer r.mu.Unlock()
		if r.herdrDown {
			return nil, errors.New("herdr_unavailable")
		}
		a := []any{}
		for _, s := range r.sessions {
			a = append(a, s)
		}
		return obj{"sessions": a}, nil
	case "session.open":
		return r.open(key, str(p["since"]))
	case "session.close":
		r.mu.Lock()
		r.generation[key]++
		if c := r.subscriptions[key]; c != nil {
			c.Close()
			delete(r.subscriptions, key)
		}
		r.mu.Unlock()
		return obj{"closed": true}, nil
	case "session.reloadOutput":
		return r.reloadOutput(key, str(p["expectedSessionId"]))
	case "session.commands":
		b, e := r.get(key)
		if e != nil {
			return nil, e
		}
		return rpc(b.socket, "commands", obj{})
	case "session.send":
		b, e := r.get(key)
		if e != nil {
			return nil, e
		}
		if strings.HasPrefix(str(p["text"]), "/") {
			supported := false
			for _, capability := range array(b.info["capabilities"]) {
				if str(capability) == "slashCommands" {
					supported = true
				}
			}
			if !supported {
				return nil, errors.New("slash_commands_require_pi_reload")
			}
		}
		return rpc(b.socket, "send", p)
	case "session.abort":
		b, e := r.get(key)
		if e != nil {
			return nil, e
		}
		result, e := rpc(b.socket, "abort", obj{"expectedSessionId": p["expectedSessionId"]})
		if e != nil && strings.Contains(e.Error(), "unsupported") && str(p["expectedSessionId"]) == "" {
			return rpc(r.herdr, "pane.send_keys", obj{"pane_id": key, "keys": []string{"esc"}})
		}
		return result, e
	case "terminal.read", "terminal.keys", "terminal.input":
		pane := str(p["paneId"])
		r.mu.Lock()
		_, ok := r.sessions[pane]
		r.mu.Unlock()
		if !ok {
			return nil, errors.New("unknown_pane")
		}
		params := obj{"pane_id": pane}
		method := "pane.read"
		if req["method"] == "terminal.read" {
			source := str(p["source"])
			if source == "" {
				source = "visible"
			}
			if source != "visible" && source != "recent" && source != "recent_unwrapped" && source != "detection" {
				return nil, errors.New("invalid_source")
			}
			params["source"] = source
			params["lines"] = 200
			params["strip_ansi"] = true
		} else {
			method = "pane.send_keys"
			keys := array(p["keys"])
			if keys == nil {
				keys = []any{}
			}
			if len(keys) > 64 {
				return nil, errors.New("too_many_keys")
			}
			for _, key := range keys {
				if str(key) == "" || len(str(key)) > 64 {
					return nil, errors.New("invalid_key")
				}
			}
			params["keys"] = keys
			if req["method"] == "terminal.input" {
				method = "pane.send_input"
				text, ok := p["text"].(string)
				if !ok || len(text) > 1000000 {
					return nil, errors.New("invalid_text")
				}
				params["text"] = text
			}
		}
		return rpc(r.herdr, method, params)
	default:
		return nil, errors.New("method_not_allowed")
	}
}
func (r *relay) watch() {
	watcher, e := fsnotify.NewWatcher()
	if e == nil {
		defer watcher.Close()
		watcher.Add(r.dir)
	}
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	var events <-chan fsnotify.Event
	var errs <-chan error
	if watcher != nil {
		events = watcher.Events
		errs = watcher.Errors
	}
	for {
		select {
		case <-r.ctx.Done():
			return
		case <-tick.C:
			r.discover()
			r.mobileMetadata()
		case _, ok := <-events:
			if !ok {
				events = nil
			} else {
				r.discover()
			}
		case _, ok := <-errs:
			if !ok {
				errs = nil
			} else {
				r.write(obj{"event": "resync", "reason": "watch_overflow"})
				r.discover()
			}
		}
	}
}
func (r *relay) watchHerdr() {
	for {
		if r.ctx.Err() != nil {
			return
		}
		c, e := dial(r.herdr)
		if e != nil {
			select {
			case <-r.ctx.Done():
				return
			case <-time.After(3 * time.Second):
				continue
			}
		}
		subs := []obj{}
		r.mu.Lock()
		for pane := range r.sessions {
			subs = append(subs, obj{"type": "pane.agent_status_changed", "pane_id": pane})
		}
		r.mu.Unlock()
		for _, t := range []string{"pane.created", "pane.closed", "pane.updated", "pane.agent_detected", "workspace.renamed", "tab.renamed", "pane.moved"} {
			subs = append(subs, obj{"type": t})
		}
		json.NewEncoder(c).Encode(obj{"id": "events", "method": "events.subscribe", "params": obj{"subscriptions": subs}})
		done := make(chan struct{})
		go func() {
			select {
			case <-r.ctx.Done():
				c.Close()
			case <-done:
			}
		}()
		sc := bufio.NewScanner(c)
		sc.Buffer(make([]byte, 65536), maxFrame)
		for sc.Scan() {
			var ev obj
			if json.Unmarshal(sc.Bytes(), &ev) != nil {
				continue
			}
			if ev["error"] != nil {
				break
			}
			if ev["event"] != nil {
				r.discover()
			}
		}
		close(done)
		c.Close()
		if r.ctx.Err() != nil {
			return
		}
		r.write(obj{"event": "resync", "reason": "herdr_events_lost"})
		r.discover()
		select {
		case <-r.ctx.Done():
			return
		case <-time.After(3 * time.Second):
		}
	}
}
func run(in io.Reader, out io.Writer, herdr, dir string) error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	r := &relay{ctx: ctx, herdr: herdr, dir: dir, out: out, bridges: map[string]bridge{}, sessions: map[string]obj{}, subscriptions: map[string]net.Conn{}, generation: map[string]uint64{}}
	r.discover()
	r.workers.Add(2)
	go func() { defer r.workers.Done(); r.watch() }()
	go func() { defer r.workers.Done(); r.watchHerdr() }()
	defer func() {
		cancel()
		r.mu.Lock()
		for _, c := range r.subscriptions {
			c.Close()
		}
		r.mu.Unlock()
		r.workers.Wait()
	}()
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 65536), maxFrame)
	for sc.Scan() {
		var req obj
		if e := json.Unmarshal(sc.Bytes(), &req); e != nil {
			r.write(obj{"id": "", "error": obj{"code": "parse_error", "message": "invalid JSON"}})
			continue
		}
		// The history response must precede all detail events for a new subscription.
		opening := req["method"] == "session.open"
		if opening {
			r.eventMu.Lock()
		}
		result, e := r.handle(req)
		if e != nil {
			r.write(obj{"id": req["id"], "error": obj{"code": "relay_error", "message": e.Error()}})
		} else {
			r.write(obj{"id": req["id"], "result": result})
		}
		if opening {
			r.eventMu.Unlock()
		}
	}
	return sc.Err()
}
func main() {
	if len(os.Args) != 2 || os.Args[1] != "relay" {
		fmt.Fprintln(os.Stderr, "usage: pibridge relay")
		os.Exit(2)
	}
	home, _ := os.UserHomeDir()
	herdr := os.Getenv("HERDR_SOCKET_PATH")
	if herdr == "" {
		herdr = filepath.Join(home, ".config/herdr/herdr.sock")
	}
	dir := os.Getenv("PIBRIDGE_DIR")
	if dir == "" {
		dir = filepath.Join(home, ".pi/agent/bridge")
	}
	os.MkdirAll(dir, 0700)
	if e := run(os.Stdin, os.Stdout, herdr, dir); e != nil {
		fmt.Fprintln(os.Stderr, "pibridge:", strconv.Quote(e.Error()))
		os.Exit(1)
	}
}
