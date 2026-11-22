package main

import (
	"errors"
	"strings"
)

var reloadSections = map[string]bool{
	"[Context]": true, "[Skills]": true, "[Prompts]": true, "[Extensions]": true,
	"[Skill warnings]": true, "[Prompt conflicts]": true, "[Extension issues]": true, "[Theme conflicts]": true,
}

// Extract recognized Pi resource/status blocks, not the full terminal snapshot.
// The terminal viewport is deliberately read-only; we do not scroll or submit keys.
func extractReloadOutput(text string) (string, string) {
	phase := "unavailable"
	var lines []string
	block := false
	for _, line := range strings.Split(strings.ReplaceAll(text, "\r", ""), "\n") {
		trimmed := strings.TrimSpace(line)
		// Pi's editor starts after a full-width horizontal border. Do not inspect
		// even resource-looking text in the user's unsent input or the footer.
		if len([]rune(trimmed)) >= 8 && strings.Trim(trimmed, "─━═") == "" {
			break
		}
		status := strings.HasPrefix(trimmed, "Reloaded keybindings,") || strings.HasPrefix(trimmed, "Reloading keybindings,")
		failed := strings.HasPrefix(trimmed, "Reload failed:") || strings.HasPrefix(trimmed, "Error: Reload failed:")
		if reloadSections[trimmed] || status || failed {
			if len(lines) > 0 {
				lines = append(lines, "")
			}
			lines = append(lines, trimmed)
			block = true
			if strings.HasPrefix(trimmed, "Reloaded keybindings,") && phase != "failed" {
				phase = "complete"
			}
			if strings.HasPrefix(trimmed, "Reloading keybindings,") && phase == "unavailable" {
				phase = "loading"
			}
			if failed {
				phase = "failed"
			}
		} else if block && trimmed != "" && (strings.HasPrefix(line, " ") || strings.HasPrefix(line, "\t")) {
			lines = append(lines, line)
		} else {
			block = false
		}
	}
	result := strings.Join(lines, "\n")
	if len(result) > 64*1024 {
		return "unavailable", ""
	}
	return phase, result
}

func (r *relay) reloadOutput(key, expected string) (obj, error) {
	if expected == "" {
		return nil, errors.New("expected_session_id_required")
	}
	b, err := r.get(key)
	if err != nil {
		return nil, err
	}
	before, err := rpc(b.socket, "info", obj{})
	if err != nil {
		return nil, err
	}
	if str(before["sessionId"]) != expected {
		return nil, errors.New("session_changed")
	}
	read, err := rpc(r.herdr, "pane.read", obj{"pane_id": key, "source": "visible", "format": "text"})
	if err != nil {
		return nil, err
	}
	after, err := rpc(b.socket, "info", obj{})
	if err != nil {
		return nil, err
	}
	if str(after["sessionId"]) != expected || str(before["instanceId"]) == "" || str(before["instanceId"]) != str(after["instanceId"]) {
		return nil, errors.New("session_changed")
	}
	text := str(object(read["read"])["text"])
	if text == "" {
		text = str(read["text"])
	}
	phase, output := extractReloadOutput(text)
	return obj{"sessionId": expected, "instanceId": after["instanceId"], "phase": phase, "text": output}, nil
}
