/**
 * Internal implementation details of the JAFAR parser.
 *
 * <p><b>WARNING: This entire package is internal and subject to change without notice.</b>
 *
 * <p>Classes in this package are implementation details and should not be used directly by external
 * code. They may be removed, renamed, or have their behavior changed at any time, even in minor or
 * patch releases.
 *
 * <p>Some public APIs (e.g., {@link io.jafar.parser.api.UntypedJafarParser.EventHandler})
 * unfortunately expose types from this package in their signatures. When using these APIs:
 *
 * <ul>
 *   <li>Avoid explicitly importing types from this package
 *   <li>Use type inference (var, lambda parameters) where possible
 *   <li>Do not call methods on these types beyond what the public API requires
 * </ul>
 *
 * <p>If you need functionality from this package, please file an issue requesting a public API.
 *
 * @since 0.1.0
 */
@io.jafar.parser.api.Internal(
    "This package contains internal implementation details. Use public APIs in io.jafar.parser.api instead.")
package io.jafar.parser.internal_api;
