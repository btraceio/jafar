package io.jafar.parser.types;

import io.jafar.parser.api.lazy.JfrField;
import io.jafar.parser.api.lazy.JfrType;
@JfrType("jdk.ExecutionSample")

public interface JFRExecutionSample {
	long startTime();
	JFRThread sampledThread();
	JFRStackTrace stackTrace();
	String state();

	@JfrField(value = "stackTrace", raw = true)
	long stackTraceId();
}
