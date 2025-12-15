package io.jafar.utils;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.impl.TypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.ValueDescriptor;

/**
 * Utility class for generating Java interfaces from JFR event types.
 *
 * <p>This class provides functionality to generate Java interface definitions that correspond to
 * JFR event types, either from runtime JFR information or from JFR recording files. The generated
 * interfaces can be used with the typed JFR parser for type-safe event handling.
 */
public final class TypeGenerator {
  /** Path to the JFR recording file, or null for runtime generation. */
  private final Path jfr;

  /** Output directory for generated files. */
  private final Path output;

  /** Target package for generated interfaces. */
  private final String pkg;

  /** Whether to overwrite existing files. */
  private final boolean overwrite;

  /** Filter for selecting which event types to generate. */
  private final Predicate<String> eventTypeFilter;

  /**
   * Constructs a new TypeGenerator with the specified parameters.
   *
   * @param jfr the path to the JFR recording file, or null for runtime generation
   * @param output the output directory for generated files
   * @param targetPackage the target package for generated interfaces
   * @param overwrite whether to overwrite existing files
   * @param eventTypeFilter filter for selecting which event types to generate
   * @throws IOException if an I/O error occurs during setup
   */
  public TypeGenerator(
      Path jfr,
      Path output,
      String targetPackage,
      boolean overwrite,
      Predicate<String> eventTypeFilter)
      throws IOException {
    if (!Files.isDirectory(output) || !Files.exists(output)) {
      throw new IllegalArgumentException("Output directory does not exist: " + output);
    }
    this.jfr = jfr;
    this.pkg = targetPackage;
    this.output = output.resolve(targetPackage.replace('.', '/'));
    this.overwrite = overwrite;
    this.eventTypeFilter = eventTypeFilter;
    Files.createDirectories(this.output);
  }

  /**
   * Generates Java interfaces from JFR event types.
   *
   * <p>This method either generates interfaces from runtime JFR information or from a JFR recording
   * file, depending on the configuration.
   *
   * @throws Exception if an error occurs during generation
   */
  public void generate() throws Exception {
    if (jfr == null) {
      generateFromRuntime();
    } else {
      generateFromFile();
    }
  }

  private void generateFromRuntime() throws Exception {
    Set<String> generated = new HashSet<>();
    FlightRecorder.getFlightRecorder()
        .getEventTypes()
        .forEach(
            et -> {
              if (eventTypeFilter == null || eventTypeFilter.test(et.getName())) {
                try {
                  Path target = output.resolve("JFR" + getSimpleName(et.getName()) + ".java");
                  String typeContent = generateTypeFromEvent(et, generated);
                  if (!Files.exists(target)) {
                    Files.write(
                        target,
                        typeContent.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE_NEW);
                  } else if (overwrite) {
                    Files.write(
                        target,
                        typeContent.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
                  }
                } catch (IOException e) {
                  throw new RuntimeException(
                      "Failed to generate type interface for " + et.getName(), e);
                }
              }
            });
  }

  private String generateTypeFromEvent(EventType et, Set<String> generatedTypes) {
    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(pkg).append(";\n");
    sb.append("\n");
    sb.append("import io.jafar.parser.api.*;\n");
    sb.append("@JfrType(\"").append(et.getName()).append("\")\n\n");
    sb.append("public interface JFR").append(getSimpleName(et.getName())).append(" {\n");
    et.getFields()
        .forEach(
            field -> {
              try {
                writeTypeFromField(field, generatedTypes);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
              String fldName = sanitizeFieldName(field.getName());
              sb.append('\t');
              if (!fldName.equals(field.getName())) {
                sb.append("@JfrField(\"").append(field.getName()).append("\") ");
              }
              sb.append(isPrimitiveName(field.getTypeName()) ? "" : "JFR")
                  .append(getSimpleName(field.getTypeName()));
              if (field.isArray()) {
                sb.append("[]");
              }
              sb.append(" ");
              sb.append(fldName).append("();\n");
            });
    sb.append("}\n");
    return sb.toString();
  }

  private void writeTypeFromField(ValueDescriptor f, Set<String> generatedTypes) throws Exception {
    String data = getTypeFromField(f, generatedTypes);

    if (data != null) {
      String typeName = f.getTypeName();
      String targetName = isPrimitiveName(typeName) ? typeName : "JFR" + getSimpleName(typeName);
      Path target = output.resolve(targetName + ".java");
      if (!Files.exists(target)) {
        Files.write(target, data.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE_NEW);
      } else if (overwrite) {
        Files.write(
            target,
            data.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
      }
    }
  }

  private String getTypeFromField(ValueDescriptor field, Set<String> generatedTypes) {
    String typeName = field.getTypeName();
    if (isPrimitiveName(typeName)) {
      return null;
    }

    if (generatedTypes.add(typeName)) {
      StringBuilder sb = new StringBuilder();
      sb.append("package ").append(pkg).append(";\n");
      sb.append("\n");
      sb.append("import io.jafar.parser.api.*;\n");
      sb.append("@JfrType(\"").append(typeName).append("\")\n\n");
      sb.append("public interface JFR").append(getSimpleName(typeName)).append(" {\n");
      field
          .getFields()
          .forEach(
              subfield -> {
                try {
                  writeTypeFromField(subfield, generatedTypes);
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
                String fldName = sanitizeFieldName(subfield.getName());
                sb.append('\t');
                if (!fldName.equals(subfield.getName())) {
                  sb.append("@JfrField(\"").append(subfield.getName()).append("\") ");
                }
                sb.append(isPrimitiveName(subfield.getTypeName()) ? "" : "JFR")
                    .append(getSimpleName(subfield.getTypeName()));
                if (subfield.isArray()) {
                  sb.append("[]");
                }
                sb.append(" ");
                sb.append(fldName).append("();\n");
              });
      sb.append("}\n");
      return sb.toString();
    }
    return null;
  }

  private void generateFromFile() throws Exception {
    Set<String> processed = new HashSet<>();
    try (StreamingChunkParser parser = new StreamingChunkParser(new TypedParserContextFactory())) {
      parser.parse(
          jfr,
          new ChunkParserListener() {
            @Override
            public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
              metadata.getClasses().stream()
                  .filter(
                      clz -> {
                        boolean isEv = isEvent(clz);
                        boolean passesFilter =
                            eventTypeFilter == null || eventTypeFilter.test(clz.getName());
                        return isEv && passesFilter;
                      })
                  .forEach(clz -> writeClass(clz, processed));
              // stop processing
              return false;
            }
          });
    }
  }

  private void writeClass(MetadataClass metadataClass, Set<String> processed) {
    if (metadataClass.isPrimitive()) {
      return;
    }
    if (isAnnotation(metadataClass) || isSettingControl(metadataClass)) {
      return;
    }
    String className = metadataClass.getName();
    if (processed.contains(className)) {
      System.out.println("Skipping already processed class: " + className);
      return;
    }
    System.out.println("Processing class: " + className);
    processed.add(className);
    try {
      Path classFile = output.resolve(getClassName(metadataClass) + ".java");
      String classContent = generateClass(metadataClass, processed);
      if (!Files.exists(classFile)) {
        Files.write(
            classFile,
            classContent.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE_NEW);
      } else if (overwrite) {
        Files.write(
            classFile,
            classContent.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String generateClass(MetadataClass clazz, Set<String> processed) {
    StringBuilder sb = new StringBuilder();
    sb.append("package ").append(pkg).append(";\n");
    sb.append("\n");
    sb.append("import io.jafar.parser.api.*;\n");
    sb.append("@JfrType(\"").append(clazz.getName()).append("\")\n\n");
    sb.append("public interface ").append(getClassName(clazz));
    sb.append(" {\n");
    for (MetadataField field : clazz.getFields()) {
      String fldName = sanitizeFieldName(field.getName());
      sb.append('\t');
      if (!fldName.equals(field.getName())) {
        sb.append("@JfrField(\"").append(field.getName()).append("\") ");
      }
      MetadataClass fldType = field.getType();
      while (fldType.isSimpleType()) {
        List<MetadataField> fl = fldType.getFields();
        fldType = fl.get(0).getType();
      }
      // Recursively generate nested types, but only if not already processed/being processed
      // This prevents infinite recursion when there are circular type references
      if (!fldType.isPrimitive() && !processed.contains(fldType.getName())) {
        writeClass(fldType, processed);
      }
      sb.append(getClassName(fldType));
      int dims = Math.max(0, field.getDimension());
      for (int i = 0; i < dims; i++) {
        sb.append("[]");
      }
      sb.append(" ");
      sb.append(fldName).append("();\n");
    }
    sb.append("}\n");
    return sb.toString();
  }

  private String getClassName(MetadataClass clazz) {
    return (!clazz.isPrimitive() ? "JFR" : "") + clazz.getSimpleName();
  }

  private String sanitizeFieldName(String fieldName) {
    String sanitized = fieldName;
    // Replace dots with underscores (e.g., "_dd.trace.operation" -> "_dd_trace_operation")
    sanitized = sanitized.replace('.', '_');
    // Handle Java keywords
    switch (sanitized) {
      case "class":
        return "clz";
      case "package":
        return "pkg";
      default:
        return sanitized;
    }
  }

  private static boolean isEvent(MetadataClass clazz) {
    String superType = clazz.getSuperType();
    if (superType == null) {
      return false;
    }
    if ("jdk.jfr.Event".equals(superType)) {
      return true;
    }
    /*
    TODO: this is not technically true as a type may have JFR event upper in hierarchy but
          let's ignore it for now
     */
    return false;
  }

  private static boolean isAnnotation(MetadataClass clazz) {
    String superType = clazz.getSuperType();
    if (superType == null) {
      return false;
    }
    if ("java.lang.annotation.Annotation".equals(superType)) {
      return true;
    }
    /*
    TODO: this is not technically true as a type may have JFR event upper in hierarchy but
          let's ignore it for now
     */
    return false;
  }

  private static boolean isSettingControl(MetadataClass clazz) {
    String superType = clazz.getSuperType();
    if (superType == null) {
      return false;
    }
    return "jdk.jfr.SettingControl".equals(superType);
    /*
    TODO: this is not technically true as a type may have JFR event upper in hierarchy but
          let's ignore it for now
     */
  }

  private static String getSimpleName(String name) {
    int idx = name.lastIndexOf('.');
    return idx == -1 ? name : name.substring(idx + 1);
  }

  private static boolean isPrimitiveName(String name) {
    return name.lastIndexOf('.') == -1 || "java.lang.String".equals(name);
  }
}
