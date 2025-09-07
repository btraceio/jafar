/**
 * Public API for the Jafar JFR parser.
 *
 * <p><b>Modes</b>
 *
 * <ul>
 *   <li><b>Typed</b>: {@link io.jafar.parser.api.TypedJafarParser} maps events to user-defined
 *       interfaces annotated with {@link io.jafar.parser.api.JfrType} and accessor methods
 *       (optionally {@link io.jafar.parser.api.JfrField}). Handlers are {@link
 *       io.jafar.parser.api.JFRHandler}.
 *   <li><b>Untyped</b>: {@link io.jafar.parser.api.UntypedJafarParser} exposes events as {@code
 *       Map<String, Object>}.
 * </ul>
 *
 * <p><b>Lifecycle</b>
 *
 * <ul>
 *   <li>Create and <b>reuse</b> a {@link io.jafar.parser.api.ParsingContext} across sessions to
 *       enable caching.
 *   <li>Parsers are {@link java.lang.AutoCloseable}; prefer try-with-resources.
 *   <li>Handlers are invoked synchronously on the parser thread; keep them fast or offload work.
 * </ul>
 *
 * <p><b>Example</b>
 *
 * <pre>{@code
 * ParsingContext ctx = ParsingContext.create();
 * try (TypedJafarParser p = ctx.newTypedParser(path)) {
 *   HandlerRegistration<MyEvent> reg =
 *       p.handle(MyEvent.class, (e, ctl) -> {
 *         // process event
 *       });
 *   p.run();
 *   reg.destroy(p);
 * }
 * }</pre>
 */
package io.jafar.parser.api;
