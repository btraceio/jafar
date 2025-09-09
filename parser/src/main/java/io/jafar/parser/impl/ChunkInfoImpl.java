package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.internal_api.ChunkHeader;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

final class ChunkInfoImpl implements Control.ChunkInfo {
  private final Instant startTime;
  private final Duration duration;
  private final long size;
  private final double nanosPerTick;

  ChunkInfoImpl(ChunkHeader header) {
    this.startTime =
        Instant.ofEpochMilli(
            TimeUnit.MILLISECONDS.convert(header.startNanos, TimeUnit.NANOSECONDS));
    this.nanosPerTick = 1_000_000_000d / header.frequency;
    this.duration = Duration.of(Math.round(header.duration * nanosPerTick), ChronoUnit.NANOS);
    this.size = header.size;
  }

  @Override
  public Instant startTime() {
    return startTime;
  }

  @Override
  public Duration duration() {
    return duration;
  }

  @Override
  public long size() {
    return size;
  }

  @Override
  public long convertTicks(long ticks, TimeUnit unit) {
    return unit.convert(Math.round(ticks * nanosPerTick), TimeUnit.NANOSECONDS);
  }
}
