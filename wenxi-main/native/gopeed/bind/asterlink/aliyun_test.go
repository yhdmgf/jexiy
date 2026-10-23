package gopeed

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	fhttp "github.com/GopeedLab/gopeed/pkg/protocol/http"
)

func TestAliyunResumeAppliesNewCeilingWithoutReplacingCheckpoint(t *testing.T) {
	openTestCore(t)
	f := serveFile(t, 2*1024*1024, 20*time.Millisecond)
	group := strings.Repeat("a", 64)
	begin := func(connections int) {
		t.Helper()
		raw, _ := json.Marshal(request{ID: "aliyun-resume", URL: f.server.URL + "/file", Headers: map[string]string{"Cookie": "test-cookie=local-only"}, Connections: connections, ConnectionProfile: "aliyun", AliyunGroup: group, Retries: 3, SpeedLimit: 1024 * 1024})
		if err := Begin(string(raw)); err != nil {
			t.Fatal(err)
		}
	}
	begin(64)
	waitState(t, "aliyun-resume", func(s snapshot) bool { return s.Downloaded > 0 && s.Status != "done" })
	if err := Pause("aliyun-resume"); err != nil {
		t.Fatal(err)
	}
	oldID := taskIDs["aliyun-resume"]
	paused := waitState(t, "aliyun-resume", func(s snapshot) bool { return s.Status == "pause" })
	begin(1)
	if taskIDs["aliyun-resume"] != oldID {
		t.Fatal("resume replaced the checkpoint")
	}
	metadata := core.GetTask(oldID).Meta
	options := metadata.Opts.Extra.(*fhttp.OptsExtra)
	if options.Connections != 1 || options.AliyunGroup != group {
		t.Fatal("Aliyun resume ignored the new concurrency ceiling")
	}
	result := waitState(t, "aliyun-resume", func(s snapshot) bool {
		if s.Downloaded < paused.Downloaded {
			t.Error("resume lost completed bytes")
		}
		if s.ActiveConnections > 1 {
			t.Error("resumed old ranges exceeded one live request")
		}
		if s.Aliyun == nil || s.Aliyun.Limit != 1 {
			t.Error("Aliyun runtime limit missing")
		}
		return s.Status == "done"
	})
	assertPayload(t, result, f.data)
}

func TestOtherProfilesKeepTheirResumeOptions(t *testing.T) {
	openTestCore(t)
	f := serveFile(t, 2*1024*1024, 20*time.Millisecond)
	startFile(t, "ordinary-resume", f.server.URL+"/file", 16, 1024*1024)
	waitState(t, "ordinary-resume", func(s snapshot) bool { return s.Downloaded > 0 && s.Status != "done" })
	if err := Pause("ordinary-resume"); err != nil {
		t.Fatal(err)
	}
	startFile(t, "ordinary-resume", f.server.URL+"/refreshed", 1, 0)
	options := core.GetTask(taskIDs["ordinary-resume"]).Meta.Opts.Extra.(*fhttp.OptsExtra)
	if options.Connections != 16 {
		t.Fatal("Aliyun changes affected ordinary download options")
	}
	result := waitState(t, "ordinary-resume", func(s snapshot) bool { return s.Status == "done" })
	if result.Aliyun != nil {
		t.Fatal("ordinary task received an Aliyun policy")
	}
	assertPayload(t, result, f.data)
}
