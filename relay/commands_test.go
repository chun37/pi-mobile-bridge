package main

import (
	"path/filepath"
	"strings"
	"testing"
)

func TestLegacyBridgeNeverReceivesSlashAsModelPrompt(t *testing.T) {
	r := fixture(t)
	_, err := r.handle(obj{"method": "session.send", "params": obj{"sessionKey": "w1:p1", "text": "/reload"}})
	if err == nil || !strings.Contains(err.Error(), "require_pi_reload") {
		t.Fatal(err)
	}
	_, err = r.handle(obj{"method": "session.send", "params": obj{"sessionKey": "w1:p1", "text": "ordinary message"}})
	if err != nil {
		t.Fatal(err)
	}
}

func TestCommandCatalogAndContextMetadata(t *testing.T) {
	r := fixture(t)
	socket := fakeSocket(t, "commands.sock", func(q obj) obj {
		switch q["method"] {
		case "info":
			return obj{"result": obj{"paneId": "w1:p1", "sessionId": "a", "model": "fixture", "thinkingLevel": "high", "idle": true,
				"contextUsage": obj{"tokens": 12345, "contextWindow": 100000, "percent": 12.345},
				"capabilities": []any{"slashCommands", "contextUsage"}}}
		case "commands":
			return obj{"result": obj{"commands": []any{obj{"name": "model", "source": "builtin"}}}}
		case "send":
			return obj{"result": obj{"status": "command"}}
		}
		return obj{"error": obj{"message": "unsupported"}}
	})
	r.dir = filepath.Dir(socket)
	r.discover()
	if object(r.sessions["w1:p1"]["contextUsage"])["percent"] != 12.345 {
		t.Fatal(r.sessions)
	}
	if r.sessions["w1:p1"]["thinkingLevel"] != "high" {
		t.Fatal(r.sessions)
	}
	if len(array(r.sessions["w1:p1"]["capabilities"])) != 2 {
		t.Fatal(r.sessions)
	}
	result, err := r.handle(obj{"method": "session.commands", "params": obj{"sessionKey": "w1:p1"}})
	if err != nil || len(array(object(result)["commands"])) != 1 {
		t.Fatal(result, err)
	}
	result, err = r.handle(obj{"method": "session.send", "params": obj{"sessionKey": "w1:p1", "text": "/model fixture"}})
	if err != nil || object(result)["status"] != "command" {
		t.Fatal(result, err)
	}
}
