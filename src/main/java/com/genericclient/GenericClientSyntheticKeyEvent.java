package com.genericclient;

import java.awt.Component;
import java.awt.event.KeyEvent;

final class GenericClientSyntheticKeyEvent extends KeyEvent
{
	GenericClientSyntheticKeyEvent(
		Component source,
		int id,
		long when,
		int modifiers,
		int keyCode,
		char keyChar)
	{
		super(source, id, when, modifiers, keyCode, keyChar);
	}
}
