package io.jafar.parser.types;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
@JfrType("jdk.ExecutionSample")

public interface JFRExecutionSample {
	long startTime();
	JFRThread sampledThread();
	JFRStackTrace stackTrace();
	String state();

	@JfrField(value = "stackTrace", raw = true)
	long stackTraceId();
}
