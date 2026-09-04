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
	// rawID is the "id" attribute as written, parsed once the element is
	// complete.
	rawID string
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

	if _, err := readElement(r, md, 0); err != nil {
		return nil, err
	}
	resolveReferences(md)
	if str := md.ClassByName("java.lang.String"); str != nil {
		md.stringTypeID = str.ID
	}
	return ev, r.err
}

// Metadata elements are decoded straight into the objects they describe. The
// element name identifies the kind, so attributes can be written into the
// target as they are read and children attached as they are parsed - there is
// no intermediate element tree, which on a type-rich chunk is the bulk of the
// parser's fixed cost.
const (
	elemUnknown = iota
	elemClass
	elemField
	elemAnnotation
	elemSetting
)

func elementKind(name string) int {
	switch name {
	case "class":
		return elemClass
	case "field":
		return elemField
	case "annotation":
		return elemAnnotation
	case "setting":
		return elemSetting
	default:
		// "root", "metadata", "region" and anything a future writer adds carry
		// no data this parser needs, but their children still do.
		return elemUnknown
	}
}

// parsedElement is the decoded element handed back to its parent. It is a value
// type: the only allocations are the objects the caller keeps.
type parsedElement struct {
	kind    int
	class   *ClassType
	field   *Field
	ann     *Annotation
	setting *Setting
}

// setAttribute writes one attribute into the element being decoded.
func (e *parsedElement) setAttribute(key, value string) {
	switch e.kind {
	case elemClass:
		switch key {
		case "id":
			e.class.rawID = value
		case "name":
			e.class.Name = value
		case "superType":
			e.class.SuperType = value
		case "simpleType":
			e.class.SimpleType = value == "true"
		}
	case elemField:
		switch key {
		case "name":
			e.field.Name = value
		case "class":
			e.field.typeID, e.field.typeName = classRef(value)
		case "constantPool":
			e.field.ConstantPool = value == "true"
		case "dimension":
			if v, err := strconv.Atoi(value); err == nil {
				e.field.Dimension = v
			}
		}
	case elemAnnotation:
		switch key {
		case "class":
			e.ann.classID, e.ann.className = classRef(value)
		case "value":
			e.ann.Value = value
		}
	case elemSetting:
		switch key {
		case "name":
			e.setting.Name = value
		case "class":
			e.setting.classID, e.setting.className = classRef(value)
		case "defaultValue":
			e.setting.DefaultValue = value
		}
	}
}

// addChild attaches a decoded child element to its parent.
func (e *parsedElement) addChild(child parsedElement) {
	switch e.kind {
	case elemClass:
		switch child.kind {
		case elemField:
			e.class.Fields = append(e.class.Fields, child.field)
		case elemAnnotation:
			e.class.Annotations = append(e.class.Annotations, child.ann)
		case elemSetting:
			if e.class.Settings == nil {
				e.class.Settings = make(map[string]*Setting)
			}
			e.class.Settings[child.setting.Name] = child.setting
		}
	case elemField:
		if child.kind == elemAnnotation {
			e.field.Annotations = append(e.field.Annotations, child.ann)
		}
	case elemAnnotation:
		if child.kind == elemAnnotation {
			e.ann.Annotations = append(e.ann.Annotations, child.ann)
		}
	}
}

// maxElementDepth guards against malformed metadata driving unbounded
// recursion.
const maxElementDepth = 64

// readElement decodes one metadata element and everything below it. Classes
// register themselves as they are completed, so a class nested under an element
// this parser does not recognise is still picked up.
func readElement(r *reader, md *Metadata, depth int) (parsedElement, error) {
	var el parsedElement
	nameIdx := r.readVarint()
	if r.err != nil {
		return el, r.err
	}
	if depth > maxElementDepth {
		return el, fmt.Errorf("metadata element nesting exceeds %d levels", maxElementDepth)
	}
	name, err := md.str(nameIdx)
	if err != nil {
		return el, err
	}
	el.kind = elementKind(name)
	switch el.kind {
	case elemClass:
		el.class = &ClassType{ID: -1}
	case elemField:
		el.field = &Field{Dimension: -1, typeID: -1}
	case elemAnnotation:
		el.ann = &Annotation{classID: -1}
	case elemSetting:
		el.setting = &Setting{classID: -1}
	}

	attrCount := r.readVarint()
	if r.err != nil {
		return el, r.err
	}
	if attrCount < 0 || attrCount > int64(r.remaining()) {
		return el, fmt.Errorf("metadata element %q: implausible attribute count %d", name, attrCount)
	}
	for i := int64(0); i < attrCount; i++ {
		k := r.readVarint()
		v := r.readVarint()
		if r.err != nil {
			return el, r.err
		}
		key, err := md.str(k)
		if err != nil {
			return el, err
		}
		value, err := md.str(v)
		if err != nil {
			return el, err
		}
		el.setAttribute(key, value)
	}

	childCount := r.readVarint()
	if r.err != nil {
		return el, r.err
	}
	if childCount < 0 || childCount > int64(r.remaining()) {
		return el, fmt.Errorf("metadata element %q: implausible child count %d", name, childCount)
	}
	for i := int64(0); i < childCount; i++ {
		child, err := readElement(r, md, depth+1)
		if err != nil {
			return el, err
		}
		el.addChild(child)
	}

	if el.kind == elemClass {
		id, err := parseTypeID(el.class.rawID)
		if err != nil {
			return el, fmt.Errorf("metadata class %q: %w", el.class.Name, err)
		}
		el.class.ID = id
		el.class.primitive = primitiveTypeNames[el.class.Name]
		md.addClass(el.class)
	}
	return el, nil
}

// resolveReferences links the type references collected while decoding. It runs
// once the whole metadata event has been read, because a field may reference a
// type declared after it.
func resolveReferences(md *Metadata) {
	for _, c := range md.Classes {
		for _, f := range c.Fields {
			f.Type = resolveClass(md, f.typeID, f.typeName)
			resolveAnnotations(md, f.Annotations)
		}
		resolveAnnotations(md, c.Annotations)
		for _, s := range c.Settings {
			s.Type = resolveClass(md, s.classID, s.className)
		}
	}
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
