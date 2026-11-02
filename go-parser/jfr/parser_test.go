package jfr

import (
	"errors"
	"fmt"
	"math"
	"reflect"
	"strings"
	"testing"
)

const (
	tIDBoolean = 1
	tIDByte    = 2
	tIDChar    = 3
	tIDShort   = 4
	tIDInt     = 5
	tIDLong    = 6
	tIDFloat   = 7
	tIDDouble  = 8
	tIDString  = 9

	tIDSymbol    = 20
	tIDNested    = 30
	tIDTimestamp = 40
	tIDTimespan  = 41
	tIDSample    = 100
)

// The synthetic recording ticks 1000 times slower than nanoseconds, so tick
// normalisation is visible in the decoded values.
const (
	testFrequency  = 1_000_000
	testStartTicks = 5_000_000
	testStartNanos = 1_700_000_000_000_000_000
	nanosPerTick   = 1_000_000_000 / testFrequency
)

func primitiveClass(id int64, name string) *metaElement {
	return elem("class", attr("id", itoa(id)), attr("name", name), attr("simpleType", "true"))
}

func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

// testMetadata declares the type universe of the synthetic recording.
func testMetadata() *metaElement {
	sample := elem("class",
		attr("id", itoa(tIDSample)),
		attr("name", "test.Sample"),
		attr("superType", "jdk.jfr.Event"),
	).with(
		elem("setting", attr("name", "enabled"), attr("class", itoa(tIDBoolean)), attr("defaultValue", "true")),
		elem("field", attr("name", "startTime"), attr("class", itoa(tIDLong))).with(
			elem("annotation", attr("class", itoa(tIDTimestamp)), attr("value", "TICKS")),
		),
		elem("field", attr("name", "duration"), attr("class", itoa(tIDLong))).with(
			elem("annotation", attr("class", itoa(tIDTimespan)), attr("value", "TICKS")),
		),
		elem("field", attr("name", "count"), attr("class", itoa(tIDInt))),
		elem("field", attr("name", "small"), attr("class", itoa(tIDShort))),
		elem("field", attr("name", "code"), attr("class", itoa(tIDChar))),
		elem("field", attr("name", "tag"), attr("class", itoa(tIDByte))),
		elem("field", attr("name", "flag"), attr("class", itoa(tIDBoolean))),
		elem("field", attr("name", "weight"), attr("class", itoa(tIDFloat))),
		elem("field", attr("name", "ratio"), attr("class", itoa(tIDDouble))),
		elem("field", attr("name", "name"), attr("class", itoa(tIDString))),
		elem("field", attr("name", "symbol"), attr("class", itoa(tIDSymbol)), attr("constantPool", "true")),
		elem("field", attr("name", "values"), attr("class", itoa(tIDLong)), attr("dimension", "1")),
		elem("field", attr("name", "nested"), attr("class", itoa(tIDNested))),
	)

	nested := elem("class", attr("id", itoa(tIDNested)), attr("name", "test.Nested")).with(
		elem("field", attr("name", "a"), attr("class", itoa(tIDInt))),
		elem("field", attr("name", "b"), attr("class", itoa(tIDString))),
	)

	symbol := elem("class",
		attr("id", itoa(tIDSymbol)),
		attr("name", "jdk.types.Symbol"),
		attr("simpleType", "true"),
	).with(
		elem("field", attr("name", "string"), attr("class", itoa(tIDString))),
	)

	return elem("root").with(
		elem("metadata").with(
			primitiveClass(tIDBoolean, "boolean"),
			primitiveClass(tIDByte, "byte"),
			primitiveClass(tIDChar, "char"),
			primitiveClass(tIDShort, "short"),
			primitiveClass(tIDInt, "int"),
			primitiveClass(tIDLong, "long"),
			primitiveClass(tIDFloat, "float"),
			primitiveClass(tIDDouble, "double"),
			primitiveClass(tIDString, "java.lang.String"),
			elem("class", attr("id", itoa(tIDTimestamp)), attr("name", "jdk.jfr.Timestamp")),
			elem("class", attr("id", itoa(tIDTimespan)), attr("name", "jdk.jfr.Timespan")),
			symbol,
			nested,
			sample,
		),
		elem("region", attr("dst", "0"), attr("gmtOffset", "3600"), attr("locale", "en_US")),
	)
}

// sampleEvent encodes one test.Sample event body.
type sampleEvent struct {
	startTicks    int64
	durationTicks int64
	count         int32
	small         int16
	code          uint16
	tag           int8
	flag          bool
	weight        float32
	ratio         float64
	name          []byte // encoded string
	symbol        int64  // constant pool index
	values        []int64
	nestedA       int32
	nestedB       []byte // encoded string
}

func (s sampleEvent) encode() []byte {
	w := &writer{}
	w.varint(tIDSample)
	w.varint(s.startTicks)
	w.varint(s.durationTicks)
	// int, short and char are written zero extended to their unsigned width.
	w.varint(int64(uint32(s.count)))
	w.varint(int64(uint16(s.small)))
	w.varint(int64(s.code))
	w.byteVal(byte(s.tag))
	if s.flag {
		w.byteVal(1)
	} else {
		w.byteVal(0)
	}
	w.float32Val(s.weight)
	w.float64Val(s.ratio)
	w.bytes(s.name)
	w.varint(s.symbol)
	w.varint(int64(len(s.values)))
	for _, v := range s.values {
		w.varint(v)
	}
	w.varint(int64(uint32(s.nestedA)))
	w.bytes(s.nestedB)
	return w.buf
}

func testRecording() []byte {
	events := [][]byte{
		sampleEvent{
			startTicks: testStartTicks + 100, durationTicks: 250,
			count: -7, small: -300, code: 'A', tag: -3, flag: true,
			weight: 1.5, ratio: 0.25,
			name:   stringRef(1),
			symbol: 7,
			values: []int64{10, 20, 30},
			// The nested string is inline rather than pooled.
			nestedA: 42, nestedB: utf8String("inline"),
		}.encode(),
		sampleEvent{
			startTicks: testStartTicks + 2000, durationTicks: 0,
			count: math.MaxInt32, small: math.MaxInt16, code: 0xFFFF, tag: 127, flag: false,
			weight: -0.5, ratio: -1.5,
			name:    nullString(),
			symbol:  999, // dangling: no such constant pool entry
			values:  nil,
			nestedA: 0, nestedB: utf8String(""),
		}.encode(),
	}

	spec := chunkSpec{
		metadata: testMetadata(),
		pools: []constantPoolData{
			{typeID: tIDString, entries: []poolEntry{{id: 1, data: utf8String("event-name")}}},
			{typeID: tIDSymbol, entries: []poolEntry{{id: 7, data: utf8String("sym-seven")}}},
		},
		events:     events,
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}
	return spec.build()
}

func parseAll(t *testing.T, data []byte, opts Options) []*Event {
	t.Helper()
	var events []*Event
	if err := NewParser(data).ParseWith(opts, func(e *Event) error {
		events = append(events, e)
		return nil
	}); err != nil {
		t.Fatalf("parse: %v", err)
	}
	return events
}

func TestParseSyntheticRecording(t *testing.T) {
	events := parseAll(t, testRecording(), Options{OnError: FailOnError})
	if len(events) != 2 {
		t.Fatalf("expected 2 events, got %d", len(events))
	}

	first := events[0]
	if first.Type.Name != "test.Sample" {
		t.Errorf("type = %q, want test.Sample", first.Type.Name)
	}
	if !first.Type.IsEvent() {
		t.Errorf("test.Sample should be recognised as an event type")
	}

	wantStart := int64(testStartNanos + 100*nanosPerTick)
	if got := first.Values["startTime"]; got != wantStart {
		t.Errorf("startTime = %v, want %d (tick normalised)", got, wantStart)
	}
	if got := first.Values["duration"]; got != int64(250*nanosPerTick) {
		t.Errorf("duration = %v, want %d (tick normalised)", got, 250*nanosPerTick)
	}

	checks := []struct {
		field string
		want  any
	}{
		{"count", int64(-7)},
		{"small", int16(-300)},
		{"code", uint16('A')},
		{"tag", int8(-3)},
		{"flag", true},
		{"weight", float32(1.5)},
		{"ratio", 0.25},
		{"name", "event-name"},
	}
	for _, c := range checks {
		if got := first.Values[c.field]; !reflect.DeepEqual(got, c.want) {
			t.Errorf("%s = %#v (%T), want %#v (%T)", c.field, got, got, c.want, c.want)
		}
	}

	arr, ok := first.Values["values"].(*Array)
	if !ok {
		t.Fatalf("values = %#v, want *Array", first.Values["values"])
	}
	if arr.ElementType != "long" {
		t.Errorf("values element type = %q, want long", arr.ElementType)
	}
	if !reflect.DeepEqual(arr.Elems, []int64{10, 20, 30}) {
		t.Errorf("values = %#v", arr)
	}

	nested, ok := first.Values["nested"].(map[string]any)
	if !ok {
		t.Fatalf("nested = %#v, want map", first.Values["nested"])
	}
	if nested["a"] != int64(42) || nested["b"] != "inline" {
		t.Errorf("nested = %#v", nested)
	}

	ref, ok := first.Values["symbol"].(*Ref)
	if !ok {
		t.Fatalf("symbol = %#v, want *Ref", first.Values["symbol"])
	}
	if ref.Type() == nil || ref.Type().Name != "jdk.types.Symbol" {
		t.Errorf("symbol ref type = %v", ref.Type())
	}
	if got, _ := GetString(first.Values, "symbol", "string"); got != "sym-seven" {
		t.Errorf("symbol/string = %q, want sym-seven", got)
	}
}

func TestParseSyntheticRecordingEdgeValues(t *testing.T) {
	events := parseAll(t, testRecording(), Options{OnError: FailOnError})
	second := events[1]

	if got := second.Values["count"]; got != int64(math.MaxInt32) {
		t.Errorf("count = %v, want %d", got, math.MaxInt32)
	}
	if got := second.Values["code"]; got != uint16(0xFFFF) {
		t.Errorf("code = %v, want 0xFFFF", got)
	}
	if got, ok := second.Values["name"]; !ok || got != nil {
		t.Errorf("name = %#v, want a nil value for a null string", got)
	}
	arr, ok := second.Values["values"].(*Array)
	if !ok || arr.Len() != 0 {
		t.Errorf("values = %#v, want an empty array", second.Values["values"])
	}

	// A reference with no matching constant pool entry resolves to nil rather
	// than failing.
	ref := second.Values["symbol"].(*Ref)
	if v := ref.Value(); v != nil {
		t.Errorf("dangling ref resolved to %#v, want nil", v)
	}
	if err := ref.Err(); err != nil {
		t.Errorf("dangling ref reported %v, want no error", err)
	}
}

func TestChunkInfoTickConversion(t *testing.T) {
	var info *ChunkInfo
	if err := NewParser(testRecording()).ParseWith(Options{
		OnChunkStart: func(ci *ChunkInfo, _ *Metadata) error {
			info = ci
			return nil
		},
	}, nil); err != nil {
		t.Fatalf("parse: %v", err)
	}
	if info == nil {
		t.Fatal("OnChunkStart was not called")
	}
	if got := info.AsDurationNanos(3); got != 3*nanosPerTick {
		t.Errorf("AsDurationNanos(3) = %d, want %d", got, 3*nanosPerTick)
	}
	if got := info.AsEpochNanos(testStartTicks + 5); got != testStartNanos+5*nanosPerTick {
		t.Errorf("AsEpochNanos = %d", got)
	}
	if got := info.StartTime().UnixNano(); got != testStartNanos {
		t.Errorf("StartTime = %d, want %d", got, testStartNanos)
	}
}

func TestMetadataExposesTypes(t *testing.T) {
	var md *Metadata
	if err := NewParser(testRecording()).ParseWith(Options{
		OnChunkStart: func(_ *ChunkInfo, m *Metadata) error {
			md = m
			return nil
		},
	}, nil); err != nil {
		t.Fatalf("parse: %v", err)
	}
	sample := md.ClassByName("test.Sample")
	if sample == nil {
		t.Fatal("test.Sample not found in the chunk metadata")
	}
	if sample.ID != tIDSample {
		t.Errorf("test.Sample id = %d, want %d", sample.ID, tIDSample)
	}
	if md.Class(tIDSample) != sample {
		t.Error("lookup by id and by name disagree")
	}
	if got := sample.SimpleName(); got != "Sample" {
		t.Errorf("SimpleName = %q, want Sample", got)
	}
	if f := sample.Field("values"); f == nil || !f.IsArray() {
		t.Errorf("values field = %#v, want an array field", f)
	}
	if f := sample.Field("symbol"); f == nil || !f.ConstantPool {
		t.Errorf("symbol field = %#v, want a constant pool field", f)
	}
	if s := sample.Settings["enabled"]; s == nil || s.DefaultValue != "true" {
		t.Errorf("enabled setting = %#v", sample.Settings["enabled"])
	}
	if types := md.EventTypes(); len(types) != 1 || types[0] != sample {
		t.Errorf("EventTypes = %v, want [test.Sample]", types)
	}
}

func TestTypeFilterSkipsEvents(t *testing.T) {
	events := parseAll(t, testRecording(), Options{
		TypeFilter: func(c *ClassType) bool { return false },
		OnError:    FailOnError,
	})
	if len(events) != 0 {
		t.Errorf("expected no events, got %d", len(events))
	}
}

func TestErrStopEndsParsing(t *testing.T) {
	n := 0
	err := NewParser(testRecording()).Parse(func(*Event) error {
		n++
		return ErrStop
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if n != 1 {
		t.Errorf("handler called %d times, want 1", n)
	}
}

func TestHandlerErrorAborts(t *testing.T) {
	boom := errString("boom")
	err := NewParser(testRecording()).Parse(func(*Event) error { return boom })
	if err != boom {
		t.Errorf("parse error = %v, want %v", err, boom)
	}
}

type errString string

func (e errString) Error() string { return string(e) }

func TestMultipleChunks(t *testing.T) {
	one := testRecording()
	data := append(append([]byte{}, one...), one...)
	var chunks []int
	events := 0
	if err := NewParser(data).ParseWith(Options{
		OnChunkStart: func(ci *ChunkInfo, _ *Metadata) error {
			chunks = append(chunks, ci.ID())
			return nil
		},
		OnError: FailOnError,
	}, func(*Event) error {
		events++
		return nil
	}); err != nil {
		t.Fatalf("parse: %v", err)
	}
	if !reflect.DeepEqual(chunks, []int{1, 2}) {
		t.Errorf("chunks = %v, want [1 2]", chunks)
	}
	if events != 4 {
		t.Errorf("events = %d, want 4", events)
	}
}

func TestInvalidMagic(t *testing.T) {
	data := testRecording()
	data[0] = 'X'
	err := NewParser(data).Parse(nil)
	if err == nil {
		t.Fatal("expected an error for a bad magic number")
	}
}

func TestGzipMagicIsReported(t *testing.T) {
	err := NewParser([]byte{0x1f, 0x8b, 0x08, 0x00, 0, 0, 0, 0}).Parse(nil)
	if err == nil {
		t.Fatal("expected an error for a gzip compressed file")
	}
	if got := err.Error(); !contains(got, "gzip") {
		t.Errorf("error = %q, want it to mention gzip", got)
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

func TestTruncatedRecordingIsRejected(t *testing.T) {
	data := testRecording()
	err := NewParser(data[:len(data)-10]).Parse(nil)
	if err == nil {
		t.Fatal("expected an error for a chunk shorter than its declared size")
	}
}

func TestChunkHeaders(t *testing.T) {
	headers, err := NewParser(testRecording()).ChunkHeaders()
	if err != nil {
		t.Fatalf("ChunkHeaders: %v", err)
	}
	if len(headers) != 1 {
		t.Fatalf("got %d headers, want 1", len(headers))
	}
	h := headers[0]
	if h.Major != 2 || h.Minor != 1 {
		t.Errorf("version = %d.%d, want 2.1", h.Major, h.Minor)
	}
	if !h.CompressedInts() {
		t.Error("expected the compressed integer encoding")
	}
	if h.Frequency != testFrequency || h.StartTicks != testStartTicks {
		t.Errorf("header timing = %+v", h)
	}
}

func TestResolveDeepBreaksCycles(t *testing.T) {
	events := parseAll(t, cyclicRecording(), Options{OnError: FailOnError})
	if len(events) != 2 {
		t.Fatalf("expected 2 events, got %d", len(events))
	}

	// Both events walk the same cycle, so the second must see exactly what the
	// first saw: a memoised truncation would corrupt it.
	for i, e := range events {
		resolved := ResolveDeepMap(e.Values)
		node, ok := resolved["node"].(map[string]any)
		if !ok {
			t.Fatalf("event %d: node = %#v, want a map", i, resolved["node"])
		}
		if node["name"] != "a" {
			t.Errorf("event %d: node.name = %#v, want a", i, node["name"])
		}
		next, ok := node["next"].(map[string]any)
		if !ok {
			t.Fatalf("event %d: node.next = %#v, want a map", i, node["next"])
		}
		if next["name"] != "b" {
			t.Errorf("event %d: node.next.name = %#v, want b", i, next["name"])
		}
		// The back edge to entry 1 is broken rather than followed.
		if v, present := next["next"]; !present || v != nil {
			t.Errorf("event %d: node.next.next = %#v, want nil at the cycle", i, v)
		}
	}
}

func TestResolveDeepMemoisesAcyclicEntries(t *testing.T) {
	events := parseAll(t, testRecording(), Options{OnError: FailOnError})
	if len(events) != 2 {
		t.Fatalf("expected 2 events, got %d", len(events))
	}

	first := ResolveDeepMap(events[0].Values)
	if got := first["symbol"]; !reflect.DeepEqual(got, map[string]any{"string": "sym-seven"}) {
		t.Fatalf("symbol = %#v", got)
	}

	// A second resolution of the same constant pool entry must hand back the
	// memoised structure rather than building a fresh one.
	again := ResolveDeepMap(events[0].Values)
	if !sameMap(first["symbol"], again["symbol"]) {
		t.Error("resolving the same constant pool entry twice rebuilt the value instead of reusing it")
	}
	// The event's own fields are always freshly built, so they stay writable.
	if sameMap(first, again) {
		t.Error("the event map itself must not be shared between resolutions")
	}
}

// sameMap reports whether two values are the same map instance.
func sameMap(a, b any) bool {
	am, aok := a.(map[string]any)
	bm, bok := b.(map[string]any)
	if !aok || !bok {
		return false
	}
	return reflect.ValueOf(am).Pointer() == reflect.ValueOf(bm).Pointer()
}

// snapshot renders an event into a form that survives value recycling, so that
// a recycled parse can be compared against a fresh one.
func snapshot(e *Event) map[string]any {
	return ResolveDeepMap(e.Values)
}

func parseSnapshots(t *testing.T, data []byte, reuse bool) []map[string]any {
	t.Helper()
	var out []map[string]any
	err := NewParser(data).ParseWith(Options{ReuseValues: reuse, OnError: FailOnError}, func(e *Event) error {
		out = append(out, snapshot(e))
		return nil
	})
	if err != nil {
		t.Fatalf("parse (reuse=%t): %v", reuse, err)
	}
	return out
}

func TestReuseValuesDecodesIdentically(t *testing.T) {
	for name, data := range map[string][]byte{
		"synthetic": testRecording(),
		"cyclic":    cyclicRecording(),
		"bag":       bagRecording(),
	} {
		t.Run(name, func(t *testing.T) {
			fresh := parseSnapshots(t, data, false)
			reused := parseSnapshots(t, data, true)
			if !reflect.DeepEqual(fresh, reused) {
				t.Errorf("ReuseValues changed the decoded values\n fresh  = %#v\n reused = %#v", fresh, reused)
			}
		})
	}
}

func TestReuseValuesHandlesVariableArrayLength(t *testing.T) {
	want := [][]string{{"x", "y", "z"}, {}, {"only"}, {"p", "q"}}
	i := 0
	err := NewParser(bagRecording()).ParseWith(Options{ReuseValues: true, OnError: FailOnError}, func(e *Event) error {
		arr, ok := e.Values["items"].(*Array)
		if !ok {
			t.Fatalf("event %d: items = %#v, want *Array", i, e.Values["items"])
		}
		if arr.Len() != len(want[i]) {
			t.Fatalf("event %d: %d items, want %d", i, arr.Len(), len(want[i]))
		}
		for j, item := range arr.Elems.([]any) {
			m, ok := item.(map[string]any)
			if !ok {
				t.Fatalf("event %d item %d: %#v, want a map", i, j, item)
			}
			if m["b"] != want[i][j] {
				t.Errorf("event %d item %d: b = %#v, want %q", i, j, m["b"], want[i][j])
			}
			if m["a"] != int64(j) {
				t.Errorf("event %d item %d: a = %#v, want %d", i, j, m["a"], j)
			}
		}
		i++
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if i != len(want) {
		t.Errorf("saw %d events, want %d", i, len(want))
	}
}

func TestReuseValuesRecyclesTheEventMap(t *testing.T) {
	var seen []uintptr
	err := NewParser(bagRecording()).ParseWith(Options{ReuseValues: true, OnError: FailOnError}, func(e *Event) error {
		seen = append(seen, reflect.ValueOf(e.Values).Pointer())
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(seen) < 2 {
		t.Fatalf("expected several events, got %d", len(seen))
	}
	for i, p := range seen[1:] {
		if p != seen[0] {
			t.Errorf("event %d used a different map; ReuseValues should hand back the same one", i+1)
		}
	}
}

func TestWithoutReuseValuesEventsStayValid(t *testing.T) {
	var kept []map[string]any
	err := NewParser(bagRecording()).Parse(func(e *Event) error {
		kept = append(kept, e.Values)
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	// The default contract lets a handler keep events; each must still hold its
	// own values afterwards.
	labels := []string{"three", "zero", "one", "two"}
	for i, m := range kept {
		if m["label"] != labels[i] {
			t.Errorf("kept event %d: label = %#v, want %q", i, m["label"], labels[i])
		}
	}
}

func TestReuseValuesKeepsConstantPoolValues(t *testing.T) {
	// A value resolved from a constant pool is cached in its pool, so it must
	// survive the events that follow the one that resolved it.
	var first map[string]any
	err := NewParser(cyclicRecording()).ParseWith(Options{ReuseValues: true, OnError: FailOnError}, func(e *Event) error {
		if first == nil {
			ref := e.Values["node"].(*Ref)
			first = ref.Map()
		}
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if first == nil {
		t.Fatal("no event was decoded")
	}
	if first["name"] != "a" {
		t.Errorf("constant pool value was recycled: name = %#v, want a", first["name"])
	}
}

// undecodableBagRecording is bagRecording with the second event's label made
// undecodable: its string encoding tag is replaced by one no encoding uses.
func undecodableBagRecording() []byte {
	bad := bagEvent("bad", []string{"i"})
	tag := varintLen(tIDBag)
	if bad[tag] != strUTF8 {
		panic("label tag not where expected")
	}
	bad[tag] = 0x7f
	spec := chunkSpec{
		metadata: bagMetadata(),
		events: [][]byte{
			bagEvent("first", []string{"x"}),
			bad,
			bagEvent("third", nil),
			bagEvent("fourth", []string{"p", "q"}),
		},
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}
	return spec.build()
}

// An event that fails to decode is reported through OnError and never reaches
// the handler, not even partially; the parser resumes at the next event using
// the size it already read. The Java parser fails the parse by default and
// skips only when its UntypedJafarParser.onDecodeError handler returns true; see
// "Differences from the Java parser" in the README.
func TestUndecodableEventIsReportedAndSkipped(t *testing.T) {
	for _, reuse := range []bool{false, true} {
		var labels []any
		var reported []error
		err := NewParser(undecodableBagRecording()).ParseWith(Options{
			ReuseValues: reuse,
			OnError: func(err error) error {
				reported = append(reported, err)
				return nil
			},
		}, func(e *Event) error {
			if _, ok := e.Values["items"]; !ok {
				t.Errorf("reuse=%v: partially decoded event delivered: %v", reuse, e.Values)
			}
			labels = append(labels, e.Values["label"])
			return nil
		})
		if err != nil {
			t.Fatalf("reuse=%v: parse: %v", reuse, err)
		}
		if want := []any{"first", "third", "fourth"}; !reflect.DeepEqual(labels, want) {
			t.Errorf("reuse=%v: labels = %v, want %v", reuse, labels, want)
		}
		if len(reported) != 1 || !strings.Contains(reported[0].Error(), "test.Bag") ||
			!strings.Contains(reported[0].Error(), "unexpected string constant id: 127") {
			t.Errorf("reuse=%v: reported = %v, want one error naming test.Bag and the bad tag", reuse, reported)
		}
	}

	err := NewParser(undecodableBagRecording()).ParseWith(Options{OnError: FailOnError}, func(*Event) error { return nil })
	if err == nil || !strings.Contains(err.Error(), "test.Bag") {
		t.Errorf("FailOnError: parse error = %v, want one naming test.Bag", err)
	}
}

// pooledStringRecording has one test.Sample event per name reference in refs,
// all resolved against a java.lang.String pool holding "one", "two" and
// "three" at indexes 1-3. With corruptTwo, entry 2's encoding tag is replaced
// by one no encoding uses, so the entry cannot be skipped while the pool is
// indexed.
func pooledStringRecording(corruptTwo bool, refs ...int64) []byte {
	two := utf8String("two")
	if corruptTwo {
		two[0] = 0x7f
	}
	var events [][]byte
	for _, ref := range refs {
		events = append(events, sampleEvent{
			startTicks: testStartTicks + 100, name: stringRef(ref), symbol: 7,
			nestedB: utf8String("inline"),
		}.encode())
	}
	spec := chunkSpec{
		metadata: testMetadata(),
		pools: []constantPoolData{
			{typeID: tIDSymbol, entries: []poolEntry{{id: 7, data: utf8String("sym-seven")}}},
			// The string pool comes last, so the symbol pool is indexed whatever
			// happens to it.
			{typeID: tIDString, entries: []poolEntry{
				{id: 1, data: utf8String("one")},
				{id: 2, data: two},
				{id: 3, data: utf8String("three")},
			}},
		},
		events:     events,
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}
	return spec.build()
}

// A pool entry whose string encoding is unknown cannot be skipped. It used to
// be skipped as if it had no payload, which misaligned every later entry of the
// checkpoint, so references to them read other bytes or nothing, without an
// error. Now indexing stops there and says so; entries after it are not
// indexed, and references to them are reported rather than read as null.
func TestCorruptedPoolStringIsReportedNotMisaligned(t *testing.T) {
	data := pooledStringRecording(true, 1, 2, 3)

	err := NewParser(data).ParseWith(Options{OnError: FailOnError}, func(*Event) error { return nil })
	if err == nil || !strings.Contains(err.Error(), "constant pool java.lang.String, index 2: unexpected string constant id: 127") {
		t.Fatalf("FailOnError: parse error = %v, want the pool entry named", err)
	}

	var names []any
	var reported []string
	err = NewParser(data).ParseWith(Options{
		OnError: func(err error) error {
			reported = append(reported, err.Error())
			return nil
		},
	}, func(e *Event) error {
		names = append(names, e.Values["name"])
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if want := []any{"one"}; !reflect.DeepEqual(names, want) {
		t.Errorf("names = %v, want %v (events referencing unindexed entries are reported, not delivered)", names, want)
	}
	if len(reported) != 3 ||
		!strings.Contains(reported[0], "index 2: unexpected string constant id: 127") ||
		!strings.Contains(reported[1], "string constant 2 not found") ||
		!strings.Contains(reported[2], "string constant 3 not found") {
		t.Errorf("reported = %q, want the pool entry, then references 2 and 3", reported)
	}

	// Each event error carries the indexing failure as its cause, as for any
	// other pooled field (TestReferenceToUnindexedPoolEntryIsReportedNotResolvedToNil).
	var errs []error
	if err := NewParser(data).ParseWith(Options{
		OnError: func(err error) error {
			errs = append(errs, err)
			return nil
		},
	}, func(*Event) error { return nil }); err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(errs) != 3 {
		t.Fatalf("reported %d errors, want 3: %v", len(errs), errs)
	}
	for _, e := range errs[1:] {
		if !errors.Is(e, errs[0]) || !strings.Contains(e.Error(), "indexing stopped early") {
			t.Errorf("event error %q does not wrap the indexing failure", e)
		}
	}
}

// A reference to a string pool entry that does not exist is an error, not a
// null string.
func TestDanglingStringReferenceIsReported(t *testing.T) {
	data := pooledStringRecording(false, 1, 9, 3)

	var names []any
	var reported []string
	err := NewParser(data).ParseWith(Options{
		OnError: func(err error) error {
			reported = append(reported, err.Error())
			return nil
		},
	}, func(e *Event) error {
		names = append(names, e.Values["name"])
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if want := []any{"one", "three"}; !reflect.DeepEqual(names, want) {
		t.Errorf("names = %v, want %v", names, want)
	}
	if len(reported) != 1 || !strings.Contains(reported[0], "string constant 9 not found in the java.lang.String pool") {
		t.Errorf("reported = %q, want one error for reference 9", reported)
	}
}

// --- other pooled fields with a corrupted entry -----------------------------

// nodeRefEvent encodes a test.Cyclic event whose node field is the given
// constant pool index.
func nodeRefEvent(node int64) []byte {
	w := &writer{}
	w.varint(tIDCyclic)
	w.varint(node)
	return w.buf
}

// nodeRefs are the node indices of the events of nodePoolRecording, in order:
// an indexed entry whose next is 0, index 0 (how HotSpot writes an absent
// value), math.MinInt64 (how the JMC writer writes a null one), an indexed
// entry whose next is an entry stored after the bad one, the bad entry, an
// entry stored after it, and an index the chunk never declared.
var nodeRefs = []int64{5, 0, math.MinInt64, 1, 2, 3, 9}

// nodePoolRecording stores test.Node entries 5, 1, 2, 3 in that order. With
// corrupt, the name of entry 2 has an encoding id no encoding uses, so
// indexing its checkpoint stops there and entries 2 and 3 are abandoned.
func nodePoolRecording(corrupt bool) []byte {
	bad := nodeEntry("b", 0)
	if corrupt {
		bad[0] = 0x7f
	}
	events := make([][]byte, len(nodeRefs))
	for i, n := range nodeRefs {
		events[i] = nodeRefEvent(n)
	}
	spec := chunkSpec{
		metadata: cyclicMetadata(),
		pools: []constantPoolData{
			{typeID: tIDNode, entries: []poolEntry{
				{id: 5, data: nodeEntry("e", 0)},
				{id: 1, data: nodeEntry("a", 3)},
				{id: 2, data: bad},
				{id: 3, data: nodeEntry("c", 0)},
			}},
		},
		events:     events,
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}
	return spec.build()
}

// In a chunk whose checkpoints were not indexed completely, a non-zero
// reference from any pooled field to an entry missing from its pool fails the
// event, with the indexing failure as its cause: the entry may be one that was
// declared and abandoned, and resolving it to nil would pass it off as absent.
// Index 0 and math.MinInt64 stay nil references. A reference from inside an indexed entry to an
// abandoned one makes that entry undecodable, which Ref.Err reports. The Java
// parser applies the same policy in ConstantPoolAccessor.
func TestReferenceToUnindexedPoolEntryIsReportedNotResolvedToNil(t *testing.T) {
	for _, reuse := range []bool{false, true} {
		reuse := reuse
		t.Run(fmt.Sprintf("reuse=%v", reuse), func(t *testing.T) {
			// With ReuseValues the *Ref values are recycled once the handler
			// returns, so everything is read inside it.
			type seen struct {
				index int64
				name  any   // the resolved entry's name, or nil
				next  any   // the resolved entry's next, resolved, or nil
				err   error // Ref.Err after resolving
			}
			var delivered []seen
			var reported []error
			err := NewParser(nodePoolRecording(true)).ParseWith(Options{
				ReuseValues: reuse,
				OnError: func(err error) error {
					reported = append(reported, err)
					return nil
				},
			}, func(e *Event) error {
				ref, ok := e.Values["node"].(*Ref)
				if !ok {
					t.Fatalf("reuse=%v: node = %#v, want a *Ref", reuse, e.Values["node"])
				}
				s := seen{index: ref.Index()}
				if m, ok := ref.Value().(map[string]any); ok {
					s.name = m["name"]
					next, ok := m["next"].(*Ref)
					if !ok {
						t.Fatalf("reuse=%v: entry %d next = %#v, want a *Ref", reuse, ref.Index(), m["next"])
					}
					if next.Err() != nil {
						t.Errorf("reuse=%v: entry %d next: Err = %v", reuse, ref.Index(), next.Err())
					}
					s.next = next.Value()
				}
				s.err = ref.Err()
				if s.err != nil {
					// An undecodable entry is decoded once; resolving it again
					// returns the cached error (Java: MutableConstantPool's
					// mapFailures rethrows the same exception).
					if ref.Value() != nil || ref.Err() != s.err {
						t.Errorf("reuse=%v: entry %d resolved again: Err = %v, want the cached %v", reuse, ref.Index(), ref.Err(), s.err)
					}
				}
				delivered = append(delivered, s)
				return nil
			})
			if err != nil {
				t.Fatalf("reuse=%v: parse: %v", reuse, err)
			}
			var got []int64
			for _, d := range delivered {
				got = append(got, d.index)
			}
			if want := []int64{5, 0, math.MinInt64, 1}; !reflect.DeepEqual(got, want) {
				t.Fatalf("reuse=%v: delivered node indices = %v, want %v; reported: %v", reuse, got, want, reported)
			}
			if len(reported) != 4 {
				t.Fatalf("reuse=%v: reported %d errors, want 4 (indexing, then the events referring to 2, 3 and 9): %v", reuse, len(reported), reported)
			}
			indexing := reported[0]
			for _, want := range []string{"test.Node, index 2", "unexpected string constant id: 127"} {
				if !strings.Contains(indexing.Error(), want) {
					t.Errorf("reuse=%v: indexing error %q does not mention %q", reuse, indexing, want)
				}
			}
			for i, idx := range []int64{2, 3, 9} {
				msg := reported[i+1].Error()
				ref := "constant pool reference " + itoa(idx) + " to test.Node does not resolve"
				if !strings.Contains(msg, "test.Cyclic") || !strings.Contains(msg, ref) ||
					!strings.Contains(msg, "indexing stopped early") {
					t.Errorf("reuse=%v: event error %q should name test.Cyclic, %q and the indexing failure", reuse, msg, ref)
				}
				if !errors.Is(reported[i+1], indexing) {
					t.Errorf("reuse=%v: event error %q does not wrap the indexing failure", reuse, msg)
				}
			}

			// Entry 5 was indexed and its own next is 0: both stay as they were.
			if d := delivered[0]; d.name != "e" || d.next != nil || d.err != nil {
				t.Errorf("reuse=%v: entry 5 = %+v, want name e, next nil, no error", reuse, d)
			}
			// Index 0 and math.MinInt64 are absent values.
			for _, d := range delivered[1:3] {
				if d.name != nil || d.err != nil {
					t.Errorf("reuse=%v: node %d = %+v, want nil without error", reuse, d.index, d)
				}
			}
			// Entry 1 was indexed, but its next points at abandoned entry 3.
			d := delivered[3]
			if d.name != nil {
				t.Errorf("reuse=%v: entry 1 = %+v, want nil: its next refers to an abandoned entry", reuse, d)
			}
			if d.err == nil || !errors.Is(d.err, indexing) ||
				!strings.Contains(d.err.Error(), "constant pool reference 3 to test.Node does not resolve") {
				t.Errorf("reuse=%v: entry 1 error = %v, want reference 3 wrapping the indexing failure", reuse, d.err)
			}
		})
	}

	err := NewParser(nodePoolRecording(true)).ParseWith(Options{OnError: FailOnError}, func(*Event) error { return nil })
	if err == nil || !strings.Contains(err.Error(), "unexpected string constant id: 127") {
		t.Errorf("FailOnError: parse error = %v, want the indexing failure", err)
	}
}

// Without an indexing failure nothing changes: a dangling non-zero reference,
// like index 0, resolves to nil without an error, as the JDK's own reader does.
func TestDanglingReferenceInIntactChunkResolvesToNil(t *testing.T) {
	var refs []*Ref
	err := NewParser(nodePoolRecording(false)).ParseWith(Options{OnError: FailOnError}, func(e *Event) error {
		refs = append(refs, e.Values["node"].(*Ref))
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(refs) != len(nodeRefs) {
		t.Fatalf("delivered %d events, want %d", len(refs), len(nodeRefs))
	}
	var names []any
	for _, r := range refs {
		if r.Err() != nil {
			t.Errorf("node %d: Err = %v", r.Index(), r.Err())
		}
		m, _ := r.Value().(map[string]any)
		if m == nil {
			names = append(names, nil)
		} else {
			names = append(names, m["name"])
		}
	}
	if want := []any{"e", nil, nil, "a", "b", "c", nil}; !reflect.DeepEqual(names, want) {
		t.Errorf("names = %v, want %v", names, want)
	}
}
