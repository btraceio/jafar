package io.jafar.mcp;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.PrintStream;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class StdoutLockTest {

  @Test
  void lockStdoutAliasesSystemOutOntoSystemErrAndReturnsOriginal() throws Exception {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    try {
      Method lock = JafarMcpServer.class.getDeclaredMethod("lockStdout");
      lock.setAccessible(true);
      PrintStream returned = (PrintStream) lock.invoke(null);
      assertSame(System.err, System.out, "System.out must point to System.err after lockStdout()");
      assertNotSame(originalOut, System.out, "System.out must have changed");
      assertSame(
          originalOut,
          returned,
          "lockStdout() must return the original System.out so the transport can keep writing"
              + " to fd 1");
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
  }
}
