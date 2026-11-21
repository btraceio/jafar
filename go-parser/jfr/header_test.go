package jfr

import (
	"os"
	"reflect"
	"strings"
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

const (
	tIDLate     = 110
	lateSymbol  = 7
	lateChunks  = 3
	latePerType = 5
)

// lateMetadata declares test.Late, whose startTime comes after fields of every
// kind the parser must skip to reach it: a constant pool reference, an inline
// string, a long array and an inline complex value.
func lateMetadata() *metaElement {
	late := elem("class",
		attr("id", itoa(tIDLate)),
		attr("name", "test.Late"),
		attr("superType", "jdk.jfr.Event"),
	).with(
		elem("field", attr("name", "symbol"), attr("class", itoa(tIDSymbol)), attr("constantPool", "true")),
		elem("field", attr("name", "label"), attr("class", itoa(tIDString))),
		elem("field", attr("name", "values"), attr("class", itoa(tIDLong)), attr("dimension", "1")),
		elem("field", attr("name", "nested"), attr("class", itoa(tIDNested))),
		elem("field", attr("name", "startTime"), attr("class", itoa(tIDLong))).with(
			elem("annotation", attr("class", itoa(tIDTimestamp)), attr("value", "TICKS")),
		),
		elem("field", attr("name", "count"), attr("class", itoa(tIDInt))),
	)
	md := testMetadata()
	md.children[0].children = append(md.children[0].children, late)
	return md
}

// lateEvent encodes one test.Late event body starting startTicks.
func lateEvent(i int, startTicks int64) []byte {
	w := &writer{}
	w.varint(tIDLate)
	w.varint(lateSymbol)
	w.bytes(utf8String("label-" + itoa(int64(i))))
	w.varint(int64(i))
	for j := 0; j < i; j++ {
		w.varint(int64(j * 1000))
	}
	w.varint(int64(uint32(i)))
	w.bytes(utf8String("nested"))
	w.varint(startTicks)
	w.varint(int64(uint32(i * 3)))
	return w.buf
}

// lateRecording is a multi-chunk recording of test.Late and test.Sample events,
// built here so that it is in every checkout. It returns the recording and the
// sum of the sizes of the events of each type, as written.
func lateRecording() ([]byte, map[string]int64) {
	sizes := make(map[string]int64)
	var data []byte
	for c := 0; c < lateChunks; c++ {
		var events [][]byte
		for i := 0; i < latePerType; i++ {
			late := lateEvent(i, testStartTicks+int64(c*10_000+i*100))
			sample := sampleEvent{
				startTicks: testStartTicks + int64(c*10_000+i*100+50),
				name:       utf8String("s"), symbol: lateSymbol,
				nestedB: utf8String(""),
			}.encode()
			events = append(events, late, sample)
			sizes["test.Late"] += int64(len(sizedEvent(late)))
			sizes["test.Sample"] += int64(len(sizedEvent(sample)))
		}
		spec := chunkSpec{
			metadata: lateMetadata(),
			pools: []constantPoolData{
				{typeID: tIDSymbol, entries: []poolEntry{{id: lateSymbol, data: utf8String("sym")}}},
			},
			events:     events,
			startNanos: testStartNanos + int64(c)*1_000_000_000,
			startTicks: testStartTicks + int64(c*10_000),
			frequency:  testFrequency,
			duration:   1_000_000,
		}
		data = append(data, spec.build()...)
	}
	return data, sizes
}

// testAPRecording reads test-ap.jfr, which the Gradle build generates
// (:test-recordings:generateTestRecordings) and get_resources.sh downloads; a
// Go-only checkout does not have it, and the tests that need it skip.
func testAPRecording(t *testing.T) []byte {
	t.Helper()
	const path = "../../parser-core/src/test/resources/test-ap.jfr"
	data, err := os.ReadFile(path)
	if err != nil {
		t.Skipf("fixture %s is not available: %v", path, err)
	}
	return data
}

// headerFixtures returns the recordings the header tests run on: tck-test.jfr
// (checked in; a Datadog recording, startTime first), the synthetic
// multi-chunk late-startTime recording, and test-ap.jfr four times over when it
// is present (JMC writer, startTime after stackTrace and eventThread).
func headerFixtures(t *testing.T) map[string][]byte {
	t.Helper()
	tck, err := os.ReadFile(tckRecording)
	if err != nil {
		t.Fatalf("read %s: %v", tckRecording, err)
	}
	late, _ := lateRecording()
	fixtures := map[string][]byte{"tck-test.jfr": tck, "late-startTime (synthetic)": late}
	if ap, err := os.ReadFile("../../parser-core/src/test/resources/test-ap.jfr"); err == nil {
		var multi []byte
		for i := 0; i < 4; i++ {
			multi = append(multi, ap...)
		}
		fixtures["test-ap.jfr x4"] = multi
	}
	return fixtures
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
		headers, values, withStartTime, notFirst := 0, 0, 0, 0
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
			if startTimeIndex(e.Type) > 0 {
				notFirst++
			}
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
		if name == "late-startTime (synthetic)" && notFirst != lateChunks*latePerType {
			t.Errorf("%s: %d events with startTime after other fields, want %d", name, notFirst, lateChunks*latePerType)
		}
		t.Logf("%s: %d events, %d with a startTime, %d of them after other fields", name, values, withStartTime, notFirst)
	}
}

// The sizes of test-ap.jfr's events, per type, as the JDK's `jfr summary`
// prints them (OpenJDK 21.0.x: Count and Size (bytes) columns).
func TestEventHeaderSizesMatchJfrSummary(t *testing.T) {
	data := testAPRecording(t)
	sizes := make(map[string]int64)
	counts := make(map[string]int64)
	err := NewParser(data).ParseWith(Options{
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

// The sizes of the synthetic recording's events, per type, as they were
// written.
func TestEventHeaderSizesMatchTheWrittenEvents(t *testing.T) {
	data, want := lateRecording()
	sizes := make(map[string]int64)
	err := NewParser(data).ParseWith(Options{
		TypeFilter:    func(*ClassType) bool { return false },
		OnEventHeader: func(h EventHeader) error { sizes[h.Type.Name] += h.Size; return nil },
		OnError:       FailOnError,
	}, nil)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if !reflect.DeepEqual(sizes, want) {
		t.Errorf("sizes = %v, want %v", sizes, want)
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
	data, _ := lateRecording()
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

// The node-pool fixtures (checked in; shared with the Java side's
// differential test) hold seven test.Holder events, a type with no startTime
// field: every header carries NoStartTime, read from a real file. Three of the
// four have broken constant pools (a dangling name, an undeclared pool, an
// unreadable entry). Two of those are reported while the checkpoint is read;
// the headers do not touch the pools, so all four count the same.
func TestNodePoolHeadersCarryNoStartTime(t *testing.T) {
	for _, shape := range []string{"intact", "dangling-name", "undeclared-pool", "unreadable-entry"} {
		data := nodePoolFixture(t, shape)
		var startTimes []int64
		var reported []error
		err := NewParser(data).ParseWith(Options{
			TypeFilter: func(*ClassType) bool { return false },
			OnError: func(err error) error {
				reported = append(reported, err)
				return nil
			},
			OnEventHeader: func(h EventHeader) error {
				if h.Type.Name != "test.Holder" {
					t.Fatalf("%s: header for %s", shape, h.Type.Name)
				}
				if startTimeIndex(h.Type) >= 0 {
					t.Fatalf("%s: test.Holder has a startTime field", shape)
				}
				startTimes = append(startTimes, h.StartTime)
				return nil
			},
		}, nil)
		if err != nil {
			t.Fatalf("%s: parse: %v", shape, err)
		}
		// What is reported comes from reading the checkpoint, before any event;
		// nothing is reported for the events, which are not decoded.
		wantReport := map[string]string{
			"undeclared-pool":  "constant pool type 77 is not declared",
			"unreadable-entry": "constant pool test.Node, index 2: unexpected string constant id: 127",
		}[shape]
		if wantReport == "" {
			if len(reported) != 0 {
				t.Errorf("%s: reported = %v, want nothing", shape, reported)
			}
		} else if len(reported) != 1 || !strings.Contains(reported[0].Error(), wantReport) {
			t.Errorf("%s: reported = %v, want one error containing %q", shape, reported, wantReport)
		}
		if len(startTimes) != len(nodeRefs) {
			t.Fatalf("%s: %d headers, want %d", shape, len(startTimes), len(nodeRefs))
		}
		for i, st := range startTimes {
			if st != NoStartTime {
				t.Errorf("%s: header %d startTime = %d, want NoStartTime", shape, i, st)
			}
		}
		counts, err := NewParser(data).CountEvents(nil)
		if err != nil {
			t.Fatalf("%s: CountEvents: %v", shape, err)
		}
		if want := map[string]int64{"test.Holder": int64(len(nodeRefs))}; !reflect.DeepEqual(counts, want) {
			t.Errorf("%s: CountEvents = %v, want %v", shape, counts, want)
		}
	}
}
