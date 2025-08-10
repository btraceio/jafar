package io.jafar.parser;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.UntypedJafarParser;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class UntypedJafarParserCornerCasesTest {

    @Test
    void eventWithNoInterestedHandlersSkipsGracefully() throws Exception {
        ByteArrayOutputStream recordingStream = new ByteArrayOutputStream();
        try (org.openjdk.jmc.flightrecorder.writer.api.Recording recording = org.openjdk.jmc.flightrecorder.writer.api.Recordings.newRecording(recordingStream)) {
            io.jafar.TestJfrRecorder rec = new io.jafar.TestJfrRecorder(recording);
            rec.registerEventType(ParserEvent.class);
            rec.writeEvent(new ParserEvent(7));
        }
        Path tmp = Files.createTempFile("recording", ".jfr");
        Files.write(tmp, recordingStream.toByteArray());

        try (UntypedJafarParser p = UntypedJafarParser.open(tmp)) {
            // no handler registered -> run should simply parse and exit
            p.run();
        }
    }

    @Test
    void metadataClassProvidedToHandler() throws Exception {
        ByteArrayOutputStream recordingStream = new ByteArrayOutputStream();
        try (org.openjdk.jmc.flightrecorder.writer.api.Recording recording = org.openjdk.jmc.flightrecorder.writer.api.Recordings.newRecording(recordingStream)) {
            io.jafar.TestJfrRecorder rec = new io.jafar.TestJfrRecorder(recording);
            rec.registerEventType(ParserEvent.class);
            rec.writeEvent(new ParserEvent(9));
        }
        Path tmp = Files.createTempFile("recording", ".jfr");
        Files.write(tmp, recordingStream.toByteArray());

        try (UntypedJafarParser p = UntypedJafarParser.open(tmp)) {
            AtomicReference<MetadataClass> seen = new AtomicReference<>();
            HandlerRegistration<?> reg = p.handle((type, value) -> {
                seen.set(type);
                assertNotNull(value.get("value"));
            });
            p.run();
            reg.destroy(p);
            assertNotNull(seen.get());
        }
    }
}


