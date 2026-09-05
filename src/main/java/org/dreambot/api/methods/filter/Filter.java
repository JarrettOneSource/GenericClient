package org.dreambot.api.methods.filter;

@FunctionalInterface
public interface Filter<T>
{
	boolean match(T value);
	default Filter<T> and(Filter<? super T> other) { return value -> match(value) && other.match(value); }
	default Filter<T> or(Filter<? super T> other) { return value -> match(value) || other.match(value); }
	default Filter<T> negate() { return value -> !match(value); }
}
