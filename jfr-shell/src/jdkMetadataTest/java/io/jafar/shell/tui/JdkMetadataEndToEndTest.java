package io.jafar.shell.tui;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.JfrTuiAdapter;
import io.jafar.shell.backend.BackendRegistry;
import io.jafar.shell.cli.CommandDispatcher;
import io.jafar.shell.core.SessionManager;
import io.jafar.shell.providers.MetadataProvider;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import jdk.jfr.AnnotationElement;
import jdk.jfr.Description;
import jdk.jfr.EventFactory;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.ValueDescriptor;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real recordings, real adapters and HTTP transport; no Jafar backend plugin on this classpath. */
class JdkMetadataEndToEndTest {
  private static final String EARLY = "test.metadata.Early";
  private static final String LATE = "test.metadata.Late";

  @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS)
  static Path dir;

  private static Path recording;

  @BeforeAll
  static void fixture() throws Exception {
    assertEquals(
        List.of("jdk"),
        BackendRegistry.getInstance().listAll().stream().map(b -> b.getId()).toList());
    assertThrows(
        ClassNotFoundException.class,
        () -> Class.forName("io.jafar.shell.backend.impl.JafarBackend"));
    Path first = record("first.jfr", EARLY, "Early metadata event");
    Path second = record("second.jfr", LATE, "Late metadata event");
    // Concatenating complete JFR chunks avoids timing-dependent recording rotation.
    byte[] a = Files.readAllBytes(first);
    byte[] b = Files.readAllBytes(second);
    assertEquals(a.length, ByteBuffer.wrap(a).getLong(8));
    assertEquals(b.length, ByteBuffer.wrap(b).getLong(8));
    recording = dir.resolve("multi-chunk.jfr");
    Files.write(recording, a);
    Files.write(recording, b, java.nio.file.StandardOpenOption.APPEND);
    try (RecordingFile file = new RecordingFile(first)) {
      assertFalse(file.readEventTypes().stream().anyMatch(t -> t.getName().equals(LATE)));
    }
    assertEquals(
        List.of(EARLY, LATE),
        RecordingFile.readAllEvents(recording).stream()
            .map(e -> e.getEventType().getName())
            .toList());
  }

  private static Path record(String filename, String name, String label) throws Exception {
    EventFactory factory =
        EventFactory.create(
            List.of(
                new AnnotationElement(Name.class, name),
                new AnnotationElement(Label.class, label),
                new AnnotationElement(Description.class, "Metadata fixture for " + name)),
            List.of(
                new ValueDescriptor(Thread.class, "worker"),
                new ValueDescriptor(Class.class, "payloadClass")));
    try (Recording r = new Recording()) {
      r.enable(name).withStackTrace();
      r.start();
      var event = factory.newEvent();
      event.set(0, Thread.currentThread());
      event.set(1, JdkMetadataEndToEndTest.class);
      event.commit();
      r.stop();
      Path result = dir.resolve(filename);
      r.dump(result);
      return result;
    } finally {
      factory.unregister();
    }
  }

  @Test
  void tuiListsLaterChunkTypesAndFollowsInternalTypeReferences() throws Exception {
    {
      ParsingContext context = ParsingContext.create();
      SessionManager<JFRSession> sessions =
          new SessionManager<>((p, c) -> new JFRSession(p, (ParsingContext) c), context);
      try {
        var session = sessions.open(recording, null).session;
        var adapter = new JfrTuiAdapter(sessions, null);
        var summary = adapter.loadBrowseSummary(session, "metadata");
        var names = summary.stream().map(m -> m.get("name")).toList();
        assertTrue(
            names.containsAll(
                List.of(
                    EARLY,
                    LATE,
                    "java.lang.Thread",
                    "java.lang.Class",
                    "jdk.types.StackTrace",
                    "jdk.types.Method",
                    "java.lang.String")),
            names.toString());
        assertEquals(names.size(), names.stream().distinct().count());
        assertTrue(session.getAvailableTypes().containsAll(List.of(EARLY, LATE)));

        TuiContext ctx = new TuiContext();
        var tab = new TuiContext.ResultTab("metadata");
        ctx.tabs.add(tab);
        tab.tableData = summary;
        var browser = new TuiBrowserController(ctx, sessions, new TuiDetailBuilder(ctx));
        browser.setTuiAdapter(adapter);
        browser.enterMetadataBrowserMode(tab, adapter.describeBrowseCategory("metadata"));
        var executor = new TuiCommandExecutor(ctx, null);
        var keys =
            new TuiKeyHandler(
                ctx, timeout -> -2, browser, executor, new TuiDetailBuilder(ctx), sessions);
        keys.handleKey('/');
        for (char c : LATE.toCharArray()) keys.handleKey(c);
        keys.handleKey(13); // Confirm the sidebar search.
        keys.handleKey(13); // Open the selected matching type.
        assertTrue(
            String.join("\n", tab.lines).contains("Late metadata event"), tab.lines.toString());
        assertTrue(
            String.join("\n", tab.lines).contains("Metadata fixture for " + LATE),
            tab.lines.toString());
        assertTrue(String.join("\n", tab.lines).contains("worker"), tab.lines.toString());
        assertTrue(ctx.metadataBrowserLineRefs.contains("java.lang.Thread"));
        ctx.sidebarFocused = false;
        tab.selectedRow = ctx.metadataBrowserLineRefs.indexOf("java.lang.Thread");
        new TuiKeyHandler(ctx, timeout -> -2, browser, null, new TuiDetailBuilder(ctx), sessions)
            .handleKey(13);
        assertTrue(String.join("\n", tab.lines).contains("javaName"), tab.lines.toString());
        browser.loadMetadataDetail("jdk.types.StackTrace", false);
        assertTrue(String.join("\n", tab.lines).contains("frames"), tab.lines.toString());
        assertTrue(ctx.metadataBrowserLineRefs.contains("jdk.types.StackFrame"));
        assertNotNull(MetadataProvider.loadClass(recording, "jdk.types.Method"));
        assertNotNull(MetadataProvider.loadField(recording, "java.lang.Thread", "javaName"));
        assertEquals(
            1,
            MetadataProvider.loadField(recording, "jdk.types.StackTrace", "frames")
                .get("dimension"));
        var classes = MetadataProvider.loadAllClasses(recording);
        assertEquals(classes.size(), classes.stream().map(m -> m.get("name")).distinct().count());
        assertEquals(classes, MetadataProvider.loadAllClasses(recording));
      } finally {
        sessions.closeAll();
      }
    }
  }

  @Test
  void cancelingSidebarSearchRestoresVisibleSelection() throws Exception {
    checkSidebarSearchClearing("cancel");
  }

  @Test
  void dismissingConfirmedSidebarSearchRestoresVisibleSelection() throws Exception {
    checkSidebarSearchClearing("confirmed");
  }

  @Test
  void backspacingSidebarSearchRestoresVisibleSelection() throws Exception {
    checkSidebarSearchClearing("backspace");
  }

  private static void checkSidebarSearchClearing(String action) throws Exception {
    for (String query : List.of("Type49", "missing")) {
      TuiContext ctx = new TuiContext();
      ctx.tabs.add(new TuiContext.ResultTab("metadata"));
      ctx.sidebarTypes = new ArrayList<>();
      for (int i = 0; i < 50; i++) ctx.sidebarTypes.add(Map.of("name", "Type" + i));
      ctx.sidebarAreaHeight = 5;
      ctx.browserMode = true;
      ctx.sidebarFocused = true;
      ctx.focus = TuiContext.Focus.RESULTS;
      var executor = new TuiCommandExecutor(ctx, null);
      var keys =
          new TuiKeyHandler(ctx, timeout -> -2, null, executor, new TuiDetailBuilder(ctx), null);
      keys.handleKey('/');
      for (char c : query.toCharArray()) keys.handleKey(c);
      switch (action) {
        case "cancel" -> keys.handleKey(27);
        case "confirmed" -> {
          keys.handleKey(13);
          keys.handleKey(27);
        }
        case "backspace" -> {
          for (int i = 0; i < query.length(); i++) keys.handleKey(127);
        }
        default -> fail("Unknown action: " + action);
      }
      String scenario = action + " after " + query;
      assertNull(ctx.sidebarFilteredIndices, scenario);
      assertEquals("", ctx.sidebarSearchQuery, scenario);
      assertTrue(
          ctx.sidebarSelectedIndex >= 0 && ctx.sidebarSelectedIndex < ctx.sidebarTypes.size(),
          scenario);
      assertTrue(
          ctx.sidebarSelectedIndex >= ctx.sidebarScrollOffset
              && ctx.sidebarSelectedIndex < ctx.sidebarScrollOffset + ctx.sidebarAreaHeight,
          scenario);
    }
  }

  @Test
  void llmReceivesLaterEventAndNestedFieldsOverHttp() throws Exception {
    checkLlm(null);
  }

  @Test
  void builtJfrShellSendsMetadataAndExecutesTheQuery() throws Exception {
    checkLlm("io.jafar.shell.Main");
  }

  @Test
  void unifiedShellSendsTheSameMetadata() throws Exception {
    checkLlm("io.jafar.shell.unified.Main");
  }

  private void checkLlm(String mainClass) throws Exception {
    List<String> requests = new CopyOnWriteArrayList<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/models",
        exchange -> {
          byte[] body = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.add(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          String reply =
              switch (requests.size()) {
                case 1 -> "FIELDS: " + LATE;
                case 2 -> "QUERY: events/" + LATE + " | count()";
                default -> "ANSWER: Found one late event.";
              };
          String json =
              "{\"choices\":[{\"message\":{\"content\":\""
                  + reply
                  + "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10}}";
          byte[] body = json.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var out = exchange.getResponseBody()) {
            out.write(body);
          }
        });
    server.start();
    try {
      ParsingContext context = ParsingContext.create();
      SessionManager<io.jafar.shell.core.Session> sessions =
          new SessionManager<>((p, c) -> new JFRSession(p, (ParsingContext) c), context);
      List<String> output = new ArrayList<>();
      List<String> errors = new ArrayList<>();
      var io =
          new CommandDispatcher.IO() {
            public void println(String s) {
              output.add(s);
            }

            public void printf(String fmt, Object... args) {
              output.add(String.format(fmt, args));
            }

            public void error(String s) {
              errors.add(s);
            }
          };
      try {
        if (mainClass != null) {
          output.add(runShell(mainClass, server.getAddress().getPort()));
        } else {
          var dispatcher = new CommandDispatcher(sessions, io, r -> {});
          dispatcher.dispatch("open " + recording);
          for (String setting :
              List.of(
                  "backend = ollama",
                  "model = metadata-test",
                  "base-url = http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                  "enabled = true",
                  "confirm = false",
                  "count-events = false",
                  "timeout = 5",
                  "max-steps = 4")) {
            dispatcher.dispatch("set llm." + setting);
          }
          dispatcher.dispatch("ask Count the late metadata event");
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(3, requests.size(), output.toString());
        String first = messages(requests.get(0));
        assertTrue(first.contains(LATE), first);
        assertTrue(first.contains("Late metadata event"), first);
        assertTrue(first.contains("Metadata fixture for " + LATE), first);
        String fields = lastMessage(requests.get(1));
        for (String expected :
            List.of(
                "worker",
                "java.lang.Thread",
                "javaName",
                "java.lang.Class",
                "jdk.types.StackTrace",
                "frames")) {
          assertTrue(fields.contains(expected), expected + " missing from " + fields);
        }
        assertTrue(fields.contains("frames: jdk.types.StackFrame[]"), fields);
        String result = lastMessage(requests.get(2));
        assertTrue(result.contains("count\n1"), result);
        assertTrue(String.join("\n", output).contains("Found one late event."), output.toString());
      } finally {
        sessions.closeAll();
      }
    } finally {
      server.stop(0);
    }
  }

  private static String messages(String request) {
    return JsonParser.parseString(request).getAsJsonObject().getAsJsonArray("messages").toString();
  }

  private static String lastMessage(String request) {
    var turns = JsonParser.parseString(request).getAsJsonObject().getAsJsonArray("messages");
    return turns.get(turns.size() - 1).getAsJsonObject().get("content").getAsString();
  }

  private static String runShell(String mainClass, int port) throws Exception {
    boolean unified = mainClass.contains("unified");
    Path home = Files.createTempDirectory(dir, "shell-home-");
    Path transcript = home.resolve("output.txt");
    var command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home,
                "-Djfr.shell.plugin.dir=" + home.resolve("plugins"),
                "-Djfr.shell.registry.remote=false",
                "-Djfr.shell.backend=jdk",
                "-Djfr.shell.pager=off",
                "-cp",
                System.getProperty(
                    unified ? "metadata.unified.classpath" : "metadata.shell.classpath"),
                mainClass));
    if (!unified) command.addAll(List.of("script", "--backend", "jdk", "-"));
    ProcessBuilder builder =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(transcript.toFile());
    builder
        .environment()
        .keySet()
        .removeIf(
            k ->
                k.startsWith("JAFAR_LLM_")
                    || k.startsWith("LLM_")
                    || k.equals("JFRSHELL_BACKEND")
                    || k.equals("JAVA_TOOL_OPTIONS")
                    || k.equals("JDK_JAVA_OPTIONS"));
    builder
        .environment()
        .putAll(
            Map.of(
                "JAFAR_LLM_BACKEND",
                "ollama",
                "JAFAR_LLM_MODEL",
                "metadata-test",
                "JAFAR_LLM_BASE_URL",
                "http://127.0.0.1:" + port + "/v1",
                "JAFAR_LLM_TIMEOUT",
                "5",
                "JAFAR_LLM_MAX_STEPS",
                "4",
                "XDG_CONFIG_HOME",
                home.resolve("config").toString(),
                "TERM",
                "dumb"));
    Process process = builder.start();
    try {
      try (var input = process.getOutputStream()) {
        String metadata = unified ? "show metadata/" + LATE : "metadata class " + LATE + " --json";
        String helpAndNested =
            unified
                ? ""
                : "help metadata\nmetadata class java.lang.Thread --fields\nmetadata class jdk.types.StackTrace --fields\n";
        input.write(
            ("open "
                    + recording
                    + "\n"
                    + helpAndNested
                    + metadata
                    + "\nask Count the late metadata event\n"
                    + (unified ? "exit\n" : ""))
                .getBytes(StandardCharsets.UTF_8));
      }
      assertTrue(
          process.waitFor(60, TimeUnit.SECONDS),
          "Shell timed out: " + Files.readString(transcript));
      String output = Files.readString(transcript);
      assertEquals(0, process.exitValue(), output);
      assertFalse(output.contains("Error:"), output);
      assertFalse(output.contains("Unknown command:"), output);
      return output;
    } finally {
      process.destroyForcibly();
    }
  }
}
