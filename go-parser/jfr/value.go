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
// Elements carry the same Go types as scalar fields of the element type: array
// elements that are constant pool references are *Ref, inline complex elements
// are map[string]any.
type Array struct {
	// ElementType is the JFR type name of the elements.
	ElementType string
	// Values holds the decoded elements.
	Values []any
}

// Len returns the number of elements.
func (a *Array) Len() int { return len(a.Values) }

func (a *Array) String() string {
	var sb strings.Builder
	sb.WriteByte('[')
	for i, v := range a.Values {
		if i > 0 {
			sb.WriteString(", ")
		}
		fmt.Fprintf(&sb, "%v", v)
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
	arr := c.newArray(elementType, int(n))
	for i := int64(0); i < n; i++ {
		v, err := c.readFieldValue(r, owner, f, depth)
		if err != nil {
			return err
		}
		arr.Values[i] = v
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
