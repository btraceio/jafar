package jfr

import (
	"os"
	"reflect"
	"testing"
)

// decodedCounts counts events per type name by decoding every event: the
// oracle for CountEvents.
func decodedCounts(t *testing.T, data []byte) map[string]int64 {
	t.Helper()
	counts := make(map[string]int64)
	if err := NewParser(data).Parse(func(e *Event) error {
		counts[e.Type.Name]++
		return nil
	}); err != nil {
		t.Fatalf("parse: %v", err)
	}
	return counts
}

// headerFixtures returns the real recordings the header tests run on:
// tck-test.jfr (a Datadog recording, startTime first) and test-ap.jfr four
// times over (written with the JMC writer, startTime after stackTrace and
// eventThread; a chunk is self-contained, so this is a multi-chunk file).
func headerFixtures(t *testing.T) map[string][]byte {
	t.Helper()
	tck, err := os.ReadFile(tckRecording)
	if err != nil {
		t.Fatalf("read %s: %v", tckRecording, err)
	}
	ap, err := os.ReadFile("../../parser-core/src/test/resources/test-ap.jfr")
	if err != nil {
		t.Fatalf("read test-ap.jfr: %v", err)
	}
	var multi []byte
	for i := 0; i < 4; i++ {
		multi = append(multi, ap...)
	}
	return map[string][]byte{"tck-test.jfr": tck, "test-ap.jfr x4": multi}
}

func TestCountEventsMatchesDecodedCounts(t *testing.T) {
	for name, data := range headerFixtures(t) {
		want := decodedCounts(t, data)
		if len(want) == 0 {
			t.Fatalf("%s: no event decoded", name)
		}
		got, err := NewParser(data).CountEvents(nil)
		if err != nil {
			t.Fatalf("%s: CountEvents: %v", name, err)
		}
		if !reflect.DeepEqual(got, want) {
			t.Errorf("%s: CountEvents = %v, want %v", name, got, want)
		}
	}
}

func TestCountEventsCountsOnlyTheAcceptedTypes(t *testing.T) {
	data := headerFixtures(t)["tck-test.jfr"]
	all := decodedCounts(t, data)
	const wanted = "jdk.GCHeapSummary"
	if all[wanted] == 0 {
		t.Fatalf("tck-test.jfr has no %s", wanted)
	}
	got, err := NewParser(data).CountEvents(func(c *ClassType) bool { return c.Name == wanted })
	if err != nil {
		t.Fatalf("CountEvents: %v", err)
	}
	if want := map[string]int64{wanted: all[wanted]}; !reflect.DeepEqual(got, want) {
		t.Errorf("CountEvents = %v, want %v", got, want)
	}
}

// The header of every event names the decoded event's type and carries its
// decoded startTime. The parser calls OnEventHeader and then the handler for
// the same event, so the two can be paired.
func TestEventHeaderMatchesTheDecodedEvent(t *testing.T) {
	for name, data := range headerFixtures(t) {
		var pending *EventHeader
		headers, values, withStartTime := 0, 0, 0
		err := NewParser(data).ParseWith(Options{
			OnEventHeader: func(h EventHeader) error {
				headers++
				if pending != nil {
					t.Fatalf("%s: two headers before one value (%s)", name, h.Type.Name)
				}
				pending = &h
				return nil
			},
		}, func(e *Event) error {
			values++
			h := pending
			pending = nil
			if h == nil {
				t.Fatalf("%s: no header before the value of %s", name, e.Type.Name)
			}
			if h.Type != e.Type {
				t.Fatalf("%s: header type %s for an event of %s", name, h.Type.Name, e.Type.Name)
			}
			if startTimeIndex(e.Type) < 0 {
				if h.StartTime != NoStartTime {
					t.Fatalf("%s: %s has no startTime field but got %d", name, e.Type.Name, h.StartTime)
				}
				return nil
			}
			withStartTime++
			if decoded, ok := e.Values["startTime"].(int64); !ok || decoded != h.StartTime {
				t.Fatalf("%s: %s: header startTime %d, decoded %#v", name, e.Type.Name, h.StartTime, e.Values["startTime"])
			}
			return nil
		})
		if err != nil {
			t.Fatalf("%s: parse: %v", name, err)
		}
		if values == 0 || headers != values {
			t.Errorf("%s: %d headers for %d events", name, headers, values)
		}
		if withStartTime == 0 {
			t.Errorf("%s: no event has a startTime, so its value went unchecked", name)
		}
		t.Logf("%s: %d events, %d with a startTime", name, values, withStartTime)
	}
}

// The sizes of test-ap.jfr's events, per type, as the JDK's `jfr summary`
// prints them (OpenJDK 21.0.x: Count and Size (bytes) columns).
func TestEventHeaderSizesMatchJfrSummary(t *testing.T) {
	data, err := os.ReadFile("../../parser-core/src/test/resources/test-ap.jfr")
	if err != nil {
		t.Fatalf("read test-ap.jfr: %v", err)
	}
	sizes := make(map[string]int64)
	counts := make(map[string]int64)
	err = NewParser(data).ParseWith(Options{
		TypeFilter: func(*ClassType) bool { return false },
		OnEventHeader: func(h EventHeader) error {
			sizes[h.Type.Name] += h.Size
			counts[h.Type.Name]++
			return nil
		},
	}, nil)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	wantSizes := map[string]int64{
		"jdk.ExecutionSample":       91318,
		"jdk.CPULoad":               5985,
		"jdk.GCHeapSummary":         10064,
		"jdk.InitialSystemProperty": 216,
	}
	wantCounts := map[string]int64{
		"jdk.ExecutionSample":       8000,
		"jdk.CPULoad":               160,
		"jdk.GCHeapSummary":         160,
		"jdk.InitialSystemProperty": 5,
	}
	if !reflect.DeepEqual(sizes, wantSizes) {
		t.Errorf("sizes = %v, want %v", sizes, wantSizes)
	}
	if !reflect.DeepEqual(counts, wantCounts) {
		t.Errorf("counts = %v, want %v", counts, wantCounts)
	}
}

// The proof that an event OnEventHeader receives is not decoded when
// TypeFilter rejects it: the recording holds an undecodable test.Bag event,
// which fails a FailOnError parse that decodes it
// (TestUndecodableEventIsReportedAndSkipped). Counted from headers, all four
// events arrive and nothing fails. test.Bag has no startTime field.
func TestHeaderOnlyEventIsNotDecoded(t *testing.T) {
	var startTimes []int64
	err := NewParser(undecodableBagRecording()).ParseWith(Options{
		TypeFilter: func(*ClassType) bool { return false },
		OnError:    FailOnError,
		OnEventHeader: func(h EventHeader) error {
			startTimes = append(startTimes, h.StartTime)
			return nil
		},
	}, nil)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if want := []int64{NoStartTime, NoStartTime, NoStartTime, NoStartTime}; !reflect.DeepEqual(startTimes, want) {
		t.Errorf("start times = %v, want %v", startTimes, want)
	}
}

func TestHeaderFilterSelectsTypesAndErrStopEndsParsing(t *testing.T) {
	data := headerFixtures(t)["tck-test.jfr"]
	n := 0
	err := NewParser(data).ParseWith(Options{
		TypeFilter:   func(*ClassType) bool { return false },
		HeaderFilter: func(c *ClassType) bool { return c.Name == "jdk.GCHeapSummary" },
		OnEventHeader: func(h EventHeader) error {
			if h.Type.Name != "jdk.GCHeapSummary" {
				t.Fatalf("header for %s, which HeaderFilter rejects", h.Type.Name)
			}
			n++
			if n == 3 {
				return ErrStop
			}
			return nil
		},
	}, nil)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if n != 3 {
		t.Errorf("headers = %d, want 3", n)
	}
}

// With OnEventHeader set, StartTime skips no chunk: a range after the
// recording would otherwise skip every chunk.
func TestEventHeaderKeepsEveryChunk(t *testing.T) {
	data := headerFixtures(t)["test-ap.jfr x4"]
	want := decodedCounts(t, data)
	var total int64
	for _, n := range want {
		total += n
	}
	after := Since(1 << 62)
	var headers int64
	err := NewParser(data).ParseWith(Options{
		StartTime:     after,
		TypeFilter:    func(*ClassType) bool { return false },
		OnEventHeader: func(EventHeader) error { headers++; return nil },
	}, nil)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if headers != total {
		t.Errorf("headers = %d, want every event (%d)", headers, total)
	}
}
