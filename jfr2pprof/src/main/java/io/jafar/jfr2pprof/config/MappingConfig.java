package io.jafar.jfr2pprof.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MappingConfig {
  private final FrameFormat frame;
  private final List<ProfileSpec> profiles;

  public MappingConfig(FrameFormat frame, List<ProfileSpec> profiles) {
    this.frame = frame;
    this.profiles = profiles;
  }

  public FrameFormat frame() {
    return frame;
  }

  public List<ProfileSpec> profiles() {
    return profiles;
  }

  public List<ValueTypePair> allValueTypes() {
    List<ValueTypePair> result = new ArrayList<>();
    for (ProfileSpec p : profiles) {
      for (ValueSpec v : p.values()) {
        result.add(new ValueTypePair(v.name(), v.unit()));
      }
    }
    return Collections.unmodifiableList(result);
  }
}
