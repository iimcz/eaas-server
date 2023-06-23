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

package de.bwl.bwfla.softwarearchive.impl;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import com.openslx.eaas.common.util.AtomicMultiCounter;
import de.bwl.bwfla.common.datatypes.SoftwarePackage;
import de.bwl.bwfla.common.datatypes.SoftwareDescription;
import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.softwarearchive.ISoftwareArchive;


public class SoftwareFileArchive implements Serializable, ISoftwareArchive
{
	private static final long serialVersionUID = -8250444788579220131L;

	protected final Logger log = Logger.getLogger(this.getClass().getName());

	private final String name;
	private final Path archivePath;
	private Map<String, SoftwarePackage> cache;

	
	/**
	 * File-based SoftwareArchive, with SoftwarePackages stored as XML files:
	 * <i>path/ID</i>
	 */
	public SoftwareFileArchive(String name, String path)
	{
		this.name = name;
		this.archivePath = Paths.get(path);

		try {
			this.cache = SoftwareFileArchive.load(archivePath, log);
		}
		catch (Exception error) {
			throw new IllegalStateException("Loading software-archive failed!", error);
		}
	}

	@Override
	public boolean hasSoftwarePackage(String id)
	{
		return cache.containsKey(id);
	}

	@Override
	public boolean changeSoftwareLabel(String id, String newLabel)
	{
		log.info("Changing label for software '" + id + "'...");
		final var software = this.getSoftwarePackageById(id);
		if (software == null)
			return false;

		software.setName(newLabel);
		return this.addSoftwarePackage(software);
	}

	@Override
	public synchronized boolean addSoftwarePackage(SoftwarePackage software)
	{
		final String id = software.getObjectId();
		final Path path = archivePath.resolve(id);
		if (Files.exists(path)) {
			log.info("Software package with ID " + id + " already exists! Replacing it...");
			try {
				Files.deleteIfExists(path);
			} catch (IOException exception) {
				log.log(Level.WARNING, "Deleting software package with ID " + id + " failed!", exception);
			}
		}
		try {
			final var data = software.value(true)
					.getBytes(StandardCharsets.UTF_8);

			Files.write(path, data, StandardOpenOption.CREATE);
		}
		catch (Exception error) {
			log.log(Level.WARNING, "Writing software package '" + path + "' failed!", error);
			return false;
		}

		if (software.isDeleted())
			cache.remove(id);
		else cache.put(id, software);

		return true;
	}
	
	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public int getNumSoftwareSeatsById(String id)
	{
		SoftwarePackage software = this.getSoftwarePackageById(id);
		return (software != null) ? software.getNumSeats() : -1;
	}

	@Override
	public void deleteSoftware(String id) {
		SoftwarePackage swp = getSoftwarePackageById(id);
		if(swp == null)
			return;

		swp.setDeleted(true);
		addSoftwarePackage(swp);
	}

	@Override
	public SoftwarePackage getSoftwarePackageById(String id)
	{
		return cache.get(id);
	}

	@Override
	public Stream<String> getSoftwarePackageIds()
	{
		return cache.keySet()
				.stream();
	}

	@Override
	public Stream<SoftwarePackage> getSoftwarePackages()
	{
		return cache.values()
				.stream();
	}
	
	@Override
	public SoftwareDescription getSoftwareDescriptionById(String id)
	{
		SoftwarePackage software = this.getSoftwarePackageById(id);
		if (software == null)
			return null;

		return SoftwareFileArchive.toSoftwareDescription(software);
	}
	
	@Override
	public Stream<SoftwareDescription> getSoftwareDescriptions()
	{
		return this.getSoftwarePackages()
				.map(SoftwareFileArchive::toSoftwareDescription);
	}


	/* =============== Internal Methods =============== */

	public enum LoadCounts
	{
		LOADED,
		SKIPPED,
		FAILED,
		__LAST;

		public static AtomicMultiCounter counter()
		{
			return new AtomicMultiCounter(__LAST.ordinal());
		}
	}

	private static Map<String, SoftwarePackage> load(Path archivePath, Logger log) throws Exception
	{
		final var counter = LoadCounts.counter();
		final var entries = new ConcurrentHashMap<String, SoftwarePackage>();
		final Consumer<Path> loader = (path) -> {
			try {
				final var software = SoftwareFileArchive.getSoftwarePackageByPath(path);
				if (software.isDeleted()) {
					counter.increment(LoadCounts.SKIPPED);
					return;
				}

				entries.put(software.getId(), software);
				counter.increment(LoadCounts.LOADED);
			}
			catch (Exception error) {
				log.log(Level.WARNING, "Loading software package '" + path.toString() + "' failed!", error);
				counter.increment(LoadCounts.FAILED);
			}
		};

		try (final var files = Files.list(archivePath)) {
			files.forEach(loader);
		}

		final var numCached = counter.get(LoadCounts.LOADED);
		final var numFailed = counter.get(LoadCounts.FAILED);
		final var numSkipped = counter.get(LoadCounts.SKIPPED);
		var summary = "Loaded " + numCached + " software-package(s), failed " + numFailed;
		if (numSkipped > 0)
			summary += ", skipped as deleted " + numSkipped;

		log.info(summary);
		return entries;
	}

	private static SoftwarePackage getSoftwarePackageByPath(Path path) throws Exception
	{
		final var data = Files.readString(path, StandardCharsets.UTF_8);
		return SoftwarePackage.fromValue(data, SoftwarePackage.class);
	}

	private static SoftwareDescription toSoftwareDescription(SoftwarePackage software)
	{
		final var desc = new SoftwareDescription(software.getId(), software.getName(), software.getIsOperatingSystem(), software.getArchive());
		desc.setPublic(software.isPublic());
		return desc;
	}
}
