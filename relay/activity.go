package main

import "os"

// Existing bridge instances already publish sessionFile. Stat avoids rereading
// full conversation logs and requires no extension reload or model request.
func sessionActivity(info obj) int64 {
	path := str(info["sessionFile"])
	if path == "" {
		return 0
	}
	file, err := os.Stat(path)
	if err != nil || !file.Mode().IsRegular() {
		return 0
	}
	value := file.ModTime().UnixMilli()
	if value < 0 {
		return 0
	}
	return value
}
