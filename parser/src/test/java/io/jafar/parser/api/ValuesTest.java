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
  void get_navigates_and_unwraps_complex_and_arrays() {
    Map<String, Object> event = buildEvent();

    Object tid = Values.get(event, "eventThread", "javaThreadId");
    assertEquals(123L, tid);

    Object framesVal = Values.get(event, "stackTrace", "frames");
    assertTrue(framesVal instanceof Object[]);
    Object[] frames = (Object[]) framesVal;
    assertEquals(3, frames.length);

    Object method1 = Values.get(event, "stackTrace", "frames", 1, "method");
    assertEquals("m1", method1);

    Object int2 = Values.get(event, "ints", 2);
    assertEquals(3, int2);
  }

  @Test
  void as_extracts_typed_values() {
    Map<String, Object> event = buildEvent();

    // eventThread resolves to a Map via ComplexType
    assertTrue(Values.as(event, Map.class, "eventThread").isPresent());
    Map<String, Object> thread = Values.as(event, Map.class, "eventThread").get();
    assertEquals("Main", thread.get("name"));

    // javaThreadId is a Long
    assertTrue(Values.as(event, Long.class, "eventThread", "javaThreadId").isPresent());
    assertTrue(Values.as(event, String.class, "eventThread", "javaThreadId").isEmpty());
  }

  @Test
  void resolved_maps_unwrap_wrappers() {
    Map<String, Object> event = buildEvent();

    Map<String, Object> shallow = Values.resolvedShallow(event);
    assertTrue(shallow.get("eventThread") instanceof Map);
    // shallow does not unwrap nested wrapper under 'stackTrace', only top-level
    assertTrue(((Map<?, ?>) shallow.get("stackTrace")).get("frames") instanceof ArrayType);

    Map<String, Object> deep = Values.resolvedDeep(event);
    assertTrue(deep.get("eventThread") instanceof Map);
    Object[] frames = (Object[]) ((Map<?, ?>) deep.get("stackTrace")).get("frames");
    assertTrue(frames[1] instanceof Map);
    assertEquals("m1", ((Map<?, ?>) frames[1]).get("method"));
  }
}
