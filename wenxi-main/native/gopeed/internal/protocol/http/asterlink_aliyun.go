package http

import (
	"context"
	"errors"
	"io"
	"net"
	"sync"
	"time"

	fhttp "github.com/GopeedLab/gopeed/pkg/protocol/http"
)

const aliyunRequestSize int64 = 4 * 1024 * 1024
const aliyunGrowthPeriod = 15 * time.Second
const aliyunRecoveryPeriod = 60 * time.Second

// Boundaries stay fixed across retries. Moving the end forward with each
// partial response would consume the same retry window indefinitely.
func aliyunRangeEnd(first, last int64) int64 {
	remaining := aliyunRequestSize - first%aliyunRequestSize
	if last-first >= remaining {
		return first + remaining - 1
	}
	return last
}

type aliyunBudget struct {
	mu                             sync.Mutex
	active, limit, networkFailures int
	generation                     uint64
	healthyBytes                   int64
	nextGrowth, holdUntil, lastUse time.Time
	changed                        chan struct{}
	queue                          []*aliyunWaiter
}

type aliyunTask struct {
	budget                             *aliyunBudget
	maximum, active, waiting, inFlight int
	retryUntil                         time.Time
}

type aliyunWaiter struct{ task *aliyunTask }

type aliyunLease struct {
	task       *aliyunTask
	generation uint64
	started    bool
	once       sync.Once
}

var aliyunBudgets = struct {
	sync.Mutex
	groups map[string]*aliyunBudget
}{groups: make(map[string]*aliyunBudget)}

func newAliyunTask(group string, maximum int) *aliyunTask {
	// Missing account information in older checkpoints uses one conservative
	// shared group; signed URLs and CDN host changes never create extra slots.
	if group == "" {
		group = "legacy"
	}
	now := time.Now()
	aliyunBudgets.Lock()
	defer aliyunBudgets.Unlock()
	for key, entry := range aliyunBudgets.groups {
		entry.mu.Lock()
		idle := entry.active == 0 && len(entry.queue) == 0 && now.Sub(entry.lastUse) > 5*time.Minute
		entry.mu.Unlock()
		if idle {
			delete(aliyunBudgets.groups, key)
		}
	}
	entry := aliyunBudgets.groups[group]
	if entry == nil {
		entry = &aliyunBudget{limit: 4, nextGrowth: now.Add(aliyunGrowthPeriod), lastUse: now, changed: make(chan struct{})}
		aliyunBudgets.groups[group] = entry
	}
	return &aliyunTask{budget: entry, maximum: max(1, min(aliyunConnections, maximum))}
}

func (b *aliyunBudget) notifyLocked() {
	close(b.changed)
	b.changed = make(chan struct{})
}

func (b *aliyunBudget) removeWaiterLocked(waiter *aliyunWaiter) {
	for i, current := range b.queue {
		if current == waiter {
			b.queue = append(b.queue[:i], b.queue[i+1:]...)
			waiter.task.waiting--
			return
		}
	}
}

func (b *aliyunBudget) firstEligibleLocked() *aliyunWaiter {
	for _, waiter := range b.queue {
		if waiter.task.active < waiter.task.maximum {
			return waiter
		}
	}
	return nil
}

func (task *aliyunTask) acquire(ctx context.Context) (*aliyunLease, error) {
	b := task.budget
	waiter := &aliyunWaiter{task: task}
	b.mu.Lock()
	b.queue = append(b.queue, waiter)
	task.waiting++
	for {
		now := time.Now()
		b.lastUse = now
		if err := ctx.Err(); err != nil {
			b.removeWaiterLocked(waiter)
			b.notifyLocked()
			b.mu.Unlock()
			return nil, err
		}
		if !b.holdUntil.After(now) && b.active < b.limit && b.firstEligibleLocked() == waiter {
			b.removeWaiterLocked(waiter)
			b.active++
			task.active++
			lease := &aliyunLease{task: task, generation: b.generation}
			b.notifyLocked()
			b.mu.Unlock()
			return lease, nil
		}
		changed := b.changed
		var timer *time.Timer
		var expired <-chan time.Time
		if remaining := b.holdUntil.Sub(now); remaining > 0 {
			timer = time.NewTimer(remaining)
			expired = timer.C
		}
		b.mu.Unlock()
		select {
		case <-ctx.Done():
		case <-changed:
		case <-expired:
		}
		if timer != nil {
			timer.Stop()
		}
		b.mu.Lock()
	}
}

func (lease *aliyunLease) release() {
	lease.once.Do(func() {
		b := lease.task.budget
		b.mu.Lock()
		b.active--
		lease.task.active--
		if lease.started {
			lease.task.inFlight--
		}
		b.lastUse = time.Now()
		b.notifyLocked()
		b.mu.Unlock()
	})
}

func (lease *aliyunLease) begin() {
	lease.task.budget.mu.Lock()
	lease.started = true
	lease.task.inFlight++
	lease.task.budget.mu.Unlock()
}

func (lease *aliyunLease) progress(bytes int64, now time.Time) {
	if bytes <= 0 {
		return
	}
	b := lease.task.budget
	b.mu.Lock()
	defer b.mu.Unlock()
	b.lastUse = now
	if lease.generation != b.generation || b.holdUntil.After(now) {
		return
	}
	b.healthyBytes += bytes
	if !now.Before(b.nextGrowth) && b.healthyBytes >= 256*1024 {
		b.limit = min(aliyunConnections, b.limit*2)
		b.networkFailures = 0
		b.healthyBytes = 0
		b.nextGrowth = now.Add(aliyunGrowthPeriod)
		b.notifyLocked()
	}
}

func (lease *aliyunLease) failed(err error, delay time.Duration, now time.Time) {
	if err == nil || errors.Is(err, context.Canceled) {
		return
	}
	var status *RequestError
	var network net.Error
	congested := errors.As(err, &status) && (status.Code == 429 || status.Code == 503)
	interrupted := errors.Is(err, io.ErrUnexpectedEOF) || errors.As(err, &network)
	if !congested && !interrupted {
		return
	}
	b := lease.task.budget
	b.mu.Lock()
	defer b.mu.Unlock()
	b.lastUse = now
	b.healthyBytes = 0
	b.nextGrowth = now.Add(aliyunRecoveryPeriod)
	if congested {
		until := now.Add(delay)
		if status.RetryAfter.After(until) {
			until = status.RetryAfter
		}
		if until.After(b.holdUntil) {
			b.holdUntil = until
		}
	}
	// Responses from one burst share an epoch: eight simultaneous failures
	// must not count as eight independent reasons to reduce concurrency.
	if lease.generation == b.generation {
		if interrupted {
			b.networkFailures++
		}
		if congested || b.networkFailures >= 2 {
			b.limit = max(1, b.limit/2)
			b.networkFailures = 0
			b.generation++
		}
	}
	b.notifyLocked()
}

func (task *aliyunTask) state() *fhttp.AliyunTransferState {
	b := task.budget
	b.mu.Lock()
	defer b.mu.Unlock()
	until := b.holdUntil
	if task.retryUntil.After(until) {
		until = task.retryUntil
	}
	return &fhttp.AliyunTransferState{
		Limit:  min(task.maximum, b.limit),
		WaitMS: max(0, time.Until(until).Milliseconds()),
		Queued: task.inFlight == 0 && (task.waiting > 0 || task.active > 0),
	}
}

func (task *aliyunTask) retryWait(until time.Time) {
	task.budget.mu.Lock()
	if until.After(task.retryUntil) {
		task.retryUntil = until
	}
	task.budget.mu.Unlock()
}

func (f *Fetcher) AliyunTransferState() *fhttp.AliyunTransferState {
	if task := f.aliyun.Load(); task != nil {
		return task.state()
	}
	return nil
}

// PrepareAliyunTransfer is called before Start, while the fetcher is paused.
// Publish the new ceiling before the resume event can report the old policy.
func (f *Fetcher) PrepareAliyunTransfer(group string, maximum int) {
	f.aliyun.Store(newAliyunTask(group, maximum))
}
