package io.jafar.testrecordings;

import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Type;
import org.openjdk.jmc.flightrecorder.writer.api.Types;

/**
 * Declares the JDK types with the layout the JVM writes, through the writer's public API.
 *
 * <p>The writer can predefine these ({@code withJdkTypeInitialization}), but it gives {@code
 * jdk.types.StackFrame} a constant pool, so every frame becomes a pool reference. The JVM writes
 * frames inline in the stack trace, and consumers rely on that: jfr-mcp's frame extraction reads
 * each frame as a map, so pooled frames yield empty flame graphs. Declaring the types here, before
 * anything asks the writer for them, makes the writer use these definitions everywhere, including
 * for the implicit {@code stackTrace} and {@code eventThread} fields of every event type.
 */
final class JdkTypes {
  private static final String ANNOTATION = "java.lang.annotation.Annotation";

  private JdkTypes() {}

  static void register(Recording r) {
    Types t = r.getTypes();
    // The only annotation the writer adds by itself (to every event's startTime).
    t.getOrAdd(
        Types.JDK.ANNOTATION_TIMESTAMP, ANNOTATION, b -> b.addField("value", Types.Builtin.STRING));

    Type symbol = t.getOrAdd(Types.JDK.SYMBOL, b -> b.addField("string", Types.Builtin.STRING));
    // java.lang.Class and jdk.types.ClassLoader refer to each other.
    Type classRef = t.getType("java.lang.Class", true);
    Type loaderRef = t.getType("jdk.types.ClassLoader", true);
    Type module =
        t.getOrAdd(
            Types.JDK.MODULE,
            b ->
                b.addField("name", symbol)
                    .addField("version", symbol)
                    .addField("location", symbol)
                    .addField("classLoader", loaderRef));
    Type pkg =
        t.getOrAdd(
            Types.JDK.PACKAGE,
            b ->
                b.addField("name", symbol)
                    .addField("module", module)
                    .addField("exported", Types.Builtin.BOOLEAN));
    Type loader =
        t.getOrAdd(
            Types.JDK.CLASS_LOADER, b -> b.addField("type", classRef).addField("name", symbol));
    Type clazz =
        t.getOrAdd(
            Types.JDK.CLASS,
            b ->
                b.addField("classLoader", loader)
                    .addField("name", symbol)
                    .addField("package", pkg)
                    .addField("modifiers", Types.Builtin.INT)
                    .addField("hidden", Types.Builtin.BOOLEAN));
    Type group =
        t.getOrAdd(
            Types.JDK.THREAD_GROUP,
            b -> b.addField("parent", b.selfType()).addField("name", Types.Builtin.STRING));
    t.getOrAdd(
        Types.JDK.THREAD,
        b ->
            b.addField("osName", Types.Builtin.STRING)
                .addField("osThreadId", Types.Builtin.LONG)
                .addField("javaName", Types.Builtin.STRING)
                .addField("javaThreadId", Types.Builtin.LONG)
                .addField("group", group));
    Type method =
        t.getOrAdd(
            Types.JDK.METHOD,
            b ->
                b.addField("type", clazz)
                    .addField("name", symbol)
                    .addField("descriptor", symbol)
                    .addField("modifiers", Types.Builtin.INT)
                    .addField("hidden", Types.Builtin.BOOLEAN));
    Type frameType =
        t.getOrAdd(Types.JDK.FRAME_TYPE, b -> b.addField("description", Types.Builtin.STRING));
    Type frame =
        t.getOrAdd(
            Types.JDK.STACK_FRAME.getTypeName(),
            false, // inline, as the JVM writes frames
            b ->
                b.addField("method", method)
                    .addField("lineNumber", Types.Builtin.INT)
                    .addField("bytecodeIndex", Types.Builtin.INT)
                    .addField("type", frameType));
    t.getOrAdd(
        Types.JDK.STACK_TRACE,
        b ->
            b.addField("truncated", Types.Builtin.BOOLEAN)
                .addField("frames", frame, f -> f.asArray()));
  }
}
