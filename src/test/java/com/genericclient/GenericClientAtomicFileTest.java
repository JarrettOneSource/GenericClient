package com.genericclient;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GenericClientAtomicFileTest
{
	@Rule public TemporaryFolder folders = new TemporaryFolder();

	@Test
	public void replacesCompleteUtf8ContentAndPreservesTheOldFileWhenWritingFails() throws Exception
	{
		Path target = folders.newFolder().toPath().resolve("state.json");
		GenericClientAtomicFile.write(target, "previous");
		GenericClientAtomicFile.write(target, "first\n\u03bb\nlast");
		assertEquals("first\n\u03bb\nlast", Files.readString(target));
		Path temporary = target.resolveSibling("state.json.tmp");
		assertFalse(Files.exists(temporary));
		Files.createDirectory(temporary);
		assertThrows(IOException.class, () -> GenericClientAtomicFile.write(target, "incomplete"));
		assertEquals("first\n\u03bb\nlast", Files.readString(target));
	}

	@Test
	public void supportsAFileSystemThatCannotMoveTheTemporaryFileAtomically() throws Exception
	{
		Path directory = folders.newFolder().toPath();
		Path target = directory.resolve("state.json");
		Files.writeString(target, "old");
		URI archive = URI.create("jar:" + directory.resolve("temporary.zip").toUri());
		try (FileSystem zip = FileSystems.newFileSystem(archive, Map.of("create", "true")))
		{
			Path temporary = zip.getPath("/pending.json");
			GenericClientAtomicFile.write(target, temporary, "replacement");
			assertEquals("replacement", Files.readString(target));
			assertFalse(Files.exists(temporary));
		}
	}
}
