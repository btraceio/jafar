package io.jafar.demo;

import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.ConstantPoolValueProcessor;
import io.jafar.parser.internal_api.GenericValueReader;
import io.jafar.parser.internal_api.RecordingParserContext;
import io.jafar.parser.internal_api.ValueProcessor;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataEvent;

import java.io.IOException;
import java.nio.file.Paths;

public final class GenericParser {
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
                        @Override
                        public void onConstantPoolValueStart(MetadataClass type, long id) {
                            System.out.println("===> !!! CP entry: " + type.getName() + "[" + type.getId() + "] :: " + id);
                        }

                        @Override
                        public void onConstantPoolValueEnd(MetadataClass type, long id) {
                            System.out.println("===> !!! End CP entry: " + type.getName() + " :: " + id);
                        }

                        @Override
                        public void onStringValue(MetadataClass owner, String fld, String value) {
                            System.out.println("===> " + owner.getName() + "." + fld + " = '" + value + "'");
                        }

                        @Override
                        public void onComplexValueStart(MetadataClass owner, String fld, MetadataClass type) {
                            System.out.println("===> Start " + owner.getName() + "." + fld + "<" + type.getName() + ">");
                        }

                        @Override
                        public void onComplexValueEnd(MetadataClass owner, String fld, MetadataClass type) {
                            System.out.println("===> End " + owner.getName() + "." + fld + "<" + type.getName() + ">");
                        }

                        @Override
                        public void onArrayStart(MetadataClass owner, String fld, MetadataClass type, int len) {
                            System.out.println("===> Array start: " + type.getName() + "[" + len + "]");
                        }

                        @Override
                        public void onConstantPoolIndex(MetadataClass owner, String fld, MetadataClass type, long pointer) {
                            long id = type.getId();
                            if (type.isSimpleType()) {
                                id = type.getFields().getFirst().getTypeId();
                            }
                            System.out.println("===> CP Index: [" + type.getName() + ":" + id + ", " + fld + ", " + pointer + "]");
                        }

                        @Override
                        public void onArrayEnd(MetadataClass owner, String fld, MetadataClass type) {
                            System.out.println("===> Array end");
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
                        r.readValue(stream, eventClz);
                        System.out.println("!!! event end " + eventClz.getName());
                    } catch (IOException e) {
                        return false;
                    }
                    return true;
                }
            });
        }
    }
}
