package io.jafar.utils;

import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.ValueDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

public final class TypeGenerator {
    private final Path jfr;
    private final Path output;
    private final String pkg;
    private final boolean overwrite;
    private final Predicate<String> eventTypeFilter;

    public TypeGenerator(Path jfr, Path output, String targetPackage, boolean overwrite, Predicate<String> eventTypeFilter) throws IOException{
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

    public void generate() throws Exception {
        if (jfr == null) {
            generateFromRuntime();
        } else {
            generateFromFile();
        }
    }

    private void generateFromRuntime() throws Exception {
        Set<String> generated = new HashSet<>();
        FlightRecorder.getFlightRecorder().getEventTypes().forEach(et -> {
            if (eventTypeFilter == null || eventTypeFilter.test(et.getName())) {
                try {
                    Path target = output.resolve("JFR" + getSimpleName(et.getName()) + ".java");
                    if (overwrite || !Files.exists(target)) {
                        Files.writeString(target, generateTypeFromEvent(et, generated), StandardOpenOption.CREATE_NEW);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);

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
        et.getFields().forEach(field -> {
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
            sb.append(isPrimitiveName(field.getTypeName()) ? "" : "JFR").append(getSimpleName(field.getTypeName()));
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
            if (overwrite || !Files.exists(target)) {
                Files.writeString(output.resolve(targetName + ".java"), data, StandardOpenOption.CREATE_NEW);
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
            field.getFields().forEach(subfield -> {
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
                sb.append(isPrimitiveName(subfield.getTypeName()) ? "" : "JFR").append(getSimpleName(subfield.getTypeName()));
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
        try (StreamingChunkParser parser = new StreamingChunkParser()) {
            parser.parse(jfr, new ChunkParserListener() {
                @Override
                public boolean onMetadata(MetadataEvent metadata) {
                    metadata.getClasses().forEach(TypeGenerator.this::writeClass);
                    // stop processing
                    return false;
                }
            });
        }
    }

    private void writeClass(MetadataClass metadataClass) {
        if (metadataClass.isPrimitive()) {
            return;
        }
        if (isAnnotation(metadataClass) || isSettingControl(metadataClass)) {
            return;
        }
        try {
            Path classFile = output.resolve(getClassName(metadataClass) + ".java");
            if (overwrite || !Files.exists(classFile)) {
                Files.writeString(classFile, generateClass(metadataClass), StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String generateClass(MetadataClass clazz) {
        StringBuilder sb  = new StringBuilder();
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
                fldType = fldType.getFields().getFirst().getType();
            }
            sb.append(getClassName(fldType));
            sb.append("[]".repeat(Math.max(0, field.getDimension())));
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
        switch (fieldName) {
            case "class": return "clz";
            case "package": return "pkg";
            default: return fieldName;
        }
    }

    public static boolean isEvent(MetadataClass clazz) {
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
        if ("jdk.jfr.SettingControl".equals(superType)) {
            return true;
        }
        /*
        TODO: this is not technically true as a type may have JFR event upper in hierarchy but
              let's ignore it for now
         */
        return false;
    }

    private static String getSimpleName(String name) {
        int idx = name.lastIndexOf('.');
        return idx == -1 ? name : name.substring(idx + 1);
    }

    private static boolean isPrimitiveName(String name) {
        return name.lastIndexOf('.') == -1 || "java.lang.String".equals(name);
    }
}
