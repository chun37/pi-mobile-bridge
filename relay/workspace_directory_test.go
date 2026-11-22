package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func saveWorkspaceState(t *testing.T, r *relay, version int, workspaces []any) {
	t.Helper()
	data, err := json.Marshal(obj{"version": version, "workspaces": workspaces})
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(filepath.Dir(r.herdr), "session.json"), data, 0600); err != nil {
		t.Fatal(err)
	}
}
func TestWorkspaceDirectoryUsesIdentityNotLabelOrPaneCwd(t *testing.T) {
	r, _, _, _ := creationFixture(t, false, false)
	root := filepath.Join(t.TempDir(), "基準 directory")
	if err := os.MkdirAll(root, 0700); err != nil {
		t.Fatal(err)
	}
	saveWorkspaceState(t, r, 3, []any{obj{"id": "w1", "identity_cwd": root, "custom_name": "completely-different-label", "tabs": []any{obj{"cwd": "/wrong/artifacts/strix"}}}})
	directory, err := r.workspaceDirectory("w1")
	if err != nil || directory != root {
		t.Fatal(directory, err)
	}
}
func TestWorkspaceDirectoryRejectsMissingUnknownAndInvalidState(t *testing.T) {
	for _, test := range []string{"missing", "malformed", "unsupported", "no-workspace", "duplicate", "empty", "relative", "not-directory", "missing-directory", "symlink", "oversized"} {
		t.Run(test, func(t *testing.T) {
			r, tabs, _, mu := creationFixture(t, false, false)
			path := filepath.Join(filepath.Dir(r.herdr), "session.json")
			root := t.TempDir()
			switch test {
			case "missing":
				os.Remove(path)
			case "malformed":
				os.WriteFile(path, []byte("{partial"), 0600)
			case "unsupported":
				saveWorkspaceState(t, r, 99, []any{obj{"id": "w1", "identity_cwd": root}})
			case "no-workspace":
				saveWorkspaceState(t, r, 3, []any{obj{"id": "other", "identity_cwd": root}})
			case "duplicate":
				saveWorkspaceState(t, r, 3, []any{obj{"id": "w1", "identity_cwd": root}, obj{"id": "w1", "identity_cwd": root}})
			case "empty":
				saveWorkspaceState(t, r, 3, []any{obj{"id": "w1", "identity_cwd": ""}})
			case "relative":
				saveWorkspaceState(t, r, 3, []any{obj{"id": "w1", "identity_cwd": "relative/artifacts"}})
			case "not-directory":
				saveWorkspaceState(t, r, 3, []any{obj{"id": "w1", "identity_cwd": path}})
			case "missing-directory":
				saveWorkspaceState(t, r, 3, []any{obj{"id": "w1", "identity_cwd": filepath.Join(root, "missing")}})
			case "symlink":
				os.Rename(path, path+".original")
				os.Symlink(path+".original", path)
			case "oversized":
				f, err := os.OpenFile(path, os.O_WRONLY, 0600)
				if err != nil {
					t.Fatal(err)
				}
				if err = f.Truncate(8*1024*1024 + 1); err != nil {
					t.Fatal(err)
				}
				f.Close()
			}
			if _, err := r.createSession(obj{"workspaceId": "w1", "requestId": testCreationID}); err == nil {
				t.Fatal("created without verified directory")
			}
			mu.Lock()
			defer mu.Unlock()
			if *tabs != 0 {
				t.Fatal("must not call tab.create")
			}
			if _, err := os.Stat(filepath.Join(r.dir, "creations", testCreationID+".json")); !os.IsNotExist(err) {
				t.Fatal("failed validation reserved request", err)
			}
		})
	}
}
func TestDirectoryIsRecordedAndReplayNeverChangesIt(t *testing.T) {
	r, _, _, _ := creationFixture(t, false, false)
	first, err := r.createSession(obj{"workspaceId": "w1", "requestId": testCreationID})
	if err != nil {
		t.Fatal(err)
	}
	if first["cwd"] == "" || first["cwd"] == nil {
		t.Fatal(first)
	}
	os.Remove(filepath.Join(filepath.Dir(r.herdr), "session.json"))
	next, err := r.createSession(obj{"workspaceId": "w1", "requestId": testCreationID, "cwd": "/wrong"})
	if err != nil || next["cwd"] != first["cwd"] {
		t.Fatal(next, err)
	}
}
func TestDirectoryIsScopedToSelectedServer(t *testing.T) {
	a, _, _, _ := creationFixture(t, false, false)
	b, _, _, _ := creationFixture(t, false, false)
	da, ea := a.workspaceDirectory("w1")
	db, eb := b.workspaceDirectory("w1")
	if ea != nil || eb != nil || da == db {
		t.Fatal(da, db, ea, eb)
	}
}
