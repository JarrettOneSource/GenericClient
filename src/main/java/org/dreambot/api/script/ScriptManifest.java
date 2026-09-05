package org.dreambot.api.script;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ScriptManifest
{
	String name();
	String author();
	Category category();
	double version();
	String description() default "";
	String image() default "";
}
