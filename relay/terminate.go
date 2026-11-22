package main

import (
	"errors"
	"fmt"
)

// session.close only unsubscribes. terminate is an explicit destructive action.
// Close exactly one pane: Herdr removes an empty tab itself. Never tab.close,
// which could kill a sibling added while the confirmation dialog was open.
func (r *relay) terminateSession(p obj) (obj, error) {
	key, sid, instance, tab := str(p["sessionKey"]), str(p["expectedSessionId"]), str(p["expectedInstanceId"]), str(p["expectedTabId"])
	if key == "" || sid == "" || instance == "" || tab == "" || len(key) > 128 || len(sid) > 128 || len(instance) > 128 || len(tab) > 128 {
		return nil, errors.New("invalid_termination_target")
	}
	r.terminateMu.Lock()
	defer r.terminateMu.Unlock()
	snapshot, err := rpc(r.herdr, "session.snapshot", obj{})
	if err != nil {
		return nil, err
	}
	panes, valid := object(snapshot["snapshot"])["panes"].([]any)
	if !valid {
		return nil, errors.New("herdr_snapshot_unavailable")
	}
	var pane obj
	for _, raw := range panes {
		v := object(raw)
		if str(v["pane_id"]) == key {
			pane = v
			break
		}
	}
	if pane == nil {
		return obj{"closed": true, "sessionKey": key, "alreadyClosed": true}, nil
	}
	if str(pane["tab_id"]) != tab {
		return nil, errors.New("termination_target_changed")
	}
	b, err := r.get(key)
	if err != nil {
		return nil, err
	}
	info, err := rpc(b.socket, "info", obj{})
	if err != nil {
		return nil, err
	}
	if str(info["paneId"]) != key || str(info["sessionId"]) != sid || str(info["instanceId"]) != instance {
		return nil, errors.New("termination_target_changed")
	}
	// IDs are never reused by Herdr. The server has no conditional identity-close;
	// identity is checked immediately before this single, non-retried operation.
	if _, err = rpc(r.herdr, "pane.close", obj{"pane_id": key}); err != nil {
		return nil, fmt.Errorf("termination_result_unconfirmed: %w; refresh before any further action", err)
	}
	r.discover()
	return obj{"closed": true, "sessionKey": key}, nil
}
