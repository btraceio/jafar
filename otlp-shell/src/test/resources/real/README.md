# Real OTLP profiles fixtures

Profiles written by a real OTLP producer, async-profiler, not by `MinimalOtlpBuilder`, so the OTLP
reader and the compare are checked against what a tool actually writes. Tests:
`otlp-shell/src/test/java/io/jafar/otlp/shell/RealOtlpProfilesTest.java` (parser, OtlpPath and the
compare reader) and `jafar-shell/src/test/java/io/jafar/shell/unified/RealOtlpCompareTest.java`
(JFR, pprof and OTLP of the same run, through the compare).

Made on 2026-11-27, Linux x86_64, as root (so async-profiler had perf events: kernel frames
appear), profiling [`RealProf.java`](../../../../../pprof-shell/src/test/resources/real/java/RealProf.java)
(the same program as the pprof fixtures) on Ubuntu OpenJDK 21.0.12 (`21.0.12.1+1-1-24.04.4`).
Everything ran from `/tmp/realprof` with `JAVA_TOOL_OPTIONS` unset, so no local path or proxy
setting ends up in a file; the JFR's command line names `/tmp/realprof/...`.

No other OTLP producer was tried: the OpenTelemetry eBPF profiler needs a privileged host kernel,
which this container does not offer.

## async-profiler 4.6 (opentelemetry-proto v1.10.0 layout)

`async-profiler-4.6-linux-x64.tar.gz` from the GitHub release `v4.6` (the latest release, 2026-10-19;
sha256 `1e79ed54d8952889a2e74f993d22ee925e3322460d4f37a2ed95bfb88585da36`). The agent writes
OTLP with the `otlp` option; the bundled `jfrconv` converts a JFR to OTLP with `-o otlp`. Only JFR
output takes several events, so every OTLP file holds one profile.

```bash
AP=/tmp/realprof/async-profiler-4.6-linux-x64
javac -d /tmp/realprof/classes RealProf.java
cd /tmp/realprof
for ev in cpu wall alloc; do
  java -agentpath:$AP/lib/libasyncProfiler.so=start,event=$ev,otlp,file=out/ap-$ev.otlp \
       -cp classes RealProf 2
done
java -agentpath:$AP/lib/libasyncProfiler.so=start,event=cpu,otlp,file=out/ap-cpu-b.otlp \
     -cp classes RealProf 2                                   # a second CPU run
java -agentpath:$AP/lib/libasyncProfiler.so=start,event=cpu,file=out/ap-cpu.jfr \
     -cp classes RealProf 2                                   # one run, written as JFR ...
java -agentpath:$AP/lib/libasyncProfiler.so=start,event=cpu,alloc,wall,file=out/ap-multi.jfr \
     -cp classes RealProf 2
cd out
$AP/bin/jfrconv -o otlp ap-cpu.jfr conv-cpu.otlp             # ... and converted to OTLP
$AP/bin/jfrconv --cpu -o pprof ap-cpu.jfr conv-cpu.pprof      # ... and to pprof
$AP/bin/jfrconv -o otlp ap-multi.jfr conv-multi-cpu.otlp      # the default is --cpu
$AP/bin/jfrconv --wall -o otlp ap-multi.jfr conv-multi-wall.otlp
```

## async-profiler 4.1 (opentelemetry-proto v1.7.0 layout)

`async-profiler-4.1-linux-x64.tar.gz` (sha256
`3b13a38a0063f6970d985a379ddaed91bcf37e239a1ea461d09eacf629f3dde1`), as for the pprof fixtures:

```bash
java -agentpath:/tmp/realprof/async-profiler-4.1-linux-x64/lib/libasyncProfiler.so=start,event=cpu,otlp,file=out/ap41-cpu.otlp \
     -cp classes RealProf 2
```

The reader refuses it: it predates the layout the parser reads (see below).

## The files

Decoded field by field against `profiles.proto` of the opentelemetry-proto tags named, with a
script independent of this repository's parser.

| File | Bytes | sha256 | Sample type | Period | Samples | Values |
|---|---|---|---|---|---|---|
| `ap-cpu.otlp` | 10,411 | `023e5f2c7f56808577c344ec2a859ebb5cf668f25ba7f091ea17deb3965e4ae6` | `perf_events/count` | `perf_events/ns` 10,000,000 | 61 records, 217 samples | one per record |
| `ap-cpu-b.otlp` | 6,501 | `2e9fe799a5ed6a059f55876b04b849f1092d5295d9680db0e0a9bba3e12a4563` | the same | the same | 44 records, 203 samples | one per record |
| `ap-wall.otlp` | 7,870 | `ff81225286ba3eec403bef6a44c2162df34d0facf5ea5767abbe5772fa6b9ee9` | `wall/count` | `wall/ns` 50,000,000 | 36 records, 903 samples | one per record |
| `ap-alloc.otlp` | 3,144 | `8146724abbc7afd4dcaa42b591425f020664cf1849577724f9b6dbfcc91341a7` | `object_sampler/count` | `object_sampler/bytes` 524,287 | 9 records, 1,405 sampled allocations | one per record |
| `ap-cpu.jfr` | 34,698 | `70046c1e6ef2ac50c34a4e157b943b619b937257836bc4818c5239077ec30362` | JFR `jdk.ExecutionSample` | | | |
| `conv-cpu.otlp` | 15,342 | `8dd3063ea31fdbc1d4bf3c3dbd6a239a596716d20fc130d42b5431b262178e5c` | `cpu/count` | `cpu/nanoseconds` 10,000,000 | 56 records, 216 timestamps | **none**: timestamps only |
| `conv-cpu.pprof` | 13,502 | `2b8b47f179f4af94a2031adbe0bd72e5ddd44e30be04a8379605239143f2ec2b` | pprof `cpu/count` | | | |
| `conv-multi-cpu.otlp` | 21,656 | `f4748b3ac7d73ed800c6e4db362ac9ca0092a36df79e4891e1da56755902c442` | `cpu/count` | `cpu/nanoseconds` 10,000,000 | 85 records, 513 timestamps, 1,161 samples | **one per timestamp**, up to 127 per record |
| `conv-multi-wall.otlp` | 12,657 | `aebc8906644158448684a8867300fafd4da5f8646419c077b1416b96aaf798b8` | `cpu/count` (a wall profile, named `cpu`) | `cpu/nanoseconds` 50,000,000 | 47 records, 297 timestamps, 945 samples | one per timestamp |
| `ap41-cpu.otlp` | 7,574 | `07d56870872c51a21293e8817eff50a423f3791f5204c79d9d4997b1ead61b29` | v1.7.0: two sample types, `count` and `ns` | none | | two per record |

What they show:

- **Layout.** 4.6 writes the v1.10.0 layout (`Sample.stack_index = 1`, `attribute_indices = 2`,
  `values = 4`, `timestamps_unix_nano = 5`; `Profile.sample_type` singular), the one
  `OtlpReader` reads. 4.1 writes v1.7.0 (`Profile.sample_type` repeated, `location_indices = 3`,
  `Sample.locations_start_index`/`locations_length`/`value = 1, 2, 3`), whose field numbers
  overlap with different meanings. v1.8.0 and v1.9.0 have yet another `Sample` (`values = 2`,
  `attribute_indices = 3`); no producer of those was available.
- **Index base.** Every dictionary table starts with its zero value: `string_table[0] = ""`, an
  empty mapping, location, function, link, attribute and stack at index 0, as v1.10.0 requires
  ("MUST be the zero value ... and present"). Indices are absolute: stack 1 is the second entry.
  4.1 does not write the empty string first (`string_table[0]` is the engine name), which v1.7.0
  forbids too.
- **Values.** The agent writes one value per sample record, a count of samples aggregated over
  equal stacks, and no timestamps. `jfrconv -o otlp` writes one record per stack and thread with a
  timestamp per sample: with no values at all (each timestamp counts 1, as v1.10.0 says), or, for a
  multi-event JFR, one value per timestamp (a JFR event's sample count, up to 13). Reading only
  `values[0]`, as OtlpPath did, gave every `conv-cpu.otlp` record the value 0 and a
  `conv-multi-cpu.otlp` record its first timestamp's value only.
- **Time.** `time_unix_nano` and `duration_nano` (about 2.02 s) are set in every 4.6 file; per-sample
  timestamps only in the `jfrconv` files.
- **Names.** Only `Function.name` is set: no system name, no filename, no start line. Java classes
  are slashed (`java/lang/String.repeat`); hidden classes keep `.0x...` addresses; native and
  kernel frames are bare symbols; overloads repeat a frame (`ArrayList.grow`, `ArrayList.grow`).
  The agent writes no line numbers; `jfrconv` writes `Line.line`. The agent marks frame types with
  collapsed-format suffixes (`java.lang.String_[i]` for the allocated class, `..._[k]` for kernel
  frames); `jfrconv` does not. Sample type names are async-profiler's engine names for the agent
  (`perf_events`, `wall`, `object_sampler`; trial runs not kept here showed `itimer`, `ctimer`,
  `lock_tracer` and `malloc_tracer` for those events), and `cpu`/`allocations` for `jfrconv`,
  which names a wall profile `cpu` as it does in pprof.
- **Attributes.** The agent writes none; `jfrconv` writes `thread.name` per sample. No resource
  attributes, no scope.
