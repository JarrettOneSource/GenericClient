package com.genericclient;

import java.awt.Component;
import java.awt.event.MouseEvent;

final class GenericClientSyntheticMouseEvent extends MouseEvent
{
	GenericClientSyntheticMouseEvent(
		Component source,
		int id,
		long when,
		int modifiers,
		int x,
		int y,
		int clickCount,
		boolean popupTrigger,
		int button)
	{
		super(source, id, when, modifiers, x, y, clickCount, popupTrigger, button);
	}
}
