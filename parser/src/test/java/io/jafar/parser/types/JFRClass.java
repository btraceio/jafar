package io.jafar.parser.types;

import io.jafar.parser.api.lazy.JfrField;
import io.jafar.parser.api.lazy.JfrType;
@JfrType("java.lang.Class")

public interface JFRClass {
	JFRClassLoader classLoader();
	String name();
	@JfrField("package")
    JFRPackage pkg();
	int modifiers();
	boolean hidden();
}
