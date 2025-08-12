package io.jafar.parser;

import io.jafar.parser.api.HandlerRegistration;
import io.jafar.parser.api.ParsingContext;
import io.jafar.parser.api.TypedJafarParser;
import io.jafar.parser.types.JFRExecutionSample;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class TypedJafarParserStabilityTest {

    @RepeatedTest(3)
    void multipleRunsWithSharedContext() throws Exception {
        URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
        ParsingContext ctx = ParsingContext.create();
        for (int i = 0; i < 3; i++) {
            try (TypedJafarParser p = ctx.newTypedParser(new File(uri).toPath())) {
                HandlerRegistration<JFRExecutionSample> reg = p.handle(JFRExecutionSample.class, (e, c) -> {});
                p.run();
                reg.destroy(p);
            }
        }
        assertTrue(ctx.uptime() > 0);
    }

    @Test
    void runsInParallelThreads() throws Exception {
        URI uri = TypedJafarParserTest.class.getClassLoader().getResource("test-ap.jfr").toURI();
        var exec = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);
        Runnable task = () -> {
            try (TypedJafarParser p = TypedJafarParser.open(new File(uri).getAbsolutePath())) {
                HandlerRegistration<JFRExecutionSample> reg = p.handle(JFRExecutionSample.class, (e, c) -> {});
                p.run();
                reg.destroy(p);
            } catch (Exception ignored) {
            } finally {
                latch.countDown();
            }
        };
        exec.submit(task);
        exec.submit(task);
        latch.await(10, TimeUnit.SECONDS);
        exec.shutdownNow();
        assertTrue(latch.getCount() == 0);
    }
}


