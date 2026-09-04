package jfr

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"testing"
)

// The benchmark suite mirrors the workload shapes of the JMH benchmarks in
// parser-codegen (UntypedParserBenchmark) and of PERFORMANCE.md - scan,
// count-only, full iteration, sparse access, filtered, string-heavy, nested -
// and adds the axis those do not isolate: the cost of resolving constant pool
// references, which is where a real recording keeps its stack traces.
//
// Every benchmark runs once per available recording. By default that is the
// recordings checked into the repository plus the larger ones ./get_resources.sh
// downloads; set JAFAR_BENCH_JFR to benchmark specific files instead, separated
// by the platform's path list separator.
//
// The workload types are chosen per recording rather than hardcoded, so the
// suite measures the same shapes whichever recording it is pointed at.

// benchRecording is a recording to benchmark, with the event types each
// workload should use.
type benchRecording struct {
	name string
	data []byte

	// hot is the most frequent event type carrying a stack trace; the constant
	// pool benchmarks use it.
	hot string
	// stringHeavy are the types with inline string fields, and nested those
	// with inline complex fields, both ranked by how much of the recording they
	// account for.
	stringHeavy []string
	nested      []string
	events      int
}

var (
	benchOnce sync.Once
	benchSet  []*benchRecording
	benchErr  error
)

func recordings(b *testing.B) []*benchRecording {
	b.Helper()
	benchOnce.Do(func() { benchSet, benchErr = loadRecordings() })
	if benchErr != nil {
		b.Fatal(benchErr)
	}
	if len(benchSet) == 0 {
		b.Skip("no recording available; run ./get_resources.sh or set JAFAR_BENCH_JFR")
	}
	return benchSet
}

func loadRecordings() ([]*benchRecording, error) {
	paths := availableRecordings()
	if p := os.Getenv("JAFAR_BENCH_JFR"); p != "" {
		paths = filepath.SplitList(p)
	}
	var out []*benchRecording
	for _, path := range paths {
		data, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		r := &benchRecording{name: filepath.Base(path), data: data}
		if err := r.profile(); err != nil {
			return nil, fmt.Errorf("%s: %w", path, err)
		}
		out = append(out, r)
	}
	return out, nil
}

// profile walks the recording once to pick the workload types, so that a
// benchmark measures the same shape whichever recording it runs on.
func (r *benchRecording) profile() error {
	counts := map[string]int{}
	hasStack := map[string]bool{}
	strFields := map[string]int{}
	nestedFields := map[string]int{}

	err := NewParser(r.data).ParseWith(Options{
		OnChunkStart: func(_ *ChunkInfo, md *Metadata) error {
			for _, t := range md.EventTypes() {
				for _, f := range t.Fields {
					switch {
					case f.Name == "stackTrace" && f.ConstantPool:
						hasStack[t.Name] = true
					case f.ConstantPool || f.Type == nil:
					case f.Type.Name == "java.lang.String":
						strFields[t.Name]++
					case !f.Type.IsPrimitive():
						nestedFields[t.Name]++
					}
				}
			}
			return nil
		},
	}, func(e *Event) error {
		counts[e.Type.Name]++
		r.events++
		return nil
	})
	if err != nil {
		return err
	}

	// Rank types by how much of the recording they account for.
	rank := func(weights map[string]int, limit int) []string {
		var names []string
		for name, w := range weights {
			if w > 0 && counts[name] > 0 {
				names = append(names, name)
			}
		}
		sort.Slice(names, func(i, j int) bool {
			wi, wj := weights[names[i]]*counts[names[i]], weights[names[j]]*counts[names[j]]
			if wi != wj {
				return wi > wj
			}
			return names[i] < names[j]
		})
		if len(names) > limit {
			names = names[:limit]
		}
		return names
	}
	r.stringHeavy = rank(strFields, 6)
	r.nested = rank(nestedFields, 6)

	best := 0
	for name, n := range counts {
		if hasStack[name] && (n > best || (n == best && name < r.hot)) {
			r.hot, best = name, n
		}
	}
	if r.hot == "" {
		// No stack traces at all; fall back to the most frequent type so the
		// constant pool benchmarks still measure something.
		for name, n := range counts {
			if n > best || (n == best && name < r.hot) {
				r.hot, best = name, n
			}
		}
	}
	return nil
}

func filterTo(names ...string) func(*ClassType) bool {
	set := make(map[string]bool, len(names))
	for _, n := range names {
		set[n] = true
	}
	return func(c *ClassType) bool { return set[c.Name] }
}

// forEachRecording runs one workload against every available recording.
//
// The ns/event metric includes the fixed per-chunk cost that BenchmarkScan
// measures on its own, so it is only meaningful for workloads whose event type
// is well represented in the recording. Subtract the scan floor before
// comparing a narrow filter against a broad one.
func forEachRecording(b *testing.B, workload func(*benchRecording) (Options, func(*Event))) {
	for _, rec := range recordings(b) {
		b.Run(rec.name, func(b *testing.B) {
			opts, fn := workload(rec)
			p := NewParser(rec.data)
			events := 0
			handler := func(e *Event) error {
				events++
				if fn != nil {
					fn(e)
				}
				return nil
			}
			b.SetBytes(int64(len(rec.data)))
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
		})
	}
}

// BenchmarkScan walks the chunk framing, metadata and constant pool index
// without decoding a single event. This is the floor every other benchmark
// builds on.
func BenchmarkScan(b *testing.B) {
	forEachRecording(b, func(*benchRecording) (Options, func(*Event)) {
		return Options{TypeFilter: func(*ClassType) bool { return false }}, nil
	})
}

// BenchmarkCountOnly decodes every event but touches no field, isolating the
// cost of building the event representation.
func BenchmarkCountOnly(b *testing.B) {
	forEachRecording(b, func(*benchRecording) (Options, func(*Event)) {
		return Options{}, nil
	})
}

// BenchmarkFullIteration decodes every event and walks all of its fields.
func BenchmarkFullIteration(b *testing.B) {
	var sink any
	forEachRecording(b, func(*benchRecording) (Options, func(*Event)) {
		return Options{}, func(e *Event) {
			for _, v := range e.Values {
				sink = v
			}
		}
	})
	_ = sink
}

// BenchmarkSparseAccess decodes every event but reads only two fields, the
// pattern a filtering or sampling consumer has.
func BenchmarkSparseAccess(b *testing.B) {
	var sink int64
	forEachRecording(b, func(*benchRecording) (Options, func(*Event)) {
		return Options{}, func(e *Event) {
			v, _ := GetInt(e.Values, "startTime")
			d, _ := GetInt(e.Values, "duration")
			sink += v + d
		}
	})
	_ = sink
}

// BenchmarkCountOnlyReused is BenchmarkCountOnly with Options.ReuseValues,
// which recycles the maps, arrays and references of an event per event type.
func BenchmarkCountOnlyReused(b *testing.B) {
	forEachRecording(b, func(*benchRecording) (Options, func(*Event)) {
		return Options{ReuseValues: true}, nil
	})
}

// BenchmarkSparseAccessReused is BenchmarkSparseAccess with recycled values,
// the shape a streaming consumer that keeps nothing has.
func BenchmarkSparseAccessReused(b *testing.B) {
	var sink int64
	forEachRecording(b, func(*benchRecording) (Options, func(*Event)) {
		return Options{ReuseValues: true}, func(e *Event) {
			v, _ := GetInt(e.Values, "startTime")
			d, _ := GetInt(e.Values, "duration")
			sink += v + d
		}
	})
	_ = sink
}

// BenchmarkFiltered decodes one event type and reads two fields from it.
func BenchmarkFiltered(b *testing.B) {
	var sink int64
	forEachRecording(b, func(rec *benchRecording) (Options, func(*Event)) {
		return Options{TypeFilter: filterTo(rec.hot)}, func(e *Event) {
			v, _ := GetInt(e.Values, "startTime")
			sink += v
		}
	})
	_ = sink
}

// BenchmarkFilteredReused is BenchmarkFiltered with recycled values.
func BenchmarkFilteredReused(b *testing.B) {
	var sink int64
	forEachRecording(b, func(rec *benchRecording) (Options, func(*Event)) {
		return Options{TypeFilter: filterTo(rec.hot), ReuseValues: true}, func(e *Event) {
			v, _ := GetInt(e.Values, "startTime")
			sink += v
		}
	})
	_ = sink
}

// BenchmarkResolveTopFrame resolves one stack trace per event down to the top
// frame's method name, crossing the stack trace, frame, method and symbol
// constant pools.
func BenchmarkResolveTopFrame(b *testing.B) {
	var sink string
	forEachRecording(b, func(rec *benchRecording) (Options, func(*Event)) {
		return Options{TypeFilter: filterTo(rec.hot)}, func(e *Event) {
			s, _ := GetString(e.Values, "stackTrace", "frames", 0, "method", "name", "string")
			sink = s
		}
	})
	_ = sink
}

// BenchmarkResolveDeep materialises every event into plain maps and slices,
// resolving the whole reachable constant pool graph. This is the worst case a
// consumer can ask for.
func BenchmarkResolveDeep(b *testing.B) {
	var sink map[string]any
	forEachRecording(b, func(rec *benchRecording) (Options, func(*Event)) {
		return Options{TypeFilter: filterTo(rec.hot)}, func(e *Event) {
			sink = ResolveDeepMap(e.Values)
		}
	})
	_ = sink
}

// BenchmarkStringHeavy decodes the event types carrying inline string fields.
func BenchmarkStringHeavy(b *testing.B) {
	var sink any
	forEachRecording(b, func(rec *benchRecording) (Options, func(*Event)) {
		return Options{TypeFilter: filterTo(rec.stringHeavy...)}, func(e *Event) {
			for _, v := range e.Values {
				sink = v
			}
		}
	})
	_ = sink
}

// BenchmarkNested decodes the event types carrying inline nested structures.
func BenchmarkNested(b *testing.B) {
	var sink any
	forEachRecording(b, func(rec *benchRecording) (Options, func(*Event)) {
		return Options{TypeFilter: filterTo(rec.nested...)}, func(e *Event) {
			for _, v := range e.Values {
				sink = v
			}
		}
	})
	_ = sink
}
