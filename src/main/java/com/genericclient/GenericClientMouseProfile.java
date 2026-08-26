package com.genericclient;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class GenericClientMouseProfile
{
	static final String DEFAULT_FILE_NAME = "default.json";

	private static final String SCHEMA = "genericclient_mouse_profile.v1";
	private static final String DEFAULT_RESOURCE = "/com/genericclient/mouse/default.json";

	private final String profileId;
	private final List<Template> templates;

	private GenericClientMouseProfile(String profileId, List<Template> templates)
	{
		this.profileId = profileId;
		this.templates = Collections.unmodifiableList(new ArrayList<>(templates));
	}

	static Path installDefault(Path directory) throws IOException
	{
		Files.createDirectories(directory);
		Path target = directory.resolve(DEFAULT_FILE_NAME);
		if (Files.exists(target))
		{
			return target;
		}

		try (InputStream input = GenericClientMouseProfile.class.getResourceAsStream(DEFAULT_RESOURCE))
		{
			if (input == null)
			{
				throw new IOException("Missing bundled mouse profile: " + DEFAULT_RESOURCE);
			}
			Files.copy(input, target);
		}
		return target;
	}

	static GenericClientMouseProfile load(Path path) throws IOException
	{
		ProfileFile file;
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			file = new Gson().fromJson(reader, ProfileFile.class);
		}
		catch (JsonParseException exception)
		{
			throw new IOException("Invalid mouse profile JSON: " + path, exception);
		}

		if (file == null || !SCHEMA.equals(file.schema))
		{
			throw new IOException("Unsupported mouse profile schema: " + path);
		}
		if (file.profile_id == null || file.profile_id.trim().isEmpty())
		{
			throw new IOException("Mouse profile has no profile_id: " + path);
		}
		if (file.templates == null || file.templates.isEmpty())
		{
			throw new IOException("Mouse profile has no templates: " + path);
		}

		List<Template> templates = new ArrayList<>(file.templates.size());
		for (int index = 0; index < file.templates.size(); index++)
		{
			templates.add(Template.fromFile(file.templates.get(index), index));
		}
		return new GenericClientMouseProfile(file.profile_id, templates);
	}

	static GenericClientMouseProfile recorded(String profileId, List<Template> templates)
	{
		if (templates.isEmpty())
		{
			throw new IllegalArgumentException("A recorded mouse profile needs at least one template");
		}
		return new GenericClientMouseProfile(profileId, templates);
	}

	void save(Path path) throws IOException
	{
		ProfileFile file = new ProfileFile();
		file.schema = SCHEMA;
		file.profile_id = profileId;
		file.templates = new ArrayList<>(templates.size());
		for (Template template : templates)
		{
			file.templates.add(template.toFile());
		}

		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(temporary, new Gson().toJson(file), StandardCharsets.UTF_8);
		try
		{
			Files.move(temporary, path,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException exception)
		{
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	String getProfileId()
	{
		return profileId;
	}

	int getTemplateCount()
	{
		return templates.size();
	}

	List<Template> getTemplates()
	{
		return templates;
	}

	static final class Template
	{
		final double distancePx;
		final double durationMillis;
		final double angleRadians;
		final double[] path;
		final double[] timeNorm;
		final double startNormX;
		final double startNormY;
		final double targetNormX;
		final double targetNormY;
		final boolean approach;

		Template(
			double distancePx,
			double durationMillis,
			double angleRadians,
			double[] path,
			double[] timeNorm,
			double startNormX,
			double startNormY,
			double targetNormX,
			double targetNormY,
			boolean approach)
		{
			this.distancePx = distancePx;
			this.durationMillis = durationMillis;
			this.angleRadians = angleRadians;
			this.path = path.clone();
			this.timeNorm = timeNorm.clone();
			this.startNormX = clamp01(startNormX);
			this.startNormY = clamp01(startNormY);
			this.targetNormX = clamp01(targetNormX);
			this.targetNormY = clamp01(targetNormY);
			this.approach = approach;
		}

		private static Template fromFile(TemplateFile file, int index) throws IOException
		{
			if (file == null || !Double.isFinite(file.distance_px) || file.distance_px <= 0.0 ||
				!Double.isFinite(file.duration_ms) || file.duration_ms <= 0.0 ||
				!Double.isFinite(file.angle_rad) || file.path == null ||
				file.path.length != GenericClientMouseMatcher.VECTOR_SIZE)
			{
				throw new IOException("Mouse template " + index + " has invalid metadata");
			}
			for (double value : file.path)
			{
				if (!Double.isFinite(value))
				{
					throw new IOException("Mouse template " + index + " has a non-finite path value");
				}
			}
			double[] start = normalizedPair(file.start_norm, index, "start_norm");
			double[] target = normalizedPair(file.target_norm, index, "target_norm");
			return new Template(
				file.distance_px,
				file.duration_ms,
				file.angle_rad,
				file.path,
				GenericClientMouseMatcher.validatedTimeNorm(file.time_norm, index),
				start[0],
				start[1],
				target[0],
				target[1],
				file.approach == null || file.approach);
		}

		private TemplateFile toFile()
		{
			TemplateFile file = new TemplateFile();
			file.distance_px = distancePx;
			file.duration_ms = durationMillis;
			file.angle_rad = angleRadians;
			file.path = path;
			file.time_norm = timeNorm;
			file.start_norm = new double[]{startNormX, startNormY};
			file.target_norm = new double[]{targetNormX, targetNormY};
			file.approach = approach;
			return file;
		}

		private static double[] normalizedPair(double[] values, int index, String name) throws IOException
		{
			if (values == null || values.length == 0)
			{
				return new double[]{0.5, 0.5};
			}
			if (values.length != 2 || !Double.isFinite(values[0]) || !Double.isFinite(values[1]))
			{
				throw new IOException("Mouse template " + index + " has invalid " + name);
			}
			return values;
		}

		private static double clamp01(double value)
		{
			return Math.max(0.0, Math.min(1.0, value));
		}
	}

	private static final class ProfileFile
	{
		private String schema;
		private String profile_id;
		private List<TemplateFile> templates;
	}

	private static final class TemplateFile
	{
		private double distance_px;
		private double duration_ms;
		private double angle_rad;
		private double[] path;
		private double[] time_norm;
		private double[] start_norm;
		private double[] target_norm;
		private Boolean approach;
	}
}
