package io.jafar.parser;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.types.JFRExecutionSample;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TypedJafarParserLifecycleTest {

    @Test
    void testControlStreamPositionExposedInHandler() throws Exception {
        URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
        try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
            AtomicLong lastPos = new AtomicLong(-1);
            HandlerRegistration<JFRExecutionSample> reg = p.handle(JFRExecutionSample.class, (event, ctl) -> {
                long pos = ctl.stream().position();
                assertTrue(pos >= 0);
                lastPos.set(pos);
            });
            p.run();
            reg.destroy(p);
            assertTrue(lastPos.get() >= 0);
        }
    }

    @Test
    void testDestroyRegistrationStopsInvocationOnSubsequentRun() throws Exception {
        // create a synthetic recording with a single ParserEvent
        java.io.ByteArrayOutputStream recordingStream = new java.io.ByteArrayOutputStream();
        try (org.openjdk.jmc.flightrecorder.writer.api.Recording recording = org.openjdk.jmc.flightrecorder.writer.api.Recordings.newRecording(recordingStream)) {
            io.jafar.TestJfrRecorder rec = new io.jafar.TestJfrRecorder(recording);
            rec.registerEventType(ParserEvent.class);
            rec.writeEvent(new ParserEvent(42));
        }
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("recording", ".jfr");
        tmp.toFile().deleteOnExit();
        java.nio.file.Files.write(tmp, recordingStream.toByteArray());

        try (TypedJafarParser p = TypedJafarParser.open(tmp.toString())) {
            AtomicInteger count = new AtomicInteger();
            HandlerRegistration<ParserEvent1> r1 = p.handle(ParserEvent1.class, (e, c) -> count.incrementAndGet());
            HandlerRegistration<ParserEvent1> r2 = p.handle(ParserEvent1.class, (e, c) -> count.incrementAndGet());

            p.run();
            assertEquals(2, count.get());

            r1.destroy(p);

            p.run();
            assertEquals(3, count.get());

            r2.destroy(p);
        }
    }

    @Test
    void testRunAfterCloseThrows() throws Exception {
        URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
        TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath());
        p.close();
        assertThrows(java.io.IOException.class, p::run);
    }
}


