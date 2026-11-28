package io.jafar.shell.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Offline mode (#235): no part of the plugin system sends a request. Every network endpoint the
 * plugin system has — the remote registry and the Maven repositories — points at a local server
 * that records each request it gets and answers 404; offline, it must get none. Each check has an
 * online control showing the same call does reach the server, so a zero count is not an accident of
 * the setup.
 */
class OfflineModeTest {

  private static final String CATALOG =
      """
      {
        "plugins": {
          "jafar": {
            "groupId": "io.test.offline",
            "artifactId": "jfr-shell-jafar",
            "latestVersion": "1.0.0",
            "repository": "maven-central",
            "depends": [],
            "recommends": [],
            "provides": ["jfr-backend"]
          }
        }
      }
      """;

  @TempDir Path tempDir;

  private HttpServer server;
  private final List<String> requests = new CopyOnWriteArrayList<>();
  private String baseUrl;
  private String savedOffline;
  private String savedRemote;
  private String savedUserHome;
  private String savedPluginDir;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
          exchange.sendResponseHeaders(404, -1);
          exchange.close();
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

    savedOffline = System.getProperty(NetworkPolicy.PROPERTY);
    savedRemote = System.getProperty("jfr.shell.registry.remote");
    savedUserHome = System.getProperty("user.home");
    savedPluginDir = System.getProperty("jfr.shell.plugin.dir");
    // The plugin-system test task turns the remote registry off; here it must be on, so that only
    // offline mode stands between the registry and the server.
    System.clearProperty("jfr.shell.registry.remote");
    System.setProperty("user.home", tempDir.toString());
    System.setProperty("jfr.shell.plugin.dir", tempDir.resolve("plugins").toString());
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    restore(NetworkPolicy.PROPERTY, savedOffline);
    restore("jfr.shell.registry.remote", savedRemote);
    restore("user.home", savedUserHome);
    restore("jfr.shell.plugin.dir", savedPluginDir);
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private void offline(boolean on) {
    if (on) {
      System.setProperty(NetworkPolicy.PROPERTY, "true");
    } else {
      // Set rather than cleared, so the online controls hold with JFRSHELL_OFFLINE on as well
      System.setProperty(NetworkPolicy.PROPERTY, "false");
    }
  }

  private PluginRegistry registry() {
    return new PluginRegistry(
        new PluginStorageManager(),
        tempDir.resolve("m2"),
        CATALOG,
        baseUrl + "/jfr-shell-plugins.json");
  }

  private MavenResolver resolver() {
    return new MavenResolver(List.of(baseUrl + "/maven2/"), tempDir.resolve("m2"));
  }

  @Test
  void registryIsNotFetchedOffline() {
    offline(true);
    registry().refreshIfNeeded();
    assertEquals(List.of(), requests);
  }

  @Test
  void registryIsFetchedOnline() {
    offline(false);
    registry();
    assertEquals(List.of("GET /jfr-shell-plugins.json"), requests);
  }

  @Test
  void mavenResolutionStaysLocalOffline() {
    offline(true);
    assertThrows(
        PluginInstallException.class,
        () -> resolver().resolveWithDependencies("io.test.offline", "jfr-shell-jafar", "1.0.0"));
    assertNull(resolver().resolveChecksum("io.test.offline", "jfr-shell-jafar", "1.0.0"));
    assertEquals(List.of(), requests);
  }

  @Test
  void mavenResolutionGoesRemoteOnline() {
    offline(false);
    assertThrows(
        PluginInstallException.class,
        () -> resolver().resolveWithDependencies("io.test.offline", "jfr-shell-jafar", "1.0.0"));
    assertFalse(requests.isEmpty());
    assertTrue(
        requests.stream().allMatch(r -> r.contains("/maven2/io/test/offline/")),
        requests::toString);
  }

  @Test
  void installIsRefusedOfflineBeforeAnyRequest() {
    offline(false);
    PluginRegistry registry = registry(); // fetched online, so a catalog entry is there
    requests.clear();
    offline(true);
    PluginInstaller installer =
        new PluginInstaller(registry, resolver(), new PluginStorageManager());

    PluginInstallException single =
        assertThrows(PluginInstallException.class, () -> installer.install("jafar"));
    PluginInstallException withDeps =
        assertThrows(
            PluginInstallException.class,
            () -> installer.installWithDependencies("jafar", null, null));

    assertTrue(single.getMessage().contains("offline mode"), single.getMessage());
    assertTrue(single.getMessage().contains("--install-plugin <jar>"), single.getMessage());
    assertTrue(withDeps.getMessage().contains("offline mode"), withDeps.getMessage());
    assertEquals(List.of(), requests);
  }

  @Test
  void installGoesToTheRepositoryOnline() {
    offline(false);
    PluginInstaller installer =
        new PluginInstaller(registry(), resolver(), new PluginStorageManager());
    requests.clear();
    assertThrows(PluginInstallException.class, () -> installer.install("jafar"));
    assertFalse(requests.isEmpty());
  }

  @Test
  void noUpdateCheckOffline() {
    offline(true);
    UpdateChecker checker = new UpdateChecker(registry());
    var installed =
        Map.of(
            "jafar",
            PluginMetadata.available(
                "io.test.offline", "jfr-shell-jafar", "0.9.0", "maven-central"));
    assertEquals(List.of(), checker.checkForUpdates(installed));
    assertEquals(List.of(), requests);
  }

  @Test
  void onValues() {
    for (String on : List.of("true", "TRUE", " yes ", "1")) {
      assertTrue(NetworkPolicy.isOn(on), on);
    }
    for (String off : new String[] {null, "", "false", "0", "no", "offline"}) {
      assertFalse(NetworkPolicy.isOn(off), String.valueOf(off));
    }
  }

  @Test
  void flagIsTakenOutBeforeTheSeparatorOnly() {
    offline(false);
    String[] rest =
        NetworkPolicy.applyFlag(
            new String[] {"show", "--offline", "x.jfr", "q", "--", "--offline"});
    assertArrayEquals(new String[] {"show", "x.jfr", "q", "--", "--offline"}, rest);
    assertTrue(NetworkPolicy.offline());
  }

  @Test
  void flagWithAValue() {
    offline(false);
    assertArrayEquals(
        new String[] {"show", "x.jfr"},
        NetworkPolicy.applyFlag(new String[] {"--offline=true", "show", "x.jfr"}));
    assertTrue(NetworkPolicy.offline());
    // The last one wins, and a value that is not on turns the mode off for this run
    NetworkPolicy.applyFlag(new String[] {"--offline", "show", "--offline=false"});
    assertEquals("false", System.getProperty(NetworkPolicy.PROPERTY));
    assertFalse(NetworkPolicy.offline());
  }

  @Test
  void aSetPropertyWinsOverTheVariable() {
    assertFalse(NetworkPolicy.offline("false", "true"));
    assertTrue(NetworkPolicy.offline("true", "false"));
    assertTrue(NetworkPolicy.offline(null, "true"));
    assertFalse(NetworkPolicy.offline(null, null));
    assertFalse(NetworkPolicy.offline("", "true"));
  }

  @Test
  void noFlagLeavesTheModeAlone() {
    offline(false);
    String[] rest = NetworkPolicy.applyFlag(new String[] {"show", "x.jfr", "--", "--offline"});
    assertArrayEquals(new String[] {"show", "x.jfr", "--", "--offline"}, rest);
    assertEquals("false", System.getProperty(NetworkPolicy.PROPERTY));
  }
}
