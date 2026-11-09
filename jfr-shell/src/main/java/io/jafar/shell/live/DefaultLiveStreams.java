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
    return JvmProcesses.findRepository(pid)
        .orElseThrow(() -> new java.nio.file.NoSuchFileException("repository for PID " + pid));
  }

  @Override
  public LiveJfrSession attach(int pid, Path repository) throws IOException {
    LiveJfrSession session =
        new LiveJfrSession(pid, repository, "jfr-live", EventStream::openRepository);
    session.start();
    return session;
  }
}
