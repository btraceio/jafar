package io.jafar.parser;

import io.jafar.TestJfrRecorder;
import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.JafarParser;
import io.jafar.parser.api.types.JFRStackFrame;
import io.jafar.parser.api.types.JFRStackTrace;
import org.junit.jupiter.api.Test;
import org.openjdk.jmc.flightrecorder.writer.api.Recording;
import org.openjdk.jmc.flightrecorder.writer.api.Recordings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JafarParserTest {
    @Test
    void testEventParsing() throws Exception {

        ByteArrayOutputStream recordingStream = new ByteArrayOutputStream();
        long eventTypeId = -1;
        try (Recording recording = Recordings.newRecording(recordingStream)) {
            TestJfrRecorder rec = new TestJfrRecorder(recording);
            eventTypeId = rec.registerEventType(ParserEvent.class).getId();
            rec.writeEvent(new ParserEvent(10));
        }

        assertNotEquals(-1, eventTypeId);

        Path tmpFile = Files.createTempFile("recording", ".jfr");
        tmpFile.toFile().deleteOnExit();

        Files.write(tmpFile, recordingStream.toByteArray());

        JafarParser parser = JafarParser.open(tmpFile.toString());

        AtomicInteger eventCount = new AtomicInteger(0);
        parser.handle(ParserEvent1.class, (event, ctl) -> {
            eventCount.incrementAndGet();
            assertEquals(10, event.value());
        });

        parser.run();

        assertEquals(1, eventCount.get());
    }

    @Test
    void testRealFile() throws Exception {
        URI uri = JafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();

        try (JafarParser p = JafarParser.open(new File(uri).getAbsolutePath())) {
            AtomicLong eventCount = new AtomicLong(0);
            HandlerRegistration<ExecutionSampleEvent> h1 = p.handle(ExecutionSampleEvent.class, (event, ctl) -> {
                assertNotNull(event.eventThread());
                assertNotNull(event.stackTrace());
                assertNotNull(event.eventThread());
                assertTrue(event.stackTrace().frames().length > 0);
                eventCount.incrementAndGet();
            });
        }
    }
}
