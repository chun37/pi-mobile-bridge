package main

import (
	"strings"
	"sync/atomic"
	"testing"
)

func TestReloadOutputExtractsOnlyResourceAndStatusBlocks(t *testing.T) {
	raw := "private conversation\n\n[Context]\n  ~/CLAUDE.md\n\n[Skills]\n  local, writing\n\n[Extensions]\n  pi-bridge\n\n[Extension issues]\n  warning: fixture\n\nprivate reply\n  not a resource\n\n Reloaded keybindings, extensions, skills, prompts, themes,\n and context files\n\n────────────────────\n draft secret\n[Skills]\n  draft-resource-secret\n"
	phase, text := extractReloadOutput(raw)
	if phase != "complete" || !strings.Contains(text, "[Skills]") || !strings.Contains(text, "and context files") {
		t.Fatal(phase, text)
	}
	for _, private := range []string{"private conversation", "private reply", "not a resource", "draft secret", "draft-resource-secret"} {
		if strings.Contains(text, private) {
			t.Fatal("forwarded surrounding chat/editor", text)
		}
	}
}
func TestReloadOutputNeverInventsCompletion(t *testing.T) {
	for _, c := range []struct{ input, phase string }{
		{"chat only\n draft", "unavailable"},
		{"[Skills]\n  fixture", "unavailable"},
		{" Reloading keybindings, extensions, skills, prompts, themes, and context files...", "loading"},
		{" Error: Reload failed: fixture failure", "failed"},
	} {
		phase, _ := extractReloadOutput(c.input)
		if phase != c.phase {
			t.Fatal(c, phase)
		}
	}
	phase, text := extractReloadOutput("[Skills]\n  " + strings.Repeat("x", 70*1024))
	if phase != "unavailable" || text != "" {
		t.Fatal("unbounded output")
	}
}
func TestReloadOutputPinsSessionAndInstance(t *testing.T) {
	r := fixture(t)
	var changing atomic.Bool
	var calls atomic.Int32
	socket := fakeSocket(t, "reload.sock", func(q obj) obj {
		id := "original"
		if changing.Load() && calls.Add(1) > 1 {
			id = "changed"
		}
		return obj{"result": obj{"sessionId": "a", "instanceId": id}}
	})
	r.bridges["w1:p1"] = bridge{socket: socket, info: obj{"sessionId": "a"}}
	r.herdr = fakeSocket(t, "reload-herdr.sock", func(q obj) obj {
		if q["method"] != "pane.read" || object(q["params"])["source"] != "visible" {
			return obj{"error": obj{"message": "unexpected mutation"}}
		}
		return obj{"result": obj{"read": obj{"text": " Reloaded keybindings, extensions, skills, prompts, themes, and context files\n\n private draft"}}}
	})
	out, err := r.reloadOutput("w1:p1", "a")
	if err != nil || out["phase"] != "complete" || strings.Contains(str(out["text"]), "draft") {
		t.Fatal(out, err)
	}
	for _, expected := range []string{"", "other-session"} {
		if _, err := r.reloadOutput("w1:p1", expected); err == nil {
			t.Fatal("accepted wrong session")
		}
	}
	changing.Store(true)
	calls.Store(0)
	if _, err := r.reloadOutput("w1:p1", "a"); err == nil {
		t.Fatal("accepted changed bridge instance")
	}
}
