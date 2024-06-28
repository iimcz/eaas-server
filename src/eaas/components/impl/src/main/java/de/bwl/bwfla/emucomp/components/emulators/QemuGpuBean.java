package de.bwl.bwfla.emucomp.components.emulators;


import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Priority;
import javax.enterprise.inject.Alternative;
import javax.inject.Inject;

import org.apache.tamaya.inject.api.Config;

import de.bwl.bwfla.common.datatypes.EmuCompState;
import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.common.utils.ProcessRunner;
import de.bwl.bwfla.emucomp.api.MachineConfiguration;
import de.bwl.bwfla.emucomp.control.connectors.QemuConnector;

@Priority(90) @Alternative
public class QemuGpuBean extends QemuBean {
	@Inject
	@Config("components.passthrough.vfio_group")
	protected int vfio_group = 0;

	@Inject
	@Config("components.passthrough.devices")
	protected String devices = "";

	protected List<PassedDevice> passedDevices = new ArrayList<PassedDevice>();


    @Override
    protected String getEmuContainerName(MachineConfiguration machineConfiguration) {
        return "qemu-gpu-system";
    }

    @Override
    public void prepareEmulatorRunner() throws BWFLAException {
		// This is mostly copied from QemuBean with changes for GPU passthrough
		if(qemu_bin == null)
			throw new BWFLAException("EmulatorContainer's executable not found! Make sure you have specified "
									+ "a valid path to your executable in the corresponding 'properties' file");

		if(!isValidQemuArch(qemu_bin))
			qemu_bin += emuEnvironment.getArch();

		File exec = new File(qemu_bin);
		String config = this.getNativeConfig();
		
		// Initialize the process-runner
		emuRunner.setCommand(exec.getAbsolutePath());
		monitor_path = this.getSocketsDir().resolve("qemu-monitor-socket").toString();
		emuRunner.addArguments("-monitor", "unix:" + monitor_path + ",server,nowait");

		if (config != null && !config.isEmpty()) {
			String[] tokens = config.trim().split("\\s+");
			String savedNetworkOption = null;
			boolean skipNext = false;
			for (String token : tokens)
			{
				// Happens when there is a space at the beginning or the end of the config
				if(token.isEmpty())
					continue;
				if (skipNext)
				{
					skipNext = false;
					continue;
				}

				if(token.contains("-enable-kvm"))
				{
					if (!this.runKvmCheck()) {
						LOG.warning("KVM device is required, but not available!");
						continue;
					}
					super.isKvmDeviceEnabled = true;
				}

				/*
				 * Filter our soundhw
				 */
				if (token.contains("-soundhw"))
				{
					skipNext = true;
					continue;
				}

				/*
					fiilter -net user
					TODO: refactor if more options need to be filtered
				 */
				if(token.contains("-net"))
				{
					savedNetworkOption = token.trim();
					continue;
				}

				if(savedNetworkOption != null)
				{
					if(token.contains("user")) {
						savedNetworkOption = null;
						continue;
					}
					emuRunner.addArgument(savedNetworkOption.trim());
					savedNetworkOption = null;
				}

				if(token.contains("nic,model=") && emuEnvironment.getNic() != null && emuEnvironment.getNic().size() >0)
					token += ",macaddr=" + emuEnvironment.getNic().get(0).getHwaddress();

				if(emuEnvironment.getNic().size() > 1){
					throw new BWFLAException("We do not support multiple hwAddresses ... yet");
				}

				emuRunner.addArgument(token.trim());
			}
		}

		// Set things up for UEFI and GPU passthrough
		emuRunner.addArguments(getUefiFirmwareOptions());
		emuRunner.addArguments(getDevicePassthroughOptions());

		// Set up USB support
		emuRunner.addArguments(getUsbOptions());
		
		// TODO: handle sound?

		// Qemu's pipe-based character-device requires two pipes (<name>.in + <name>.out) to be created
		final String printerFileName = "parallel-port";
		final Path printerBasePath = this.getPrinterDir().resolve(printerFileName);
		final Path printerOutPath = this.getPrinterDir().resolve(printerFileName + ".out");
		PostScriptPrinter.createUnixPipe(printerBasePath.toString() + ".in");
		PostScriptPrinter.createUnixPipe(printerOutPath.toString());

		// Configure printer device
		super.printer = new PostScriptPrinter(printerOutPath, this, LOG);
		emuRunner.addArguments("-chardev", "pipe,id=printer,path=" + printerBasePath.toString());
		emuRunner.addArguments("-parallel", "chardev:printer");
    }

	@Override
	protected void addAdditionalContainerConfigArguments(ProcessRunner runner) throws BWFLAException {
		boolean passthroughPrepared = runPassedDeviceCheck("/dev/iommu");
		passthroughPrepared &= runPassedDeviceCheck("/dev/vfio/vfio");
		passthroughPrepared &= runPassedDeviceCheck("/dev/kvm");
		passthroughPrepared &= runPassedDeviceCheck("/dev/vfio/" + vfio_group);

		if (!passthroughPrepared) {
			LOG.severe("Failed to prepare runc parameters for vfio/iommu passthrough - check config and iommu setup!");
			emuBeanState.update(EmuCompState.EMULATOR_FAILED);
			throw new BWFLAException("Failed to prepare runc params.");
		}

		for (PassedDevice device : passedDevices) {
			runner.addArguments("--add-device", String.format("%s:%s:%s:%s", device.Type, device.Major, device.Minor, device.Path));
		}
		passedDevices.clear();

		// Add bind mounts for USB devices
		runner.addArguments("--mount", "/dev/bus/usb:/dev/bus/usb:bind:rw");
		runner.addArguments("--mount", "/dev/usb:/dev/usb:bind:rw");
		runner.addArguments("--add-usb-cgroup");

		// TODO: a configurable value or use a modified VM parameter
		runner.addArguments("--memlock-limit", "1099511627776");
	}

	@Override
	public void start() throws BWFLAException {
		addControlConnector(new QemuConnector(monitor_path));
		super.start();
	}

	protected boolean runPassedDeviceCheck(String path) throws BWFLAException {
		final ProcessRunner runner = new ProcessRunner();
		runner.setCommand("file");
		runner.addArgument(path);
		runner.redirectStdErrToStdOut(false);
		runner.setLogger(LOG);
		try {
			final ProcessRunner.Result result = runner.executeWithResult().orElse(null);

			if (result == null || !result.successful())
				return false;
			
			if (result.stdout().contains("character special")) {
				int substringStart = result.stdout().lastIndexOf("(");
				final String[] majorMinorPair = result.stdout().substring(substringStart).split("/");
				final String majorString = majorMinorPair[0].substring(1);
				final String minorString = majorMinorPair[1].substring(0, majorMinorPair[1].length() - 2);

				passedDevices.add(new PassedDevice(
					path, "c", majorString, minorString
				));

				return true;
			}
			return false;
		} catch (IOException e) {
			throw new BWFLAException("Failed to read output of the 'file' command.");
		}
	}

	protected String[] getUefiFirmwareOptions() {
		// TODO: parametrize
		return new String[] {
			"-machine",
			"q35",
			"-drive",
			"if=pflash,format=raw,readonly=on,file=/opt/ovmf/OVMF.fd",
			"-drive",
			"if=pflash,format=raw,file=/opt/ovmf/OVMF_VARS.fd"
		};
	}

    protected String[] getDevicePassthroughOptions() {
		String[] devices_parts = devices.split(";");
		// TODO: check that we actually got something
		ArrayList<String> output = new ArrayList<>();
		for (String string : devices_parts) {
			output.add("-device");
			output.add("vfio-pci,host=" + string);
		}
		return output.toArray(new String[] {});
	}
	
	private String[] getUsbOptions() {
		return new String[] {
			"-device",
			"qemu-xhci"
		};
	}

	final protected class PassedDevice {
		public String Path;
		public String Type;
		public String Major;
		public String Minor;

		public PassedDevice(String path, String type, String major, String minor)
		{
			Path = path;
			Type = type;
			Major = major;
			Minor = minor;
		}
	}
}
