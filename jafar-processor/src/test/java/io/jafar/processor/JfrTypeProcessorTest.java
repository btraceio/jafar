package io.jafar.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

/** Tests for the {@link JfrTypeProcessor} annotation processor. */
class JfrTypeProcessorTest {

  @Test
  void generatesHandlerAndFactory() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRTestEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.TestEvent")
                public interface JFRTestEvent {
                    long startTime();
                    long duration();
                    String message();
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();
    assertThat(compilation).generatedSourceFile("test.JFRTestEventHandler");
    assertThat(compilation).generatedSourceFile("test.JFRTestEventFactory");
  }

  @Test
  void generatedFactoryImplementsHandlerFactory() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRSimpleEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.SimpleEvent")
                public interface JFRSimpleEvent {
                    long timestamp();
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify the generated factory implements HandlerFactory
    assertThat(compilation)
        .generatedSourceFile("test.JFRSimpleEventFactory")
        .contentsAsUtf8String()
        .contains("implements HandlerFactory<JFRSimpleEvent>");
  }

  @Test
  void generatesHandlerWithConstantPoolFields() {
    JavaFileObject stackTraceInterface =
        JavaFileObjects.forSourceString(
            "test.JFRStackTrace",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.types.StackTrace")
                public interface JFRStackTrace {
                    long id();
                }
                """);

    JavaFileObject eventSource =
        JavaFileObjects.forSourceString(
            "test.JFRExecutionSample",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.ExecutionSample")
                public interface JFRExecutionSample {
                    long startTime();
                    JFRStackTrace stackTrace();
                }
                """);

    Compilation compilation =
        javac().withProcessors(new JfrTypeProcessor()).compile(stackTraceInterface, eventSource);

    assertThat(compilation).succeeded();

    // Verify handler has constant pool type ID field
    assertThat(compilation)
        .generatedSourceFile("test.JFRExecutionSampleHandler")
        .contentsAsUtf8String()
        .contains("STACKTRACE_TYPE_ID");
  }

  @Test
  void handlesJfrFieldAnnotation() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRFieldEvent",
            """
                package test;

                import io.jafar.parser.api.JfrField;
                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.FieldEvent")
                public interface JFRFieldEvent {
                    @JfrField("eventStartTime")
                    long startTime();
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify handler uses the JFR field name in switch case
    assertThat(compilation)
        .generatedSourceFile("test.JFRFieldEventHandler")
        .contentsAsUtf8String()
        .contains("case \"eventStartTime\":");
  }

  @Test
  void skipsJfrIgnoreAnnotatedMethods() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRIgnoreEvent",
            """
                package test;

                import io.jafar.parser.api.JfrIgnore;
                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.IgnoreEvent")
                public interface JFRIgnoreEvent {
                    long timestamp();

                    @JfrIgnore
                    default String computed() {
                        return "computed";
                    }
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify handler doesn't have the ignored method as a field
    assertThat(compilation)
        .generatedSourceFile("test.JFRIgnoreEventHandler")
        .contentsAsUtf8String()
        .doesNotContain("private String computed");
  }

  @Test
  void errorOnNonInterface() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRBadClass",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.BadEvent")
                public class JFRBadClass {
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("can only be applied to interfaces");
  }

  @Test
  void errorOnAbstractClass() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRAbstractEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.AbstractEvent")
                public abstract class JFRAbstractEvent {
                    public abstract long timestamp();
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("can only be applied to interfaces");
  }

  @Test
  void errorOnEnum() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFREventType",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.EventType")
                public enum JFREventType {
                    TYPE_A, TYPE_B
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).failed();
    assertThat(compilation).hadErrorContaining("can only be applied to interfaces");
  }

  @Test
  void skipsMethodsWithParameters() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRMethodEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.MethodEvent")
                public interface JFRMethodEvent {
                    long timestamp();

                    // Method with parameters - should be default to be valid
                    default String compute(int value) {
                        return "computed";
                    }
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify handler doesn't have field for method with parameters
    assertThat(compilation)
        .generatedSourceFile("test.JFRMethodEventHandler")
        .contentsAsUtf8String()
        .doesNotContain("private String compute");
    assertThat(compilation)
        .generatedSourceFile("test.JFRMethodEventHandler")
        .contentsAsUtf8String()
        .doesNotContain("case \"compute\":");
  }

  @Test
  void skipsStaticMethods() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRStaticEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.StaticEvent")
                public interface JFRStaticEvent {
                    long timestamp();

                    static String getName() {
                        return "static";
                    }
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify handler doesn't have field for static method
    assertThat(compilation)
        .generatedSourceFile("test.JFRStaticEventHandler")
        .contentsAsUtf8String()
        .doesNotContain("private String getName");
  }

  @Test
  void handlesEmptyInterface() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFREmptyEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.EmptyEvent")
                public interface JFREmptyEvent {
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();
    assertThat(compilation).generatedSourceFile("test.JFREmptyEventHandler");
    assertThat(compilation).generatedSourceFile("test.JFREmptyEventFactory");

    // Verify handler compiles even with no fields
    assertThat(compilation)
        .generatedSourceFile("test.JFREmptyEventHandler")
        .contentsAsUtf8String()
        .contains("public final class JFREmptyEventHandler implements JFREmptyEvent");
  }

  @Test
  void handlesInterfaceWithOnlyDefaultMethods() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRDefaultEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.DefaultEvent")
                public interface JFRDefaultEvent {
                    default String getType() {
                        return "default";
                    }
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify handler doesn't have fields for default methods
    assertThat(compilation)
        .generatedSourceFile("test.JFRDefaultEventHandler")
        .contentsAsUtf8String()
        .doesNotContain("private String getType");
  }

  @Test
  void handlesInterfaceWithOnlyIgnoredMethods() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRAllIgnoredEvent",
            """
                package test;

                import io.jafar.parser.api.JfrIgnore;
                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.AllIgnoredEvent")
                public interface JFRAllIgnoredEvent {
                    @JfrIgnore
                    default String compute() {
                        return "ignored";
                    }
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();
    assertThat(compilation).generatedSourceFile("test.JFRAllIgnoredEventHandler");
  }

  @Test
  void handlesPrimitiveWrapperTypes() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRWrapperEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.WrapperEvent")
                public interface JFRWrapperEvent {
                    Long timestamp();
                    Integer count();
                    Boolean flag();
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify handler has fields for wrapper types
    assertThat(compilation)
        .generatedSourceFile("test.JFRWrapperEventHandler")
        .contentsAsUtf8String()
        .contains("private java.lang.Long timestamp");
    assertThat(compilation)
        .generatedSourceFile("test.JFRWrapperEventHandler")
        .contentsAsUtf8String()
        .contains("private java.lang.Integer count");
  }

  @Test
  void handlesAllPrimitiveTypes() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRPrimitiveEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.PrimitiveEvent")
                public interface JFRPrimitiveEvent {
                    byte byteValue();
                    short shortValue();
                    int intValue();
                    long longValue();
                    float floatValue();
                    double doubleValue();
                    boolean booleanValue();
                    char charValue();
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify all primitive types are handled
    assertThat(compilation)
        .generatedSourceFile("test.JFRPrimitiveEventHandler")
        .contentsAsUtf8String()
        .contains("private byte byteValue");
    assertThat(compilation)
        .generatedSourceFile("test.JFRPrimitiveEventHandler")
        .contentsAsUtf8String()
        .contains("private short shortValue");
    assertThat(compilation)
        .generatedSourceFile("test.JFRPrimitiveEventHandler")
        .contentsAsUtf8String()
        .contains("private boolean booleanValue");
    assertThat(compilation)
        .generatedSourceFile("test.JFRPrimitiveEventHandler")
        .contentsAsUtf8String()
        .contains("private char charValue");
  }

  @Test
  void handlesMultipleConstantPoolFields() {
    JavaFileObject threadInterface =
        JavaFileObjects.forSourceString(
            "test.JFRThread",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.types.Thread")
                public interface JFRThread {
                    long javaThreadId();
                }
                """);

    JavaFileObject stackTraceInterface =
        JavaFileObjects.forSourceString(
            "test.JFRStackTrace",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.types.StackTrace")
                public interface JFRStackTrace {
                    long id();
                }
                """);

    JavaFileObject eventSource =
        JavaFileObjects.forSourceString(
            "test.JFRMultiCPEvent",
            """
                package test;

                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.MultiCPEvent")
                public interface JFRMultiCPEvent {
                    long startTime();
                    JFRThread thread();
                    JFRStackTrace stackTrace();
                }
                """);

    Compilation compilation =
        javac()
            .withProcessors(new JfrTypeProcessor())
            .compile(threadInterface, stackTraceInterface, eventSource);

    assertThat(compilation).succeeded();

    // Verify handler has multiple type ID fields
    assertThat(compilation)
        .generatedSourceFile("test.JFRMultiCPEventHandler")
        .contentsAsUtf8String()
        .contains("THREAD_TYPE_ID");
    assertThat(compilation)
        .generatedSourceFile("test.JFRMultiCPEventHandler")
        .contentsAsUtf8String()
        .contains("STACKTRACE_TYPE_ID");
  }

  @Test
  void handlesInterfaceInDefaultPackage() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "JFRDefaultPackageEvent",
            """
                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.DefaultPackageEvent")
                public interface JFRDefaultPackageEvent {
                    long timestamp();
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();
    assertThat(compilation).generatedSourceFile("JFRDefaultPackageEventHandler");
    assertThat(compilation).generatedSourceFile("JFRDefaultPackageEventFactory");
  }

  @Test
  void handlesComplexJfrFieldNames() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRComplexFieldEvent",
            """
                package test;

                import io.jafar.parser.api.JfrField;
                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.ComplexFieldEvent")
                public interface JFRComplexFieldEvent {
                    @JfrField("event.start.time")
                    long startTime();

                    @JfrField("event$duration")
                    long duration();
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify handler uses complex field names correctly
    assertThat(compilation)
        .generatedSourceFile("test.JFRComplexFieldEventHandler")
        .contentsAsUtf8String()
        .contains("case \"event.start.time\":");
    assertThat(compilation)
        .generatedSourceFile("test.JFRComplexFieldEventHandler")
        .contentsAsUtf8String()
        .contains("case \"event$duration\":");
  }

  @Test
  void handlesInterfaceWithMixedMethodTypes() {
    JavaFileObject source =
        JavaFileObjects.forSourceString(
            "test.JFRMixedEvent",
            """
                package test;

                import io.jafar.parser.api.JfrIgnore;
                import io.jafar.parser.api.JfrType;

                @JfrType("jdk.MixedEvent")
                public interface JFRMixedEvent {
                    // Regular field
                    long timestamp();

                    // Default method
                    default String getType() {
                        return "mixed";
                    }

                    // Ignored method
                    @JfrIgnore
                    default int compute() {
                        return 42;
                    }

                    // Static method
                    static String getName() {
                        return "MixedEvent";
                    }

                    // Method with parameters (must be default)
                    default String format(String pattern) {
                        return "formatted";
                    }

                    // Regular field
                    String message();
                }
                """);

    Compilation compilation = javac().withProcessors(new JfrTypeProcessor()).compile(source);

    assertThat(compilation).succeeded();

    // Verify only timestamp and message are in the handler
    assertThat(compilation)
        .generatedSourceFile("test.JFRMixedEventHandler")
        .contentsAsUtf8String()
        .contains("private long timestamp");
    assertThat(compilation)
        .generatedSourceFile("test.JFRMixedEventHandler")
        .contentsAsUtf8String()
        .contains("private String message");
    // Should not contain skipped methods
    assertThat(compilation)
        .generatedSourceFile("test.JFRMixedEventHandler")
        .contentsAsUtf8String()
        .doesNotContain("private String getType");
    assertThat(compilation)
        .generatedSourceFile("test.JFRMixedEventHandler")
        .contentsAsUtf8String()
        .doesNotContain("private int compute");
    assertThat(compilation)
        .generatedSourceFile("test.JFRMixedEventHandler")
        .contentsAsUtf8String()
        .doesNotContain("private String format");
  }
}
