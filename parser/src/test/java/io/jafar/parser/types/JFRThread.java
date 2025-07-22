package io.jafar.parser.types;

import io.jafar.parser.api.lazy.JfrType;
@JfrType("java.lang.Thread")

public interface JFRThread {
	String osName();
	long osThreadId();
	String javaName();
	long javaThreadId();
	JFRThreadGroup group();
	boolean virtual();
}
