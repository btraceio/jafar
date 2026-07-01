package io.jafar.jfr2pprof.config;

import java.util.List;

public final class ProfileSpec {
  private final String type;
  private final String event;
  private final String stackField;
  private final List<ValueSpec> values;
  private final List<LabelSpec> labels;
  private int sampleTypeIndex;

  public ProfileSpec(
      String type,
      String event,
      String stackField,
      List<ValueSpec> values,
      List<LabelSpec> labels) {
    this.type = type;
    this.event = event;
    this.stackField = stackField;
    this.values = values;
    this.labels = labels;
    this.sampleTypeIndex = 0;
  }

  public String type() {
    return type;
  }

  public String event() {
    return event;
  }

  public String stackField() {
    return stackField;
  }

  public List<ValueSpec> values() {
    return values;
  }

  public List<LabelSpec> labels() {
    return labels;
  }

  public int sampleTypeIndex() {
    return sampleTypeIndex;
  }

  void setSampleTypeIndex(int sampleTypeIndex) {
    this.sampleTypeIndex = sampleTypeIndex;
  }
}
