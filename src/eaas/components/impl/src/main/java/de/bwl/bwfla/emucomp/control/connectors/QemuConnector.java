package de.bwl.bwfla.emucomp.control.connectors;

import java.net.URI;
import java.nio.file.Path;

public class QemuConnector implements IConnector {
    public final static String PROTOCOL = "qemu";

    private final Path unixSocket;

    public QemuConnector(Path unixSocket) {
        this.unixSocket = unixSocket;
    }

    public QemuConnector(String unixSocket) {
        this(Path.of(unixSocket));
    }

    @Override
    public URI getControlPath(URI componentResource) {
        return componentResource.resolve(QemuConnector.PROTOCOL);
    }

    @Override
    public String getProtocol() {
        return QemuConnector.PROTOCOL;
    }

    public Path getUnixSocket() {
        return unixSocket;
    }
    
}
