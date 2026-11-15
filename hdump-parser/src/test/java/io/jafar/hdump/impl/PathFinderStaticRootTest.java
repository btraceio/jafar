package io.jafar.hdump.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.management.HotSpotDiagnosticMXBean;
import io.jafar.hdump.api.HeapDump;
import io.jafar.hdump.api.HeapDumpParser;
import io.jafar.hdump.api.HeapObject;
import io.jafar.hdump.api.PathStep;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathFinderStaticRootTest {

  /** Reachable through this static field alone. */
  static final StaticHeld HELD = new StaticHeld();

  static final class StaticHeld {
    final Object[] payload = {new Object()};
  }

  @Test
  void findsPathFromStaticField(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("dump.hprof");
    ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
        .dumpHeap(file.toString(), true);

    try (HeapDump dump = HeapDumpParser.parse(file)) {
      HeapObject held =
          dump.getObjectsOfClass(StaticHeld.class.getName().replace('.', '/')).findFirst().get();

      List<PathStep> path = dump.findPathToGcRoot(held);

      assertFalse(path.isEmpty(), "object held by a static field has a path");
      assertEquals(
          "static " + PathFinderStaticRootTest.class.getName().replace('.', '/') + ".HELD",
          path.get(0).fieldName());
      assertEquals(held.getId(), path.get(path.size() - 1).object().getId());
    }
  }
}
