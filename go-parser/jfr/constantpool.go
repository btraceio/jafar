package jfr

import "fmt"

// constantPool holds the offsets of the entries of one constant pool type
// within a chunk, and caches the values decoded from them.
//
// Entries are decoded lazily: a checkpoint event only records where each entry
// starts, and the bytes are decoded the first time a *Ref pointing at the entry
// is resolved.
type constantPool struct {
	typeID  int64
	class   *ClassType
	offsets map[int64]int
	cache   map[int64]any
	pending map[int64]bool
	errors  map[int64]error
	// deepCache memoises fully resolved entries for ResolveDeep. Created on
	// first use, since most consumers never resolve deeply.
	deepCache map[int64]any
	chunk     *chunkParser
}

func newConstantPool(chunk *chunkParser, typeID int64, hint int) *constantPool {
	if hint < 0 || hint > 1<<20 {
		hint = 0
	}
	return &constantPool{
		typeID:  typeID,
		class:   chunk.md.Class(typeID),
		offsets: make(map[int64]int, hint),
		cache:   make(map[int64]any),
		pending: make(map[int64]bool),
		errors:  make(map[int64]error),
		chunk:   chunk,
	}
}

// addOffset records where entry id starts, unless an earlier checkpoint of the
// chunk already indexed it. It reports whether it recorded the offset.
func (p *constantPool) addOffset(id int64, offset int) bool {
	if _, ok := p.offsets[id]; ok {
		return false
	}
	p.offsets[id] = offset
	return true
}

// indexedEntry is a constant pool entry a checkpoint indexed, kept so that the
// checkpoint can take the entry back when its framing turns out to be wrong.
type indexedEntry struct {
	pool *constantPool
	id   int64
}

// has reports whether the chunk declared an entry with the given index and it
// was indexed.
func (p *constantPool) has(id int64) bool {
	_, ok := p.offsets[id]
	return ok
}

// value returns the decoded constant pool entry, decoding it on first use.
func (p *constantPool) value(id int64) (any, error) {
	if v, ok := p.cache[id]; ok {
		return v, nil
	}
	if err, ok := p.errors[id]; ok {
		return nil, err
	}
	offset, ok := p.offsets[id]
	if !ok {
		return nil, nil
	}
	if p.pending[id] {
		// A cyclic constant pool entry; the JVM writer does not emit these,
		// but a corrupted recording could.
		return nil, fmt.Errorf("constant pool %s: cyclic reference at index %d", p.typeName(), id)
	}
	p.pending[id] = true
	defer delete(p.pending, id)

	v, err := p.chunk.decodeConstant(p.class, offset)
	if err != nil {
		err = fmt.Errorf("constant pool %s, index %d: %w", p.typeName(), id, err)
		p.errors[id] = err
		return nil, err
	}
	p.cache[id] = v
	return v, nil
}

func (p *constantPool) typeName() string {
	if p.class != nil {
		return p.class.Name
	}
	return fmt.Sprintf("type#%d", p.typeID)
}

// constantPools indexes the constant pools of a chunk by type ID.
type constantPools struct {
	chunk *chunkParser
	pools map[int64]*constantPool
	// indexingFailure is the first problem that stopped the checkpoints of
	// the chunk from being indexed completely, or nil. When it is set, some
	// declared entries may never have been indexed, so a reference that does
	// not resolve may point at one of them rather than be dangling.
	indexingFailure error
}

// recordIndexingFailure remembers the first checkpoint indexing failure.
func (ps *constantPools) recordIndexingFailure(err error) {
	if ps.indexingFailure == nil {
		ps.indexingFailure = err
	}
}

// missing explains a string reference that does not resolve: in a chunk with
// an indexing failure that failure is the likeliest cause, so it is wrapped.
func (ps *constantPools) missing(err error) error {
	if ps.indexingFailure == nil {
		return err
	}
	return fmt.Errorf("%w (constant pool indexing stopped early in this chunk: %w)", err, ps.indexingFailure)
}

// unindexedRef builds the error for a reference to an entry missing from its
// pool in a chunk with an indexing failure. Only called when indexingFailure
// is set.
func (ps *constantPools) unindexedRef(p *constantPool, idx int64) error {
	return fmt.Errorf("constant pool reference %d to %s does not resolve: the pool has no such entry (constant pool indexing stopped early in this chunk: %w)", idx, p.typeName(), ps.indexingFailure)
}

func newConstantPools(chunk *chunkParser) *constantPools {
	return &constantPools{chunk: chunk, pools: make(map[int64]*constantPool)}
}

// get returns the pool for typeID, or nil when the chunk declares none.
func (ps *constantPools) get(typeID int64) *constantPool {
	return ps.pools[typeID]
}

// getOrCreate returns the pool for typeID, creating an empty one when the chunk
// declares no entries for the type. References into an empty pool resolve to
// nil, which is what the JVM's own reader does for a dangling index.
func (ps *constantPools) getOrCreate(typeID int64) *constantPool {
	if p, ok := ps.pools[typeID]; ok {
		return p
	}
	p := newConstantPool(ps.chunk, typeID, 0)
	ps.pools[typeID] = p
	return p
}

func (ps *constantPools) addOrGet(typeID int64, hint int) *constantPool {
	if p, ok := ps.pools[typeID]; ok {
		return p
	}
	p := newConstantPool(ps.chunk, typeID, hint)
	ps.pools[typeID] = p
	return p
}

// decodeConstant decodes a single constant pool entry of type ct located at the
// given chunk-relative offset.
//
// Constant pool entries of a simple type store the wrapped value unwrapped, so
// they are decoded field-wise rather than through readValue.
func (c *chunkParser) decodeConstant(ct *ClassType, offset int) (any, error) {
	if ct == nil {
		return nil, fmt.Errorf("unknown constant pool type")
	}
	r := c.cpReader
	saved := r.pos
	savedErr := r.err
	r.err = nil
	// A constant pool value is cached in its pool and outlives the event that
	// first resolved it, so it must never be built from an event's arena.
	savedArena, savedInPool := c.arena, c.inPool
	c.arena, c.inPool = nil, true
	defer func() {
		r.pos = saved
		r.err = savedErr
		c.arena, c.inPool = savedArena, savedInPool
	}()
	r.seek(offset)
	if r.err != nil {
		return nil, r.err
	}

	if ct.Name == "java.lang.String" {
		s, isNull, err := readString(r, c.md, c)
		if err != nil {
			return nil, err
		}
		if isNull {
			return nil, nil
		}
		return s, nil
	}
	if field := simpleTypeField(ct); field != nil {
		v, err := c.readPrimitive(r, ct, unwrapSimple(field.Type), field.Name)
		if err != nil {
			return nil, err
		}
		return map[string]any{field.Name: v}, nil
	}
	return c.readValue(r, ct, 0)
}

// simpleTypeField returns the single wrapped field of a simple type whose
// unwrapped type is a primitive, or nil when ct is not such a type.
func simpleTypeField(ct *ClassType) *Field {
	if !ct.SimpleType || len(ct.Fields) != 1 {
		return nil
	}
	f := ct.Fields[0]
	if !unwrappable(f) {
		return nil
	}
	if u := unwrapSimple(f.Type); u != nil && u.primitive {
		return f
	}
	return nil
}

// unwrapSimple follows the chain of single-field simple types down to the type
// that is actually encoded.
func unwrapSimple(ct *ClassType) *ClassType {
	for depth := 0; ct != nil && ct.SimpleType && len(ct.Fields) == 1 && depth < 16; depth++ {
		f := ct.Fields[0]
		if !unwrappable(f) || f.Type == nil || f.Type == ct {
			break
		}
		ct = f.Type
	}
	return ct
}

// unwrappable reports whether a simple type's single field is encoded inline as
// a plain value, which is the only shape that can stand in for the type that
// wraps it. A pooled or array field keeps its own framing and has to be decoded
// through the general field path.
func unwrappable(f *Field) bool {
	return !f.ConstantPool && f.Dimension != 1
}
