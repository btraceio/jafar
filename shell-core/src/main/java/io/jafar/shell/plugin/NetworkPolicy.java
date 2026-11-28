package io.jafar.shell.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Offline mode (#235): with it, nothing in the plugin system contacts the network. The remote
 * plugin registry is not fetched, Maven resolution reads only the local repository, update checks
 * do not run, and no plugin is downloaded or installed from a repository: a command that needs a
 * backend uses one already installed or fails, saying so. Installing a plugin from a local jar
 * still works.
 *
 * <p>It is on when the system property {@value #PROPERTY} or the environment variable {@value #ENV}
 * is {@code true}, {@code 1} or {@code yes}, or the command line has {@value #FLAG} (see {@link
 * #applyFlag}). It is read at each use, so it holds whenever the property is set before the first
 * network access.
 */
public final class NetworkPolicy {

  /** The system property that turns offline mode on. */
  public static final String PROPERTY = "jfr.shell.offline";

  /** The environment variable that turns offline mode on. */
  public static final String ENV = "JFRSHELL_OFFLINE";

  /** The command-line flag that turns offline mode on. */
  public static final String FLAG = "--offline";

  private NetworkPolicy() {}

  /** Whether offline mode is on. */
  public static boolean offline() {
    return isOn(System.getProperty(PROPERTY)) || isOn(System.getenv(ENV));
  }

  static boolean isOn(String value) {
    if (value == null) {
      return false;
    }
    String v = value.trim().toLowerCase(Locale.ROOT);
    return v.equals("true") || v.equals("1") || v.equals("yes");
  }

  /**
   * Takes {@value #FLAG} out of a command line, turning offline mode on if it was there. Only the
   * arguments before a {@code --} separator are looked at: those after it belong to a script.
   *
   * @return the command line without the flag
   */
  public static String[] applyFlag(String[] args) {
    List<String> rest = new ArrayList<>(args.length);
    boolean found = false;
    boolean afterSeparator = false;
    for (String arg : args) {
      if (!afterSeparator && FLAG.equals(arg)) {
        found = true;
        continue;
      }
      if ("--".equals(arg)) {
        afterSeparator = true;
      }
      rest.add(arg);
    }
    if (found) {
      System.setProperty(PROPERTY, "true");
    }
    return rest.toArray(new String[0]);
  }

  /** Why {@code what} cannot be done in offline mode, and what to do instead. */
  public static String refusal(String what) {
    return "offline mode ("
        + FLAG
        + ", -D"
        + PROPERTY
        + " or "
        + ENV
        + ") forbids "
        + what
        + ". Install a plugin from a local jar with --install-plugin <jar>, point -Djfr.shell"
        + ".backend.jars=<jar> at a backend jar, or run without offline mode to download it";
  }
}
