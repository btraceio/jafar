package jfr

import "fmt"

// skipConstant advances the reader past one constant pool entry of type ct
// without decoding it. Checkpoint parsing only records where entries start, so
// every entry is skipped exactly once while the checkpoint is read.
func (c *chunkParser) skipConstant(r *reader, ct *ClassType) error {
	if ct == nil {
		return fmt.Errorf("cannot skip a value of an unresolved type")
	}
	// java.lang.String entries hold the encoded string directly.
	if ct.Name == "java.lang.String" {
		return skipString(r)
	}
	// Simple types are stored unwrapped in constant pools.
	if f := simpleTypeField(ct); f != nil {
		return c.skipTypeBody(r, unwrapSimple(f.Type), 0)
	}
	return c.skipTypeBody(r, ct, 0)
}

// skipField advances past one field value, array dimensions included.
func (c *chunkParser) skipField(r *reader, f *Field, depth int) error {
	if f.Dimension != 1 {
		return c.skipFieldValue(r, f, depth)
	}
	n := r.readVarint()
	if r.err != nil {
		return r.err
	}
	if n < 0 || n > int64(r.remaining()) {
		return fmt.Errorf("field %s: implausible array length %d", f.Name, n)
	}
	for i := int64(0); i < n; i++ {
		if err := c.skipFieldValue(r, f, depth); err != nil {
			return err
		}
	}
	return nil
}

func (c *chunkParser) skipFieldValue(r *reader, f *Field, depth int) error {
	if f.ConstantPool {
		r.readVarint()
		return r.err
	}
	t := unwrapSimple(f.Type)
	if t == nil {
		return fmt.Errorf("field %s: field type could not be resolved from the chunk metadata", f.Name)
	}
	return c.skipTypeBody(r, t, depth+1)
}

// skipTypeBody advances past the inline encoding of a value of type ct.
func (c *chunkParser) skipTypeBody(r *reader, ct *ClassType, depth int) error {
	if ct == nil {
		return fmt.Errorf("cannot skip a value of an unresolved type")
	}
	if depth > maxValueDepth {
		return fmt.Errorf("type %s: value nesting exceeds %d levels", ct.Name, maxValueDepth)
	}
	switch ct.Name {
	case "byte", "boolean":
		r.skip(1)
	case "char", "short", "int", "long":
		r.readVarint()
	case "float":
		r.skip(4)
	case "double":
		r.skip(8)
	case "java.lang.String":
		return skipString(r)
	default:
		for _, f := range ct.Fields {
			if err := c.skipField(r, f, depth); err != nil {
				return err
			}
		}
	}
	return r.err
}
