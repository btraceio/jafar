package io.jafar.arch;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Comparator.naturalOrder;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports every covered module's compiled classes and keeps the className → module mapping.
 *
 * <p>The build (arch-tests/build.gradle) defines the module groups once and passes them as system
 * properties: {@code jafar.arch.group.<name>} is a comma-separated member list and {@code
 * jafar.arch.module.<m>} is the absolute path of {@code <m>/build/classes/java/main}. Nothing
 * enumerates modules here — a module with no property or no group is a configuration error that
 * fails loudly (R3), never an empty default.
 */
public final class ModuleClasses {

  private static final String MODULE_PROPERTY_PREFIX = "jafar.arch.module.";
  private static final String GROUP_PROPERTY_PREFIX = "jafar.arch.group.";

  /** matches {@code include ':x'}/{@code include ':x', ':y'} lines in a Gradle settings file */
  private static final Pattern INCLUDE_LINE = Pattern.compile("(?m)^\\s*include\\s+(.+)$");

  /** matches the {@code :name} inside the quotes of an include entry */
  private static final Pattern QUOTED_PROJECT = Pattern.compile("':([\\w.-]+)'");

  private final SortedMap<String, Path> moduleDirs;
  private final SortedMap<String, Integer> moduleClassCounts;
  private final Map<String, String> classToModule;
  private final SortedMap<String, SortedSet<String>> groups;
  private final JavaClasses allClasses;

  private ModuleClasses(
      SortedMap<String, Path> moduleDirs,
      SortedMap<String, Integer> moduleClassCounts,
      Map<String, String> classToModule,
      SortedMap<String, SortedSet<String>> groups,
      JavaClasses allClasses) {
    this.moduleDirs = moduleDirs;
    this.moduleClassCounts = moduleClassCounts;
    this.classToModule = classToModule;
    this.groups = groups;
    this.allClasses = allClasses;
  }

  /** Reads the group/module system properties and imports every covered module's classes. */
  public static ModuleClasses load() {
    SortedMap<String, SortedSet<String>> groups = readGroups();
    SortedMap<String, Path> moduleDirs = new TreeMap<>();
    for (SortedSet<String> members : groups.values()) {
      for (String module : members) {
        if (moduleDirs.containsKey(module)) {
          continue;
        }
        String property = System.getProperty(MODULE_PROPERTY_PREFIX + module);
        if (property == null || property.isEmpty()) {
          throw new IllegalStateException(
              "module '"
                  + module
                  + "' is in a module group but has no "
                  + MODULE_PROPERTY_PREFIX
                  + module
                  + " system property — pass it from arch-tests/build.gradle (R3: no silent default)");
        }
        moduleDirs.put(module, Paths.get(property));
      }
    }
    if (moduleDirs.isEmpty()) {
      throw new IllegalStateException("no jafar.arch.group.* system properties found");
    }

    ClassFileImporter importer = new ClassFileImporter();
    SortedMap<String, Integer> counts = new TreeMap<>();
    Map<String, String> classToModule = new LinkedHashMap<>();
    for (Map.Entry<String, Path> entry : moduleDirs.entrySet()) {
      JavaClasses moduleClasses = importer.importPath(entry.getValue());
      counts.put(entry.getKey(), moduleClasses.size());
      for (JavaClass clazz : moduleClasses) {
        String previous = classToModule.put(clazz.getName(), entry.getKey());
        if (previous != null && !previous.equals(entry.getKey())) {
          throw new IllegalStateException(
              "class '"
                  + clazz.getName()
                  + "' is compiled in both '"
                  + previous
                  + "' and '"
                  + entry.getKey()
                  + "' — module class sets must be disjoint");
        }
      }
    }
    // one combined import so ArchUnit resolves cross-module dependencies within the set
    JavaClasses allClasses = importer.importPaths(new ArrayList<>(moduleDirs.values()));
    return new ModuleClasses(moduleDirs, counts, classToModule, groups, allClasses);
  }

  /** The module groups, name → members, as passed by the build (the single source of truth). */
  public SortedMap<String, SortedSet<String>> groups() {
    return groups;
  }

  /** Every covered module, sorted. */
  public SortedSet<String> modules() {
    return new TreeSet<>(moduleDirs.keySet());
  }

  /** The imported class dir of {@code module}. */
  public Path dirOf(String module) {
    Path dir = moduleDirs.get(module);
    if (dir == null) {
      throw new IllegalStateException("unknown module '" + module + "'");
    }
    return dir;
  }

  /** How many classes were imported from {@code module}; 0 means the rules cover nothing there. */
  public int classCount(String module) {
    Integer count = moduleClassCounts.get(module);
    if (count == null) {
      throw new IllegalStateException("unknown module '" + module + "'");
    }
    return count;
  }

  /** Which module (if any) owns the class named {@code className}. */
  public Optional<String> moduleOf(String className) {
    return Optional.ofNullable(classToModule.get(className));
  }

  /** All classes of all covered modules in one import. */
  public JavaClasses classes() {
    return allClasses;
  }

  /** True for classes compiled in {@code module}. */
  public DescribedPredicate<JavaClass> residesIn(String module) {
    dirOf(module); // fail fast on an unknown module name
    return DescribedPredicate.describe(
        "reside in module '" + module + "'",
        javaClass -> module.equals(classToModule.get(javaClass.getName())));
  }

  /** True for classes compiled in any member of {@code group}. */
  public DescribedPredicate<JavaClass> residesInGroup(String group) {
    SortedSet<String> members = groups.get(group);
    if (members == null) {
      throw new IllegalStateException(
          "no module group '"
              + group
              + "': define jafar.arch.group."
              + group
              + " in arch-tests/build.gradle");
    }
    return DescribedPredicate.describe(
        "reside in group '" + group + "' " + members,
        javaClass -> {
          String module = classToModule.get(javaClass.getName());
          return module != null && members.contains(module);
        });
  }

  private static SortedMap<String, SortedSet<String>> readGroups() {
    SortedMap<String, SortedSet<String>> groups = new TreeMap<>();
    for (String key : System.getProperties().stringPropertyNames()) {
      if (!key.startsWith(GROUP_PROPERTY_PREFIX)) {
        continue;
      }
      String value = System.getProperty(key, "");
      SortedSet<String> members = new TreeSet<>(naturalOrder());
      for (String member : value.split(",")) {
        String trimmed = member.trim();
        if (!trimmed.isEmpty()) {
          members.add(trimmed);
        }
      }
      if (members.isEmpty()) {
        throw new IllegalStateException("group property '" + key + "' lists no modules");
      }
      groups.put(key.substring(GROUP_PROPERTY_PREFIX.length()), members);
    }
    return groups;
  }

  /** Reads every project included by a Gradle settings file ({@code include ':x'} entries). */
  public static List<String> readIncludes(Path settingsFile) {
    String text;
    try {
      text = new String(Files.readAllBytes(settingsFile), UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + settingsFile, e);
    }
    List<String> includes = new ArrayList<>();
    Matcher line = INCLUDE_LINE.matcher(text);
    while (line.find()) {
      Matcher quoted = QUOTED_PROJECT.matcher(line.group(1));
      while (quoted.find()) {
        includes.add(quoted.group(1));
      }
    }
    return includes;
  }
}
