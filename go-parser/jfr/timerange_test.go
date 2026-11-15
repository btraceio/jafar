package jfr

import (
	"math"
	"reflect"
	"sort"
	"testing"
)

const (
	rangeSecond = int64(1_000_000_000)
	// Chunks of the range recording are 10 s apart and last 5 s.
	rangeChunkGap      = 10 * rangeSecond
	rangeChunkDuration = 5 * rangeSecond
)

// rangeChunk builds one chunk of the range recording: it starts at startNanos,
// lasts rangeChunkDuration, and holds one test.Sample per entry of
// eventNanos, starting at that time. Times before startNanos are allowed and
// encode as ticks before the chunk's start tick.
func rangeChunk(startNanos int64, metadata *metaElement, duration int64, eventNanos ...int64) []byte {
	var events [][]byte
	for _, at := range eventNanos {
		events = append(events, sampleEvent{
			startTicks: testStartTicks + (at-startNanos)/nanosPerTick,
			name:       nullString(),
			nestedB:    utf8String(""),
		}.encode())
	}
	return chunkSpec{
		metadata:   metadata,
		events:     events,
		startNanos: startNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   duration,
	}.build()
}

func rangeChunkStart(i int) int64 { return testStartNanos + int64(i)*rangeChunkGap }
func rangeChunkEnd(i int) int64   { return rangeChunkStart(i) + rangeChunkDuration }

// rangeRecording is three chunks. Each holds two events inside its own span;
// the last also holds one stamped inside the first chunk's span, as a profiler
// does that emits an event when the chunk is finished with the time of what it
// describes (datadog.HeapLiveObject in a Datadog recording, 36 minutes early).
func rangeRecording() []byte {
	var data []byte
	for i := 0; i < 3; i++ {
		at := []int64{rangeChunkStart(i) + rangeSecond, rangeChunkStart(i) + 2*rangeSecond}
		if i == 2 {
			at = append(at, rangeChunkStart(0)+3*rangeSecond)
		}
		data = append(data, rangeChunk(rangeChunkStart(i), testMetadata(), rangeChunkDuration, at...)...)
	}
	return data
}

type rangeOutcome struct {
	startTimes []int64
	read       []int
	skipped    []int
}

func (o *rangeOutcome) within(r *TimeRange) []int64 {
	out := []int64{}
	for _, t := range o.startTimes {
		if r == nil || r.Contains(t) {
			out = append(out, t)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i] < out[j] })
	return out
}

func parseRange(t *testing.T, data []byte, opts Options) *rangeOutcome {
	t.Helper()
	o := &rangeOutcome{}
	opts.OnError = FailOnError
	opts.OnChunkStart = func(ci *ChunkInfo, _ *Metadata) error {
		o.read = append(o.read, ci.ID())
		return nil
	}
	opts.OnChunkSkipped = func(ci *ChunkInfo, _ *Metadata) error {
		o.skipped = append(o.skipped, ci.ID())
		return nil
	}
	if err := NewParser(data).ParseWith(opts, func(e *Event) error {
		o.startTimes = append(o.startTimes, e.Values["startTime"].(int64))
		return nil
	}); err != nil {
		t.Fatalf("parse: %v", err)
	}
	return o
}

func TestChunkCanHoldMatch(t *testing.T) {
	start, duration := 100*rangeSecond, 10*rangeSecond
	end := start + duration
	cases := []struct {
		name     string
		r        *TimeRange
		duration int64
		want     bool
	}{
		{"ends before the range, past the margin", Since(end + chunkEndMargin + 1), duration, false},
		{"ends before the range, within the margin", Since(end + chunkEndMargin), duration, true},
		{"range starts at the chunk end", Since(end), duration, true},
		{"overlapping", Between(start+rangeSecond, start+2*rangeSecond), duration, true},
		// an event is written when it is committed: it can start long before its chunk
		{"starts after the range", Until(start - chunkEndMargin - 1), duration, true},
		{"starts long after the range", Between(0, start-3600*rangeSecond), duration, true},
		{"unfinished chunk", Since(math.MaxInt64 - 1), 0, true},
		{"negative duration", Since(math.MaxInt64 - 1), -5, true},
	}
	for _, c := range cases {
		if got := chunkCanHoldMatch(c.r, start, c.duration); got != c.want {
			t.Errorf("%s: chunkCanHoldMatch(%+v) = %v, want %v", c.name, *c.r, got, c.want)
		}
	}
	// start + duration overflows; saturated, the chunk still ends after any lower bound
	if !chunkCanHoldMatch(Since(math.MaxInt64-5), math.MaxInt64-10, math.MaxInt64) {
		t.Error("an overflowing chunk end was taken for an early one")
	}
}

func TestStartTimeSkipsChunksThatEndBeforeTheRange(t *testing.T) {
	data := rangeRecording()

	o := parseRange(t, data, Options{StartTime: Since(rangeChunkStart(2))})
	if want := []int{1, 2}; !reflect.DeepEqual(o.skipped, want) {
		t.Errorf("skipped chunks = %v, want %v", o.skipped, want)
	}
	if want := []int{3}; !reflect.DeepEqual(o.read, want) {
		t.Errorf("read chunks = %v, want %v", o.read, want)
	}
	// a hint, not a filter: the early-stamped event of the chunk that was read is delivered
	if len(o.startTimes) != 3 {
		t.Errorf("delivered %d events, want the 3 of the last chunk", len(o.startTimes))
	}

	// the margin: one second after the chunk's end still reads it, one nanosecond more does not
	o = parseRange(t, data, Options{StartTime: Since(rangeChunkEnd(2) + chunkEndMargin)})
	if want := []int{3}; !reflect.DeepEqual(o.read, want) {
		t.Errorf("at the margin: read chunks = %v, want %v", o.read, want)
	}
	o = parseRange(t, data, Options{StartTime: Since(rangeChunkEnd(2) + chunkEndMargin + 1)})
	if len(o.read) != 0 || len(o.skipped) != 3 || len(o.startTimes) != 0 {
		t.Errorf("past the margin: read %v, skipped %v, %d events; want everything skipped",
			o.read, o.skipped, len(o.startTimes))
	}
}

func TestStartTimeNeverSkipsChunksThatStartAfterTheRange(t *testing.T) {
	data := rangeRecording()
	early := Until(rangeChunkEnd(0))

	o := parseRange(t, data, Options{StartTime: early})
	if len(o.skipped) != 0 {
		t.Fatalf("skipped chunks %v: a later chunk can hold an event that starts in the range", o.skipped)
	}
	// the two events of the first chunk and the one the last chunk holds from that time
	want := []int64{
		rangeChunkStart(0) + rangeSecond,
		rangeChunkStart(0) + 2*rangeSecond,
		rangeChunkStart(0) + 3*rangeSecond,
	}
	if got := o.within(early); !reflect.DeepEqual(got, want) {
		t.Errorf("events in the range = %v, want %v", got, want)
	}
}

// Skipping chunks must never lose an event that starts in the range.
func TestStartTimeNeverLosesAnEventInTheRange(t *testing.T) {
	data := rangeRecording()
	all := parseRange(t, data, Options{})
	if len(all.startTimes) != 7 || len(all.skipped) != 0 {
		t.Fatalf("reference parse: %d events, skipped %v", len(all.startTimes), all.skipped)
	}

	var times []int64
	for i := 0; i < 3; i++ {
		for _, edge := range []int64{rangeChunkStart(i), rangeChunkEnd(i)} {
			for _, d := range []int64{0, 1, chunkEndMargin, chunkEndMargin + 1, 2 * chunkEndMargin} {
				times = append(times, edge+d, edge-d)
			}
		}
	}
	var ranges []*TimeRange
	for _, a := range times {
		ranges = append(ranges, Since(a), Until(a))
		for _, b := range times {
			ranges = append(ranges, Between(a, b))
		}
	}
	for _, r := range ranges {
		ranged := parseRange(t, data, Options{StartTime: r})
		if got, want := ranged.within(r), all.within(r); !reflect.DeepEqual(got, want) {
			t.Fatalf("range %+v: events in range = %v, want %v (skipped chunks %v)", *r, got, want, ranged.skipped)
		}
	}
}

func TestStartTimeReadsAnUnfinishedChunk(t *testing.T) {
	at := testStartNanos + rangeSecond
	data := rangeChunk(testStartNanos, testMetadata(), 0, at)
	o := parseRange(t, data, Options{StartTime: Since(at + 3600*rangeSecond)})
	if len(o.skipped) != 0 || len(o.startTimes) != 1 {
		t.Errorf("skipped %v, %d events; a chunk without a duration has no known end", o.skipped, len(o.startTimes))
	}
}

// A startTime that is not a tick timestamp is not converted to epoch
// nanoseconds, so the chunk header says nothing about its values.
func TestStartTimeReadsAChunkWhoseStartTimeIsNotATickTimestamp(t *testing.T) {
	md := testMetadata()
	if !dropStartTimeAnnotation(md) {
		t.Fatal("test.Sample.startTime has no annotation to drop; the fixture changed")
	}
	at := testStartNanos + rangeSecond
	far := Since(at + 3600*rangeSecond)

	o := parseRange(t, rangeChunk(testStartNanos, md, rangeChunkDuration, at), Options{StartTime: far})
	if len(o.skipped) != 0 || len(o.startTimes) != 1 {
		t.Errorf("skipped %v, %d events; want the chunk read", o.skipped, len(o.startTimes))
	}
	// the same chunk with the annotation in place is skipped: the annotation is what decides
	o = parseRange(t, rangeChunk(testStartNanos, testMetadata(), rangeChunkDuration, at), Options{StartTime: far})
	if len(o.skipped) != 1 {
		t.Errorf("with a tick timestamp: skipped %v, want the chunk skipped", o.skipped)
	}
	// and a type filter that does not want the type takes it out of the decision
	o = parseRange(t, rangeChunk(testStartNanos, md, rangeChunkDuration, at), Options{
		StartTime:  far,
		TypeFilter: func(c *ClassType) bool { return c.Name != "test.Sample" },
	})
	if len(o.skipped) != 1 {
		t.Errorf("type filtered out: skipped %v, want the chunk skipped", o.skipped)
	}
}

// dropStartTimeAnnotation removes the annotations of test.Sample's startTime
// field and reports whether it found any.
func dropStartTimeAnnotation(e *metaElement) bool {
	if e.name == "field" {
		for _, a := range e.attributes {
			if a[0] == "name" && a[1] == "startTime" && len(e.children) > 0 {
				e.children = nil
				return true
			}
		}
		return false
	}
	for _, c := range e.children {
		if dropStartTimeAnnotation(c) {
			return true
		}
	}
	return false
}

func TestOnChunkSkippedCanStopParsing(t *testing.T) {
	calls := 0
	err := NewParser(rangeRecording()).ParseWith(Options{
		StartTime: Since(rangeChunkStart(2)),
		OnChunkSkipped: func(*ChunkInfo, *Metadata) error {
			calls++
			return ErrStop
		},
		OnError: FailOnError,
	}, func(*Event) error {
		t.Error("an event was delivered after ErrStop")
		return nil
	})
	if err != nil || calls != 1 {
		t.Errorf("err = %v, OnChunkSkipped calls = %d; want nil and 1", err, calls)
	}
}
