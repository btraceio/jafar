import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;

/**
 * The program the async-profiler fixtures profile: a CPU-bound loop behind a lambda, a nested
 * class, constructors, allocation and a sleeping thread, so the profiles carry each Java frame-name
 * shape. See ../README.md for the exact commands.
 */
public final class RealProf {

  /** A nested class with a constructor: {@code RealProf$Hasher.<init>}. */
  static final class Hasher {
    private long state;

    Hasher(long seed) {
      this.state = seed;
    }

    long sum(int n) {
      for (int i = 0; i < n; i++) {
        state = mix(state, i);
      }
      return state;
    }

    private static long mix(long h, long v) {
      h ^= v;
      h *= 0x100000001b3L;
      return h;
    }
  }

  static long burn(int rounds) {
    IntUnaryOperator step = n -> (int) new Hasher(0xcbf29ce484222325L).sum(n);
    long total = 0;
    for (int r = 0; r < rounds; r++) {
      total += step.applyAsInt(1 << 20);
    }
    return total;
  }

  static int allocate(int n) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add(("x".repeat(64) + i).toUpperCase());
    }
    return out.size();
  }

  public static void main(String[] args) throws Exception {
    long seconds = args.length > 0 ? Long.parseLong(args[0]) : 2;
    Thread sleeper =
        new Thread(
            () -> {
              try {
                while (true) {
                  Thread.sleep(5);
                }
              } catch (InterruptedException e) {
                // done
              }
            },
            "sleeper");
    sleeper.setDaemon(true);
    sleeper.start();
    long deadline = System.nanoTime() + seconds * 1_000_000_000L;
    long sum = 0;
    while (System.nanoTime() < deadline) {
      sum += burn(8);
      sum += allocate(20_000);
    }
    sleeper.interrupt();
    System.out.println(sum);
  }
}
