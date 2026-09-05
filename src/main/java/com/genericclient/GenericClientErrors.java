package com.genericclient;

import com.google.common.base.Throwables;

/** Stable failure text shared by action receipts and runtime diagnostics. */
final class GenericClientErrors
{
	private GenericClientErrors() { }

	static String rootMessage(Throwable error)
	{
		Throwable cause = Throwables.getRootCause(error);
		String message = cause.getMessage();
		return message == null ? cause.getClass().getSimpleName() : message;
	}
}
