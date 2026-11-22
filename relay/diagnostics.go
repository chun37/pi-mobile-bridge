package main

import (
	"errors"
	"os"
	"runtime"
	"runtime/debug"
	"strings"
	"time"
)

var relayStartedAt = time.Now().UTC().Format(time.RFC3339)

// Report the running process, never a `pi --version` subprocess or an installed
// package on disk that may differ from what the user is connected to.
func relayRuntime() obj {
	host, _ := os.Hostname()
	result := obj{"version": version, "pid": os.Getpid(), "goVersion": runtime.Version(), "os": runtime.GOOS, "arch": runtime.GOARCH,
		"hostname": host, "startedAt": relayStartedAt}
	if info, ok := debug.ReadBuildInfo(); ok {
		for _, setting := range info.Settings {
			switch setting.Key {
			case "vcs.revision":
				result["revision"] = setting.Value
			case "vcs.modified":
				result["modified"] = setting.Value == "true"
			}
		}
	}
	if runtime.GOOS == "linux" {
		if path, err := os.Readlink("/proc/self/exe"); err == nil {
			result["executableReplaced"] = strings.HasSuffix(path, " (deleted)")
		}
	}
	return result
}

func selectDebugFields(source obj, keys ...string) obj {
	result := obj{}
	for _, key := range keys {
		switch value := source[key].(type) {
		case string:
			if len(value) > 1024 {
				value = value[:1024]
			}
			result[key] = value
		case float64, int, bool:
			result[key] = value
		}
	}
	return result
}

func (r *relay) sessionDebug(p obj) (obj, error) {
	key, sid, instance := str(p["sessionKey"]), str(p["expectedSessionId"]), str(p["expectedInstanceId"])
	if key == "" || sid == "" || instance == "" {
		return nil, errors.New("invalid_debug_target")
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
		return nil, errors.New("debug_target_changed")
	}
	snapshot, err := rpc(r.herdr, "session.snapshot", obj{})
	if err != nil {
		return nil, err
	}
	state := object(snapshot["snapshot"])
	found := false
	for _, raw := range array(state["panes"]) {
		if str(object(raw)["pane_id"]) == key {
			found = true
			break
		}
	}
	if !found {
		return nil, errors.New("debug_target_unavailable")
	}
	// Recheck after the Herdr read so the two sources cannot silently refer to
	// different Pi generations. All values below are explicitly whitelisted.
	again, err := rpc(b.socket, "info", obj{})
	if err != nil {
		return nil, err
	}
	if str(again["paneId"]) != key || str(again["sessionId"]) != sid || str(again["instanceId"]) != instance {
		return nil, errors.New("debug_target_changed")
	}
	bridge := selectDebugFields(again, "bridgeVersion", "pid")
	bridge["build"] = selectDebugFields(object(again["build"]), "revision", "modified")
	bridge["runtime"] = selectDebugFields(object(again["runtime"]), "piVersion", "nodeVersion", "platform", "arch")
	caps := []any{}
	for _, v := range array(again["capabilities"]) {
		if s := str(v); s != "" && len(s) <= 128 && len(caps) < 32 {
			caps = append(caps, s)
		}
	}
	bridge["capabilities"] = caps
	session := selectDebugFields(again, "paneId", "sessionId", "instanceId", "model", "thinkingLevel")
	return obj{"collectedAt": time.Now().UTC().Format(time.RFC3339), "relay": relayRuntime(),
		"herdr": selectDebugFields(state, "version", "protocol"), "bridge": bridge, "session": session}, nil
}
