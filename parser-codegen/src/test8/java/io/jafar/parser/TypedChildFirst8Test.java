package io.jafar.parser;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.jafar.parser.api.JFRHandler;
import io.jafar.parser.api.TypedJafarParser;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The bridge class loader on JDK 8: an interface the parser's loader sees as another class (a
 * child-first loader's own copy) is implemented by a runtime class defined in the bridge, a path
 * that does not go through the JDK 8 definer (Unsafe.defineAnonymousClass). Mirrors
 * TypedChildClassLoaderTest's child-first case, which runs on JDK 21.
 */
public class TypedChildFirst8Test {

  /** Defines its own copy of {@link ChildFirstShared8} from the same bytes. */
  private static final class ChildFirst extends ClassLoader {
    ChildFirst(ClassLoader parent) {
      super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.equals(ChildFirstShared8.class.getName())) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> c = findLoadedClass(name);
        if (c != null) {
          return c;
        }
        try (InputStream in = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          byte[] buf = new byte[4096];
          for (int n; (n = in.read(buf)) > 0; ) {
            out.write(buf, 0, n);
          }
          byte[] bytes = out.toByteArray();
          return defineClass(name, bytes, 0, bytes.length);
        } catch (IOException e) {
          throw new ClassNotFoundException(name, e);
        }
      }
    }
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void aChildFirstLoadersOwnCopyIsImplemented() throws Exception {
    Class<?> copy =
        new ChildFirst(getClass().getClassLoader()).loadClass(ChildFirstShared8.class.getName());
    assertTrue(copy != ChildFirstShared8.class, "the premise: two copies of ChildFirstShared8");
    Method startTime = copy.getMethod("startTime");
    List<String> seen = Collections.synchronizedList(new ArrayList<>());
    String path =
        new File(getClass().getClassLoader().getResource("test-ap.jfr").toURI()).getAbsolutePath();
    try (TypedJafarParser p = TypedJafarParser.open(path)) {
      p.handle(
          (Class) copy,
          (JFRHandler)
              (e, ctl) -> {
                try {
                  seen.add(
                      copy.isInstance(e)
                          ? String.valueOf(startTime.invoke(e))
                          : "not the child's ChildFirstShared8: " + e.getClass());
                } catch (ReflectiveOperationException ex) {
                  throw new AssertionError(ex);
                }
              });
      p.run();
    }
    assertTrue(seen.size() > 1, "no samples");
    assertTrue(seen.stream().noneMatch(v -> v.startsWith("not")), seen.get(0));
  }
}
