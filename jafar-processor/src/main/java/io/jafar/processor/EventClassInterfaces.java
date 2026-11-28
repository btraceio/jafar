package io.jafar.processor;

import io.jafar.parser.api.GenerateJfrType;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>Which fields: those JFR records for the class (see {@code jdk.jfr.Event}): non-static,
 * non-transient fields of the class and its superclasses below {@code jdk.jfr.Event}, of a
 * primitive type, {@code String} or {@code Thread}, named by {@code @jdk.jfr.Name} or else as
 * declared; plus the implicit {@code startTime}, {@code duration} and {@code eventThread}.
 */
final class EventClassInterfaces {
  private static final String EVENT = "jdk.jfr.Event";
  private static final String NAME = "jdk.jfr.Name";
  private static final String THREAD_INTERFACE = "JfrThread";

  private final Elements elements;
  private final Types types;
  private final Filer filer;
  private final Messager messager;
  // Packages whose JfrThread interface this compilation has written or found
  private final Set<String> threadInterfaces = new HashSet<>();

  EventClassInterfaces(ProcessingEnvironment env) {
    this.elements = env.getElementUtils();
    this.types = env.getTypeUtils();
    this.filer = env.getFiler();
    this.messager = env.getMessager();
  }

  void process(Set<? extends Element> annotated) {
    for (Element element : annotated) {
      try {
        generate(element);
      } catch (IOException e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Failed to generate the @JfrType interface: " + e.getMessage(),
            element);
      }
    }
  }

  private void generate(Element element) throws IOException {
    TypeElement eventType = elements.getTypeElement(EVENT);
    if (element.getKind() != ElementKind.CLASS) {
      error(element, "@GenerateJfrType goes on a class that extends " + EVENT);
      return;
    }
    TypeElement type = (TypeElement) element;
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
    if (!SourceVersion.isIdentifier(interfaceName) || SourceVersion.isKeyword(interfaceName)) {
      error(element, "@GenerateJfrType(\"" + interfaceName + "\") is not a Java type name");
      return;
    }
    String qualified = pkg.isEmpty() ? interfaceName : pkg + "." + interfaceName;
    if (elements.getTypeElement(qualified) != null) {
      error(element, "@GenerateJfrType: " + qualified + " exists already; name the interface");
      return;
    }

    // Field name -> accessor return type, in the order JFR lays them out: implicit fields, then
    // superclass fields before the class's own
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("startTime", "long");
    fields.put("duration", "long");
    fields.put("eventThread", THREAD_INTERFACE); // so the package needs JfrThread
    List<TypeElement> chain = new ArrayList<>();
    for (TypeElement t = type;
        t != null && !t.getQualifiedName().contentEquals(EVENT);
        t = superclass(t)) {
      chain.add(0, t);
    }
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
        String name = jfrName(field, field.getSimpleName().toString());
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
                  + ": JFR does not record its type, or it needs an interface of its own",
              field);
          continue;
        }
        fields.put(name, javaType);
      }
    }
    if (!threadInterface(pkg, element)) {
      return;
    }
    writeInterface(
        pkg, interfaceName, jfrName(type, elements.getBinaryName(type).toString()), type, fields);
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

  private TypeElement superclass(TypeElement type) {
    TypeMirror sup = type.getSuperclass();
    return sup.getKind() == TypeKind.DECLARED
        ? (TypeElement) ((DeclaredType) sup).asElement()
        : null;
  }

  /** The {@code @jdk.jfr.Name} value on {@code element}, else {@code fallback}. */
  private static String jfrName(Element element, String fallback) {
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
    return fallback;
  }

  /** The accessor type for a field of type {@code type}, or null when it is not generated. */
  private static String javaType(TypeMirror type) {
    switch (type.getKind()) {
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case CHAR:
      case FLOAT:
      case DOUBLE:
        return type.toString();
      case DECLARED:
        String name =
            ((TypeElement) ((DeclaredType) type).asElement()).getQualifiedName().toString();
        if (name.equals("java.lang.String")) {
          return "String";
        }
        if (name.equals("java.lang.Thread")) {
          return THREAD_INTERFACE;
        }
        return null;
      default:
        return null;
    }
  }

  /**
   * Makes sure {@code pkg} has the {@code JfrThread} interface: written once per package, or one
   * the package already has for {@code java.lang.Thread}.
   */
  private boolean threadInterface(String pkg, Element element) throws IOException {
    if (threadInterfaces.contains(pkg)) {
      return true;
    }
    String qualified = pkg.isEmpty() ? THREAD_INTERFACE : pkg + "." + THREAD_INTERFACE;
    TypeElement existing = elements.getTypeElement(qualified);
    if (existing != null) {
      io.jafar.parser.api.JfrType jfrType =
          existing.getAnnotation(io.jafar.parser.api.JfrType.class);
      if (existing.getKind() != ElementKind.INTERFACE
          || jfrType == null
          || !jfrType.value().equals("java.lang.Thread")) {
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
    String qualified = pkg.isEmpty() ? name : pkg + "." + name;
    try (Writer w = filer.createSourceFile(qualified, type).openWriter()) {
      w.write(packageLine(pkg));
      w.write("/**\n");
      w.write(" * The JFR event {@code " + eventName + "}, as {@link ");
      w.write(type.getQualifiedName().toString());
      w.write(
          "} records it.\n * Generated by jafar-processor from its {@code @GenerateJfrType}.\n */\n");
      w.write("@io.jafar.parser.api.JfrType(\"" + escape(eventName) + "\")\n");
      w.write("public interface " + name + " {\n");
      boolean first = true;
      for (Map.Entry<String, String> field : fields.entrySet()) {
        if (!first) {
          w.write("\n");
        }
        first = false;
        String accessor = field.getKey();
        if (!SourceVersion.isIdentifier(accessor) || SourceVersion.isKeyword(accessor)) {
          // A recorded name that is not a Java method name: map it explicitly
          w.write("  @io.jafar.parser.api.JfrField(\"" + escape(accessor) + "\")\n");
          accessor = "field_" + accessor.replaceAll("[^A-Za-z0-9_]", "_");
        }
        w.write("  " + field.getValue() + " " + accessor + "();\n");
      }
      w.write("}\n");
    }
  }

  private static String packageLine(String pkg) {
    return pkg.isEmpty() ? "" : "package " + pkg + ";\n\n";
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private void error(Element element, String message) {
    messager.printMessage(Diagnostic.Kind.ERROR, message, element);
  }
}
