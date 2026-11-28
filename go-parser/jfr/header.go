package jfr

// startTimeIndex returns the index of ct's startTime field when it is a long,
// neither an array nor a constant pool reference, and -1 otherwise. Such a
// field is written as a varint like every long, so an event header can carry
// it by skipping the fields before it and reading one varint, without
// decoding the event.
func startTimeIndex(ct *ClassType) int {
	if ct.startTimeAt == 0 {
		// Stored plus two, so that 0 stays "not computed" and a miss (-1) is
		// cached as 1 like any index.
		ct.startTimeAt = findStartTime(ct) + 2
	}
	return ct.startTimeAt - 2
}

func findStartTime(ct *ClassType) int {
	for i, f := range ct.Fields {
		if f.Name != "startTime" {
			continue
		}
		if f.Dimension == 1 || f.ConstantPool || f.Type == nil || f.Type.Name != "long" {
			return -1
		}
		return i
	}
	return -1
}

// peekStartTime skips the fields before the event's startTime, reads it and
// puts the reader back at the start of the payload. A payload that cannot hold
// it (a corrupted event; the event may not be decoded, so nothing else would
// report it) yields NoStartTime.
func (c *chunkParser) peekStartTime(r *reader, ct *ClassType) int64 {
	idx := startTimeIndex(ct)
	if idx < 0 {
		return NoStartTime
	}
	pos := r.position()
	defer func() {
		r.err = nil
		r.seek(pos)
	}()
	for i := 0; i < idx; i++ {
		if err := c.skipField(r, ct.Fields[i], 0); err != nil {
			return NoStartTime
		}
	}
	v := r.readVarint()
	if r.err != nil {
		return NoStartTime
	}
	return c.normalize(ct, "startTime", v)
}

// CountEvents counts the events of each type filter accepts (every type when
// filter is nil) without decoding them: it parses with a TypeFilter that
// rejects every type and counts in OnEventHeader. A type with no event is
// absent from the result. Recoverable problems (an event of a type the
// metadata does not declare, a checkpoint that cannot be followed) are passed
// over as ParseWith does without OnError, so such events are not counted; to
// fail on them instead, count in OnEventHeader with OnError: FailOnError.
func (p *Parser) CountEvents(filter func(*ClassType) bool) (map[string]int64, error) {
	counts := make(map[string]int64)
	err := p.ParseWith(Options{
		TypeFilter:             func(*ClassType) bool { return false },
		HeaderFilter:           filter,
		headerWithoutStartTime: true,
		OnEventHeader: func(h EventHeader) error {
			counts[h.Type.Name]++
			return nil
		},
	}, nil)
	if err != nil {
		return nil, err
	}
	return counts, nil
}
