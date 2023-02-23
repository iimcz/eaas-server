package de.bwl.bwfla.emil;

import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.emil.datatypes.DefaultEnvironments;
import org.apache.tamaya.ConfigurationProvider;

import javax.enterprise.context.ApplicationScoped;
import java.io.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

@ApplicationScoped
public class DefaultEnvironmentsBackend
{
	private final Properties defaultEnvironments;
	private final File propertiesPath;
	private final Logger log;

	public DefaultEnvironmentsBackend()
	{

		log = Logger.getLogger(DefaultEnvironmentsBackend.class.getName());

		var config = ConfigurationProvider.getConfiguration();
		propertiesPath = new File(config.get("emil.default_environments_path"));

		defaultEnvironments = loadProperties();
	}

	private Properties loadProperties()
	{
		log.info("Reading default Environment properties...");
		final Properties properties = new Properties();
		if (propertiesPath.exists()) {
			try {
				try (InputStream input = new FileInputStream(propertiesPath)) {
					properties.load(input);
				}
			}
			catch (Exception error) {
				log.warning("Failed reading default Environments..." + error);
			}
		}
		else {
			log.info("No default environments configured yet...");
		}

		return properties;
	}

	public String getDefaultEnvironment(String osId)
	{
		if (osId == null)
			osId = "default";
		synchronized (defaultEnvironments) {
			return defaultEnvironments.getProperty(osId);
		}
	}

	public DefaultEnvironments getDefaultEnvironments()
	{
		synchronized (defaultEnvironments) {
			Properties defaults = defaultEnvironments;
			List<DefaultEnvironments.DefaultEntry> map = new ArrayList<>();

			Enumeration<?> enumeration = defaults.propertyNames();
			while (enumeration.hasMoreElements()) {
				String k = (String) enumeration.nextElement();
				DefaultEnvironments.DefaultEntry e = new DefaultEnvironments.DefaultEntry();
				e.setKey(k);
				e.setValue(defaults.getProperty(k));
				map.add(e);
			}
			DefaultEnvironments response = new DefaultEnvironments();
			response.setMap(map);
			return response;
		}
	}

	public synchronized void setDefaultEnvironment(String osId, String envId) throws BWFLAException
	{
		synchronized (defaultEnvironments) {
			if (osId == null) // default for all OS
				osId = "default";
			defaultEnvironments.setProperty(osId, envId);

			try {
				try (OutputStream outstream = new FileOutputStream(propertiesPath)) {
					defaultEnvironments.store(outstream, null);
					log.info("List of default environments updated!");
				}
			}
			catch (Exception error) {
				throw new BWFLAException("Updating default environments failed!", error);
			}
		}
	}

}
