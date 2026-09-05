package com.genericclient;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class GenericClientWalkDispatchTest
{
	@Test
	public void pauseAndResumeCannotGiveAnOldClickDecisionFreshInputAuthority() throws Exception
	{
		List<GenericClientActivityContext> dispatched = new ArrayList<>();
		GenericClientWalker.WalkInput input = new GenericClientWalker.WalkInput()
		{
			@Override public CompletableFuture<GenericClientInteractionResult> walkToFarthest(List<WorldPoint> candidates,
				GenericClientActivityContext context, double reach)
			{
				dispatched.add(context);
				return new CompletableFuture<>();
			}
			@Override public void cancelWalkToTile(GenericClientActivityContext context) { }
		};
		try (GenericClientWalker walker = GenericClientTestSupport.walker(input,
			new GenericClientWalkTestFixtures.FakeObstacleInput(), GenericClientCollisionMap.loadBundled(), message -> { }))
		{
			WorldPoint start = new WorldPoint(3202, 3428, 0);
			walker.publishGameTick(GenericClientWalkTestFixtures.snapshot(0, start));
			walker.walkTo(new GenericClientWalkRequest(new WorldPoint(3230, 3428, 0), 0, 100,
				GenericClientActivityContext.none(), false, List.of(), GenericClientWalkInterrupts.NONE, List.of(), null), new GenericClientWalker.ClickBoundary()
				{
					@Override public double nextReachFraction()
					{
						walker.pauseActiveInput("test_pause");
						walker.resumeActiveInput("test_resume");
						return 1;
					}
					@Override public CompletableFuture<GenericClientInteractionResult> execute(GenericClientActivityContext context,
						java.util.function.Supplier<CompletableFuture<GenericClientInteractionResult>> action) { return action.get(); }
				});
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (dispatched.isEmpty() && System.nanoTime() < deadline)
			{
				walker.publishGameTick(GenericClientWalkTestFixtures.snapshot(1, start));
				Thread.sleep(5);
			}
			assertEquals(1, dispatched.size());
			assertFalse(dispatched.get(0).isInputAllowed());
		}
	}
}
