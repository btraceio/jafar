package io.jafar.parser.internal_api;

/**
 * Abstraction for defining generated classes at runtime. Implementations may use different JDK
 * mechanisms depending on the runtime version (e.g., hidden classes on 15+, Lookup#defineClass on
 * 9+, or Unsafe#defineAnonymousClass on 8).
 */
public interface ClassDefiner {
  /** Human-readable name of the strategy (e.g., "hidden", "lookup", "unsafe", "loader"). */
  String name();

  /**
   * Define the provided class bytes associated with the given host class.
   *
   * @param bytes the class file bytes (bytecode)
   * @param host the host/anchor class to associate with
   * @return the defined Class
   * @throws Throwable if definition fails
   */
  Class<?> define(byte[] bytes, Class<?> host) throws Throwable;
}
