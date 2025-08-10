package io.jafar.parser.impl;

import java.util.ArrayDeque;
import java.util.Deque;

final class MultiTypeStack {
    private final Deque<Object> stack;

    MultiTypeStack(int capacity) {
        stack = new ArrayDeque<>(capacity);
    }

    void push(Object value) {
        stack.push(value);
    }

    <T> T pop(Class<T> clz) {
        Object v = stack.peek();
        if (v == null) {
            return null;
        }
        if (clz.isAssignableFrom(v.getClass())) {
            return clz.cast(stack.pop());
        }
        return null;
    }

    <T> T peek(Class<T> clz) {
        Object v = stack.peek();
        if (v == null) {
            return null;
        }
        if (clz.isAssignableFrom(v.getClass())) {
            return clz.cast(v);
        }
        return null;
    }

    Object peek() {
        return stack.peek();
    }
}
