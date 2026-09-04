package jfr

import (
	"fmt"
	"strconv"
	"strings"
)

// primitiveTypeNames is the set of JFR type names that are encoded inline as a
// single scalar rather than as a sequence of fields. java.lang.String is part
// of the set because it has a dedicated on-disk encoding.
var primitiveTypeNames = map[string]bool{
	"byte":             true,
	"char":             true,
	"short":            true,
	"int":              true,
	"long":             true,
	"float":            true,
	"double":           true,
	"boolean":          true,
	"java.lang.String": true,
}

// Annotation is a JFR metadata annotation attached to a type or a field, for
// example jdk.jfr.Timespan or jdk.jfr.Label.
type Annotation struct {
	// Type is the annotation type; nil when the recording references an
	// annotation class that is not declared in the chunk metadata.
	Type *ClassType
	// Value is the annotation value, empty when the annotation carries none.
	Value string
	// Annotations are the annotations declared on this annotation.
	Annotations []*Annotation

	classID   int64
	className string
}

// Name returns the annotation type name, or an empty string when the type
// could not be resolved.
func (a *Annotation) Name() string {
	if a.Type == nil {
		return ""
	}
	return a.Type.Name
}

// Setting is a recording setting declared on an event type.
type Setting struct {
	// Name is the setting name, for example "threshold" or "enabled".
	Name string
	// Type is the setting value type; may be nil when unresolvable.
	Type *ClassType
	// DefaultValue is the configured value of the setting.
	DefaultValue string

	classID   int64
	className string
}

// Field is a single field declared by a ClassType.
type Field struct {
	// Name is the field name.
	Name string
	// Type is the declared field type. It is always resolved for well-formed
	// recordings; a nil type marks a field this parser cannot decode.
	Type *ClassType
	// Dimension is the array dimension of the field: -1 when the attribute is
	// absent, 0 for a scalar and 1 for an array.
	Dimension int
	// ConstantPool reports whether the field value is encoded as a constant
	// pool index rather than inline.
	ConstantPool bool
	// Annotations are the annotations declared on the field.
	Annotations []*Annotation

	typeID   int64
	typeName string
	// pool caches the constant pool this field points into. Fields are
	// chunk-scoped, so the cache never crosses chunk boundaries.
	pool *constantPool
}

// IsArray reports whether the field is encoded as an array.
func (f *Field) IsArray() bool { return f.Dimension > 0 }

// ClassType is a type declared in the metadata of a chunk. Event types,
// constant pool types and the built-in primitive types are all represented by
// ClassType.
//
// Instances are chunk-scoped: the same JFR type declared in two chunks is
// represented by two distinct ClassType values, and type IDs are only
// meaningful within their own chunk.
type ClassType struct {
	// ID is the numeric type ID used to reference the type inside the chunk.
	ID int64
	// Name is the fully qualified type name, e.g. "jdk.ExecutionSample".
	Name string
	// SuperType is the declared super type name, e.g. "jdk.jfr.Event"; empty
	// when the type declares none.
	SuperType string
	// SimpleType reports whether the type wraps a single value; such types are
	// stored unwrapped in constant pools.
	SimpleType bool
	// Fields are the declared fields, in encoding order.
	Fields []*Field
	// Annotations are the annotations declared on the type.
	Annotations []*Annotation
	// Settings are the recording settings declared on the type, keyed by name.
	Settings map[string]*Setting

	primitive bool
	// temporal caches the per-field tick normalisation kinds; see temporal.go.
	temporal map[string]int
}

// IsPrimitive reports whether values of this type are encoded as a single
// scalar (including java.lang.String).
func (c *ClassType) IsPrimitive() bool { return c.primitive }

// IsEvent reports whether the type is an event type, i.e. whether it extends
// jdk.jfr.Event.
func (c *ClassType) IsEvent() bool {
	return c.SuperType == "jdk.jfr.Event"
}

// Field returns the field with the given name, or nil.
func (c *ClassType) Field(name string) *Field {
	for _, f := range c.Fields {
		if f.Name == name {
			return f
		}
	}
	return nil
}

// SimpleName returns the type name without its package.
func (c *ClassType) SimpleName() string {
	if idx := strings.LastIndexByte(c.Name, '.'); idx >= 0 {
		return c.Name[idx+1:]
	}
	return c.Name
}

func (c *ClassType) String() string {
	return fmt.Sprintf("ClassType{id=%d, name=%s, superType=%s}", c.ID, c.Name, c.SuperType)
}

// Metadata is the type universe of a single chunk.
type Metadata struct {
	// Classes are all types declared by the chunk, in declaration order.
	Classes []*ClassType

	strings      []string
	byID         map[int64]*ClassType
	byName       map[string]*ClassType
	stringTypeID int64
}

// Class returns the type with the given ID, or nil.
func (m *Metadata) Class(id int64) *ClassType { return m.byID[id] }

// ClassByName returns the type with the given name, or nil.
func (m *Metadata) ClassByName(name string) *ClassType { return m.byName[name] }

// EventTypes returns the declared event types.
func (m *Metadata) EventTypes() []*ClassType {
	var out []*ClassType
	for _, c := range m.Classes {
		if c.IsEvent() {
			out = append(out, c)
		}
	}
	return out
}

func (m *Metadata) addClass(c *ClassType) {
	// First declaration of an ID wins, mirroring the Java implementation.
	if _, ok := m.byID[c.ID]; ok {
		return
	}
	m.byID[c.ID] = c
	if _, ok := m.byName[c.Name]; !ok {
		m.byName[c.Name] = c
	}
	m.Classes = append(m.Classes, c)
}

func (m *Metadata) str(idx int64) (string, error) {
	if idx < 0 || idx >= int64(len(m.strings)) {
		return "", fmt.Errorf("metadata string index %d out of bounds (table size %d)", idx, len(m.strings))
	}
	return m.strings[idx], nil
}

// metadataEvent is the decoded chunk metadata event.
type metadataEvent struct {
	Size       int64
	StartTime  int64
	Duration   int64
	MetadataID int64
	Metadata   *Metadata
}

// element is the generic metadata element representation used while decoding;
// every element is a name, a set of string attributes and a list of children.
type element struct {
	name       string
	attributes map[string]string
	children   []*element
}

func (e *element) attr(key string) string { return e.attributes[key] }

// readMetadata decodes the metadata event located at the reader's current
// position and builds the chunk type universe from it.
func readMetadata(r *reader) (*metadataEvent, error) {
	ev := &metadataEvent{}
	ev.Size = r.readVarint()
	if r.err != nil {
		return nil, r.err
	}
	if ev.Size == 0 {
		return nil, fmt.Errorf("metadata event: unexpected event size 0")
	}
	if typeID := r.readVarint(); typeID != 0 {
		return nil, fmt.Errorf("metadata event: unexpected event type %d (should be 0)", typeID)
	}
	ev.StartTime = r.readVarint()
	ev.Duration = r.readVarint()
	ev.MetadataID = r.readVarint()

	md := &Metadata{
		byID:         make(map[int64]*ClassType),
		byName:       make(map[string]*ClassType),
		stringTypeID: -1,
	}
	ev.Metadata = md

	// String table. String constants referenced from within the metadata event
	// resolve against this very table, hence the -1 string type ID.
	count := r.readVarint()
	if r.err != nil {
		return nil, r.err
	}
	if count < 0 || count > int64(r.remaining()) {
		return nil, fmt.Errorf("metadata event: implausible string table size %d", count)
	}
	md.strings = make([]string, count)
	for i := int64(0); i < count; i++ {
		s, _, err := readString(r, md, nil)
		if err != nil {
			return nil, err
		}
		md.strings[i] = s
	}

	root, err := readElement(r, md, 0)
	if err != nil {
		return nil, err
	}
	if err := buildTypes(md, root); err != nil {
		return nil, err
	}
	if str := md.ClassByName("java.lang.String"); str != nil {
		md.stringTypeID = str.ID
	}
	return ev, r.err
}

// maxElementDepth guards against malformed metadata driving unbounded
// recursion.
const maxElementDepth = 64

func readElement(r *reader, md *Metadata, depth int) (*element, error) {
	if depth > maxElementDepth {
		return nil, fmt.Errorf("metadata element nesting exceeds %d levels", maxElementDepth)
	}
	nameIdx := r.readVarint()
	if r.err != nil {
		return nil, r.err
	}
	name, err := md.str(nameIdx)
	if err != nil {
		return nil, err
	}
	e := &element{name: name}

	attrCount := r.readVarint()
	if r.err != nil {
		return nil, r.err
	}
	if attrCount < 0 || attrCount > int64(r.remaining()) {
		return nil, fmt.Errorf("metadata element %q: implausible attribute count %d", name, attrCount)
	}
	if attrCount > 0 {
		e.attributes = make(map[string]string, attrCount)
	}
	for i := int64(0); i < attrCount; i++ {
		k := r.readVarint()
		v := r.readVarint()
		if r.err != nil {
			return nil, r.err
		}
		key, err := md.str(k)
		if err != nil {
			return nil, err
		}
		value, err := md.str(v)
		if err != nil {
			return nil, err
		}
		e.attributes[key] = value
	}

	childCount := r.readVarint()
	if r.err != nil {
		return nil, r.err
	}
	if childCount < 0 || childCount > int64(r.remaining()) {
		return nil, fmt.Errorf("metadata element %q: implausible child count %d", name, childCount)
	}
	for i := int64(0); i < childCount; i++ {
		child, err := readElement(r, md, depth+1)
		if err != nil {
			return nil, err
		}
		e.children = append(e.children, child)
	}
	return e, nil
}

// buildTypes walks the decoded element tree, materialises every declared type
// and then resolves the cross references (field types, annotation types,
// setting types) that may point forward.
func buildTypes(md *Metadata, root *element) error {
	var classes []*ClassType
	var walk func(e *element) error
	walk = func(e *element) error {
		if e.name == "class" {
			c, err := buildClass(e)
			if err != nil {
				return err
			}
			md.addClass(c)
			classes = append(classes, c)
			return nil
		}
		for _, child := range e.children {
			if err := walk(child); err != nil {
				return err
			}
		}
		return nil
	}
	if err := walk(root); err != nil {
		return err
	}

	for _, c := range classes {
		for _, f := range c.Fields {
			f.Type = resolveClass(md, f.typeID, f.typeName)
			resolveAnnotations(md, f.Annotations)
		}
		resolveAnnotations(md, c.Annotations)
		for _, s := range c.Settings {
			s.Type = resolveClass(md, s.classID, s.className)
		}
	}
	return nil
}

func resolveClass(md *Metadata, id int64, name string) *ClassType {
	if id >= 0 {
		if c := md.Class(id); c != nil {
			return c
		}
	}
	if name != "" {
		return md.ClassByName(name)
	}
	return nil
}

func resolveAnnotations(md *Metadata, annotations []*Annotation) {
	for _, a := range annotations {
		a.Type = resolveClass(md, a.classID, a.className)
		resolveAnnotations(md, a.Annotations)
	}
}

func buildClass(e *element) (*ClassType, error) {
	id, err := parseTypeID(e.attr("id"))
	if err != nil {
		return nil, fmt.Errorf("metadata class %q: %w", e.attr("name"), err)
	}
	c := &ClassType{
		ID:         id,
		Name:       e.attr("name"),
		SuperType:  e.attr("superType"),
		SimpleType: e.attr("simpleType") == "true",
	}
	c.primitive = primitiveTypeNames[c.Name]
	for _, child := range e.children {
		switch child.name {
		case "field":
			c.Fields = append(c.Fields, buildField(child))
		case "annotation":
			c.Annotations = append(c.Annotations, buildAnnotation(child))
		case "setting":
			s := buildSetting(child)
			if c.Settings == nil {
				c.Settings = make(map[string]*Setting)
			}
			c.Settings[s.Name] = s
		}
	}
	return c, nil
}

func buildField(e *element) *Field {
	f := &Field{
		Name:         e.attr("name"),
		ConstantPool: e.attr("constantPool") == "true",
		Dimension:    -1,
	}
	if d := e.attr("dimension"); d != "" {
		if v, err := strconv.Atoi(d); err == nil {
			f.Dimension = v
		}
	}
	f.typeID, f.typeName = classRef(e.attr("class"))
	for _, child := range e.children {
		if child.name == "annotation" {
			f.Annotations = append(f.Annotations, buildAnnotation(child))
		}
	}
	return f
}

func buildAnnotation(e *element) *Annotation {
	a := &Annotation{Value: e.attr("value")}
	a.classID, a.className = classRef(e.attr("class"))
	for _, child := range e.children {
		if child.name == "annotation" {
			a.Annotations = append(a.Annotations, buildAnnotation(child))
		}
	}
	return a
}

func buildSetting(e *element) *Setting {
	s := &Setting{
		Name:         e.attr("name"),
		DefaultValue: e.attr("defaultValue"),
	}
	s.classID, s.className = classRef(e.attr("class"))
	return s
}

// classRef interprets a "class" attribute. Well-formed recordings carry a
// numeric type ID; some producers (dd-trace-java among them) write the type
// name instead, which is resolved by name later on.
func classRef(value string) (int64, string) {
	if value == "" {
		return -1, ""
	}
	if id, err := strconv.ParseInt(value, 10, 64); err == nil {
		return id, ""
	}
	return -1, value
}

func parseTypeID(value string) (int64, error) {
	if value == "" {
		return -1, nil
	}
	id, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return -1, fmt.Errorf("invalid type id %q", value)
	}
	return id, nil
}
