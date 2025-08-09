package io.jafar.tools;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.api.ParserContext;
import io.jafar.parser.internal_api.ChunkHeader;
import io.jafar.parser.internal_api.ChunkParserListener;
import io.jafar.parser.internal_api.ParserContextFactory;
import io.jafar.parser.internal_api.RecordingStream;
import io.jafar.parser.internal_api.SimpleParserContextFactory;
import io.jafar.parser.internal_api.StreamingChunkParser;
import io.jafar.parser.internal_api.TypeSkipper;
import io.jafar.parser.internal_api.metadata.MetadataEvent;
import io.jafar.parser.internal_api.metadata.MetadataField;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class Scrubber {

    private static final String SCRUBBING_INFO_KEY = "scrubbingInfo";

    record SkipInfo(long startPos, long endPos) {}

    record TypeScrubbing(long typeId, TypeSkipper skipper, int scrubFieldIndex, int scrubGuardIndex, BiFunction<String, String, Boolean> guard) {
        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            TypeScrubbing that = (TypeScrubbing) o;
            return typeId == that.typeId;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(typeId);
        }
    }

    /**
     * Represents a field that should be scrubbed in the recording.
     * The field will be replaced with a string of 'x' bytes if the guard condition is met.
     */
    public static final class ScrubField  {
        final String scrubFieldName;
        final String guardFieldName;
        final BiFunction<String, String, Boolean> guard;

        /**
         * Creates a new ScrubField instance.
         * @param guardFieldName the name of the field that will be used as a guard
         * @param scrubFieldName the name of the field to scrub
         * @param guard a function that takes the guard field value and the scrub field value and returns true if scrubbing should be applied
         */
        public ScrubField(String guardFieldName, String scrubFieldName, BiFunction<String, String, Boolean> guard) {
            this.scrubFieldName = scrubFieldName;
            this.guardFieldName = guardFieldName;
            this.guard = guard;
        }

        @Override
        public String toString() {
            return "ScrubField{" +
                    "scrubFieldName='" + scrubFieldName + '\'' +
                    ", guardFieldName='" + guardFieldName + '\'' +
                    '}';
        }
    }

    private static class ScrubbingInfo {
        long chunkOffset;
        Set<SkipInfo> skipInfo;
        Map<Long, TypeScrubbing> targetClassMap;
    }

    /**
     * Scrub the given file by replacing the specified fields with a string of 'x' bytes.
     * @param input the input file to scrub
     * @param output the output file to write the scrubbed content to
     * @param scrubDefinition a function that defines which fields to scrub.
     * @throws Exception if an error occurs during parsing or writing
     */
    public static void scrubFile(Path input, Path output, Function<String, ScrubField> scrubDefinition) throws Exception {
        Set<SkipInfo> globalSkipInfo = new TreeSet<>(Comparator.comparingLong(o -> o.endPos));
        ParserContextFactory contextFactory = new SimpleParserContextFactory();

        try (StreamingChunkParser parser = new StreamingChunkParser(contextFactory)) {
            parser.parse(input, new SkipInfoCollector(scrubDefinition, globalSkipInfo));
        }

        scrubFile(input, output, globalSkipInfo);
    }

    private static void scrubFile(Path input, Path output, Set<SkipInfo> skipRanges) throws Exception {
        final int BUF_SIZE = 64 * 1024;
        ByteBuffer copyBuf = ByteBuffer.allocateDirect(BUF_SIZE);
        try (FileChannel in = FileChannel.open(input, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(output, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            long pos = 0;
            for (SkipInfo range : skipRanges) {
                long from = range.startPos, to = range.endPos;
                // Copy region before the skip
                long chunkSize = from - pos;
                if (chunkSize > 0) {
                    copyRegion(in, out, pos, chunkSize, copyBuf);
                }

                // We want to fill the interval [from, to) with a string of 'x' bytes
                // The string bytes are as follows:
                // - 1 byte for the type (4 = string)
                // - varint for the length of the string
                // - the string itself [array of bytes]
                // We need to compute the length of the string such that:
                // 1 + varintSize(payloadLen) + payloadLen == to - from
                long s = to - from - 1;
                int payloadLen = computeFittingPayloadLength((int) s);
                if (payloadLen > BUF_SIZE) {
                    System.out.println("xxx");
                }
                copyBuf.clear();
                copyBuf.put((byte)4); // string encoded as byte array
                writeVarint(copyBuf, payloadLen);
                for (int i = 0; i < payloadLen; i++) {
                    copyBuf.put((byte) 'x');
                }
                copyBuf.flip();
                while (copyBuf.hasRemaining()) {
                    out.write(copyBuf);
                }
                in.position(to); // advance the stream position
                pos = to;
            }

            // Copy the remainder of the file after last skip
            long fileSize = in.size();
            if (pos < fileSize) {
                copyRegion(in, out, pos, fileSize - pos, copyBuf);
            }
        }


    }

    public static void writeVarint(ByteBuffer buf, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            buf.put((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.put((byte) value);
    }

    /**
     * Returns the number of bytes required to encode the given value as a varint.
     */
    static int varintSize(int value) {
        int size = 0;
        do {
            size++;
            value >>>= 7;
        } while (value != 0);
        return size;
    }

    /**
     * Computes the payload length such that varintSize(payloadLen) + payloadLen == totalLen.
     */
    static int computeFittingPayloadLength(int totalLen) {
        for (int len = totalLen; len >= 0; len--) {
            if (varintSize(len) + len == totalLen) return len;
        }
        throw new IllegalArgumentException("Cannot compute fitting payload length for: " + totalLen);
    }

    static void copyRegion(FileChannel in, FileChannel out, long pos, long size, ByteBuffer buf) throws IOException {
        in.position(pos);
        long remaining = size;
        while (remaining > 0) {
            buf.clear();
            int read = in.read(buf);
            if (read == -1) break;
            buf.flip();
            if (read > remaining) {
                buf.limit((int) remaining);
                in.position(in.position() + remaining - read);
            }
            while (buf.hasRemaining()) {
                int written = out.write(buf);
                remaining -= written;
            }
        }
    }

    private static class SkipInfoCollector implements ChunkParserListener {
        private final Function<String, ScrubField> scrubDefinition;
        private final Set<SkipInfo> globalSkipInfo;

        public SkipInfoCollector(Function<String, ScrubField> scrubDefinition, Set<SkipInfo> globalSkipInfo) {
            this.scrubDefinition = scrubDefinition;
            this.globalSkipInfo = globalSkipInfo;
        }

        @Override
        public boolean onChunkStart(ParserContext context, int chunkIndex, ChunkHeader header) {
            ScrubbingInfo info = new ScrubbingInfo();
            context.put(SCRUBBING_INFO_KEY, ScrubbingInfo.class, info);
            info.chunkOffset = header.offset;
            info.skipInfo = new TreeSet<>(Comparator.comparingLong(o -> o.startPos));
            info.targetClassMap = new HashMap<>();

            return ChunkParserListener.super.onChunkStart(context, chunkIndex, header);
        }

        @Override
        public boolean onMetadata(ParserContext context, MetadataEvent metadata) {
            ScrubbingInfo info = context.get(SCRUBBING_INFO_KEY, ScrubbingInfo.class);
            for (var md : metadata.getClasses()) {
                ScrubField scrubField = scrubDefinition.apply(md.getName());
                if (scrubField != null) {
                    info.targetClassMap.computeIfAbsent(md.getId(), id -> {
                        TypeSkipper skipper = TypeSkipper.createSkipper(md);
                        int scrubFieldIndex = -1;
                        int guardFieldIndex = -1;
                        for (int i = 0; i < md.getFields().size(); i++) {
                            MetadataField field = md.getFields().get(i);
                            if (field.getName().equals(scrubField.scrubFieldName)) {
                                scrubFieldIndex = i;
                            } else if (field.getName().equals(scrubField.guardFieldName)) {
                                guardFieldIndex = i;
                            }
                            if (scrubFieldIndex != -1 && guardFieldIndex != -1) {
                                break;
                            }
                        }
                        if (scrubFieldIndex != -1) {
                            return new TypeScrubbing(md.getId(), skipper, scrubFieldIndex, guardFieldIndex, scrubField.guard);
                        }
                        // should never happen, but just in case
                        return null;
                    });
                }
            }
            return ChunkParserListener.super.onMetadata(context, metadata);
        }

        @Override
        public boolean onEvent(ParserContext context, long typeId, long eventStartPos, long rawSize, long payloadSize) {
            ScrubbingInfo info = context.get(SCRUBBING_INFO_KEY, ScrubbingInfo.class);
            if (info == null) {
                throw new IllegalStateException("invalid parser state, no scrubbing info found");
            }

            var targetScrub = info.targetClassMap.get(typeId);
            if (targetScrub != null) {
                RecordingStream stream = context.get(RecordingStream.class);
                assert stream != null;
                long chunkOffset = info.chunkOffset;
                try {
                    SkipInfo[] skipInfo = new SkipInfo[1];
                    String[] skipValue = new String[1];
                    String[] guardValue = new String[1];
                    targetScrub.skipper.skip(stream, (idx, from, to) -> {
                        if (targetScrub.scrubFieldIndex == idx) {
                            skipInfo[0] = new SkipInfo(chunkOffset + from, chunkOffset + to);
                            if (targetScrub.scrubGuardIndex != -1) {
                                long currentPos = stream.position();
                                stream.position(from);
                                try {
                                    skipValue[0] = ParsingUtils.readUTF8(stream);
                                } catch (IOException e) {
                                    throw new RuntimeException("Failed to read scrub field value at " + from, e);
                                } finally {
                                    stream.position(currentPos);
                                }
                            }
                        }
                        if (targetScrub.scrubGuardIndex == idx) {
                            long currentPos = stream.position();
                            stream.position(from);
                            try {
                                guardValue[0] = ParsingUtils.readUTF8(stream);
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to read guard field value at " + from, e);
                            } finally {
                                stream.position(currentPos);
                            }
                        }
                    });
                    if (targetScrub.guard != null && guardValue[0] != null && skipValue[0] != null) {
                        if (!targetScrub.guard.apply(guardValue[0], skipValue[0])) {
                            skipInfo[0] = null; // do not scrub if guard condition is not met
                        }
                    }
                    if (skipInfo[0] != null) {
                        info.skipInfo.add(skipInfo[0]);
                    }
                } catch (IOException ex) {
                    return false;
                }
            }
            return ChunkParserListener.super.onEvent(context, typeId, eventStartPos, rawSize, payloadSize);
        }

        @Override
        public boolean onChunkEnd(ParserContext context, int chunkIndex, boolean skipped) {
            var info = context.get(SCRUBBING_INFO_KEY, ScrubbingInfo.class);
            globalSkipInfo.addAll(info.skipInfo);
            return true;
        }
    }
}
