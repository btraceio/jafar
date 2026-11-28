# Real pprof fixtures

Profiles written by real producers, not by `MinimalPprofBuilder` or jfr2pprof, so the compare's
assumptions about pprof are checked against what tools actually write. Tests:
`pprof-shell/src/test/java/io/jafar/pprof/shell/RealPprofProfilesTest.java` (the reader) and
`jafar-shell/src/test/java/io/jafar/shell/unified/RealProfilesCompareTest.java` (the comparison).

Made on 2026-11-27, Linux x86_64, as root (so async-profiler had perf events: kernel frames
appear). Everything was built and run from `/tmp/realprof`, with `JAVA_TOOL_OPTIONS` unset, so no
local path or proxy setting ends up in a file: the Go mapping names `/tmp/realprof/realprof`, the
JFR's command line `/tmp/realprof/...`.

## Go: `runtime/pprof`, go1.24.7 linux/amd64

Source: [`go/`](go/) (`main.go`, `work/work.go`): a CPU loop (`(*Hasher).Sum` inlined into the
closure `Burn.func1` inlined into `Burn`), a generic `Map`, a JSON round trip that allocates, and
eight goroutines contending for one mutex.

```bash
mkdir -p /tmp/realprof/out
(cd go && go build -trimpath -o /tmp/realprof/realprof .)
cd /tmp/realprof
./realprof -out out -seconds 2                   # go-cpu, go-heap, go-mutex, go-block
./realprof -out out -prefix go-b- -seconds 2     # a second run; only its CPU profile is kept
cp out/go-cpu.pb.gz out/go-heap.pb.gz out/go-mutex.pb.gz out/go-block.pb.gz <here>
cp out/go-b-cpu.pb.gz <here>/go-cpu-b.pb.gz
```

`realprof` sets `runtime.SetMutexProfileFraction(5)` and `runtime.SetBlockProfileRate(1)`.

## async-profiler 4.1, linux-x64

`async-profiler-4.1-linux-x64.tar.gz` from the GitHub release `v4.1` (sha256
`3b13a38a0063f6970d985a379ddaed91bcf37e239a1ea461d09eacf629f3dde1`), profiling
[`java/RealProf.java`](java/RealProf.java) on Ubuntu OpenJDK 21.0.12 (`21.0.12.1+1-1-24.04.4`).
The agent writes JFR (or OTLP); pprof comes from the bundled converter, `jfrconv`. A `.pb.gz`
output name gives gzip; `-o pprof` gives uncompressed protobuf.

```bash
AP=/tmp/realprof/async-profiler-4.1-linux-x64
javac -d /tmp/realprof/classes java/RealProf.java
cd /tmp/realprof
for ev in cpu wall alloc; do
  java -agentpath:$AP/lib/libasyncProfiler.so=start,event=$ev,file=out/ap-$ev.jfr \
       -cp classes RealProf 2
done
cd out
$AP/bin/jfrconv --cpu ap-cpu.jfr ap-cpu.pb.gz
$AP/bin/jfrconv -o pprof --cpu ap-cpu.jfr ap-cpu.pprof
$AP/bin/jfrconv --wall ap-wall.jfr ap-wall.pb.gz
$AP/bin/jfrconv --alloc ap-alloc.jfr ap-alloc.pb.gz
$AP/bin/jfrconv --alloc --total ap-alloc.jfr ap-alloc-total.pb.gz
cp ap-cpu.jfr ap-wall.jfr ap-*.pb.gz ap-cpu.pprof <here>
```

## The files

Read with `github.com/google/pprof/profile` and `go tool pprof -raw`, independently of this
repository's parser. None has `default_sample_type`, `drop_frames` or `keep_frames`.

| File | Bytes | sha256 | Sample types | Period | Duration |
|---|---|---|---|---|---|
| `go-cpu.pb.gz` | 5,624 | `728aca1f6237e562378d807aafc95aad352658502333b9af67fa0855e59d147c` | `samples/count`, `cpu/nanoseconds` (202 samples, 2.02e9 ns) | `cpu/nanoseconds` 10,000,000 | 2.111 s |
| `go-cpu-b.pb.gz` | 6,330 | `c94852ff7d4535a40316facf544fa469aa76c7627a550f77c1cc7d4488934d3d` | the same (206 samples) | the same | 2.212 s |
| `go-heap.pb.gz` | 2,356 | `0a3831046ea03cbebbb6f887ba2f2c2ff404d5709ae4fbc67adc4e0a91016dd5` | `alloc_objects/count`, `alloc_space/bytes`, `inuse_objects/count`, `inuse_space/bytes` | `space/bytes` 524,288 | 0 |
| `go-mutex.pb.gz` | 450 | `4175c715cdd24a60d9f83126910667308f0778d800ecd62c005da02ecf14c1f9` | `contentions/count` (1,430: 286 sampled x 5), `delay/nanoseconds` | `contentions/count` 1 | 0 |
| `go-block.pb.gz` | 980 | `286050944a99003795f501ead25683e4b1d66a7fabd8fb9b7a6dcf8e97856180` | `contentions/count`, `delay/nanoseconds` | `contentions/count` 1 | 0 |
| `ap-cpu.jfr` | 30,252 | `2144a1a1e629c9e3e7a290f196228f5271e19148396d6a4314caadab160b0c08` | JFR: 215 `jdk.ExecutionSample` | | |
| `ap-wall.jfr` | 23,562 | `9d2c7f1f77ee8ba49972ae6854f15812a481f3712a9074aaaca61af7917b6a36` | JFR: `profiler.WallClockSample` | | |
| `ap-cpu.pb.gz` | 5,201 | `111564dc38fc05d98d8c0ec48e41647b2ba71bd940264ebe9981574da6e95a2b` | `cpu/count` (215) | none (0) | 2.031 s |
| `ap-cpu.pprof` | 11,322 | `0e529cba9ace3cfc64d140660335312a3516644668e63ccb09696cc5a0bf9db4` | the same, uncompressed | | |
| `ap-wall.pb.gz` | 3,201 | `4820edabf3f37a39930975de437dc3701ce259e0282c00f8b2ca8e115b33d0c5` | `cpu/count` (945) - a wall profile, named `cpu` | none | 2.023 s |
| `ap-alloc.pb.gz` | 1,515 | `be8a9bb21187ba3f9e44c798776aa1bfb63e9b80129481ca050c971e543d4377` | `allocations/count` (1,430) | none | 2.027 s |
| `ap-alloc-total.pb.gz` | 1,543 | `6177a049e4ea24d2a6c4111ca5e9fb273d5999c51b2cacca26b019aece6ce5a6` | `allocations/bytes` (749,730,410: about 512 KiB per sample) | none | 2.027 s |

Name shapes:

- **Go:** `pkg/path.Func` (`encoding/json.Marshal`, which has a Java internal name's shape),
  `pkg.(*T).Method`, closures `Burn.func1`, generics `Map[go.shape.int,go.shape.*uint8]`, assembly
  symbols (`aeshashbody`). Filenames are module-relative with `-trimpath`
  (`example.com/realprof/work/work.go`, `encoding/json/encode.go`), with `start_line`. Inlined
  calls are one location with several lines, innermost first. No sample labels except the heap
  profile's numeric `bytes`. Mappings: the binary, `[vdso]`, `[vsyscall]`.
- **async-profiler (`jfrconv`):** Java classes slashed (`java/lang/String.repeat`), unless
  `--dot`; a class in the default package has no slash (`RealProf.main`); hidden classes keep their
  address after a dot (`RealProf$$Lambda.0x00007f...applyAsInt`, `LambdaForm$MH.0x...invoke`);
  `<init>` and `lambda$burn$0` appear; native and kernel frames are bare symbols
  (`Compile::Compile`, `start_thread`, `__pthread_mutex_lock@GLIBC_2.2.5`,
  `do_syscall_64`, `asm_exc_page_fault`). In an allocation profile the leaf frame is the allocated
  class, dotted (`java.lang.String`, `byte[]`). No filenames and no `start_line`; a Java frame's
  `Line.line` is its line number (`RealProf.allocate` at 46), with or without `--lines`, and the
  name never carries it. One line per location (no location has two), one
  mapping named `async-profiler`, and the comment `Produced by async-profiler`. The JFR, by contrast, gives a C++ frame its library
  as the class (`libjvm.so`, `libc.so.6`); compare keys `Native`, `C++` and `Kernel` frames by the
  symbol alone, so both files key that frame `Compile::Compile`.
