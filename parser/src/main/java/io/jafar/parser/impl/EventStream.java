package io.jafar.parser.impl;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.CheckpointEvent;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.ConstantPoolValueProcessor;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.ValueProcessor;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

/**
 * Abstract base class for event stream processing in JFR recordings.
 * <p>
 * This class implements ChunkParserListener and provides a framework for
 * processing JFR events with support for constant pools and value processing.
 * </p>
 */
@SuppressWarnings("unchecked")
public abstract class EventStream implements ChunkParserListener {
    /** The delegate chunk parser listener. */
    private final ChunkParserListener delegate;

    /**
     * Constructs a new EventStream with the specified delegate.
     * 
     * @param delegate the delegate chunk parser listener
     */
    public EventStream(ChunkParserListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public final boolean onMetadata(ParserContext context, MetadataEvent metadata) {
        // use this callback to inspect/process metadata registrations
        return delegate == null || delegate.onMetadata(context, metadata); // can return 'false' to abort processing
    }

    @Override
    public final boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
        // store a new MutableConstantPools
        context.put(ConstantPools.class, new ConstantPools());

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
                ParserContext ctx = type.getContext();
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
                        context.put(type.getName() + "#value", Map.class, value);
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
                ParserContext ctx = owner.getContext();
                assert ctx != null;
                ConstantPools cpools = ctx.get(ConstantPools.class);
                ConstantPoolAccessor cpAccessor = new ConstantPoolAccessor(cpools, type, pointer);

                ArrayHolder ah = stack.peek(ArrayHolder.class);
                if (ah != null) {
                    ah.add(cpAccessor);
                } else {
                    Map<String, Object> parent = stack.peek(Map.class);
                    assert parent != null;
                    parent.put(fld, cpAccessor);
                }
            }
        };
        context.put(ConstantPoolValueProcessor.class, cpValueProcessor);
        // store also the value reader instance to be reused in onEvent
        context.put(GenericValueReader.class, new GenericValueReader(cpValueProcessor));
        return delegate == null || delegate.onChunkStart(context, chunkIndex, header);
    }

    @Override
    public final boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
//        context.remove(ConstantPools.class); // remove the constant pools from context
        // also clean up the parsers
        context.remove(ConstantPoolValueProcessor.class);
        context.remove(GenericValueReader.class);

        return delegate == null || delegate.onChunkEnd(context, chunkIndex, skipped);
    }

    @SuppressWarnings("unchecked")
    @Override
    public final boolean onEvent(ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
        try {
            GenericValueReader r = context.get(GenericValueReader.class);
            ValueProcessor vp = context.get(ConstantPoolValueProcessor.class);

            MetadataClass eventClz = context.getMetadataLookup().getClass(typeId);
            try {
                vp.onComplexValueStart(null, null, eventClz);
                r.readValue(context.get(RecordingStream.class), eventClz);
            } finally {
                vp.onComplexValueEnd(null, null, eventClz);
                Map<String, Object> value = (Map<String, Object>)context.remove(eventClz.getName() + "#value", Map.class);

                // Process event value with parsed data
                onEventValue(eventClz, value);
            }
        } catch (IOException e) {
            return delegate != null && delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
        }
        return delegate == null || delegate.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
    }

    @Override
    public final void onRecordingStart(ParserContext context) {
        if (delegate != null) {
            delegate.onRecordingStart(context);
        } else {
            ChunkParserListener.super.onRecordingStart(context);
        }
    }

    @Override
    public final boolean onCheckpoint(ParserContext context, CheckpointEvent checkpoint) {
        return delegate != null ? delegate.onCheckpoint(context, checkpoint) : ChunkParserListener.super.onCheckpoint(context, checkpoint);
    }

    @Override
    public final void onRecordingEnd(ParserContext context) {
        if (delegate != null) {
            delegate.onRecordingEnd(context);
        } else {
            ChunkParserListener.super.onRecordingEnd(context);
        }
    }

    /**
     * Called when an event value is processed.
     * <p>
     * This method is called for each event value that is successfully parsed
     * and should be implemented by subclasses to handle the event data.
     * </p>
     * 
     * @param type the metadata class type of the event
     * @param value the parsed event value as a map
     */
    protected abstract void onEventValue(MetadataClass type, Map<String, Object> value);
}
