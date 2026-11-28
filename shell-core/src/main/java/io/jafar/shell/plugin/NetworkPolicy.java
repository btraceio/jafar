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
 * <p>It is on when the command line has {@value #FLAG} (see {@link #applyFlag}), or the system
 * property {@value #PROPERTY} is {@code true}, {@code 1} or {@code yes}, or, with the property not
 * set, the environment variable {@value #ENV} is: a property set to anything else, such as {@code
 * false}, turns it off for one run whatever the variable says. It is read at each use, so it holds
 * whenever the property is set before the first network access.
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
    String property = System.getProperty(PROPERTY);
    return property != null ? isOn(property) : isOn(System.getenv(ENV));
  }

  static boolean isOn(String value) {
    if (value == null) {
      return false;
    }
    String v = value.trim().toLowerCase(Locale.ROOT);
    return v.equals("true") || v.equals("1") || v.equals("yes");
  }

  /**
   * Takes {@value #FLAG} and {@code --offline=<value>} out of a command line, turning offline mode
   * on (or, for a value that is not on, off) as the last of them says. Only the arguments before a
   * {@code --} separator are looked at: those after it belong to a script. The flag is never an
   * option's value: {@code -b --offline} is {@code -b} followed by the flag.
   *
   * @return the command line without the flag
   */
  public static String[] applyFlag(String[] args) {
    List<String> rest = new ArrayList<>(args.length);
    String value = null;
    boolean afterSeparator = false;
    for (String arg : args) {
      if (!afterSeparator && FLAG.equals(arg)) {
        value = "true";
        continue;
      }
      if (!afterSeparator && arg.startsWith(FLAG + "=")) {
        value = arg.substring(FLAG.length() + 1);
        continue;
      }
      if ("--".equals(arg)) {
        afterSeparator = true;
      }
      rest.add(arg);
    }
    if (value != null) {
      System.setProperty(PROPERTY, isOn(value) ? "true" : "false");
    }
    return rest.toArray(new String[0]);
  }

  /** Why {@code what} cannot be done in offline mode, and what to do instead. */
  public static String refusal(String what) {
    return refusal(
        what,
        "Install a plugin from a local jar with --install-plugin <jar>, point -Djfr.shell"
            + ".backend.jars=<jar> at a backend jar, or run without offline mode to download it");
  }

  /** As {@link #refusal(String)}, with the remedy given. */
  public static String refusal(String what, String remedy) {
    return "offline mode ("
        + FLAG
        + ", -D"
        + PROPERTY
        + " or "
        + ENV
        + ") forbids "
        + what
        + ". "
        + remedy;
  }
}
