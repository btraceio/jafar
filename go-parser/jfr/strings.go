package jfr

import (
	"fmt"
	"unicode/utf16"
)

// JFR string encodings, as written by the JVM's chunk writer.
const (
	strNull     = 0
	strEmpty    = 1
	strConstant = 2
	strUTF8     = 3
	strCharArr  = 4
	strLatin1   = 5
)

// readString decodes a JFR encoded string.
//
// It returns the decoded value and whether the encoded string was null. cp may
// be nil while decoding the metadata string table, in which case a constant
// reference resolves against that very table.
func readString(r *reader, md *Metadata, cp *chunkParser) (string, bool, error) {
	id := r.readByte()
	if r.err != nil {
		return "", false, r.err
	}
	switch id {
	case strNull:
		return "", true, nil
	case strEmpty:
		return "", false, nil
	case strConstant:
		ptr := r.readVarint()
		if r.err != nil {
			return "", false, r.err
		}
		if md.stringTypeID < 0 {
			// Within the metadata event a constant reference points into the
			// metadata string table itself.
			s, err := md.str(ptr)
			return s, false, err
		}
		if cp == nil {
			return "", true, nil
		}
		// String field values may be interned in the java.lang.String
		// constant pool; this is how the JMC writer emits them.
		pool := cp.pools.get(md.stringTypeID)
		if pool == nil {
			return "", true, nil
		}
		v, err := pool.value(ptr)
		if err != nil {
			return "", false, err
		}
		if s, ok := v.(string); ok {
			return s, false, nil
		}
		return "", true, nil
	case strUTF8, strLatin1:
		size := r.readVarint()
		if r.err != nil {
			return "", false, r.err
		}
		if size == 0 {
			return "", false, nil
		}
		if size < 0 || size > int64(r.remaining()) {
			return "", false, fmt.Errorf("string of %d byte(s) exceeds the remaining %d byte(s)", size, r.remaining())
		}
		buf := r.data[r.pos : r.pos+int(size)]
		r.pos += int(size)
		if id == strUTF8 {
			return string(buf), false, nil
		}
		return latin1ToString(buf), false, nil
	case strCharArr:
		size := r.readVarint()
		if r.err != nil {
			return "", false, r.err
		}
		if size == 0 {
			return "", false, nil
		}
		if size < 0 || size > int64(r.remaining()) {
			return "", false, fmt.Errorf("char array of %d entries exceeds the remaining %d byte(s)", size, r.remaining())
		}
		chars := make([]uint16, size)
		for i := range chars {
			chars[i] = uint16(r.readVarint())
		}
		if r.err != nil {
			return "", false, r.err
		}
		return string(utf16.Decode(chars)), false, nil
	default:
		return "", false, fmt.Errorf("unexpected string constant id: %d", id)
	}
}

// skipString advances over a JFR encoded string without decoding it.
func skipString(r *reader) error {
	id := r.readByte()
	if r.err != nil {
		return r.err
	}
	switch id {
	case strUTF8, strLatin1:
		size := r.readVarint()
		if r.err != nil {
			return r.err
		}
		if size < 0 || size > int64(r.remaining()) {
			return fmt.Errorf("string of %d byte(s) exceeds the remaining %d byte(s)", size, r.remaining())
		}
		r.skip(int(size))
	case strCharArr:
		size := r.readVarint()
		if r.err != nil {
			return r.err
		}
		if size < 0 || size > int64(r.remaining()) {
			return fmt.Errorf("char array of %d entries exceeds the remaining %d byte(s)", size, r.remaining())
		}
		for i := int64(0); i < size; i++ {
			r.readVarint()
		}
	case strConstant:
		r.readVarint()
	default:
		// null, empty and unknown ids carry no payload
	}
	return r.err
}

// latin1ToString decodes ISO-8859-1 bytes, where every byte maps to the code
// point of the same value.
func latin1ToString(b []byte) string {
	runes := make([]rune, len(b))
	for i, c := range b {
		runes[i] = rune(c)
	}
	return string(runes)
}
