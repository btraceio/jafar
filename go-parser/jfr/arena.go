package jfr

// A valueArena recycles the objects that make up one decoded event: the maps
// holding its fields, the arrays holding its array fields, and the constant
// pool references it points through.
//
// Events of the same type decode to the same shape, so after the first event of
// a type the arena hands back exactly the objects the previous one used and the
// decode allocates nothing beyond the strings it reads. This is what
// Options.ReuseValues buys, and why it comes with the requirement that a
// handler must not keep an event past its callback.
//
// Arenas are per event type and per chunk. Values decoded for a constant pool
// entry never come from an arena: they are cached in their pool and outlive the
// event that first resolved them.
type valueArena struct {
	maps   []map[string]any
	mapIdx int

	arrays []*Array
	arrIdx int

	// refs is a block of references handed out by address. It is only ever
	// resized between events, never during one, so a reference stays valid for
	// as long as the event that produced it is.
	refs    []Ref
	refIdx  int
	refMiss int
}

// reset prepares the arena for the next event, growing the reference block to
// the high-water mark the previous events needed.
func (a *valueArena) reset() {
	if a.refMiss > 0 {
		a.refs = make([]Ref, len(a.refs)+a.refMiss)
		a.refMiss = 0
	}
	a.mapIdx, a.arrIdx, a.refIdx = 0, 0, 0
}

// arenaFor returns the arena of an event type, creating it on first use.
func (c *chunkParser) arenaFor(ct *ClassType) *valueArena {
	a := c.arenas[ct]
	if a == nil {
		a = &valueArena{}
		if c.arenas == nil {
			c.arenas = make(map[*ClassType]*valueArena)
		}
		c.arenas[ct] = a
	}
	return a
}

// newMap returns the map for the next complex value of the event being decoded.
//
// A recycled map is cleared rather than overwritten key by key: an array of
// complex values makes the number of maps an event needs vary, so the map at a
// given position is not guaranteed to have held the same type last time.
func (c *chunkParser) newMap(hint int) map[string]any {
	a := c.arena
	if a == nil {
		return make(map[string]any, hint)
	}
	if a.mapIdx < len(a.maps) {
		m := a.maps[a.mapIdx]
		a.mapIdx++
		clear(m)
		return m
	}
	m := make(map[string]any, hint)
	a.maps = append(a.maps, m)
	a.mapIdx++
	return m
}

// newArray returns the array for the next array field of the event being
// decoded.
func (c *chunkParser) newArray(elementType string, n int) *Array {
	a := c.arena
	if a == nil {
		return &Array{ElementType: elementType, Values: make([]any, n)}
	}
	if a.arrIdx < len(a.arrays) {
		arr := a.arrays[a.arrIdx]
		a.arrIdx++
		arr.ElementType = elementType
		if cap(arr.Values) >= n {
			arr.Values = arr.Values[:n]
			for i := range arr.Values {
				arr.Values[i] = nil
			}
		} else {
			arr.Values = make([]any, n)
		}
		return arr
	}
	arr := &Array{ElementType: elementType, Values: make([]any, n)}
	a.arrays = append(a.arrays, arr)
	a.arrIdx++
	return arr
}

// newRef returns the reference for the next constant pool field of the event
// being decoded. Without an arena the references come from a chunk-wide block,
// which still avoids allocating them one at a time.
func (c *chunkParser) newRef(pool *constantPool, index int64) *Ref {
	if a := c.arena; a != nil {
		if a.refIdx < len(a.refs) {
			ref := &a.refs[a.refIdx]
			a.refIdx++
			ref.pool = pool
			ref.index = index
			return ref
		}
		// Grow only between events; fall back to the chunk block for now.
		a.refMiss++
	}
	if len(c.refSlab) == 0 {
		c.refSlab = make([]Ref, refSlabSize)
	}
	ref := &c.refSlab[0]
	c.refSlab = c.refSlab[1:]
	ref.pool = pool
	ref.index = index
	return ref
}

// refSlabSize is the number of references allocated in one block. A block stays
// alive as long as any reference handed out from it does, so it trades a bounded
// amount of retention (a few kilobytes) for a large drop in allocation count.
const refSlabSize = 256
