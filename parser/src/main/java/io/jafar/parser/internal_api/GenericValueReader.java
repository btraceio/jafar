package io.jafar.parser.internal_api;

import io.jafar.parser.ParsingUtils;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import io.jafar.parser.internal_api.metadata.MetadataField;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class GenericValueReader {
    private final ValueProcessor processor;

    public GenericValueReader(ValueProcessor processor) {
        this.processor = processor;
    }

    public Map<String, Object> readEvent(RecordingStream stream, MetadataClass type) throws IOException {
        if (type.getSuperType().equals("jdk.jfr.Event")) {
            Map<String, Object> event = new HashMap<>();
            stream.getContext().put("event", Map.class, event);
            processor.onComplexValueStart(null, "event", type);
            readValue(stream, type);
            processor.onComplexValueEnd(null, "event", type);
            stream.getContext().put("event", Map.class, null);
            return event;
        }
        return null;
    }

    public void readValue(RecordingStream stream, MetadataClass type) throws IOException {
        if (type.isPrimitive()) {
            readSingleValue(stream, type, "");
        }
        for (MetadataField fld : type.getFields()) {
            if (fld.getDimension() == 1) {
                int len = (int) stream.readVarint();
                try {
                    processor.onArrayStart(type, fld.getName(), fld.getType(), len);
                    for (int i = 0; i < len; i++) {
                        readSingleFieldValue(stream, type, fld);
                    }
                } finally {
                    processor.onArrayEnd(type, fld.getName(), fld.getType());
                }
            } else {
                readSingleFieldValue(stream, type, fld);
            }
        }
    }

    private void readSingleFieldValue(RecordingStream stream, MetadataClass type, MetadataField fld) throws IOException {
        if (fld.hasConstantPool()) {
            long idx = stream.readVarint();
            processor.onConstantPoolIndex(type, fld.getName(), fld.getType(), idx);
        } else {
            if (fld.getType().isPrimitive()) {
                readSingleValue(stream, fld.getType(), fld.getName());
            } else {
                processor.onComplexValueStart(type, fld.getName(), fld.getType());
                readValue(stream, fld.getType());
                processor.onComplexValueEnd(type, fld.getName(), fld.getType());
            }
        }
    }

    private void readSingleValue(RecordingStream stream, MetadataClass type, String fldName) throws IOException {
        switch (type.getName()) {
            case "short":
                processor.onShortValue(type, fldName, (short) stream.readVarint());
                break;
            case "char":
                processor.onCharValue(type, fldName, (char) stream.readVarint());
                break;
            case "int":
                processor.onIntValue(type, fldName, (int) stream.readVarint());
                break;
            case "long":
                processor.onLongValue(type, fldName, stream.readVarint());
                break;
            case "byte":
                processor.onByteValue(type, fldName, stream.read());
                break;
            case "boolean":
                processor.onBooleanValue(type, fldName, stream.read() != 0);
                break;
            case "double":
                processor.onDoubleValue(type, fldName, stream.readDouble());
                break;
            case "float":
                processor.onFloatValue(type, fldName, stream.readFloat());
                break;
            case "java.lang.String":
                processor.onStringValue(type, fldName, ParsingUtils.readUTF8(stream));
                break;
            default:
                throw new IllegalStateException("Unknown primitive type: " + type);
        }
    }
}
