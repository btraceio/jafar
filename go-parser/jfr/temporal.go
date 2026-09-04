package jfr

// JFR marks tick-based fields with metadata annotations. Fields annotated with
// jdk.jfr.Timestamp(TICKS) hold a timestamp in ticks, fields annotated with
// jdk.jfr.Timespan(TICKS) a duration in ticks. Both are normalised to
// nanoseconds so that consumers never have to know the chunk's tick frequency.
const (
	temporalNone = iota
	temporalTimestamp
	temporalTimespan
)

const (
	timestampAnnotation = "jdk.jfr.Timestamp"
	timespanAnnotation  = "jdk.jfr.Timespan"
	ticksUnit           = "TICKS"
)

// normalize converts a tick-based field value to nanoseconds. Values of fields
// that are not tick-based are returned unchanged.
func (c *chunkParser) normalize(owner *ClassType, fieldName string, value int64) int64 {
	if owner == nil || fieldName == "" || c.info == nil {
		return value
	}
	if owner.temporal == nil {
		owner.temporal = buildTemporalKinds(owner)
	}
	switch owner.temporal[fieldName] {
	case temporalTimestamp:
		return c.info.AsEpochNanos(value)
	case temporalTimespan:
		return c.info.AsDurationNanos(value)
	default:
		return value
	}
}

// buildTemporalKinds maps the names of the tick-based fields of ct to their
// normalisation kind. Fields that need no conversion are absent from the map.
func buildTemporalKinds(ct *ClassType) map[string]int {
	kinds := make(map[string]int)
	for _, f := range ct.Fields {
		for _, a := range f.Annotations {
			if a.Type == nil {
				continue
			}
			// An absent value means the annotation's default unit, which is
			// TICKS for both annotations.
			if a.Value != "" && a.Value != ticksUnit {
				continue
			}
			if a.Type.Name == timestampAnnotation {
				kinds[f.Name] = temporalTimestamp
				break
			}
			if a.Type.Name == timespanAnnotation {
				kinds[f.Name] = temporalTimespan
				break
			}
		}
	}
	return kinds
}
