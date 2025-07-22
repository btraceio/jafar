package io.jafar.parser.types;

import io.jafar.parser.api.lazy.JfrType;
@JfrType("jdk.types.StackFrame")

public interface JFRStackFrame {
	JFRMethod method();
	int lineNumber();
	int bytecodeIndex();
	String type();
}
