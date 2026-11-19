package main

import (
	"os"
	"strconv"
)

// Metadata is written under a per-relay source and expires after disconnection.
// It is never exposed as an arbitrary mobile RPC method.
func (r *relay) mobileMetadata() {
	r.mu.Lock()
	panes := make([]string, 0, len(r.subscriptions))
	for pane := range r.subscriptions {
		panes = append(panes, pane)
	}
	r.mu.Unlock()
	for _, pane := range panes {
		if r.ctx.Err() != nil {
			return
		}
		_, _ = rpc(r.herdr, "pane.report_metadata", obj{"pane_id": pane, "source": "pibridge:" + strconv.Itoa(os.Getpid()), "tokens": obj{"mobile": "connected"}, "ttl_ms": 15000})
	}
}
