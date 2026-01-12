package io.jafar.shell.llm.tuning;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager.SessionRef;
import io.jafar.shell.llm.LLMConfig;
import io.jafar.shell.llm.LLMProvider;
import io.jafar.shell.llm.LLMProviderFactory;
import io.jafar.shell.llm.tuning.reports.TuningReport;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Main entry point for running LLM prompt tuning tests. */
public class PromptTunerMain {

  public static void main(String[] args) {
    try {
      Options options = parseArgs(args);

      System.out.println("=== LLM Prompt Tuning ===\n");

      // 1. Load test suite
      System.out.println("Loading test suite from: " + options.testSuitePath);
      TestSuite testSuite = TestSuite.load(options.testSuitePath);
      System.out.println("Loaded " + testSuite.getTestCases().size() + " test cases\n");

      // 2. Load variants
      System.out.println("Loading variants from: " + options.variantsPath);
      List<PromptVariant> variants = PromptVariant.loadAll(options.variantsPath);
      System.out.println("Loaded " + variants.size() + " prompt variants\n");

      // 3. Initialize LLM provider
      System.out.println("Initializing LLM provider...");
      LLMConfig config;
      try {
        config = LLMConfig.load();
      } catch (IOException e) {
        System.out.println("No config file found, using defaults");
        config = LLMConfig.defaults();
      }
      LLMProvider provider = LLMProviderFactory.create(config);

      if (!provider.isAvailable()) {
        System.err.println("\nERROR: LLM provider not available!");
        System.err.println(
            "Provider: " + config.provider() + ", Model: " + config.model());
        if (config.provider() == LLMConfig.ProviderType.LOCAL) {
          System.err.println("\nMake sure Ollama is running:");
          System.err.println("  ollama serve");
          System.err.println("\nAnd the model is available:");
          System.err.println("  ollama pull " + config.model());
          System.err.println("  ollama list");
        }
        System.exit(1);
      }

      System.out.println("Provider: " + config.provider());
      System.out.println("Model: " + provider.getModelName());
      System.out.println();

      // 4. Open test JFR recording
      System.out.println("Opening JFR recording: " + options.recordingPath);
      ParsingContext ctx = ParsingContext.create();
      JFRSession session = new JFRSession(Paths.get(options.recordingPath), ctx);

      // Create SessionRef wrapper
      SessionRef testSession = new SessionRef(1, "test", session);
      System.out.println(
          "Recording loaded: " + testSession.session.getAvailableEventTypes().size() + " event types\n");

      // 5. Run tests for each variant
      List<TuningResults> allResults = new ArrayList<>();

      for (int i = 0; i < variants.size(); i++) {
        PromptVariant variant = variants.get(i);
        System.out.println(
            "=== Testing variant " + (i + 1) + "/" + variants.size() + ": " + variant.getId() + " ===");
        System.out.println("Description: " + variant.getDescription());

        PromptTuner tuner = new PromptTuner(testSuite, variant, provider, testSession);
        TuningResults results = tuner.runTests();

        TuningMetrics metrics = results.calculateMetrics();
        System.out.println("\n" + metrics.formatSummary());
        System.out.println();

        allResults.add(results);
      }

      // 6. Generate report
      Path reportPath = Paths.get(options.outputDir, "tuning-report.md");
      TuningReport.generateMarkdown(allResults, reportPath);
      System.out.println("=== Report generated: " + reportPath + " ===");

      // 7. Cleanup
      session.close();

      // 8. Exit with success if best variant is above threshold
      double bestSuccessRate =
          allResults.stream()
              .mapToDouble(r -> r.calculateMetrics().successRate())
              .max()
              .orElse(0.0);

      if (bestSuccessRate >= 0.9) {
        System.out.println("\nSUCCESS: Best variant achieved " + String.format("%.1f%%", bestSuccessRate * 100) + " success rate!");
        System.exit(0);
      } else {
        System.out.println(
            "\nWARNING: Best variant only achieved " + String.format("%.1f%%", bestSuccessRate * 100) + " success rate (target: 90%)");
        System.exit(1);
      }

    } catch (Exception e) {
      System.err.println("ERROR: " + e.getMessage());
      e.printStackTrace();
      System.exit(1);
    }
  }

  /**
   * Parses command line arguments.
   *
   * @param args command line arguments
   * @return parsed options
   */
  private static Options parseArgs(String[] args) {
    Options options = new Options();

    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--test-suite":
          options.testSuitePath = args[++i];
          break;
        case "--variants":
          options.variantsPath = args[++i];
          break;
        case "--recording":
          options.recordingPath = args[++i];
          break;
        case "--output":
          options.outputDir = args[++i];
          break;
        case "--help":
        case "-h":
          printUsage();
          System.exit(0);
          break;
        default:
          System.err.println("Unknown option: " + args[i]);
          printUsage();
          System.exit(1);
      }
    }

    // Set defaults
    if (options.testSuitePath == null) {
      options.testSuitePath = "src/test/resources/llm-tuning/test-suite.json";
    }
    if (options.variantsPath == null) {
      options.variantsPath = "src/test/resources/llm-tuning/variants.json";
    }
    if (options.recordingPath == null) {
      options.recordingPath = "src/test/resources/sample.jfr";
    }
    if (options.outputDir == null) {
      options.outputDir = "build/reports/prompt-tuning";
    }

    return options;
  }

  private static void printUsage() {
    System.out.println("Usage: PromptTunerMain [OPTIONS]");
    System.out.println();
    System.out.println("Options:");
    System.out.println("  --test-suite <path>   Path to test suite JSON file");
    System.out.println(
        "                        (default: src/test/resources/llm-tuning/test-suite.json)");
    System.out.println("  --variants <path>     Path to variants JSON file");
    System.out.println(
        "                        (default: src/test/resources/llm-tuning/variants.json)");
    System.out.println("  --recording <path>    Path to JFR recording for testing");
    System.out.println("                        (default: src/test/resources/sample.jfr)");
    System.out.println("  --output <dir>        Output directory for reports");
    System.out.println("                        (default: build/reports/prompt-tuning)");
    System.out.println("  --help, -h            Show this help message");
  }

  /** Command line options. */
  private static class Options {
    String testSuitePath;
    String variantsPath;
    String recordingPath;
    String outputDir;
  }
}
