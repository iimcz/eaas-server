package de.bwl.bwfla.emil;
import de.bwl.bwfla.api.imagearchive.*;
import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.imagearchive.util.EnvironmentsAdapter;
import org.apache.tamaya.ConfigurationProvider;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import java.net.URL;
import java.util.List;
import java.util.logging.Logger;


@ApplicationScoped
public class DatabaseEnvironmentsAdapter {

    protected static final Logger LOG = Logger.getLogger(" de.bwl.bwfla.emil.DatabaseEnvironmentsAdapter");
    EnvironmentsAdapter environmentsAdapter;

    @PostConstruct
    public void init() {
        final var imageArchive = ConfigurationProvider.getConfiguration()
                .get("ws.imagearchive");

        environmentsAdapter = new EnvironmentsAdapter(imageArchive);
    }

    @Deprecated
    public EnvironmentsAdapter.ImportImageHandle importImage(String archive, URL url, ImageArchiveMetadata iaMd, boolean b) throws BWFLAException {
        return environmentsAdapter.importImage(archive, url, iaMd, b);
    }

    public String getDefaultEnvironment(String osId) throws BWFLAException {
        return environmentsAdapter.getDefaultEnvironment(osId);
    }

    public void setDefaultEnvironment(String osId, String envId) throws BWFLAException {
        environmentsAdapter.setDefaultEnvironment(osId, envId);
    }

    @Deprecated
    public static final String EMULATOR_DEFAULT_ARCHIVE = "emulators";

    @Deprecated
    public ImageNameIndex getNameIndexes() throws BWFLAException {
        return environmentsAdapter.getNameIndexes(EMULATOR_DEFAULT_ARCHIVE);
    }

    @Deprecated
    public void deleteNameIndexesEntry(String id, String version) throws BWFLAException {
        environmentsAdapter.deleteNameIndexesEntry(id, version);
    }

    @Deprecated
    public void deleteNameIndexesEntry(String backend, String id, String version) throws BWFLAException {
        environmentsAdapter.deleteNameIndexesEntry(backend, id, version);
    }

    @Deprecated
    public ImageNameIndex getImagesIndex() throws BWFLAException
    {
        return environmentsAdapter.getNameIndexes();
    }

    @Deprecated
    public EmulatorMetadata extractMetadata(String imageId) throws BWFLAException {
        return environmentsAdapter.extractMetadata(imageId);
    }

    public List<DefaultEntry> getDefaultEnvironments() throws BWFLAException {
        return environmentsAdapter.getDefaultEnvironments("default");
    }

    @Deprecated
    public String resolveEmulatorImage(String imgid) throws BWFLAException {
        return environmentsAdapter.resolveEmulatorImage(imgid);
    }
}
