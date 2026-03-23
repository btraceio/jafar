package io.jafar.shell.cli;

import io.jafar.shell.core.FlameNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generates a self-contained interactive HTML flamegraph file and opens it in the browser. */
public final class FlameGraphHtmlRenderer {

  private FlameGraphHtmlRenderer() {}

  public static void render(FlameNode root, CommandDispatcher.IO io) throws IOException {
    String html = buildHtml(root);
    Path tmp = writeTempFile(html);
    io.println("Flamegraph: file://" + tmp.toAbsolutePath());
  }

  static String toJson(FlameNode node) {
    StringBuilder sb = new StringBuilder();
    appendNode(sb, node);
    return sb.toString();
  }

  private static void appendNode(StringBuilder sb, FlameNode node) {
    long childSum = 0;
    for (FlameNode child : node.children.values()) childSum += child.value;
    long selfValue = node.value - childSum;
    sb.append("{\"name\":\"");
    appendEscaped(sb, node.name);
    sb.append("\",\"value\":").append(selfValue);
    sb.append(",\"children\":[");
    boolean first = true;
    for (FlameNode child : node.children.values()) {
      if (!first) sb.append(',');
      appendNode(sb, child);
      first = false;
    }
    sb.append("]}");
  }

  private static void appendEscaped(StringBuilder sb, String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
  }

  static String buildHtml(FlameNode root) throws IOException {
    String json = toJson(root);
    String d3js = loadResource("d3.min.js");
    String fgjs = loadResource("d3-flamegraph.min.js");
    String fgcss = loadResource("d3-flamegraph.css");
    return "<!DOCTYPE html>\n"
        + "<html lang=\"en\">\n"
        + "<head>\n"
        + "<meta charset=\"UTF-8\">\n"
        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
        + "<title>Jafar Flamegraph</title>\n"
        + "<style>"
        + fgcss
        + "</style>\n"
        + "<style>\n"
        + "* { box-sizing: border-box; margin: 0; padding: 0; }\n"
        + "html, body { height: 100%; background: #1e1e1e; color: #d4d4d4;"
        + " font-family: system-ui, -apple-system, sans-serif; }\n"
        + "#header { padding: 8px 12px; background: #252526;"
        + " border-bottom: 1px solid #3c3c3c; display: flex; align-items: center;"
        + " gap: 10px; flex-wrap: wrap; }\n"
        + "#title { font-weight: bold; font-size: 14px; color: #cccccc; white-space: nowrap; }\n"
        + "#search { background: #3c3c3c; border: 1px solid #555; border-radius: 3px;"
        + " color: #d4d4d4; font-size: 12px; padding: 4px 8px; outline: none; width: 240px; }\n"
        + "#search::placeholder { color: #666; }\n"
        + "#search:focus { border-color: #007acc; }\n"
        + "#btn-reset { background: #3c3c3c; border: 1px solid #555; border-radius: 3px;"
        + " color: #d4d4d4; font-size: 12px; padding: 4px 10px; cursor: pointer;"
        + " white-space: nowrap; }\n"
        + "#btn-reset:hover { background: #4c4c4c; }\n"
        + "#details { flex: 1; font-size: 12px; color: #9cdcfe; overflow: hidden;"
        + " text-overflow: ellipsis; white-space: nowrap; font-family: monospace; min-width: 0; }\n"
        + "#chart { width: 100%; }\n"
        + ".d3-flame-graph text { font-family: ui-monospace, Menlo, Consolas, monospace !important; }\n"
        + ".d3-flame-graph-label { font-family: ui-monospace, Menlo, Consolas, monospace !important;"
        + " font-size: 11px !important; }\n"
        + ".d3-flame-graph-tip { font-family: monospace !important; font-size: 12px !important;"
        + " line-height: 1.5 !important; max-width: none !important; }\n"
        + "</style>\n"
        + "</head>\n"
        + "<body>\n"
        + "<div id=\"header\">\n"
        + "  <span id=\"title\">Jafar Flamegraph</span>\n"
        + "  <input id=\"search\" type=\"text\""
        + " placeholder=\"search\u2026 (Enter to highlight, Esc to clear)\" autocomplete=\"off\">\n"
        + "  <button id=\"btn-reset\">Reset Zoom</button>\n"
        + "  <div id=\"details\"></div>\n"
        + "</div>\n"
        + "<div id=\"chart\"></div>\n"
        + "<script>"
        + d3js
        + "</script>\n"
        + "<script>"
        + fgjs
        + "</script>\n"
        + "<script>\n"
        + "const DATA = "
        + json
        + ";\n"
        + "\n"
        + "const chart = flamegraph()\n"
        + "  .width(document.getElementById('chart').clientWidth || window.innerWidth)\n"
        + "  .cellHeight(20)\n"
        + "  .transitionDuration(400)\n"
        + "  .minFrameSize(5)\n"
        + "  .selfValue(true)\n"
        + "  .sort(true)\n"
        + "  .title('')\n"
        + "  .details(document.getElementById('details'));\n"
        + "\n"
        + "d3.select('#chart').datum(DATA).call(chart);\n"
        + "\n"
        + "const searchEl = document.getElementById('search');\n"
        + "searchEl.addEventListener('keydown', function(e) {\n"
        + "  if (e.key === 'Enter') { e.preventDefault(); chart.search(this.value.trim()); }\n"
        + "  if (e.key === 'Escape') { this.value = ''; chart.clear(); }\n"
        + "});\n"
        + "searchEl.addEventListener('input', function() {\n"
        + "  if (!this.value) chart.clear();\n"
        + "});\n"
        + "\n"
        + "document.getElementById('btn-reset').addEventListener('click', function() {\n"
        + "  chart.resetZoom();\n"
        + "});\n"
        + "\n"
        + "window.addEventListener('resize', function() {\n"
        + "  chart.width(document.getElementById('chart').clientWidth);\n"
        + "  chart.update();\n"
        + "});\n"
        + "</script>\n"
        + "</body>\n"
        + "</html>\n";
  }

  private static String loadResource(String name) throws IOException {
    String path = "/io/jafar/shell/cli/" + name;
    try (InputStream in = FlameGraphHtmlRenderer.class.getResourceAsStream(path)) {
      if (in == null) throw new IOException("Resource not found: " + path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static Path writeTempFile(String html) throws IOException {
    Path tmp = Files.createTempFile("jafar-flamegraph-", ".html");
    Files.write(tmp, html.getBytes(StandardCharsets.UTF_8));
    return tmp;
  }
}
