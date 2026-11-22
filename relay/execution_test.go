package main

import (
	"bufio"
	"context"
	"encoding/json"
	"net"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

type packetWriter chan obj

func (w packetWriter) Write(p []byte) (int, error) {
	var v obj
	if e := json.Unmarshal(p, &v); e != nil {
		return 0, e
	}
	w <- v
	return len(p), nil
}

func TestOpenPinsActualSnapshotIdentityAndForwardsExecution(t *testing.T) {
	for _, legacy := range []bool{false, true} {
		name := "atomic"
		if legacy {
			name = "legacy"
		}
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "bridge.sock")
			ln, e := net.Listen("unix", path)
			if e != nil {
				t.Fatal(e)
			}
			stop := make(chan struct{})
			t.Cleanup(func() { ln.Close(); close(stop) })
			var infoCalls atomic.Int32
			info := obj{"sessionId": "actual-session", "instanceId": "actual-instance", "idle": false,
				"execution": obj{"state": "working", "seq": 10}}
			go func() {
				for {
					c, err := ln.Accept()
					if err != nil {
						return
					}
					go func(c net.Conn) {
						defer c.Close()
						sc := bufio.NewScanner(c)
						if !sc.Scan() {
							return
						}
						var q obj
						json.Unmarshal(sc.Bytes(), &q)
						enc := json.NewEncoder(c)
						switch str(q["method"]) {
						case "subscribe":
							enc.Encode(obj{"id": q["id"], "result": obj{"seq": 0}})
							// A buffered packet at/before the snapshot must never be replayed.
							for _, row := range []obj{
								{"event": "agent_start", "seq": 9, "payload": obj{}, "execution": obj{"state": "working", "seq": 9}},
								{"event": "agent_end", "seq": 11, "payload": obj{}, "execution": obj{"state": "working", "seq": 11}},
								{"event": "agent_settled", "seq": 12, "sessionId": "switched-session", "instanceId": "switched-instance", "payload": obj{"aborted": false}, "execution": obj{"state": "done", "seq": 12}},
							} {
								enc.Encode(row)
							}
							<-stop
						case "history":
							h := obj{"entries": []any{}, "seq": 10, "leafId": "leaf"}
							if !legacy {
								h["info"] = info
							}
							enc.Encode(obj{"id": q["id"], "result": h})
						case "info":
							infoCalls.Add(1)
							enc.Encode(obj{"id": q["id"], "result": info})
						}
					}(c)
				}
			}()
			out := make(packetWriter, 8)
			r := &relay{ctx: context.Background(), out: out, bridges: map[string]bridge{"p": {socket: path,
				info: obj{"sessionId": "stale-discovery", "instanceId": "stale-instance"}}},
				sessions: map[string]obj{"p": {"bridge": true}}, subscriptions: map[string]net.Conn{}, generation: map[string]uint64{}}
			h, err := r.open("p", "")
			if err != nil {
				t.Fatal(err)
			}
			defer func() { r.handle(obj{"method": "session.close", "params": obj{"sessionKey": "p"}}); r.workers.Wait() }()
			if object(h["info"])["sessionId"] != "actual-session" {
				t.Fatal(h)
			}
			for _, seq := range []float64{11, 12} {
				select {
				case packet := <-out:
					sid, instance := "actual-session", "actual-instance"
					if seq == 12 {
						sid, instance = "switched-session", "switched-instance"
					}
					if packet["seq"] != seq || packet["sessionId"] != sid || packet["instanceId"] != instance {
						t.Fatal(packet)
					}
					if object(packet["execution"])["seq"] != seq {
						t.Fatal(packet)
					}
					if seq == 12 && object(packet["execution"])["state"] != "done" {
						t.Fatal(packet)
					}
				case <-time.After(2 * time.Second):
					t.Fatal("missing execution event")
				}
			}
			expectedCalls := int32(0)
			if legacy {
				expectedCalls = 1
			}
			if infoCalls.Load() != expectedCalls {
				t.Fatal("atomic snapshot replaced by separate info RPC", infoCalls.Load())
			}
		})
	}
}

func TestDiscoveryPreservesSDKExecutionAndRuntimeIdentity(t *testing.T) {
	r := fixture(t)
	socket := fakeSocket(t, "runtime.sock", func(q obj) obj {
		return obj{"result": obj{"paneId": "w1:p1", "sessionId": "a",
			"instanceId": "runtime", "idle": true, "execution": obj{"state": "done", "seq": 10}}}
	})
	r.dir = filepath.Dir(socket)
	r.discover()
	s := r.sessions["w1:p1"]
	if s["instanceId"] != "runtime" || object(s["execution"])["state"] != "done" {
		t.Fatal(s)
	}
	// Herdr's status remains raw metadata, not a competing SDK execution state.
	if s["state"] != "idle" {
		t.Fatal(s)
	}
}

func TestAbortForwardsExpectedSessionWithoutTerminalFallback(t *testing.T) {
	r := fixture(t)
	received := make(chan obj, 1)
	socket := fakeSocket(t, "abort.sock", func(q obj) obj {
		received <- object(q["params"])
		return obj{"error": obj{"code": "unsupported_abort", "message": "unsupported_abort"}}
	})
	r.bridges["w1:p1"] = bridge{socket: socket, info: obj{"sessionId": "a"}}
	_, err := r.handle(obj{"method": "session.abort", "params": obj{"sessionKey": "w1:p1", "expectedSessionId": "a"}})
	if err == nil {
		t.Fatal("guarded abort must not fall back to blind Escape")
	}
	if (<-received)["expectedSessionId"] != "a" {
		t.Fatal("lost expected session guard")
	}
}
