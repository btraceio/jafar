package io.jafar.parser.impl;

import io.jafar.parser.internal_api.collections.LongObjectHashMap;
import java.util.Map;

final class ConstantPools {
  private final LongObjectHashMap<ConstantPool> cpools = new LongObjectHashMap<>();

  void add(long typeId, long id, Map<String, Object> value) {
    cpools.computeIfAbsent(typeId, k -> new ConstantPool()).add(id, value);
  }

  public ConstantPool getConstantPool(long typeId) {
    return cpools.get(typeId);
  }

  public Map<String, Object> getValue(long typeId, long id) {
    return cpools.getOrDefault(typeId, ConstantPool.EMPTY).get(id);
  }
}
