package http

import (
	"bytes"
	"context"
	"errors"
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

func TestAliyunRangesStartBeforeFirstResponseHeaders(t *testing.T) {
	const workers = 4
	data := bytes.Repeat([]byte("original-file-range-content\n"), 128*1024)
	arrived := make(chan struct{})
	var requests atomic.Int32
	var serialized atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var first, last int
		if _, err := fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &first, &last); err != nil || first < 0 || last < first || last >= len(data) {
			w.WriteHeader(http.StatusRequestedRangeNotSatisfiable)
			return
		}
		if first != 0 || last != 0 {
			if requests.Add(1) == workers {
				close(arrived)
			}
			select {
			case <-arrived:
			case <-time.After(time.Second):
				serialized.Store(true)
			case <-r.Context().Done():
				return
			}
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", first, last, len(data)))
		w.Header().Set("Content-Length", fmt.Sprint(last-first+1))
		w.WriteHeader(http.StatusPartialContent)
		_, _ = w.Write(data[first : last+1])
	}))
	defer server.Close()
	f := buildFetcher()
	defer f.Close()
	if err := f.Resolve(&base.Request{URL: server.URL}); err != nil {
		t.Fatal(err)
	}
	if err := f.Create(&base.Options{Name: "parallel.bin", Path: t.TempDir(), Extra: &fhttp.OptsExtra{Connections: 8, ConnectionProfile: "aliyun", AliyunGroup: t.Name()}}); err != nil {
		t.Fatal(err)
	}
	if err := f.Start(); err != nil {
		t.Fatal(err)
	}
	if err := f.Wait(); err != nil {
		t.Fatal(err)
	}
	if serialized.Load() {
		t.Error("other Aliyun ranges waited for the first response headers")
	}
	got, err := os.ReadFile(f.Meta().SingleFilepath())
	if err != nil || !bytes.Equal(data, got) {
		t.Fatal("parallel original-file download did not preserve every byte")
	}
}

func TestAliyunAccountBudgetGrowthAndBurstBackoff(t *testing.T) {
	first := newAliyunTask(t.Name(), 8)
	second := newAliyunTask(t.Name(), 8)
	independent := newAliyunTask(t.Name()+"-other", 8)
	var leases []*aliyunLease
	t.Cleanup(func() {
		for _, lease := range leases {
			lease.release()
		}
	})
	for i := 0; i < 4; i++ {
		lease, err := first.acquire(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		leases = append(leases, lease)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Millisecond)
	defer cancel()
	if lease, err := second.acquire(ctx); !errors.Is(err, context.DeadlineExceeded) {
		if lease != nil {
			lease.release()
		}
		t.Fatal("same account escaped its initial four slots")
	}
	other, err := independent.acquire(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	other.release()
	leases[0].progress(256*1024, time.Now().Add(aliyunGrowthPeriod))
	if first.state().Limit != 8 {
		t.Fatal("healthy progress did not grow to eight")
	}
	for i := 0; i < 4; i++ {
		lease, err := second.acquire(context.Background())
		if err != nil {
			t.Fatal(err)
		}
		leases = append(leases, lease)
	}
	for _, lease := range leases {
		lease.failed(NewRequestError(503, "busy"), 0, time.Now())
	}
	if first.state().Limit != 4 {
		t.Fatal("one burst caused more than one reduction")
	}
	for _, lease := range leases {
		lease.release()
	}
	lease, err := second.acquire(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	leases = append(leases, lease)
	lease.failed(NewRequestError(429, "busy again"), 0, time.Now())
	if first.state().Limit != 2 {
		t.Fatal("a new failed burst did not reduce to two")
	}
	lease.release()
	lease, err = first.acquire(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	leases = append(leases, lease)
	lease.failed(NewRequestError(503, "busy again"), 0, time.Now())
	if first.state().Limit != 1 {
		t.Fatal("repeated congestion did not reduce to one")
	}
	lease.release()
	healthy, err := second.acquire(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	leases = append(leases, healthy)
	healthy.progress(1024*1024, time.Now().Add(aliyunGrowthPeriod))
	if first.state().Limit != 1 {
		t.Fatal("concurrency recovered before the cooldown ended")
	}
	healthy.progress(1024*1024, time.Now().Add(aliyunRecoveryPeriod+time.Second))
	if first.state().Limit != 2 {
		t.Fatal("stable progress did not gradually recover")
	}
}

func TestAliyunCooldownCancellationAndPerTaskCeiling(t *testing.T) {
	task := newAliyunTask(t.Name(), 1)
	lease, err := task.acquire(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	defer lease.release()
	if !task.state().Queued {
		t.Fatal("waiting for the global connection budget was reported as a stall")
	}
	lease.begin()
	if task.state().Queued {
		t.Fatal("an in-flight request was still reported as queued")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	if extra, err := task.acquire(ctx); !errors.Is(err, context.DeadlineExceeded) {
		if extra != nil {
			extra.release()
		}
		t.Fatal("old checkpoint exceeded its new single-connection ceiling")
	}
	cancel()
	failure := NewRequestError(429, "wait")
	failure.RetryAfter = time.Now().Add(2 * time.Second)
	lease.failed(failure, 0, time.Now())
	lease.release()
	if task.state().WaitMS < 1500 {
		t.Fatal("Retry-After was not preserved")
	}
	ctx, cancel = context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	if extra, err := task.acquire(ctx); !errors.Is(err, context.DeadlineExceeded) {
		if extra != nil {
			extra.release()
		}
		t.Fatal("server cooldown was ignored")
	}
	if task.state().Queued {
		t.Fatal("cancelled waiter leaked an account slot")
	}
}

func TestAliyunHTTPFailuresReduceConcurrencyAndHonorRetryAfter(t *testing.T) {
	for _, code := range []int{429, 503} {
		t.Run(fmt.Sprint(code), func(t *testing.T) {
			data := bytes.Repeat([]byte{0x17, 0x35, 0x62, 0x4a}, 2*1024*1024)
			arrived := make(chan struct{})
			var requests atomic.Int32
			var rejectedAt atomic.Int64
			var earlyRequest atomic.Bool
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				var first, last int
				if _, err := fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &first, &last); err != nil || first < 0 || last < first || last >= len(data) {
					w.WriteHeader(416)
					return
				}
				if first != 0 || last != 0 {
					if number := requests.Add(1); number <= 4 {
						if number == 4 {
							close(arrived)
						}
						select {
						case <-arrived:
						case <-r.Context().Done():
							return
						}
						rejectedAt.Store(time.Now().UnixNano())
						w.Header().Set("Retry-After", "3")
						w.WriteHeader(code)
						return
					}
					if time.Since(time.Unix(0, rejectedAt.Load())) < 2800*time.Millisecond {
						earlyRequest.Store(true)
					}
				}
				w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", first, last, len(data)))
				w.Header().Set("Content-Length", fmt.Sprint(last-first+1))
				w.WriteHeader(206)
				_, _ = w.Write(data[first : last+1])
			}))
			defer server.Close()
			f := buildFetcher()
			defer f.Close()
			if err := f.Resolve(&base.Request{URL: server.URL}); err != nil {
				t.Fatal(err)
			}
			if err := f.Create(&base.Options{Name: "backoff.bin", Path: t.TempDir(), Extra: &fhttp.OptsExtra{Connections: 4, ConnectionProfile: "aliyun", AliyunGroup: t.Name()}}); err != nil {
				t.Fatal(err)
			}
			if err := f.Start(); err != nil {
				t.Fatal(err)
			}
			if err := f.Wait(); err != nil {
				t.Fatal(err)
			}
			if earlyRequest.Load() || f.AliyunTransferState().Limit != 2 {
				t.Fatal("HTTP error did not reach the account backoff policy")
			}
			got, err := os.ReadFile(f.Meta().SingleFilepath())
			if err != nil || !bytes.Equal(got, data) {
				t.Fatal("retry did not preserve the original file")
			}
		})
	}
}

func TestAliyunInterruptedWindowsRetainProgress(t *testing.T) {
	data := bytes.Repeat([]byte{0x71, 0x19, 0x3a, 0xcf}, 8*1024*1024)
	var interruptions atomic.Int32
	var invalidWindow atomic.Bool
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var first, last int
		if _, err := fmt.Sscanf(r.Header.Get("Range"), "bytes=%d-%d", &first, &last); err != nil || first < 0 || last < first || last >= len(data) {
			w.WriteHeader(416)
			return
		}
		if last-first+1 > int(aliyunRequestSize) || first/int(aliyunRequestSize) != last/int(aliyunRequestSize) {
			invalidWindow.Store(true)
		}
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", first, last, len(data)))
		w.Header().Set("Content-Length", fmt.Sprint(last-first+1))
		w.WriteHeader(206)
		end := last + 1
		if end-first > 1024*1024 {
			end = first + 1024*1024
			interruptions.Add(1)
		}
		_, _ = w.Write(data[first:end])
	}))
	defer server.Close()
	f := buildFetcher()
	defer f.Close()
	if err := f.Resolve(&base.Request{URL: server.URL}); err != nil {
		t.Fatal(err)
	}
	retries := 3
	if err := f.Create(&base.Options{Name: "interrupted.bin", Path: t.TempDir(), Extra: &fhttp.OptsExtra{Connections: 4, ConnectionProfile: "aliyun", AliyunGroup: t.Name(), RetryLimit: &retries}}); err != nil {
		t.Fatal(err)
	}
	if err := f.Start(); err != nil {
		t.Fatal(err)
	}
	if err := f.Wait(); err != nil {
		t.Fatal(err)
	}
	got, err := os.ReadFile(f.Meta().SingleFilepath())
	if err != nil || !bytes.Equal(got, data) {
		t.Fatal("interrupted download did not preserve every byte")
	}
	if interruptions.Load() < 4 || invalidWindow.Load() {
		t.Fatal("fixed request boundaries were not exercised")
	}
	if f.AliyunTransferState().Limit > 2 {
		t.Fatal("repeated interrupted responses did not reduce concurrency")
	}
}
