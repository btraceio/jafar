package jfr

import (
	"os"
	"testing"
)

func BenchmarkParseRecording(b *testing.B) {
	data, err := os.ReadFile(tckRecording)
	if err != nil {
		b.Skipf("fixture is not available: %v", err)
	}
	p := NewParser(data)
	b.SetBytes(int64(len(data)))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		n := 0
		if err := p.Parse(func(*Event) error {
			n++
			return nil
		}); err != nil {
			b.Fatal(err)
		}
	}
}

// BenchmarkParseRecordingSparse measures the common filtering pattern: decode
// one event type and read a couple of fields from it.
func BenchmarkParseRecordingSparse(b *testing.B) {
	data, err := os.ReadFile(tckRecording)
	if err != nil {
		b.Skipf("fixture is not available: %v", err)
	}
	p := NewParser(data)
	opts := Options{TypeFilter: func(c *ClassType) bool { return c.Name == "datadog.ExecutionSample" }}
	b.SetBytes(int64(len(data)))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		sum := int64(0)
		if err := p.ParseWith(opts, func(e *Event) error {
			v, _ := GetInt(e.Values, "startTime")
			sum += v
			return nil
		}); err != nil {
			b.Fatal(err)
		}
	}
}
