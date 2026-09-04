package jfr

import (
	"math"
	"testing"
)

func TestVarintRoundTrip(t *testing.T) {
	values := []int64{
		0, 1, 63, 64, 127, 128, 255, 256, 16383, 16384,
		1 << 20, 1 << 34, 1 << 55, 1 << 56,
		math.MaxInt64, -1, -128, math.MinInt64,
	}
	for _, v := range values {
		r := newReader(appendVarint(nil, v))
		got := r.readVarint()
		if r.err != nil {
			t.Fatalf("readVarint(%d): %v", v, r.err)
		}
		if got != v {
			t.Errorf("readVarint round trip: got %d, want %d", got, v)
		}
		if r.remaining() != 0 {
			t.Errorf("readVarint(%d) left %d byte(s) unread", v, r.remaining())
		}
	}
}

func TestVarintEncodingWidth(t *testing.T) {
	// The encoding uses seven bits per byte for the first eight bytes and a
	// full byte for the ninth, so any 64 bit value fits in nine bytes.
	if got := len(appendVarint(nil, -1)); got != 9 {
		t.Errorf("encoded width of -1 = %d, want 9", got)
	}
	if got := len(appendVarint(nil, 127)); got != 1 {
		t.Errorf("encoded width of 127 = %d, want 1", got)
	}
}

func TestReaderFixedWidthValues(t *testing.T) {
	w := &writer{}
	w.u16(0xFFFE)
	w.u32(0x01020304)
	w.u64(0x0102030405060708)
	w.float32Val(1.5)
	w.float64Val(-2.25)
	w.byteVal(0xFF)

	r := newReader(w.buf)
	if got := r.readInt16(); got != -2 {
		t.Errorf("readInt16 = %d, want -2", got)
	}
	if got := r.readInt32(); got != 0x01020304 {
		t.Errorf("readInt32 = %#x", got)
	}
	if got := r.readInt64(); got != 0x0102030405060708 {
		t.Errorf("readInt64 = %#x", got)
	}
	if got := r.readFloat32(); got != 1.5 {
		t.Errorf("readFloat32 = %v", got)
	}
	if got := r.readFloat64(); got != -2.25 {
		t.Errorf("readFloat64 = %v", got)
	}
	if got := r.readByte(); got != -1 {
		t.Errorf("readByte = %d, want -1", got)
	}
	if r.err != nil {
		t.Fatalf("reader error: %v", r.err)
	}
	if r.readByte(); r.err == nil {
		t.Error("reading past the end should set the error")
	}
}

func TestReadStringEncodings(t *testing.T) {
	md := &Metadata{stringTypeID: -1, strings: []string{"zero", "one"}}

	cases := []struct {
		name   string
		data   []byte
		want   string
		isNull bool
	}{
		{"null", []byte{strNull}, "", true},
		{"empty", []byte{strEmpty}, "", false},
		{"utf8", utf8String("héllo"), "héllo", false},
		{"metadata constant", stringRef(1), "one", false},
		{"latin1", append([]byte{strLatin1, 2}, 0xE9, 0x41), "éA", false},
		{"chars", append(append([]byte{strCharArr, 3}, appendVarint(nil, 'a')...),
			append(appendVarint(nil, 0x00E9), appendVarint(nil, 'z')...)...), "aéz", false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got, isNull, err := readString(newReader(c.data), md, nil)
			if err != nil {
				t.Fatalf("readString: %v", err)
			}
			if isNull != c.isNull {
				t.Fatalf("isNull = %t, want %t", isNull, c.isNull)
			}
			if got != c.want {
				t.Errorf("readString = %q, want %q", got, c.want)
			}
		})
	}
}

func TestReadStringRejectsUnknownEncoding(t *testing.T) {
	md := &Metadata{stringTypeID: -1}
	if _, _, err := readString(newReader([]byte{9}), md, nil); err == nil {
		t.Error("expected an error for an unknown string encoding")
	}
}

func TestSkipStringMatchesReadString(t *testing.T) {
	for _, data := range [][]byte{
		{strNull},
		{strEmpty},
		utf8String("hello"),
		stringRef(3),
		append([]byte{strLatin1, 2}, 0xE9, 0x41),
	} {
		r := newReader(append(append([]byte{}, data...), 0x7f))
		if err := skipString(r); err != nil {
			t.Fatalf("skipString: %v", err)
		}
		if got := r.readVarint(); got != 0x7f {
			t.Errorf("skipString left the reader at the wrong position (next varint = %d)", got)
		}
	}
}

func TestRoundHalfUpMatchesJavaMathRound(t *testing.T) {
	cases := map[float64]int64{
		0.5: 1, -0.5: 0, 1.4: 1, 1.5: 2, -1.5: -1, -1.6: -2, 2.5: 3,
	}
	for in, want := range cases {
		if got := roundHalfUp(in); got != want {
			t.Errorf("roundHalfUp(%v) = %d, want %d", in, got, want)
		}
	}
}
