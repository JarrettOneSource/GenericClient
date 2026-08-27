package com.genericclient;

import net.runelite.api.Client;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;

final class GenericClientBankCache
{
	private GenericClientAccountSnapshot.ContainerSnapshot contents;
	private long capturedGameTick = -1;

	GenericClientAccountSnapshot.BankSnapshot capture(Client client, long gameTick)
	{
		Widget bankItems = client.getWidget(InterfaceID.Bankmain.ITEMS);
		boolean open = bankItems != null && !bankItems.isHidden();
		if (open)
		{
			ItemContainer container = client.getItemContainer(InventoryID.BANK);
			if (container != null)
			{
				contents = GenericClientAccountSnapshot.captureContainer(client, InventoryID.BANK, false);
				capturedGameTick = gameTick;
			}
		}

		if (contents == null)
		{
			return open
				? new GenericClientAccountSnapshot.BankSnapshot(
					"open", -1, GenericClientAccountSnapshot.ContainerSnapshot.unavailable())
				: GenericClientAccountSnapshot.BankSnapshot.unknown();
		}
		return new GenericClientAccountSnapshot.BankSnapshot(
			open ? "open" : "cached",
			capturedGameTick,
			contents);
	}

	void clear()
	{
		contents = null;
		capturedGameTick = -1;
	}
}
