package jfr

import (
	"math"
	"reflect"
	"runtime"
	"testing"
	"time"
)

// Parity with the Java parser's primitive array handling (parser-core
// ArrayHolder, UntypedPrimitiveArrayTest): int and long array elements decode to
// int64, the same Go type as a scalar int field, @Timestamp/@Timespan(TICKS)
// elements are normalised to nanoseconds, which do not fit in 32 bits, and int,
// long, float, double, short and char elements are stored unboxed in typed
// slices.
const tIDPrimitiveArrays = 103

var (
	primitiveArrayInts       = []int32{0, 1, -1, 42, math.MaxInt32, math.MinInt32}
	primitiveArrayStampTicks = []int32{testStartTicks, testStartTicks + 7}
	primitiveArraySpanTicks  = []int32{3, math.MaxInt32}
	primitiveArrayLongs      = []int64{math.MaxInt64, -2}
	primitiveArrayFloats     = []float32{0, -1.5, math.MaxFloat32, math.SmallestNonzeroFloat32, float32(math.Inf(-1))}
	primitiveArrayDoubles    = []float64{0, -1.5, math.MaxFloat64, math.SmallestNonzeroFloat64, math.Inf(1)}
	primitiveArrayShorts     = []int16{0, -1, 1000, math.MinInt16, math.MaxInt16}
	primitiveArrayChars      = []uint16{0, 'A', 200, math.MaxUint16}
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
		elem("field", attr("name", "shorts"), attr("class", itoa(tIDShort)), attr("dimension", "1")),
		elem("field", attr("name", "chars"), attr("class", itoa(tIDChar)), attr("dimension", "1")),
		elem("field", attr("name", "label"), attr("class", itoa(tIDString))),
	)
	return elem("root").with(
		elem("metadata").with(
			primitiveClass(tIDInt, "int"),
			primitiveClass(tIDLong, "long"),
			primitiveClass(tIDFloat, "float"),
			primitiveClass(tIDDouble, "double"),
			primitiveClass(tIDShort, "short"),
			primitiveClass(tIDChar, "char"),
			primitiveClass(tIDString, "java.lang.String"),
			elem("class", attr("id", itoa(tIDTimestamp)), attr("name", "jdk.jfr.Timestamp")),
			elem("class", attr("id", itoa(tIDTimespan)), attr("name", "jdk.jfr.Timespan")),
			event,
		),
	)
}

// primitiveArraysEvent encodes one test.PrimitiveArrays event body.
func primitiveArraysEvent(ints, stamps, spans []int32, longs []int64, floats []float32, doubles []float64,
	shorts []int16, chars []uint16) []byte {
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
	// short and char are written zero extended to their unsigned 16 bit width.
	w.varint(int64(len(shorts)))
	for _, v := range shorts {
		w.varint(int64(uint16(v)))
	}
	w.varint(int64(len(chars)))
	for _, v := range chars {
		w.varint(int64(v))
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
		primitiveArrayLongs, primitiveArrayFloats, primitiveArrayDoubles,
		primitiveArrayShorts, primitiveArrayChars))
	for _, reuse := range []bool{false, true} {
		var ev map[string]any
		err := NewParser(data).ParseWith(Options{ReuseValues: reuse, OnError: FailOnError}, func(e *Event) error {
			ev = ResolveDeepMap(e.Values) // copies, so recycled arrays cannot change under the checks
			raw := e.Values["ints"].(*Array)
			if _, ok := raw.Elems.([]int64); !ok {
				t.Errorf("reuse=%v: ints Elems is %T, want []int64", reuse, raw.Elems)
			}
			if raw.Len() != len(primitiveArrayInts) || raw.At(5) != int64(math.MinInt32) {
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
			{"shorts", primitiveArrayShorts},
			{"chars", primitiveArrayChars},
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

// TestPrimitiveArrayElementsAreNotBoxed fails if int, long, float, double,
// short or char array elements are boxed into interface values: Go allocates
// for every such conversion of a value outside its small-value table
// (runtime.convT64, convT16 and friends; the table covers 0..255), so a boxed
// decode costs one allocation per element. The test
// compares a parse of an event with 50,000 element arrays against the same
// event shape with 8 element arrays; the number of allocations must not grow
// with the element count.
func TestPrimitiveArrayElementsAreNotBoxed(t *testing.T) {
	recording := func(n int) []byte {
		ints := make([]int32, n)
		longs := make([]int64, n)
		floats := make([]float32, n)
		doubles := make([]float64, n)
		shorts := make([]int16, n)
		chars := make([]uint16, n)
		for i := range ints {
			ints[i] = math.MaxInt32 - int32(i) // outside any small-value cache
			longs[i] = math.MaxInt64 - int64(i)
			floats[i] = float32(i) + 0.5
			doubles[i] = float64(i) + 0.25
			shorts[i] = int16(1000 + i%30000) // >= 256, outside the small-value table
			chars[i] = uint16(256 + i)
		}
		return primitiveArraysRecording(primitiveArraysEvent(ints, nil, nil, longs, floats, doubles, shorts, chars))
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

// An Array built by hand must work with Len, At, Get, ResolveDeep and String.
func TestHandBuiltArray(t *testing.T) {
	arr := &Array{ElementType: "double", Elems: []float64{1.5, 2.5}}
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

// The README and the Java parity note claim single-byte values never allocate
// when converted to an interface, which is why byte and boolean arrays stay in
// Values. Pin that claim, and the converse for 16 bit values that the typed
// Shorts and Chars slices exist for.
func TestInterfaceConversionCosts(t *testing.T) {
	var sink any
	b, z, s := int8(-100), true, int16(1000)
	if n := testing.AllocsPerRun(100, func() { sink = b; sink = z }); n != 0 {
		t.Errorf("int8/bool to interface: %.0f allocs, want 0", n)
	}
	if n := testing.AllocsPerRun(100, func() { sink = s }); n != 1 {
		t.Errorf("int16(1000) to interface: %.0f allocs, want 1", n)
	}
	_ = sink
}

// With ReuseValues, a recycled array whose element count matches the previous
// event's must not store its slice in Elems again: converting a slice to an
// interface allocates its header (runtime.convTslice), which would put one
// allocation per array back on every event.
//
// The event's scalar fields allocate per event regardless (count, an int64
// outside Go's small-value table, and label, a string, are boxed into the
// event map), so the per-event cost of events with filled arrays is compared
// against the same events with empty arrays, which hold a nil slice.
func TestReusedArraysDoNotReboxTheirSlice(t *testing.T) {
	recording := func(events int, filled bool) []byte {
		bodies := make([][]byte, events)
		for i := range bodies {
			if filled {
				bodies[i] = primitiveArraysEvent(primitiveArrayInts, primitiveArrayStampTicks,
					primitiveArraySpanTicks, primitiveArrayLongs, primitiveArrayFloats,
					primitiveArrayDoubles, primitiveArrayShorts, primitiveArrayChars)
			} else {
				bodies[i] = primitiveArraysEvent(nil, nil, nil, nil, nil, nil, nil, nil)
			}
		}
		return primitiveArraysRecording(bodies...)
	}
	allocs := func(data []byte) float64 {
		return testing.AllocsPerRun(5, func() {
			err := NewParser(data).ParseWith(Options{ReuseValues: true, OnError: FailOnError},
				func(e *Event) error { return nil })
			if err != nil {
				t.Fatal(err)
			}
		})
	}
	const few, many = 2, 50
	perEvent := func(filled bool) float64 {
		return (allocs(recording(many, filled)) - allocs(recording(few, filled))) / (many - few)
	}
	withArrays, withoutArrays := perEvent(true), perEvent(false)
	if withArrays > withoutArrays {
		t.Errorf("%.2f allocations per event with filled arrays, %.2f with empty ones:"+
			" recycled arrays allocate per event", withArrays, withoutArrays)
	}
}

// Arrays decoded for constant pool entries cannot come from an arena (the
// values are cached and shared), so their Array structs come from a chunk-wide
// block. Without the block each such array costs three allocations - struct,
// backing array, and the slice header that storing the elements in Elems copies
// to the heap - instead of two plus a share of a block.
//
// The cost is isolated by comparing constant pool entries holding a 3 element
// long array against identical entries holding one scalar long (one
// allocation: its interface box).
const (
	tIDHolder      = 60
	tIDHolderEvent = 104
)

func holderRecording(entries int, asArray bool) []byte {
	field := elem("field", attr("name", "values"), attr("class", itoa(tIDLong)))
	if asArray {
		field.attributes = append(field.attributes, attr("dimension", "1"))
	}
	holder := elem("class", attr("id", itoa(tIDHolder)), attr("name", "test.Holder")).with(field)
	event := elem("class",
		attr("id", itoa(tIDHolderEvent)),
		attr("name", "test.HolderEvent"),
		attr("superType", "jdk.jfr.Event"),
	).with(
		elem("field", attr("name", "holder"), attr("class", itoa(tIDHolder)), attr("constantPool", "true")),
	)
	metadata := elem("root").with(elem("metadata").with(
		primitiveClass(tIDLong, "long"), holder, event,
	))
	pool := constantPoolData{typeID: tIDHolder}
	events := make([][]byte, entries)
	for i := range events {
		w := &writer{}
		if asArray {
			w.varint(3)
			for j := 0; j < 3; j++ {
				w.varint(int64(1000 + i*3 + j))
			}
		} else {
			w.varint(int64(1000 + i))
		}
		pool.entries = append(pool.entries, poolEntry{id: int64(i + 1), data: w.buf})
		ev := &writer{}
		ev.varint(tIDHolderEvent)
		ev.varint(int64(i + 1))
		events[i] = ev.buf
	}
	spec := chunkSpec{
		metadata:   metadata,
		pools:      []constantPoolData{pool},
		events:     events,
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}
	return spec.build()
}

func TestConstantPoolArraysShareStructBlocks(t *testing.T) {
	perEntry := func(asArray bool) float64 {
		resolveAll := func(data []byte) float64 {
			return testing.AllocsPerRun(5, func() {
				err := NewParser(data).ParseWith(Options{OnError: FailOnError}, func(e *Event) error {
					v := Get(e.Values, "holder", "values")
					if arr, ok := v.(*Array); asArray && (!ok || arr.Len() != 3) || !asArray && v == nil {
						t.Fatalf("holder.values = %#v", v)
					}
					return nil
				})
				if err != nil {
					t.Fatal(err)
				}
			})
		}
		const few, many = 64, 640
		return (resolveAll(holderRecording(many, asArray)) - resolveAll(holderRecording(few, asArray))) / (many - few)
	}
	arrayCost := perEntry(true) - perEntry(false)
	// Expected: backing + header + 1/64 block - scalar box = 1.016.
	if arrayCost > 1.5 {
		t.Errorf("a constant pool array costs %.3f allocations more than a scalar, want about 1:"+
			" its Array struct is allocated on its own", arrayCost)
	}
}

// Keeping one event's array must not keep another event's elements alive.
// Without ReuseValues, events are the caller's to keep, so their arrays must
// not share an allocation: the garbage collector keeps a whole allocation alive
// while any part of it is reachable, and with it everything its other Array
// structs point to. The second event's backing array carries a finalizer; it
// must be collectable while the first event's array is still held.
func TestKeptEventArrayDoesNotRetainOtherEvents(t *testing.T) {
	const n = 4096
	event := func() []byte {
		w := &writer{}
		w.varint(tIDHolderEvent + 1)
		w.varint(n)
		for i := 0; i < n; i++ {
			w.varint(int64(1000 + i))
		}
		return w.buf
	}
	metadata := elem("root").with(elem("metadata").with(
		primitiveClass(tIDLong, "long"),
		elem("class",
			attr("id", itoa(tIDHolderEvent+1)),
			attr("name", "test.Values"),
			attr("superType", "jdk.jfr.Event"),
		).with(elem("field", attr("name", "values"), attr("class", itoa(tIDLong)), attr("dimension", "1"))),
	))
	data := chunkSpec{
		metadata:   metadata,
		events:     [][]byte{event(), event()},
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}.build()

	var kept *Array
	collected := make(chan struct{}, 1)
	seen := 0
	err := NewParser(data).ParseWith(Options{OnError: FailOnError}, func(e *Event) error {
		arr := e.Values["values"].(*Array)
		if seen == 0 {
			kept = arr
		} else {
			elems := arr.Elems.([]int64)
			runtime.SetFinalizer(&elems[0], func(*int64) { collected <- struct{}{} })
		}
		seen++
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 20; i++ {
		runtime.GC()
		select {
		case <-collected:
			runtime.KeepAlive(kept)
			return
		case <-time.After(10 * time.Millisecond):
		}
	}
	runtime.KeepAlive(kept)
	t.Fatal("the second event's array stayed reachable while only the first event's array was kept")
}
