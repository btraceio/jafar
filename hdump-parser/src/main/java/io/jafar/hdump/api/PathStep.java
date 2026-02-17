package io.jafar.hdump.api;

/**
 * A single step in the reference path from a GC root to a heap object. The {@code object} is the
 * heap object at this position, and {@code fieldName} is the name of the reference field from the
 * previous step ({@code null} for the GC root itself).
 */
public record PathStep(HeapObject object, String fieldName) {}
