// Package work is the profiled code: a CPU-bound loop with an inlinable helper, a method on a
// pointer receiver, a closure, a generic function and an allocating JSON round trip, so the
// profiles carry each Go function-name shape.
package work

import (
	"encoding/json"
	"strings"
	"sync"
)

// Hasher mixes integers; Sum has a pointer receiver: work.(*Hasher).Sum.
type Hasher struct{ state uint64 }

// mix is small enough for the compiler to inline into Sum: an inlined frame.
func mix(h, v uint64) uint64 {
	h ^= v
	h *= 0x100000001b3
	return h
}

// Sum burns CPU.
func (h *Hasher) Sum(n int) uint64 {
	for i := 0; i < n; i++ {
		h.state = mix(h.state, uint64(i))
	}
	return h.state
}

// Burn runs Sum inside a closure: work.Burn.func1.
func Burn(rounds int) uint64 {
	var total uint64
	step := func(n int) {
		h := &Hasher{state: 14695981039346656037}
		total += h.Sum(n)
	}
	for r := 0; r < rounds; r++ {
		step(1 << 20)
	}
	return total
}

// Map is generic: work.Map[...].
func Map[T, U any](in []T, f func(T) U) []U {
	out := make([]U, 0, len(in))
	for _, v := range in {
		out = append(out, f(v))
	}
	return out
}

// Record is what Allocate marshals.
type Record struct {
	Name  string
	Tags  []string
	Value int
}

// Allocate allocates: records, strings and JSON (encoding/json.Marshal, a slashed Go name).
func Allocate(n int) int {
	recs := Map(make([]int, n), func(i int) *Record {
		return &Record{Name: strings.Repeat("x", 64), Tags: []string{"a", "b", "c"}, Value: i}
	})
	size := 0
	for _, r := range recs {
		b, err := json.Marshal(r)
		if err != nil {
			panic(err)
		}
		var back Record
		if err := json.Unmarshal(b, &back); err != nil {
			panic(err)
		}
		size += len(b)
	}
	return size
}

// Contend makes goroutines fight over one mutex, for the mutex and block profiles.
func Contend(goroutines, iterations int) int {
	var mu sync.Mutex
	var wg sync.WaitGroup
	counter := 0
	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < iterations; i++ {
				mu.Lock()
				h := &Hasher{}
				counter += int(h.Sum(200) & 1)
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	return counter
}
