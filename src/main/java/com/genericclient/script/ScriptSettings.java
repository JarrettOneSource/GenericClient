package com.genericclient.script;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Optional catalog metadata beyond DreamBot's script manifest. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ScriptSettings
{
	String id();
	Input[] inputs() default {};
	Button[] actions() default {};
	int[] randomEvents() default {};

	@Retention(RetentionPolicy.RUNTIME)
	@interface Input
	{
		String id();
		String label();
		String[] choices();
		String[] labels() default {};
		String defaultValue();
	}

	@Retention(RetentionPolicy.RUNTIME)
	@interface Button
	{
		String id();
		String label();
	}
}
