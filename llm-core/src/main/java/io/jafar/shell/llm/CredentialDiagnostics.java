package io.jafar.shell.llm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Answers "which credential is this shell actually going to use, and why".
 *
 * <p>This exists because the SDK resolves credentials silently and does not fail fast when it finds
 * none — a misconfigured user otherwise learns about it as a 401 from the server, several seconds
 * and one confusing message later. It also catches the shadowing trap that is by far the most
 * common cause of "it worked yesterday": an exported {@code ANTHROPIC_API_KEY} takes precedence
 * over an OAuth profile, so a stale key silently sends requests to a different organisation.
 *
 * <p>It reads the profile directory rather than shelling out to {@code ant}, so it works whether or
 * not that CLI is installed on the machine — the shell only needs to know that a profile exists,
 * not to use it directly.
 */
public final class CredentialDiagnostics {

  private CredentialDiagnostics() {}

  /** One candidate credential source and its state. */
  public record Source(String name, State state, String detail) {
    public enum State {
      /** This source will be used. */
      ACTIVE,
      /** Present, but a higher-precedence source wins. */
      SHADOWED,
      /** Not configured. */
      ABSENT,
      /** Configured but broken. */
      INVALID
    }
  }

  /**
   * Describes every credential source in precedence order, marking the one that wins.
   *
   * <p>Order mirrors the SDK's: API key, auth token, selected/active OAuth profile, Workload
   * Identity Federation, default profile.
   */
  public static List<Source> sources() {
    List<Source> sources = new ArrayList<>();
    boolean claimed = false;

    String apiKey = System.getenv("ANTHROPIC_API_KEY");
    if (apiKey == null) {
      sources.add(new Source("ANTHROPIC_API_KEY", Source.State.ABSENT, "not set"));
    } else if (apiKey.isBlank()) {
      sources.add(
          new Source(
              "ANTHROPIC_API_KEY",
              Source.State.INVALID,
              "set but empty — still takes precedence and authenticates as an empty key"));
      claimed = true;
    } else {
      sources.add(
          new Source("ANTHROPIC_API_KEY", Source.State.ACTIVE, "set (" + masked(apiKey) + ")"));
      claimed = true;
    }

    String authToken = System.getenv("ANTHROPIC_AUTH_TOKEN");
    if (authToken == null || authToken.isBlank()) {
      sources.add(new Source("ANTHROPIC_AUTH_TOKEN", Source.State.ABSENT, "not set"));
    } else {
      sources.add(
          new Source(
              "ANTHROPIC_AUTH_TOKEN",
              claimed ? Source.State.SHADOWED : Source.State.ACTIVE,
              claimed
                  ? "set, but ANTHROPIC_API_KEY wins — the API rejects requests carrying both"
                  : "set (" + masked(authToken) + ")"));
      claimed = true;
    }

    Optional<String> profile = activeProfileDescription();
    if (profile.isEmpty()) {
      sources.add(
          new Source(
              "OAuth profile",
              Source.State.ABSENT,
              "no profile found under " + configDir() + " — run `ant auth login`"));
    } else {
      sources.add(
          new Source(
              "OAuth profile",
              claimed ? Source.State.SHADOWED : Source.State.ACTIVE,
              claimed ? profile.get() + " (shadowed by an environment variable)" : profile.get()));
      claimed = true;
    }

    boolean wif =
        isSet(System.getenv("ANTHROPIC_FEDERATION_RULE_ID"))
            && isSet(System.getenv("ANTHROPIC_ORGANIZATION_ID"))
            && isSet(System.getenv("ANTHROPIC_SERVICE_ACCOUNT_ID"))
            && (isSet(System.getenv("ANTHROPIC_IDENTITY_TOKEN_FILE"))
                || isSet(System.getenv("ANTHROPIC_IDENTITY_TOKEN")));
    sources.add(
        new Source(
            "Workload Identity Federation",
            wif ? (claimed ? Source.State.SHADOWED : Source.State.ACTIVE) : Source.State.ABSENT,
            wif ? "federation environment variables are set" : "not configured"));

    return sources;
  }

  /** The name of the profile the SDK would use, with its workspace when recorded. */
  public static Optional<String> activeProfileDescription() {
    Path configs = configDir().resolve("configs");
    if (!Files.isDirectory(configs)) {
      return Optional.empty();
    }
    String selected = System.getenv("ANTHROPIC_PROFILE");
    if (isSet(selected)) {
      Path file = configs.resolve(selected + ".json");
      return Files.isRegularFile(file)
          ? Optional.of("profile '" + selected + "' (ANTHROPIC_PROFILE)")
          // A named profile that does not exist is an error in the SDK, not a fall-through.
          : Optional.empty();
    }
    try (Stream<Path> files = Files.list(configs)) {
      List<String> names =
          files
              .filter(Files::isRegularFile)
              .map(p -> p.getFileName().toString())
              .filter(n -> n.endsWith(".json"))
              .map(n -> n.substring(0, n.length() - ".json".length()))
              .sorted()
              .toList();
      if (names.isEmpty()) {
        return Optional.empty();
      }
      String preferred = names.contains("default") ? "default" : names.get(0);
      return Optional.of(
          names.size() == 1
              ? "profile '" + preferred + "'"
              : "profile '" + preferred + "' (of " + names.size() + " on disk)");
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** The directory the SDK reads profiles from. */
  public static Path configDir() {
    String override = System.getenv("ANTHROPIC_CONFIG_DIR");
    if (isSet(override)) {
      return Path.of(override);
    }
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("win")) {
      String appData = System.getenv("APPDATA");
      if (isSet(appData)) {
        return Path.of(appData, "Anthropic");
      }
    }
    return Path.of(System.getProperty("user.home", "."), ".config", "anthropic");
  }

  /** Shows enough of a secret to identify it, never enough to use it. */
  private static String masked(String secret) {
    if (secret.length() <= 8) {
      return "****";
    }
    return secret.substring(0, 4) + "…" + secret.substring(secret.length() - 4);
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }
}
