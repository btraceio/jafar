package io.jafar.parser.internal_api;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;

import java.io.IOException;

public final class TypeSkipper {
    public static TypeSkipper createSkipper(MetadataClass clz) {
        IntList instructions = new IntArrayList(20);
        for (MetadataField fld : clz.getFields()) {
            fillSkipper(fld, instructions);
        }
        return new TypeSkipper(instructions.toIntArray());
    }

    private static void fillSkipper(MetadataField fld, IntList instructions) {
        int startingSize = instructions.size();
        int arraySizeIdx = -1;
        MetadataClass fldClz = fld.getType();
        if (fld.getDimension() > 0) {
            instructions.add(Instructions.ARRAY);
            arraySizeIdx = instructions.size();
            instructions.add(0); // reserve slot for the array size
        }
        boolean withCp = fld.hasConstantPool();
        while (fldClz.isSimpleType()) {
            fldClz = fldClz.getFields().getFirst().getType();
        }
        switch (fldClz.getName()) {
            case "byte", "boolean" ->
                    instructions.add(Instructions.BYTE);
            case "char", "short", "int", "long" ->
                    instructions.add(Instructions.VARINT);
            case "float" ->
                    instructions.add(Instructions.FLOAT);
            case "double" ->
                    instructions.add(Instructions.DOUBLE);
            case "java.lang.String" -> {
                if (withCp) {
                    instructions.add(Instructions.CP_ENTRY);
                } else {
                    instructions.add(Instructions.STRING);
                }
            }
            default -> {
                if (withCp) {
                    instructions.add(Instructions.CP_ENTRY);
                } else {
                    for (MetadataField subField : fldClz.getFields()) {
                        fillSkipper(subField, instructions);
                    }
                }
            }
        }
        if (fld.getDimension() > 0) {
            instructions.set(arraySizeIdx, instructions.size() - startingSize - 2);
        }
    }

    private static final class Instructions {
        public static final int ARRAY = 1;
        public static final int BYTE = 2;
        public static final int FLOAT = 3;
        public static final int DOUBLE = 4;
        public static final int STRING = 5;
        public static final int VARINT = 6;
        public static final int CP_ENTRY = 7;
    }

    public interface Listener {
        Listener NOOP = (idx, from, to) -> {};
        void onSkip(int idx, long from, long to);
    }

    private final int[] instructions;

    public TypeSkipper(int[] instructions) {
        this.instructions = instructions;
    }

    public void skip(RecordingStream stream) throws IOException {
        skip(stream, Listener.NOOP);
    }

    public void skip(RecordingStream stream, Listener listener) throws IOException {
        for (int i = 0; i < instructions.length; i++) {
            long from = stream.position();
            int instruction = instructions[i];
            if (instruction == Instructions.ARRAY) {
                int endIndex = (++i) + instructions[i++]; // the next instruction for array is encoding the number of instructions per array item
                int cnt = (int)stream.readVarint();
                if (cnt == 0) {
                    i = endIndex;
                    continue;
                }
                int savedIndex = i;
                instruction = instructions[i];
                for (int j = 0; j < cnt; ) {
                    skip(instruction, stream);
                    if (endIndex == i++) {
                        i = savedIndex;
                        j++;
                    }
                }
                listener.onSkip(i, from, stream.position());

                i = endIndex;
                continue;
            }
            skip(instruction, stream);
            listener.onSkip(i, from, stream.position());
        }
    }

    private static void skip(int instruction, RecordingStream stream) throws IOException {
        switch (instruction) {
            case Instructions.VARINT:
            case Instructions.CP_ENTRY: stream.readVarint(); break;
            case Instructions.BYTE: stream.skip(1); break;
            case Instructions.FLOAT: stream.skip(4); break;
            case Instructions.DOUBLE: stream.skip(8); break;
            case Instructions.STRING: ParsingUtils.skipUTF8(stream); break;
        }
    }
}
