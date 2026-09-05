package com.genericclient;

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.runelite.api.Client;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.client.callback.ClientThread;

final class GenericClientNativeInputs implements AutoCloseable
{
	final GenericClientCameraOwner cameraOwner;
	final GenericClientGameInput gameInput;
	final GenericClientMenuInput menuInput;
	final GenericClientRunInput runInput;
	final GenericClientNpcInput npcInput;
	final GenericClientInventoryInput inventoryInput;
	final GenericClientSpellInput spellInput;
	final GenericClientAutocastInput autocastInput;
	final GenericClientPrayerInput prayerInput;
	final GenericClientUiInput uiInput;
	final GenericClientObjectInput objectInput;
	final GenericClientEquipmentInput equipmentInput;
	final GenericClientGroundItemInput groundItemInput;
	final GenericClientDialogueInput dialogueInput;
	final GenericClientBankInput bankInput;
	final GenericClientGrandExchangeInput grandExchangeInput;
	final GenericClientCombatInput combatInput;
	final GenericClientWorldInput worldInput;
	final GenericClientPoisonInput poisonInput;

	GenericClientNativeInputs(
		Client client,
		ClientThread clientThread,
		ScheduledExecutorService executor,
		GenericClientSyntheticMouse syntheticMouse,
		GenericClientSyntheticKeyboard syntheticKeyboard,
		GenericClientBehaviorController behaviorController,
		Supplier<GenericClientSnapshot> snapshot,
		Consumer<String> reporter)
	{
		cameraOwner = new GenericClientCameraOwner(client);
		gameInput = new GenericClientGameInput(
			client,
			clientThread,
			executor,
			syntheticMouse,
			reporter);
		menuInput = new GenericClientMenuInput(
			client,
			clientThread,
			executor,
			syntheticMouse,
			reporter);
		runInput = new GenericClientRunInput(client, clientThread, executor, menuInput);
		npcInput = new GenericClientNpcInput(
			client, clientThread, executor, menuInput, cameraOwner, reporter);
		inventoryInput = new GenericClientInventoryInput(
			client, clientThread, executor, menuInput);
		spellInput = new GenericClientSpellInput(
			client, clientThread, executor, menuInput, npcInput, inventoryInput);
		autocastInput = new GenericClientAutocastInput(
			client, clientThread, executor, menuInput, reporter);
		prayerInput = new GenericClientPrayerInput(
			client, clientThread, executor, menuInput, reporter);
		uiInput = new GenericClientUiInput(
			client, clientThread, menuInput, syntheticKeyboard);
		objectInput = new GenericClientObjectInput(client, clientThread, executor, menuInput, cameraOwner);
		equipmentInput = new GenericClientEquipmentInput(client, clientThread, executor, menuInput);
		groundItemInput = new GenericClientGroundItemInput(
			client, clientThread, executor, menuInput, cameraOwner);
		dialogueInput = new GenericClientDialogueInput(
			client,
			clientThread,
			menuInput,
			syntheticKeyboard,
			behaviorController,
			syntheticMouse::getPosition,
			reporter);
		bankInput = new GenericClientBankInput(
			client,
			clientThread,
			executor,
			menuInput,
			syntheticKeyboard,
			reporter);
		grandExchangeInput = new GenericClientGrandExchangeInput(
			client,
			clientThread,
			executor,
			menuInput,
			syntheticKeyboard,
			snapshot,
			reporter);
		worldInput = new GenericClientWorldInput(client, clientThread);
		poisonInput = new GenericClientPoisonInput(
			client,
			clientThread,
			executor,
			inventoryInput);
		combatInput = new GenericClientCombatInput(
			client,
			clientThread,
			executor,
			syntheticMouse,
			reporter);
	}

	void onMenuOptionClicked(MenuOptionClicked event)
	{
		gameInput.onMenuOptionClicked(event);
		menuInput.onMenuOptionClicked(event);
	}

	boolean isActive()
	{
		return gameInput.isRunning() || menuInput.isRunning() || autocastInput.isRunning() ||
			combatInput.isRunning() || bankInput.isRunning() || grandExchangeInput.isRunning();
	}

	void cancel(String reason)
	{
		cameraOwner.cancel();
		pause(reason);
		bankInput.cancel(reason);
		grandExchangeInput.cancel(reason);
	}

	void pause(String reason)
	{
		gameInput.cancel(reason);
		autocastInput.cancel(reason);
		prayerInput.cancelPending(reason);
		menuInput.cancel(reason);
		combatInput.cancel(reason);
	}

	@Override
	public void close()
	{
		cameraOwner.cancel();
		gameInput.close();
		bankInput.close();
		grandExchangeInput.close();
		menuInput.close();
		combatInput.close();
	}
}
