package jfr

import (
	"fmt"
	"strings"
)

// maxValueDepth guards against malformed metadata describing self-referential
// inline types, which would otherwise drive unbounded recursion.
const maxValueDepth = 64

// Array is the value of a JFR array field.
//
// Elems holds the elements in a slice whose Go type depends on the element
// type, so that numeric elements are stored unboxed and decoding them
// allocates nothing per element:
//
//	ElementType     Elems
//	int, long       []int64   (int sign extended from 32 bits;
//	                           @Timestamp/@Timespan(TICKS) in nanoseconds)
//	float           []float32
//	double          []float64
//	short           []int16
//	char            []uint16
//	anything else   []any     (each element has the Go type of a scalar
//	                           field of the element type)
//
// Array elements that are constant pool references are *Ref and inline complex
// elements are map[string]any, in an []any whatever their type. Switch on the
// type of Elems to read the elements in bulk; Len and At work on any array.
type Array struct {
	// ElementType is the JFR type name of the elements.
	ElementType string
	// Elems is the slice of elements; see the table above for its type.
	Elems any
}

// arrayKind is where the decoder stores the elements of an array field.
type arrayKind uint8

const (
	arrayValues arrayKind = iota
	arrayInts
	arrayFloats
	arrayDoubles
	arrayShorts
	arrayChars
)

// arrayKindOf returns where the elements of an array field are stored.
// Constant pool references are always *Ref values, whatever their type.
func arrayKindOf(f *Field) arrayKind {
	if f.ConstantPool || f.Type == nil || !f.Type.primitive {
		return arrayValues
	}
	switch f.Type.Name {
	case "int", "long":
		return arrayInts
	case "float":
		return arrayFloats
	case "double":
		return arrayDoubles
	case "short":
		return arrayShorts
	case "char":
		return arrayChars
	default:
		return arrayValues
	}
}

// Len returns the number of elements.
func (a *Array) Len() int {
	switch e := a.Elems.(type) {
	case []int64:
		return len(e)
	case []float32:
		return len(e)
	case []float64:
		return len(e)
	case []int16:
		return len(e)
	case []uint16:
		return len(e)
	case []any:
		return len(e)
	default:
		return 0
	}
}

// At returns element i as an interface value, carrying the same Go type a
// scalar field of the element type would. For a numeric slice this boxes the
// element; switch on Elems and index the slice to avoid that.
//
// At panics if i is out of range, like a slice index.
func (a *Array) At(i int) any {
	switch e := a.Elems.(type) {
	case []int64:
		return e[i]
	case []float32:
		return e[i]
	case []float64:
		return e[i]
	case []int16:
		return e[i]
	case []uint16:
		return e[i]
	case []any:
		return e[i]
	default:
		return []any(nil)[i] // no elements: panics with the usual index message
	}
}

func (a *Array) String() string {
	var sb strings.Builder
	sb.WriteByte('[')
	for i, n := 0, a.Len(); i < n; i++ {
		if i > 0 {
			sb.WriteString(", ")
		}
		fmt.Fprintf(&sb, "%v", a.At(i))
	}
	sb.WriteByte(']')
	return sb.String()
}

// Ref is an unresolved reference to a constant pool entry. Resolution is
// performed on demand by Value and is cached per constant pool entry, so the
// cost of decoding an entry is paid once per recording chunk no matter how many
// events reference it.
//
// A Ref stays resolvable after Parse returns.
type Ref struct {
	pool  *constantPool
	index int64
}

// Index returns the constant pool index this reference points at.
func (r *Ref) Index() int64 { return r.index }

// Type returns the type of the referenced constant pool entry.
func (r *Ref) Type() *ClassType {
	if r.pool == nil {
		return nil
	}
	return r.pool.class
}

// Value resolves the reference. It returns a map[string]any for complex types,
// a string for java.lang.String entries and nil when the entry is missing or
// could not be decoded; in the latter case Err reports why.
func (r *Ref) Value() any {
	if r.pool == nil {
		return nil
	}
	v, err := r.pool.value(r.index)
	if err != nil {
		return nil
	}
	return v
}

// Map resolves the reference and returns it as a map, or nil when the entry is
// missing or is not a complex value.
func (r *Ref) Map() map[string]any {
	m, _ := r.Value().(map[string]any)
	return m
}

// Text resolves the reference and returns it as a string. The second result
// reports whether the entry resolved to a string value.
func (r *Ref) Text() (string, bool) {
	s, ok := r.Value().(string)
	return s, ok
}

// Err returns the error of the last resolution attempt for this entry, if any.
func (r *Ref) Err() error {
	if r.pool == nil {
		return nil
	}
	return r.pool.errors[r.index]
}

// String renders the reference without resolving it.
func (r *Ref) String() string {
	name := "?"
	if t := r.Type(); t != nil {
		name = t.Name
	}
	return fmt.Sprintf("Ref(%s#%d)", name, r.index)
}

// readValue decodes a value of type ct at the reader's current position.
//
// Complex types decode to map[string]any; primitive types decode to the Go
// type listed in the package documentation.
func (c *chunkParser) readValue(r *reader, ct *ClassType, depth int) (any, error) {
	if ct == nil {
		return nil, fmt.Errorf("cannot decode a value of an unresolved type")
	}
	if depth > maxValueDepth {
		return nil, fmt.Errorf("type %s: value nesting exceeds %d levels", ct.Name, maxValueDepth)
	}
	if ct.primitive {
		return c.readPrimitive(r, nil, ct, "")
	}
	m := c.newMap(len(ct.Fields))
	for _, f := range ct.Fields {
		if err := c.readField(r, m, ct, f, depth); err != nil {
			return nil, err
		}
	}
	return m, nil
}

func (c *chunkParser) readField(r *reader, into map[string]any, owner *ClassType, f *Field, depth int) error {
	if f.Dimension != 1 {
		v, err := c.readFieldValue(r, owner, f, depth)
		if err != nil {
			return err
		}
		into[f.Name] = v
		return nil
	}
	n := r.readVarint()
	if r.err != nil {
		return r.err
	}
	if n < 0 || n > int64(r.remaining()) {
		return fmt.Errorf("%s.%s: implausible array length %d", owner.Name, f.Name, n)
	}
	elementType := ""
	if f.Type != nil {
		elementType = f.Type.Name
	}
	arr := c.newArray(elementType)
	switch arrayKindOf(f) {
	case arrayInts:
		// Elements are stored unboxed; resolve the field's tick
		// normalisation once for the whole array.
		ints := sized[int64](arr, int(n))
		temporal := c.temporalKind(owner, f.Name)
		isInt := f.Type.Name == "int"
		for i := range ints {
			v := r.readVarint()
			if isInt {
				// See readPrimitive: truncate to 32 bits, then sign extend.
				v = int64(int32(v))
			}
			ints[i] = c.applyTemporal(temporal, v)
		}
	case arrayFloats:
		floats := sized[float32](arr, int(n))
		for i := range floats {
			floats[i] = r.readFloat32()
		}
	case arrayDoubles:
		doubles := sized[float64](arr, int(n))
		for i := range doubles {
			doubles[i] = r.readFloat64()
		}
	case arrayShorts:
		shorts := sized[int16](arr, int(n))
		for i := range shorts {
			shorts[i] = int16(r.readVarint())
		}
	case arrayChars:
		chars := sized[uint16](arr, int(n))
		for i := range chars {
			chars[i] = uint16(r.readVarint())
		}
	default:
		values := sized[any](arr, int(n))
		for i := range values {
			v, err := c.readFieldValue(r, owner, f, depth)
			if err != nil {
				return err
			}
			values[i] = v
		}
	}
	if r.err != nil {
		return r.err
	}
	into[f.Name] = arr
	return nil
}

// readFieldValue decodes a single (non-array) field value.
func (c *chunkParser) readFieldValue(r *reader, owner *ClassType, f *Field, depth int) (any, error) {
	if f.ConstantPool {
		idx := r.readVarint()
		if r.err != nil {
			return nil, r.err
		}
		if f.pool == nil {
			typeID := int64(-1)
			if f.Type != nil {
				typeID = f.Type.ID
			}
			f.pool = c.pools.getOrCreate(typeID)
		}
		return c.newRef(f.pool, idx), nil
	}
	if f.Type == nil {
		return nil, fmt.Errorf("%s.%s: field type could not be resolved from the chunk metadata", owner.Name, f.Name)
	}
	if f.Type.primitive {
		return c.readPrimitive(r, owner, f.Type, f.Name)
	}
	return c.readValue(r, f.Type, depth+1)
}

// readPrimitive decodes a scalar value. owner and fieldName drive the tick
// normalisation of @Timestamp / @Timespan annotated fields.
func (c *chunkParser) readPrimitive(r *reader, owner *ClassType, ct *ClassType, fieldName string) (any, error) {
	switch ct.Name {
	case "short":
		return int16(r.readVarint()), r.err
	case "char":
		return uint16(r.readVarint()), r.err
	case "int":
		// JFR encodes int fields as a varint carrying the 32 bit two's
		// complement value; truncate before sign extending.
		v := int64(int32(r.readVarint()))
		return c.normalize(owner, fieldName, v), r.err
	case "long":
		v := r.readVarint()
		return c.normalize(owner, fieldName, v), r.err
	case "byte":
		return r.readByte(), r.err
	case "boolean":
		return r.readBoolean(), r.err
	case "float":
		return r.readFloat32(), r.err
	case "double":
		return r.readFloat64(), r.err
	case "java.lang.String":
		s, isNull, err := readString(r, c.md, c)
		if err != nil {
			return nil, err
		}
		if isNull {
			return nil, nil
		}
		return s, nil
	default:
		return nil, fmt.Errorf("unknown primitive type: %s", ct.Name)
	}
}
