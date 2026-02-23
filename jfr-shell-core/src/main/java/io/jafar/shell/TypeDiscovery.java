package io.jafar.shell;

import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.utils.TypeGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Discovers available JFR event types in a recording and can generate typed interfaces for them on
 * the fly.
 */
public final class TypeDiscovery {

  private final ParsingContext parsingContext;

  public TypeDiscovery(ParsingContext parsingContext) {
    this.parsingContext = parsingContext;
  }

  /**
   * Discover all event types available in the given recording.
   *
   * @param recordingPath path to the JFR recording
   * @return map of event type names to their metadata
   * @throws IOException if reading fails
   */
  public Map<String, MetadataClass> discoverTypes(Path recordingPath) throws Exception {
    Map<String, MetadataClass> discoveredTypes = new HashMap<>();

    // Use untyped parser just to collect metadata
    try (UntypedJafarParser parser = parsingContext.newUntypedParser(recordingPath)) {
      parser.withParserListener(
          new io.jafar.parser.internal_api.ChunkParserListener() {
            @Override
            public boolean onMetadata(
                io.jafar.parser.api.ParserContext context, MetadataEvent metadata) {
              for (MetadataClass clazz : metadata.getClasses()) {
                if (isEventType(clazz)) {
                  discoveredTypes.put(clazz.getName(), clazz);
                }
              }
              return true;
            }
          });

      // Register a no-op handler to trigger metadata discovery
      parser.handle(
          (type, value, ctl) -> {
            /* no-op */
          });
      parser.run();
    }

    return discoveredTypes;
  }

  /**
   * Generate typed interfaces for the discovered event types using the existing TypeGenerator.
   *
   * @param recordingPath path to JFR recording
   * @param outputDir directory to write generated classes
   * @param packageName package name for generated classes
   * @throws Exception if generation fails
   */
  public void generateTypedInterfaces(Path recordingPath, Path outputDir, String packageName)
      throws Exception {
    if (!Files.exists(outputDir)) {
      Files.createDirectories(outputDir);
    }

    // Use the existing TypeGenerator with a filter for common event types
    TypeGenerator generator =
        new TypeGenerator(
            recordingPath,
            outputDir.getParent(), // TypeGenerator expects parent dir
            packageName,
            true, // overwrite
            typeName -> isCommonEventType(typeName));

    generator.generate();
  }

  private boolean isCommonEventType(String typeName) {
    return typeName.startsWith("jdk.")
        || typeName.startsWith("jfr.")
        || getCommonEventTypes().contains(typeName);
  }

  /**
   * Get a summary of discovered types with event counts.
   *
   * @param recordingPath path to the JFR recording
   * @return map of type names to event counts
   * @throws IOException if reading fails
   */
  public Map<String, Long> getTypeCounts(Path recordingPath) throws Exception {
    Map<String, Long> typeCounts = new HashMap<>();

    try (UntypedJafarParser parser = parsingContext.newUntypedParser(recordingPath)) {
      parser.handle(
          (type, value, ctl) -> {
            String typeName = type.getName();
            typeCounts.merge(typeName, 1L, Long::sum);
          });
      parser.run();
    }

    return typeCounts;
  }

  /**
   * Get common JFR event types that are typically of interest.
   *
   * @return set of common event type names
   */
  public static Set<String> getCommonEventTypes() {
    Set<String> commonTypes = new TreeSet<>();

    // Profiling events
    commonTypes.add("jdk.ExecutionSample");
    commonTypes.add("jdk.MethodSample");
    commonTypes.add("jdk.MethodProfiling");

    // Memory events
    commonTypes.add("jdk.ObjectAllocation");
    commonTypes.add("jdk.ObjectAllocationInNewTLAB");
    commonTypes.add("jdk.ObjectAllocationOutsideTLAB");

    // GC events
    commonTypes.add("jdk.GarbageCollection");
    commonTypes.add("jdk.GCPhaseParallel");
    commonTypes.add("jdk.GCPhasePause");
    commonTypes.add("jdk.GCHeapSummary");

    // Threading events
    commonTypes.add("jdk.ThreadStart");
    commonTypes.add("jdk.ThreadEnd");
    commonTypes.add("jdk.ThreadSleep");
    commonTypes.add("jdk.ThreadPark");
    commonTypes.add("jdk.MonitorEnter");
    commonTypes.add("jdk.MonitorWait");

    // I/O events
    commonTypes.add("jdk.FileRead");
    commonTypes.add("jdk.FileWrite");
    commonTypes.add("jdk.SocketRead");
    commonTypes.add("jdk.SocketWrite");

    // Class loading
    commonTypes.add("jdk.ClassLoad");
    commonTypes.add("jdk.ClassDefine");

    // JIT compilation
    commonTypes.add("jdk.Compilation");
    commonTypes.add("jdk.CompilerPhase");

    return commonTypes;
  }

  /**
   * Check if a metadata class represents a JFR event type.
   *
   * @param clazz metadata class to check
   * @return true if this is an event type
   */
  private boolean isEventType(MetadataClass clazz) {
    String name = clazz.getName();

    // Filter out internal types and focus on actual events
    if (name.contains("$") || name.contains("@")) {
      return false;
    }

    // Most JFR events are in jdk.* packages
    if (name.startsWith("jdk.") || name.startsWith("jfr.")) {
      return true;
    }

    // Custom events typically have meaningful package names
    if (name.contains(".") && !name.startsWith("java.")) {
      return true;
    }

    return false;
  }

  /**
   * Convert JFR type name to Java interface name. e.g., "jdk.ExecutionSample" ->
   * "JFRExecutionSample"
   *
   * @param typeName JFR type name
   * @return Java interface name
   */
  private String toJavaInterfaceName(String typeName) {
    String simpleName = typeName.substring(typeName.lastIndexOf('.') + 1);
    return "JFR" + simpleName;
  }
}
