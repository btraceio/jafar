package jfr

import (
	"os"
	"testing"
)

// The benchmark suite mirrors the workload shapes of the JMH benchmarks in
// parser-codegen (UntypedParserBenchmark) and of PERFORMANCE.md, and adds the
// axis those do not isolate: the cost of resolving constant pool references,
// which in a real recording carries the stack traces.
//
// Set JAFAR_BENCH_JFR to benchmark a different recording.

func benchData(b *testing.B) []byte {
	b.Helper()
	path := os.Getenv("JAFAR_BENCH_JFR")
	if path == "" {
		path = tckRecording
	}
	data, err := os.ReadFile(path)
	if err != nil {
		b.Skipf("recording %s is not available: %v", path, err)
	}
	return data
}

// runBench reports throughput plus per-event cost, so that workloads decoding
// different numbers of events stay comparable.
//
// The ns/event metric includes the fixed per-chunk cost that BenchmarkScan
// measures on its own, so it is only meaningful for workloads whose event type
// is well represented in the recording. Subtract the scan floor before
// comparing a narrow filter against a broad one.
func runBench(b *testing.B, opts Options, fn func(*Event)) {
	data := benchData(b)
	p := NewParser(data)
	events := 0
	handler := func(e *Event) error {
		events++
		if fn != nil {
			fn(e)
		}
		return nil
	}
	b.SetBytes(int64(len(data)))
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		events = 0
		if err := p.ParseWith(opts, handler); err != nil {
			b.Fatal(err)
		}
	}
	b.StopTimer()
	if events > 0 {
		b.ReportMetric(float64(events), "events/op")
		b.ReportMetric(float64(b.Elapsed().Nanoseconds())/float64(b.N*events), "ns/event")
	}
}

// hotType is the event type the constant-pool benchmarks work on: a sample
// event carrying a stack trace. Override with JAFAR_BENCH_TYPE when
// benchmarking a different recording.
func hotType() string {
	if t := os.Getenv("JAFAR_BENCH_TYPE"); t != "" {
		return t
	}
	return "datadog.ExecutionSample"
}

func filterTo(names ...string) func(*ClassType) bool {
	set := make(map[string]bool, len(names))
	for _, n := range names {
		set[n] = true
	}
	return func(c *ClassType) bool { return set[c.Name] }
}

// BenchmarkScan walks the chunk framing, metadata and constant pool index
// without decoding a single event. This is the floor every other benchmark
// builds on.
func BenchmarkScan(b *testing.B) {
	runBench(b, Options{TypeFilter: func(*ClassType) bool { return false }}, nil)
}

// BenchmarkCountOnly decodes every event but touches no field, isolating the
// cost of building the event representation.
func BenchmarkCountOnly(b *testing.B) {
	runBench(b, Options{}, nil)
}

// BenchmarkFullIteration decodes every event and walks all of its fields.
func BenchmarkFullIteration(b *testing.B) {
	var sink any
	runBench(b, Options{}, func(e *Event) {
		for _, v := range e.Values {
			sink = v
		}
	})
	_ = sink
}

// BenchmarkSparseAccess decodes every event but reads only two fields, the
// pattern a filtering or sampling consumer has.
func BenchmarkSparseAccess(b *testing.B) {
	var sink int64
	runBench(b, Options{}, func(e *Event) {
		v, _ := GetInt(e.Values, "startTime")
		d, _ := GetInt(e.Values, "duration")
		sink += v + d
	})
	_ = sink
}

// BenchmarkFiltered decodes one event type and reads two fields from it.
func BenchmarkFiltered(b *testing.B) {
	var sink int64
	runBench(b, Options{TypeFilter: filterTo(hotType())}, func(e *Event) {
		v, _ := GetInt(e.Values, "startTime")
		sink += v
	})
	_ = sink
}

// BenchmarkResolveTopFrame resolves one stack trace per event down to the top
// frame's method name, crossing the stack trace, frame, method and symbol
// constant pools.
func BenchmarkResolveTopFrame(b *testing.B) {
	var sink string
	runBench(b, Options{TypeFilter: filterTo(hotType())}, func(e *Event) {
		s, _ := GetString(e.Values, "stackTrace", "frames", 0, "method", "name", "string")
		sink = s
	})
	_ = sink
}

// BenchmarkResolveDeep materialises every event into plain maps and slices,
// resolving the whole reachable constant pool graph. This is the worst case a
// consumer can ask for.
func BenchmarkResolveDeep(b *testing.B) {
	var sink map[string]any
	runBench(b, Options{TypeFilter: filterTo(hotType())}, func(e *Event) {
		sink = ResolveDeepMap(e.Values)
	})
	_ = sink
}

// BenchmarkStringHeavy decodes the event types carrying inline string fields.
func BenchmarkStringHeavy(b *testing.B) {
	var sink any
	runBench(b, Options{TypeFilter: filterTo(
		"datadog.ExceptionSample", "jdk.GCPhasePauseLevel1", "jdk.GCPhasePause",
		"jdk.ActiveSetting", "jdk.BooleanFlag", "jdk.LongFlag",
	)}, func(e *Event) {
		for _, v := range e.Values {
			sink = v
		}
	})
	_ = sink
}

// BenchmarkNested decodes the event types carrying inline nested structures.
func BenchmarkNested(b *testing.B) {
	var sink any
	runBench(b, Options{TypeFilter: filterTo(
		"jdk.GCHeapSummary", "jdk.MetaspaceSummary", "jdk.MetaspaceChunkFreeListSummary",
	)}, func(e *Event) {
		for _, v := range e.Values {
			sink = v
		}
	})
	_ = sink
}
