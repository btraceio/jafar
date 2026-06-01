package io.jafar.parser.benchmark;

import io.jafar.parser.impl.UntypedParserContextFactory;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.StreamingChunkParser;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(java.util.concurrent.TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 2, timeUnit = java.util.concurrent.TimeUnit.SECONDS)
@Fork(1)
public class StreamingChunkParserIoBenchmark {

  @Param({"parser-core/src/test/resources/test-jfr.jfr"})
  private String filePath;

  private Path path;
  private byte[] fileData;
  private final AtomicInteger pathCount = new AtomicInteger();
  private final AtomicInteger streamCount = new AtomicInteger();

  @Benchmark
  public void parseViaPath(Blackhole bh) throws Exception {
    pathCount.set(0);
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          path,
          new ChunkParserListener() {
            @Override
            public boolean onChunkStart(
                io.jafar.parser.api.ParserContext ctx,
                int idx,
                io.jafar.parser.internal_api.ChunkHeader h) {
              pathCount.incrementAndGet();
              return true;
            }

            @Override
            public boolean onMetadata(
                io.jafar.parser.api.ParserContext ctx,
                io.jafar.parser.internal_api.metadata.MetadataEvent event) {
              return true;
            }

            @Override
            public boolean onCheckpoint(
                io.jafar.parser.api.ParserContext ctx,
                io.jafar.parser.internal_api.CheckpointEvent event) {
              return true;
            }

            @Override
            public boolean onEvent(
                io.jafar.parser.api.ParserContext ctx,
                long eventType,
                long position,
                long size,
                long payloadSize) {
              bh.consume(eventType);
              return true;
            }
          });
    }
    bh.consume(pathCount.get());
  }

  @Benchmark
  public void parseViaStream(Blackhole bh) throws Exception {
    streamCount.set(0);
    java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(fileData);
    try (StreamingChunkParser parser =
        new StreamingChunkParser(new UntypedParserContextFactory())) {
      parser.parse(
          bais,
          new ChunkParserListener() {
            @Override
            public boolean onChunkStart(
                io.jafar.parser.api.ParserContext ctx,
                int idx,
                io.jafar.parser.internal_api.ChunkHeader h) {
              streamCount.incrementAndGet();
              return true;
            }

            @Override
            public boolean onMetadata(
                io.jafar.parser.api.ParserContext ctx,
                io.jafar.parser.internal_api.metadata.MetadataEvent event) {
              return true;
            }

            @Override
            public boolean onCheckpoint(
                io.jafar.parser.api.ParserContext ctx,
                io.jafar.parser.internal_api.CheckpointEvent event) {
              return true;
            }

            @Override
            public boolean onEvent(
                io.jafar.parser.api.ParserContext ctx,
                long eventType,
                long position,
                long size,
                long payloadSize) {
              bh.consume(eventType);
              return true;
            }
          });
    }
    bh.consume(streamCount.get());
  }
}
