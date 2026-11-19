package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSessionActivityUsesPersistedFileModificationTime(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session.jsonl")
	if err := os.WriteFile(path, []byte("{}\n"), 0600); err != nil {
		t.Fatal(err)
	}
	expected := time.UnixMilli(1700000000123)
	if err := os.Chtimes(path, expected, expected); err != nil {
		t.Fatal(err)
	}
	if got := sessionActivity(obj{"sessionFile": path}); got != expected.UnixMilli() {
		t.Fatal(got)
	}
}

func TestUnknownOrNonFileActivityHasNoInventedTimestamp(t *testing.T) {
	dir := t.TempDir()
	for _, info := range []obj{{}, {"sessionFile": filepath.Join(dir, "missing")}, {"sessionFile": dir}} {
		if got := sessionActivity(info); got != 0 {
			t.Fatal(got)
		}
	}
}
