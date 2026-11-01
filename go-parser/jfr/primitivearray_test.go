package jfr

import (
	"math"
	"reflect"
	"testing"
)

// Parity with the Java parser's primitive array handling (parser-core
// ArrayHolder, UntypedPrimitiveArrayTest): int and long array elements decode to
// int64, the same Go type as a scalar int field, @Timestamp/@Timespan(TICKS)
// elements are normalised to nanoseconds, which do not fit in 32 bits, and int,
// long, float and double elements are stored unboxed in typed slices.
const tIDPrimitiveArrays = 103

var (
	primitiveArrayInts       = []int32{0, 1, -1, 42, math.MaxInt32, math.MinInt32}
	primitiveArrayStampTicks = []int32{testStartTicks, testStartTicks + 7}
	primitiveArraySpanTicks  = []int32{3, math.MaxInt32}
	primitiveArrayLongs      = []int64{math.MaxInt64, -2}
	primitiveArrayFloats     = []float32{0, -1.5, math.MaxFloat32, math.SmallestNonzeroFloat32, float32(math.Inf(-1))}
	primitiveArrayDoubles    = []float64{0, -1.5, math.MaxFloat64, math.SmallestNonzeroFloat64, math.Inf(1)}
)

func primitiveArraysMetadata() *metaElement {
	event := elem("class",
		attr("id", itoa(tIDPrimitiveArrays)),
		attr("name", "test.PrimitiveArrays"),
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
		elem("field", attr("name", "longs"), attr("class", itoa(tIDLong)), attr("dimension", "1")),
		elem("field", attr("name", "floats"), attr("class", itoa(tIDFloat)), attr("dimension", "1")),
		elem("field", attr("name", "doubles"), attr("class", itoa(tIDDouble)), attr("dimension", "1")),
		elem("field", attr("name", "label"), attr("class", itoa(tIDString))),
	)
	return elem("root").with(
		elem("metadata").with(
			primitiveClass(tIDInt, "int"),
			primitiveClass(tIDLong, "long"),
			primitiveClass(tIDFloat, "float"),
			primitiveClass(tIDDouble, "double"),
			primitiveClass(tIDString, "java.lang.String"),
			elem("class", attr("id", itoa(tIDTimestamp)), attr("name", "jdk.jfr.Timestamp")),
			elem("class", attr("id", itoa(tIDTimespan)), attr("name", "jdk.jfr.Timespan")),
			event,
		),
	)
}

// primitiveArraysEvent encodes one test.PrimitiveArrays event body.
func primitiveArraysEvent(ints, stamps, spans []int32, longs []int64, floats []float32, doubles []float64) []byte {
	w := &writer{}
	w.varint(tIDPrimitiveArrays)
	count := int32(-7)
	w.varint(int64(uint32(count)))
	for _, arr := range [][]int32{ints, stamps, spans} {
		w.varint(int64(len(arr)))
		for _, v := range arr {
			// int is written zero extended to its unsigned width.
			w.varint(int64(uint32(v)))
		}
	}
	w.varint(int64(len(longs)))
	for _, v := range longs {
		w.varint(v)
	}
	w.varint(int64(len(floats)))
	for _, v := range floats {
		w.float32Val(v)
	}
	w.varint(int64(len(doubles)))
	for _, v := range doubles {
		w.float64Val(v)
	}
	// A field after the arrays proves the reader stayed aligned.
	w.bytes(utf8String("after-arrays"))
	return w.buf
}

func primitiveArraysRecording(events ...[]byte) []byte {
	spec := chunkSpec{
		metadata:   primitiveArraysMetadata(),
		events:     events,
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}
	return spec.build()
}

func TestPrimitiveArrayElements(t *testing.T) {
	data := primitiveArraysRecording(primitiveArraysEvent(
		primitiveArrayInts, primitiveArrayStampTicks, primitiveArraySpanTicks,
		primitiveArrayLongs, primitiveArrayFloats, primitiveArrayDoubles))
	for _, reuse := range []bool{false, true} {
		var ev map[string]any
		err := NewParser(data).ParseWith(Options{ReuseValues: reuse, OnError: FailOnError}, func(e *Event) error {
			ev = ResolveDeepMap(e.Values) // copies, so recycled arrays cannot change under the checks
			raw := e.Values["ints"].(*Array)
			if raw.Len() != len(primitiveArrayInts) || raw.At(5) != int64(math.MinInt32) || len(raw.Values) != 0 {
				t.Errorf("reuse=%v: ints *Array = %#v", reuse, raw)
			}
			if got := Get(e.Values, "doubles", 4); got != math.Inf(1) {
				t.Errorf("reuse=%v: Get(doubles, 4) = %#v, want +Inf", reuse, got)
			}
			return nil
		})
		if err != nil {
			t.Fatalf("reuse=%v: parse: %v", reuse, err)
		}
		if ev["count"] != int64(-7) {
			t.Errorf("reuse=%v: count = %#v, want int64(-7)", reuse, ev["count"])
		}
		var wantInts []int64
		for _, v := range primitiveArrayInts {
			wantInts = append(wantInts, int64(v))
		}
		checks := []struct {
			field string
			want  any
		}{
			{"ints", wantInts},
			{"stamps", []int64{testStartNanos, testStartNanos + 7*nanosPerTick}},
			{"spans", []int64{3 * nanosPerTick, int64(math.MaxInt32) * nanosPerTick}},
			{"longs", primitiveArrayLongs},
			{"floats", primitiveArrayFloats},
			{"doubles", primitiveArrayDoubles},
		}
		for _, c := range checks {
			if got := ev[c.field]; !reflect.DeepEqual(got, c.want) {
				t.Errorf("reuse=%v: %s = %#v, want %#v", reuse, c.field, got, c.want)
			}
		}
		if ev["label"] != "after-arrays" {
			t.Errorf("reuse=%v: label = %#v, want after-arrays", reuse, ev["label"])
		}
	}
}

// TestPrimitiveArrayElementsAreNotBoxed fails if int, long, float or double
// array elements are boxed into interface values: Go allocates for every such
// conversion of a value outside its small-value cache (runtime.convT64 and
// friends), so a boxed decode costs one allocation per element. The test
// compares a parse of an event with 50,000 element arrays against the same
// event shape with 8 element arrays; the number of allocations must not grow
// with the element count.
func TestPrimitiveArrayElementsAreNotBoxed(t *testing.T) {
	recording := func(n int) []byte {
		ints := make([]int32, n)
		longs := make([]int64, n)
		floats := make([]float32, n)
		doubles := make([]float64, n)
		for i := range ints {
			ints[i] = math.MaxInt32 - int32(i) // outside any small-value cache
			longs[i] = math.MaxInt64 - int64(i)
			floats[i] = float32(i) + 0.5
			doubles[i] = float64(i) + 0.25
		}
		return primitiveArraysRecording(primitiveArraysEvent(ints, nil, nil, longs, floats, doubles))
	}
	small, large := recording(8), recording(50_000)
	for _, reuse := range []bool{false, true} {
		allocs := func(data []byte) float64 {
			return testing.AllocsPerRun(5, func() {
				err := NewParser(data).ParseWith(Options{ReuseValues: reuse, OnError: FailOnError},
					func(e *Event) error { return nil })
				if err != nil {
					t.Fatal(err)
				}
			})
		}
		s, l := allocs(small), allocs(large)
		if l-s > 4 {
			t.Errorf("reuse=%v: %.0f allocations with 8 elements per array, %.0f with 50,000:"+
				" elements are being boxed", reuse, s, l)
		}
	}
}

// An Array built by hand has no record of which slice the parser would have
// filled; Len, At, Get and ResolveDeep must still find its elements.
func TestHandBuiltArray(t *testing.T) {
	arr := &Array{ElementType: "double", Doubles: []float64{1.5, 2.5}}
	if arr.Len() != 2 || arr.At(1) != 2.5 {
		t.Errorf("Len = %d, At(1) = %#v", arr.Len(), arr.At(1))
	}
	root := map[string]any{"a": arr}
	if got := Get(root, "a", 0); got != 1.5 {
		t.Errorf("Get(a, 0) = %#v, want 1.5", got)
	}
	if got := ResolveDeepMap(root)["a"]; !reflect.DeepEqual(got, []float64{1.5, 2.5}) {
		t.Errorf("ResolveDeep = %#v", got)
	}
	if got := arr.String(); got != "[1.5, 2.5]" {
		t.Errorf("String = %q", got)
	}
}
