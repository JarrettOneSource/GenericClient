package com.genericclient;

import java.awt.Canvas;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

final class GenericClientMouseRecorder implements AutoCloseable
{
	private static final double MAX_SEGMENT_GAP_MILLIS = 240.0;
	private static final double MAX_JUMP_PIXELS = 360.0;
	private static final double MIN_DISTANCE_PIXELS = 40.0;
	private static final double MAX_DISTANCE_PIXELS = 1800.0;
	private static final double MAX_LATERAL_NORM = 0.75;
	private static final double MAX_BACKTRACK_NORM = 0.25;
	private static final double MAX_STEP_NORM = 0.40;
	private static final double APPROACH_CLICK_WINDOW_MILLIS = 260.0;
	private static final double APPROACH_CLICK_RADIUS_PIXELS = 12.0;
	private static final int MIN_SEGMENT_POINTS = 4;
	private static final int SAMPLE_COUNT = GenericClientMouseMatcher.SAMPLE_COUNT;

	private final Canvas canvas;
	private final BooleanSupplier automatedMovement;
	private final List<Sample> segment = new ArrayList<>();
	private final List<GenericClientMouseProfile.Template> templates = new ArrayList<>();
	private final MouseAdapter listener = new MouseAdapter()
	{
		@Override
		public void mouseMoved(MouseEvent event)
		{
			captureMove(event);
		}

		@Override
		public void mouseDragged(MouseEvent event)
		{
			captureMove(event);
		}

		@Override
		public void mousePressed(MouseEvent event)
		{
			captureClick(event);
		}
	};

	private boolean recording;

	GenericClientMouseRecorder(Canvas canvas, BooleanSupplier automatedMovement)
	{
		this.canvas = canvas;
		this.automatedMovement = automatedMovement;
		canvas.addMouseMotionListener(listener);
		canvas.addMouseListener(listener);
	}

	synchronized void start()
	{
		if (recording)
		{
			throw new IllegalStateException("Mouse recording is already running");
		}
		segment.clear();
		templates.clear();
		recording = true;
	}

	synchronized GenericClientMouseProfile stop(String profileId)
	{
		if (!recording)
		{
			throw new IllegalStateException("Mouse recording is not running");
		}
		finishSegment(false);
		recording = false;
		return GenericClientMouseProfile.recorded(profileId, templates);
	}

	synchronized boolean isRecording()
	{
		return recording;
	}

	synchronized int getTemplateCount()
	{
		return templates.size();
	}

	private synchronized void captureMove(MouseEvent event)
	{
		if (!recording)
		{
			return;
		}
		if (automatedMovement.getAsBoolean())
		{
			segment.clear();
			return;
		}

		Sample next = sample(event);
		if (!segment.isEmpty())
		{
			Sample previous = segment.get(segment.size() - 1);
			double gap = next.timeMillis - previous.timeMillis;
			if (gap < 0.0 || gap > MAX_SEGMENT_GAP_MILLIS || distance(previous, next) > MAX_JUMP_PIXELS)
			{
				finishSegment(false);
			}
		}
		segment.add(next);
	}

	private synchronized void captureClick(MouseEvent event)
	{
		if (!recording || event.getButton() != MouseEvent.BUTTON1)
		{
			return;
		}
		if (automatedMovement.getAsBoolean())
		{
			segment.clear();
			return;
		}

		if (!segment.isEmpty())
		{
			Sample click = sample(event);
			Sample last = segment.get(segment.size() - 1);
			boolean approach = click.timeMillis - last.timeMillis <= APPROACH_CLICK_WINDOW_MILLIS &&
				distance(last, click) <= APPROACH_CLICK_RADIUS_PIXELS;
			finishSegment(approach);
		}
	}

	private void finishSegment(boolean approach)
	{
		GenericClientMouseProfile.Template template = buildTemplate(
			segment,
			Math.max(1, canvas.getWidth()),
			Math.max(1, canvas.getHeight()),
			approach);
		if (template != null)
		{
			templates.add(template);
		}
		segment.clear();
	}

	private static GenericClientMouseProfile.Template buildTemplate(
		List<Sample> samples,
		int viewportWidth,
		int viewportHeight,
		boolean approach)
	{
		if (samples.size() < MIN_SEGMENT_POINTS)
		{
			return null;
		}
		Sample first = samples.get(0);
		Sample last = samples.get(samples.size() - 1);
		double dx = last.x - first.x;
		double dy = last.y - first.y;
		double distance = Math.hypot(dx, dy);
		double duration = last.timeMillis - first.timeMillis;
		if (distance < MIN_DISTANCE_PIXELS || distance > MAX_DISTANCE_PIXELS || duration <= 0.0)
		{
			return null;
		}

		double ux = dx / distance;
		double uy = dy / distance;
		double vx = -uy;
		double vy = ux;
		double[] timeSampled = new double[SAMPLE_COUNT * 2];
		for (int index = 0; index < SAMPLE_COUNT; index++)
		{
			double wanted = first.timeMillis + duration * index / (SAMPLE_COUNT - 1);
			Sample point = interpolateTime(samples, wanted);
			double relativeX = point.x - first.x;
			double relativeY = point.y - first.y;
			timeSampled[index * 2] = (relativeX * ux + relativeY * uy) / distance;
			timeSampled[index * 2 + 1] = (relativeX * vx + relativeY * vy) / distance;
		}
		anchor(timeSampled);
		if (!usable(timeSampled))
		{
			return null;
		}

		double[] cumulativeArc = cumulativeArc(samples);
		double totalArc = cumulativeArc[cumulativeArc.length - 1];
		double[] path = new double[SAMPLE_COUNT * 2];
		double[] timeNorm = new double[SAMPLE_COUNT];
		for (int index = 0; index < SAMPLE_COUNT; index++)
		{
			double wantedArc = totalArc * index / (SAMPLE_COUNT - 1);
			Sample point = interpolateArc(samples, cumulativeArc, wantedArc);
			double relativeX = point.x - first.x;
			double relativeY = point.y - first.y;
			path[index * 2] = (relativeX * ux + relativeY * uy) / distance;
			path[index * 2 + 1] = (relativeX * vx + relativeY * vy) / distance;
			timeNorm[index] = (point.timeMillis - first.timeMillis) / duration;
		}
		anchor(path);
		timeNorm[0] = 0.0;
		timeNorm[timeNorm.length - 1] = 1.0;

		return new GenericClientMouseProfile.Template(
			distance,
			duration,
			Math.atan2(dy, dx),
			path,
			timeNorm,
			first.x / viewportWidth,
			first.y / viewportHeight,
			last.x / viewportWidth,
			last.y / viewportHeight,
			approach);
	}

	private static boolean usable(double[] path)
	{
		double backtrack = 0.0;
		for (int index = 0; index < SAMPLE_COUNT; index++)
		{
			if (Math.abs(path[index * 2 + 1]) > MAX_LATERAL_NORM)
			{
				return false;
			}
			if (index == 0)
			{
				continue;
			}
			double du = path[index * 2] - path[(index - 1) * 2];
			double dv = path[index * 2 + 1] - path[(index - 1) * 2 + 1];
			if (Math.hypot(du, dv) > MAX_STEP_NORM)
			{
				return false;
			}
			if (du < 0.0 && (backtrack -= du) > MAX_BACKTRACK_NORM)
			{
				return false;
			}
		}
		return true;
	}

	private static Sample interpolateTime(List<Sample> samples, double timeMillis)
	{
		Sample previous = samples.get(0);
		for (int index = 1; index < samples.size(); index++)
		{
			Sample next = samples.get(index);
			if (next.timeMillis >= timeMillis)
			{
				double span = Math.max(1.0, next.timeMillis - previous.timeMillis);
				double fraction = clamp01((timeMillis - previous.timeMillis) / span);
				return interpolate(previous, next, timeMillis, fraction);
			}
			previous = next;
		}
		return samples.get(samples.size() - 1);
	}

	private static double[] cumulativeArc(List<Sample> samples)
	{
		double[] cumulative = new double[samples.size()];
		for (int index = 1; index < samples.size(); index++)
		{
			cumulative[index] = cumulative[index - 1] + distance(samples.get(index - 1), samples.get(index));
		}
		return cumulative;
	}

	private static Sample interpolateArc(List<Sample> samples, double[] cumulative, double arc)
	{
		if (arc <= 0.0)
		{
			return samples.get(0);
		}
		if (arc >= cumulative[cumulative.length - 1])
		{
			return samples.get(samples.size() - 1);
		}
		for (int index = 1; index < samples.size(); index++)
		{
			if (cumulative[index] < arc)
			{
				continue;
			}
			double span = cumulative[index] - cumulative[index - 1];
			if (span == 0.0)
			{
				continue;
			}
			double fraction = clamp01((arc - cumulative[index - 1]) / span);
			Sample previous = samples.get(index - 1);
			Sample next = samples.get(index);
			return interpolate(previous, next,
				previous.timeMillis + (next.timeMillis - previous.timeMillis) * fraction,
				fraction);
		}
		return samples.get(samples.size() - 1);
	}

	private static Sample interpolate(Sample first, Sample second, double timeMillis, double fraction)
	{
		return new Sample(
			timeMillis,
			first.x + (second.x - first.x) * fraction,
			first.y + (second.y - first.y) * fraction);
	}

	private static void anchor(double[] path)
	{
		path[0] = 0.0;
		path[1] = 0.0;
		path[path.length - 2] = 1.0;
		path[path.length - 1] = 0.0;
	}

	private static double clamp01(double value)
	{
		return Math.max(0.0, Math.min(1.0, value));
	}

	private static double distance(Sample first, Sample second)
	{
		return Math.hypot(second.x - first.x, second.y - first.y);
	}

	private static Sample sample(MouseEvent event)
	{
		return new Sample(System.nanoTime() / 1_000_000.0, event.getX(), event.getY());
	}

	@Override
	public synchronized void close()
	{
		recording = false;
		segment.clear();
		templates.clear();
		canvas.removeMouseMotionListener(listener);
		canvas.removeMouseListener(listener);
	}

	private static final class Sample
	{
		private final double timeMillis;
		private final double x;
		private final double y;

		private Sample(double timeMillis, double x, double y)
		{
			this.timeMillis = timeMillis;
			this.x = x;
			this.y = y;
		}
	}
}
