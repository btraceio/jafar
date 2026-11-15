package differential

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"math"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
)

// The recordings below are built byte by byte, following the pattern of
// jfr/testrecording_test.go (which lives in package jfr and cannot be imported
// from here): a 68 byte chunk header, then the events, a checkpoint event and
// the metadata event, with the header pointing at the latter two. The string
// and varint tags must match the encoding the parser reads (strings.go).

const (
	nodePoolIntact = "../../jfr/testdata/node-pool-intact.jfr"

	tIDBoolean = 1
	tIDInt     = 5
	tIDLong    = 6
	tIDFloat   = 7
	tIDDouble  = 8
	tIDString  = 9
	tIDNested  = 30
	tIDEvent   = 100
	tIDMsg     = 101

	chunkHeaderSize       = 68
	featureCompressedInts = 1

	strNull = 0
	strUTF8 = 3

	testFrequency  = 1_000_000
	testStartTicks = 5_000_000
	testStartNanos = 1_700_000_000_000_000_000
)

// writer accumulates the bytes of a recording.
type writer struct {
	buf []byte
}

func (w *writer) byteVal(b byte) { w.buf = append(w.buf, b) }

func (w *writer) u16(v uint16) { w.buf = binary.BigEndian.AppendUint16(w.buf, v) }

func (w *writer) u32(v uint32) { w.buf = binary.BigEndian.AppendUint32(w.buf, v) }

func (w *writer) u64(v uint64) { w.buf = binary.BigEndian.AppendUint64(w.buf, v) }

func (w *writer) float32Val(v float32) { w.u32(math.Float32bits(v)) }

func (w *writer) float64Val(v float64) { w.u64(math.Float64bits(v)) }

func (w *writer) varint(v int64) { w.buf = appendVarint(w.buf, v) }

// appendVarint encodes v the way the JFR chunk writer does: seven bits per
// byte, least significant group first, the high bit marking a continuation.
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

// --- metadata construction --------------------------------------------------

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

func itoa(v int64) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var buf [20]byte
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

func primitiveClass(id int64, name string) *metaElement {
	return elem("class", attr("id", itoa(id)), attr("name", name), attr("simpleType", "true"))
}

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
		body.buf = append(body.buf, utf8String(s)...)
	}
	table.encodeElement(body, root)
	return sizedEvent(body.buf)
}

// encodeCheckpointEvent renders a checkpoint event with no constant pools.
func encodeCheckpointEvent() []byte {
	body := &writer{}
	body.varint(1)   // event type: checkpoint
	body.varint(200) // start time
	body.varint(0)   // duration
	body.varint(0)   // next offset delta: last checkpoint of the chunk
	body.byteVal(0)  // flush flag
	body.varint(0)   // no constant pools
	return sizedEvent(body.buf)
}

// buildChunk assembles a chunk: header, events, checkpoint, metadata.
func buildChunk(metadata *metaElement, events [][]byte) []byte {
	var ev writer
	for _, e := range events {
		ev.buf = append(ev.buf, sizedEvent(e)...)
	}
	checkpoint := encodeCheckpointEvent()
	chunkMetadata := encodeMetadataEvent(metadata)

	cpOffset := chunkHeaderSize + len(ev.buf)
	metaOffset := cpOffset + len(checkpoint)
	size := metaOffset + len(chunkMetadata)

	w := &writer{}
	w.buf = append(w.buf, 'F', 'L', 'R', 0)
	w.u16(2) // major
	w.u16(1) // minor
	w.u64(uint64(size))
	w.u64(uint64(cpOffset))
	w.u64(uint64(metaOffset))
	w.u64(uint64(testStartNanos))
	w.u64(1000) // duration
	w.u64(uint64(testStartTicks))
	w.u64(uint64(testFrequency))
	w.u32(featureCompressedInts)
	if len(w.buf) != chunkHeaderSize {
		panic("chunk header size mismatch")
	}
	w.buf = append(w.buf, ev.buf...)
	w.buf = append(w.buf, checkpoint...)
	w.buf = append(w.buf, chunkMetadata...)
	return w.buf
}

// allKindsMetadata declares an event type with one field of each shape the
// dumper must flatten: a string, a nested inline map, an array of inline maps,
// a long, a boolean, a float, a double and a null string.
func allKindsMetadata() *metaElement {
	nested := elem("class", attr("id", itoa(tIDNested)), attr("name", "test.Nested")).with(
		elem("field", attr("name", "a"), attr("class", itoa(tIDInt))),
		elem("field", attr("name", "s"), attr("class", itoa(tIDString))),
	)
	event := elem("class",
		attr("id", itoa(tIDEvent)),
		attr("name", "test.Event"),
		attr("superType", "jdk.jfr.Event"),
	).with(
		elem("field", attr("name", "message"), attr("class", itoa(tIDString))),
		elem("field", attr("name", "n"), attr("class", itoa(tIDNested))),
		elem("field", attr("name", "items"), attr("class", itoa(tIDNested)), attr("dimension", "1")),
		elem("field", attr("name", "count"), attr("class", itoa(tIDLong))),
		elem("field", attr("name", "flag"), attr("class", itoa(tIDBoolean))),
		elem("field", attr("name", "f"), attr("class", itoa(tIDFloat))),
		elem("field", attr("name", "d"), attr("class", itoa(tIDDouble))),
		elem("field", attr("name", "absent"), attr("class", itoa(tIDString))),
	)
	return elem("root").with(
		elem("metadata").with(
			primitiveClass(tIDBoolean, "boolean"),
			primitiveClass(tIDInt, "int"),
			primitiveClass(tIDLong, "long"),
			primitiveClass(tIDFloat, "float"),
			primitiveClass(tIDDouble, "double"),
			primitiveClass(tIDString, "java.lang.String"),
			nested,
			event,
		),
	)
}

// item is one element of the items array field of a test.Event.
type item struct {
	a int64
	s string
}

// allKindsEvent encodes a test.Event whose field values are the parameters;
// field order must follow the metadata declaration order.
func allKindsEvent(message string, a int64, s string, items []item, count int64, flag bool, f float32, d float64, absent bool) []byte {
	w := &writer{}
	w.varint(tIDEvent)
	w.buf = append(w.buf, utf8String(message)...)
	w.varint(a) // n.a
	w.buf = append(w.buf, utf8String(s)...)
	w.varint(int64(len(items)))
	for _, it := range items {
		w.varint(it.a)
		w.buf = append(w.buf, utf8String(it.s)...)
	}
	w.varint(count)
	if flag {
		w.byteVal(1)
	} else {
		w.byteVal(0)
	}
	w.float32Val(f)
	w.float64Val(d)
	if absent {
		w.buf = append(w.buf, utf8String("present")...)
	} else {
		w.buf = append(w.buf, nullString()...)
	}
	return w.buf
}

// TestDumpFlattensSortsAndFormats pins the whole dump of a small synthetic
// recording: the header, one line per event, byte-order sorting of both the
// fields within a line and the lines themselves, dotted paths for nested maps,
// .N indices for arrays, the f:<bits> float format and null.
func TestDumpFlattensSortsAndFormats(t *testing.T) {
	data := buildChunk(allKindsMetadata(), [][]byte{
		allKindsEvent("hello world", 7, "inner", []item{{1, "x"}, {2, "y"}}, 42, true, 2.5, 1.5, false),
		allKindsEvent("beta", 8, "second", []item{{3, "z"}}, 43, false, 2.5, 0.25, true),
	})
	path := filepath.Join(t.TempDir(), "all-kinds.jfr")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}

	var buf bytes.Buffer
	if err := Dump(path, &buf); err != nil {
		t.Fatalf("Dump: %v", err)
	}

	want := strings.Join([]string{
		"# jafar-dump v1 side=go fixture-sha256=" + fmt64(sha256.Sum256(data)) + " events=2",
		"test.Event absent=null count=42 d=f:3ff8000000000000 f=f:4004000000000000 flag=true items.0.a=1 items.0.s=x items.1.a=2 items.1.s=y message=hello world n.a=7 n.s=inner",
		"test.Event absent=present count=43 d=f:3fd0000000000000 f=f:4004000000000000 flag=false items.0.a=3 items.0.s=z message=beta n.a=8 n.s=second",
		"",
	}, "\n")
	if got := buf.String(); got != want {
		t.Errorf("dump mismatch\n got: %q\nwant: %q", got, want)
	}
}

// TestDumpEscapesEmbeddedNewlines proves a string value carrying \n and \r
// stays on one line: the escape is two visible characters, and the output has
// exactly one line per event.
func TestDumpEscapesEmbeddedNewlines(t *testing.T) {
	metadata := elem("root").with(
		elem("metadata").with(
			primitiveClass(tIDString, "java.lang.String"),
			elem("class", attr("id", itoa(tIDMsg)), attr("name", "test.Msg"), attr("superType", "jdk.jfr.Event")).with(
				elem("field", attr("name", "text"), attr("class", itoa(tIDString))),
			),
		),
	)
	msg := func(text string) []byte {
		w := &writer{}
		w.varint(tIDMsg)
		w.buf = append(w.buf, utf8String(text)...)
		return w.buf
	}
	data := buildChunk(metadata, [][]byte{msg("alpha\nbeta\r\ngamma")})

	path := filepath.Join(t.TempDir(), "multiline.jfr")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}

	var buf bytes.Buffer
	if err := Dump(path, &buf); err != nil {
		t.Fatalf("Dump: %v", err)
	}
	out := buf.String()
	if !strings.Contains(out, "text=alpha\\nbeta\\r\\ngamma\n") {
		t.Errorf("output does not carry the escaped value: %q", out)
	}
	lines := strings.Split(strings.TrimSuffix(out, "\n"), "\n")
	if len(lines) != 2 { // header + the one event
		t.Errorf("got %d output lines, want 2 (header + 1 event): %q", len(lines), out)
	}
	if strings.ContainsAny(lines[1], "\n\r") {
		t.Errorf("event line still holds a raw newline or carriage return: %q", lines[1])
	}
}

// TestDumpNodePoolIntactFixture checks the harness against the shared Java/Go
// fixture: the header carries the fixture's real sha256 and the true event
// count, the lines are sorted, and no chunk identity appears anywhere.
func TestDumpNodePoolIntactFixture(t *testing.T) {
	data, err := os.ReadFile(nodePoolIntact)
	if err != nil {
		t.Skipf("fixture %s is not available: %v", nodePoolIntact, err)
	}
	path := filepath.Join(t.TempDir(), "node-pool-intact.jfr")
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}

	var buf bytes.Buffer
	if err := Dump(path, &buf); err != nil {
		t.Fatalf("Dump: %v", err)
	}
	lines := strings.Split(strings.TrimSuffix(buf.String(), "\n"), "\n")
	if len(lines) < 2 {
		t.Fatalf("dump holds %d lines, want a header plus events: %q", len(lines), buf.String())
	}
	events := lines[1:]

	wantHeader := "# jafar-dump v1 side=go fixture-sha256=" + fmt64(sha256.Sum256(data)) +
		" events=" + itoa(int64(len(events)))
	if lines[0] != wantHeader {
		t.Errorf("header = %q, want %q", lines[0], wantHeader)
	}
	if !sort.StringsAreSorted(events) {
		t.Errorf("event lines are not sorted in byte order")
	}
	if strings.Contains(buf.String(), "chunk") {
		t.Errorf("dump carries chunk identity: %q", buf.String())
	}
	// The fixture holds seven test.Holder events (jfr/parser_test.go, nodeRefs);
	// the dumper must see them all, resolved through the node pool.
	var holders int
	for _, line := range events {
		if strings.HasPrefix(line, "test.Holder ") {
			holders++
		}
	}
	if holders != 7 {
		t.Errorf("dumped %d test.Holder lines, want 7:\n%s", holders, buf.String())
	}
}

// TestDumpParseErrorWritesErrorHeader pins the failure contract: a recording
// that does not parse produces the error header, only the error header, and a
// returned error.
func TestDumpParseErrorWritesErrorHeader(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bad.jfr")
	if err := os.WriteFile(path, []byte("not a jfr recording at all"), 0o644); err != nil {
		t.Fatal(err)
	}

	var buf bytes.Buffer
	if err := Dump(path, &buf); err == nil {
		t.Fatal("Dump succeeded on garbage, want an error")
	}
	out := buf.String()
	if !strings.HasPrefix(out, "# jafar-dump v1 side=go error=") {
		t.Errorf("output does not start with the error header: %q", out)
	}
	if strings.Count(out, "\n") != 1 {
		t.Errorf("error dump holds more than the header line: %q", out)
	}
	if strings.ContainsAny(out, "\r") {
		t.Errorf("error header holds a raw carriage return: %q", out)
	}
}

// TestDumpMissingFileWritesErrorHeader pins that an unreadable recording
// cannot be mistaken for an empty dump either.
func TestDumpMissingFileWritesErrorHeader(t *testing.T) {
	var buf bytes.Buffer
	if err := Dump(filepath.Join(t.TempDir(), "absent.jfr"), &buf); err == nil {
		t.Fatal("Dump succeeded on a missing file, want an error")
	}
	if !strings.HasPrefix(buf.String(), "# jafar-dump v1 side=go error=") {
		t.Errorf("output does not start with the error header: %q", buf.String())
	}
}

// fmt64 renders a sha256 sum as lowercase hex.
func fmt64(sum [32]byte) string {
	const digits = "0123456789abcdef"
	out := make([]byte, 64)
	for i, b := range sum {
		out[i*2] = digits[b>>4]
		out[i*2+1] = digits[b&0xf]
	}
	return string(out)
}
