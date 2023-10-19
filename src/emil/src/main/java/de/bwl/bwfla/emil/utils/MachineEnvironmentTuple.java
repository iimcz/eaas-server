package de.bwl.bwfla.emil.utils;

import de.bwl.bwfla.emil.datatypes.EmilEnvironment;
import de.bwl.bwfla.emucomp.api.MachineConfiguration;


public class MachineEnvironmentTuple
{
	private final MachineConfiguration machine;
	private final EmilEnvironment environment;

	public MachineEnvironmentTuple(MachineConfiguration machine, EmilEnvironment environment)
	{
		this.machine = machine;
		this.environment = environment;
	}

	public MachineConfiguration machine()
	{
		return machine;
	}

	public EmilEnvironment environment()
	{
		return environment;
	}
}
