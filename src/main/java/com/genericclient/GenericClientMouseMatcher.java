package com.genericclient;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

final class GenericClientMouseMatcher
{
	static final int SAMPLE_COUNT = 32;
	static final int VECTOR_SIZE = SAMPLE_COUNT * 2;

	private static final int OUTPUT_POINT_COUNT = 128;
	private static final int RANDOM_TOP_K = 12;
	private static final int SMOOTHING_WINDOW = 3;
	private static final double APPROACH_PENALTY = 0.6;
	private static final double MAX_BACKTRACK_NORM = 0.12;
	private static final double MIN_NORMALIZED_TIME_STEP = 0.000001;

	private GenericClientMouseMatcher()
	{
	}

	static List<PathPoint> generate(
		GenericClientMouseProfile profile,
		Point start,
		Point target,
		Rectangle viewport,
		int durationMillis,
		Random random)
	{
		double distance = start.distance(target);
		double angle = Math.atan2(target.y - start.y, target.x - start.x);
		GenericClientMouseProfile.Template selected = select(
			profile.getTemplates(),
			start,
			target,
			viewport,
			distance,
			angle,
			durationMillis,
			random);
		return toScreenPath(postprocess(selected.path), selected.timeNorm,
			start, target, durationMillis);
	}

	private static GenericClientMouseProfile.Template select(
		List<GenericClientMouseProfile.Template> templates,
		Point start,
		Point target,
		Rectangle viewport,
		double distance,
		double angle,
		int durationMillis,
		Random random)
	{
		double startNormX = normalizedX(start, viewport);
		double startNormY = normalizedY(start, viewport);
		double targetNormX = normalizedX(target, viewport);
		double targetNormY = normalizedY(target, viewport);
		GenericClientMouseProfile.Template[] candidates = top(
			templates,
			distance,
			angle,
			durationMillis,
			startNormX,
			startNormY,
			targetNormX,
			targetNormY);

		List<GenericClientMouseProfile.Template> usable = new ArrayList<>(candidates.length);
		for (GenericClientMouseProfile.Template candidate : candidates)
		{
			if (candidate != null && staysInViewport(candidate, start, target, viewport, durationMillis))
			{
				usable.add(candidate);
			}
		}
		if (!usable.isEmpty())
		{
			return usable.get(random.nextInt(usable.size()));
		}

		int candidateCount = 0;
		while (candidateCount < candidates.length && candidates[candidateCount] != null)
		{
			candidateCount++;
		}
		return candidates[random.nextInt(candidateCount)];
	}

	private static GenericClientMouseProfile.Template[] top(
		List<GenericClientMouseProfile.Template> templates,
		double distance,
		double angle,
		double durationMillis,
		double startNormX,
		double startNormY,
		double targetNormX,
		double targetNormY)
	{
		int limit = Math.min(RANDOM_TOP_K, templates.size());
		GenericClientMouseProfile.Template[] nearest = new GenericClientMouseProfile.Template[limit];
		double[] scores = new double[limit];
		Arrays.fill(scores, Double.POSITIVE_INFINITY);

		for (GenericClientMouseProfile.Template template : templates)
		{
			double score = score(template, distance, angle, durationMillis,
				startNormX, startNormY, targetNormX, targetNormY);
			for (int index = 0; index < limit; index++)
			{
				if (score >= scores[index])
				{
					continue;
				}
				for (int shift = limit - 1; shift > index; shift--)
				{
					scores[shift] = scores[shift - 1];
					nearest[shift] = nearest[shift - 1];
				}
				scores[index] = score;
				nearest[index] = template;
				break;
			}
		}
		return nearest;
	}

	private static double score(
		GenericClientMouseProfile.Template template,
		double distance,
		double angle,
		double durationMillis,
		double startNormX,
		double startNormY,
		double targetNormX,
		double targetNormY)
	{
		double distanceScore = Math.abs(Math.log1p(template.distancePx) - Math.log1p(Math.max(0.0, distance)));
		double durationScore = Math.abs(Math.log1p(template.durationMillis) -
			Math.log1p(Math.max(0.0, durationMillis)));
		return 2.0 * distanceScore
			+ 1.5 * angleDistance(template.angleRadians, angle)
			+ 0.5 * zoneDistance(template.startNormX, template.startNormY, startNormX, startNormY)
			+ 0.5 * zoneDistance(template.targetNormX, template.targetNormY, targetNormX, targetNormY)
			+ durationScore
			+ qualityPenalty(template)
			+ (template.approach ? 0.0 : APPROACH_PENALTY);
	}

	private static double qualityPenalty(GenericClientMouseProfile.Template template)
	{
		double maxLateral = 0.0;
		double backtrack = 0.0;
		double maxStep = 0.0;
		for (int index = 0; index < SAMPLE_COUNT; index++)
		{
			maxLateral = Math.max(maxLateral, Math.abs(template.path[index * 2 + 1]));
			if (index == 0)
			{
				continue;
			}
			double du = template.path[index * 2] - template.path[(index - 1) * 2];
			double dv = template.path[index * 2 + 1] - template.path[(index - 1) * 2 + 1];
			maxStep = Math.max(maxStep, Math.hypot(du, dv));
			if (du < 0.0)
			{
				backtrack -= du;
			}
		}
		return Math.max(0.0, maxLateral - 0.35) * 0.7
			+ Math.max(0.0, backtrack - 0.08) * 1.2
			+ Math.max(0.0, maxStep - 0.20) * 0.8;
	}

	private static boolean staysInViewport(
		GenericClientMouseProfile.Template template,
		Point start,
		Point target,
		Rectangle viewport,
		int durationMillis)
	{
		if (viewport.width <= 0 || viewport.height <= 0)
		{
			return true;
		}
		for (PathPoint point : toScreenPath(postprocess(template.path), template.timeNorm,
			start, target, durationMillis))
		{
			if (point.x < viewport.x || point.y < viewport.y ||
				point.x > viewport.x + viewport.width || point.y > viewport.y + viewport.height)
			{
				return false;
			}
		}
		return true;
	}

	private static double[] postprocess(double[] path)
	{
		double[] cleaned = smooth(path);
		anchorEndpoints(cleaned);
		limitBacktrack(cleaned);
		anchorEndpoints(cleaned);
		return cleaned;
	}

	private static double[] smooth(double[] path)
	{
		int half = SMOOTHING_WINDOW / 2;
		double[] result = new double[path.length];
		for (int index = 0; index < SAMPLE_COUNT; index++)
		{
			int left = Math.max(0, index - half);
			int right = Math.min(SAMPLE_COUNT - 1, index + half);
			double u = 0.0;
			double v = 0.0;
			for (int sample = left; sample <= right; sample++)
			{
				u += path[sample * 2];
				v += path[sample * 2 + 1];
			}
			int count = right - left + 1;
			result[index * 2] = u / count;
			result[index * 2 + 1] = v / count;
		}
		return result;
	}

	private static void limitBacktrack(double[] path)
	{
		double totalForward = 0.0;
		double totalBacktrack = 0.0;
		double[] deltas = new double[SAMPLE_COUNT - 1];
		for (int index = 1; index < SAMPLE_COUNT; index++)
		{
			double delta = path[index * 2] - path[(index - 1) * 2];
			deltas[index - 1] = delta;
			if (delta < 0.0)
			{
				totalBacktrack -= delta;
			}
			else
			{
				totalForward += delta;
			}
		}
		if (totalBacktrack <= MAX_BACKTRACK_NORM || totalBacktrack == 0.0 || totalForward == 0.0)
		{
			return;
		}

		double negativeScale = MAX_BACKTRACK_NORM / totalBacktrack;
		double positiveScale = (1.0 + MAX_BACKTRACK_NORM) / totalForward;
		double u = 0.0;
		path[0] = 0.0;
		for (int index = 1; index < SAMPLE_COUNT; index++)
		{
			double delta = deltas[index - 1];
			u += delta < 0.0 ? delta * negativeScale : delta * positiveScale;
			path[index * 2] = u;
		}
	}

	private static void anchorEndpoints(double[] path)
	{
		path[0] = 0.0;
		path[1] = 0.0;
		path[path.length - 2] = 1.0;
		path[path.length - 1] = 0.0;
	}

	private static List<PathPoint> toScreenPath(
		double[] path,
		double[] timeNorm,
		Point start,
		Point target,
		int durationMillis)
	{
		double dx = target.x - start.x;
		double dy = target.y - start.y;
		double distance = Math.max(1.0, Math.hypot(dx, dy));
		double ux = dx / distance;
		double uy = dy / distance;
		double vx = -uy;
		double vy = ux;
		double[] outputTime = outputTimeNorm(timeNorm);
		List<PathPoint> points = new ArrayList<>(OUTPUT_POINT_COUNT);
		for (int index = 0; index < OUTPUT_POINT_COUNT; index++)
		{
			double progress = index / (double) (OUTPUT_POINT_COUNT - 1);
			double u = sample(path, progress, 0);
			double v = sample(path, progress, 1);
			points.add(new PathPoint(
				durationMillis * outputTime[index],
				start.x + (u * ux + v * vx) * distance,
				start.y + (u * uy + v * vy) * distance));
		}
		points.set(0, new PathPoint(0.0, start.x, start.y));
		points.set(points.size() - 1, new PathPoint(durationMillis, target.x, target.y));
		return points;
	}

	private static double sample(double[] path, double progress, int component)
	{
		double scaled = progress * (SAMPLE_COUNT - 1);
		int index = (int) Math.floor(scaled);
		double fraction = scaled - index;
		double p0 = component(path, index - 1, component);
		double p1 = component(path, index, component);
		double p2 = component(path, index + 1, component);
		double p3 = component(path, index + 2, component);
		double fractionSquared = fraction * fraction;
		double fractionCubed = fractionSquared * fraction;
		return 0.5 * (2.0 * p1
			+ (-p0 + p2) * fraction
			+ (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * fractionSquared
			+ (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * fractionCubed);
	}

	private static double component(double[] path, int index, int component)
	{
		int bounded = Math.max(0, Math.min(SAMPLE_COUNT - 1, index));
		return path[bounded * 2 + component];
	}

	private static double[] outputTimeNorm(double[] timeNorm)
	{
		double[] output = new double[OUTPUT_POINT_COUNT];
		for (int index = 0; index < output.length; index++)
		{
			double progress = index / (double) (output.length - 1);
			output[index] = interpolate(timeNorm, progress);
		}
		output[0] = 0.0;
		output[output.length - 1] = 1.0;
		enforceStrictIncreasing(output);
		return output;
	}

	private static double interpolate(double[] values, double progress)
	{
		double scaled = progress * (values.length - 1);
		int index = (int) Math.floor(scaled);
		if (index >= values.length - 1)
		{
			return values[values.length - 1];
		}
		double fraction = scaled - index;
		return values[index] * (1.0 - fraction) + values[index + 1] * fraction;
	}

	static double[] validatedTimeNorm(double[] values, int templateIndex) throws IOException
	{
		if (values == null || values.length == 0)
		{
			return minimumJerkTimeNorm(SAMPLE_COUNT);
		}
		if (values.length != SAMPLE_COUNT)
		{
			throw new IOException("Mouse template " + templateIndex + " has invalid time_norm length");
		}
		double[] result = values.clone();
		double previous = -1.0;
		for (double value : result)
		{
			if (!Double.isFinite(value) || value < 0.0 || value > 1.0 || value < previous)
			{
				throw new IOException("Mouse template " + templateIndex + " has invalid time_norm values");
			}
			previous = value;
		}
		result[0] = 0.0;
		result[result.length - 1] = 1.0;
		enforceStrictIncreasing(result);
		return result;
	}

	private static double[] minimumJerkTimeNorm(int count)
	{
		double[] result = new double[count];
		for (int index = 0; index < count; index++)
		{
			result[index] = inverseMinimumJerk(index / (double) (count - 1));
		}
		result[0] = 0.0;
		result[result.length - 1] = 1.0;
		enforceStrictIncreasing(result);
		return result;
	}

	private static void enforceStrictIncreasing(double[] values)
	{
		for (int index = 1; index < values.length - 1; index++)
		{
			double minimum = values[index - 1] + MIN_NORMALIZED_TIME_STEP;
			double maximum = 1.0 - MIN_NORMALIZED_TIME_STEP * (values.length - 1 - index);
			values[index] = Math.max(minimum, Math.min(maximum, values[index]));
		}
	}

	private static double inverseMinimumJerk(double progress)
	{
		double low = 0.0;
		double high = 1.0;
		for (int iteration = 0; iteration < 24; iteration++)
		{
			double midpoint = (low + high) * 0.5;
			double jerk = 10.0 * Math.pow(midpoint, 3)
				- 15.0 * Math.pow(midpoint, 4)
				+ 6.0 * Math.pow(midpoint, 5);
			if (jerk < progress)
			{
				low = midpoint;
			}
			else
			{
				high = midpoint;
			}
		}
		return (low + high) * 0.5;
	}

	private static double angleDistance(double first, double second)
	{
		double difference = Math.abs(first - second) % (Math.PI * 2.0);
		return difference > Math.PI ? Math.PI * 2.0 - difference : difference;
	}

	private static double zoneDistance(double ax, double ay, double bx, double by)
	{
		double dx = zoneIndex(ax) - zoneIndex(bx);
		double dy = zoneIndex(ay) - zoneIndex(by);
		return Math.hypot(dx, dy) / Math.sqrt(8.0);
	}

	private static int zoneIndex(double normalized)
	{
		return Math.max(0, Math.min(2, (int) Math.floor(clamp01(normalized) * 3.0)));
	}

	private static double normalizedX(Point point, Rectangle viewport)
	{
		return clamp01((point.x - viewport.x) / (double) viewport.width);
	}

	private static double normalizedY(Point point, Rectangle viewport)
	{
		return clamp01((point.y - viewport.y) / (double) viewport.height);
	}

	private static double clamp01(double value)
	{
		return Math.max(0.0, Math.min(1.0, value));
	}

	static final class PathPoint
	{
		final double timeMillis;
		final double x;
		final double y;

		private PathPoint(double timeMillis, double x, double y)
		{
			this.timeMillis = timeMillis;
			this.x = x;
			this.y = y;
		}
	}
}
