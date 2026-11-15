// Command jfrdump writes the Go-side canonical dump of a JFR recording, for the
// Java/Go differential gate (parser-core's differentialTest drives it; the
// Java half is JfrCanonicalDump).
//
// Usage:
//
//	jfrdump [-o output] <recording>
//
// The dump goes to standard output unless -o names a file. A recording that
// cannot be read or parsed still produces a header line carrying error= in
// the output, and the command exits non-zero, so a broken dump can never
// pass silently as a stale or empty file.
package main

import (
	"flag"
	"fmt"
	"io"
	"os"

	"github.com/btraceio/jafar/go-parser/internal/differential"
)

func main() {
	out := flag.String("o", "", "output file (default: standard output)")
	flag.Usage = usage
	flag.Parse()
	if flag.NArg() != 1 {
		flag.Usage()
		os.Exit(2)
	}
	if err := run(flag.Arg(0), *out); err != nil {
		fmt.Fprintln(os.Stderr, "jfrdump:", err)
		os.Exit(1)
	}
}

// run performs the dump, writing to the named file or to standard output, and
// takes care to close and flush an output file even when the dump fails.
func run(recording, out string) error {
	var w io.Writer = os.Stdout
	if out == "" {
		return differential.Dump(recording, w)
	}
	f, err := os.Create(out)
	if err != nil {
		return err
	}
	err = differential.Dump(recording, f)
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	return err
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: jfrdump [-o output] <recording>")
	flag.PrintDefaults()
}
