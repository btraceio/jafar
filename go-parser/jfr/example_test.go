package jfr_test

import (
	"fmt"
	"log"

	"github.com/btraceio/jafar/go-parser/jfr"
)

// Decoding every event of a recording.
func Example() {
	parser, err := jfr.Open("recording.jfr")
	if err != nil {
		log.Fatal(err)
	}
	counts := map[string]int{}
	if err := parser.Parse(func(e *jfr.Event) error {
		counts[e.Type.Name]++
		return nil
	}); err != nil {
		log.Fatal(err)
	}
	fmt.Println(counts["jdk.ExecutionSample"])
}

// Reading a few fields of one event type. The type filter keeps the parser from
// decoding events that are thrown away anyway, and Get walks through the thread
// and stack trace constant pools.
func Example_filtering() {
	parser, err := jfr.Open("recording.jfr")
	if err != nil {
		log.Fatal(err)
	}
	opts := jfr.Options{
		TypeFilter: func(t *jfr.ClassType) bool { return t.Name == "jdk.ExecutionSample" },
	}
	err = parser.ParseWith(opts, func(e *jfr.Event) error {
		thread, _ := jfr.GetString(e.Values, "sampledThread", "javaName")
		method, _ := jfr.GetString(e.Values, "stackTrace", "frames", 0, "method", "name", "string")
		startTime, _ := jfr.GetInt(e.Values, "startTime") // nanoseconds since the epoch
		fmt.Println(startTime, thread, method)
		return nil
	})
	if err != nil {
		log.Fatal(err)
	}
}

// Inspecting the type universe of every chunk without decoding events.
func Example_metadata() {
	parser, err := jfr.Open("recording.jfr")
	if err != nil {
		log.Fatal(err)
	}
	opts := jfr.Options{
		OnChunkStart: func(chunk *jfr.ChunkInfo, md *jfr.Metadata) error {
			for _, t := range md.EventTypes() {
				fmt.Printf("chunk %d: %s (%d fields)\n", chunk.ID(), t.Name, len(t.Fields))
			}
			return jfr.ErrStop
		},
		// Stop before any event is decoded.
		TypeFilter: func(*jfr.ClassType) bool { return false },
	}
	if err := parser.ParseWith(opts, nil); err != nil {
		log.Fatal(err)
	}
}
