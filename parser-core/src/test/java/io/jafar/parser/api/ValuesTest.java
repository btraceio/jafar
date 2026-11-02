package io.jafar.parser.api;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class ValuesTest {

  private static ComplexType complex(Map<String, Object> value) {
    return new ComplexType() {
      @Override
      public Map<String, Object> getValue() {
        return value;
      }
    };
  }

  private static ArrayType objArray(Object[] arr) {
    return new ArrayType() {
      @Override
      public Class<?> getType() {
        return arr.getClass();
      }

      @Override
      public Object getArray() {
        return arr;
      }
    };
  }

  private static ArrayType intArray(int[] arr) {
    return new ArrayType() {
      @Override
      public Class<?> getType() {
        return arr.getClass();
      }

      @Override
      public Object getArray() {
        return arr;
      }
    };
  }

  private Map<String, Object> buildEvent() {
    Map<String, Object> root = new HashMap<>();

    // eventThread as ComplexType -> Map
    Map<String, Object> thread = new HashMap<>();
    thread.put("javaThreadId", 123L);
    thread.put("name", "Main");
    root.put("eventThread", complex(thread));

    // stackTrace.frames as ArrayType of Object[] with mixed elements
    Map<String, Object> frame0 = new HashMap<>();
    frame0.put("method", "m0");

    Map<String, Object> frame1m = new HashMap<>();
    frame1m.put("method", "m1");

    Object[] frames = new Object[] {frame0, complex(frame1m), 42};
    Map<String, Object> stackTrace = new HashMap<>();
    stackTrace.put("frames", objArray(frames));
    root.put("stackTrace", stackTrace);

    // primitive array
    root.put("ints", intArray(new int[] {1, 2, 3}));

    return root;
  }

  @Test
  void getNavigatesAndUnwrapsComplexAndArrays() {
    Map<String, Object> event = buildEvent();

    Object tid = Values.get(event, "eventThread", "javaThreadId");
    assertEquals(123L, tid);

    Object framesVal = Values.get(event, "stackTrace", "frames");
    assertInstanceOf(Object[].class, framesVal);
    Object[] frames = (Object[]) framesVal;
    assertEquals(3, frames.length);

    Object method1 = Values.get(event, "stackTrace", "frames", 1, "method");
    assertEquals("m1", method1);

    Object int2 = Values.get(event, "ints", 2);
    assertEquals(3, int2);
  }

  @Test
  void asExtractsTypedValues() {
    Map<String, Object> event = buildEvent();

    // eventThread resolves to a Map via ComplexType
    assertTrue(Values.as(event, Map.class, "eventThread").isPresent());
    long tid = Values.as(event, Long.class, "eventThread", "javaThreadId").orElse(-1L);
    assertEquals(123L, tid);
    Map<String, Object> thread = Values.as(event, Map.class, "eventThread").get();
    assertEquals("Main", thread.get("name"));

    // javaThreadId is a Long
    assertTrue(Values.as(event, Long.class, "eventThread", "javaThreadId").isPresent());
    assertTrue(Values.as(event, String.class, "eventThread", "javaThreadId").isEmpty());
  }

  @Test
  void resolvedMapsUnwrapWrappers() {
    Map<String, Object> event = buildEvent();

    Map<String, Object> shallow = Values.resolvedShallow(event);
    assertInstanceOf(Map.class, shallow.get("eventThread"));
    // shallow does not unwrap nested wrapper under 'stackTrace', only top-level
    assertInstanceOf(ArrayType.class, ((Map<?, ?>) shallow.get("stackTrace")).get("frames"));

    Map<String, Object> deep = Values.resolvedDeep(event);
    assertInstanceOf(Map.class, deep.get("eventThread"));
    Object[] frames = (Object[]) ((Map<?, ?>) deep.get("stackTrace")).get("frames");
    assertInstanceOf(Map.class, frames[1]);
    assertEquals("m1", ((Map<?, ?>) frames[1]).get("method"));
  }

  private static ComplexType simple(Map<String, Object> value) {
    return new ComplexType() {
      @Override
      public Map<String, Object> getValue() {
        return value;
      }

      @Override
      public boolean isSimpleType() {
        return true;
      }
    };
  }

  @Test
  void collapsedResolvesSimpleTypeReferencesToTheirValue() {
    Map<String, Object> event = new HashMap<>();
    event.put("state", simple(Map.of("name", "RUNNABLE")));
    // A wrapper around a wrapper, and wrappers inside an array.
    event.put("nested", simple(Map.of("value", simple(Map.of("string", "deep")))));
    event.put(
        "many", objArray(new Object[] {simple(Map.of("name", "A")), simple(Map.of("name", "B"))}));
    // A genuine struct with one field is not a simple type and must keep its shape,
    // which is what a size()==1 heuristic would get wrong.
    event.put("struct", complex(Map.of("only", "x")));
    event.put("plain", 7L);

    Map<String, Object> out = Values.resolvedDeepCollapsed(event);

    assertEquals("RUNNABLE", out.get("state"));
    assertEquals("deep", out.get("nested"));
    assertArrayEquals(new Object[] {"A", "B"}, (Object[]) out.get("many"));
    assertEquals(Map.of("only", "x"), out.get("struct"));
    assertEquals(7L, out.get("plain"));
  }

  @Test
  void resolvedDeepStillKeepsWrappers() {
    Map<String, Object> event = new HashMap<>();
    event.put("state", simple(Map.of("name", "RUNNABLE")));

    assertEquals(Map.of("name", "RUNNABLE"), Values.resolvedDeep(event).get("state"));
  }

  @Test
  void collapsedKeepsNullReference() {
    Map<String, Object> event = new HashMap<>();
    event.put("gone", simple(null));

    assertNull(Values.resolvedDeepCollapsed(event).get("gone"));
  }
}
