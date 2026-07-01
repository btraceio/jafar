package io.jafar.jfr2pprof.proto;

public record Label(String key, String str, long num, String numUnit) {
  public boolean isStr() {
    return str != null;
  }
}
