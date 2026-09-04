// Package jfr implements an untyped parser for JDK Flight Recorder (JFR)
// recordings.
//
// It is a Go port of the untyped API of the Jafar parser: events are handed to
// the caller as map[string]any keyed by field name, with the following value
// types:
//
//	JFR type            Go type
//	------------------------------------------------------
//	boolean             bool
//	byte                int8
//	char                uint16
//	short               int16
//	int                 int64 (sign extended from 32 bits)
//	long                int64
//	float               float32
//	double              float64
//	java.lang.String    string, or nil for a null string
//	inline complex      map[string]any
//	constant pool ref   *Ref (resolve with Ref.Value)
//	array               *Array
//
// Fields annotated with jdk.jfr.Timestamp(TICKS) and jdk.jfr.Timespan(TICKS)
// are normalised: timestamps become nanoseconds since the epoch and timespans
// become nanosecond durations, so callers never deal with raw ticks.
//
// Constant pool references are resolved lazily and cached per chunk, which
// keeps the cost of a large stack trace pool proportional to the number of
// distinct entries rather than to the number of events referencing them.
package jfr

import (
	"errors"
	"fmt"
	"os"
)

// ErrStop can be returned from an event handler to stop parsing without
// reporting an error.
var ErrStop = errors.New("jfr: parsing stopped")

// Event is a single decoded JFR event.
type Event struct {
	// Type is the event type as declared in the chunk metadata.
	Type *ClassType
	// Values holds the event fields keyed by field name.
	Values map[string]any
	// Chunk describes the chunk the event was read from.
	Chunk *ChunkInfo
}

// Get looks a value up by path; see the package level Get function.
func (e *Event) Get(path ...any) any {
	return Get(e.Values, path...)
}

// EventFunc is called for every decoded event. Returning ErrStop ends parsing
// successfully; any other error aborts parsing and is returned from Parse.
//
// The Values map is not reused between events, but constant pool values
// reachable through *Ref are shared within a chunk and must not be mutated.
type EventFunc func(*Event) error

// Options tunes a parsing run. The zero value decodes every event and reports
// the first decoding error.
type Options struct {
	// TypeFilter, when set, is consulted for every event type; events whose
	// type is rejected are skipped without being decoded.
	TypeFilter func(*ClassType) bool

	// OnChunkStart, when set, is called once per chunk after its metadata and
	// constant pools have been read and before any of its events are decoded.
	// Returning ErrStop ends parsing successfully; any other error aborts it.
	OnChunkStart func(*ChunkInfo, *Metadata) error

	// OnChunkEnd, when set, is called once per chunk after its last event.
	OnChunkEnd func(*ChunkInfo) error

	// OnError is called for recoverable decoding problems: a checkpoint chain
	// that cannot be followed, a constant pool referring to an undeclared type,
	// or a single event that fails to decode. Parsing continues when it returns
	// nil and aborts with the returned error otherwise.
	//
	// When OnError is nil recoverable problems are ignored and parsing
	// continues, which is what reading a truncated or non-conforming recording
	// on a best-effort basis needs. Pass FailOnError to turn them into hard
	// failures instead.
	//
	// Problems that leave the parser without a resynchronisation point - a bad
	// chunk magic, an out-of-range chunk size, undecodable chunk metadata -
	// always abort parsing and are never reported here.
	OnError func(error) error
}

// FailOnError is an Options.OnError implementation that turns every recoverable
// decoding problem into a parsing failure.
func FailOnError(err error) error { return err }

// Parser reads a JFR recording held in memory.
//
// A Parser is read-only and may be reused for any number of parsing runs, but a
// single run is not safe for concurrent use.
type Parser struct {
	data []byte
	name string
}

// Open reads the JFR recording at path into memory and returns a parser for it.
func Open(path string) (*Parser, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	p := NewParser(data)
	p.name = path
	return p, nil
}

// NewParser returns a parser reading the given recording bytes. The slice is
// retained, not copied, and must not be modified while the parser is in use.
func NewParser(data []byte) *Parser {
	return &Parser{data: data, name: "<memory>"}
}

// Parse decodes every event of the recording and calls fn for each of them.
func (p *Parser) Parse(fn EventFunc) error {
	return p.ParseWith(Options{}, fn)
}

// ParseWith decodes the recording using the given options.
//
// Chunks are processed in recording order and fn is called from the calling
// goroutine, so events arrive in the order they appear in the file.
func (p *Parser) ParseWith(opts Options, fn EventFunc) error {
	if fn == nil {
		fn = func(*Event) error { return nil }
	}
	err := p.parse(opts, fn)
	if errors.Is(err, ErrStop) {
		return nil
	}
	return err
}

// ChunkHeaders scans the recording and returns the header of every chunk
// without decoding metadata, constant pools or events.
func (p *Parser) ChunkHeaders() ([]*ChunkHeader, error) {
	var headers []*ChunkHeader
	err := p.forEachChunk(func(h *ChunkHeader, _ []byte) error {
		headers = append(headers, h)
		return nil
	})
	if err != nil {
		return nil, err
	}
	return headers, nil
}

// forEachChunk walks the chunk sequence, handing each chunk's header and its
// bytes (the header included, so that chunk-relative offsets apply) to fn.
func (p *Parser) forEachChunk(fn func(*ChunkHeader, []byte) error) error {
	offset := 0
	index := 1
	for offset < len(p.data) {
		if err := checkMagic(p.data[offset:], p.name, offset); err != nil {
			return err
		}
		if remaining := len(p.data) - offset; remaining < chunkHeaderSize {
			return fmt.Errorf("%s: truncated chunk %d: %d byte(s) left, %d needed for a chunk header",
				p.name, index, remaining, chunkHeaderSize)
		}
		r := newReader(p.data[offset:])
		header, err := readChunkHeader(r, index, int64(offset))
		if err != nil {
			return fmt.Errorf("%s: %w", p.name, err)
		}
		if !header.CompressedInts() {
			return fmt.Errorf("%s: chunk %d does not use the compressed integer encoding (features 0x%x); only varint encoded chunks are supported",
				p.name, index, uint32(header.Features))
		}
		if header.Size <= 0 || header.Size > int64(len(p.data)-offset) {
			return fmt.Errorf("%s: chunk %d declares size %d but only %d byte(s) are left",
				p.name, index, header.Size, len(p.data)-offset)
		}
		end := offset + int(header.Size)
		if err := fn(header, p.data[offset:end]); err != nil {
			return err
		}
		offset = end
		index++
	}
	return nil
}

func (p *Parser) parse(opts Options, fn EventFunc) error {
	return p.forEachChunk(func(header *ChunkHeader, data []byte) error {
		c := &chunkParser{
			header:   header,
			info:     newChunkInfo(header),
			data:     data,
			cpReader: newReader(data),
			opts:     &opts,
		}
		c.pools = newConstantPools(c)
		if err := c.readChunkMetadata(); err != nil {
			return fmt.Errorf("%s: chunk %d: %w", p.name, header.Order, err)
		}
		if err := c.readCheckpoints(); err != nil {
			return fmt.Errorf("%s: chunk %d: %w", p.name, header.Order, err)
		}
		if opts.OnChunkStart != nil {
			if err := opts.OnChunkStart(c.info, c.md); err != nil {
				return err
			}
		}
		if err := c.readEvents(fn); err != nil {
			var he *handlerError
			if errors.As(err, &he) {
				// The caller's own error is passed through untouched.
				return he.err
			}
			return fmt.Errorf("%s: chunk %d: %w", p.name, header.Order, err)
		}
		if opts.OnChunkEnd != nil {
			if err := opts.OnChunkEnd(c.info); err != nil {
				return err
			}
		}
		return nil
	})
}

// chunkParser holds the decoding state of a single chunk.
type chunkParser struct {
	header *ChunkHeader
	info   *ChunkInfo
	// data is the chunk including its header, so that the chunk-relative
	// offsets carried by the header can be used directly.
	data  []byte
	md    *Metadata
	pools *constantPools
	// cpReader is a dedicated reader used to decode constant pool entries
	// while the event reader stays parked mid-event.
	cpReader *reader
	opts     *Options
	// headerSize is the number of bytes consumed by the chunk header.
	headerSize int
}

func (c *chunkParser) readChunkMetadata() error {
	if c.header.MetadataOffset <= 0 || c.header.MetadataOffset >= int64(len(c.data)) {
		return fmt.Errorf("metadata offset %d is outside the chunk", c.header.MetadataOffset)
	}
	r := newReader(c.data)
	r.seek(int(c.header.MetadataOffset))
	if r.err != nil {
		return r.err
	}
	ev, err := readMetadata(r)
	if err != nil {
		return fmt.Errorf("metadata: %w", err)
	}
	c.md = ev.Metadata
	return nil
}

// readCheckpoints walks the checkpoint chain of the chunk. The chunk header
// points at one end of the chain and every checkpoint carries a signed offset
// delta to the next one, so the chain may run forwards or backwards.
//
// Constant pools are auxiliary data: a chain that cannot be followed to its end
// is reported through Options.OnError and leaves the pools read so far in
// place, rather than failing the chunk.
func (c *chunkParser) readCheckpoints() error {
	if c.header.ConstantPoolOffset <= 0 || c.header.ConstantPoolOffset >= int64(len(c.data)) {
		return c.recoverable(fmt.Errorf("constant pool offset %d is outside the chunk", c.header.ConstantPoolOffset))
	}
	r := newReader(c.data)
	pos := int(c.header.ConstantPoolOffset)
	visited := make(map[int]bool)
	for {
		if visited[pos] {
			return c.recoverable(fmt.Errorf("checkpoint at %d: cyclic checkpoint chain", pos))
		}
		visited[pos] = true
		r.err = nil
		r.seek(pos)
		if r.err != nil {
			return c.recoverable(r.err)
		}
		next, err := c.readCheckpoint(r)
		if err != nil {
			return c.recoverable(err)
		}
		if next == 0 {
			return nil
		}
		newPos := pos + next
		if newPos < 0 || newPos >= len(c.data) {
			return c.recoverable(fmt.Errorf("checkpoint at %d: next checkpoint offset %d is outside the chunk", pos, newPos))
		}
		pos = newPos
	}
}

// readCheckpoint reads one checkpoint event and returns the offset delta to the
// next one, or 0 when this is the last checkpoint of the chunk.
func (c *chunkParser) readCheckpoint(r *reader) (int, error) {
	start := r.position()
	size := r.readVarint()
	if r.err != nil {
		return 0, r.err
	}
	if size <= 0 {
		return 0, fmt.Errorf("checkpoint at %d: unexpected event size %d", start, size)
	}
	end := start + int(size)
	if end > len(c.data) {
		return 0, fmt.Errorf("checkpoint at %d: event size %d runs past the chunk", start, size)
	}
	if typeID := r.readVarint(); typeID != 1 {
		return 0, fmt.Errorf("checkpoint at %d: unexpected event type %d (should be 1)", start, typeID)
	}
	r.readVarint() // start time
	r.readVarint() // duration
	nextOffsetDelta := r.readVarint()
	r.readByte() // flush flag
	poolCount := r.readVarint()
	if r.err != nil {
		return 0, r.err
	}
	if poolCount < 0 || poolCount > int64(end-r.position()) {
		return 0, fmt.Errorf("checkpoint at %d: implausible constant pool count %d", start, poolCount)
	}
	for i := int64(0); i < poolCount; i++ {
		if err := c.readConstantPool(r, start, end); err != nil {
			// The remaining pools of this checkpoint cannot be located once one
			// of them cannot be read, but the chain itself is still intact.
			if err := c.recoverable(fmt.Errorf("checkpoint at %d: %w", start, err)); err != nil {
				return 0, err
			}
			return int(nextOffsetDelta), nil
		}
	}
	return int(nextOffsetDelta), r.err
}

// maxZeroTypeIDs bounds the workaround for a JMC writer bug that emits zero
// type IDs in checkpoints.
const maxZeroTypeIDs = 1024

// readConstantPool reads the entries of one constant pool of a checkpoint,
// recording where each entry starts. end is the exclusive end offset of the
// enclosing checkpoint event; every entry has to lie within it.
func (c *chunkParser) readConstantPool(r *reader, checkpointStart, end int) error {
	var typeID int64
	for i := 0; ; i++ {
		typeID = r.readVarint()
		if r.err != nil {
			return r.err
		}
		// Skip zero type IDs, which the JMC JFR writer is known to emit.
		if typeID != 0 {
			break
		}
		if i >= maxZeroTypeIDs {
			return fmt.Errorf("more than %d zero type ids", maxZeroTypeIDs)
		}
	}
	count := r.readVarint()
	if r.err != nil {
		return r.err
	}
	if count < 0 || count > int64(end-r.position()) {
		return fmt.Errorf("constant pool %d declares an implausible entry count %d", typeID, count)
	}
	class := c.md.Class(typeID)
	if class == nil {
		return fmt.Errorf("constant pool type %d is not declared in the chunk metadata", typeID)
	}
	pool := c.pools.addOrGet(typeID, int(count))
	for i := int64(0); i < count; i++ {
		id := r.readVarint()
		if r.err != nil {
			return r.err
		}
		offset := r.position()
		if err := c.skipConstant(r, class); err != nil {
			return fmt.Errorf("constant pool %s, index %d: %w", class.Name, id, err)
		}
		if r.position() > end {
			return fmt.Errorf("constant pool %s, index %d: entry runs past the checkpoint event", class.Name, id)
		}
		pool.addOffset(id, offset)
	}
	return nil
}

func (c *chunkParser) readEvents(fn EventFunc) error {
	r := newReader(c.data)
	// Recompute the header size from the header encoding rather than assuming
	// it, so that a future header layout keeps the event loop aligned.
	if _, err := readChunkHeader(r, c.header.Order, c.header.Offset); err != nil {
		return err
	}
	c.headerSize = r.position()

	size := int64(c.header.Size)
	pos := int64(c.headerSize)
	for pos < size {
		r.err = nil
		r.seek(int(pos))
		if r.err != nil {
			return r.err
		}
		eventSize := r.readVarint()
		if r.err != nil {
			// A trailing event whose size varint is cut off ends the chunk.
			return c.recoverable(fmt.Errorf("event at %d: %w", pos, r.err))
		}
		if eventSize <= 0 {
			// Padding between events; resynchronise on the next byte.
			pos = int64(r.position())
			continue
		}
		if eventSize > size-pos {
			// The last event runs past the chunk boundary; stop here rather
			// than reading into whatever follows the chunk.
			return c.recoverable(fmt.Errorf("event at %d: size %d runs past the chunk", pos, eventSize))
		}
		eventType := r.readVarint()
		if r.err != nil {
			return c.recoverable(fmt.Errorf("event at %d: %w", pos, r.err))
		}
		// Type IDs 0 and 1 are the metadata and checkpoint events, which are
		// read separately.
		if eventType > 1 {
			if err := c.readEvent(r, eventType, fn); err != nil {
				return err
			}
		}
		pos += eventSize
	}
	return nil
}

func (c *chunkParser) readEvent(r *reader, typeID int64, fn EventFunc) error {
	class := c.md.Class(typeID)
	if class == nil {
		return c.recoverable(fmt.Errorf("event at %d: type %d is not declared in the chunk metadata", r.position(), typeID))
	}
	if c.opts.TypeFilter != nil && !c.opts.TypeFilter(class) {
		return nil
	}
	values, err := c.readValue(r, class, 0)
	if err != nil {
		return c.recoverable(fmt.Errorf("event %s: %w", class.Name, err))
	}
	m, ok := values.(map[string]any)
	if !ok {
		return c.recoverable(fmt.Errorf("event %s: unexpected non-complex event value", class.Name))
	}
	if err := fn(&Event{Type: class, Values: m, Chunk: c.info}); err != nil {
		return &handlerError{err}
	}
	return nil
}

// handlerError carries an error raised by the caller's event handler so that
// the parser can tell it apart from its own decoding errors and pass it back
// unchanged.
type handlerError struct{ err error }

func (e *handlerError) Error() string { return e.err.Error() }

func (e *handlerError) Unwrap() error { return e.err }

// recoverable reports a problem the parser can resynchronise from. It returns
// nil when parsing should continue.
func (c *chunkParser) recoverable(err error) error {
	if c.opts.OnError != nil {
		return c.opts.OnError(err)
	}
	return nil
}

// compressionMagics maps the leading bytes of the container formats a JFR file
// is commonly wrapped in to a hint for the user.
var compressionMagics = []struct {
	magic []byte
	label string
	hint  string
}{
	{[]byte{0x04, 0x22, 0x4d, 0x18}, "LZ4", "decompress with: lz4 -d <file>"},
	{[]byte{0x1f, 0x8b}, "gzip", "decompress with: gzip -d <file>"},
	{[]byte{0x28, 0xb5, 0x2f, 0xfd}, "zstd", "decompress with: zstd -d <file>"},
}

// checkMagic validates the JFR chunk magic and turns a well-known compression
// header into an actionable error.
func checkMagic(data []byte, name string, offset int) error {
	if len(data) >= 4 && data[0] == 'F' && data[1] == 'L' && data[2] == 'R' && data[3] == 0 {
		return nil
	}
	if offset == 0 {
		for _, cm := range compressionMagics {
			if len(data) >= len(cm.magic) && string(data[:len(cm.magic)]) == string(cm.magic) {
				return fmt.Errorf("%s: file is %s-compressed and cannot be parsed directly; %s", name, cm.label, cm.hint)
			}
		}
	}
	n := len(data)
	if n > 4 {
		n = 4
	}
	return fmt.Errorf("%s: invalid JFR magic number %q at offset %d", name, data[:n], offset)
}
