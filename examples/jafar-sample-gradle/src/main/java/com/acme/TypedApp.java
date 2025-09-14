package com.acme;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
import io.jafar.parser.api.TypedJafarParser;
import java.nio.file.Paths;

public class TypedApp {
  // Minimal typed model for a common event
  @JfrType("jdk.ExecutionSample")
  public interface JFRExecutionSample {
    JFRThread sampledThread();

    JFRStackTrace stackTrace();

    @JfrField(value = "stackTrace", raw = true)
    long stackTraceId();
  }

  @JfrType("java.lang.Thread")
  public interface JFRThread {
    long javaThreadId();
  }

  @JfrType("jdk.types.StackTrace")
  public interface JFRStackTrace {
    JFRStackFrame[] frames();
  }

  @JfrType("jdk.types.StackFrame")
  public interface JFRStackFrame {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: TypedApp <file.jfr>");
    }

    try (TypedJafarParser p = JafarParser.newTypedParser(Paths.get(args[0]))) {
      HandlerRegistration<JFRExecutionSample> reg =
          p.handle(
              JFRExecutionSample.class,
              (e, ctl) -> {
                long tid = e.sampledThread().javaThreadId();
                int depth = e.stackTrace().frames().length;
                System.out.println("tid=" + tid + " depth=" + depth);
              });
      p.run();
      reg.destroy(p);
    }
  }
}
