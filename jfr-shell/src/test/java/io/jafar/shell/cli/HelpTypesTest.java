package io.jafar.shell.cli;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.core.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HelpTypesTest {
    static class BufferIO implements CommandDispatcher.IO {
        final StringBuilder out = new StringBuilder();
        @Override public void println(String s) { out.append(s).append('\n'); }
        @Override public void printf(String fmt, Object... args) { out.append(String.format(fmt, args)); }
        @Override public void error(String s) { out.append(s).append('\n'); }
        String text() { return out.toString(); }
    }

    @Test
    void helpTypesShowsUsageAndExamples() {
        var sessions = new SessionManager(ParsingContext.create(), (p, c) -> null);
        var io = new BufferIO();
        var disp = new CommandDispatcher(sessions, io, r -> {});
        boolean handled = disp.dispatch("help metadata");
        assertTrue(handled);
        String t = io.text();
        assertTrue(t.contains("Usage: metadata"));
        assertTrue(t.contains("--search"));
        assertTrue(t.contains("--regex"));
        assertTrue(t.contains("metadata --search"));
    }
}
