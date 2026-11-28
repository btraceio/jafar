package io.jafar.hdump.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jafar.hdump.api.HeapDumpParser.ParserOptions;
import io.jafar.hdump.shell.hdumppath.HdumpPathEvaluator;
import io.jafar.hdump.shell.hdumppath.HdumpPathParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #302: gcroots rows of THREAD_OBJ roots had no threadSerial. The parser keeps it, but behind
 * getThreadObjSerial(); the row read getThreadSerial(), which is -1 for THREAD_OBJ.
 */
class GcRootThreadSerialTest {

  @TempDir Path dir;

  @Test
  void threadObjRootsCarryTheirThreadSerial() throws Exception {
    check(false);
  }

  /** INDEXED mode builds its roots from the gcroots index, not the parse. */
  @Test
  void threadObjRootsCarryTheirThreadSerialWhenIndexed() throws Exception {
    check(true);
  }

  private void check(boolean indexed) throws Exception {
    Path hprof =
        new MinimalHprofBuilder()
            .addClass(100, "java/lang/Thread")
            .addInstance(1000, 100)
            .addInstance(1001, 100)
            .addThreadObjRoot(1000, 7)
            .addThreadObjRoot(1001, 8)
            .addJavaFrameRoot(1000, 7, 3)
            .write(dir);
    try (HeapSession session =
        indexed ? HeapSession.open(hprof, ParserOptions.INDEXED) : HeapSession.open(hprof)) {
      List<Map<String, Object>> threads =
          HdumpPathEvaluator.evaluate(
              session, HdumpPathParser.parse("gcroots/THREAD_OBJ | sortBy(threadSerial)"));
      assertEquals(2, threads.size(), threads.toString());
      assertEquals(7, threads.get(0).get("threadSerial"), threads.toString());
      assertEquals(8, threads.get(1).get("threadSerial"), threads.toString());
      // The doc's example matches them; a frame root keeps its own serial
      assertEquals(
          1,
          HdumpPathEvaluator.evaluate(
                  session, HdumpPathParser.parse("gcroots/THREAD_OBJ[threadSerial > 7]"))
              .size());
      Map<String, Object> frame =
          HdumpPathEvaluator.evaluate(session, HdumpPathParser.parse("gcroots/JAVA_FRAME")).get(0);
      assertEquals(7, frame.get("threadSerial"), frame.toString());
      assertEquals(3, frame.get("frameNumber"), frame.toString());
    }
  }
}
