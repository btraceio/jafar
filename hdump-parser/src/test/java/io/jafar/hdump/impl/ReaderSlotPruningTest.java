package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.test.SyntheticHeapDumpGenerator;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A heap dump keeps one slot per thread that read it, so that {@link HeapDumpImpl#close()} can
 * release every thread's view of the mapped file (#227). A dump stays open for a whole MCP session
 * while the server's thread pool replaces idle threads, so slots of threads that have exited must
 * not accumulate.
 */
class ReaderSlotPruningTest {

  @TempDir Path tempDir;

  @Test
  void slotsOfExitedThreadsArePrunedWhenANewThreadArrives() throws Exception {
    Path file = tempDir.resolve("dump.hprof");
    SyntheticHeapDumpGenerator.generateMinimalHeapDump(file, 10);
    try (HeapDump dump = HeapDumpParser.parse(file)) {
      HeapDumpImpl impl = (HeapDumpImpl) dump;
      int before = impl.readerSlotCount(); // threads that read the dump while it was parsed
      for (int i = 0; i < 200; i++) {
        Thread thread = new Thread(impl::getThreadLocalReader);
        thread.start();
        thread.join();
      }

      // A new thread's first read prunes every exited thread's slot and adds its own.
      int[] held = new int[1];
      Thread last =
          new Thread(
              () -> {
                impl.getThreadLocalReader();
                held[0] = impl.readerSlotCount();
              });
      last.start();
      last.join();

      assertEquals(before + 1, held[0], "slots held after 200 threads read and left");
    }
  }
}
