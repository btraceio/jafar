package io.jafar.parser.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates, at build time, the {@link JfrType} interface for a {@code jdk.jfr.Event} subclass
 * (#89), so that the event class written for recording can be read back with the typed API without
 * declaring its fields a second time. Needs {@code jafar-processor} as an annotation processor.
 *
 * <pre>{@code
 * @GenerateJfrType
 * @Name("com.example.Request")
 * public class RequestEvent extends jdk.jfr.Event {
 *   String requestId;
 *   long bytes;
 * }
 *
 * // generated: @JfrType("com.example.Request") public interface RequestEventJfr { ... }
 * parser.handle(RequestEventJfr.class, (e, ctl) -> use(e.requestId(), e.bytes()));
 * }</pre>
 *
 * <p>The interface, in the event class's package, has an accessor for each field JFR records for
 * the class: its non-static, non-transient fields (its superclasses' too) of a primitive type or
 * {@code String}, named as recorded (the field's {@code @jdk.jfr.Name}, else its own name; never
 * its {@code @Label}), plus {@code startTime()} and {@code duration()} in ticks and {@code
 * eventThread()}. {@code Thread} fields use a generated {@code JfrThread} interface in the same
 * package. Fields of other types ({@code Class}, arrays, ...) are left out, with a note. The
 * handler is the one {@code jafar-processor} generates for any {@code @JfrType} interface: events
 * are read lazily into a reused instance, as with a hand-written interface; no instance of the
 * event class itself is ever created.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface GenerateJfrType {
  /**
   * Simple name of the generated interface. Empty (the default): the event class's simple name,
   * prefixed by its enclosing classes' for a nested class, followed by {@code Jfr}.
   *
   * @return the interface's simple name
   */
  String value() default "";
}
