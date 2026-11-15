/// Generates the small test fixture used by the unit test suites
/// (test-fixtures/synthetic.jfr). It is a real JDK Flight Recording produced
/// by the JDK's own JFR implementation - single producer, internally
/// consistent metadata - containing:
///
///   * plenty of jdk.ExecutionSample events with deep stack traces
///   * allocation samples (jdk.ObjectAllocationSample / InNewTLAB)
///   * GC and VM events from the default profile, kept small by recording
///     for a few seconds only
///
/// Tests rely on it having ExecutionSample events with non-empty stacks and
/// startTime timestamps; anything richer is best effort. Regenerate with:
///
///   java test-fixtures/GenerateRecording.java test-fixtures/synthetic.jfr
///
/// Because the output is committed, regenerate it from a system JDK with a
/// generic library path (see test-fixtures/README.md), never from a
/// per-user JDK install, and verify with `strings ... | grep -E '/Users/|/home/'`.
///
/// Requires a JDK (module jdk.jfr). No external dependencies.
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.text.ParseException;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

public class GenerateRecording {

  static volatile long sink;

  // Deep-ish, non-trivial recursion so samples have meaningful stacks.
  static long recurse(int depth, long acc) {
    if (depth <= 0) {
      return acc * 2654435761L + System.nanoTime();
    }
    acc = acc + System.nanoTime() % 1000;
    // busy work so the sampler catches us here
    for (int i = 0; i < 2000; i++) {
      acc ^= (acc << 13);
      acc ^= (acc >>> 7);
      acc ^= (acc << 17);
    }
    return recurse(depth - 1, acc);
  }

  public static void main(String[] args) throws IOException, InterruptedException, ParseException {
    if (args.length != 1) {
      System.err.println("usage: java GenerateRecording.java <output.jfr>");
      System.exit(2);
    }
    Path out = Path.of(args[0]);

    try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
      recording.setName("jafar-test-fixture");
      recording.setMaxSize(16L * 1024 * 1024);
      recording.setMaxAge(Duration.ofMinutes(1));
      // This file is committed to the repository: do not record the host's
      // environment or process list into it (jdk.InitialSystemProperty is
      // kept because ScrubberIntegrationTest requires a java.home entry).
      recording.disable("jdk.InitialEnvironmentVariable");
      recording.disable("jdk.SystemProcess");
      // jdk.NativeLibrary events carry absolute paths of the running JDK;
      // they would leak the machine the fixture was generated on.
      recording.disable("jdk.NativeLibrary");
      recording.start();

      // Burn CPU for the duration so execution samples accumulate.
      long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
      long acc = 0;
      while (System.nanoTime() < deadline) {
        acc += recurse(64, acc);
        // some allocation pressure for allocation samples
        byte[][] ballast = new byte[64][];
        for (int i = 0; i < ballast.length; i++) {
          ballast[i] = new byte[256];
        }
        acc += ballast.length;
      }
      System.gc(); // ensure GC events land in the recording
      Thread.sleep(200);
      recording.stop();
      recording.dump(out);
      sink ^= acc; // keep work alive
    }

    System.out.println("wrote " + out + " (" + Files.size(out) + " bytes), sink=" + sink);
  }
}
