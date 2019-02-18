package de.bwl.bwfla.emucomp.components.emulators;

import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.emucomp.api.Drive;
import de.bwl.bwfla.emucomp.api.MachineConfiguration;
import de.bwl.bwfla.emucomp.api.Nic;

import java.nio.file.Paths;

public class ContraltoBean extends EmulatorBean {
    @Override
    protected void prepareEmulatorRunner() throws BWFLAException {
        emuRunner.setCommand("mono");
        emuRunner.addArgument("/ContrAlto/Contralto/bin/Debug/Contralto.exe");
        emuRunner.setWorkingDirectory(Paths.get("/ContrAlto/Contralto/bin/Debug"));
    }

    @Override
    protected String getEmulatorWorkdir()
    {
        return "/ContrAlto/Contralto/bin/Debug";
    }

    @Override
    protected String getEmuContainerName(MachineConfiguration env)
    {
        return "contralto";
    }

    @Override
    protected boolean addDrive(Drive drive) {
        return false;
    }

    @Override
    protected boolean connectDrive(Drive drive, boolean attach) {
        return false;
    }

    @Override
    protected boolean addNic(Nic nic) {
        return false;
    }
}