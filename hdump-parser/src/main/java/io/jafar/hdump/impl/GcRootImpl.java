package io.jafar.hdump.impl;

import io.jafar.hdump.api.GcRoot;
import io.jafar.hdump.api.HeapObject;

/** Implementation of GcRoot. */
final class GcRootImpl implements GcRoot {

  private final Type type;
  private final long objectId;
  private final int threadSerial;
  private final int frameNumber;
  private final HeapDumpImpl dump;

  GcRootImpl(Type type, long objectId, int threadSerial, int frameNumber, HeapDumpImpl dump) {
    this.type = type;
    this.objectId = objectId;
    this.threadSerial = threadSerial;
    this.frameNumber = frameNumber;
    this.dump = dump;
  }

  @Override
  public Type getType() {
    return type;
  }

  @Override
  public long getObjectId() {
    return objectId;
  }

  @Override
  public HeapObject getObject() {
    return dump.getObjectByIdInternal(objectId);
  }

  @Override
  public int getThreadSerial() {
    return (type == Type.JAVA_FRAME
            || type == Type.JNI_LOCAL
            || type == Type.NATIVE_STACK
            || type == Type.THREAD_BLOCK)
        ? threadSerial
        : -1;
  }

  @Override
  public int getFrameNumber() {
    return (type == Type.JAVA_FRAME || type == Type.JNI_LOCAL) ? frameNumber : -1;
  }

  @Override
  public int getThreadObjSerial() {
    return type == Type.THREAD_OBJ ? threadSerial : -1;
  }

  @Override
  public String toString() {
    return "GcRoot[" + type + " -> 0x" + Long.toHexString(objectId) + "]";
  }
}
