package io.jafar.mcp;

import reactor.core.publisher.Mono;

/** Contract for components that can signal when their shutdown sequence completes. */
interface ShutdownAwaitable {
  Mono<Void> awaitShutdown();
}
