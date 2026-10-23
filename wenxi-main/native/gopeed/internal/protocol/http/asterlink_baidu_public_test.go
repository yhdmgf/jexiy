package http

import (
	"bytes"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"sync/atomic"
	"testing"
	"time"

	"github.com/GopeedLab/gopeed/pkg/base"
	fhttp "github.com/GopeedLab/gopeed/pkg/protocol/http"
)

func TestBaiduPublicConnectionsPreserveCheckpoint(t *testing.T) {
	for _, scenario := range []struct {
		name             string
		saved, requested int
	}{{"new", 0, 64}, {"legacy-single", 1, 64}, {"reduced", 64, 8}} {
		t.Run(scenario.name, func(t *testing.T) {
			data := bytes.Repeat([]byte("public-baidu-data"), 2*1024*1024)
			var active, maximum atomic.Int32
			var received atomic.Int64
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var first, last int
				if _, err := fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &first, &last); err != nil || first < 0 || last < first || last >= len(data) {
					w.WriteHeader(416)
					return
				}
				w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", first, last, len(data)))
				w.Header().Set("Content-Length", fmt.Sprint(last-first+1))
				w.WriteHeader(206)
				if first != 0 || last != 0 {
					n := active.Add(1)
					defer active.Add(-1)
					for previous := maximum.Load(); n > previous && !maximum.CompareAndSwap(previous, n); previous = maximum.Load() {
					}
					w.(http.Flusher).Flush()
					time.Sleep(30 * time.Millisecond)
					received.Add(int64(last - first + 1))
				}
				_, _ = w.Write(data[first : last+1])
			}))
			defer server.Close()
			f := buildFetcher()
			defer f.Close()
			if err := f.Resolve(&base.Request{URL: server.URL}); err != nil {
				t.Fatal(err)
			}
			if err := f.Create(&base.Options{Name: "public.bin", Path: t.TempDir(), Extra: &fhttp.OptsExtra{Connections: scenario.requested, ConnectionProfile: "baidu_public"}}); err != nil {
				t.Fatal(err)
			}
			savedBytes := 0
			if scenario.saved > 0 {
				seed := make([]byte, len(data))
				for i := 0; i < scenario.saved; i++ {
					begin, end := len(data)*i/scenario.saved, len(data)*(i+1)/scenario.saved-1
					copy(seed[begin:begin+2048], data[begin:begin+2048])
					c := &connection{Chunk: newChunk(int64(begin), int64(end)), Downloaded: 2048}
					c.Chunk.Downloaded = 2048
					f.connections = append(f.connections, c)
					savedBytes += 2048
				}
				if err := os.WriteFile(f.Meta().SingleFilepath(), seed, 0600); err != nil {
					t.Fatal(err)
				}
			}
			if err := f.Start(); err != nil {
				t.Fatal(err)
			}
			if err := f.Wait(); err != nil {
				t.Fatal(err)
			}
			if maximum.Load() < 2 || maximum.Load() > int32(scenario.requested) {
				t.Fatalf("parallelism=%d, requested=%d", maximum.Load(), scenario.requested)
			}
			if _, total := f.ConnectionCounts(); total != scenario.requested {
				t.Fatalf("reported=%d", total)
			}
			if received.Load() != int64(len(data)-savedBytes) {
				t.Fatalf("received=%d expected=%d", received.Load(), len(data)-savedBytes)
			}
			got, err := os.ReadFile(f.Meta().SingleFilepath())
			if err != nil || !bytes.Equal(got, data) {
				t.Fatal("checkpoint expansion changed file contents")
			}
		})
	}
}

func TestBaiduPublicNoRangeStaysSingle(t *testing.T) {
	data := bytes.Repeat([]byte("no-range"), 65536)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", fmt.Sprint(len(data)))
		_, _ = w.Write(data)
	}))
	defer server.Close()
	f := buildFetcher()
	defer f.Close()
	if err := f.Resolve(&base.Request{URL: server.URL}); err != nil {
		t.Fatal(err)
	}
	if err := f.Create(&base.Options{Name: "single.bin", Path: t.TempDir(), Extra: &fhttp.OptsExtra{Connections: 64, ConnectionProfile: "baidu_public"}}); err != nil {
		t.Fatal(err)
	}
	if err := f.Start(); err != nil {
		t.Fatal(err)
	}
	if err := f.Wait(); err != nil {
		t.Fatal(err)
	}
	if _, total := f.ConnectionCounts(); total != 1 {
		t.Fatalf("non-range connection count=%d", total)
	}
	got, err := os.ReadFile(f.Meta().SingleFilepath())
	if err != nil || !bytes.Equal(got, data) {
		t.Fatal("non-range download changed contents")
	}
}
