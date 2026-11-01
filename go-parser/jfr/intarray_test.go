package jfr

import (
	"math"
	"reflect"
	"testing"
)

// Parity check for the Java parser's int array fix (parser-core ArrayHolder,
// UntypedIntArrayTest): an event field of type int with dimension 1 decodes
// to int64 elements, the same Go type as a scalar int field, and
// @Timestamp/@Timespan(TICKS) elements are normalised to nanoseconds, which
// do not fit in 32 bits.
const tIDIntArrays = 103

var (
	intArrayInts       = []int32{0, 1, -1, 42, math.MaxInt32, math.MinInt32}
	intArrayStampTicks = []int32{testStartTicks, testStartTicks + 7}
	intArraySpanTicks  = []int32{3, math.MaxInt32}
)

func intArraysMetadata() *metaElement {
	event := elem("class",
		attr("id", itoa(tIDIntArrays)),
		attr("name", "test.IntArrays"),
		attr("superType", "jdk.jfr.Event"),
	).with(
		elem("field", attr("name", "count"), attr("class", itoa(tIDInt))),
		elem("field", attr("name", "ints"), attr("class", itoa(tIDInt)), attr("dimension", "1")),
		elem("field", attr("name", "stamps"), attr("class", itoa(tIDInt)), attr("dimension", "1")).with(
			elem("annotation", attr("class", itoa(tIDTimestamp)), attr("value", "TICKS")),
		),
		elem("field", attr("name", "spans"), attr("class", itoa(tIDInt)), attr("dimension", "1")).with(
			elem("annotation", attr("class", itoa(tIDTimespan)), attr("value", "TICKS")),
		),
		elem("field", attr("name", "label"), attr("class", itoa(tIDString))),
	)
	return elem("root").with(
		elem("metadata").with(
			primitiveClass(tIDInt, "int"),
			primitiveClass(tIDString, "java.lang.String"),
			elem("class", attr("id", itoa(tIDTimestamp)), attr("name", "jdk.jfr.Timestamp")),
			elem("class", attr("id", itoa(tIDTimespan)), attr("name", "jdk.jfr.Timespan")),
			event,
		),
	)
}

func intArraysRecording() []byte {
	w := &writer{}
	w.varint(tIDIntArrays)
	count := int32(-7)
	w.varint(int64(uint32(count)))
	for _, arr := range [][]int32{intArrayInts, intArrayStampTicks, intArraySpanTicks} {
		w.varint(int64(len(arr)))
		for _, v := range arr {
			// int is written zero extended to its unsigned width.
			w.varint(int64(uint32(v)))
		}
	}
	w.bytes(utf8String("after-arrays"))
	spec := chunkSpec{
		metadata:   intArraysMetadata(),
		events:     [][]byte{w.buf},
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}
	return spec.build()
}

func TestIntArrayElementsMatchScalarInt(t *testing.T) {
	var seen []map[string]any
	err := NewParser(intArraysRecording()).ParseWith(Options{OnError: FailOnError}, func(e *Event) error {
		seen = append(seen, e.Values)
		return nil
	})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if len(seen) != 1 {
		t.Fatalf("saw %d events, want 1", len(seen))
	}
	ev := seen[0]
	if ev["count"] != int64(-7) {
		t.Errorf("count = %#v, want int64(-7)", ev["count"])
	}
	elements := func(field string) []any {
		arr, ok := ev[field].(*Array)
		if !ok {
			t.Fatalf("%s = %#v, want *Array", field, ev[field])
		}
		return arr.Values
	}
	var wantInts []any
	for _, v := range intArrayInts {
		wantInts = append(wantInts, int64(v))
	}
	if got := elements("ints"); !reflect.DeepEqual(got, wantInts) {
		t.Errorf("ints = %#v, want %#v", got, wantInts)
	}
	wantStamps := []any{int64(testStartNanos), int64(testStartNanos + 7*nanosPerTick)}
	if got := elements("stamps"); !reflect.DeepEqual(got, wantStamps) {
		t.Errorf("stamps = %#v, want %#v", got, wantStamps)
	}
	wantSpans := []any{int64(3 * nanosPerTick), int64(math.MaxInt32) * nanosPerTick}
	if got := elements("spans"); !reflect.DeepEqual(got, wantSpans) {
		t.Errorf("spans = %#v, want %#v", got, wantSpans)
	}
	if ev["label"] != "after-arrays" {
		t.Errorf("label = %#v, want after-arrays", ev["label"])
	}
}
