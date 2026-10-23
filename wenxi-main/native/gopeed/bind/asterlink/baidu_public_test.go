package gopeed

import (
	"encoding/json"
	"testing"
	"time"
)

func TestPublicBaiduUpgradesSingleConnectionOnResume(t *testing.T) {
	storage, payload, key := openTestCore(t)
	f := serveFile(t, 24*1024*1024, 20*time.Millisecond)
	args := request{ID: "baidu-public-upgrade", URL: f.server.URL + "/file",
		Headers:     map[string]string{"Cookie": "test-cookie=local-only"},
		Connections: 1, ConnectionProfile: "baidu", Retries: 1, SpeedLimit: 1024 * 1024}
	raw, _ := json.Marshal(args)
	if err := Begin(string(raw)); err != nil {
		t.Fatal(err)
	}
	waitState(t, args.ID, func(s snapshot) bool { return s.Downloaded > 0 })
	if err := Pause(args.ID); err != nil {
		t.Fatal(err)
	}
	if err := Close(); err != nil {
		t.Fatal(err)
	}
	if err := Open(storage, payload, key); err != nil {
		t.Fatal(err)
	}
	args.ConnectionProfile, args.Connections, args.SpeedLimit = "baidu_public", 64, 0
	raw, _ = json.Marshal(args)
	if err := Begin(string(raw)); err != nil {
		t.Fatal(err)
	}
	state := waitState(t, args.ID, func(s snapshot) bool { return s.Status == "done" }, 45*time.Second)
	assertPayload(t, state, f.data)
	if state.TotalConnections != 64 || f.peak.Load() < 2 || f.peak.Load() > 64 {
		t.Fatalf("public resume retained account connection limit: %+v, peak=%d", state, f.peak.Load())
	}
}
