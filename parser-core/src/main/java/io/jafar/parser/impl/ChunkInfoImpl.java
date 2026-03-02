package io.jafar.parser.impl;

import io.jafar.parser.api.Control;
import io.jafar.parser.internal_api.ChunkHeader;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;

final class ChunkInfoImpl implements Control.ChunkInfo {
  private static final AtomicLong CHUNK_COUNTER = new AtomicLong();
  private final Instant startTime;
  private final long startNanos;
  private final long startTicks;
  private final Duration duration;
  private final long size;
  private final double nanosPerTick;
  private final long id;

  ChunkInfoImpl(ChunkHeader header) {
    this.startNanos = header.startNanos;
    this.startTime = Instant.ofEpochSecond(0, header.startNanos);
    this.startTicks = header.startTicks;
    this.nanosPerTick = 1_000_000_000d / header.frequency;
    this.duration = Duration.of(Math.round(header.duration * nanosPerTick), ChronoUnit.NANOS);
    this.size = header.size;
    this.id = CHUNK_COUNTER.incrementAndGet();
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
  public long chunkId() {
    return id;
  }

  @Override
  public Duration asDuration(long ticks) {
    return Duration.ofNanos(Math.round(nanosPerTick * ticks));
  }

  @Override
  public long asDurationNanos(long ticks) {
    return Math.round(nanosPerTick * ticks);
  }

  @Override
  public Instant asInstant(long ticks) {
    long tickDiff = ticks - startTicks;
    long nanoDiff = Math.round(tickDiff * nanosPerTick);
    return startTime.plus(nanoDiff, ChronoUnit.NANOS);
  }

  @Override
  public long asEpochNanos(long ticks) {
    return startNanos + Math.round((ticks - startTicks) * nanosPerTick);
  }
}
