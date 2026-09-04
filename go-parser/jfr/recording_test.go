package jfr

import (
	"os"
	"path/filepath"
	"testing"
)

// These tests run against the JFR recordings checked into the repository. They
// are skipped when the package is built outside the repository tree.
const (
	tckRecording       = "../../jfr-shell-tck/src/main/resources/tck-test.jfr"
	strippedRecordings = "../../parser-core/src/test/resources/dd-trace-java-stripped"
)

func openFixture(t *testing.T, path string) *Parser {
	t.Helper()
	if _, err := os.Stat(path); err != nil {
		t.Skipf("fixture %s is not available: %v", path, err)
	}
	p, err := Open(path)
	if err != nil {
		t.Fatalf("Open(%s): %v", path, err)
	}
	return p
}

// TestTckRecordingEventCounts pins the per-type event counts of the reference
// recording. The recording holds two chunks written by different producers that
// map the same type IDs to different type names, so the counts also assert that
// event types are resolved against the metadata of their own chunk.
func TestTckRecordingEventCounts(t *testing.T) {
	p := openFixture(t, tckRecording)

	counts := map[string]int{}
	total := 0
	chunks := 0
	if err := p.ParseWith(Options{
		OnChunkStart: func(*ChunkInfo, *Metadata) error {
			chunks++
			return nil
		},
		OnError: FailOnError,
	}, func(e *Event) error {
		counts[e.Type.Name]++
		total++
		return nil
	}); err != nil {
		t.Fatalf("parse: %v", err)
	}

	if chunks != 2 {
		t.Errorf("chunks = %d, want 2", chunks)
	}
	if total != 67657 {
		t.Errorf("total events = %d, want 67657", total)
	}
	// Counts cross-checked against "jfr summary" of the same recording.
	want := map[string]int{
		"datadog.ExecutionSample":   14202,
		"datadog.MethodSample":      12813,
		"datadog.ExceptionSample":   3357,
		"jdk.TenuringDistribution":  13800,
		"jdk.GCReferenceStatistics": 3784,
		"jdk.GCHeapSummary":         1892,
		"jdk.GarbageCollection":     946,
		"jdk.ThreadStart":           17,
		"jdk.JavaMonitorEnter":      1,
	}
	for name, n := range want {
		if counts[name] != n {
			t.Errorf("%s = %d, want %d", name, counts[name], n)
		}
	}
}

// TestTckRecordingResolvesConstantPools walks from an event through the thread,
// stack trace, frame, method and symbol constant pools.
func TestTckRecordingResolvesConstantPools(t *testing.T) {
	p := openFixture(t, tckRecording)

	threads := 0
	methods := 0
	err := p.ParseWith(Options{OnError: FailOnError}, func(e *Event) error {
		if name, ok := GetString(e.Values, "eventThread", "javaName"); ok && name != "" {
			threads++
		}
		if method, ok := GetString(e.Values, "stackTrace", "frames", 0, "method", "name", "string"); ok && method != "" {
			methods++
		}
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if threads == 0 {
		t.Error("no event thread name could be resolved")
	}
	if methods == 0 {
		t.Error("no stack trace method name could be resolved")
	}
	t.Logf("resolved %d thread names and %d top frame method names", threads, methods)
}

// TestTckRecordingTimestampsAreNormalised checks that tick based fields come
// back in nanoseconds within the recorded chunk's time range.
func TestTckRecordingTimestampsAreNormalised(t *testing.T) {
	p := openFixture(t, tckRecording)

	checked := 0
	err := p.ParseWith(Options{OnError: FailOnError}, func(e *Event) error {
		start, ok := GetInt(e.Values, "startTime")
		if !ok {
			return nil
		}
		// Events may carry a timestamp slightly before the chunk start, so
		// the window is widened by a day on both sides; a raw tick value
		// would be off by decades.
		const day = int64(24 * 3600 * 1e9)
		lo := e.Chunk.Header.StartNanos - day
		hi := e.Chunk.Header.StartNanos + e.Chunk.AsDurationNanos(e.Chunk.Header.Duration) + day
		if start < lo || start > hi {
			t.Fatalf("%s startTime %d outside the chunk range [%d, %d]", e.Type.Name, start, lo, hi)
		}
		checked++
		if checked > 5000 {
			return ErrStop
		}
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if checked == 0 {
		t.Error("no event carried a startTime")
	}
}

// TestStrippedRecordings parses the fixtures that hold only metadata and
// constant pool events, some of them malformed. They must decode their type
// universe without the parser failing.
func TestStrippedRecordings(t *testing.T) {
	entries, err := os.ReadDir(strippedRecordings)
	if err != nil {
		t.Skipf("fixtures are not available: %v", err)
	}
	for _, entry := range entries {
		if filepath.Ext(entry.Name()) != ".jfr" {
			continue
		}
		t.Run(entry.Name(), func(t *testing.T) {
			p := openFixture(t, filepath.Join(strippedRecordings, entry.Name()))
			chunks := 0
			if err := p.ParseWith(Options{
				OnChunkStart: func(_ *ChunkInfo, md *Metadata) error {
					chunks++
					if len(md.Classes) == 0 {
						t.Errorf("chunk %d declares no types", chunks)
					}
					if md.ClassByName("java.lang.String") == nil {
						t.Errorf("chunk %d declares no java.lang.String type", chunks)
					}
					return nil
				},
			}, nil); err != nil {
				t.Fatalf("parse: %v", err)
			}
			if chunks == 0 {
				t.Error("no chunk was parsed")
			}
		})
	}
}
