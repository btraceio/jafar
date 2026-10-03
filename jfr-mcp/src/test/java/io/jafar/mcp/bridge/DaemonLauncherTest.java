package io.jafar.mcp.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DaemonLauncherTest {

  private static final Path JAR = Path.of("/opt/jfr-mcp/jfr-mcp-all.jar");

  @Test
  void daemonCommandRunsTheSameJarAsAnAutoStartedDaemon() {
    List<String> cmd = DaemonLauncher.daemonCommand("/jdk/bin/java", JAR, Map.of());

    assertEquals("/jdk/bin/java", cmd.get(0));
    assertTrue(cmd.contains("-Dmcp.daemon.autostarted=true"));
    int jar = cmd.indexOf("-jar");
    assertTrue(jar > 0, "needs -jar: " + cmd);
    assertEquals(JAR.toString(), cmd.get(jar + 1));
    assertEquals(jar + 2, cmd.size(), "no --stdio: it must run as the SSE daemon");
  }

  @Test
  void systemPropertiesCarryThroughSoTheDaemonUsesTheSameStateAndPort() {
    Map<String, String> props = new LinkedHashMap<>();
    props.put("jafar.state.dir", "/tmp/state");
    props.put("mcp.port", "3055");
    props.put("mcp.daemon.idle.timeout.minutes", "5");
    props.put("user.home", "/Users/x");
    props.put("java.version", "26");

    List<String> cmd = DaemonLauncher.daemonCommand("java", JAR, DaemonLauncher.forwarded(props));

    assertTrue(cmd.contains("-Djafar.state.dir=/tmp/state"));
    assertTrue(cmd.contains("-Dmcp.port=3055"));
    assertTrue(cmd.contains("-Dmcp.daemon.idle.timeout.minutes=5"));
    assertFalse(cmd.contains("-Duser.home=/Users/x"), "unrelated properties must not leak");
    assertFalse(cmd.contains("-Djava.version=26"));
  }

  @Test
  void aCallerCannotTurnTheAutostartMarkerOff() {
    List<String> cmd =
        DaemonLauncher.daemonCommand("java", JAR, Map.of("mcp.daemon.autostarted", "false"));

    assertEquals(
        1,
        cmd.stream().filter(a -> a.startsWith("-Dmcp.daemon.autostarted=")).count(),
        "exactly one marker: " + cmd);
    assertTrue(cmd.contains("-Dmcp.daemon.autostarted=true"));
  }

  @Test
  void detachedShellCommandPassesLogsAndDaemonCommandAsPlainArguments() {
    List<String> daemon = List.of("java", "-Dx=a b", "-jar", "/p with space/j.jar");

    List<String> cmd =
        DaemonLauncher.detachedShellCommand(
            daemon, Path.of("/s/mcp-sse.log"), Path.of("/s/mcp-sse.err.log"));

    assertEquals("/bin/sh", cmd.get(0));
    assertEquals("-c", cmd.get(1));
    // $0, $1 (stdout log), $2 (stderr log), then the daemon command verbatim: nothing is
    // spliced into the script text, so spaces and quotes in paths cannot break out of it.
    assertEquals(List.of("sh", "/s/mcp-sse.log", "/s/mcp-sse.err.log"), cmd.subList(3, 6));
    assertEquals(daemon, cmd.subList(6, cmd.size()));
    assertTrue(cmd.get(2).contains("nohup"), "must survive the bridge's terminal going away");
  }

  // ───────────────────────────── AOT cache for the daemon ─────────────────────────────

  private static final Runtime.Version JDK_27 = Runtime.Version.parse("27+36");

  private static DaemonLauncher.AotPlan aot(
      Path state, String version, Runtime.Version jvm, java.util.function.LongPredicate alive) {
    return DaemonLauncher.aotPlan(state, version, jvm, alive);
  }

  @org.junit.jupiter.api.io.TempDir Path state;

  @Test
  void noAotWithoutAKnownJarVersion() {
    assertTrue(aot(state, null, JDK_27, pid -> false).flags().isEmpty());
    assertTrue(aot(state, "unknown", JDK_27, pid -> false).flags().isEmpty());
  }

  @Test
  void noAotOnAJvmWithoutTheOneStepCacheFlags() {
    assertTrue(
        aot(state, "1.0", Runtime.Version.parse("24.0.1+9"), pid -> false).flags().isEmpty());
  }

  @Test
  void theFirstDaemonIsTheTrainingRunAndWritesTheCacheOnExit() {
    DaemonLauncher.AotPlan plan = aot(state, "1.0", JDK_27, pid -> false);

    assertTrue(plan.training());
    assertEquals(1, plan.flags().size());
    assertTrue(
        plan.flags().get(0).startsWith("-XX:AOTCacheOutput=" + state), plan.flags().toString());
  }

  @Test
  void anExistingCacheIsUsedNotRebuilt() throws Exception {
    DaemonLauncher.AotPlan first = aot(state, "1.0", JDK_27, pid -> false);
    Path cache = Path.of(first.flags().get(0).substring("-XX:AOTCacheOutput=".length()));
    java.nio.file.Files.writeString(cache, "archive bytes");

    DaemonLauncher.AotPlan plan = aot(state, "1.0", JDK_27, pid -> false);

    assertFalse(plan.training());
    assertEquals(List.of("-XX:AOTCache=" + cache), plan.flags());
  }

  @Test
  void aLiveTrainingDaemonMeansNoSecondTrainingRun() throws Exception {
    DaemonLauncher.AotPlan first = aot(state, "1.0", JDK_27, pid -> false);
    java.nio.file.Files.writeString(first.marker(), "4242");

    DaemonLauncher.AotPlan plan = aot(state, "1.0", JDK_27, pid -> pid == 4242);

    assertTrue(plan.flags().isEmpty(), "two JVMs must not write the same cache file");
    assertFalse(plan.training());
  }

  @Test
  void aDeadTrainingDaemonsMarkerDoesNotBlockTrainingAgain() throws Exception {
    DaemonLauncher.AotPlan first = aot(state, "1.0", JDK_27, pid -> false);
    java.nio.file.Files.writeString(first.marker(), "4242");

    DaemonLauncher.AotPlan plan = aot(state, "1.0", JDK_27, pid -> false);

    assertTrue(plan.training());
  }

  @Test
  void aCacheIsNeverSharedAcrossJarOrJvmVersions() {
    String a = aot(state, "1.0", JDK_27, pid -> false).flags().get(0);
    String otherJar = aot(state, "1.1", JDK_27, pid -> false).flags().get(0);
    String otherJvm =
        aot(state, "1.0", Runtime.Version.parse("27.0.1+2"), pid -> false).flags().get(0);

    assertFalse(a.equals(otherJar));
    assertFalse(a.equals(otherJvm));
  }

  @Test
  void cachesForOtherVersionsAreRemoved() throws Exception {
    Path stale = state.resolve("jfr-mcp-0.9-jdk25.aot");
    java.nio.file.Files.writeString(stale, "old");
    Path staleConfig = state.resolve("jfr-mcp-0.9-jdk25.aot.config");
    java.nio.file.Files.writeString(staleConfig, "left behind by a training run");
    Path unrelated = state.resolve("mcp-sessions.json");
    java.nio.file.Files.writeString(unrelated, "[]");

    aot(state, "1.0", JDK_27, pid -> false);

    assertFalse(java.nio.file.Files.exists(stale));
    assertFalse(java.nio.file.Files.exists(staleConfig));
    assertTrue(java.nio.file.Files.exists(unrelated));
  }

  @Test
  void theDaemonCommandCarriesTheAotFlagsBeforeTheJar() {
    List<String> cmd =
        DaemonLauncher.daemonCommand("java", JAR, Map.of(), List.of("-XX:AOTCache=/s/x.aot"));

    assertTrue(cmd.indexOf("-XX:AOTCache=/s/x.aot") < cmd.indexOf("-jar"), cmd.toString());
  }

  @Test
  void theLaunchScriptsPidIsTheLastLineThatIsOnlyDigits() {
    assertEquals(4567, DaemonLauncher.lastPid("[1] 123\n4567\n"));
    assertEquals(4567, DaemonLauncher.lastPid("4567"));
    assertEquals(-1, DaemonLauncher.lastPid("sh: nohup: command not found\n"));
    assertEquals(-1, DaemonLauncher.lastPid(""));
  }
}
