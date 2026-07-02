package io.jafar.tools;

import io.jafar.tools.Scrubber.ScrubField;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Command-line entry point for {@link Scrubber}. */
public final class ScrubberMain {

  private ScrubberMain() {}

  public static void main(String[] args) throws Exception {
    Path input = null;
    Path output = null;
    List<String[]> scrubFields = new ArrayList<>();

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--input":
          input = Paths.get(args[++i]);
          break;
        case "--output":
          output = Paths.get(args[++i]);
          break;
        case "--scrub-field":
          scrubFields.add(splitTypeField(args[++i]));
          break;
        default:
          usage();
          System.exit(1);
          return;
      }
    }

    if (input == null || output == null || scrubFields.isEmpty()) {
      usage();
      System.exit(1);
      return;
    }

    List<String[]> fields = scrubFields;
    Scrubber.scrubFile(
        input,
        output,
        type -> {
          for (String[] field : fields) {
            if (field[0].equals(type)) {
              return new ScrubField(null, field[1], null);
            }
          }
          return null;
        });
  }

  private static String[] splitTypeField(String spec) {
    int dot = spec.lastIndexOf('.');
    if (dot < 0) {
      throw new IllegalArgumentException("--scrub-field must be <Type>.<field>, got: " + spec);
    }
    return new String[] {spec.substring(0, dot), spec.substring(dot + 1)};
  }

  private static void usage() {
    System.err.println(
        "Usage: scrub --input <recording.jfr> --output <scrubbed.jfr>"
            + " --scrub-field <Type>.<field> [--scrub-field <Type>.<field> ...]");
  }
}
