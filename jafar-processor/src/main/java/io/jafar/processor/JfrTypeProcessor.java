package io.jafar.processor;

import com.google.auto.service.AutoService;
import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrIgnore;
import io.jafar.parser.api.JfrType;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Annotation processor that generates handler implementations and factories for {@link JfrType}
 * annotated interfaces.
 *
 * <p>For each interface annotated with {@code @JfrType}, this processor generates:
 *
 * <ul>
 *   <li>A handler implementation class that implements the interface
 *   <li>A handler factory class with thread-local caching for reduced allocations
 * </ul>
 */
@AutoService(Processor.class)
public class JfrTypeProcessor extends AbstractProcessor {

  private Filer filer;
  private Messager messager;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.filer = processingEnv.getFiler();
    this.messager = processingEnv.getMessager();
  }

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    Set<String> annotations = new HashSet<>();
    annotations.add(JfrType.class.getCanonicalName());
    return annotations;
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    for (Element element : roundEnv.getElementsAnnotatedWith(JfrType.class)) {
      if (element.getKind() != ElementKind.INTERFACE) {
        messager.printMessage(
            Diagnostic.Kind.ERROR, "@JfrType can only be applied to interfaces", element);
        continue;
      }

      TypeElement typeElement = (TypeElement) element;

      // Skip nested/inner classes to avoid package conflicts
      if (typeElement.getNestingKind().isNested()) {
        messager.printMessage(
            Diagnostic.Kind.NOTE,
            "Skipping nested interface "
                + typeElement.getQualifiedName()
                + " - use top-level interfaces for build-time generation",
            element);
        continue;
      }

      try {
        generateHandler(typeElement);
        generateFactory(typeElement);
      } catch (IOException e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR, "Failed to generate handler: " + e.getMessage(), element);
      }
    }
    return true;
  }

  private void generateHandler(TypeElement interfaceElement) throws IOException {
    String packageName = getPackageName(interfaceElement);
    String interfaceName = interfaceElement.getSimpleName().toString();
    String handlerName = interfaceName + "Handler";
    String qualifiedName = packageName.isEmpty() ? handlerName : packageName + "." + handlerName;

    JfrType jfrType = interfaceElement.getAnnotation(JfrType.class);
    String jfrTypeName = jfrType.value();

    List<FieldInfo> fields = extractFields(interfaceElement);

    JavaFileObject sourceFile = filer.createSourceFile(qualifiedName, interfaceElement);
    try (PrintWriter out = new PrintWriter(sourceFile.openWriter())) {
      // Package declaration
      if (!packageName.isEmpty()) {
        out.println("package " + packageName + ";");
        out.println();
      }

      // Imports
      out.println("import io.jafar.parser.api.ConstantPools;");
      out.println("import io.jafar.parser.api.MetadataLookup;");
      out.println("import io.jafar.parser.internal_api.RecordingStream;");
      out.println("import io.jafar.parser.internal_api.TypeSkipper;");
      out.println("import io.jafar.parser.internal_api.metadata.MetadataClass;");
      out.println("import io.jafar.parser.internal_api.metadata.MetadataField;");
      out.println("import java.io.IOException;");
      out.println("import java.util.List;");
      out.println();

      // Class declaration - no @JfrType annotation on impl to avoid re-processing
      out.println("/**");
      out.println(" * Generated handler implementation for {@link " + interfaceName + "}.");
      out.println(" * JFR type: " + jfrTypeName);
      out.println(" */");
      out.println("public final class " + handlerName + " implements " + interfaceName + " {");
      out.println();

      // Static type ID fields for constant pool resolution
      Set<String> cpTypes = new HashSet<>();
      for (FieldInfo field : fields) {
        if (field.needsConstantPool) {
          cpTypes.add(field.cpTypeName);
        }
      }
      for (String cpType : cpTypes) {
        String fieldName = cpTypeIdFieldName(cpType);
        out.println("  /** Type ID for " + cpType + " constant pool. Bound at runtime. */");
        out.println("  private static volatile long " + fieldName + " = -1L;");
        out.println();
      }

      // Instance fields
      out.println("  // Instance fields for event data");
      out.println("  private ConstantPools constantPools;");
      for (FieldInfo field : fields) {
        if (field.needsConstantPool) {
          out.println("  private long " + field.fieldName + "_cpRef;");
        } else {
          out.println("  private " + field.javaType + " " + field.fieldName + ";");
        }
      }
      out.println();

      // Bind method
      out.println("  /**");
      out.println("   * Binds type IDs from the recording metadata.");
      out.println("   * Must be called before using handlers with a new recording.");
      out.println("   */");
      out.println("  public static void bind(MetadataLookup metadata) {");
      for (String cpType : cpTypes) {
        String fieldName = cpTypeIdFieldName(cpType);
        out.println(
            "    MetadataClass "
                + sanitizeVarName(cpType)
                + "Class = metadata.getClass(\""
                + cpType
                + "\");");
        out.println("    if (" + sanitizeVarName(cpType) + "Class != null) {");
        out.println("      " + fieldName + " = " + sanitizeVarName(cpType) + "Class.getId();");
        out.println("    }");
      }
      out.println("  }");
      out.println();

      // Read method
      out.println("  /**");
      out.println("   * Reads event data from the stream.");
      out.println("   * @param stream the recording stream positioned at event data");
      out.println("   * @param metadata the metadata class for this event type");
      out.println("   * @param constantPools the constant pools for resolving references");
      out.println("   * @throws IOException if reading fails");
      out.println("   */");
      out.println(
          "  public void read(RecordingStream stream, MetadataClass metadata, ConstantPools constantPools) throws IOException {");
      out.println("    this.constantPools = constantPools;");
      out.println("    List<MetadataField> metaFields = metadata.getFields();");
      out.println("    for (MetadataField metaField : metaFields) {");
      out.println("      String fieldName = metaField.getName();");
      out.println("      switch (fieldName) {");

      for (FieldInfo field : fields) {
        out.println("        case \"" + field.jfrFieldName + "\":");
        if (field.needsConstantPool) {
          out.println("          this." + field.fieldName + "_cpRef = stream.readVarint();");
        } else {
          out.println("          this." + field.fieldName + " = " + generateReadCode(field) + ";");
        }
        out.println("          break;");
      }

      out.println("        default:");
      out.println("          TypeSkipper.skip(metaField, stream);");
      out.println("          break;");
      out.println("      }");
      out.println("    }");
      out.println("  }");
      out.println();

      // Reset method for reuse
      out.println("  /** Resets instance fields for reuse. */");
      out.println("  public void reset() {");
      out.println("    this.constantPools = null;");
      for (FieldInfo field : fields) {
        if (field.needsConstantPool) {
          out.println("    this." + field.fieldName + "_cpRef = 0L;");
        } else if (field.isPrimitive) {
          out.println(
              "    this." + field.fieldName + " = " + getDefaultValue(field.javaType) + ";");
        } else {
          out.println("    this." + field.fieldName + " = null;");
        }
      }
      out.println("  }");
      out.println();

      // Getter methods (interface implementation)
      for (FieldInfo field : fields) {
        out.println("  @Override");
        out.println("  public " + field.returnType + " " + field.methodName + "() {");
        if (field.needsConstantPool) {
          String cpTypeIdField = cpTypeIdFieldName(field.cpTypeName);
          out.println("    if (" + cpTypeIdField + " == -1L || constantPools == null) {");
          out.println("      return null;");
          out.println("    }");
          out.println(
              "    return ("
                  + field.returnType
                  + ") constantPools.getConstantPool("
                  + cpTypeIdField
                  + ").get("
                  + field.fieldName
                  + "_cpRef);");
        } else {
          out.println("    return this." + field.fieldName + ";");
        }
        out.println("  }");
        out.println();
      }

      out.println("}");
    }
  }

  private void generateFactory(TypeElement interfaceElement) throws IOException {
    String packageName = getPackageName(interfaceElement);
    String interfaceName = interfaceElement.getSimpleName().toString();
    String handlerName = interfaceName + "Handler";
    String factoryName = interfaceName + "Factory";
    String qualifiedName = packageName.isEmpty() ? factoryName : packageName + "." + factoryName;

    JfrType jfrType = interfaceElement.getAnnotation(JfrType.class);
    String jfrTypeName = jfrType.value();

    JavaFileObject sourceFile = filer.createSourceFile(qualifiedName, interfaceElement);
    try (PrintWriter out = new PrintWriter(sourceFile.openWriter())) {
      // Package declaration
      if (!packageName.isEmpty()) {
        out.println("package " + packageName + ";");
        out.println();
      }

      // Imports
      out.println("import io.jafar.parser.api.ConstantPools;");
      out.println("import io.jafar.parser.api.HandlerFactory;");
      out.println("import io.jafar.parser.api.MetadataLookup;");
      out.println("import io.jafar.parser.internal_api.RecordingStream;");
      out.println("import io.jafar.parser.internal_api.metadata.MetadataClass;");
      out.println();

      // Class declaration - implements HandlerFactory<InterfaceType>
      out.println("/**");
      out.println(" * Generated factory for {@link " + handlerName + "}.");
      out.println(" * Provides thread-local cached handler instances for reduced allocations.");
      out.println(" * JFR type: " + jfrTypeName);
      out.println(" */");
      out.println(
          "public final class "
              + factoryName
              + " implements HandlerFactory<"
              + interfaceName
              + "> {");
      out.println();

      // Thread-local cache - instance field for thread safety
      out.println("  /** Thread-local cache for handler instances. */");
      out.println("  private final ThreadLocal<" + handlerName + "> cache =");
      out.println("      ThreadLocal.withInitial(" + handlerName + "::new);");
      out.println();

      // Public constructor
      out.println("  /** Creates a new factory instance. */");
      out.println("  public " + factoryName + "() {}");
      out.println();

      // Bind method (implements HandlerFactory)
      out.println("  /**");
      out.println("   * {@inheritDoc}");
      out.println("   */");
      out.println("  @Override");
      out.println("  public void bind(MetadataLookup metadata) {");
      out.println("    " + handlerName + ".bind(metadata);");
      out.println("  }");
      out.println();

      // Get method (implements HandlerFactory)
      out.println("  /**");
      out.println("   * {@inheritDoc}");
      out.println("   */");
      out.println("  @Override");
      out.println(
          "  public "
              + interfaceName
              + " get(RecordingStream stream, MetadataClass metadata, ConstantPools constantPools) {");
      out.println("    " + handlerName + " handler = cache.get();");
      out.println("    handler.reset();");
      out.println("    try {");
      out.println("      handler.read(stream, metadata, constantPools);");
      out.println("    } catch (java.io.IOException e) {");
      out.println("      throw new RuntimeException(\"Failed to read event data\", e);");
      out.println("    }");
      out.println("    return handler;");
      out.println("  }");
      out.println();

      // Get JFR type name (implements HandlerFactory)
      out.println("  /**");
      out.println("   * {@inheritDoc}");
      out.println("   */");
      out.println("  @Override");
      out.println("  public String getJfrTypeName() {");
      out.println("    return \"" + jfrTypeName + "\";");
      out.println("  }");
      out.println();

      // Get interface class (implements HandlerFactory)
      out.println("  /**");
      out.println("   * {@inheritDoc}");
      out.println("   */");
      out.println("  @Override");
      out.println("  @SuppressWarnings(\"unchecked\")");
      out.println("  public Class<" + interfaceName + "> getInterfaceClass() {");
      out.println("    return " + interfaceName + ".class;");
      out.println("  }");
      out.println();

      out.println("}");
    }
  }

  private List<FieldInfo> extractFields(TypeElement interfaceElement) {
    List<FieldInfo> fields = new ArrayList<>();

    for (Element enclosed : interfaceElement.getEnclosedElements()) {
      if (enclosed.getKind() != ElementKind.METHOD) {
        continue;
      }

      ExecutableElement method = (ExecutableElement) enclosed;

      // Skip methods with @JfrIgnore
      if (method.getAnnotation(JfrIgnore.class) != null) {
        continue;
      }

      // Skip default methods
      if (method.getModifiers().contains(Modifier.DEFAULT)) {
        continue;
      }

      // Skip methods with parameters
      if (!method.getParameters().isEmpty()) {
        continue;
      }

      // Skip static methods
      if (method.getModifiers().contains(Modifier.STATIC)) {
        continue;
      }

      String methodName = method.getSimpleName().toString();
      TypeMirror returnType = method.getReturnType();

      // Determine JFR field name
      JfrField jfrField = method.getAnnotation(JfrField.class);
      String jfrFieldName = jfrField != null ? jfrField.value() : methodName;
      boolean isRaw = jfrField != null && jfrField.raw();

      FieldInfo field = new FieldInfo();
      field.methodName = methodName;
      field.fieldName = methodName;
      field.jfrFieldName = jfrFieldName;
      field.returnType = returnType.toString();
      field.isRaw = isRaw;

      // Determine if this needs constant pool resolution
      analyzeType(returnType, field);

      fields.add(field);
    }

    return fields;
  }

  private void analyzeType(TypeMirror type, FieldInfo field) {
    TypeKind kind = type.getKind();

    if (kind.isPrimitive()) {
      field.isPrimitive = true;
      field.needsConstantPool = false;
      field.javaType = type.toString();
    } else if (kind == TypeKind.ARRAY) {
      ArrayType arrayType = (ArrayType) type;
      TypeMirror componentType = arrayType.getComponentType();
      field.isArray = true;
      if (componentType.getKind().isPrimitive()) {
        field.needsConstantPool = false;
        field.javaType = type.toString();
      } else {
        // Array of complex types - needs constant pool
        field.needsConstantPool = true;
        field.cpTypeName = getJfrTypeName(componentType);
        field.javaType = "long"; // Store CP ref
      }
    } else if (kind == TypeKind.DECLARED) {
      DeclaredType declaredType = (DeclaredType) type;
      TypeElement typeElement = (TypeElement) declaredType.asElement();
      String qualifiedName = typeElement.getQualifiedName().toString();

      // Check for primitive wrapper types and String
      if (qualifiedName.equals("java.lang.String")) {
        field.needsConstantPool = false;
        field.javaType = "String";
      } else if (qualifiedName.equals("java.lang.Long")
          || qualifiedName.equals("java.lang.Integer")
          || qualifiedName.equals("java.lang.Short")
          || qualifiedName.equals("java.lang.Byte")
          || qualifiedName.equals("java.lang.Boolean")
          || qualifiedName.equals("java.lang.Character")
          || qualifiedName.equals("java.lang.Float")
          || qualifiedName.equals("java.lang.Double")) {
        field.needsConstantPool = false;
        field.javaType = qualifiedName;
      } else {
        // Complex type - needs constant pool
        field.needsConstantPool = true;
        field.cpTypeName = getJfrTypeName(type);
        field.javaType = "long"; // Store CP ref
      }
    }
  }

  private String getJfrTypeName(TypeMirror type) {
    if (type.getKind() == TypeKind.DECLARED) {
      DeclaredType declaredType = (DeclaredType) type;
      TypeElement typeElement = (TypeElement) declaredType.asElement();
      JfrType jfrType = typeElement.getAnnotation(JfrType.class);
      if (jfrType != null) {
        return jfrType.value();
      }
      // Fallback to qualified name
      return typeElement.getQualifiedName().toString();
    }
    return type.toString();
  }

  private String getPackageName(TypeElement typeElement) {
    String qualifiedName = typeElement.getQualifiedName().toString();
    int lastDot = qualifiedName.lastIndexOf('.');
    return lastDot > 0 ? qualifiedName.substring(0, lastDot) : "";
  }

  private String cpTypeIdFieldName(String jfrTypeName) {
    // Convert jdk.types.StackTrace -> STACKTRACE_TYPE_ID
    String simpleName = jfrTypeName.substring(jfrTypeName.lastIndexOf('.') + 1);
    return simpleName.toUpperCase() + "_TYPE_ID";
  }

  private String sanitizeVarName(String name) {
    return name.replace('.', '_').replace('$', '_');
  }

  private String generateReadCode(FieldInfo field) {
    // Note: RecordingStream uses readVarint() for all varint-encoded integers
    if (field.javaType.equals("long")) {
      return "stream.readVarint()";
    } else if (field.javaType.equals("int")) {
      return "(int) stream.readVarint()";
    } else if (field.javaType.equals("short")) {
      return "(short) stream.readVarint()";
    } else if (field.javaType.equals("byte")) {
      return "(byte) stream.readVarint()";
    } else if (field.javaType.equals("boolean")) {
      return "stream.readBoolean()";
    } else if (field.javaType.equals("char")) {
      return "(char) stream.readVarint()";
    } else if (field.javaType.equals("float")) {
      return "stream.readFloat()";
    } else if (field.javaType.equals("double")) {
      return "stream.readDouble()";
    } else if (field.javaType.equals("String")) {
      return "stream.readUTF8()";
    } else {
      // Complex type - should be handled by constant pool path
      return "null /* unsupported type: " + field.javaType + " */";
    }
  }

  private String getDefaultValue(String type) {
    switch (type) {
      case "long":
        return "0L";
      case "int":
        return "0";
      case "short":
        return "(short) 0";
      case "byte":
        return "(byte) 0";
      case "boolean":
        return "false";
      case "char":
        return "'\\0'";
      case "float":
        return "0.0f";
      case "double":
        return "0.0";
      default:
        return "null";
    }
  }

  /** Information about a field in the interface. */
  private static class FieldInfo {
    String methodName;
    String fieldName;
    String jfrFieldName;
    String returnType;
    String javaType;
    boolean isPrimitive;
    boolean isArray;
    boolean needsConstantPool;
    String cpTypeName;
    boolean isRaw;
  }
}
