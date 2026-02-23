package io.jafar.shell;

import dev.tamboui.layout.Position;
import dev.tamboui.layout.Size;
import dev.tamboui.terminal.AbstractBackend;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

/**
 * Minimal TamboUI {@link dev.tamboui.terminal.Backend} backed by a JLine {@link Terminal}.
 *
 * <p>{@link AbstractBackend} provides {@code draw()} and {@code setCursorPosition()} by delegating
 * to {@link #writeRaw(String)}. This class implements the remaining terminal operations (raw mode,
 * alternate screen, cursor visibility, size queries, and key reading) against the JLine terminal.
 */
final class JLineShellBackend extends AbstractBackend {
  private final Terminal terminal;
  private final OutputStream output;
  private Attributes savedAttributes;

  JLineShellBackend(Terminal terminal) {
    this.terminal = terminal;
    this.output = terminal.output();
  }

  // ---- raw I/O (used by AbstractBackend.draw) ----

  @Override
  public void writeRaw(String s) throws IOException {
    output.write(s.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public void writeRaw(byte[] bytes) throws IOException {
    output.write(bytes);
  }

  @Override
  public void flush() throws IOException {
    output.flush();
  }

  // ---- screen management ----

  @Override
  public void clear() throws IOException {
    writeRaw("\033[2J\033[H");
    flush();
  }

  @Override
  public Size size() throws IOException {
    return new Size(terminal.getWidth(), terminal.getHeight());
  }

  @Override
  public void enterAlternateScreen() throws IOException {
    writeRaw("\033[?1049h");
    flush();
  }

  @Override
  public void leaveAlternateScreen() throws IOException {
    writeRaw("\033[?1049l");
    flush();
  }

  // ---- cursor ----

  @Override
  public void showCursor() throws IOException {
    writeRaw("\033[?25h");
    flush();
  }

  @Override
  public void hideCursor() throws IOException {
    writeRaw("\033[?25l");
    flush();
  }

  @Override
  public Position getCursorPosition() throws IOException {
    return Position.ORIGIN;
  }

  // ---- raw mode ----

  @Override
  public void enableRawMode() throws IOException {
    // Save current attributes for later restore.
    savedAttributes = terminal.getAttributes();
    // Build raw mode manually: JLine's enterRawMode() does NOT disable ISIG,
    // so Ctrl+C would generate SIGINT instead of arriving as byte 3.
    Attributes raw = new Attributes(savedAttributes);
    raw.setLocalFlag(Attributes.LocalFlag.ICANON, false);
    raw.setLocalFlag(Attributes.LocalFlag.ECHO, false);
    raw.setLocalFlag(Attributes.LocalFlag.ISIG, false);
    raw.setLocalFlag(Attributes.LocalFlag.IEXTEN, false);
    raw.setInputFlag(Attributes.InputFlag.IXON, false);
    raw.setInputFlag(Attributes.InputFlag.ICRNL, false);
    raw.setInputFlag(Attributes.InputFlag.INLCR, false);
    raw.setControlChar(Attributes.ControlChar.VMIN, 1);
    raw.setControlChar(Attributes.ControlChar.VTIME, 0);
    terminal.setAttributes(raw);
  }

  @Override
  public void disableRawMode() throws IOException {
    if (savedAttributes != null) {
      terminal.setAttributes(savedAttributes);
      savedAttributes = null;
    }
  }

  // ---- resize ----

  @Override
  public void onResize(Runnable handler) {
    terminal.handle(
        Terminal.Signal.WINCH,
        signal -> {
          if (handler != null) {
            handler.run();
          }
        });
  }

  // ---- key input ----

  @Override
  public int read(int timeoutMs) throws IOException {
    return terminal.reader().read(timeoutMs);
  }

  @Override
  public int peek(int timeoutMs) throws IOException {
    return terminal.reader().peek(timeoutMs);
  }

  // ---- lifecycle ----

  @Override
  public void close() throws IOException {
    // Terminal is owned externally; not closed here.
  }
}
