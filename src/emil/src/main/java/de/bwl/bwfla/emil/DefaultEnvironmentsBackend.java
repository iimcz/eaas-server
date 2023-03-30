package de.bwl.bwfla.emil;

import de.bwl.bwfla.common.exceptions.BWFLAException;
import org.apache.tamaya.ConfigurationProvider;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;


@ApplicationScoped
public class DefaultEnvironmentsBackend
{
	private final Logger log;
	private final Map<String, String> entries;
	private boolean updated = false;

	public DefaultEnvironmentsBackend()
	{
		this.log = Logger.getLogger("DEFAULT-ENVIRONMENTS");
		this.entries = new ConcurrentHashMap<>();
	}

	public String getDefaultEnvironment(String osId)
	{
		if (osId == null)
			osId = "default";

		return entries.get(osId);
	}

	public Map<String, String> getDefaultEnvironments()
	{
		return entries;
	}

	public void setDefaultEnvironment(String osId, String envId) throws BWFLAException
	{
		if (osId == null) // default for all OS
			osId = "default";

		entries.put(osId, envId);
		updated = true;
	}


	// ===== Internal Helpers ===============

	@PostConstruct
	protected void initialize()
	{
		final var path = DefaultEnvironmentsBackend.getPropertiesPath();
		DefaultEnvironmentsBackend.load(path, entries, log);
	}

	@PreDestroy
	protected void destroy()
	{
		try {
			if (updated) {
				final var path = DefaultEnvironmentsBackend.getPropertiesPath();
				DefaultEnvironmentsBackend.store(path, entries, log);
			}
		}
		catch (Exception error) {
			throw new RuntimeException("Storing default environments failed!", error);
		}
	}

	private static Path getPropertiesPath()
	{
		final var config = ConfigurationProvider.getConfiguration();
		return Path.of(config.get("emil.default_environments_path"));
	}

	private static void load(Path path, Map<String, String> entries, Logger log)
	{
		log.info("Loading default environments from: " + path);
		if (!Files.exists(path)) {
			log.info("No default environments found!");
			return;
		}

		try {
			final Properties properties = new Properties();
			try (InputStream input = Files.newInputStream(path)) {
				properties.load(input);
			}

			properties.forEach((key, value) -> entries.put((String) key, (String) value));
			log.info("Loaded " + properties.size() + " default environment(s)");
		}
		catch (Exception error) {
			log.log(Level.WARNING, "Loading default environments failed!", error);
		}
	}

	private static void store(Path path, Map<String, String> entries, Logger log) throws Exception
	{
		final var properties = new Properties();
		entries.forEach(properties::setProperty);

		try (OutputStream output = Files.newOutputStream(path)) {
			properties.store(output, null);
		}

		log.info(properties.size() + " default environment(s) written to: " + path);
	}
}
