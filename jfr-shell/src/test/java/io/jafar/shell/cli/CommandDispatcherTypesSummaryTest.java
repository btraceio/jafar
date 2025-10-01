package io.jafar.shell.cli;

import io.jafar.parser.api.ParsingContext;
import io.jafar.shell.JFRSession;
import io.jafar.shell.core.SessionManager;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class CommandDispatcherTypesSummaryTest {
    static class BufferIO implements CommandDispatcher.IO {
        final StringBuilder out = new StringBuilder();
        @Override public void println(String s) { out.append(s).append('\n'); }
        @Override public void printf(String fmt, Object... args) { out.append(String.format(fmt, args)); }
        @Override public void error(String s) { out.append(s).append('\n'); }
        String text() { return out.toString(); }
        void clear() { out.setLength(0); }
    }

    @Test
    void printsSummaryCounts() throws Exception {
        JFRSession session = Mockito.mock(JFRSession.class);
        when(session.getRecordingPath()).thenReturn(Path.of("/tmp/one.jfr"));
        when(session.getNonPrimitiveMetadataTypes()).thenReturn(Set.of("jdk.A", "jdk.B", "custom.C"));
        when(session.getPrimitiveMetadataTypes()).thenReturn(Set.of("int", "long"));
        when(session.getAvailableEventTypes()).thenReturn(Set.of("jdk.A", "custom.C"));

        SessionManager sm = new SessionManager(ParsingContext.create(), (p, c) -> session);
        BufferIO io = new BufferIO();
        CommandDispatcher disp = new CommandDispatcher(sm, io, r -> {});

        disp.dispatch("open /tmp/one.jfr --alias one");
        io.clear();
        disp.dispatch("metadata --summary");
        String out = io.text();
        assertTrue(out.contains("Types Summary:"));
        assertTrue(out.contains("Non-primitive:"));
        assertTrue(out.contains("Primitives:"));
        assertTrue(out.contains("All metadata:"));
    }
}
