package jfr

import (
	"fmt"
	"math"
	"time"
)

// chunkHeaderSize is the fixed size, in bytes, of a JFR chunk header:
// magic(4) + major(2) + minor(2) + 7 * int64 + features(4).
const chunkHeaderSize = 68

// magicBE is "FLR\0" read as a big-endian 32 bit integer.
const magicBE int32 = 0x464C5200

// ChunkHeader describes a single chunk of a JFR recording.
//
// Offsets are relative to the start of the chunk, matching the on-disk
// encoding.
type ChunkHeader struct {
	// Order is the 1-based index of the chunk within the recording.
	Order int
	// Offset is the byte offset of the chunk within the recording.
	Offset int64
	// Major and Minor are the JFR format version.
	Major int16
	Minor int16
	// Size is the total size of the chunk in bytes, header included.
	Size int64
	// ConstantPoolOffset is the chunk-relative offset of the first checkpoint event.
	ConstantPoolOffset int64
	// MetadataOffset is the chunk-relative offset of the metadata event.
	MetadataOffset int64
	// StartNanos is the chunk start time in nanoseconds since the epoch.
	StartNanos int64
	// Duration is the chunk duration in ticks.
	Duration int64
	// StartTicks is the chunk start time expressed in ticks.
	StartTicks int64
	// Frequency is the number of ticks per second.
	Frequency int64
	// Features carries the chunk feature flags. Bit 0 selects the compressed
	// (varint) integer encoding, which every JVM-written recording uses.
	Features int32
}

// featureCompressedInts is the chunk feature flag selecting varint encoded
// integers; see jdk.jfr.internal.consumer.ChunkHeader.
const featureCompressedInts int32 = 1

// CompressedInts reports whether the chunk encodes integers as varints.
func (h *ChunkHeader) CompressedInts() bool { return h.Features&featureCompressedInts != 0 }

func (h *ChunkHeader) String() string {
	return fmt.Sprintf("ChunkHeader{order=%d, offset=%d, version=%d.%d, size=%d, cpOffset=%d, metaOffset=%d, startNanos=%d, duration=%d, startTicks=%d, frequency=%d, compressedInts=%t}",
		h.Order, h.Offset, h.Major, h.Minor, h.Size, h.ConstantPoolOffset, h.MetadataOffset,
		h.StartNanos, h.Duration, h.StartTicks, h.Frequency, h.CompressedInts())
}

// readChunkHeader decodes a chunk header at the reader's current position.
func readChunkHeader(r *reader, index int, offset int64) (*ChunkHeader, error) {
	h := &ChunkHeader{Order: index, Offset: offset}
	magic := r.readInt32()
	if r.err != nil {
		return nil, r.err
	}
	if magic != magicBE {
		return nil, fmt.Errorf("invalid JFR magic number: 0x%08x at offset %d", uint32(magic), offset)
	}
	h.Major = r.readInt16()
	h.Minor = r.readInt16()
	h.Size = r.readInt64()
	h.ConstantPoolOffset = r.readInt64()
	h.MetadataOffset = r.readInt64()
	h.StartNanos = r.readInt64()
	h.Duration = r.readInt64()
	h.StartTicks = r.readInt64()
	h.Frequency = r.readInt64()
	h.Features = r.readInt32()
	if r.err != nil {
		return nil, r.err
	}
	if h.Size < chunkHeaderSize {
		return nil, fmt.Errorf("chunk %d: size %d is smaller than the chunk header (%d bytes)", index, h.Size, chunkHeaderSize)
	}
	if h.Frequency <= 0 {
		return nil, fmt.Errorf("chunk %d: invalid tick frequency %d", index, h.Frequency)
	}
	return h, nil
}

// ChunkInfo exposes the timing information of a chunk and converts the
// tick-based values found in event fields to wall-clock values.
type ChunkInfo struct {
	// Header is the raw chunk header.
	Header *ChunkHeader

	startNanos   int64
	startTicks   int64
	nanosPerTick float64
}

func newChunkInfo(h *ChunkHeader) *ChunkInfo {
	return &ChunkInfo{
		Header:       h,
		startNanos:   h.StartNanos,
		startTicks:   h.StartTicks,
		nanosPerTick: 1e9 / float64(h.Frequency),
	}
}

// ID returns the 1-based index of the chunk within the recording.
func (c *ChunkInfo) ID() int { return c.Header.Order }

// StartTime returns the chunk start time.
func (c *ChunkInfo) StartTime() time.Time {
	return time.Unix(0, c.startNanos)
}

// Duration returns the chunk duration.
func (c *ChunkInfo) Duration() time.Duration {
	return time.Duration(c.AsDurationNanos(c.Header.Duration))
}

// Size returns the chunk size in bytes.
func (c *ChunkInfo) Size() int64 { return c.Header.Size }

// AsDurationNanos converts a tick count to a duration in nanoseconds.
func (c *ChunkInfo) AsDurationNanos(ticks int64) int64 {
	return roundHalfUp(float64(ticks) * c.nanosPerTick)
}

// AsDuration converts a tick count to a duration.
func (c *ChunkInfo) AsDuration(ticks int64) time.Duration {
	return time.Duration(c.AsDurationNanos(ticks))
}

// AsEpochNanos converts a tick timestamp to nanoseconds since the epoch.
func (c *ChunkInfo) AsEpochNanos(ticks int64) int64 {
	return c.startNanos + roundHalfUp(float64(ticks-c.startTicks)*c.nanosPerTick)
}

// AsTime converts a tick timestamp to a wall-clock time.
func (c *ChunkInfo) AsTime(ticks int64) time.Time {
	return time.Unix(0, c.AsEpochNanos(ticks))
}

// roundHalfUp mirrors java.lang.Math.round: round half towards positive
// infinity. Go's math.Round rounds half away from zero, which differs for
// negative half-way values.
func roundHalfUp(v float64) int64 {
	return int64(math.Floor(v + 0.5))
}
