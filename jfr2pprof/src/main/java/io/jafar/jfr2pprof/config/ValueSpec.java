package io.jafar.jfr2pprof.config;

public final class ValueSpec {
  private final String name;
  private final String unit;
  private final String field;
  private final double scale;

  public ValueSpec(String name, String unit, String field, double scale) {
    this.name = name;
    this.unit = unit;
    this.field = field;
    this.scale = scale;
  }

  public String name() {
    return name;
  }

  public String unit() {
    return unit;
  }

  public String field() {
    return field;
  }

  public double scale() {
    return scale;
  }

  public boolean isCount() {
    return "@count".equals(field);
  }

  public String[] fieldSegments() {
    if (isCount()) {
      throw new IllegalStateException("@count has no field segments");
    }
    return field.split("\\.");
  }
}
