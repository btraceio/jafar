package io.jafar.shell.core;

import io.jafar.shell.core.llm.SecretCommands;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import org.jline.reader.LineReader;
import org.jline.reader.impl.history.DefaultHistory;

/**
 * A jline history that never holds a line setting a credential ({@code set llm.api-key = ...}), so
 * the key is neither written to the history file nor recalled with the up arrow.
 *
 * <p>Filters in {@code internalAdd(Instant, String, boolean)}: in jline 3.21 and 3.26 (the versions
 * the shells use) both a typed line ({@code add}) and the file read at start ({@code load}) go
 * through it. A key line already in the file, from before this filter, is dropped as it is loaded;
 * the file is then rewritten from what was kept, because jline otherwise only appends to it — and
 * its size trim re-reads the file directly, not through {@code internalAdd}.
 *
 * <p>Like {@link DefaultHistory}, not thread-safe; a shell's line reader uses it from one thread.
 */
public class SecretFilteringHistory extends DefaultHistory {
  private LineReader lineReader;
  private boolean loading;
  private int droppedWhileLoading;

  @Override
  public void attach(LineReader reader) {
    this.lineReader = reader; // before super.attach, which calls load()
    super.attach(reader);
  }

  @Override
  public void load() throws IOException {
    droppedWhileLoading = 0;
    loading = true;
    try {
      super.load();
    } finally {
      loading = false;
    }
    Path file = historyFile();
    if (droppedWhileLoading > 0 && file != null) {
      write(file, false); // not incremental: replaces the file with the entries kept
    }
  }

  @Override
  protected void internalAdd(Instant time, String line, boolean checkDuplicates) {
    if (SecretCommands.setsSecret(line)) {
      if (loading) {
        droppedWhileLoading++;
      }
      return;
    }
    super.internalAdd(time, line, checkDuplicates);
  }

  /** {@link LineReader#HISTORY_FILE}, in the forms jline itself accepts. */
  private Path historyFile() {
    Object value = lineReader != null ? lineReader.getVariable(LineReader.HISTORY_FILE) : null;
    if (value instanceof Path path) {
      return path;
    }
    if (value instanceof File file) {
      return file.toPath();
    }
    return value != null ? Paths.get(value.toString()) : null;
  }
}
