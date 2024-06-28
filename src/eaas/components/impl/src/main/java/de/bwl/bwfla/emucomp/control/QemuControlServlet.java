package de.bwl.bwfla.emucomp.control;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.usb.UsbConfiguration;
import javax.usb.UsbDevice;
import javax.usb.UsbDeviceDescriptor;
import javax.usb.UsbDisconnectedException;
import javax.usb.UsbException;
import javax.usb.UsbHostManager;
import javax.usb.UsbHub;
import javax.usb.UsbInterface;
import javax.usb.UsbServices;

import org.mongojack.internal.stream.JacksonEncoder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.common.utils.ProcessRunner;
import de.bwl.bwfla.emucomp.NodeManager;
import de.bwl.bwfla.emucomp.components.AbstractEaasComponent;
import de.bwl.bwfla.emucomp.control.connectors.IConnector;
import de.bwl.bwfla.emucomp.control.connectors.QemuConnector;

// Note that this servlet does not have any URL pattern (neither in web.xml)
// The dispatching to this servlet is done in the FilterDispatcher
@WebServlet(name = QemuControlServlet.SERVLET_NAME)
public class QemuControlServlet extends HttpServlet {
    public static final String SERVLET_NAME = "QemuControlServlet";
	
	/** Protocol ID, that must be present in request's URL */
	private static final String PROTOCOL_SUFFIX = "/" + QemuConnector.PROTOCOL;
	
	/** Length of the Protocol ID */
	private static final int PROTOCOL_SUFFIX_LENGTH = PROTOCOL_SUFFIX.length();
	
	/** Start offset of a component ID in the request's URL */
	private static final int COMPONENT_ID_OFFSET = "/components/".length();
	
	/** Logger instance. */
	private final Logger log = Logger.getLogger(QemuControlServlet.SERVLET_NAME);

    @Inject
    private NodeManager nodeManager;

    protected String getComponentId(HttpServletRequest request) throws BWFLAException
	{
		// Parse the request's path, that should contain the session's ID
		final String path = request.getPathInfo();
		if (path == null || !path.endsWith(PROTOCOL_SUFFIX))
			throw new BWFLAException("Wrong servlet requested!");

		final int soffset = COMPONENT_ID_OFFSET;
		final int eoffset = path.length() - PROTOCOL_SUFFIX_LENGTH;
		final String componentId = path.substring(soffset, eoffset);
		if (componentId.isEmpty())
			throw new BWFLAException("Component ID is missing in request!");

		return componentId;
	}
    
	protected QemuConnector getQemuConnector(String componentId) throws BWFLAException
	{
		try {
			AbstractEaasComponent component = nodeManager.getComponentById(componentId, AbstractEaasComponent.class);
			IConnector connector = component.getControlConnector(QemuConnector.PROTOCOL);
			if (connector == null || !(connector instanceof QemuConnector)) {
				String message = "No GuacamoleConnector found for component '" + componentId + "'!";
				throw new BWFLAException(message);
			}

			return (QemuConnector) connector;
		}
		catch (Exception e) {
			throw new BWFLAException("No eaas component found with ID " + componentId, e);
		}
	}

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        QemuConnector connector;

		resp.addHeader("Access-Control-Allow-Origin", "*");
        
        try {
            final var componentId = getComponentId(req);
            connector = getQemuConnector(componentId);
        } catch (BWFLAException e) {
            throw new ServletException(e);
        }

		
        // TODO: filter input lines, maybe don't just forward each line to QEMU and use some command DSL instead.
        var lineStream = req.getReader().lines();
        for (var iterator = lineStream.iterator(); iterator.hasNext(); ) {
            var line = iterator.next();
            log.info("Forwarding to QEMU: " + line);
			
			// TODO: replace this hack using socat to pass a message through unix domain socket
			// with the native version when we switch to a Java version >16
			ProcessRunner echo = new ProcessRunner("echo");
			echo.addArgument(line);
			ProcessRunner socat = new ProcessRunner("socat");
			socat.addArguments("-", "UNIX-CONNECT:" + connector.getUnixSocket());
			ProcessRunner runner = ProcessRunner.pipe(echo, socat);
			runner.execute();
		}
    }

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
		List<DeviceInfo> deviceInfos = new ArrayList<DeviceInfo>();
		try {
			forAllDevices((UsbDevice device) -> {
				var descriptor = device.getUsbDeviceDescriptor();
				String idVendor = String.format("%#04x", descriptor.idVendor());
				String idProduct = String.format("%#04x", descriptor.idProduct());
				String qemuId = generateQemuId(device);
				String product = "";
				try {
					product = device.getProductString();
					deviceInfos.add(new DeviceInfo(
						device.getManufacturerString(),
						device.getProductString(),
						descriptor.idVendor(),
						descriptor.idProduct(),
						String.format("device_add usb-host,vendorid=%s,productid=%s,id=%s", idVendor, idProduct, qemuId),
						String.format("device_del %s", qemuId),
						getDeviceType(device)
					));
				} catch (Exception e) {
					log.warning("Got exception: " + e);
					// Do nothing
				}
				log.info("Found USB device \"" + product + "\" Vendor ID: " + idVendor + ", Product ID: " + idProduct);
			});
		} catch (UsbException e) {
			throw new ServletException(e);
		} catch (SecurityException e) {
			throw new ServletException(e);
		}

		resp.setContentType("application/json");
		resp.addHeader("Access-Control-Allow-Origin", "*");
		ObjectMapper mapper = new ObjectMapper();
		mapper.writeValue(resp.getOutputStream(), deviceInfos);
	}

	protected void forAllDevices(Consumer<UsbDevice> action) throws UsbException, SecurityException {
		forAllDevices(UsbHostManager.getUsbServices().getRootUsbHub(), action);
	}

	protected void forAllDevices(UsbHub hub, Consumer<UsbDevice> action) throws UsbException, SecurityException {
		for (UsbDevice device : (List<UsbDevice>) hub.getAttachedUsbDevices()) {
			if (device.isUsbHub()) {
				forAllDevices((UsbHub) device, action);
				continue;
			}
			action.accept(device);
		}
	}

	protected String generateQemuId(UsbDevice device) {
		var descriptor = device.getUsbDeviceDescriptor();
		return String.format("device%04x%04x", descriptor.idVendor(), descriptor.idProduct());
	}

	// TODO: this should probably be an enum or something like that, instead of just a string
	protected String getDeviceType(UsbDevice device) {
		UsbConfiguration configuration = device.getActiveUsbConfiguration();
		if (configuration == null) {
			return "invalid";
		}
		List<UsbInterface> interfaces = configuration.getUsbInterfaces();
		for (UsbInterface iface : interfaces) {
			var descriptor = iface.getUsbInterfaceDescriptor();
			if (descriptor.bInterfaceClass() == 3) {
				if (descriptor.bInterfaceProtocol() == 1)
					return "keyboard";
				else if (descriptor.bInterfaceProtocol() == 2)
					return "mouse";
				return "hid";
			}
		}
		return "unknown";
	}

	@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
	@JsonIgnoreProperties(ignoreUnknown = true)
	protected class DeviceInfo {
		public String vendor = "";
		public String device = "";
		public int idVendor = 0;
		public int idDevice = 0;
		public String connectCommand = "";
		public String disconnectCommand = "";
		public String deviceType = "";

		public DeviceInfo() {
		}

		public DeviceInfo(String vendor, String device, int idVendor, int idDevice, String connectCommand,
				String disconnectCommand, String deviceType) {
			this.vendor = vendor;
			this.device = device;
			this.idVendor = idVendor;
			this.idDevice = idDevice;
			this.connectCommand = connectCommand;
			this.disconnectCommand = disconnectCommand;
			this.deviceType = deviceType;
		}
	}
}
