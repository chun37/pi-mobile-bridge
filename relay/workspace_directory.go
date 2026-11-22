package main

import (
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
)

// Herdr 0.9.5's live API does not expose workspace identity_cwd. Read only the
// versioned saved layout beside this server's socket, not the globally focused
// pane, a workspace label, a common path prefix, or a client-provided directory.
// Unsupported/missing state fails closed before tab.create.
func (r *relay) workspaceDirectory(id string) (string, error) {
	path := filepath.Join(filepath.Dir(r.herdr), "session.json")
	unavailable := errors.New("workspace_directory_unavailable: ワークスペースの開始先を取得できません。選択中paneのディレクトリには代替しません")
	stat, err := os.Lstat(path)
	if err != nil || !stat.Mode().IsRegular() || stat.Size() > 8*1024*1024 {
		return "", unavailable
	}
	file, err := os.Open(path)
	if err != nil {
		return "", unavailable
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 8*1024*1024+1))
	if err != nil || len(data) > 8*1024*1024 {
		return "", unavailable
	}
	var state struct {
		Version    int `json:"version"`
		Workspaces []struct {
			ID        string `json:"id"`
			Directory string `json:"identity_cwd"`
		} `json:"workspaces"`
	}
	if json.Unmarshal(data, &state) != nil || state.Version != 3 {
		return "", unavailable
	}
	directory := ""
	found := false
	for _, ws := range state.Workspaces {
		if ws.ID == id {
			if found {
				return "", unavailable
			}
			found = true
			directory = ws.Directory
		}
	}
	if !found || !filepath.IsAbs(directory) || len(directory) > 4096 {
		return "", unavailable
	}
	directory = filepath.Clean(directory)
	info, err := os.Stat(directory)
	if err != nil || !info.IsDir() {
		return "", unavailable
	}
	return directory, nil
}
