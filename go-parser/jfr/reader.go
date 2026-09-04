package jfr

import (
	"encoding/binary"
	"fmt"
	"math"
)

// reader is a positioned big-endian reader over an in-memory slice of a JFR
// recording. All JFR scalar encodings (fixed width big-endian integers, LEB128
// style varints and the tagged string encoding) are decoded from here.
//
// The reader keeps a sticky error: once a read fails every subsequent read is a
// no-op returning the zero value, so a decoding routine may perform a sequence
// of reads and check err() only once at a convenient boundary. Loops driven by
// decoded values must still check err() on every iteration to avoid spinning on
// zero-length data.
type reader struct {
	data []byte
	pos  int
	err  error
}

func newReader(data []byte) *reader {
	return &reader{data: data}
}

func (r *reader) fail(format string, args ...any) {
	if r.err == nil {
		r.err = fmt.Errorf(format, args...)
	}
}

// ensure reports whether n more bytes can be read at the current position.
func (r *reader) ensure(n int) bool {
	if r.err != nil {
		return false
	}
	if n < 0 || r.pos+n > len(r.data) {
		r.fail("unexpected end of data: need %d byte(s) at offset %d of %d", n, r.pos, len(r.data))
		return false
	}
	return true
}

func (r *reader) position() int { return r.pos }

// seek moves the read position to an absolute offset.
func (r *reader) seek(pos int) {
	if r.err != nil {
		return
	}
	if pos < 0 || pos > len(r.data) {
		r.fail("seek out of bounds: offset %d of %d", pos, len(r.data))
		return
	}
	r.pos = pos
}

func (r *reader) skip(n int) {
	if !r.ensure(n) {
		return
	}
	r.pos += n
}

func (r *reader) remaining() int {
	if r.err != nil {
		return 0
	}
	return len(r.data) - r.pos
}

func (r *reader) readByte() int8 {
	if !r.ensure(1) {
		return 0
	}
	b := r.data[r.pos]
	r.pos++
	return int8(b)
}

func (r *reader) readBoolean() bool {
	return r.readByte() != 0
}

func (r *reader) readInt16() int16 {
	if !r.ensure(2) {
		return 0
	}
	v := binary.BigEndian.Uint16(r.data[r.pos:])
	r.pos += 2
	return int16(v)
}

func (r *reader) readInt32() int32 {
	if !r.ensure(4) {
		return 0
	}
	v := binary.BigEndian.Uint32(r.data[r.pos:])
	r.pos += 4
	return int32(v)
}

func (r *reader) readInt64() int64 {
	if !r.ensure(8) {
		return 0
	}
	v := binary.BigEndian.Uint64(r.data[r.pos:])
	r.pos += 8
	return int64(v)
}

func (r *reader) readFloat32() float32 {
	if !r.ensure(4) {
		return 0
	}
	v := binary.BigEndian.Uint32(r.data[r.pos:])
	r.pos += 4
	return math.Float32frombits(v)
}

func (r *reader) readFloat64() float64 {
	if !r.ensure(8) {
		return 0
	}
	v := binary.BigEndian.Uint64(r.data[r.pos:])
	r.pos += 8
	return math.Float64frombits(v)
}

// readVarint decodes the JFR variable length integer encoding: up to eight
// groups of seven bits, little-endian, each continued while the high bit is
// set, followed by a ninth byte contributing a full eight bits.
func (r *reader) readVarint() int64 {
	if r.err != nil {
		return 0
	}
	var ret int64
	for shift := uint(0); shift < 56; shift += 7 {
		if !r.ensure(1) {
			return 0
		}
		b := r.data[r.pos]
		r.pos++
		ret |= int64(b&0x7f) << shift
		if b&0x80 == 0 {
			return ret
		}
	}
	if !r.ensure(1) {
		return 0
	}
	b := r.data[r.pos]
	r.pos++
	return ret | int64(b)<<56
}
