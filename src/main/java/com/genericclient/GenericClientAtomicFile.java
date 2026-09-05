package com.genericclient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Replaces a text file only after its complete contents have been written. */
final class GenericClientAtomicFile
{
	private GenericClientAtomicFile() { }

	static void write(Path target, String value) throws IOException
	{
		write(target, target.resolveSibling(target.getFileName() + ".tmp"), value);
	}

	static void write(Path target, Path temporary, String value) throws IOException
	{
		Files.writeString(temporary, value, StandardCharsets.UTF_8);
		try
		{
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		}
		catch (AtomicMoveNotSupportedException exception)
		{
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
