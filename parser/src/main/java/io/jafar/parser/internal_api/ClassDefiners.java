package io.jafar.parser.internal_api;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Factory for ClassDefiner strategies with reflective implementations. */
public final class ClassDefiners {
  private static final String PROP = "jafar.classdefiner"; // hidden|lookup|unsafe|loader
  private static volatile ClassDefiner CACHED;

  private ClassDefiners() {}

  public static ClassDefiner best() {
    ClassDefiner local = CACHED;
    if (local != null) return local;

    String forced = System.getProperty(PROP);
    if (forced != null) {
      local = byName(forced.trim());
      return CACHED = local;
    }

    // Probe in order of preference
    try {
      // privateLookupIn exists since 9, defineHiddenClass since 15
      Class<?> lookupClz = MethodHandles.lookup().getClass();
      Class<?> mhClz = MethodHandles.class;
      Method privateLookupIn =
          mhClz.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
      // ClassOption enum presence implies 15+
      Class<?> classOption = Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
      Method defineHiddenClass =
          lookupClz.getMethod(
              "defineHiddenClass",
              byte[].class,
              boolean.class,
              Array.newInstance(classOption, 0).getClass());
      if (privateLookupIn != null && defineHiddenClass != null) {
        local = new HiddenDefiner();
        return CACHED = local;
      }
    } catch (Throwable ignored) {
      // fall through
    }

    try {
      // 9+: Lookup#defineClass(byte[])
      Class<?> lookupClz = MethodHandles.lookup().getClass();
      Method defineClass = lookupClz.getMethod("defineClass", byte[].class);
      Method privateLookupIn =
          MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
      if (defineClass != null && privateLookupIn != null) {
        local = new LookupDefiner();
        return CACHED = local;
      }
    } catch (Throwable ignored) {
      // fall through
    }

    try {
      // 8: Unsafe#defineAnonymousClass
      Class<?> unsafeClz = Class.forName("sun.misc.Unsafe");
      Field f = unsafeClz.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      Object unsafe = f.get(null);
      Method m =
          unsafeClz.getMethod("defineAnonymousClass", Class.class, byte[].class, Object[].class);
      if (unsafe != null && m != null) {
        local = new UnsafeDefiner(unsafe, m);
        return CACHED = local;
      }
    } catch (Throwable ignored) {
      // fall through
    }

    // Last resort: use ClassLoader#defineClass
    local = new LoaderDefiner();
    return CACHED = local;
  }

  public static ClassDefiner byName(String name) {
    String n = name.toLowerCase();
    switch (n) {
      case "hidden":
        return new HiddenDefiner();
      case "lookup":
        return new LookupDefiner();
      case "unsafe":
        try {
          Class<?> unsafeClz = Class.forName("sun.misc.Unsafe");
          Field f = unsafeClz.getDeclaredField("theUnsafe");
          f.setAccessible(true);
          Object unsafe = f.get(null);
          Method m =
              unsafeClz.getMethod(
                  "defineAnonymousClass", Class.class, byte[].class, Object[].class);
          return new UnsafeDefiner(unsafe, m);
        } catch (Throwable t) {
          throw new IllegalStateException("Unsafe not available", t);
        }
      case "loader":
        return new LoaderDefiner();
      default:
        throw new IllegalArgumentException("Unknown definer: " + name);
    }
  }

  private static final class HiddenDefiner implements ClassDefiner {
    HiddenDefiner() {}

    @Override
    public String name() {
      return "hidden";
    }

    @Override
    public Class<?> define(byte[] bytes, Class<?> host) throws Throwable {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      Method privateLookupIn =
          MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
      Object hostLookup = privateLookupIn.invoke(null, host, lookup);

      Class<?> lookupClz = hostLookup.getClass();
      Class<?> classOption = Class.forName("java.lang.invoke.MethodHandles$Lookup$ClassOption");
      Object opts = Array.newInstance(classOption, 1);
      Array.set(opts, 0, Enum.valueOf((Class) classOption, "NESTMATE"));
      Method defineHiddenClass =
          lookupClz.getMethod("defineHiddenClass", byte[].class, boolean.class, opts.getClass());
      Object hiddenLookup = defineHiddenClass.invoke(hostLookup, bytes, Boolean.TRUE, opts);
      Method lookupClass = hiddenLookup.getClass().getMethod("lookupClass");
      return (Class<?>) lookupClass.invoke(hiddenLookup);
    }
  }

  private static final class LookupDefiner implements ClassDefiner {
    LookupDefiner() {}

    @Override
    public String name() {
      return "lookup";
    }

    @Override
    public Class<?> define(byte[] bytes, Class<?> host) throws Throwable {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      Method privateLookupIn =
          MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
      Object hostLookup = privateLookupIn.invoke(null, host, lookup);
      Method defineClass = hostLookup.getClass().getMethod("defineClass", byte[].class);
      return (Class<?>) defineClass.invoke(hostLookup, bytes);
    }
  }

  private static final class UnsafeDefiner implements ClassDefiner {
    private final Object unsafe;
    private final Method defineAnonymousClass;

    UnsafeDefiner(Object unsafe, Method defineAnonymousClass) {
      this.unsafe = unsafe;
      this.defineAnonymousClass = defineAnonymousClass;
    }

    @Override
    public String name() {
      return "unsafe";
    }

    @Override
    public Class<?> define(byte[] bytes, Class<?> host) throws Throwable {
      return (Class<?>) defineAnonymousClass.invoke(unsafe, host, bytes, null);
    }
  }

  private static final class LoaderDefiner implements ClassDefiner {
    LoaderDefiner() {}

    @Override
    public String name() {
      return "loader";
    }

    @Override
    public Class<?> define(byte[] bytes, Class<?> host) throws Throwable {
      ClassLoader cl = host != null ? host.getClassLoader() : ClassLoader.getSystemClassLoader();
      if (cl == null) cl = ClassLoader.getSystemClassLoader();
      Method m =
          ClassLoader.class.getDeclaredMethod(
              "defineClass", String.class, byte[].class, int.class, int.class);
      m.setAccessible(true);
      return (Class<?>) m.invoke(cl, null, bytes, 0, bytes.length);
    }
  }
}
