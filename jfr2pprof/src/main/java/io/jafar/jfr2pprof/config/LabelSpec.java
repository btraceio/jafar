package io.jafar.jfr2pprof.config;

public final class LabelSpec {
  private final String jfrPath;
  private final String pprofKey;

  public LabelSpec(String jfrPath, String pprofKey) {
    this.jfrPath = jfrPath;
    this.pprofKey = pprofKey;
  }

  public String jfrPath() {
    return jfrPath;
  }

  public String pprofKey() {
    return pprofKey;
  }

  public String[] jfrSegments() {
    return jfrPath.split("\\.");
  }
}
