package io.jafar.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The {@code --json} contract of {@code show} and {@code script} (#234): everything on stdout is
 * one JSON document, read strictly to its end; {@code truncated} says whether {@code --limit} left
 * rows out; a failure is still one JSON object, with a nonzero exit status.
 */
class JsonStdoutContractTest {

  private static final Path JFR =
      Paths.get("..", "parser-core", "src", "test", "resources", "test-ap.jfr")
          .normalize()
          .toAbsolutePath();

  @TempDir Path tempDir;

  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private PrintStream originalOut;
  private PrintStream originalErr;

  @BeforeEach
  void setUp() {
    originalOut = System.out;
    originalErr = System.err;
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
  }

  @AfterEach
  void tearDown() {
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  private int run(String... args) {
    out.reset();
    err.reset();
    return new CommandLine(new Main()).execute(args);
  }

  private String stdout() {
    return out.toString(StandardCharsets.UTF_8);
  }

  private String stderr() {
    return err.toString(StandardCharsets.UTF_8);
  }

  /** All of stdout as one JSON document, read strictly: anything before or after it fails. */
  private JsonElement document() throws IOException {
    String text = stdout();
    JsonReader reader = new JsonReader(new StringReader(text));
    reader.setLenient(false);
    JsonElement element = new Gson().getAdapter(JsonElement.class).read(reader);
    assertEquals(JsonToken.END_DOCUMENT, reader.peek(), "more after the document: " + text);
    return element;
  }

  private JsonObject show(String query, String... options) throws IOException {
    String[] args = new String[3 + options.length + 1];
    args[0] = "show";
    args[1] = JFR.toString();
    args[2] = query;
    System.arraycopy(options, 0, args, 3, options.length);
    args[args.length - 1] = "--json";
    int rc = run(args);
    assertEquals(0, rc, stderr());
    return document().getAsJsonObject();
  }

  /** A type with few events, so that a test can ask for all of them. */
  private static final String FEW = "events/jdk.InitialSystemProperty";

  private int count(String events) throws IOException {
    return show(events + " | count()")
        .getAsJsonArray("result")
        .get(0)
        .getAsJsonObject()
        .get("count")
        .getAsInt();
  }

  @Test
  void aggregateIsOneObject() throws IOException {
    JsonObject doc = show("events/jdk.ExecutionSample | count()");
    assertEquals(List.of("result", "truncated", "limit"), List.copyOf(doc.keySet()));
    assertFalse(doc.get("truncated").getAsBoolean());
    assertTrue(doc.get("limit").isJsonNull());
    assertTrue(doc.getAsJsonArray("result").get(0).getAsJsonObject().get("count").getAsInt() > 1);
  }

  @Test
  void limitOfExactlyTheRowCountIsNotTruncated() throws IOException {
    int n = count(FEW);
    assertTrue(n > 1, "fixture: " + n);
    JsonObject all = show(FEW, "--limit", String.valueOf(n));
    assertEquals(n, all.getAsJsonArray("result").size());
    assertFalse(all.get("truncated").getAsBoolean());
    assertEquals(n, all.get("limit").getAsInt());

    JsonObject cut = show(FEW, "--limit", String.valueOf(n - 1));
    assertEquals(n - 1, cut.getAsJsonArray("result").size());
    assertTrue(cut.get("truncated").getAsBoolean());
  }

  @Test
  void truncationAfterAPipelineAndForValues() throws IOException {
    JsonObject groups =
        show("events/jdk.ExecutionSample | groupBy(sampledThread/javaName)", "--limit", "1");
    assertEquals(1, groups.getAsJsonArray("result").size());
    assertTrue(groups.get("truncated").getAsBoolean(), groups.toString());

    int n = count(FEW);
    JsonObject values = show(FEW + "/key", "--limit", String.valueOf(n));
    assertEquals(n, values.getAsJsonArray("result").size());
    assertFalse(values.get("truncated").getAsBoolean());
    JsonObject fewer = show(FEW + "/key", "--limit", String.valueOf(n - 1));
    assertEquals(n - 1, fewer.getAsJsonArray("result").size());
    assertTrue(fewer.get("truncated").getAsBoolean());
  }

  @Test
  void invalidQueryIsAnErrorObjectAndNonzero() throws IOException {
    int rc = run("show", JFR.toString(), "events/jdk.ExecutionSample | count(", "--json");
    assertEquals(1, rc);
    JsonObject doc = document().getAsJsonObject();
    assertEquals(List.of("error"), List.copyOf(doc.keySet()));
    assertTrue(doc.get("error").getAsString().contains("Expected ')'"), doc.toString());
    assertTrue(stderr().contains("Expected ')'"), stderr());
  }

  @Test
  void missingRecordingIsAnErrorObject() throws IOException {
    int rc = run("show", tempDir.resolve("none.jfr").toString(), "events/x", "--json");
    assertEquals(1, rc);
    assertTrue(
        document().getAsJsonObject().get("error").getAsString().contains("JFR file not found"));
  }

  @Test
  void aResultWithNoJsonFormIsAnError() throws IOException {
    int rc = run("show", JFR.toString(), "metadata", "--json");
    assertEquals(1, rc);
    assertTrue(
        document().getAsJsonObject().get("error").getAsString().contains("has no JSON form"),
        stdout());
  }

  @Test
  void formatJsonWithoutTheFlagIsUnchanged() throws IOException {
    assertEquals(
        0, run("show", JFR.toString(), "events/jdk.ExecutionSample | count()", "--format", "json"));
    JsonArray rows = document().getAsJsonArray();
    assertEquals(1, rows.size());
  }

  private Path script(String... lines) throws IOException {
    Path script = tempDir.resolve("s.jfrs");
    Files.writeString(script, String.join("\n", lines) + "\n");
    return script;
  }

  @Test
  void scriptGivesOneResultPerShowAndNothingElseOnStdout() throws IOException {
    Path s =
        script(
            "open " + JFR,
            "show events/jdk.ExecutionSample | count()",
            "echo not-a-result",
            "show events/jdk.ExecutionSample --limit 1");
    assertEquals(0, run("script", s.toString(), "--json"), stderr());
    JsonObject doc = document().getAsJsonObject();
    assertEquals(List.of("results"), List.copyOf(doc.keySet()));
    JsonArray results = doc.getAsJsonArray("results");
    assertEquals(2, results.size());
    assertEquals(
        "show events/jdk.ExecutionSample | count()",
        results.get(0).getAsJsonObject().get("command").getAsString());
    assertFalse(results.get(0).getAsJsonObject().get("truncated").getAsBoolean());
    assertTrue(results.get(1).getAsJsonObject().get("truncated").getAsBoolean());
    assertEquals(1, results.get(1).getAsJsonObject().get("limit").getAsInt());
    assertTrue(stderr().contains("not-a-result"), stderr());
    assertFalse(stderr().contains("Script executed successfully"), stderr());
  }

  @Test
  void failingScriptKeepsTheResultsBeforeTheFailure() throws IOException {
    Path s =
        script(
            "open " + JFR,
            "show events/jdk.ExecutionSample | count()",
            "show events/jdk.NoSuchEvent",
            "show events/jdk.ExecutionSample | count()");
    assertEquals(1, run("script", s.toString(), "--json"));
    JsonObject doc = document().getAsJsonObject();
    assertTrue(doc.get("error").getAsString().contains("jdk.NoSuchEvent"), doc.toString());
    assertEquals(1, doc.getAsJsonArray("results").size());
  }

  @Test
  void scriptWithoutTheFlagIsUnchanged() throws IOException {
    Path s = script("open " + JFR, "show events/jdk.ExecutionSample | count()");
    assertEquals(0, run("script", s.toString()));
    assertTrue(stdout().contains("Script executed successfully: 2 commands"), stdout());
  }

  @Test
  void usageErrorsAskingForJsonAreRecognised() {
    CommandLine cl = new CommandLine(new Main());
    CommandLine.ParameterException e =
        org.junit.jupiter.api.Assertions.assertThrows(
            CommandLine.ParameterException.class,
            () -> cl.parseArgs("show", JFR.toString(), "--json"));
    assertTrue(Main.jsonRequested(e.getCommandLine(), new String[] {"show", "x", "--json"}));
    assertFalse(Main.jsonRequested(e.getCommandLine(), new String[] {"show", "x", "--", "--json"}));
    assertFalse(Main.jsonRequested(e.getCommandLine(), new String[] {"show", "x"}));
  }
}
