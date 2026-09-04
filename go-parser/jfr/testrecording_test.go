package jfr

import (
	"encoding/binary"
	"math"
)

// This file builds JFR recordings byte by byte so that the parser can be
// exercised end to end without depending on a recorded file. The encoding
// follows the chunk layout the JVM writes: a 68 byte header, then the events,
// the checkpoint events and the metadata event, with the header pointing at the
// latter two.

// writer accumulates the bytes of a recording.
type writer struct {
	buf []byte
}

func (w *writer) len() int { return len(w.buf) }

func (w *writer) byteVal(b byte) { w.buf = append(w.buf, b) }

func (w *writer) bytes(b []byte) { w.buf = append(w.buf, b...) }

func (w *writer) u16(v uint16) {
	w.buf = binary.BigEndian.AppendUint16(w.buf, v)
}

func (w *writer) u32(v uint32) {
	w.buf = binary.BigEndian.AppendUint32(w.buf, v)
}

func (w *writer) u64(v uint64) {
	w.buf = binary.BigEndian.AppendUint64(w.buf, v)
}

func (w *writer) float32Val(v float32) { w.u32(math.Float32bits(v)) }

func (w *writer) float64Val(v float64) { w.u64(math.Float64bits(v)) }

func (w *writer) varint(v int64) { w.buf = appendVarint(w.buf, v) }

// appendVarint encodes v the way the JFR chunk writer does: seven bits per
// byte, least significant group first, the high bit marking a continuation,
// with a ninth byte carrying the top eight bits.
func appendVarint(dst []byte, v int64) []byte {
	u := uint64(v)
	for i := 0; i < 8; i++ {
		b := byte(u & 0x7f)
		u >>= 7
		if u == 0 {
			return append(dst, b)
		}
		dst = append(dst, b|0x80)
	}
	return append(dst, byte(u&0xff))
}

func varintLen(v int64) int { return len(appendVarint(nil, v)) }

// utf8String encodes a string with the UTF-8 tag.
func utf8String(s string) []byte {
	out := []byte{strUTF8}
	out = appendVarint(out, int64(len(s)))
	return append(out, s...)
}

// stringRef encodes a reference to a constant pool string.
func stringRef(idx int64) []byte {
	return appendVarint([]byte{strConstant}, idx)
}

// nullString encodes a null string.
func nullString() []byte { return []byte{strNull} }

// sizedEvent prefixes body with the event size varint. The size covers the
// prefix itself, so its own encoded length is resolved by iteration.
func sizedEvent(body []byte) []byte {
	for prefix := 1; ; prefix++ {
		size := int64(len(body) + prefix)
		if varintLen(size) == prefix {
			return append(appendVarint(nil, size), body...)
		}
	}
}

// --- metadata construction -------------------------------------------------

// metaElement is an element of the metadata element tree.
type metaElement struct {
	name       string
	attributes [][2]string
	children   []*metaElement
}

func elem(name string, attrs ...[2]string) *metaElement {
	return &metaElement{name: name, attributes: attrs}
}

func (e *metaElement) with(children ...*metaElement) *metaElement {
	e.children = append(e.children, children...)
	return e
}

func attr(k, v string) [2]string { return [2]string{k, v} }

// stringTable assigns indices to the strings referenced by a metadata event.
type stringTable struct {
	index  map[string]int64
	values []string
}

func newStringTable() *stringTable {
	return &stringTable{index: make(map[string]int64)}
}

func (t *stringTable) id(s string) int64 {
	if idx, ok := t.index[s]; ok {
		return idx
	}
	idx := int64(len(t.values))
	t.index[s] = idx
	t.values = append(t.values, s)
	return idx
}

func (t *stringTable) collect(e *metaElement) {
	t.id(e.name)
	for _, a := range e.attributes {
		t.id(a[0])
		t.id(a[1])
	}
	for _, c := range e.children {
		t.collect(c)
	}
}

func (t *stringTable) encodeElement(w *writer, e *metaElement) {
	w.varint(t.id(e.name))
	w.varint(int64(len(e.attributes)))
	for _, a := range e.attributes {
		w.varint(t.id(a[0]))
		w.varint(t.id(a[1]))
	}
	w.varint(int64(len(e.children)))
	for _, c := range e.children {
		t.encodeElement(w, c)
	}
}

// encodeMetadataEvent renders a complete metadata event for the given root
// element.
func encodeMetadataEvent(root *metaElement) []byte {
	table := newStringTable()
	table.collect(root)

	body := &writer{}
	body.varint(0)   // event type: metadata
	body.varint(100) // start time
	body.varint(0)   // duration
	body.varint(1)   // metadata id
	body.varint(int64(len(table.values)))
	for _, s := range table.values {
		body.bytes(utf8String(s))
	}
	table.encodeElement(body, root)
	return sizedEvent(body.buf)
}

// --- checkpoint construction -----------------------------------------------

// poolEntry is a single constant pool entry: its index and its encoded bytes.
type poolEntry struct {
	id   int64
	data []byte
}

// constantPoolData is one constant pool of a checkpoint event.
type constantPoolData struct {
	typeID  int64
	entries []poolEntry
}

// encodeCheckpointEvent renders a checkpoint event holding the given pools.
func encodeCheckpointEvent(pools []constantPoolData) []byte {
	body := &writer{}
	body.varint(1)   // event type: checkpoint
	body.varint(200) // start time
	body.varint(0)   // duration
	body.varint(0)   // next offset delta: last checkpoint of the chunk
	body.byteVal(0)  // flush flag
	body.varint(int64(len(pools)))
	for _, p := range pools {
		body.varint(p.typeID)
		body.varint(int64(len(p.entries)))
		for _, e := range p.entries {
			body.varint(e.id)
			body.bytes(e.data)
		}
	}
	return sizedEvent(body.buf)
}

// --- chunk assembly --------------------------------------------------------

// chunkSpec describes a chunk to be assembled.
type chunkSpec struct {
	metadata   *metaElement
	pools      []constantPoolData
	events     [][]byte // event bodies, each starting with the event type varint
	startNanos int64
	startTicks int64
	frequency  int64
	duration   int64
}

// build assembles a chunk: header, events, checkpoint, metadata.
func (s chunkSpec) build() []byte {
	events := &writer{}
	for _, ev := range s.events {
		events.bytes(sizedEvent(ev))
	}
	checkpoint := encodeCheckpointEvent(s.pools)
	metadata := encodeMetadataEvent(s.metadata)

	cpOffset := chunkHeaderSize + events.len()
	metaOffset := cpOffset + len(checkpoint)
	size := metaOffset + len(metadata)

	w := &writer{}
	w.bytes([]byte{'F', 'L', 'R', 0})
	w.u16(2) // major
	w.u16(1) // minor
	w.u64(uint64(size))
	w.u64(uint64(cpOffset))
	w.u64(uint64(metaOffset))
	w.u64(uint64(s.startNanos))
	w.u64(uint64(s.duration))
	w.u64(uint64(s.startTicks))
	w.u64(uint64(s.frequency))
	w.u32(uint32(featureCompressedInts))
	if w.len() != chunkHeaderSize {
		panic("chunk header size mismatch")
	}
	w.bytes(events.buf)
	w.bytes(checkpoint)
	w.bytes(metadata)
	return w.buf
}

// --- cyclic constant pool recording -----------------------------------------

// The class/class loader graph of a real recording can form a cycle through the
// constant pools. cyclicRecording reproduces the shape with the smallest
// possible type: a node whose pooled "next" field points back at its
// predecessor.
const (
	tIDNode   = 50
	tIDCyclic = 101
)

func cyclicMetadata() *metaElement {
	node := elem("class", attr("id", itoa(tIDNode)), attr("name", "test.Node")).with(
		elem("field", attr("name", "name"), attr("class", itoa(tIDString))),
		elem("field", attr("name", "next"), attr("class", itoa(tIDNode)), attr("constantPool", "true")),
	)
	event := elem("class",
		attr("id", itoa(tIDCyclic)),
		attr("name", "test.Cyclic"),
		attr("superType", "jdk.jfr.Event"),
	).with(
		elem("field", attr("name", "node"), attr("class", itoa(tIDNode)), attr("constantPool", "true")),
	)
	return elem("root").with(
		elem("metadata").with(
			primitiveClass(tIDString, "java.lang.String"),
			node,
			event,
		),
	)
}

// nodeEntry encodes one test.Node constant pool entry.
func nodeEntry(name string, next int64) []byte {
	w := &writer{}
	w.bytes(utf8String(name))
	w.varint(next)
	return w.buf
}

func cyclicRecording() []byte {
	event := func() []byte {
		w := &writer{}
		w.varint(tIDCyclic)
		w.varint(1) // node -> constant pool entry 1
		return w.buf
	}
	spec := chunkSpec{
		metadata: cyclicMetadata(),
		pools: []constantPoolData{
			{typeID: tIDNode, entries: []poolEntry{
				{id: 1, data: nodeEntry("a", 2)},
				{id: 2, data: nodeEntry("b", 1)},
			}},
		},
		events:     [][]byte{event(), event()},
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}
	return spec.build()
}

// --- variable shape recording -----------------------------------------------

// An array of complex values makes the number of maps an event needs vary from
// event to event, which is the case value recycling has to get right.
const tIDBag = 102

func bagMetadata() *metaElement {
	nested := elem("class", attr("id", itoa(tIDNested)), attr("name", "test.Nested")).with(
		elem("field", attr("name", "a"), attr("class", itoa(tIDInt))),
		elem("field", attr("name", "b"), attr("class", itoa(tIDString))),
	)
	bag := elem("class",
		attr("id", itoa(tIDBag)),
		attr("name", "test.Bag"),
		attr("superType", "jdk.jfr.Event"),
	).with(
		elem("field", attr("name", "label"), attr("class", itoa(tIDString))),
		elem("field", attr("name", "items"), attr("class", itoa(tIDNested)), attr("dimension", "1")),
	)
	return elem("root").with(
		elem("metadata").with(
			primitiveClass(tIDInt, "int"),
			primitiveClass(tIDString, "java.lang.String"),
			nested,
			bag,
		),
	)
}

// bagEvent encodes a test.Bag event with the given item labels.
func bagEvent(label string, items []string) []byte {
	w := &writer{}
	w.varint(tIDBag)
	w.bytes(utf8String(label))
	w.varint(int64(len(items)))
	for i, it := range items {
		w.varint(int64(uint32(int32(i))))
		w.bytes(utf8String(it))
	}
	return w.buf
}

// bagRecording holds events whose array field has a different length each time,
// including an empty one, so a recycled array cannot hide a stale element.
func bagRecording() []byte {
	spec := chunkSpec{
		metadata: bagMetadata(),
		events: [][]byte{
			bagEvent("three", []string{"x", "y", "z"}),
			bagEvent("zero", nil),
			bagEvent("one", []string{"only"}),
			bagEvent("two", []string{"p", "q"}),
		},
		startNanos: testStartNanos,
		startTicks: testStartTicks,
		frequency:  testFrequency,
		duration:   1000,
	}
	return spec.build()
}
