package io.jafar.demo;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.ConstantPoolValueProcessor;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.RecordingParserContext;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

public final class GenericParser {
    private static class ArrayHolder {
        private final Object array;
        private int index = 0;
        ArrayHolder(String type, int len) {
            switch (type) {
                case "short":
                    array = new short[len];
                    break;
                case "char":
                    array = new char[len];
                    break;
                case "int":
                    array = new int[len];
                    break;
                case "long":
                    array = new long[len];
                    break;
                case "byte":
                    array = new byte[len];
                    break;
                case "boolean":
                    array = new boolean[len];
                    break;
                case "double":
                    array = new double[len];
                    break;
                case "float":
                    array = new float[len];
                    break;
                default:
                    array = new Object[len];
            }
        }

        void add(Object value) {
            Array.set(array, index++, value);
        }

        Object getArray() {
            return array;
        }
    }
    public static final class ConstantPoolAccessor {
        private final Map<MetadataClass, Map<Long, Map<String, Object>>> constantPools;
        private final MetadataClass type;
        private final long pointer;

        ConstantPoolAccessor(Map<MetadataClass, Map<Long, Map<String, Object>>> constantPools, MetadataClass type, long pointer) {
            this.constantPools = constantPools;
            this.type = type;
            this.pointer = pointer;
        }

        public Map<String, Object> getValue() {
            return constantPools.get(type).get(pointer);
        }
    }
    public static void main(String[] args) throws Exception {
        try (StreamingChunkParser parser = new StreamingChunkParser()) {
            parser.parse(Paths.get(args[0]), new ChunkParserListener() {
                @Override
                public boolean onMetadata(MetadataEvent metadata, RecordingParserContext context) {
                    // use this callback to inspect/process metadata registrations
                    return true; // can return 'false' to abort processing
                }

                @Override
                public boolean onChunkStart(int chunkIndex, ChunkHeader header, RecordingStream stream) {
                    Map<MetadataClass, Map<Long, Map<String, Object>>> constantPool = new HashMap<>();

                    // register a custom value processor which can also work with constant pools
                    // the processor instance is exclusive for the chunk - chunks can be processed in parallel so be prepared
                    // the callbacks are executed as follows:
                    // - onConstantPoolValueStart -> <field processing> -> onConstantPoolValueEmd
                    // field processing can be either:
                    // - array: onArrayStart -> onValue | onConConstantPoolIndex -> onArrayEnd
                    // - inline complex type: onComplexValueStart -> <field processing> -> onComplexValueEnd
                    // - constant pool index: onConstantPoolIndex
                    // - primitive value: on*Value
                    ConstantPoolValueProcessor cpValueProcessor = new ConstantPoolValueProcessor() {
                        private final Deque<Map<String, Object>> complexValueStack = new ArrayDeque<>(15);
                        private final Deque<ArrayHolder> arrayStack = new ArrayDeque<>(5);
                        @Override
                        public void onConstantPoolValueStart(MetadataClass type, long id) {
                            Map<String, Object> value = new HashMap<>();
                            complexValueStack.push(value);
                        }

                        @Override
                        public void onConstantPoolValueEnd(MetadataClass type, long id) {
                            Map<String, Object> value = complexValueStack.pop();
                            assert complexValueStack.isEmpty();
                            Map<Long, Map<String, Object>> constants = constantPool.computeIfAbsent(type, k -> new HashMap<>());
                            constants.put(id, value);
                        }

                        @Override
                        public void onStringValue(MetadataClass owner, String fld, String value) {
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, value);
                        }

                        @SuppressWarnings("unchecked")
                        @Override
                        public void onComplexValueStart(MetadataClass owner, String fld, MetadataClass type) {
                            if (owner != null && !fld.equals("event")) {
                                Map<String, Object> value = new HashMap<>();
                                complexValueStack.push(value);
                            } else {
                                Map<String, Object> value = stream.getContext().get("event", Map.class);
                                complexValueStack.push(value);
                            }
                        }

                        @Override
                        public void onShortValue(MetadataClass type, String fld, short value) {
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, value);
                        }

                        @Override
                        public void onCharValue(MetadataClass type, String fld, char value) {
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, value);
                        }

                        @Override
                        public void onIntValue(MetadataClass owner, String fld, long value) {
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, value);
                        }

                        @Override
                        public void onLongValue(MetadataClass type, String fld, long value) {
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, value);
                        }

                        @Override
                        public void onByteValue(MetadataClass type, String fld, byte value) {
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, value);
                        }

                        @Override
                        public void onBooleanValue(MetadataClass owner, String fld, boolean value) {
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, value);
                        }

                        @Override
                        public void onDoubleValue(MetadataClass owner, String fld, double value) {
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, value);
                        }

                        @Override
                        public void onFloatValue(MetadataClass owner, String fld, float value) {
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, value);
                        }

                        @Override
                        public void onComplexValueEnd(MetadataClass owner, String fld, MetadataClass type) {
                            Map<String, Object> value = complexValueStack.pop();
                            assert value != null;
                            Map<String, Object> parent = complexValueStack.peek();
                            if (parent != null) {
                                parent.put(fld, value);
                            }
                        }

                        @Override
                        public void onArrayStart(MetadataClass owner, String fld, MetadataClass type, int len) {
                            ArrayHolder arr = new ArrayHolder(type.getName(), len);
                            arrayStack.push(arr);
                        }

                        @Override
                        public void onArrayEnd(MetadataClass owner, String fld, MetadataClass type) {
                            ArrayHolder arr = arrayStack.pop();
                            assert arr != null;
                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;
                            parent.put(fld, arr.getArray());
                        }

                        @Override
                        public void onConstantPoolIndex(MetadataClass owner, String fld, MetadataClass type, long pointer) {
                            MetadataClass cpType = type;
                            long id = type.getId();
                            if (type.isSimpleType()) {
                                cpType = type.getFields().getFirst().getType();
                                id = type.getFields().getFirst().getTypeId();
                            }
                            ConstantPoolAccessor cpAccessor = new ConstantPoolAccessor(constantPool, cpType, pointer);

                            Map<String, Object> parent = complexValueStack.peek();
                            assert parent != null;

                            parent.put(fld, cpAccessor);
                        }
                    };
                    stream.getContext().put(ConstantPoolValueProcessor.class, cpValueProcessor);
                    // store also the value reader instance to be reused in onEvent
                    stream.getContext().put(GenericValueReader.class, new GenericValueReader(cpValueProcessor));
                    return true;
                }

                @Override
                public boolean onEvent(long typeId, RecordingStream stream, long eventStartPos, long rawSize, long payloadSize) {
                    GenericValueReader r = stream.getContext().get(GenericValueReader.class);
                    try {
                        MetadataClass eventClz = stream.getContext().getMetadataLookup().getClass(typeId);
                        System.out.println("!!! event start " + eventClz.getName());
                        Map<String, Object> event = r.readEvent(stream, eventClz);
                        System.out.println("!!! event end " + eventClz.getName() + ":: " + event);
                    } catch (IOException e) {
                        return false;
                    }
                    return true;
                }
            });
        }
    }
}
