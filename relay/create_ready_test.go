package main

import (
	"context"
	"errors"
	"io"
	"net"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"
)

func TestCreationWaitsForNewShellWithoutCreatingAnotherTab(t *testing.T) {
	r, tabs, starts, mu := creationFixture(t, false, false, 2)
	params := obj{"workspaceId": "w1", "requestId": testCreationID}
	result, err := r.createSession(params)
	if err != nil || result["started"] != true {
		t.Fatal(result, err)
	}
	if _, err := r.createSession(params); err != nil {
		t.Fatal(err)
	}
	mu.Lock()
	defer mu.Unlock()
	if *tabs != 1 || *starts != 3 {
		t.Fatalf("tabs=%d starts=%d", *tabs, *starts)
	}
}

func TestStartCreatedPiStopsOnPermanentBusy(t *testing.T) {
	r, _, starts, mu := creationFixture(t, false, false, 100000)
	before := time.Now()
	err := r.startCreatedPi("w1:p9", testCreationID, 30*time.Millisecond, 5*time.Millisecond)
	var serverErr *rpcError
	if !errors.As(err, &serverErr) || serverErr.code != "agent_pane_busy" {
		t.Fatal(err)
	}
	if time.Since(before) > time.Second {
		t.Fatal("shell wait was not bounded")
	}
	mu.Lock()
	defer mu.Unlock()
	if *starts < 1 || *starts > 7 {
		t.Fatal(*starts)
	}
}

func TestStartCreatedPiNeverRetriesOtherServerErrors(t *testing.T) {
	for _, code := range []string{"agent_not_ready", "timeout", "unknown", "agent_name_conflict"} {
		t.Run(code, func(t *testing.T) {
			var calls atomic.Int32
			socket := fakeSocket(t, "herdr.sock", func(q obj) obj {
				calls.Add(1)
				// A message containing the busy code must not trigger a retry.
				return obj{"error": obj{"code": code, "message": "agent_pane_busy: outcome unknown"}}
			})
			r := &relay{ctx: context.Background(), herdr: socket}
			err := r.startCreatedPi("w1:p9", testCreationID, time.Second, time.Millisecond)
			if err == nil || calls.Load() != 1 {
				t.Fatal(err, calls.Load())
			}
		})
	}
}

func TestStartCreatedPiDoesNotRetryTransportFailure(t *testing.T) {
	socket := filepath.Join(t.TempDir(), "herdr.sock")
	ln, err := net.Listen("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	var calls atomic.Int32
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			calls.Add(1)
			// Consume the request then lose the response: outcome is unknown.
			buf := make([]byte, 4096)
			_, _ = c.Read(buf)
			c.Close()
		}
	}()
	r := &relay{ctx: context.Background(), herdr: socket}
	err = r.startCreatedPi("w1:p9", testCreationID, time.Second, time.Millisecond)
	if !errors.Is(err, io.EOF) || calls.Load() != 1 {
		t.Fatal(err, calls.Load())
	}
}

func TestStartCreatedPiCancellation(t *testing.T) {
	t.Run("before submission", func(t *testing.T) {
		r, _, starts, mu := creationFixture(t, false, false)
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		r.ctx = ctx
		if err := r.startCreatedPi("w1:p9", testCreationID, time.Second, time.Millisecond); !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
		mu.Lock()
		defer mu.Unlock()
		if *starts != 0 {
			t.Fatal("submitted after cancellation")
		}
	})
	t.Run("during shell wait", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()
		var calls atomic.Int32
		socket := fakeSocket(t, "herdr.sock", func(q obj) obj {
			calls.Add(1)
			cancel()
			return obj{"error": obj{"code": "agent_pane_busy", "message": "shell initializing"}}
		})
		r := &relay{ctx: ctx, herdr: socket}
		if err := r.startCreatedPi("w1:p9", testCreationID, time.Second, time.Second); !errors.Is(err, context.Canceled) {
			t.Fatal(err)
		}
		if calls.Load() != 1 {
			t.Fatal(calls.Load())
		}
	})
}
