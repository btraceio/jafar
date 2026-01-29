/**
 * Technology Compatibility Kit (TCK) for JFR Shell backend plugins.
 *
 * <p>This package provides an executable test runner that backend plugin authors can use to
 * validate their implementations conform to the expected behavior.
 *
 * <h2>Usage</h2>
 *
 * <p>Run the TCK against your backend JAR:
 *
 * <pre>{@code
 * java -jar jfr-shell-tck-all.jar my-backend.jar [test-recording.jfr]
 * }</pre>
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li><b>my-backend.jar</b> - Path to your backend plugin JAR
 *   <li><b>test-recording.jfr</b> - Optional path to a test JFR file (default: built-in 2.3MB file)
 * </ul>
 *
 * <p>Your backend JAR must:
 *
 * <ul>
 *   <li>Implement {@link io.jafar.shell.backend.JfrBackend}
 *   <li>Register via META-INF/services/io.jafar.shell.backend.JfrBackend
 * </ul>
 *
 * <h2>Test Categories</h2>
 *
 * <p>The TCK validates:
 *
 * <ul>
 *   <li><b>Identity:</b> Backend has valid ID, name, and reasonable priority
 *   <li><b>Capability consistency:</b> Declared capabilities match actual behavior
 *   <li><b>Event streaming:</b> Events are properly streamed
 *   <li><b>Metadata:</b> Metadata classes can be loaded with field details
 *   <li><b>Chunks:</b> Chunk information is accurately reported (if supported)
 *   <li><b>Constant pools:</b> Constant pool types and entries are accessible (if supported)
 * </ul>
 *
 * <h2>Memory Considerations</h2>
 *
 * <p>The built-in test file is small (2.3MB) to avoid OOM issues with backends that load more data
 * into memory. If your backend can handle larger files, you can provide your own test recording.
 *
 * @see TckRunner
 * @see BackendTck
 */
package io.jafar.shell.backend.tck;
