package com.genericclient;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

final class GenericClientTestSupport
{
	private GenericClientTestSupport()
	{
	}

	static GenericClientBehaviorController behavior(Path directory) throws Exception
	{
		return new GenericClientBehaviorController(
			new GenericClientBehaviorStore(directory),
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
			(task, delayMillis) -> () -> { },
			GenericClientBehaviorController.systemClock(),
			new GenericClientBehaviorController.RandomSource()
			{
				@Override
				public double nextDouble()
				{
					return 0.999;
				}

				@Override
				public double nextGaussian()
				{
					return 0.0;
				}
			},
			message -> { });
	}
}
