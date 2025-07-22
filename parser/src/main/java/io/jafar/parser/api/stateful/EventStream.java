package io.jafar.parser.api.stateful;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.ConstantPoolValueProcessor;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.impl.lazy.LazyParserContext;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.ValueProcessor;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@SuppressWarnings("unchecked")
public abstract class EventStream implements ChunkParserListener {
    @Override
    public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
        // use this callback to inspect/process metadata registrations
        return true; // can return 'false' to abort processing
    }

    @Override
    public final boolean onChunkStart(int chunkIndex, ChunkHeader header, RecordingStream stream) {
        ParserContext ctx = stream.getContext();
        // store a new MutableConstantPools
        ctx.put(ConstantPools.class, new ConstantPools());

        //! these two keys should be removed at `onChunkEnd` to avoid any unwanted leakage

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
            private final MultiTypeStack stack = new MultiTypeStack(20);

            @Override
            public void onConstantPoolValueStart(MetadataClass type, long id) {
                Map<String, Object> value = new HashMap<>();
                stack.push(value);
            }

            @SuppressWarnings("unchecked")
            @Override
            public void onConstantPoolValueEnd(MetadataClass type, long id) {
                Map<String, Object> value = (Map<String, Object>)stack.pop(Map.class);
                assert value != null && !value.isEmpty();
                LazyParserContext ctx = type.getContext();
                ConstantPools cpools = ctx.get(ConstantPools.class);
                assert cpools != null;
                cpools.add(type.getId(), id, value);
            }

            @Override
            public void onStringValue(MetadataClass owner, String fld, String value) {
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                    Map<String, Object> parent = (Map<String, Object>)stack.peek(Map.class);
                    assert parent != null;
                    parent.put(fld, value);
                }
            }

            @SuppressWarnings("unchecked")
            @Override
            public void onComplexValueStart(MetadataClass owner, String fld, MetadataClass type) {
                Map<String, Object> value = new HashMap<>();
                stack.push(value);
            }

            @Override
            public void onShortValue(MetadataClass type, String fld, short value) {
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                    Map<String, Object> parent = (Map<String, Object>)stack.peek(Map.class);
                    assert parent != null;
                    parent.put(fld, value);
                }
            }

            @Override
            public void onCharValue(MetadataClass type, String fld, char value) {
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                Map<String, Object> parent = (Map<String, Object>)stack.peek(Map.class);
                assert parent != null;
                parent.put(fld, value);
                }
            }

            @Override
            public void onIntValue(MetadataClass owner, String fld, long value) {
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                    Map<String, Object> parent = (Map<String, Object>)stack.peek(Map.class);
                    assert parent != null;
                    parent.put(fld, value);
                }
            }

            @Override
            public void onLongValue(MetadataClass type, String fld, long value) {
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                    Map<String, Object> parent = (Map<String, Object>)stack.peek(Map.class);
                    assert parent != null;
                    parent.put(fld, value);
                }
            }

            @Override
            public void onByteValue(MetadataClass type, String fld, byte value) {
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                    Map<String, Object> parent = (Map<String, Object>)stack.peek(Map.class);
                    assert parent != null;
                    parent.put(fld, value);
                }
            }

            @Override
            public void onBooleanValue(MetadataClass owner, String fld, boolean value) {
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                    Map<String, Object> parent = (Map<String, Object>)stack.peek(Map.class);
                    assert parent != null;
                    parent.put(fld, value);
                }
            }

            @Override
            public void onDoubleValue(MetadataClass owner, String fld, double value) {
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                    Map<String, Object> parent = (Map<String, Object>)stack.peek(Map.class);
                    assert parent != null;
                    parent.put(fld, value);
                }
            }

            @Override
            public void onFloatValue(MetadataClass owner, String fld, float value) {
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                    Map<String, Object> parent = (Map<String, Object>)stack.peek(Map.class);
                    assert parent != null;
                    parent.put(fld, value);
                }
            }

            @Override
            public void onComplexValueEnd(MetadataClass owner, String fld, MetadataClass type) {
                Map<String, Object> value = stack.pop(Map.class);
                assert value != null;
                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(value);
                } else {
                    if (owner != null && fld != null) {
                        Map<String, Object> parent = stack.peek(Map.class);
                        if (parent != null) {
                            parent.put(fld, value);
                        }
                    } else {
                        // a special case of 'free-floating' value - will be made available in the context
                        //    as 'type-name#value' of type Map
                        ctx.put(type.getName() + "#value", Map.class, value);
                    }
                }
            }

            @Override
            public void onArrayStart(MetadataClass owner, String fld, MetadataClass type, int len) {
                ArrayHolder arr = new ArrayHolder(type.getName(), len);
                stack.push(arr);
            }

            @Override
            public void onArrayEnd(MetadataClass owner, String fld, MetadataClass type) {
                ArrayHolder arr = stack.pop(ArrayHolder.class);
                assert arr != null;
                Map<String, Object> parent = stack.peek(Map.class);
                assert parent != null;
                parent.put(fld, arr);
            }

            @Override
            public void onConstantPoolIndex(MetadataClass owner, String fld, MetadataClass type, long pointer) {
                MetadataClass cpType = type;
                LazyParserContext ctx = owner.getContext();
                assert ctx != null;
                ConstantPools cpools = ctx.get(ConstantPools.class);
                ConstantPoolAccessor cpAccessor = new ConstantPoolAccessor(cpools, cpType, pointer);

                Map<String, Object> parent = stack.peek(Map.class);
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
    public final boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
//        context.remove(ConstantPools.class); // remove the constant pools from context
        // also clean up the parsers
        context.remove(ConstantPoolValueProcessor.class);
        context.remove(GenericValueReader.class);

        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public final boolean onEvent(long typeId, RecordingStream stream, long eventStartPos, long rawSize, long payloadSize) {
        try {
            LazyParserContext ctx = stream.getContext();
            GenericValueReader r = ctx.get(GenericValueReader.class);
            ValueProcessor vp = ctx.get(ConstantPoolValueProcessor.class);

            MetadataClass eventClz = ctx.getMetadataLookup().getClass(typeId);
            try {
                vp.onComplexValueStart(null, null, eventClz);
                r.readValue(stream, eventClz);
            } finally {
                vp.onComplexValueEnd(null, null, eventClz);
                Map<String, Object> value = (Map<String, Object>)ctx.remove(eventClz.getName() + "#value", Map.class);

                if (value.containsKey("stackTrace")) {
                    System.out.println("xxx");
                }
                onEventValue(value);
            }
        } catch (IOException e) {
            return false;
        }
        return true;
    }

    @Override
    public final void onRecordingStart(ParserContext context) {
        ChunkParserListener.super.onRecordingStart(context);
    }

    @Override
    public final boolean onCheckpoint(ParserContext context, CheckpointEvent checkpoint) {
        return ChunkParserListener.super.onCheckpoint(context, checkpoint);
    }

    @Override
    public final void onRecordingEnd(ParserContext context) {
        ChunkParserListener.super.onRecordingEnd(context);
    }

    protected abstract void onEventValue(Map<String, Object> value);
}
