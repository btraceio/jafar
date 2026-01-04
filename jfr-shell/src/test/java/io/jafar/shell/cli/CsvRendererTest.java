package io.jafar.shell.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CsvRendererTest {

  @Test
  void rendersSimpleData() {
    List<Map<String, Object>> rows =
        List.of(Map.of("name", "Alice", "age", 30), Map.of("name", "Bob", "age", 25));

    StringBuilder out = new StringBuilder();
    CommandDispatcher.IO io = new TestIO(out);
    CsvRenderer.render(rows, io);

    String csv = out.toString();
    assertTrue(csv.contains("name,age") || csv.contains("age,name"));
    assertTrue(csv.contains("Alice"));
    assertTrue(csv.contains("30"));
    assertTrue(csv.contains("Bob"));
    assertTrue(csv.contains("25"));
  }

  @Test
  void escapesCommasAndQuotes() {
    List<Map<String, Object>> rows =
        List.of(Map.of("text", "hello, world"), Map.of("text", "say \"hello\""));

    StringBuilder out = new StringBuilder();
    CommandDispatcher.IO io = new TestIO(out);
    CsvRenderer.render(rows, io);

    String csv = out.toString();
    assertTrue(csv.contains("\"hello, world\""));
    assertTrue(csv.contains("\"say \"\"hello\"\"\""));
  }

  @Test
  void handlesNullAndEmpty() {
    Map<String, Object> row1 = new HashMap<>();
    row1.put("a", "value");
    row1.put("b", null);
    Map<String, Object> row2 = Map.of("a", "");

    List<Map<String, Object>> rows = List.of(row1, row2);

    StringBuilder out = new StringBuilder();
    CommandDispatcher.IO io = new TestIO(out);
    CsvRenderer.render(rows, io);

    String csv = out.toString();
    assertTrue(csv.contains("a,b") || csv.contains("b,a"));
    String[] lines = csv.split("\n");
    assertTrue(lines.length >= 3); // header + 2 data rows
  }

  @Test
  void flattensCollections() {
    List<Map<String, Object>> rows = List.of(Map.of("items", List.of("a", "b", "c")));

    StringBuilder out = new StringBuilder();
    CommandDispatcher.IO io = new TestIO(out);
    CsvRenderer.render(rows, io);

    String csv = out.toString();
    assertTrue(csv.contains("a | b | c"));
  }

  @Test
  void rendersEmptyResult() {
    List<Map<String, Object>> rows = List.of();

    StringBuilder out = new StringBuilder();
    CommandDispatcher.IO io = new TestIO(out);
    CsvRenderer.render(rows, io);

    String csv = out.toString();
    assertTrue(csv.contains("(no rows)"));
  }

  @Test
  void rendersSingleValue() {
    List<String> values = List.of("value1", "value2");

    StringBuilder out = new StringBuilder();
    CommandDispatcher.IO io = new TestIO(out);
    CsvRenderer.renderValues(values, io);

    String csv = out.toString();
    assertTrue(csv.contains("value"));
    assertTrue(csv.contains("value1"));
    assertTrue(csv.contains("value2"));
  }

  @Test
  void escapesNewlines() {
    List<Map<String, Object>> rows = List.of(Map.of("text", "line1\nline2"));

    StringBuilder out = new StringBuilder();
    CommandDispatcher.IO io = new TestIO(out);
    CsvRenderer.render(rows, io);

    String csv = out.toString();
    assertTrue(csv.contains("\"line1\nline2\""));
  }

  private static class TestIO implements CommandDispatcher.IO {
    private final StringBuilder out;

    TestIO(StringBuilder out) {
      this.out = out;
    }

    @Override
    public void println(String line) {
      out.append(line).append('\n');
    }

    @Override
    public void printf(String fmt, Object... args) {
      out.append(String.format(fmt, args));
    }

    @Override
    public void error(String msg) {
      out.append("ERROR: ").append(msg).append('\n');
    }
  }
}
