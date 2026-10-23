package gopeed

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func beginCacheTest(t *testing.T, input request) {
	t.Helper()
	raw, err := json.Marshal(input)
	if err != nil {
		t.Fatal(err)
	}
	if err := Begin(string(raw)); err != nil {
		t.Fatal(err)
	}
}

func TestTaskCacheRootsHTTPAndRestart(t *testing.T) {
	storage, legacy, key := openTestCore(t)
	cache := filepath.Join(t.TempDir(), destinationCacheName)
	f := serveFile(t, 2*1024*1024, 0)
	input := request{ID: "custom", CacheRoot: cache, URL: f.server.URL + "/file", Headers: map[string]string{"Cookie": "test-cookie=local-only"}, Connections: 4, Retries: 1, SpeedLimit: 512 * 1024}
	beginCacheTest(t, input)
	state := waitState(t, input.ID, func(s snapshot) bool { return s.Downloaded > 0 && s.Status != "done" })
	if filepath.Clean(state.Path) != filepath.Join(cache, input.ID, "payload.gopeed") {
		t.Fatalf("wrong cache path: %s", state.Path)
	}
	if _, err := os.Stat(filepath.Join(legacy, input.ID)); !os.IsNotExist(err) {
		t.Fatal("payload unexpectedly created in the legacy cache")
	}
	if err := Pause(input.ID); err != nil {
		t.Fatal(err)
	}
	oldID := taskIDs[input.ID]
	if err := Close(); err != nil {
		t.Fatal(err)
	}
	if err := Open(storage, legacy, key); err != nil {
		t.Fatal(err)
	}
	input.SpeedLimit = 0
	input.URL = f.server.URL + "/refreshed"
	beginCacheTest(t, input)
	if taskIDs[input.ID] != oldID {
		t.Fatal("changing process discarded the native checkpoint")
	}
	assertPayload(t, waitState(t, input.ID, func(s snapshot) bool { return s.Status == "done" }), f.data)
	startFile(t, "legacy", f.server.URL+"/file", 2, 0)
	legacyState := waitState(t, "legacy", func(s snapshot) bool { return s.Status == "done" })
	assertPayload(t, legacyState, f.data)
	if filepath.Clean(legacyState.Path) != filepath.Join(legacy, "legacy", "payload.gopeed") {
		t.Fatal("old tasks changed their original cache root")
	}
}

func TestTaskCacheRootsTorrent(t *testing.T) {
	_, legacy, _ := openTestCore(t)
	cache := filepath.Join(t.TempDir(), destinationCacheName)
	data, want := torrentFixture(t, 256*1024)
	beginCacheTest(t, request{ID: "custom-bt", CacheRoot: cache, TorrentData: data, TorrentIndex: 1, Connections: 32})
	state := waitState(t, "custom-bt", func(s snapshot) bool { return s.Status == "done" })
	assertPayload(t, state, want)
	rel, err := filepath.Rel(filepath.Join(cache, "custom-bt"), state.Path)
	if err != nil || !filepath.IsLocal(rel) {
		t.Fatalf("BT payload escaped chosen directory: %s", state.Path)
	}
	if _, err := os.Stat(filepath.Join(legacy, "custom-bt")); !os.IsNotExist(err) {
		t.Fatal("BT payload unexpectedly created in legacy cache")
	}
}

func TestTaskCacheRootsRejectUnsafeDirectories(t *testing.T) {
	_, legacy, _ := openTestCore(t)
	root := t.TempDir()
	for _, cache := range []string{"relative/.asterlink-cache", root, filepath.Join(root, "downloads")} {
		if _, err := taskPayloadDirectory(request{ID: "task", CacheRoot: cache}); err == nil {
			t.Fatalf("accepted unsafe cache root %s", cache)
		}
	}
	if _, err := taskPayloadDirectory(request{ID: "../outside", CacheRoot: filepath.Join(root, destinationCacheName)}); err == nil {
		t.Fatal("accepted task traversal")
	}
	keep := filepath.Join(root, "keep.txt")
	if err := os.WriteFile(keep, []byte("keep"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := clearTaskPayload(root); err == nil {
		t.Fatal("accepted an unrelated directory for deletion")
	}
	if _, err := os.Stat(keep); err != nil {
		t.Fatal("unrelated file was removed")
	}
	if err := os.Symlink(root, filepath.Join(legacy, "linked-task")); err != nil {
		t.Skipf("symlink creation unavailable: %v", err)
	}
	if _, err := taskPayloadDirectory(request{ID: "linked-task"}); err == nil {
		t.Fatal("accepted a linked task directory")
	}
	if err := clearTaskPayload(filepath.Join(legacy, "linked-task")); err == nil {
		t.Fatal("accepted linked directory cleanup")
	}
	if _, err := os.Stat(keep); err != nil {
		t.Fatal("linked target was modified")
	}
}

func TestTaskCacheRootsPreserveRedirectedLegacyRoot(t *testing.T) {
	storage, legacy, key := openTestCore(t)
	if err := Close(); err != nil {
		t.Fatal(err)
	}
	linkedRoot := filepath.Join(filepath.Dir(legacy), "redirected-cache")
	if err := os.Symlink(legacy, linkedRoot); err != nil {
		t.Skipf("symlink creation unavailable: %v", err)
	}
	if err := Open(storage, linkedRoot, key); err != nil {
		t.Fatal(err)
	}
	f := serveFile(t, 8192, 0)
	startFile(t, "legacy", f.server.URL+"/file", 1, 0)
	assertPayload(t, waitState(t, "legacy", func(s snapshot) bool { return s.Status == "done" }), f.data)
}
