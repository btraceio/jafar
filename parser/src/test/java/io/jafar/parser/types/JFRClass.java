package io.jafar.parser.types;

import io.jafar.parser.api.JfrField;
import io.jafar.parser.api.JfrType;
@JfrType("java.lang.Class")

public interface JFRClass {
	JFRClassLoader classLoader();
	String name();
	@JfrField("package")
    JFRPackage pkg();
	int modifiers();
	boolean hidden();
}
