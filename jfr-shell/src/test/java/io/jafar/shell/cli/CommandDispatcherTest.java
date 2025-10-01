package io.jafar.shell.cli;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandDispatcherTest {

    static class BufferIO implements CommandDispatcher.IO {
        final StringBuilder out = new StringBuilder();
        @Override public void println(String s) { out.append(s).append('\n'); }
        @Override public void printf(String fmt, Object... args) { out.append(String.format(fmt, args)); }
        @Override public void error(String s) { out.append(s).append('\n'); }
        String text() { return out.toString(); }
        void clear() { out.setLength(0); }
    }

    ParsingContext ctx;
    SessionManager sm;
    BufferIO io;
    CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        ctx = ParsingContext.create();
        SessionManager.JFRSessionFactory factory = (path, c) -> {
            JFRSession s = Mockito.mock(JFRSession.class);
            when(s.getRecordingPath()).thenReturn(path);
            when(s.getAvailableEventTypes()).thenReturn(java.util.Set.of());
            when(s.getHandlerCount()).thenReturn(0);
            when(s.hasRun()).thenReturn(false);
            return s;
        };
        sm = new SessionManager(ctx, factory);
        io = new BufferIO();
        dispatcher = new CommandDispatcher(sm, io, r -> {});
    }

    @Test
    void openSessionsInfoCloseFlow() {
        boolean handled = dispatcher.dispatch("open /tmp/one.jfr --alias one");
        assertTrue(handled);
        assertEquals(1, sm.list().size());
        assertTrue(io.text().contains("Opened session #1 (one)"));

        io.clear();
        dispatcher.dispatch("sessions");
        assertTrue(io.text().contains("*#1 one"));

        io.clear();
        dispatcher.dispatch("info");
        assertTrue(io.text().contains("Session Information:"));
        assertTrue(io.text().contains("Recording: /tmp/one.jfr"));

        io.clear();
        dispatcher.dispatch("close");
        assertTrue(io.text().contains("Closed session #1") || io.text().contains("Closed session 1"));
        assertTrue(sm.list().isEmpty());
    }
}

