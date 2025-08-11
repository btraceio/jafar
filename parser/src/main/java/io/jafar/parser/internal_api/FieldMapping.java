package io.jafar.parser.internal_api;

public final class FieldMapping {
    private final String method;
    private final boolean raw;
    
    public FieldMapping(String method, boolean raw) {
        this.method = method;
        this.raw = raw;
    }
    
    public String method() {
        return method;
    }
    
    public boolean raw() {
        return raw;
    }
}
