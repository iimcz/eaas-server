package de.bwl.bwfla.objectarchive.impl;

import de.bwl.bwfla.common.logging.PrefixLogger;
import de.bwl.bwfla.common.logging.PrefixLoggerContext;
import de.bwl.bwfla.objectarchive.conf.ObjectArchiveSingleton;
import de.bwl.bwfla.objectarchive.datatypes.DigitalObjectArchive;


public abstract class DigitalObjectArchiveBase implements DigitalObjectArchive
{
    protected final PrefixLogger log;

    protected DigitalObjectArchiveBase(String type)
    {
        final var logctx = new PrefixLoggerContext()
                .add("type", type);

        this.log = new PrefixLogger(ObjectArchiveSingleton.LOGGER_NAME, logctx);
    }
}
