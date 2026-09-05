package org.dreambot.api.methods.grandexchange;

import org.dreambot.api.methods.widget.Widgets;

public final class GrandExchange
{
    private GrandExchange() {}
    public static boolean isOpen() { return Widgets.isVisible(465,0); }
}
