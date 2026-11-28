package io.jafar.processor;

import io.jafar.parser.api.GenerateJfrType;
import io.jafar.parser.api.JfrType;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Writes the {@code @JfrType} interface source for each {@code jdk.jfr.Event} subclass annotated
 * {@link GenerateJfrType} (#89). The interfaces are compiled in a later round, where {@link
 * JfrTypeProcessor} generates their handlers as for any other {@code @JfrType} interface.
 *
 * <p>The fields are those JFR records for the class, by the JDK's own rules ({@code
 * jdk.jfr.internal.util.Utils.getVisibleEventFields} and {@code TypeLibrary}): the class's own
 * non-static, non-transient fields and its superclasses' that are not private, below {@code
 * jdk.jfr.Event}, of a primitive type, {@code String} or {@code Thread}; named by {@code
 * jdk.jfr.Name} when that is a valid Java identifier and else as declared; plus the implicit {@code
 * startTime}, {@code duration} and {@code eventThread}. The event's name is the class's {@code
 * jdk.jfr.Name} when that is a valid type name, and else its binary name.
 */
final class EventClassInterfaces {
  private static final String EVENT = "jdk.jfr.Event";
  private static final String NAME = "jdk.jfr.Name";
  private static final String THREAD_INTERFACE = "JfrThread";

  /** Every user event has these, and {@code stackTrace}, which is not generated. */
  private static final List<String> IMPLICIT =
      List.of("startTime", "duration", "eventThread", "stackTrace");

  private final Elements elements;
  private final Types types;
  private final Filer filer;
  private final Messager messager;
  // Packages whose JfrThread interface this compilation has written or found
  private final Set<String> threadInterfaces = new HashSet<>();
  // Interfaces this compilation has written: the Filer refuses a second one, less helpfully
  private final Set<String> written = new HashSet<>();
  // Event classes whose superclass does not resolve yet: another processor may generate it
  private final Set<String> deferred = new HashSet<>();
  // The JFR names of the events whose interfaces were written, for the processor's layouts
  private final Set<String> eventNames = new HashSet<>();
  // Types the compilation's own sources declare (root elements of every round so far)
  private final Set<String> sourceTypes = new HashSet<>();

  EventClassInterfaces(ProcessingEnvironment env) {
    this.elements = env.getElementUtils();
    this.types = env.getTypeUtils();
    this.filer = env.getFiler();
    this.messager = env.getMessager();
  }

  /** The JFR names of the events whose interfaces this compilation wrote. */
  Set<String> eventNames() {
    return eventNames;
  }

  /**
   * Writes the interfaces for {@code annotated}, and for the classes deferred in earlier rounds. In
   * the last round a class still deferred is an error.
   */
  void process(Set<? extends Element> annotated, Set<? extends Element> roots, boolean lastRound) {
    for (Element root : roots) {
      if (root instanceof TypeElement t) {
        sourceTypes.add(t.getQualifiedName().toString());
      }
    }
    List<Element> todo = new ArrayList<>(annotated);
    for (String name : List.copyOf(deferred)) {
      deferred.remove(name);
      TypeElement type = elements.getTypeElement(name);
      if (type != null) {
        todo.add(type);
      }
    }
    for (Element element : todo) {
      try {
        generate(element, lastRound);
      } catch (IOException e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Failed to generate the @JfrType interface: " + e.getMessage(),
            element);
      }
    }
  }

  private void generate(Element element, boolean lastRound) throws IOException {
    TypeElement eventType = elements.getTypeElement(EVENT);
    if (element.getKind() != ElementKind.CLASS) {
      error(element, "@GenerateJfrType goes on a class that extends " + EVENT);
      return;
    }
    TypeElement type = (TypeElement) element;
    if (unresolvedSuperclass(type)) {
      if (lastRound) {
        error(element, "@GenerateJfrType: a superclass of " + type + " does not resolve");
      } else {
        deferred.add(type.getQualifiedName().toString());
      }
      return;
    }
    if (eventType == null || !types.isSubtype(types.erasure(type.asType()), eventType.asType())) {
      error(element, "@GenerateJfrType goes on a class that extends " + EVENT);
      return;
    }
    if (type.getModifiers().contains(Modifier.ABSTRACT)) {
      error(
          element,
          "@GenerateJfrType on an abstract class: JFR records only concrete event classes;"
              + " annotate the class that is committed");
      return;
    }
    String pkg = elements.getPackageOf(type).getQualifiedName().toString();
    String interfaceName = interfaceName(type);
    if (!isIdentifier(interfaceName)) {
      error(element, "@GenerateJfrType(\"" + interfaceName + "\") is not a Java type name");
      return;
    }
    String eventName = eventName(type);
    String qualified = qualify(pkg, interfaceName);
    if (interfaceName.equals(THREAD_INTERFACE) || written.contains(qualified)) {
      error(element, "@GenerateJfrType: " + qualified + " is taken; name the interface");
      return;
    }
    TypeElement existing = elements.getTypeElement(qualified);
    if (existing != null
        && (sourceTypes.contains(qualified) || !isOwnOutput(existing, eventName))) {
      error(element, "@GenerateJfrType: " + qualified + " exists already; name the interface");
      return;
    }

    // Recorded field name -> accessor return type, in the order JFR lays them out: the implicit
    // fields, then superclass fields before the class's own
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("startTime", "long");
    fields.put("duration", "long");
    fields.put("eventThread", THREAD_INTERFACE);
    List<TypeElement> chain = new ArrayList<>();
    for (TypeElement t = type;
        t != null && !t.getQualifiedName().contentEquals(EVENT);
        t = superclass(t)) {
      chain.add(0, t);
    }
    boolean clash = false;
    for (TypeElement t : chain) {
      for (Element member : t.getEnclosedElements()) {
        if (member.getKind() != ElementKind.FIELD) {
          continue;
        }
        VariableElement field = (VariableElement) member;
        Set<Modifier> modifiers = field.getModifiers();
        if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.TRANSIENT)) {
          continue;
        }
        if (t != type && modifiers.contains(Modifier.PRIVATE)) {
          continue; // JFR records a superclass's private fields only for the class itself
        }
        String name = fieldName(field);
        String javaType = javaType(field.asType());
        if (javaType == null) {
          messager.printMessage(
              Diagnostic.Kind.NOTE,
              "@GenerateJfrType: field '"
                  + field.getSimpleName()
                  + "' ("
                  + field.asType()
                  + ") is left out of "
                  + interfaceName
                  + (isClass(field.asType())
                      ? ": JFR records it, but no interface is generated for java.lang.Class;"
                          + " declare one by hand"
                      : ": JFR does not record a field of this type"),
              field);
          continue;
        }
        if (fields.containsKey(name) || IMPLICIT.contains(name)) {
          error(
              field,
              "@GenerateJfrType: "
                  + type
                  + " records two fields named '"
                  + name
                  + "'"
                  + (IMPLICIT.contains(name) ? " (every event has one)" : "")
                  + "; give one another name with @jdk.jfr.Name");
          clash = true;
          continue;
        }
        fields.put(name, javaType);
      }
    }
    if (clash || !threadInterface(pkg, element)) {
      return;
    }
    // Written also over an earlier build's output (found on the class path or source path): its
    // accessors may be stale, and only a written source gets its handler and service entry
    writeInterface(pkg, interfaceName, eventName, type, fields);
    written.add(qualified);
    eventNames.add(eventName);
  }

  /**
   * Whether {@code existing}, not one of this compilation's sources, is this processor's interface
   * for the event from a build that did not clean first (on the class path or source path): then it
   * is written again.
   */
  private static boolean isOwnOutput(TypeElement existing, String eventName) {
    JfrType jfrType = existing.getAnnotation(JfrType.class);
    return existing.getKind() == ElementKind.INTERFACE
        && jfrType != null
        && jfrType.value().equals(eventName);
  }

  private boolean unresolvedSuperclass(TypeElement type) {
    for (TypeMirror sup = type.getSuperclass();
        sup.getKind() != TypeKind.NONE;
        sup = ((TypeElement) ((DeclaredType) sup).asElement()).getSuperclass()) {
      if (sup.getKind() == TypeKind.ERROR) {
        return true;
      }
      if (sup.getKind() != TypeKind.DECLARED) {
        return false;
      }
    }
    return false;
  }

  private String interfaceName(TypeElement type) {
    GenerateJfrType annotation = type.getAnnotation(GenerateJfrType.class);
    if (annotation != null && !annotation.value().isEmpty()) {
      return annotation.value();
    }
    StringBuilder name = new StringBuilder();
    for (Element e = type; e instanceof TypeElement; e = e.getEnclosingElement()) {
      name.insert(0, e.getSimpleName());
    }
    return name.append("Jfr").toString();
  }

  /**
   * The event's name, as JFR gives it: the class's {@code jdk.jfr.Name} when each of its
   * dot-separated parts is a Java identifier, else the binary name (JFR warns and does the same).
   */
  private String eventName(TypeElement type) {
    String binary = elements.getBinaryName(type).toString();
    String named = annotatedName(type);
    if (named == null) {
      return binary;
    }
    for (String part : named.split("\\.", -1)) {
      if (!isIdentifier(part)) {
        messager.printMessage(
            Diagnostic.Kind.WARNING,
            "@GenerateJfrType: @Name(\""
                + named
                + "\") is not a valid type name, so JFR names the event "
                + binary
                + "; so does the generated interface",
            type);
        return binary;
      }
    }
    return named;
  }

  /**
   * A field's recorded name: its {@code jdk.jfr.Name} when that is a Java identifier, else its own
   * name (JFR warns and does the same).
   */
  private String fieldName(VariableElement field) {
    String declared = field.getSimpleName().toString();
    String named = annotatedName(field);
    if (named == null) {
      return declared;
    }
    if (!isIdentifier(named)) {
      messager.printMessage(
          Diagnostic.Kind.WARNING,
          "@GenerateJfrType: @Name(\""
              + named
              + "\") is not a Java identifier, so JFR records the field as '"
              + declared
              + "'; so does the generated interface",
          field);
      return declared;
    }
    return named;
  }

  /**
   * As {@code jdk.internal.module.Checks.isJavaIdentifier}: not a keyword, true, false, null or _.
   */
  private static boolean isIdentifier(String s) {
    return SourceVersion.isIdentifier(s) && !SourceVersion.isKeyword(s) && !s.equals("_");
  }

  private TypeElement superclass(TypeElement type) {
    TypeMirror sup = type.getSuperclass();
    return sup.getKind() == TypeKind.DECLARED
        ? (TypeElement) ((DeclaredType) sup).asElement()
        : null;
  }

  /** The {@code @jdk.jfr.Name} value on {@code element}, or null. */
  private static String annotatedName(Element element) {
    for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
      if (((TypeElement) mirror.getAnnotationType().asElement())
          .getQualifiedName()
          .contentEquals(NAME)) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
            mirror.getElementValues().entrySet()) {
          if (e.getKey().getSimpleName().contentEquals("value")) {
            return String.valueOf(e.getValue().getValue());
          }
        }
      }
    }
    return null;
  }

  private static boolean isClass(TypeMirror type) {
    return type.getKind() == TypeKind.DECLARED
        && ((TypeElement) ((DeclaredType) type).asElement())
            .getQualifiedName()
            .contentEquals("java.lang.Class");
  }

  /** The accessor type for a field of type {@code type}, or null when it is not generated. */
  private static String javaType(TypeMirror type) {
    TypeKind kind = type.getKind();
    if (kind.isPrimitive()) {
      // The kind, not the mirror's text: that carries type annotations ("@Unit long")
      return kind.name().toLowerCase(Locale.ROOT);
    }
    if (kind != TypeKind.DECLARED) {
      return null;
    }
    String name = ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
    if (name.equals("java.lang.String")) {
      return "String";
    }
    if (name.equals("java.lang.Thread")) {
      return THREAD_INTERFACE;
    }
    return null;
  }

  /**
   * Makes sure {@code pkg} has the {@code JfrThread} interface: the package's own, when one of the
   * compilation's sources declares it for {@code java.lang.Thread}; else written once, also over an
   * earlier build's (whose handler would not be registered again otherwise).
   */
  private boolean threadInterface(String pkg, Element element) throws IOException {
    if (threadInterfaces.contains(pkg)) {
      return true;
    }
    String qualified = qualify(pkg, THREAD_INTERFACE);
    TypeElement existing = elements.getTypeElement(qualified);
    if (existing != null) {
      JfrType jfrType = existing.getAnnotation(JfrType.class);
      boolean threadInterface =
          existing.getKind() == ElementKind.INTERFACE
              && jfrType != null
              && jfrType.value().equals("java.lang.Thread");
      if (threadInterface && !sourceTypes.contains(qualified)) {
        return writeThreadInterface(pkg, qualified, element);
      }
      if (!threadInterface) {
        error(
            element,
            "@GenerateJfrType: "
                + qualified
                + " exists and is not a @JfrType(\"java.lang.Thread\") interface");
        return false;
      }
      threadInterfaces.add(pkg);
      return true;
    }
    return writeThreadInterface(pkg, qualified, element);
  }

  private boolean writeThreadInterface(String pkg, String qualified, Element element)
      throws IOException {
    try (Writer w = filer.createSourceFile(qualified, element).openWriter()) {
      w.write(packageLine(pkg));
      w.write("/**\n");
      w.write(" * {@code java.lang.Thread}, the thread fields of the event interfaces\n");
      w.write(
          " * {@code @GenerateJfrType} generated in this package. Generated by jafar-processor.\n");
      w.write(" */\n");
      w.write("@io.jafar.parser.api.JfrType(\"java.lang.Thread\")\n");
      w.write("public interface " + THREAD_INTERFACE + " {\n");
      w.write("  String osName();\n\n  long osThreadId();\n\n");
      w.write("  String javaName();\n\n  long javaThreadId();\n}\n");
    }
    threadInterfaces.add(pkg);
    return true;
  }

  private void writeInterface(
      String pkg, String name, String eventName, TypeElement type, Map<String, String> fields)
      throws IOException {
    // eventName and the field names are valid Java names by now (eventName, fieldName), so they
    // need no escaping in the comment or the string literal
    try (Writer w = filer.createSourceFile(qualify(pkg, name), type).openWriter()) {
      w.write(packageLine(pkg));
      w.write("/**\n");
      w.write(" * The JFR event {@code " + eventName + "}, as {@link ");
      w.write(type.getQualifiedName().toString());
      w.write(
          "} records it.\n * Generated by jafar-processor from its {@code @GenerateJfrType}.\n */\n");
      w.write("@io.jafar.parser.api.JfrType(\"" + eventName + "\")\n");
      w.write("public interface " + name + " {\n");
      boolean first = true;
      for (Map.Entry<String, String> field : fields.entrySet()) {
        if (!first) {
          w.write("\n");
        }
        first = false;
        w.write("  " + field.getValue() + " " + field.getKey() + "();\n");
      }
      w.write("}\n");
    }
  }

  private static String qualify(String pkg, String simpleName) {
    return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
  }

  private static String packageLine(String pkg) {
    return pkg.isEmpty() ? "" : "package " + pkg + ";\n\n";
  }

  private void error(Element element, String message) {
    messager.printMessage(Diagnostic.Kind.ERROR, message, element);
  }
}
