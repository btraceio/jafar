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

  // Inline templates for code generation snippets
  private static final String STATIC_FIELD_TEMPLATE =
      "  /** Type ID for %s constant pool. Bound at runtime. */\n"
          + "  private static volatile long %s = -1L;\n\n";

  private static final String INSTANCE_FIELD_CP_TEMPLATE = "  private long %s_cpRef;\n";
  private static final String INSTANCE_FIELD_CP_ARRAY_TEMPLATE = "  private long[] %s_cpRef;\n";
  private static final String INSTANCE_FIELD_TEMPLATE = "  private %s %s;\n";

  private static final String BIND_BODY_TEMPLATE =
      "    MetadataClass %sClass = metadata.getClass(\"%s\");\n"
          + "    if (%sClass != null) {\n"
          + "      %s = %sClass.getId();\n"
          + "    }\n";

  private static final String SWITCH_CASE_CP_TEMPLATE =
      "        case \"%s\":\n"
          + "          this.%s_cpRef = stream.readVarint();\n"
          + "          break;\n";

  private static final String SWITCH_CASE_CP_ARRAY_TEMPLATE =
      "        case \"%s\": {\n"
          + "          int count = (int) stream.readVarint();\n"
          + "          this.%s_cpRef = new long[count];\n"
          + "          for (int i = 0; i < count; i++) {\n"
          + "            this.%s_cpRef[i] = stream.readVarint();\n"
          + "          }\n"
          + "          break;\n"
          + "        }\n";

  private static final String SWITCH_CASE_PRIMITIVE_ARRAY_TEMPLATE =
      "        case \"%s\": {\n"
          + "          int count = (int) stream.readVarint();\n"
          + "          this.%s = new %s[count];\n"
          + "          for (int i = 0; i < count; i++) {\n"
          + "            this.%s[i] = %s;\n"
          + "          }\n"
          + "          break;\n"
          + "        }\n";

  private static final String SWITCH_CASE_STRING_ARRAY_TEMPLATE =
      "        case \"%s\": {\n"
          + "          int count = (int) stream.readVarint();\n"
          + "          this.%s = new String[count];\n"
          + "          for (int i = 0; i < count; i++) {\n"
          + "            this.%s[i] = stream.readUTF8();\n"
          + "          }\n"
          + "          break;\n"
          + "        }\n";

  private static final String SWITCH_CASE_TEMPLATE =
      "        case \"%s\":\n" + "          this.%s = %s;\n" + "          break;\n";

  private static final String RESET_FIELD_CP_TEMPLATE = "    this.%s_cpRef = 0L;\n";
  private static final String RESET_FIELD_PRIMITIVE_TEMPLATE = "    this.%s = %s;\n";
  private static final String RESET_FIELD_OBJECT_TEMPLATE = "    this.%s = null;\n";

  private static final String GETTER_CP_TEMPLATE =
      "  @Override\n"
          + "  public %s %s() {\n"
          + "    if (%s == -1L || constantPools == null) {\n"
          + "      return null;\n"
          + "    }\n"
          + "    return (%s) constantPools.getConstantPool(%s).get(%s_cpRef);\n"
          + "  }\n\n";

  private static final String GETTER_CP_ARRAY_TEMPLATE =
      "  @Override\n"
          + "  public %s[] %s() {\n"
          + "    if (%s == -1L || constantPools == null || this.%s_cpRef == null) {\n"
          + "      return null;\n"
          + "    }\n"
          + "    %s[] result = new %s[this.%s_cpRef.length];\n"
          + "    for (int i = 0; i < this.%s_cpRef.length; i++) {\n"
          + "      result[i] = (%s) constantPools.getConstantPool(%s).get(this.%s_cpRef[i]);\n"
          + "    }\n"
          + "    return result;\n"
          + "  }\n\n";

  private static final String GETTER_TEMPLATE =
      "  @Override\n" + "  public %s %s() {\n" + "    return this.%s;\n" + "  }\n\n";

  private Filer filer;
  private Messager messager;
  private final Set<String> generatedFactories = new HashSet<>();
  private TemplateEngine handlerTemplate;
  private TemplateEngine factoryTemplate;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);
    this.filer = processingEnv.getFiler();
    this.messager = processingEnv.getMessager();

    // Load templates
    try {
      this.handlerTemplate = new TemplateEngine("templates/HandlerTemplate.java");
      this.factoryTemplate = new TemplateEngine("templates/FactoryTemplate.java");
    } catch (IOException e) {
      messager.printMessage(Diagnostic.Kind.ERROR, "Failed to load templates: " + e.getMessage());
    }
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
    // Process @JfrType annotated interfaces
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
        String factoryClassName = generateFactory(typeElement);
        generatedFactories.add(factoryClassName);
      } catch (IOException e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR, "Failed to generate handler: " + e.getMessage(), element);
      }
    }

    // In the final processing round, generate ServiceLoader registration
    if (roundEnv.processingOver() && !generatedFactories.isEmpty()) {
      try {
        generateServiceLoaderRegistration();
      } catch (IOException e) {
        messager.printMessage(
            Diagnostic.Kind.ERROR,
            "Failed to generate ServiceLoader registration: " + e.getMessage());
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

    // Generate handler code from template
    String handlerCode =
        handlerTemplate
            .builder()
            .set("PACKAGE", packageName)
            .set("INTERFACE_NAME", interfaceName)
            .set("HANDLER_NAME", handlerName)
            .set("JFR_TYPE_NAME", jfrTypeName)
            .set("STATIC_TYPE_ID_FIELDS", buildStaticTypeIdFields(fields))
            .set("INSTANCE_FIELDS", buildInstanceFields(fields))
            .set("BIND_BODY", buildBindBody(fields))
            .set("SWITCH_CASES", buildSwitchCases(fields))
            .set("RESET_FIELDS", buildResetFields(fields))
            .set("GETTER_METHODS", buildGetterMethods(fields))
            .render();

    // Remove package declaration if in default package
    if (packageName.isEmpty()) {
      handlerCode = handlerCode.replaceFirst("package ;\n\n", "");
    }

    // Write generated code to file
    JavaFileObject sourceFile = filer.createSourceFile(qualifiedName, interfaceElement);
    try (PrintWriter out = new PrintWriter(sourceFile.openWriter())) {
      out.print(handlerCode);
    }
  }

  private String generateFactory(TypeElement interfaceElement) throws IOException {
    String packageName = getPackageName(interfaceElement);
    String interfaceName = interfaceElement.getSimpleName().toString();
    String handlerName = interfaceName + "Handler";
    String factoryName = interfaceName + "Factory";
    String qualifiedName = packageName.isEmpty() ? factoryName : packageName + "." + factoryName;

    JfrType jfrType = interfaceElement.getAnnotation(JfrType.class);
    String jfrTypeName = jfrType.value();

    // Generate factory code from template
    String factoryCode =
        factoryTemplate
            .builder()
            .set("PACKAGE", packageName)
            .set("INTERFACE_NAME", interfaceName)
            .set("HANDLER_NAME", handlerName)
            .set("FACTORY_NAME", factoryName)
            .set("JFR_TYPE_NAME", jfrTypeName)
            .render();

    // Remove package declaration if in default package
    if (packageName.isEmpty()) {
      factoryCode = factoryCode.replaceFirst("package ;\n\n", "");
    }

    // Write generated code to file
    JavaFileObject sourceFile = filer.createSourceFile(qualifiedName, interfaceElement);
    try (PrintWriter out = new PrintWriter(sourceFile.openWriter())) {
      out.print(factoryCode);
    }

    return qualifiedName;
  }

  /** Generates META-INF/services/io.jafar.parser.api.HandlerFactory file for ServiceLoader. */
  private void generateServiceLoaderRegistration() throws IOException {
    javax.tools.FileObject serviceFile =
        filer.createResource(
            javax.tools.StandardLocation.CLASS_OUTPUT,
            "",
            "META-INF/services/io.jafar.parser.api.HandlerFactory");

    try (PrintWriter out = new PrintWriter(serviceFile.openWriter())) {
      for (String factoryClassName : generatedFactories) {
        out.println(factoryClassName);
      }
    }

    messager.printMessage(
        Diagnostic.Kind.NOTE,
        "Generated ServiceLoader registration for " + generatedFactories.size() + " factories");
  }

  // --- Template application methods (single item) ---

  /** Generates a static type ID field declaration for constant pool resolution. */
  private String staticFieldDeclaration(String cpType) {
    String fieldName = cpTypeIdFieldName(cpType);
    return String.format(STATIC_FIELD_TEMPLATE, cpType, fieldName);
  }

  /** Generates an instance field declaration for constant pool reference. */
  private String instanceFieldCpDeclaration(String fieldName) {
    return String.format(INSTANCE_FIELD_CP_TEMPLATE, fieldName);
  }

  /** Generates an instance field declaration for constant pool reference array. */
  private String instanceFieldCpArrayDeclaration(String fieldName) {
    return String.format(INSTANCE_FIELD_CP_ARRAY_TEMPLATE, fieldName);
  }

  /** Generates an instance field declaration. */
  private String instanceFieldDeclaration(String javaType, String fieldName) {
    return String.format(INSTANCE_FIELD_TEMPLATE, javaType, fieldName);
  }

  /** Generates bind method body for a constant pool type. */
  private String bindStatement(String cpType) {
    String fieldName = cpTypeIdFieldName(cpType);
    String varName = sanitizeVarName(cpType);
    return String.format(BIND_BODY_TEMPLATE, varName, cpType, varName, fieldName, varName);
  }

  /** Generates a switch case for constant pool field reading. */
  private String switchCaseCp(String jfrFieldName, String fieldName) {
    return String.format(SWITCH_CASE_CP_TEMPLATE, jfrFieldName, fieldName);
  }

  /** Generates a switch case for direct field reading. */
  private String switchCase(String jfrFieldName, String fieldName, String readCode) {
    return String.format(SWITCH_CASE_TEMPLATE, jfrFieldName, fieldName, readCode);
  }

  /** Generates a switch case for constant pool array reading. */
  private String switchCaseCpArray(String jfrFieldName, String fieldName) {
    return String.format(SWITCH_CASE_CP_ARRAY_TEMPLATE, jfrFieldName, fieldName, fieldName);
  }

  /** Generates a switch case for primitive array reading. */
  private String switchCasePrimitiveArray(
      String jfrFieldName, String fieldName, String elementType, String readCode) {
    return String.format(
        SWITCH_CASE_PRIMITIVE_ARRAY_TEMPLATE,
        jfrFieldName,
        fieldName,
        elementType,
        fieldName,
        readCode);
  }

  /** Generates a switch case for String array reading. */
  private String switchCaseStringArray(String jfrFieldName, String fieldName) {
    return String.format(SWITCH_CASE_STRING_ARRAY_TEMPLATE, jfrFieldName, fieldName, fieldName);
  }

  /** Generates reset statement for constant pool reference field. */
  private String resetFieldCp(String fieldName) {
    return String.format(RESET_FIELD_CP_TEMPLATE, fieldName);
  }

  /** Generates reset statement for primitive field. */
  private String resetFieldPrimitive(String fieldName, String javaType) {
    return String.format(RESET_FIELD_PRIMITIVE_TEMPLATE, fieldName, getDefaultValue(javaType));
  }

  /** Generates reset statement for object field. */
  private String resetFieldObject(String fieldName) {
    return String.format(RESET_FIELD_OBJECT_TEMPLATE, fieldName);
  }

  /** Generates getter method for constant pool field. */
  private String getterMethodCp(
      String returnType, String methodName, String cpTypeName, String fieldName) {
    String cpTypeIdField = cpTypeIdFieldName(cpTypeName);
    return String.format(
        GETTER_CP_TEMPLATE,
        returnType,
        methodName,
        cpTypeIdField,
        returnType,
        cpTypeIdField,
        fieldName);
  }

  /** Generates getter method for constant pool array field. */
  private String getterMethodCpArray(
      String elementType, String methodName, String cpTypeName, String fieldName) {
    String cpTypeIdField = cpTypeIdFieldName(cpTypeName);
    return String.format(
        GETTER_CP_ARRAY_TEMPLATE,
        elementType,
        methodName,
        cpTypeIdField,
        fieldName,
        elementType,
        elementType,
        fieldName,
        fieldName,
        elementType,
        cpTypeIdField,
        fieldName);
  }

  /** Generates getter method for direct field. */
  private String getterMethod(String returnType, String methodName, String fieldName) {
    return String.format(GETTER_TEMPLATE, returnType, methodName, fieldName);
  }

  // --- Build methods (aggregate multiple items) ---

  /** Builds static type ID fields for constant pool resolution. */
  private String buildStaticTypeIdFields(List<FieldInfo> fields) {
    Set<String> cpTypes = new HashSet<>();
    for (FieldInfo field : fields) {
      if (field.needsConstantPool) {
        cpTypes.add(field.cpTypeName);
      }
    }

    if (cpTypes.isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    for (String cpType : cpTypes) {
      sb.append(staticFieldDeclaration(cpType));
    }
    return sb.toString();
  }

  /** Builds instance field declarations. */
  private String buildInstanceFields(List<FieldInfo> fields) {
    StringBuilder sb = new StringBuilder();
    for (FieldInfo field : fields) {
      if (field.needsConstantPool) {
        if (field.isArray) {
          sb.append(instanceFieldCpArrayDeclaration(field.fieldName));
        } else {
          sb.append(instanceFieldCpDeclaration(field.fieldName));
        }
      } else {
        sb.append(instanceFieldDeclaration(field.javaType, field.fieldName));
      }
    }
    return sb.toString();
  }

  /** Builds the bind method body. */
  private String buildBindBody(List<FieldInfo> fields) {
    Set<String> cpTypes = new HashSet<>();
    for (FieldInfo field : fields) {
      if (field.needsConstantPool) {
        cpTypes.add(field.cpTypeName);
      }
    }

    if (cpTypes.isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    for (String cpType : cpTypes) {
      sb.append(bindStatement(cpType));
    }
    return sb.toString();
  }

  /** Builds switch cases for field reading. */
  private String buildSwitchCases(List<FieldInfo> fields) {
    StringBuilder sb = new StringBuilder();
    for (FieldInfo field : fields) {
      if (field.isArray) {
        // Array handling
        if (field.needsConstantPool) {
          // Array of complex types - read count + loop of CP refs
          sb.append(switchCaseCpArray(field.jfrFieldName, field.fieldName));
        } else if (field.elementType.equals("String")) {
          // String array - read count + loop of UTF-8 strings
          sb.append(switchCaseStringArray(field.jfrFieldName, field.fieldName));
        } else {
          // Primitive array - read count + loop of primitives
          String readCode = generateReadCode(field);
          sb.append(
              switchCasePrimitiveArray(
                  field.jfrFieldName, field.fieldName, field.elementType, readCode));
        }
      } else {
        // Non-array handling
        if (field.needsConstantPool) {
          sb.append(switchCaseCp(field.jfrFieldName, field.fieldName));
        } else {
          sb.append(switchCase(field.jfrFieldName, field.fieldName, generateReadCode(field)));
        }
      }
    }
    return sb.toString();
  }

  /** Builds field reset statements. */
  private String buildResetFields(List<FieldInfo> fields) {
    StringBuilder sb = new StringBuilder();
    for (FieldInfo field : fields) {
      if (field.needsConstantPool) {
        sb.append(resetFieldCp(field.fieldName));
      } else if (field.isPrimitive) {
        sb.append(resetFieldPrimitive(field.fieldName, field.javaType));
      } else {
        sb.append(resetFieldObject(field.fieldName));
      }
    }
    return sb.toString();
  }

  /** Builds getter method implementations. */
  private String buildGetterMethods(List<FieldInfo> fields) {
    StringBuilder sb = new StringBuilder();
    for (FieldInfo field : fields) {
      if (field.needsConstantPool) {
        if (field.isArray) {
          // CP array - resolve long[] to object array
          sb.append(
              getterMethodCpArray(
                  field.elementType, field.methodName, field.cpTypeName, field.fieldName));
        } else {
          // Single CP reference
          sb.append(
              getterMethodCp(
                  field.returnType, field.methodName, field.cpTypeName, field.fieldName));
        }
      } else {
        sb.append(getterMethod(field.returnType, field.methodName, field.fieldName));
      }
    }
    return sb.toString();
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
        // Primitive array (int[], long[], etc.) - inline encoding
        field.needsConstantPool = false;
        field.elementIsPrimitive = true;
        field.elementType = componentType.toString();
        field.javaType = type.toString(); // Keep as int[], long[], etc.
      } else if (componentType.getKind() == TypeKind.DECLARED) {
        DeclaredType declaredComponentType = (DeclaredType) componentType;
        TypeElement componentElement = (TypeElement) declaredComponentType.asElement();
        String componentName = componentElement.getQualifiedName().toString();

        if (componentName.equals("java.lang.String")) {
          // String array - inline UTF-8 encoding
          field.needsConstantPool = false;
          field.elementIsPrimitive = false;
          field.elementType = "String";
          field.javaType = "String[]";
        } else {
          // Array of complex types - CP reference array
          field.needsConstantPool = true;
          field.elementIsPrimitive = false;
          field.cpTypeName = getJfrTypeName(componentType);
          field.elementType = componentName; // Store element type for getter
          field.javaType = "long[]"; // Store as array of CP refs
        }
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
    String typeToRead = field.isArray ? field.elementType : field.javaType;
    return generateReadCodeForType(typeToRead);
  }

  private String generateReadCodeForType(String javaType) {
    // Note: RecordingStream uses readVarint() for all varint-encoded integers
    if (javaType.equals("long")) {
      return "stream.readVarint()";
    } else if (javaType.equals("int")) {
      return "(int) stream.readVarint()";
    } else if (javaType.equals("short")) {
      return "(short) stream.readVarint()";
    } else if (javaType.equals("byte")) {
      return "(byte) stream.readVarint()";
    } else if (javaType.equals("boolean")) {
      return "stream.readBoolean()";
    } else if (javaType.equals("char")) {
      return "(char) stream.readVarint()";
    } else if (javaType.equals("float")) {
      return "stream.readFloat()";
    } else if (javaType.equals("double")) {
      return "stream.readDouble()";
    } else if (javaType.equals("String")) {
      return "stream.readUTF8()";
    } else {
      // Complex type - should be handled by constant pool path
      return "null /* unsupported type: " + javaType + " */";
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
    // Array-specific fields
    String elementType; // Element type name (e.g., "int", "String", "JFRStackFrame")
    boolean elementIsPrimitive;
  }
}
