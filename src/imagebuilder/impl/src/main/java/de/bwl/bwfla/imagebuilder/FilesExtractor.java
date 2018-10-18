/*
 * This file is part of the Emulation-as-a-Service framework.
 *
 * The Emulation-as-a-Service framework is free software: you can
 * redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * The Emulation-as-a-Service framework is distributed in the hope that
 * it will be useful, but WITHOUT ANY WARRANTY; without even the
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Emulation-as-a-Software framework.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package de.bwl.bwfla.imagebuilder;

import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.common.utils.DeprecatedProcessRunner;
import de.bwl.bwfla.common.utils.Zip32Utils;
import de.bwl.bwfla.imagebuilder.api.ImageContentDescription;
import org.apache.commons.io.IOUtils;

import javax.activation.DataHandler;
import javax.activation.URLDataSource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

class FilesExtractor {

	public static void extract(ImageContentDescription entry, Path dstPath, Path workdir, Logger log) throws BWFLAException
	{
		if (entry.getData() != null)
			FilesExtractor.extract(entry.getData(), dstPath, entry.getArchiveFormat(), workdir, log);
		else
			FilesExtractor.extract(new DataHandler(new URLDataSource(entry.getURL())), dstPath, entry.getArchiveFormat(), workdir, log);
	}

	public static void extract(DataHandler handler, Path dstPath, ImageContentDescription.ArchiveFormat format, Path workdir, Logger log)
			throws BWFLAException
	{
		if (format == null)
			throw new BWFLAException("cannot extract. entry format not set.");

		switch (format) {
			case ZIP:
				FilesExtractor.extractZip(handler, dstPath, log);
				return;

			case TAR:
				FilesExtractor.extractTar(handler, dstPath, log);
				return;

			case SIMG:
				FilesExtractor.extractSingularityImage(handler, dstPath, workdir, log);
				return;

			default:
				throw new BWFLAException("Cannot extract entry! Unsupported archive format: " + format.toString());
		}
	}


	private static void extractSingularityImage(DataHandler handler, Path dstdir, Path workdir, Logger log) throws BWFLAException
	{
		final Path image = workdir.resolve("image-" + UUID.randomUUID().toString() + ".simg");
		try {
			Files.copy(handler.getInputStream(), image);

			final String command = "sudo --non-interactive /usr/local/bin/singularity image.export " + image.toString()
					+ " | tar -C " + dstdir.toString() + " -v -xf -";

			final DeprecatedProcessRunner process = new DeprecatedProcessRunner();
			process.setCommand("/bin/bash");
			process.addArguments("-c");
			process.addArgument(command);
			process.setLogger(log);
			if (!process.execute())
				throw new IOException("Running image export failed!");
		}
		catch (Exception error) {
			throw new BWFLAException("Extracting singularity image failed!", error);
		}
		finally {
			try {
				Files.deleteIfExists(image);
			}
			catch (Exception error) {
				log.log(Level.WARNING, "Deleting '" + image.toString() + "' failed!", error);
			}
		}
	}

	private static void extractTar(DataHandler handler, Path dstdir, Logger log) throws BWFLAException
	{
		final DeprecatedProcessRunner process = new DeprecatedProcessRunner();
		process.setCommand("sudo");
		process.addArguments("--non-interactive");
		process.addArgument("tar");
		process.addArgument("--no-same-owner");
		process.addArguments("-v", "-xzf", "-");
		process.setWorkingDirectory(dstdir);
		process.setLogger(log);
		process.start();

		try (InputStream in = handler.getInputStream(); OutputStream out = process.getStdInStream()) {
			IOUtils.copy(in, out);
			in.close();
			out.close();

			int ret = process.waitUntilFinished();
			process.printStdOut();
			process.printStdErr();
			process.cleanup();
			if (ret != 0)
				throw new BWFLAException("Running untar failed!");
		}
		catch (IOException error) {
			throw new BWFLAException("Extracting tar archive failed!", error);
		}
	}


	private static void extractZip(DataHandler handler, Path dstdir, Logger log) throws BWFLAException {
		try (final InputStream input = handler.getInputStream()) {
			log.info("Extracting '" + handler.getName() + "' into image...");
			Zip32Utils.unzip(input, dstdir.toFile());
		}
		catch (Exception error)	{
			throw new BWFLAException("Extracting zip archive failed!", error);
		}
	}
}
