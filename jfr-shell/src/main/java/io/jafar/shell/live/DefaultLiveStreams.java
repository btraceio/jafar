package io.jafar.shell.live;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import jdk.jfr.consumer.EventStream;

/**
 * Production {@link LiveStreams}: hsperfdata discovery + {@link EventStream#openRepository(Path)}.
 */
public final class DefaultLiveStreams implements LiveStreams {

  public static final LiveStreams INSTANCE = new DefaultLiveStreams();

  private DefaultLiveStreams() {}

  @Override
  public List<JvmProcess> listLocalJvm() {
    return JvmProcesses.listLocalJvm();
  }

  @Override
  public Path findRepository(int pid) throws IOException {
    // JvmProcesses.findRepository throws the descriptive IOException (searched temp base +
    // --repository hint) and never returns empty — no wrapper message on top of it.
    return JvmProcesses.findRepository(pid);
  }

  @Override
  public LiveStreamView attach(int pid, Path repository) throws IOException {
    // Hardening at attach time (planted-directory defense + TOCTOU narrowing): applies to
    // discovery-found AND explicitly user-supplied --repository paths. The check is boolean —
    // the message enumerates the possible rejection reasons honestly.
    if (!JvmProcesses.isPlausibleRepository(repository, pid, System.getProperty("user.name"))) {
      throw new IOException(
          "Repository "
              + repository
              + " is not a plausible tail source for PID "
              + pid
              + "; it was rejected because it failed one or more of: symlinked path, not owned"
              + " by the current user, no regular file inside, target not alive, directory"
              + " predates the target start; pass the right directory with --repository");
    }
    LiveJfrSession session =
        new LiveJfrSession(pid, repository, "jfr-live", EventStream::openRepository);
    session.start();
    return session;
  }

  @Override
  public Path searchedBase() {
    return JvmProcesses.tempBase();
  }
}
