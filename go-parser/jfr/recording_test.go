package jfr

import (
	"crypto/sha256"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

// These tests run against the JFR recordings available in the repository. They
// are skipped when the package is built outside the repository tree.
const (
	tckRecording       = "../../jfr-shell-tck/src/main/resources/tck-test.jfr"
	strippedRecordings = "../../parser-core/src/test/resources/dd-trace-java-stripped"
)

// wellFormedRecordings lists the recordings expected to decode without a single
// recoverable problem, in reporting order. The first is checked into the
// repository; the rest are the larger ones ./get_resources.sh downloads, and are
// simply absent when it has not been run.
//
// The deliberately malformed dd-trace-java fixtures are not listed here; they
// have their own test.
var wellFormedRecordings = []string{
	tckRecording,
	"../../parser-core/src/test/resources/test-jfr.jfr",
	"../../parser-core/src/test/resources/test-ap.jfr",
	"../../parser-core/src/test/resources/test-dd.jfr",
	"../../demo/src/test/resources/test-jfr.jfr",
	"../../demo/src/test/resources/test-ap.jfr",
	"../../demo/src/test/resources/test-dd.jfr",
}

// availableRecordings returns the well-formed recordings present on disk.
//
// It deduplicates by content rather than by file name: get_resources.sh copies
// the same recordings into two directories, and it also writes one of them
// under a second name, so a name-based check leaves the parser measuring the
// same bytes twice. Files that do not carry the JFR magic are skipped, so that
// a failed or misconfigured download shows up as a missing recording rather
// than as a parser failure.
func availableRecordings() []string {
	var out []string
	seen := map[string]bool{}
	for _, path := range wellFormedRecordings {
		info, err := os.Stat(path)
		if err != nil {
			continue
		}
		key, err := recordingIdentity(path, info.Size())
		if err != nil {
			continue
		}
		if seen[key] {
			continue
		}
		seen[key] = true
		out = append(out, path)
	}
	return out
}

// recordingIdentity fingerprints a recording by its size and its leading bytes,
// which is enough to spot a copy without hashing gigabytes. It fails for a file
// that is not a JFR recording.
func recordingIdentity(path string, size int64) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()

	head := make([]byte, 1<<20)
	n, err := io.ReadFull(f, head)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return "", err
	}
	head = head[:n]
	if len(head) < 4 || string(head[:4]) != "FLR\x00" {
		return "", fmt.Errorf("%s does not start with the JFR magic", path)
	}
	return fmt.Sprintf("%d:%x", size, sha256.Sum256(head)), nil
}

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

// TestAvailableRecordingsDecodeCleanly parses every well-formed recording in the
// repository strictly: a recoverable problem that the parser would normally
// absorb fails the test instead. It is the check that gains the most from
// ./get_resources.sh having been run, since the larger recordings exercise far
// more of the format than the ones checked in.
func TestAvailableRecordingsDecodeCleanly(t *testing.T) {
	paths := availableRecordings()
	if len(paths) == 0 {
		t.Skip("no recording available; run ./get_resources.sh")
	}
	for _, path := range paths {
		t.Run(filepath.Base(path), func(t *testing.T) {
			p := openFixture(t, path)

			events, chunks := 0, 0
			types := map[string]bool{}
			err := p.ParseWith(Options{
				OnChunkStart: func(_ *ChunkInfo, md *Metadata) error {
					chunks++
					if len(md.Classes) == 0 {
						t.Errorf("chunk %d declares no types", chunks)
					}
					return nil
				},
				OnError: FailOnError,
			}, func(e *Event) error {
				events++
				types[e.Type.Name] = true
				// Every event belongs to the chunk it was read from and to a
				// type declared by that chunk's metadata.
				if e.Chunk == nil || e.Type == nil {
					t.Fatalf("event %d has no chunk or type", events)
				}
				return nil
			})
			if err != nil {
				t.Fatalf("parse: %v", err)
			}
			if chunks == 0 {
				t.Fatal("no chunk was parsed")
			}
			if events == 0 {
				t.Fatal("no event was decoded")
			}
			t.Logf("%d chunk(s), %d events, %d event types", chunks, events, len(types))
		})
	}
}

// The fresh-versus-recycled comparison deep resolves every event twice, so it
// is exhaustive on a recording small enough for that to be cheap and capped on
// anything larger.
const (
	exhaustiveComparisonBytes = 16 << 20
	maxEquivalenceEvents      = 20000
)

// TestAvailableRecordingsReuseEquivalence checks value recycling against every
// available recording, not just the one checked in: a recycled parse has to
// decode to exactly what a fresh parse does.
func TestAvailableRecordingsReuseEquivalence(t *testing.T) {
	if testing.Short() {
		t.Skip("deep-compares events of every available recording twice")
	}
	paths := availableRecordings()
	if len(paths) == 0 {
		t.Skip("no recording available; run ./get_resources.sh")
	}
	for _, path := range paths {
		t.Run(filepath.Base(path), func(t *testing.T) {
			p := openFixture(t, path)

			limit := maxEquivalenceEvents
			if info, err := os.Stat(path); err == nil && info.Size() <= exhaustiveComparisonBytes {
				limit = 0 // compare every event
			}

			collect := func(reuse bool) []map[string]any {
				var out []map[string]any
				err := p.ParseWith(Options{ReuseValues: reuse, OnError: FailOnError}, func(e *Event) error {
					if limit > 0 && len(out) >= limit {
						return ErrStop
					}
					out = append(out, ResolveDeepMap(e.Values))
					return nil
				})
				if err != nil {
					t.Fatalf("parse (reuse=%t): %v", reuse, err)
				}
				return out
			}

			fresh := collect(false)
			reused := collect(true)
			if len(fresh) != len(reused) {
				t.Fatalf("event counts differ: %d fresh, %d reused", len(fresh), len(reused))
			}
			for i := range fresh {
				if !reflect.DeepEqual(fresh[i], reused[i]) {
					t.Fatalf("event %d differs between a fresh and a recycled parse:\n fresh  = %#v\n reused = %#v",
						i, fresh[i], reused[i])
				}
			}
			t.Logf("compared %d events", len(fresh))
		})
	}
}
