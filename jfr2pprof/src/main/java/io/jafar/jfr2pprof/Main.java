package io.jafar.jfr2pprof;

import io.jafar.jfr2pprof.config.MappingConfig;
import io.jafar.jfr2pprof.config.MappingLoader;
import io.jafar.jfr2pprof.convert.Jfr2PprofConverter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Main {

  public static void main(String[] args) {
    System.exit(run(args));
  }

  static int run(String[] args) {
    String configPath = null, outputPath = null, jfrPath = null;
    boolean noGzip = false;
    String periodType = null;

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--config" -> {
          if (++i >= args.length) return usage();
          configPath = args[i];
        }
        case "--output" -> {
          if (++i >= args.length) return usage();
          outputPath = args[i];
        }
        case "--no-gzip" -> noGzip = true;
        case "--period-type" -> {
          if (++i >= args.length) return usage();
          periodType = args[i];
        }
        default -> {
          if (args[i].startsWith("--")) return usage();
          if (jfrPath != null) return usage();
          jfrPath = args[i];
        }
      }
    }

    if (configPath == null || outputPath == null || jfrPath == null) return usage();

    Path config = Path.of(configPath);
    Path jfr = Path.of(jfrPath);
    Path out = Path.of(outputPath);

    if (!Files.exists(config)) {
      System.err.println("Config not found: " + config);
      return 2;
    }
    if (!Files.exists(jfr)) {
      System.err.println("Recording not found: " + jfr);
      return 2;
    }

    MappingConfig mappingConfig;
    try {
      mappingConfig = MappingLoader.load(config);
    } catch (IllegalArgumentException e) {
      System.err.println("Invalid config: " + e.getMessage());
      return 1;
    } catch (IOException e) {
      System.err.println("Failed to load config: " + e.getMessage());
      return 2;
    }

    boolean gzip = !noGzip;
    try (OutputStream os = new FileOutputStream(out.toFile())) {
      int count = new Jfr2PprofConverter().convert(jfr, mappingConfig, gzip, os);
      System.out.println("Wrote " + count + " samples to " + out);
      return 0;
    } catch (IllegalStateException e) {
      System.err.println(e.getMessage());
      return 1;
    } catch (IOException e) {
      System.err.println("I/O error: " + e.getMessage());
      return 2;
    }
  }

  private static int usage() {
    System.err.println(
        "Usage: jfr2pprof --config <mapping.yaml> --output <out.pprof> [--no-gzip] [--period-type <t>/<u>] <recording.jfr>");
    return 1;
  }
}
