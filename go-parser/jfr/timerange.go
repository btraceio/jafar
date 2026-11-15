package jfr

import "math"

// TimeRange is a range of event start times in nanoseconds since the epoch,
// both ends inclusive. It is the unit of a decoded event's startTime.
//
// Set as Options.StartTime it lets the parser skip whole chunks that cannot
// hold an event starting in the range.
type TimeRange struct {
	// From is the earliest start time in the range.
	From int64
	// To is the latest start time in the range.
	To int64
}

// Since returns the range of start times at or after fromEpochNanos.
func Since(fromEpochNanos int64) *TimeRange {
	return &TimeRange{From: fromEpochNanos, To: math.MaxInt64}
}

// Until returns the range of start times at or before toEpochNanos.
func Until(toEpochNanos int64) *TimeRange {
	return &TimeRange{From: math.MinInt64, To: toEpochNanos}
}

// Between returns the range of start times from fromEpochNanos to toEpochNanos,
// both included.
func Between(fromEpochNanos, toEpochNanos int64) *TimeRange {
	return &TimeRange{From: fromEpochNanos, To: toEpochNanos}
}

// Contains reports whether epochNanos is in the range.
func (r *TimeRange) Contains(epochNanos int64) bool {
	return epochNanos >= r.From && epochNanos <= r.To
}

// chunkEndMargin is the slack on the comparison with a chunk's end. A chunk's
// start and its events' start times come from the same tick counter, but its
// duration is a separate header field.
const chunkEndMargin = int64(1_000_000_000)

const (
	startTimeField = "startTime"
	eventSuperType = "jdk.jfr.Event"
)

// chunkCanHoldMatch reports whether a chunk starting at startNanos and lasting
// durationNanos can hold an event whose start time is in r.
//
// There is one rule, and it looks one way only. An event is written when it is
// committed, into the chunk that is current then, so no event starts after its
// chunk ends: a chunk that ends before the range begins holds nothing.
//
// The mirror image does not hold and is deliberately not implemented. A chunk
// that starts after the range ends can still hold events that start in it: an
// event with a duration is written when it ends (a jdk.ThreadSleep was measured
// starting 2.5 s before its chunk), and a Datadog profiler recording holds
// datadog.HeapLiveObject events, which have no duration field, stamped up to 36
// minutes before the chunk they were written to.
//
// A chunk that was never finished carries no duration and is always read.
//
// The header's duration is in nanoseconds, not ticks: across the 99 chunks of a
// JDK 21 recording with a tick frequency of 2.1e9 it equalled the distance to
// the next chunk's StartNanos exactly.
func chunkCanHoldMatch(r *TimeRange, startNanos, durationNanos int64) bool {
	if durationNanos <= 0 {
		return true
	}
	end := saturatingAdd(startNanos, durationNanos)
	return saturatingAdd(end, chunkEndMargin) >= r.From
}

// needsChunk reports whether a parse wanting the event types filter accepts
// (all of them when it is nil), with start times in r, has to read the chunk.
//
// When the header rules the chunk out, the metadata still has a say: a wanted
// type whose startTime is not a tick timestamp is not converted to epoch
// nanoseconds, so its values are in some other unit and comparing them with
// the header would be guessing.
func needsChunk(filter func(*ClassType) bool, r *TimeRange, h *ChunkHeader, md *Metadata) bool {
	if chunkCanHoldMatch(r, h.StartNanos, h.Duration) {
		return true
	}
	for _, ct := range md.Classes {
		hasStartTime := false
		for _, f := range ct.Fields {
			if f.Name == startTimeField {
				hasStartTime = true
				break
			}
		}
		if !hasStartTime && ct.SuperType != eventSuperType {
			continue // not an event type: java.lang.String, jdk.types.StackTrace, ...
		}
		if filter != nil && !filter(ct) {
			continue
		}
		if !hasStartTime {
			return true
		}
		if ct.temporal == nil {
			ct.temporal = buildTemporalKinds(ct)
		}
		if ct.temporal[startTimeField] != temporalTimestamp {
			return true
		}
	}
	return false
}

func saturatingAdd(a, b int64) int64 {
	sum := a + b
	if (a^sum)&(b^sum) < 0 {
		if a < 0 {
			return math.MinInt64
		}
		return math.MaxInt64
	}
	return sum
}
