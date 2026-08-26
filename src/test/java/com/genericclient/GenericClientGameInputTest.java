package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CompletableFuture;
import net.runelite.api.coords.WorldPoint;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientGameInputTest
{
	@Rule
	public TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void derivesCardinalCameraYawTargets()
	{
		WorldPoint origin = new WorldPoint(3200, 3200, 0);
		assertEquals(0, GenericClientGameInput.yawToward(origin, new WorldPoint(3200, 3210, 0)));
		assertEquals(512, GenericClientGameInput.yawToward(origin, new WorldPoint(3210, 3200, 0)));
		assertEquals(1024, GenericClientGameInput.yawToward(origin, new WorldPoint(3200, 3190, 0)));
		assertEquals(1536, GenericClientGameInput.yawToward(origin, new WorldPoint(3190, 3200, 0)));
	}

	@Test
	public void rollsBehaviorAfterEveryDispatchedCompositeClick() throws Exception
	{
		BehaviorFixture fixture = behaviorFixture();
		try
		{
			CompletableFuture<GenericClientInteractionResult> pending =
				GenericClientGameInput.performWithBehavior(
				fixture.behavior,
				true,
				() -> CompletableFuture.completedFuture(new GenericClientGameInput.RawWalkResult(
					new WorldPoint(3205, 3205, 0),
					"WALK_TILE_CLICK_EXECUTED test",
					true)));
			fixture.timer.runPending();
			GenericClientInteractionResult result = pending.get();

			assertEquals("ready", result.getBehaviorBefore().get("status"));
			assertEquals("micro", result.getBehaviorAfter().get("kind"));
			assertEquals("completed", result.getBehaviorAfter().get("status"));
		}
		finally
		{
			fixture.behavior.close();
		}
	}

	@Test
	public void skipsThePostActionRollWhenNoClickWasDispatched() throws Exception
	{
		BehaviorFixture fixture = behaviorFixture();
		try
		{
			GenericClientInteractionResult result = GenericClientGameInput.performWithBehavior(
				fixture.behavior,
				true,
				() -> CompletableFuture.completedFuture(new GenericClientGameInput.RawWalkResult(
					null,
					"WALK_TILE_CLICK_FAILED test",
					false)))
				.get();

			assertTrue(result.getBehaviorAfter().isEmpty());
		}
		finally
		{
			fixture.behavior.close();
		}
	}

	@Test
	public void breaksFalseBypassesBothSidesOfTheCompositeClick() throws Exception
	{
		BehaviorFixture fixture = behaviorFixture();
		try
		{
			GenericClientInteractionResult result = GenericClientGameInput.performWithBehavior(
				fixture.behavior,
				false,
				() -> CompletableFuture.completedFuture(new GenericClientGameInput.RawWalkResult(
					new WorldPoint(3205, 3205, 0),
					"WALK_TILE_CLICK_EXECUTED test",
					true)))
				.get();

			assertEquals("bypassed", result.getBehaviorBefore().get("status"));
			assertEquals("bypassed", result.getBehaviorAfter().get("status"));
		}
		finally
		{
			fixture.behavior.close();
		}
	}

	private BehaviorFixture behaviorFixture() throws Exception
	{
		PendingTimer timer = new PendingTimer();
		GenericClientBehaviorController behavior = new GenericClientBehaviorController(
			new GenericClientBehaviorStore(temporaryFolder.newFolder().toPath()),
			new GenericClientBehaviorController.BreakEffects()
			{
				@Override
				public CompletableFuture<String> moveOffscreen(GenericClientBehaviorProfile.Edge edge)
				{
					return CompletableFuture.completedFuture("offscreen");
				}

				@Override
				public CompletableFuture<String> logout()
				{
					return CompletableFuture.completedFuture("logout");
				}

				@Override
				public CompletableFuture<String> ensureLoggedIn()
				{
					return CompletableFuture.completedFuture("login");
				}
			},
			timer,
			GenericClientBehaviorController.systemClock(),
			new GenericClientBehaviorController.RandomSource()
			{
				@Override
				public double nextDouble()
				{
					return 0.0;
				}

				@Override
				public double nextGaussian()
				{
					return 0.0;
				}
			},
			message -> { });
		behavior.activateAccount(123L);
		behavior.setLoggedIn(true);
		return new BehaviorFixture(behavior, timer);
	}

	private static final class BehaviorFixture
	{
		private final GenericClientBehaviorController behavior;
		private final PendingTimer timer;

		private BehaviorFixture(GenericClientBehaviorController behavior, PendingTimer timer)
		{
			this.behavior = behavior;
			this.timer = timer;
		}
	}

	private static final class PendingTimer implements GenericClientBehaviorController.Timer
	{
		private Runnable pending;

		@Override
		public GenericClientBehaviorController.Cancellable schedule(Runnable task, long delayMillis)
		{
			pending = task;
			return () -> pending = null;
		}

		private void runPending()
		{
			Runnable task = pending;
			pending = null;
			if (task == null)
			{
				throw new AssertionError("No behavior timer was scheduled");
			}
			task.run();
		}
	}
}
