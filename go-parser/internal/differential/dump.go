// Package differential renders the Go side of the Java/Go differential
// harness: a canonical, byte-stable dump of every event of a JFR recording.
//
// The line format mirrors test-support's JfrCanonicalDump (side=java) exactly,
// so the two sides can be compared line by line:
//
//	# jafar-dump v1 side=go fixture-sha256=<hex> events=<n>
//	<event type> <dotted.path>=<value> ...   (one line per event, sorted)
//
// Event values come from jfr.ResolveDeepMap and are flattened to sorted dotted
// paths: nested maps recurse, arrays are indexed .0 .1 …, []byte renders as
// b<len>:<hex>, float32/float64 as f:<16 hex digits of the IEEE-754 double bit
// pattern>, and \n and \r in string values are escaped so an event never spans
// lines. No chunk identity appears anywhere: a re-chunked recording must dump
// identically.
//
// The package lives under internal/ on purpose. It is test tooling for the
// differential gate, not part of the parser's public API, and it keeps the
// Go-side harness out of go-parser/jfr so the Java/Go parity rule (AGENTS.md)
// sees no one-sided production change.
package differential

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"math"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"

	jfr "github.com/btraceio/jafar/go-parser/jfr"
)

// Dump parses the JFR recording at path and writes its canonical dump to w.
//
// On success the output starts with the header line
// "# jafar-dump v1 side=go fixture-sha256=<hex> events=<n>", followed by one
// line per event, sorted in byte order; events counts the event lines.
//
// On any error — the recording cannot be read, or does not parse — Dump
// writes the header line "# jafar-dump v1 side=go error=<msg>" and nothing
// else, and returns the error. A consumer can therefore never mistake a
// failed dump for an empty one.
func Dump(path string, w io.Writer) error {
	data, err := os.ReadFile(path)
	if err != nil {
		writeErrorHeader(w, err)
		return err
	}
	lines, err := collect(jfr.NewParser(data))
	if err != nil {
		writeErrorHeader(w, err)
		return err
	}
	sum := sha256.Sum256(data)
	fmt.Fprintf(w, "# jafar-dump v1 side=go fixture-sha256=%x events=%d\n", sum, len(lines))
	for _, line := range lines {
		io.WriteString(w, line)
		io.WriteString(w, "\n")
	}
	return nil
}

// writeErrorHeader emits the error form of the dump header. The message is
// escaped the same way event values are, so a multi-line error never breaks
// the one-header-line contract.
func writeErrorHeader(w io.Writer, err error) {
	fmt.Fprintf(w, "# jafar-dump v1 side=go error=%s\n", escape(err.Error()))
}

// collector gathers the event lines of one dump run. Its single strings.Builder
// and flattened-entry slice are reused for every event, so a run allocates one
// line per event instead of a builder and a slice each.
type collector struct {
	// mu guards lines. The parser hands events to the handler on the calling
	// goroutine today, but chunked parsing is exactly where a concurrent
	// producer showed up in the Java probe (kb/probe-pitfalls item 4), so the
	// collection is made safe rather than relying on that staying true.
	mu sync.Mutex
	// lines holds one finished line per event.
	lines []string
	// sb and flat are the reused per-event builder and scratch slice.
	sb   strings.Builder
	flat []string
}

// collect parses the recording with p and renders one line per event, sorted
// in byte order.
//
// Parsing is strict: FailOnError turns recoverable decoding problems into
// failures, mirroring the Java parser, which fails on them too. A malformed
// node-pool fixture therefore reports error= on both sides of the
// differential instead of a Go dump of half its events next to a Java error
// header.
func collect(p *jfr.Parser) ([]string, error) {
	c := &collector{}
	if err := p.ParseWith(jfr.Options{OnError: jfr.FailOnError}, c.event); err != nil {
		return nil, err
	}
	sort.Strings(c.lines)
	return c.lines, nil
}

// event renders one event into the reused builder and appends the finished
// line to the collection.
func (c *collector) event(e *jfr.Event) error {
	vals := jfr.ResolveDeepMap(e.Values)
	c.sb.Reset()
	c.sb.WriteString(e.Type.Name)
	c.flat = c.flat[:0]
	c.flatten(vals, "")
	sort.Strings(c.flat)
	for _, kv := range c.flat {
		c.sb.WriteByte(' ')
		c.sb.WriteString(kv)
	}
	c.mu.Lock()
	c.lines = append(c.lines, c.sb.String())
	c.mu.Unlock()
	return nil
}

// flatten appends a "dotted.path=value" entry for every scalar reachable from
// m, recursing into nested maps. The entries are sorted by the caller, so map
// iteration order never leaks into the dump.
func (c *collector) flatten(m map[string]any, prefix string) {
	for name, v := range m {
		key := name
		if prefix != "" {
			key = prefix + "." + name
		}
		c.value(key, v)
	}
}

// value appends the entry for v at the given dotted path, indexing arrays and
// slices element-wise as .N.
func (c *collector) value(key string, v any) {
	switch t := v.(type) {
	case map[string]any:
		c.flatten(t, key)
	case []byte:
		c.flat = append(c.flat, key+"="+bytesStr(t))
	case []any:
		for i, el := range t {
			c.value(key+"."+strconv.Itoa(i), el)
		}
	case []int64:
		for i, el := range t {
			c.flat = append(c.flat, key+"."+strconv.Itoa(i)+"="+strconv.FormatInt(el, 10))
		}
	case []float32:
		for i, el := range t {
			c.flat = append(c.flat, key+"."+strconv.Itoa(i)+"="+floatStr(float64(el)))
		}
	case []float64:
		for i, el := range t {
			c.flat = append(c.flat, key+"."+strconv.Itoa(i)+"="+floatStr(el))
		}
	case []int16:
		for i, el := range t {
			c.flat = append(c.flat, key+"."+strconv.Itoa(i)+"="+strconv.FormatInt(int64(el), 10))
		}
	case []uint16:
		for i, el := range t {
			c.flat = append(c.flat, key+"."+strconv.Itoa(i)+"="+strconv.FormatUint(uint64(el), 10))
		}
	case float32:
		c.flat = append(c.flat, key+"="+floatStr(float64(t)))
	case float64:
		c.flat = append(c.flat, key+"="+floatStr(t))
	case nil:
		c.flat = append(c.flat, key+"=null")
	case string:
		c.flat = append(c.flat, key+"="+escape(t))
	case bool:
		c.flat = append(c.flat, key+"="+strconv.FormatBool(t))
	case int8:
		c.flat = append(c.flat, key+"="+strconv.FormatInt(int64(t), 10))
	case int16:
		c.flat = append(c.flat, key+"="+strconv.FormatInt(int64(t), 10))
	case uint16:
		c.flat = append(c.flat, key+"="+strconv.FormatUint(uint64(t), 10))
	case int64:
		c.flat = append(c.flat, key+"="+strconv.FormatInt(t, 10))
	default:
		c.flat = append(c.flat, key+"="+escape(fmt.Sprintf("%v", t)))
	}
}

// floatStr renders a float as f:<16 hex digits of the IEEE-754 double bit
// pattern>, zero padded, mirroring the Java side's Double.doubleToLongBits
// rendering. The differential compares floats by bit pattern, not by decimal
// text, so 0.1 never round-trips through a formatter that disagrees between
// the two sides.
func floatStr(v float64) string {
	return "f:" + fmt.Sprintf("%016x", math.Float64bits(v))
}

// bytesStr renders a byte array as b<len>:<hex>.
func bytesStr(b []byte) string {
	return "b" + strconv.Itoa(len(b)) + ":" + hex.EncodeToString(b)
}

// escape renders \n and \r as their two-character forms so a string value
// never breaks the one-line-per-event contract.
func escape(s string) string {
	if !strings.ContainsAny(s, "\n\r") {
		return s
	}
	s = strings.ReplaceAll(s, "\n", "\\n")
	return strings.ReplaceAll(s, "\r", "\\r")
}
