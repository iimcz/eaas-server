




package de.bwl.bwfla.imageclassifier.impl;

import com.openslx.eaas.common.databind.DataUtils;
import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.common.utils.ProcessRunner;
import de.bwl.bwfla.imageclassifier.datatypes.Classifier;
import de.bwl.bwfla.imageclassifier.datatypes.IdentificationOutputIndex;
import de.bwl.bwfla.imageclassifier.datatypes.Siegfried;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SiegfriedClassifier extends Classifier<Siegfried.File> {

    private Logger log = Logger.getLogger(SiegfriedClassifier.class.getName());
    private Siegfried runSiegfried(Path isopath)
    {
        log.info("running siegfried");
        try {
            ProcessRunner process = new ProcessRunner();
            process.setCommand("sf");
            process.addArgument("-json");
                process.addArgument(isopath.toAbsolutePath().toString());

            final ProcessRunner.Result result = process.executeWithResult(false)
                    .orElse(null);

            if (result == null || !result.successful())
                throw new BWFLAException("Running siegfried failed!");

            final String res = result.stdout();
            var sf = Siegfried.fromJsonValue("{ \"siegfried\" : " + res + "}", Siegfried.class);
            log.info(DataUtils.json().writer().withDefaultPrettyPrinter().writeValueAsString(sf));
            return sf;

        }
        catch(Exception exception) {
            log.log(Level.WARNING, exception.getMessage(), exception);
            return null;
        }
    }

    @Override
    public IdentificationOutputIndex<Siegfried.File> runIdentification(boolean verbose) {
        IdentificationOutputIndex<Siegfried.File> index = new SiegfriedOutputIndex();
        for(Path p : contentDirectories)
        {
            Siegfried sf = runSiegfried(p);
            if(sf == null || sf.getFiles() == null)
            {
                log.severe("running siegfried failed");
                continue;
            }
            index.add(sf.getFiles());
            index.addContentPath(p.toString());
        }
        return index;
    }
}
