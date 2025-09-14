package com.acme;

import io.jafar.parser.api.Control;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.Values;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.nio.file.Paths;
import java.util.Map;

public class App {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new IllegalArgumentException("Usage: App <file.jfr>");
    }

    try (UntypedJafarParser p = JafarParser.newUntypedParser(Paths.get(args[0]))) {
      HandlerRegistration<?> reg =
          p.handle(
              (MetadataClass type, Map<String, Object> value, Control ctl) -> {
                // Filter down to a common event for a quick sanity check
                if ("jdk.ExecutionSample".equals(type.getName())) {
                  Object tid = Values.get(value, "sampledThread", "javaThreadId");
                  Object frames = Values.get(value, "stackTrace", "frames");
                  int depth = (frames instanceof Object[] a) ? a.length : 0;
                  System.out.println("tid=" + tid + " depth=" + depth);
                }
              });
      p.run();
      reg.destroy(p);
    }
  }
}
