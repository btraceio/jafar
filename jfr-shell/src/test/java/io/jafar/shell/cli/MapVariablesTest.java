package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MapVariablesTest {

  static class BufferIO implements CommandDispatcher.IO {
    final StringBuilder out = new StringBuilder();
    final StringBuilder err = new StringBuilder();

    @Override
    public void println(String s) {
      out.append(s).append('\n');
    }

    @Override
    public void printf(String fmt, Object... args) {
      out.append(String.format(fmt, args));
    }

    @Override
    public void error(String s) {
      err.append(s).append('\n');
    }

    String text() {
      return out.toString();
    }

    String errors() {
      return err.toString();
    }

    void clear() {
      out.setLength(0);
      err.setLength(0);
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
          JFRSession s = Mockito.mock(JFRSession.class);
          Mockito.when(s.getRecordingPath()).thenReturn(path);
          Mockito.when(s.getAvailableEventTypes()).thenReturn(java.util.Set.of());
          Mockito.when(s.getHandlerCount()).thenReturn(0);
          Mockito.when(s.hasRun()).thenReturn(false);
          return s;
        };
    sm = new SessionManager(ctx, factory);
    io = new BufferIO();
    dispatcher = new CommandDispatcher(sm, io, r -> {});
  }

  @Test
  void setSimpleMapLiteral() {
    dispatcher.dispatch("set config = {\"threshold\": 1000, \"enabled\": true}");
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(output.contains("config"), "Should show config variable");
    assertTrue(output.contains("map{"), "Should show map type");
    assertTrue(output.contains("threshold=1000"), "Should show threshold value");
    assertTrue(output.contains("enabled=true"), "Should show enabled value");
  }

  @Test
  void setEmptyMap() {
    dispatcher.dispatch("set empty = {}");
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(output.contains("empty"), "Should show empty variable");
    assertTrue(output.contains("{}"), "Should show empty map");
  }

  @Test
  void setNestedMap() {
    dispatcher.dispatch(
        "set config = {\"db\": {\"host\": \"localhost\", \"port\": 5432}, \"timeout\": 30}");
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(output.contains("config"), "Should show config variable");
    assertTrue(output.contains("map{"), "Should show map type");
    assertTrue(output.contains("db="), "Should show db key");
  }

  @Test
  void setMapWithNullValue() {
    dispatcher.dispatch("set config = {\"key\": null, \"value\": 42}");
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(output.contains("config"), "Should show config variable");
    assertTrue(output.contains("key=null"), "Should show null value");
    assertTrue(output.contains("value=42"), "Should show numeric value");
  }

  @Test
  void setMapWithStringValues() {
    dispatcher.dispatch("set config = {\"name\": \"test\", \"pattern\": \".*Error.*\"}");
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(output.contains("config"), "Should show config variable");
    assertTrue(output.contains("name=\"test\""), "Should show string value with quotes");
    assertTrue(output.contains("pattern=\".*Error.*\""), "Should show pattern value");
  }

  @Test
  void setMapWithMixedTypes() {
    dispatcher.dispatch(
        "set config = {\"count\": 42, \"ratio\": 3.14, \"enabled\": true, \"name\": \"test\", \"opt\": null}");
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(output.contains("config"), "Should show config variable");
    assertTrue(output.contains("count=42"), "Should show integer");
    assertTrue(output.contains("ratio=3.14"), "Should show double");
    assertTrue(output.contains("enabled=true"), "Should show boolean");
    assertTrue(output.contains("name=\"test\""), "Should show string");
    assertTrue(output.contains("opt=null"), "Should show null");
  }

  @Test
  void invalidMapSyntax() {
    dispatcher.dispatch("set bad = {\"key\": }");
    String errors = io.errors();
    assertTrue(errors.contains("Invalid map literal"), "Should report syntax error");
  }

  @Test
  void mapWithUnquotedKey() {
    dispatcher.dispatch("set bad = {key: \"value\"}");
    String errors = io.errors();
    assertTrue(
        errors.contains("Invalid map literal"), "Should report syntax error for unquoted key");
  }

  @Test
  void accessSimpleMapField() throws Exception {
    dispatcher.dispatch("set config = {\"threshold\": 1000}");
    io.clear();
    dispatcher.dispatch("echo Threshold is ${config.threshold}");
    String output = io.text();
    assertTrue(output.contains("Threshold is 1000"), "Should substitute map field");
  }

  @Test
  void accessNestedMapField() throws Exception {
    dispatcher.dispatch("set config = {\"db\": {\"host\": \"localhost\", \"port\": 5432}}");
    io.clear();
    dispatcher.dispatch("echo Host: ${config.db.host}, Port: ${config.db.port}");
    String output = io.text();
    assertTrue(output.contains("Host: localhost"), "Should substitute nested field");
    assertTrue(output.contains("Port: 5432"), "Should substitute nested numeric field");
  }

  @Test
  void accessDeeplyNestedMapField() throws Exception {
    dispatcher.dispatch(
        "set config = {\"app\": {\"db\": {\"primary\": {\"host\": \"db1.local\"}}}}");
    io.clear();
    dispatcher.dispatch("echo DB: ${config.app.db.primary.host}");
    String output = io.text();
    assertTrue(output.contains("DB: db1.local"), "Should substitute deeply nested field");
  }

  @Test
  void accessNonExistentMapField() throws Exception {
    dispatcher.dispatch("set config = {\"key\": \"value\"}");
    io.clear();
    dispatcher.dispatch("echo Missing: ${config.missing}");
    String output = io.text();
    assertTrue(output.contains("Missing: "), "Should return empty string for missing field");
    assertFalse(output.contains("null"), "Should not show 'null' for missing field");
  }

  @Test
  void accessFieldThroughNullValue() throws Exception {
    dispatcher.dispatch("set config = {\"data\": null, \"valid\": \"value\"}");
    io.clear();
    dispatcher.dispatch("echo Nested: ${config.data.nested}");
    String output = io.text();
    assertTrue(
        output.contains("Nested: "), "Should return empty string when traversing through null");
    assertFalse(output.contains("null"), "Should not show 'null' for path through null field");
  }

  @Test
  void mapSizeProperty() throws Exception {
    dispatcher.dispatch("set config = {\"a\": 1, \"b\": 2, \"c\": 3}");
    io.clear();
    dispatcher.dispatch("echo Size: ${config.size}");
    String output = io.text();
    assertTrue(output.contains("Size: 3"), "Should report map size");
  }

  @Test
  void varsInfoForMap() {
    dispatcher.dispatch("set config = {\"threshold\": 1000, \"enabled\": true}");
    io.clear();
    dispatcher.dispatch("vars --info config");
    String output = io.text();
    assertTrue(output.contains("Type: map"), "Should show type as map");
    assertTrue(output.contains("Size: 2 entries"), "Should show entry count");
    assertTrue(output.contains("Structure:"), "Should show structure");
  }

  @Test
  void unsetMap() {
    dispatcher.dispatch("set config = {\"key\": \"value\"}");
    io.clear();
    dispatcher.dispatch("unset config");
    io.clear();
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(
        output.contains("No variables") || !output.contains("config"),
        "Should not show config after unset");
  }

  @Test
  void mapWithEscapedStrings() {
    dispatcher.dispatch("set config = {\"path\": \"/tmp\\\\test\", \"line\": \"hello\\nworld\"}");
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(output.contains("config"), "Should show config variable");
    assertTrue(
        output.contains("\\\\") || output.contains("\\n"),
        "Should escape special characters in display");
  }

  @Test
  void deepNestingTruncation() {
    // Test that deeply nested maps are truncated in display
    dispatcher.dispatch(
        "set deep = {\"a\": {\"b\": {\"c\": {\"d\": {\"e\": {\"f\": \"value\"}}}}}}");
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(output.contains("deep"), "Should show deep variable");
    assertTrue(output.contains("{...}"), "Should truncate deep nesting");
  }

  @Test
  void manyEntriesTruncation() {
    // Test that maps with many entries are truncated
    dispatcher.dispatch(
        "set many = {\"a\": 1, \"b\": 2, \"c\": 3, \"d\": 4, \"e\": 5, \"f\": 6, \"g\": 7}");
    dispatcher.dispatch("vars");
    String output = io.text();
    assertTrue(output.contains("many"), "Should show many variable");
    assertTrue(output.contains("..."), "Should truncate many entries");
  }

  @Test
  void globalScopeMap() {
    dispatcher.dispatch("set --global config = {\"key\": \"value\"}");
    io.clear();
    dispatcher.dispatch("vars --global");
    String output = io.text();
    assertTrue(output.contains("Global variables"), "Should show global scope");
    assertTrue(output.contains("config"), "Should show config in global scope");
  }

  @Test
  void sessionScopeMap() throws Exception {
    // Open a session first
    dispatcher.dispatch("open /tmp/test.jfr");
    io.clear();
    dispatcher.dispatch("set config = {\"key\": \"value\"}");
    io.clear();
    dispatcher.dispatch("vars --session");
    String output = io.text();
    assertTrue(output.contains("Session variables"), "Should show session scope");
    assertTrue(output.contains("config"), "Should show config in session scope");
  }
}
