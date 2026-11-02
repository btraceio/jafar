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
      "        case \"%1$s\":\n"
          + "          if (p.%2$s_inline) {\n"
          + "            %3$s %2$s_handler = new %3$s();\n"
          + "            %2$s_handler.read(stream, metaField.getType(), constantPools);\n"
          + "            this.%2$s_value = %2$s_handler;\n"
          + "            break;\n"
          + "          }\n"
          + "          this.%2$s_cpRef = stream.readVarint();\n"
          + "          break;\n";

  // A complex accessor whose type has no generated handler: only a pool reference can be read
  // (Plan rejects an inline field).
  private static final String SWITCH_CASE_CP_ONLY_TEMPLATE =
      "        case \"%s\":\n"
          + "          this.%s_cpRef = stream.readVarint();\n"
          + "          break;\n";

  // A String accessor: inline, or a reference resolved in the getter through the field's pool.
  private static final String SWITCH_CASE_STRING_TEMPLATE =
      "        case \"%1$s\":\n"
          + "          this.%2$s_cpType = p.%2$s_cpType;\n"
          + "          if (this.%2$s_cpType != -1L) {\n"
          + "            this.%2$s_cpRef = stream.readVarint();\n"
          + "          } else {\n"
          + "            this.%2$s = stream.readUTF8();\n"
          + "          }\n"
          + "          break;\n";

  private static final String SWITCH_CASE_CP_ARRAY_TEMPLATE =
      "        case \"%s\": {\n"
          + "          int count = (int) stream.readVarint();\n"
          + "%s"
          + "          this.%s_cpRef = new long[count];\n"
          + "          for (int i = 0; i < count; i++) {\n"
          + "            this.%s_cpRef[i] = stream.readVarint();\n"
          + "          }\n"
          + "          break;\n"
          + "        }\n";

  // The inline branch of an array of complex values: each element is read in place by a new
  // instance of the element type's handler - the values outlive this handler's next read.
  private static final String SWITCH_CASE_INLINE_ARRAY_TEMPLATE =
      "          if (p.%1$s_inline) {\n"
          + "            MetadataClass elementType = metaField.getType();\n"
          + "            %2$s[] values = new %2$s[count];\n"
          + "            for (int i = 0; i < count; i++) {\n"
          + "              %3$s element = new %3$s();\n"
          + "              element.read(stream, elementType, constantPools);\n"
          + "              values[i] = element;\n"
          + "            }\n"
          + "            this.%1$s_value = values;\n"
          + "            break;\n"
          + "          }\n";

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

  // Checks a declared field against the recording's metadata, once per metadata class (see
  // HandlerTemplate.validate): pool storage (0 inline, 1 pooled, 2 either - @JfrField(raw = true),
  // whose value is the pool index for a pooled field), array shape, and the field's JFR type.
  private static final String VALIDATE_CASE_TEMPLATE =
      "          case \"%s\":\n"
          + "            requireStorage(metadata, metaField, %d, %s, %s);\n"
          + "            break;\n";

  // A complex-typed accessor: a pool reference or an inline value, decided by the metadata.
  private static final String VALIDATE_CASE_COMPLEX_TEMPLATE =
      "          case \"%s\":\n"
          + "            %s = planComplex(metadata, metaField, \"%s\", %s, %s);\n"
          + "            break;\n";

  // A String accessor: an inline string or a reference to a pooled string or wrapper of one.
  private static final String VALIDATE_CASE_STRING_TEMPLATE =
      "          case \"%s\":\n"
          + "            %s = planString(metadata, metaField);\n"
          + "            break;\n";

  private static final java.util.Map<String, String> BOXED_TO_PRIMITIVE = new java.util.HashMap<>();

  static {
    BOXED_TO_PRIMITIVE.put("java.lang.Long", "long");
    BOXED_TO_PRIMITIVE.put("java.lang.Integer", "int");
    BOXED_TO_PRIMITIVE.put("java.lang.Short", "short");
    BOXED_TO_PRIMITIVE.put("java.lang.Byte", "byte");
    BOXED_TO_PRIMITIVE.put("java.lang.Boolean", "boolean");
    BOXED_TO_PRIMITIVE.put("java.lang.Character", "char");
    BOXED_TO_PRIMITIVE.put("java.lang.Float", "float");
    BOXED_TO_PRIMITIVE.put("java.lang.Double", "double");
  }

  private static final String RESET_FIELD_CP_TEMPLATE = "    this.%s_cpRef = 0L;\n";
  private static final String RESET_FIELD_PRIMITIVE_TEMPLATE = "    this.%s = %s;\n";
  private static final String RESET_FIELD_OBJECT_TEMPLATE = "    this.%s = null;\n";

  // A chunk need not have a pool of every type; like the runtime-generated handlers
  // (CodeGenerator), an absent pool yields null, and an empty array for an array field.
  private static final String GETTER_CP_TEMPLATE =
      "  @Override\n"
          + "  public %1$s %2$s() {\n"
          + "%7$s"
          + "    if (%3$s == -1L || constantPools == null) {\n"
          + "      return null;\n"
          + "    }\n"
          + "    io.jafar.parser.api.ConstantPool cp = constantPools.getConstantPool(%5$s);\n"
          + "    return cp == null ? null : (%4$s) cp.get(%6$s_cpRef);\n"
          + "  }\n\n";

  private static final String GETTER_CP_ARRAY_TEMPLATE =
      "  @Override\n"
          + "  public %1$s[] %2$s() {\n"
          + "%12$s"
          + "    if (%3$s == -1L || constantPools == null || this.%4$s_cpRef == null) {\n"
          + "      return null;\n"
          + "    }\n"
          + "    io.jafar.parser.api.ConstantPool cp = constantPools.getConstantPool(%10$s);\n"
          + "    if (cp == null) {\n"
          + "      return new %5$s[0];\n"
          + "    }\n"
          + "    %5$s[] result = new %6$s[this.%7$s_cpRef.length];\n"
          + "    for (int i = 0; i < this.%8$s_cpRef.length; i++) {\n"
          + "      result[i] = (%9$s) cp.get(this.%11$s_cpRef[i]);\n"
          + "    }\n"
          + "    return result;\n"
          + "  }\n\n";

  private static final String GETTER_TEMPLATE =
      "  @Override\n" + "  public %s %s() {\n" + "    return this.%s;\n" + "  }\n\n";

  private static final String GETTER_INLINE_TEMPLATE =
      "    if (this.%1$s_value != null) {\n" + "      return this.%1$s_value;\n" + "    }\n";

  // Like the pool getters, an absent pool or entry yields null; the entry is unwrapped to its
  // string (HandlerTemplate.unwrapString).
  private static final String GETTER_STRING_TEMPLATE =
      "  @Override\n"
          + "  public String %1$s() {\n"
          + "    if (this.%1$s_cpType == -1L) {\n"
          + "      return this.%1$s;\n"
          + "    }\n"
          + "    if (constantPools == null) {\n"
          + "      return null;\n"
          + "    }\n"
          + "    io.jafar.parser.api.ConstantPool cp = constantPools.getConstantPool(this.%1$s_cpType);\n"
          + "    return cp == null ? null : unwrapString(cp.get(this.%1$s_cpRef), \"%1$s\");\n"
          + "  }\n\n";

  private Filer filer;
  private Messager messager;
  private final Set<String> generatedFactories = new HashSet<>();
  // Top-level @JfrType interfaces of this compilation, whose handlers this processor generates
  private final Set<String> processedInterfaces = new HashSet<>();
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
    // Known before generating any handler: a handler may read another interface's values inline
    // with that interface's handler, generated in this same round (generatedHandlerName)
    for (Element element : roundEnv.getElementsAnnotatedWith(JfrType.class)) {
      if (element.getKind() == ElementKind.INTERFACE
          && !((TypeElement) element).getNestingKind().isNested()) {
        processedInterfaces.add(((TypeElement) element).getQualifiedName().toString());
      }
    }

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
            .set("VALIDATE_CASES", buildValidateCases(fields))
            .set("PLAN_FIELDS", buildPlanFields(fields))
            .set("PLAN_DEFAULTS", buildPlanDefaults(fields))
            .set("PLAN_ASSIGN", buildPlanAssign(fields))
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

  /** Generates a switch case for a complex field: inline when the plan says so, else pooled. */
  private String switchCaseCp(String jfrFieldName, String fieldName, String inlineHandler) {
    if (inlineHandler == null) {
      return String.format(SWITCH_CASE_CP_ONLY_TEMPLATE, jfrFieldName, fieldName);
    }
    return String.format(SWITCH_CASE_CP_TEMPLATE, jfrFieldName, fieldName, inlineHandler);
  }

  /** Generates a switch case for direct field reading. */
  private String switchCase(String jfrFieldName, String fieldName, String readCode) {
    return String.format(SWITCH_CASE_TEMPLATE, jfrFieldName, fieldName, readCode);
  }

  /** Generates a switch case for an array of complex values: inline or pool references. */
  private String switchCaseCpArray(
      String jfrFieldName, String fieldName, String elementType, String inlineHandler) {
    String inline =
        inlineHandler == null
            ? ""
            : String.format(
                SWITCH_CASE_INLINE_ARRAY_TEMPLATE, fieldName, elementType, inlineHandler);
    return String.format(SWITCH_CASE_CP_ARRAY_TEMPLATE, jfrFieldName, inline, fieldName, fieldName);
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
      String returnType,
      String methodName,
      String cpTypeName,
      String fieldName,
      String inlineHandler) {
    String cpTypeIdField = cpTypeIdFieldName(cpTypeName);
    return String.format(
        GETTER_CP_TEMPLATE,
        returnType,
        methodName,
        cpTypeIdField,
        returnType,
        cpTypeIdField,
        fieldName,
        inlineGetterPrefix(fieldName, inlineHandler));
  }

  private static String inlineGetterPrefix(String fieldName, String inlineHandler) {
    return inlineHandler == null ? "" : String.format(GETTER_INLINE_TEMPLATE, fieldName);
  }

  /** Generates getter method for constant pool array field. */
  private String getterMethodCpArray(
      String elementType,
      String methodName,
      String cpTypeName,
      String fieldName,
      String inlineHandler) {
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
        fieldName,
        inlineGetterPrefix(fieldName, inlineHandler));
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
        if (field.inlineHandler != null) {
          // the value read in place when the recording stores the field inline
          String type = field.isArray ? field.elementType + "[]" : field.returnType;
          sb.append(instanceFieldDeclaration(type, field.fieldName + "_value"));
        }
      } else {
        sb.append(instanceFieldDeclaration(field.javaType, field.fieldName));
        if (field.isPooledString()) {
          // the pool a pooled string field refers to (-1 inline), and the reference
          sb.append(instanceFieldDeclaration("long", field.fieldName + "_cpType"));
          sb.append(instanceFieldCpDeclaration(field.fieldName));
        }
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
          // Array of complex types - read count + loop of inline values or CP refs
          sb.append(
              switchCaseCpArray(
                  field.jfrFieldName, field.fieldName, field.elementType, field.inlineHandler));
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
          sb.append(switchCaseCp(field.jfrFieldName, field.fieldName, field.inlineHandler));
        } else if (field.isPooledString()) {
          sb.append(
              String.format(SWITCH_CASE_STRING_TEMPLATE, field.jfrFieldName, field.fieldName));
        } else {
          sb.append(switchCase(field.jfrFieldName, field.fieldName, generateReadCode(field)));
        }
      }
    }
    return sb.toString();
  }

  /** Builds the cases of the handler's metadata check (HandlerTemplate.validate). */
  private String buildValidateCases(List<FieldInfo> fields) {
    StringBuilder sb = new StringBuilder();
    for (FieldInfo field : fields) {
      if (field.needsConstantPool) {
        sb.append(
            String.format(
                VALIDATE_CASE_COMPLEX_TEMPLATE,
                field.jfrFieldName,
                field.fieldName + "_inline",
                field.cpTypeName,
                field.isArray,
                field.inlineHandler == null ? "null" : "\"" + field.inlineHandler + "\""));
        continue;
      }
      if (field.isPooledString()) {
        sb.append(
            String.format(
                VALIDATE_CASE_STRING_TEMPLATE, field.jfrFieldName, field.fieldName + "_cpType"));
        continue;
      }
      int pooled = field.needsConstantPool ? 1 : field.isRaw ? 2 : 0;
      String types;
      if (field.needsConstantPool) {
        types = "new String[] {\"" + field.cpTypeName + "\"}";
      } else {
        types = expectedJfrTypes(field.isArray ? field.elementType : field.javaType);
      }
      sb.append(
          String.format(VALIDATE_CASE_TEMPLATE, field.jfrFieldName, pooled, field.isArray, types));
    }
    return sb.toString();
  }

  /** Builds the fields of the handler's read plan (HandlerTemplate.Plan). */
  private String buildPlanFields(List<FieldInfo> fields) {
    StringBuilder sb = new StringBuilder();
    for (FieldInfo field : fields) {
      if (field.needsConstantPool) {
        sb.append("    /** Whether {@code ")
            .append(field.jfrFieldName)
            .append("} is stored inline rather than as a constant pool reference. */\n")
            .append("    final boolean ")
            .append(field.fieldName)
            .append("_inline;\n");
      } else if (field.isPooledString()) {
        sb.append("    /** The pool {@code ")
            .append(field.jfrFieldName)
            .append("} refers to, or -1 when it is an inline string. */\n")
            .append("    final long ")
            .append(field.fieldName)
            .append("_cpType;\n");
      }
    }
    return sb.toString();
  }

  /** Declares the plan's locals with their value for a field the metadata does not have. */
  private String buildPlanDefaults(List<FieldInfo> fields) {
    StringBuilder sb = new StringBuilder();
    for (FieldInfo field : fields) {
      if (field.needsConstantPool) {
        sb.append("      boolean ").append(field.fieldName).append("_inline = false;\n");
      } else if (field.isPooledString()) {
        sb.append("      long ").append(field.fieldName).append("_cpType = -1L;\n");
      }
    }
    return sb.toString();
  }

  /** Assigns the plan's final fields from its locals. */
  private String buildPlanAssign(List<FieldInfo> fields) {
    StringBuilder sb = new StringBuilder();
    for (FieldInfo field : fields) {
      String name = null;
      if (field.needsConstantPool) {
        name = field.fieldName + "_inline";
      } else if (field.isPooledString()) {
        name = field.fieldName + "_cpType";
      }
      if (name != null) {
        sb.append("      this.").append(name).append(" = ").append(name).append(";\n");
      }
    }
    return sb.toString();
  }

  /**
   * The JFR types whose encoding the read generated for {@code javaType} consumes correctly: the
   * varint integer types are interchangeable, as are the one-byte {@code byte} and {@code boolean};
   * {@code float}, {@code double} and {@code String} match only themselves.
   */
  private static String expectedJfrTypes(String javaType) {
    switch (javaType) {
      case "long":
      case "int":
      case "short":
      case "char":
      case "java.lang.Long":
      case "java.lang.Integer":
      case "java.lang.Short":
      case "java.lang.Character":
        return "new String[] {\"long\", \"int\", \"short\", \"char\"}";
      case "byte":
      case "boolean":
      case "java.lang.Byte":
      case "java.lang.Boolean":
        return "new String[] {\"byte\", \"boolean\"}";
      case "float":
      case "java.lang.Float":
        return "new String[] {\"float\"}";
      case "double":
      case "java.lang.Double":
        return "new String[] {\"double\"}";
      case "String":
        return "new String[] {\"java.lang.String\"}";
      default:
        return "new String[0]";
    }
  }

  /** Builds field reset statements. */
  private String buildResetFields(List<FieldInfo> fields) {
    StringBuilder sb = new StringBuilder();
    for (FieldInfo field : fields) {
      if (field.needsConstantPool) {
        if (field.isArray) {
          // CP array field: reset to null
          sb.append(resetFieldObject(field.fieldName + "_cpRef"));
        } else {
          // Single CP ref: reset to 0L
          sb.append(resetFieldCp(field.fieldName));
        }
        if (field.inlineHandler != null) {
          sb.append(resetFieldObject(field.fieldName + "_value"));
        }
      } else if (field.isPooledString()) {
        sb.append(resetFieldObject(field.fieldName));
        sb.append(resetFieldCp(field.fieldName));
        sb.append(
            String.format(RESET_FIELD_PRIMITIVE_TEMPLATE, field.fieldName + "_cpType", "-1L"));
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
                  field.elementType,
                  field.methodName,
                  field.cpTypeName,
                  field.fieldName,
                  field.inlineHandler));
        } else {
          // Single CP reference
          sb.append(
              getterMethodCp(
                  field.returnType,
                  field.methodName,
                  field.cpTypeName,
                  field.fieldName,
                  field.inlineHandler));
        }
      } else if (field.isPooledString()) {
        sb.append(String.format(GETTER_STRING_TEMPLATE, field.fieldName));
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
          field.inlineHandler = generatedHandlerName(componentElement);
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
        field.inlineHandler = generatedHandlerName(typeElement);
        field.javaType = "long"; // Store CP ref
      }
    }
  }

  /**
   * The generated handler of {@code type}, which reads a value of it in place, or {@code null} when
   * there is none to call: {@code type} is not a top-level {@code @JfrType} interface, or it is one
   * from the classpath (a precompiled library; {@code @JfrType} has runtime retention) whose
   * handler is not there - this processor generates handlers only for this compilation's
   * interfaces, and referencing a missing class would fail the build.
   */
  private String generatedHandlerName(TypeElement type) {
    if (type.getKind() != ElementKind.INTERFACE
        || type.getAnnotation(JfrType.class) == null
        || type.getNestingKind().isNested()) {
      return null;
    }
    String interfaceName = type.getQualifiedName().toString();
    String handlerName = interfaceName + "Handler";
    if (processedInterfaces.contains(interfaceName)
        || processingEnv.getElementUtils().getTypeElement(handlerName) != null) {
      return handlerName;
    }
    return null;
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
      // A byte is one raw byte, not a varint: the JDK's own reader (ParserFactory.ByteParser) reads
      // it with readByte() and skips it as 1 byte, as do GenericValueReader and TypeSkipper.
      return "stream.read()";
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
    } else if (javaType.startsWith("java.lang.")) {
      // Boxed accessors read like their primitive; autoboxing does the rest. They used to fall
      // through to "null", which consumed nothing and misaligned every later field.
      String primitive = BOXED_TO_PRIMITIVE.get(javaType);
      if (primitive != null) {
        return generateReadCodeForType(primitive);
      }
      return "null /* unsupported type: " + javaType + " */";
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
    // A complex type's generated handler, which reads an inline value in place; null if none
    String inlineHandler;

    /** A String accessor, which reads an inline string or resolves a pooled one. */
    boolean isPooledString() {
      return !isArray && !isRaw && "String".equals(javaType);
    }
  }
}
