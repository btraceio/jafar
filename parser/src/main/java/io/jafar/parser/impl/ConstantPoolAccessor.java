package io.jafar.parser.impl;

import io.jafar.parser.api.ComplexType;
import io.jafar.parser.internal_api.metadata.MetadataClass;
import java.util.Map;
import java.util.Objects;

final class ConstantPoolAccessor implements ComplexType {
  private final ConstantPools constantPools;
  private final long typeId;
  private final long pointer;

  ConstantPoolAccessor(ConstantPools constantPools, MetadataClass type, long pointer) {
    this.constantPools = constantPools;
    this.typeId = type.getId();
    this.pointer = pointer;
  }

  @Override
  public Map<String, Object> getValue() {
    return constantPools.getValue(typeId, pointer);
  }

  @Override
  public String toString() {
    return Objects.toString(getValue());
  }
}
