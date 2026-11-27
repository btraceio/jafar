// Command realprof writes the real Go pprof fixtures: a CPU, heap, mutex and block profile of
// package work. See ../README.md for the exact commands.
package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"runtime/pprof"
	"time"

	"example.com/realprof/work"
)

func main() {
	dir := flag.String("out", ".", "directory to write the profiles into")
	seconds := flag.Float64("seconds", 2, "how long the CPU phase runs")
	prefix := flag.String("prefix", "go-", "file name prefix")
	mutexFraction := flag.Int("mutexfraction", 5, "runtime.SetMutexProfileFraction")
	flag.Parse()

	runtime.SetMutexProfileFraction(*mutexFraction)
	runtime.SetBlockProfileRate(1)

	cpu, err := os.Create(filepath.Join(*dir, *prefix+"cpu.pb.gz"))
	check(err)
	check(pprof.StartCPUProfile(cpu))
	deadline := time.Now().Add(time.Duration(*seconds * float64(time.Second)))
	var sum uint64
	size := 0
	for time.Now().Before(deadline) {
		sum += work.Burn(8)
		size += work.Allocate(2000)
	}
	pprof.StopCPUProfile()
	check(cpu.Close())

	work.Contend(8, 2000)

	runtime.GC()
	for _, name := range []string{"heap", "mutex", "block"} {
		f, err := os.Create(filepath.Join(*dir, *prefix+name+".pb.gz"))
		check(err)
		check(pprof.Lookup(name).WriteTo(f, 0))
		check(f.Close())
	}
	fmt.Println(sum, size)
}

func check(err error) {
	if err != nil {
		panic(err)
	}
}
