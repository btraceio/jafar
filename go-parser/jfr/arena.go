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
// decoded. The caller sizes its elements with sized.
//
// A recycled array keeps the slice it last held, so an event shaped like the
// previous one of its type decodes into the same backing array. Without an
// arena the array is new: allocated on its own for an event, from a chunk-wide
// block inside a constant pool entry.
func (c *chunkParser) newArray(elementType string) *Array {
	a := c.arena
	if a == nil {
		if !c.inPool {
			// An event decoded without ReuseValues is the caller's to keep,
			// so its arrays get their own allocations: a shared block would
			// keep every sibling array's elements alive with any one of them.
			return &Array{ElementType: elementType}
		}
		// Not recyclable either: a constant pool value is cached and shared
		// by every event referencing it. Arrays inside pool entries live as
		// long as their pool anyway, so their structs come from a chunk-wide
		// block, which pays for the slice header that Elems costs.
		if len(c.arraySlab) == 0 {
			c.arraySlab = make([]Array, arraySlabSize)
		}
		arr := &c.arraySlab[0]
		c.arraySlab = c.arraySlab[1:]
		arr.ElementType = elementType
		return arr
	}
	var arr *Array
	if a.arrIdx < len(a.arrays) {
		arr = a.arrays[a.arrIdx]
	} else {
		arr = &Array{}
		a.arrays = append(a.arrays, arr)
	}
	a.arrIdx++
	arr.ElementType = elementType
	return arr
}

// sized returns arr's elements as a []T of n elements, for the decoder to
// overwrite, reusing the slice arr already holds when it has type []T and the
// capacity.
//
// Storing a slice in an interface copies its header to the heap
// (runtime.convTslice), so Elems is assigned only when the header changes: a
// recycled array whose element count matches the previous event's costs
// nothing. An array whose element type differs from last time (an array of
// complex values makes the array at a given position vary) gets a new slice.
func sized[T any](arr *Array, n int) []T {
	if cur, ok := arr.Elems.([]T); ok {
		if len(cur) == n {
			return cur
		}
		if cap(cur) >= n {
			if n < len(cur) {
				clear(cur[n:]) // drop references the shorter array no longer covers
			}
			cur = cur[:n]
			arr.Elems = cur
			return cur
		}
	}
	if n == 0 {
		arr.Elems = []T(nil) // a nil slice converts to an interface without allocating
		return nil
	}
	s := make([]T, n)
	arr.Elems = s
	return s
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

// arraySlabSize is the number of Array structs allocated in one block. A block
// stays alive, with every array in it and the elements they hold, as long as
// any one of them is reachable; only constant pool arrays, which their pool
// keeps alive anyway, share blocks.
const arraySlabSize = 64
