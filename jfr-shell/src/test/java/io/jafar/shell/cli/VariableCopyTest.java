package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.core.VariableStore;
import io.jafar.shell.core.VariableStore.MapValue;
import io.jafar.shell.core.VariableStore.ScalarValue;
import io.jafar.shell.core.VariableStore.Value;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for variable-to-variable copying functionality (Bug #3 fix). Ensures that variables can be
 * copied and their properties are preserved.
 */
class VariableCopyTest {

  static class BufferIO implements CommandDispatcher.IO {
    final List<String> output = new ArrayList<>();
    final List<String> errors = new ArrayList<>();

    @Override
    public void println(String s) {
      output.add(s);
    }

    @Override
    public void printf(String fmt, Object... args) {
      output.add(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      errors.add(s);
    }

    boolean hasError(String text) {
      return errors.stream().anyMatch(line -> line.contains(text));
    }

    void clear() {
      output.clear();
      errors.clear();
    }
  }

  ParsingContext ctx;
  SessionManager sm;
  BufferIO io;
  CommandDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    ctx = ParsingContext.create();
    SessionManager.JFRSessionFactory factory =
        (path, c) -> {
          JFRSession session = Mockito.mock(JFRSession.class);
          Mockito.when(session.getRecordingPath()).thenReturn(path);
          Mockito.when(session.getAvailableTypes()).thenReturn(java.util.Set.of());
          Mockito.when(session.getHandlerCount()).thenReturn(0);
          Mockito.when(session.hasRun()).thenReturn(false);
          return session;
        };
    sm = new SessionManager(ctx, factory);
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sm, io, ref -> {});
  }

  // ==================== Scalar Value Copying Tests ====================

  @Test
  void testCopyScalarValue_String() {
    dispatcher.dispatch("set original = \"test_value\"");
    dispatcher.dispatch("set copy = original");

    VariableStore store = dispatcher.getGlobalStore();
    assertTrue(store.contains("original"), "original should exist");
    assertTrue(store.contains("copy"), "copy should exist");

    Value originalValue = store.get("original");
    Value copyValue = store.get("copy");

    assertInstanceOf(ScalarValue.class, originalValue);
    assertInstanceOf(ScalarValue.class, copyValue);
    assertEquals(
        ((ScalarValue) originalValue).value(),
        ((ScalarValue) copyValue).value(),
        "Values should be equal");
  }

  @Test
  void testCopyScalarValue_Number() {
    dispatcher.dispatch("set original = 42");
    dispatcher.dispatch("set copy = original");

    VariableStore store = dispatcher.getGlobalStore();
    Value originalValue = store.get("original");
    Value copyValue = store.get("copy");

    assertInstanceOf(ScalarValue.class, copyValue);
    // Number types should match - both should be same type (Long or Double)
    Object originalNum = ((ScalarValue) originalValue).value();
    Object copyNum = ((ScalarValue) copyValue).value();
    assertEquals(originalNum.getClass(), copyNum.getClass(), "Number types should match");
    assertEquals(42, ((Number) copyNum).intValue(), "Value should be 42");
  }

  @Test
  void testCopyScalarValue_Decimal() {
    dispatcher.dispatch("set original = 3.14");
    dispatcher.dispatch("set copy = original");

    VariableStore store = dispatcher.getGlobalStore();
    Value copyValue = store.get("copy");

    assertInstanceOf(ScalarValue.class, copyValue);
    assertEquals(3.14, ((ScalarValue) copyValue).value());
  }

  // ==================== Map Value Copying Tests ====================

  @Test
  void testCopyMapValue() {
    dispatcher.dispatch("set original = {\"name\": \"test\", \"count\": 100}");
    dispatcher.dispatch("set copy = original");

    VariableStore store = dispatcher.getGlobalStore();
    assertTrue(store.contains("copy"), "copy should exist");

    Value copyValue = store.get("copy");
    assertInstanceOf(MapValue.class, copyValue);

    Map<String, Object> copyMap = ((MapValue) copyValue).value();
    assertEquals("test", copyMap.get("name"));
    assertEquals(100, ((Number) copyMap.get("count")).intValue(), "count should be 100");
  }

  @Test
  void testCopyMapValue_NestedAccess() {
    dispatcher.dispatch("set original = {\"db\": {\"host\": \"localhost\", \"port\": 5432}}");
    dispatcher.dispatch("set copy = original");

    VariableStore store = dispatcher.getGlobalStore();
    Value copyValue = store.get("copy");
    assertInstanceOf(MapValue.class, copyValue);

    Object dbHost = ((MapValue) copyValue).getField("db.host");
    assertEquals("localhost", dbHost);

    Object dbPort = ((MapValue) copyValue).getField("db.port");
    assertEquals(5432, ((Number) dbPort).intValue(), "port should be 5432");
  }

  // ==================== Dollar-Prefix Syntax Tests ====================

  @Test
  void testCopyWithDollarPrefix() {
    dispatcher.dispatch("set original = \"value\"");
    dispatcher.dispatch("set copy = $original");

    VariableStore store = dispatcher.getGlobalStore();
    assertTrue(store.contains("copy"));

    Value copyValue = store.get("copy");
    assertInstanceOf(ScalarValue.class, copyValue);
    assertEquals("value", ((ScalarValue) copyValue).value());
  }

  @Test
  void testCopyWithDollarPrefix_NotFound() {
    dispatcher.dispatch("set copy = $nonexistent");

    assertTrue(io.hasError("Variable not found"), "Should error for undefined variable");
    assertFalse(dispatcher.getGlobalStore().contains("copy"), "copy should not be created");
  }

  // ==================== Variable Substitution Tests ====================

  @Test
  void testCopyWithBraceSubstitution() {
    dispatcher.dispatch("set original = \"test\"");
    dispatcher.dispatch("set copy = \"${original}\"");

    VariableStore store = dispatcher.getGlobalStore();
    Value copyValue = store.get("copy");

    assertInstanceOf(ScalarValue.class, copyValue);
    assertEquals("test", ((ScalarValue) copyValue).value());
  }

  @Test
  void testCopyWithSubstitutionInExpression() {
    dispatcher.dispatch("set prefix = \"hello\"");
    dispatcher.dispatch("set suffix = \"world\"");
    dispatcher.dispatch("set combined = \"${prefix}_${suffix}\"");

    VariableStore store = dispatcher.getGlobalStore();
    Value combinedValue = store.get("combined");

    assertInstanceOf(ScalarValue.class, combinedValue);
    assertEquals("hello_world", ((ScalarValue) combinedValue).value());
  }

  // ==================== Original Bug Reproduction ====================

  @Test
  void testOriginalBug_VariableCopyPreservesProperties() {
    // Simulate: set jdk_exec_count = ... (some query result with properties)
    // Then: set exec_count = jdk_exec_count
    // Expected: exec_count should have the same properties as jdk_exec_count

    Map<String, Object> queryResult = new HashMap<>();
    queryResult.put("count", 264L);
    queryResult.put("eventType", "jdk.ExecutionSample");

    dispatcher.getGlobalStore().set("jdk_exec_count", new MapValue(queryResult));

    // Now copy it
    dispatcher.dispatch("set exec_count = jdk_exec_count");

    VariableStore store = dispatcher.getGlobalStore();
    assertTrue(store.contains("exec_count"), "exec_count should exist");

    Value execCountValue = store.get("exec_count");
    assertInstanceOf(MapValue.class, execCountValue);

    Map<String, Object> copiedMap = ((MapValue) execCountValue).value();
    assertEquals(264L, copiedMap.get("count"), "count property should be preserved");
    assertEquals(
        "jdk.ExecutionSample",
        copiedMap.get("eventType"),
        "eventType property should be preserved");
  }

  // ==================== Multiple Copies Tests ====================

  @Test
  void testMultipleCopies() {
    dispatcher.dispatch("set original = \"value\"");
    dispatcher.dispatch("set copy1 = original");
    dispatcher.dispatch("set copy2 = original");
    dispatcher.dispatch("set copy3 = copy1");

    VariableStore store = dispatcher.getGlobalStore();

    assertTrue(store.contains("original"));
    assertTrue(store.contains("copy1"));
    assertTrue(store.contains("copy2"));
    assertTrue(store.contains("copy3"));

    assertEquals(
        ((ScalarValue) store.get("original")).value(), ((ScalarValue) store.get("copy1")).value());
    assertEquals(
        ((ScalarValue) store.get("original")).value(), ((ScalarValue) store.get("copy2")).value());
    assertEquals(
        ((ScalarValue) store.get("original")).value(), ((ScalarValue) store.get("copy3")).value());
  }

  // ==================== Global vs Session Store Tests ====================

  @Test
  void testCopyBetweenStores() {
    // Set in global store
    dispatcher.dispatch("set --global original = \"global_value\"");

    // Copy to session store (requires a session)
    // Since we don't have a real session in this test, this will copy within global
    dispatcher.dispatch("set copy = original");

    VariableStore global = dispatcher.getGlobalStore();
    assertTrue(global.contains("copy"));
    assertEquals("global_value", ((ScalarValue) global.get("copy")).value());
  }

  // ==================== Edge Cases ====================

  @Test
  void testCopyNonExistentVariable_BareNameFallsThrough() {
    // Bare name that doesn't exist as variable should try JfrPath parsing
    // Without a session, this should error
    dispatcher.dispatch("set copy = nonexistent_var");

    // Should error because no session is open for query evaluation
    assertTrue(
        io.hasError("No session open") || io.hasError("Invalid query"),
        "Should error when trying to parse as query");
  }

  @Test
  void testCopySelfReference() {
    dispatcher.dispatch("set var = \"original\"");
    dispatcher.dispatch("set var = var");

    VariableStore store = dispatcher.getGlobalStore();
    Value varValue = store.get("var");

    assertInstanceOf(ScalarValue.class, varValue);
    assertEquals("original", ((ScalarValue) varValue).value());
  }

  @Test
  void testCopyOverwritesExisting() {
    dispatcher.dispatch("set target = \"old_value\"");
    dispatcher.dispatch("set source = \"new_value\"");
    dispatcher.dispatch("set target = source");

    VariableStore store = dispatcher.getGlobalStore();
    Value targetValue = store.get("target");

    assertEquals("new_value", ((ScalarValue) targetValue).value());
  }

  // ==================== Complex Map Tests ====================

  @Test
  void testCopyComplexMap() {
    dispatcher.dispatch(
        "set config = {\"db\": {\"host\": \"localhost\", \"port\": 5432}, \"app\": {\"name\": \"test\"}}");
    dispatcher.dispatch("set backup = config");

    VariableStore store = dispatcher.getGlobalStore();
    Value backupValue = store.get("backup");

    assertInstanceOf(MapValue.class, backupValue);
    assertEquals("localhost", ((MapValue) backupValue).getField("db.host"));
    assertEquals(5432, ((Number) ((MapValue) backupValue).getField("db.port")).intValue());
    assertEquals("test", ((MapValue) backupValue).getField("app.name"));
  }

  // ==================== Backward Compatibility Tests ====================

  @Test
  void testStringLiteralsStillWork() {
    dispatcher.dispatch("set var = \"literal string\"");

    Value value = dispatcher.getGlobalStore().get("var");
    assertInstanceOf(ScalarValue.class, value);
    assertEquals("literal string", ((ScalarValue) value).value());
  }

  @Test
  void testNumericLiteralsStillWork() {
    dispatcher.dispatch("set num = 123");

    Value value = dispatcher.getGlobalStore().get("num");
    assertInstanceOf(ScalarValue.class, value);
    assertEquals(123, ((Number) ((ScalarValue) value).value()).intValue());
  }

  @Test
  void testMapLiteralsStillWork() {
    dispatcher.dispatch("set map = {\"key\": \"value\", \"num\": 42}");

    Value value = dispatcher.getGlobalStore().get("map");
    assertInstanceOf(MapValue.class, value);
    assertEquals("value", ((MapValue) value).value().get("key"));
    assertEquals(42, ((Number) ((MapValue) value).value().get("num")).intValue());
  }
}
