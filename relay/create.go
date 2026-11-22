package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"time"
)

var creationID = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

func (r *relay) listWorkspaces() (obj, error) {
	result, err := rpc(r.herdr, "session.snapshot", obj{})
	if err != nil {
		return nil, err
	}
	workspaces := []any{}
	for _, raw := range array(object(result["snapshot"])["workspaces"]) {
		ws := object(raw)
		if id := str(ws["workspace_id"]); id != "" {
			workspaces = append(workspaces, obj{"id": id, "label": ws["label"]})
		}
	}
	return obj{"workspaces": workspaces}, nil
}

// A durable exclusive reservation prevents a timed-out or reconnected client from
// creating the same tab twice. Unknown outcomes are never automatically replayed.
func (r *relay) createSession(params obj) (obj, error) {
	id, workspace := str(params["requestId"]), str(params["workspaceId"])
	if !creationID.MatchString(id) || workspace == "" || len(workspace) > 128 {
		return nil, errors.New("invalid_creation_request")
	}
	dir := filepath.Join(r.dir, "creations")
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	path := filepath.Join(dir, id+".json")
	if data, err := os.ReadFile(path); err == nil {
		return recordedCreation(data, workspace)
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	// Validate the explicitly selected workspace; never use the desktop focus.
	listing, err := r.listWorkspaces()
	if err != nil {
		return nil, err
	}
	found := false
	for _, ws := range array(listing["workspaces"]) {
		if str(object(ws)["id"]) == workspace {
			found = true
		}
	}
	if !found {
		return nil, errors.New("unknown_workspace")
	}
	directory, err := r.workspaceDirectory(workspace)
	if err != nil {
		return nil, err
	}
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if os.IsExist(err) {
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			return nil, readErr
		}
		return recordedCreation(data, workspace)
	}
	if err != nil {
		return nil, err
	}
	record := obj{"workspaceId": workspace, "requestId": id, "cwd": directory}
	if err = json.NewEncoder(file).Encode(record); err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return nil, err
	}
	if closeErr != nil {
		return nil, closeErr
	}
	created, err := rpc(r.herdr, "tab.create", obj{
		"workspace_id": workspace, "label": "Pi " + id[:8], "focus": false, "cwd": directory,
	})
	if err != nil {
		return nil, fmt.Errorf("creation_outcome_unknown: %w; do not create another tab blindly", err)
	}
	tab := str(object(created["tab"])["tab_id"])
	pane := str(object(created["root_pane"])["pane_id"])
	if tab == "" || pane == "" {
		return nil, errors.New("creation_outcome_unknown: tab response has no tab or root pane")
	}
	result := obj{"requestId": id, "workspaceId": workspace, "tabId": tab, "sessionKey": pane, "cwd": directory}
	// No client-supplied command, argv, environment, shell text, or target pane.
	startErr := r.startCreatedPi(pane, id, 5*time.Second, 100*time.Millisecond)
	result["started"] = startErr == nil
	if startErr != nil {
		result["startupError"] = startErr.Error()
	}
	record["result"] = result
	data, err := json.Marshal(record)
	if err != nil {
		return nil, err
	}
	tmp, err := os.CreateTemp(dir, ".creation-*")
	if err != nil {
		return nil, err
	}
	defer os.Remove(tmp.Name())
	if _, err = tmp.Write(data); err == nil {
		err = tmp.Sync()
	}
	closeErr = tmp.Close()
	if err != nil {
		return nil, err
	}
	if closeErr != nil {
		return nil, closeErr
	}
	if err = os.Rename(tmp.Name(), path); err != nil {
		return nil, err
	}
	r.discover()
	return result, nil
}

// tab.create returns before the new interactive shell is necessarily ready.
// agent_pane_busy is a pre-start rejection: no command was submitted. Only that
// explicit rejection may be retried, only in this newly created root pane.
// Never retry timeouts, transport failures, or post-submission readiness errors.
func (r *relay) startCreatedPi(pane, id string, wait, interval time.Duration) error {
	deadline := time.Now().Add(wait)
	for {
		if err := r.ctx.Err(); err != nil {
			return err
		}
		_, err := rpcWithTimeout(r.herdr, "agent.start", obj{
			"pane_id": pane, "kind": "pi", "name": "mobile-" + id[:24],
			"args": []any{}, "timeout_ms": 20000,
		}, 25*time.Second)
		var serverErr *rpcError
		if !errors.As(err, &serverErr) || serverErr.code != "agent_pane_busy" {
			return err
		}
		remaining := time.Until(deadline)
		if remaining <= 0 {
			return err
		}
		timer := time.NewTimer(min(interval, remaining))
		select {
		case <-r.ctx.Done():
			timer.Stop()
			return r.ctx.Err()
		case <-timer.C:
		}
		if !time.Now().Before(deadline) {
			return err
		}
	}
}

func recordedCreation(data []byte, workspace string) (obj, error) {
	var record obj
	if json.Unmarshal(data, &record) != nil {
		return nil, errors.New("creation_pending: do not create another tab blindly")
	}
	if str(record["workspaceId"]) != workspace {
		return nil, errors.New("creation_request_conflict")
	}
	if result := object(record["result"]); len(result) != 0 {
		return result, nil
	}
	return nil, errors.New("creation_pending: previous outcome is unknown; inspect existing tabs before starting again")
}
