package jfr

import (
	"math"
	"reflect"
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
	if !reflect.DeepEqual(arr.Values, []any{int64(10), int64(20), int64(30)}) {
		t.Errorf("values = %#v", arr.Values)
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
