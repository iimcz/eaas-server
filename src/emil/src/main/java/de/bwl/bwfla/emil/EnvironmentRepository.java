/*
 * This file is part of the Emulation-as-a-Service framework.
 *
 * The Emulation-as-a-Service framework is free software: you can
 * redistribute it and/or modify it under the terms of the GNU General
 * Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * The Emulation-as-a-Service framework is distributed in the hope that
 * it will be useful, but WITHOUT ANY WARRANTY; without even the
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 * PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with the Emulation-as-a-Software framework.
 * If not, see <http://www.gnu.org/licenses/>.
 */

package de.bwl.bwfla.emil;

import com.openslx.eaas.common.concurrent.ParallelProcessors;
import com.openslx.eaas.common.databind.DataUtils;
import com.openslx.eaas.common.databind.Streamable;
import com.openslx.eaas.common.util.MultiCounter;
import com.openslx.eaas.generalization.ImageGeneralizationPatchDescription;
import com.openslx.eaas.generalization.ImageGeneralizationPatchResponse;
import com.openslx.eaas.imagearchive.ImageArchiveClient;
import com.openslx.eaas.imagearchive.ImageArchiveMappers;
import com.openslx.eaas.imagearchive.api.v2.common.ReplaceOptionsV2;
import com.openslx.eaas.imagearchive.api.v2.common.ResolveOptionsV2;
import com.openslx.eaas.imagearchive.api.v2.databind.AccessMethodV2;
import com.openslx.eaas.imagearchive.api.v2.databind.MetaDataKindV2;
import com.openslx.eaas.imagearchive.client.endpoint.v2.common.IReadableResource;
import com.openslx.eaas.imagearchive.client.endpoint.v2.common.RemoteResourceRW;
import com.openslx.eaas.imagearchive.client.endpoint.v2.util.EmulatorMetaHelperV2;
import com.openslx.eaas.imagearchive.databind.EmulatorMetaData;
import com.openslx.eaas.imagearchive.databind.ImageMetaData;
import com.openslx.eaas.migration.IMigratable;
import com.openslx.eaas.migration.MigrationRegistry;
import com.openslx.eaas.migration.MigrationUtils;
import com.openslx.eaas.migration.config.MigrationConfig;
import com.openslx.eaas.generalization.ImageGeneralizationClient;
import com.openslx.eaas.generalization.ImageGeneralizationPatchRequest;
import com.webcohesion.enunciate.metadata.rs.TypeHint;
import de.bwl.bwfla.api.imagearchive.*;
import de.bwl.bwfla.common.datatypes.identification.OperatingSystems;
import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.common.utils.ImageInformation;
import de.bwl.bwfla.common.utils.NetworkUtils;
import de.bwl.bwfla.emil.datatypes.DefaultEnvironmentResponse;
import de.bwl.bwfla.emil.datatypes.EmilEnvironment;
import de.bwl.bwfla.emil.datatypes.EmilObjectEnvironment;
import de.bwl.bwfla.emil.datatypes.EnvironmentCreateRequest;
import de.bwl.bwfla.emil.datatypes.EnvironmentDeleteRequest;
import de.bwl.bwfla.common.services.rest.ErrorInformation;
import de.bwl.bwfla.emil.datatypes.ImportImageRequest;
import de.bwl.bwfla.emil.datatypes.rest.*;
import de.bwl.bwfla.emil.datatypes.rest.ReplicateImagesResponse;
import de.bwl.bwfla.emil.datatypes.rest.TaskStateResponse;
import de.bwl.bwfla.emil.datatypes.rest.UpdateEnvironmentDescriptionRequest;
import de.bwl.bwfla.common.services.security.AuthenticatedUser;
import de.bwl.bwfla.common.services.security.Role;
import de.bwl.bwfla.common.services.security.Secured;
import de.bwl.bwfla.common.services.security.UserContext;
import de.bwl.bwfla.emil.tasks.CreateEmptyImageTask;
import de.bwl.bwfla.emil.tasks.ExportEnvironmentTask;
import de.bwl.bwfla.emil.tasks.ImportImageTask;
import de.bwl.bwfla.emil.tasks.ImportImageTask.ImportImageTaskRequest;
import de.bwl.bwfla.emil.tasks.ReplicateImageTask;
import de.bwl.bwfla.emil.utils.ImportCounts;
import de.bwl.bwfla.emil.utils.LegacyImageArchiveConfigIterator;
import de.bwl.bwfla.emil.utils.LegacyImageArchiveUtils;
import de.bwl.bwfla.emil.utils.MachineEnvironmentTuple;
import de.bwl.bwfla.emil.utils.TaskManager;
import de.bwl.bwfla.emucomp.api.*;
import de.bwl.bwfla.emucomp.api.MachineConfiguration.NativeConfig;
import de.bwl.bwfla.imageproposer.client.ImageProposer;
import de.bwl.bwfla.objectarchive.util.ObjectArchiveHelper;
import de.bwl.bwfla.softwarearchive.util.SoftwareArchiveHelper;
import org.apache.tamaya.ConfigurationProvider;
import org.apache.tamaya.inject.api.Config;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.stream.JsonGenerator;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.StreamingOutput;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Stream;


@ApplicationScoped
@Path("/environment-repository")
public class EnvironmentRepository extends EmilRest
		implements IMigratable
{
	private ImageArchiveClient imagearchive = null;

	@Inject
	private DatabaseEnvironmentsAdapter envdb = null;

	@Inject
	@Config(value = "emil.imageproposerservice")
	private String imageProposerService = null;

	@Inject
	@Config(value = "ws.imagebuilder")
	String imageBuilderAddress;

	@Inject
	private EmilEnvironmentRepository emilEnvRepo = null;

	private ImageProposer imageProposer = null;

	@Inject
	private TaskManager taskManager = null;

	@Resource(lookup = "java:jboss/ee/concurrency/executor/io")
	private ExecutorService executor;

	@Inject
	@AuthenticatedUser
	private UserContext authenticatedUser = null;

	@Inject
	private ObjectClassification classification = null;

	private SoftwareArchiveHelper swHelper;

	private ImageGeneralizationClient imageGeneralization;

	@Inject
	private EmilObjectData objects;

	@Inject
	private DefaultEnvironmentsBackend defaultEnvs;


	@PostConstruct
	private void initialize()
	{
		try {
			imagearchive = emilEnvRepo.getImageArchive();
			imageProposer = new ImageProposer(imageProposerService + "/imageproposer");
			swHelper = new SoftwareArchiveHelper(softwareArchive);

			final Supplier<String> usertoken = () -> this.getUserContext().getToken();
			imageGeneralization = ImageGeneralizationClient.create(usertoken);
		}
		catch (Exception error) {
			throw new RuntimeException(error);
		}
	}

	@PreDestroy
	private void destroy()
	{
		try {
			if (imageGeneralization != null)
				imageGeneralization.close();
		}
		catch (Exception error) {
			LOG.log(Level.WARNING, "Closing generalization-patches client failed!", error);
		}
	}


	// ========== Public API ==============================

	@Path("/environments")
	public Environments environments()
	{
		return new Environments();
	}

	@Path("/default-environments")
	public DefaultEnvironments defaultEnvironments()
	{
		return new DefaultEnvironments();
	}

	@Path("/templates")
	public Templates templates()
	{
		return new Templates();
	}

	@Path("/patches")
	public Patches patches()
	{
		return new Patches();
	}

	@Path("/actions")
	public Actions actions()
	{
		return new Actions();
	}

	@Path("/images")
	public Images images()
	{
		return new Images();
	}

	@GET
	@Path("/db-migration")
	@Secured(roles={Role.RESTRICTED})
	@Produces(MediaType.APPLICATION_JSON)
	public Response migrateDb()
	{
		LOG.info("Try to migrate DB content ...");
		try {
			emilEnvRepo.importOldDb();
			return Response.status(Status.OK)
					.build();
		}
		catch ( BWFLAException error) {
			LOG.log(Level.WARNING,"database migration failed!\n", error);
			return EnvironmentRepository.internalErrorResponse(error);
		}
	}

	@GET
	@Path("/os-metadata")
	@Secured(roles={Role.PUBLIC})
	@Produces(MediaType.APPLICATION_JSON)
	public OperatingSystems getOperatingSystemMetadata()
	{
		LOG.info("Loading OS-metadata...");

		final String serverDataDir = ConfigurationProvider.getConfiguration()
				.get("commonconf.serverdatadir");

		final java.nio.file.Path osInfoPath = Paths.get(serverDataDir, "operating-systems.json");
		if (!Files.exists(osInfoPath)) {
			LOG.severe("No operating-systems.json found!");
			throw new NotFoundException();
		}

		try {
			return DataUtils.json().mapper().readValue(osInfoPath.toFile(), OperatingSystems.class);
		}
		catch (Exception error) {
			LOG.log(Level.WARNING, "Deserializing OS-metadata failed!", error);
			throw new InternalServerErrorException(error);
		}
	}

	/** Get the image-name index */
	@Deprecated
	@GET
	@Path("/image-name-index")
	@Secured(roles={Role.PUBLIC})
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
    public ImageNameIndex getNameIndexes() throws BWFLAException
	{
		final var entries = new ImageNameIndex.Entries();
		final var aliases = new ImageNameIndex.Aliases();
		final var emulators = imagearchive.api()
				.v2()
				.metadata(MetaDataKindV2.EMULATORS)
				.fetch(ImageArchiveMappers.JSON_TREE_TO_EMULATOR_METADATA);

		final Consumer<EmulatorMetaData> converter = (emulator) -> {
			final var metadata = new ImageMetadata();
			metadata.setName(emulator.name());
			metadata.setVersion(emulator.version());
			metadata.setDigest(emulator.digest());

			final var newimg = emulator.image();
			final var oldimg = new ImageDescription();
			oldimg.setFstype(newimg.fileSystemType());
			oldimg.setType(newimg.category());
			oldimg.setId(newimg.id());
			metadata.setImage(oldimg);

			final var newprov = emulator.provenance();
			final var oldprov = new Provenance();
			oldprov.setOciSourceUrl(newprov.url());
			oldprov.setVersionTag(newprov.tag());
			oldprov.getLayers()
					.addAll(newprov.layers());
			metadata.setProvenance(oldprov);

			final var ee = new ImageNameIndex.Entries.Entry();
			ee.setKey(emulator.name() + "|" + emulator.version());
			ee.setValue(metadata);
			entries.getEntry()
					.add(ee);

			final var tags = new HashSet<>(emulator.tags());
			tags.add(emulator.version());
			if (tags.contains(EmulatorMetaData.DEFAULT_VERSION))
				tags.add("latest");

			for (final var tag : tags) {
				final var alias = new Alias();
				alias.setName(emulator.name());
				alias.setVersion(emulator.version());
				alias.setAlias(tag);

				final var ae = new ImageNameIndex.Aliases.Entry();
				ae.setKey(emulator.name() + "|" + tag);
				ae.setValue(alias);
				aliases.getEntry()
						.add(ae);
			}
		};

		try (emulators) {
			emulators.stream()
					.forEach(converter);
		}

		final var index = new ImageNameIndex();
		index.setEntries(entries);
		index.setAliases(aliases);
		return index;
    }

	@Deprecated
	@GET
	@Path("/images-index")
	@Secured(roles={Role.PUBLIC})
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public ImageNameIndex getImagesIndex() throws BWFLAException
	{
		final var entries = new ImageNameIndex.Entries();
		final var images = this.images()
				.list();

		final Consumer<ImageMetaData> converter = (image) -> {
			final var metadata = new ImageMetadata();
			metadata.setName(image.id());
			metadata.setLabel(image.label());

			final var description = new ImageDescription();
			description.setFstype(image.fileSystemType());
			description.setType(image.category());
			description.setId(image.id());
			metadata.setImage(description);

			final var entry = new ImageNameIndex.Entries.Entry();
			entry.setKey(image.id() + "|*");
			entry.setValue(metadata);
			entries.getEntry()
					.add(entry);
		};

		try (images) {
			images.stream()
					.forEach(converter);
		}

		final var index = new ImageNameIndex();
		index.setEntries(entries);
		return index;
	}


	// ========== Subresources ==============================

	public class ResolvableResource
	{
		private final String kind;
		private final IReadableResource<?> resource;

		protected ResolvableResource(String kind, IReadableResource<?> resource)
		{
			this.kind = kind;
			this.resource = resource;
		}

		@HEAD
		@Path("/{id}/url")
		@Secured(roles = {Role.PUBLIC})
		public Response resolveHEAD(@PathParam("id") String id)
		{
			return this.resolve(id, HttpMethod.HEAD);
		}

		@GET
		@Path("/{id}/url")
		@Secured(roles = {Role.PUBLIC})
		public Response resolveGET(@PathParam("id") String id)
		{
			return this.resolve(id, HttpMethod.GET);
		}

		private Response resolve(String id, String method)
		{
			try {
				final var userctx = EnvironmentRepository.this.getUserContext();
				final var options = new ResolveOptionsV2()
						.setMethod(AccessMethodV2.valueOf(method));

				if (userctx.isAvailable()) {
					options.userinfo()
							.setTenantId(userctx.getTenantId())
							.setUserId(userctx.getUserId());
				}

				final var location = resource.resolve(id, options);
				LOG.info("Resolving " + kind + " '" + id + "' -> " + method + " " + location);
				return Response.temporaryRedirect(new URI(location))
						.build();
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Resolving " + kind + " '" + id + "' failed!", error);
				throw new NotFoundException();
			}
		}
	}

	public class Images extends ResolvableResource
	{
		public Images()
		{
			super("image", imagearchive.api().v2().images());
		}

		/** List all available images */
		@GET
		@Secured(roles={Role.PUBLIC})
		@Produces(MediaType.APPLICATION_JSON)
		@TypeHint(ImageMetaData[].class)
		public Streamable<ImageMetaData> list() throws BWFLAException
		{
			LOG.info("Listing all available images...");
			return imagearchive.api()
					.v2()
					.metadata(MetaDataKindV2.IMAGES)
					.fetch(ImageArchiveMappers.JSON_TREE_TO_IMAGE_METADATA);
		}

		@POST
		@Secured(roles={Role.RESTRICTED})
		@Consumes(MediaType.APPLICATION_JSON)
		public Response update(ImageMetaData userMetaData) throws BWFLAException {
			LOG.info("Updating image metadata...");
			ImageMetaData storedMetadata;
			// we only re-use label and ID from user provided metadata
			try {
				storedMetadata = imagearchive.api()
						.v2()
						.metadata(MetaDataKindV2.IMAGES).fetch(userMetaData.id(), ImageArchiveMappers.JSON_TREE_TO_IMAGE_METADATA);
			} catch (Throwable e) {
				LOG.log(Level.WARNING, "Loading metadata failed!", e);
				throw new BadRequestException(Response.status(Status.BAD_REQUEST)
						.entity(new ErrorInformation(e))
						.build());
			}

			final var updatedMetadata = new ImageMetaData()
					.setId(userMetaData.id())
					.setLabel(userMetaData.label())
					.setCategory(storedMetadata.category());

			// todo set options here. unclear which location will be used.
			imagearchive.api()
                        .v2()
                        .metadata(MetaDataKindV2.IMAGES)
                        .replace(userMetaData.id(), updatedMetadata, ImageArchiveMappers.OBJECT_TO_JSON_TREE);

			return Response.status(Status.OK).build();
		}

		/** Create a new environment */
//		@POST
//		@Secured({Role.RESTRCITED})
//		@Produces(MediaType.APPLICATION_JSON)
//		@Consumes(MediaType.APPLICATION_JSON)
//		public TaskStateResponse _import(ImageImRequest envReq)
//		{
//			LOG.info("Importing a new image ...");
//
//			try {
//				ImageMetadata d = null;
//				if (envReq.getLabel() != null)
//				{
//					d = new ImageMetadata();
//					d.setName(envReq.getLabel());
//				}
//				TaskState id = envdb.importImage("default", envReq.getSize(), ImageType.USER, d);
//				return new TaskStateResponse(id.getTaskId(), id.isDone());
//			}
//			catch (BWFLAException e)
//			{
//				return new TaskStateResponse(e);
//			}
//		}
	}

	public class Environments
	{
		/** List all available environments */
		@GET
		@Secured(roles={Role.PUBLIC})
		@Produces(MediaType.APPLICATION_JSON)
		public Response list(@QueryParam("detailed") @DefaultValue("false") boolean detailed,
							 @QueryParam("localOnly") @DefaultValue("true") boolean localOnly)
		{
			LOG.info("Listing all available environments...");
			try {
				final Stream<EmilEnvironment> environments = emilEnvRepo.getEmilEnvironments();
				final Stream<Object> entries = (!detailed) ? environments.filter((env) -> (!localOnly || !(env).getArchive().equals("remote"))).map(EnvironmentListItem::new)
						: environments.map((env) -> (Object) this.addEnvironmentDetailsNoThrow(env))
								.filter(Objects::nonNull)
								.filter((env) -> (!localOnly || !((EnvironmentDetails) env).getArchive().equals("remote")));

				// Construct response (in streaming-mode)
				final StreamingOutput output = (ostream) -> {
					final var jsonfactory = DataUtils.json()
							.mapper()
							.getFactory();

					try (com.fasterxml.jackson.core.JsonGenerator json = jsonfactory.createGenerator(ostream)) {
						final var writer = DataUtils.json()
								.writer();

						json.writeStartArray();
						entries.forEach((entry) -> {
							try {
								writer.writeValue(json, entry);
							}
							catch (Exception error) {
								LOG.log(Level.WARNING, "Serializing environment failed!", error);
								throw new RuntimeException(error);
							}
						});
						json.writeEndArray();
						json.flush();
					}
					finally {
						environments.close();
						entries.close();
					}
				};

				return Response.status(Status.OK)
						.entity(output)
						.build();
			}
			catch (Throwable error) {
				LOG.log(Level.WARNING, "Loading environments failed!", error);
				throw new BadRequestException(Response.status(Status.BAD_REQUEST)
						.entity(new ErrorInformation(error))
						.build());
			}
		}

		/** Get specific environment */
		@GET
		@Path("/{envId}")
		@Secured(roles={Role.PUBLIC})
		@Produces(MediaType.APPLICATION_JSON)
		public Response get(@PathParam("envId") String envId)
		{
			LOG.info("Looking up environment '" + envId + "'...");

			EmilEnvironment emilenv = emilEnvRepo.getEmilEnvironmentById(envId);
			if (emilenv == null) {
				// TODO: throw NotFoundException here
				throw new BadRequestException(Response
						.status(Status.BAD_REQUEST)
						.entity(new ErrorInformation("environment id not found " + envId))
						.build());
			}

			try {
				EnvironmentDetails result = this.addEnvironmentDetails(emilenv);
				return Response.ok()
						.entity(result)
						.build();
			}
			catch (BWFLAException error) {
				LOG.log(Level.WARNING, "Loading environment failed!", error);
				throw new BadRequestException(Response
						.status(Status.BAD_REQUEST)
						.entity(new ErrorInformation("failed retrieving data"))
						.build());
			}
		}

		/** Construct an ephemeral machine config and environment description */
		public MachineEnvironmentTuple construct(EnvironmentCreateRequest request) throws BWFLAException
		{
			if (request.getTemplateId() == null || request.getTemplateId().isEmpty())
				throw new IllegalArgumentException("Template ID is invalid!");

			final var template = imagearchive.api()
					.v2()
					.templates()
					.fetch(request.getTemplateId());

			final var machine = (MachineConfiguration) template.copy(); // don't modify the real template
			machine.setId(UUID.randomUUID().toString());
			machine.setCurrentTimestamp();
			machine.getDescription()
					.setTitle(request.getLabel());

			if (machine.getNativeConfig() == null)
				machine.setNativeConfig(new NativeConfig());

			machine.getNativeConfig()
					.setValue(request.getNativeConfig());

			machine.setOperatingSystemId(request.getOperatingSystemId());
			EnvironmentRepository.driveUpdateHelper(machine, request.getDriveSettings(), objects);

			if (machine.getUiOptions() == null)
				machine.setUiOptions(new UiOptions());

			final UiOptions uiopts = machine.getUiOptions();
			if (request.isUseXpra())
				uiopts.setForwarding_system("XPRA");
			else uiopts.setForwarding_system(null);

			if (request.isUseWebRTC())
				uiopts.setAudio_system("webRTC");
			else uiopts.setAudio_system(null);

			if (uiopts.getHtml5() == null)
				uiopts.setHtml5(new Html5Options());

			uiopts.setDisableGhostCursor(request.isDisableGhostCursor());

			// TODO: refactor
			if (request.getRomId() != null && request.getRomLabel() != null) {
				ImageArchiveBinding romBinding = new ImageArchiveBinding("default", request.getRomId(), ImageType.ROMS.value());
				romBinding.setId("rom-" + request.getRomId());
				romBinding.setAccess(Binding.AccessType.COPY);
				machine.getAbstractDataResource().add(romBinding);
			}

			final var environment = new EmilEnvironment();
			environment.setEnvId(machine.getId());
			environment.setTitle(request.getLabel());
			environment.setDescription("User created environment");
			environment.setEnableRelativeMouse(request.isEnableRelativeMouse());
			environment.setEnablePrinting(request.isEnablePrinting());
			environment.setShutdownByOs(request.isShutdownByOs());
			environment.setXpraEncoding(request.getXpraEncoding());
			environment.setOs(request.getOperatingSystemId());
			environment.setLinuxRuntime(machine.isLinuxRuntime());
			if (machine.isLinuxRuntime())
				environment.setArchive("public");

			if (request.isEnableNetwork()) {
				NetworkingType network = new NetworkingType();
				network.setConnectEnvs(true);
				network.setEnableInternet(request.isEnableInternet());
				environment.setNetworking(network);
			}

			return new MachineEnvironmentTuple(machine, environment);
		}

		/** Create a new environment */
		@POST
		@Secured(roles={Role.RESTRICTED})
		@Produces(MediaType.APPLICATION_JSON)
		@Consumes(MediaType.APPLICATION_JSON)
		public Response create(EnvironmentCreateRequest request)
		{
			LOG.info("Creating new environment from template '" + request.getTemplateId() + "'...");

			try {
				final var result = this.construct(request);
				final var environment = result.environment();
				final var machine = result.machine();
				final var ropts = new ReplaceOptionsV2();
				if (machine.isLinuxRuntime())
					ropts.setLocation("public");

				imagearchive.api()
						.v2()
						.machines()
						.replace(machine.getId(), machine, ropts);

				emilEnvRepo.save(environment, true);
				LOG.info("New environment created: " + machine.getId());

				final JsonObject json = Json.createObjectBuilder()
						.add("id", machine.getId())
						.build();

				return Response.ok()
						.entity(json)
						.build();
			}
			catch (Throwable error) {
				LOG.log(Level.WARNING, "Creating new environment failed!", error);
				throw new BadRequestException(Response
						.status(Response.Status.BAD_REQUEST)
						.entity(new ErrorInformation(error.getMessage()))
						.build());
			}
		}

		/**
		 * Update the description of a specified environment. This method
		 * expects a JSON object containing the description changes:
		 * <p>
		 * <pre>
		 * {
		 *      "envId": &ltEnvironment's ID&gt,
		 *      "title": "New title",
		 *      "description": "New description"
		 * }
		 * </pre>
		 * <p>
		 * When an internal error occurs, a JSON response containing the
		 * corresponding message will be returned:
		 * <p>
		 * <pre>
		 * {
		 *      "status": "1",
		 *      "message": "Error message."
		 * }
		 * </pre>
		 *
		 * @param desc A JSON object containing description changes.
		 * @return A JSON object containing the result message.
		 */
		@PATCH
		@Path("/{envId}")
		@Secured(roles={Role.RESTRICTED})
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.APPLICATION_JSON)
		public Response update(@PathParam("envId") String envId, UpdateEnvironmentDescriptionRequest desc)
		{
			LOG.info("Updating environment '" + envId + "'...");

			boolean imported = false;

			if (envId == null)
				return EnvironmentRepository.errorMessageResponse("envId was null");

			final EmilEnvironment oldenv = emilEnvRepo.getEmilEnvironmentById(envId);
			if (oldenv == null)
				return EnvironmentRepository.errorMessageResponse("No emil environment found with ID: " + envId);

			EmilEnvironment newenv;

			try {
				final Environment environment = imagearchive.api()
						.v2()
						.environments()
						.fetch(envId);

				if (environment instanceof MachineConfiguration) {
					final MachineConfiguration machineConfiguration = (MachineConfiguration) environment;
					machineConfiguration.setOperatingSystemId(desc.getOs());
					if (desc.getNativeConfig() != null) {
						if (machineConfiguration.getNativeConfig() == null)
							machineConfiguration.setNativeConfig(new NativeConfig());
						machineConfiguration.getNativeConfig().setValue(desc.getNativeConfig());
					}

					if (machineConfiguration.getUiOptions() == null)
						machineConfiguration.setUiOptions(new UiOptions());

					final UiOptions uiopts = machineConfiguration.getUiOptions();
					if (desc.isUseXpra())
						uiopts.setForwarding_system("XPRA");
					else uiopts.setForwarding_system(null);

					if (desc.isUseWebRTC())
						uiopts.setAudio_system("webRTC");
					else uiopts.setAudio_system(null);

					if (uiopts.getHtml5() == null)
						uiopts.setHtml5(new Html5Options());

					// Do not check for null. EmuBean would choose latest, if value is null
					final EmulatorSpec emulator = machineConfiguration.getEmulator();
					if(desc.getContainerEmulatorName() != null)
						emulator.setContainerName(desc.getContainerEmulatorName());
					if(desc.getContainerEmulatorVersion() != null)
						emulator.setContainerVersion(desc.getContainerEmulatorVersion());

					uiopts.getHtml5().setPointerLock(desc.isEnableRelativeMouse());

					machineConfiguration.setDrive(desc.getDrives());
					driveUpdateHelper(machineConfiguration, desc.getDriveSettings(), objects);

					machineConfiguration.setLinuxRuntime(desc.isLinuxRuntime());

					if (desc.getNetworking() != null && desc.getNetworking().isEnableInternet()) {
						List<Nic> nics = machineConfiguration.getNic();
						if (nics.size() == 0) {
							Nic nic = new Nic();
							nic.setHwaddress(NetworkUtils.getRandomHWAddress());
							nics.add(nic);
						}
					}
				}



				environment.setUserTag(desc.getUserTag());
				if (!oldenv.getArchive().equals("default")) {
					// we need to import / duplicate the env
					if (oldenv instanceof EmilObjectEnvironment)
						newenv = new EmilObjectEnvironment(oldenv);
					else newenv = new EmilEnvironment(oldenv);

					newenv.setArchive("default");

					final var id = imagearchive.api()
							.v2()
							.environments()
							.insert(environment);

					newenv.setEnvId(id);
					newenv.setParentEnvId(oldenv.getEnvId());
					oldenv.addChildEnvId(newenv.getEnvId());
					imported = true;
				}
				else {
					final var options  = new ReplaceOptionsV2()
							.setLocation(oldenv.getArchive());

					imagearchive.api()
							.v2()
							.environments()
							.replace(environment.getId(), environment, options);

					newenv = oldenv;
				}

				imageProposer.refreshIndex();
			}
			catch (BWFLAException error) {
				LOG.log(Level.SEVERE, "Loading environment description failed!", error);
				return EnvironmentRepository.errorMessageResponse("No emulation environment found with ID: " + envId);
			}

			newenv.setTitle(desc.getTitle());
			newenv.setAuthor(desc.getAuthor());
			newenv.setOs(desc.getOs());
			newenv.setDescription(desc.getDescription());
			newenv.setEnablePrinting(desc.isEnablePrinting());
			newenv.setEnableRelativeMouse(desc.isEnableRelativeMouse());
			newenv.setShutdownByOs(desc.isShutdownByOs());
			newenv.setDisableGhostCursor(desc.isDisableGhostCursor());
			newenv.setNetworking(desc.getNetworking());
			newenv.setCanProcessAdditionalFiles(desc.canProcessAdditionalFiles());
			newenv.setXpraEncoding(desc.getXpraEncoding());
			newenv.setLinuxRuntime(desc.isLinuxRuntime());
			newenv.setHelpText(desc.getHelpText());

			if (desc.getTime() != null)
				newenv.setTimeContext(desc.getTime());
			else newenv.setTimeContext(null);

			try {
				if (imported) {
					// emilEnvRepo.save(currentEnv, false);
					emilEnvRepo.save(newenv, true);
				}
				else emilEnvRepo.save(newenv, false);
			}
			catch (BWFLAException error) {
				return EnvironmentRepository.internalErrorResponse(error);
			}

			final JsonObject json = Json.createObjectBuilder()
					.add("status", "0")
					.add("id", newenv.getEnvId())
					.build();

			return EnvironmentRepository.createResponse(Status.OK, json);
		}

		/** Delete a specific environment */
		@DELETE
		@Path("/{envId}")
		@Secured(roles={Role.RESTRICTED})
		@Produces(MediaType.APPLICATION_JSON)
		@Consumes(MediaType.APPLICATION_JSON)
		public Response delete(@PathParam("envId") String envId, EnvironmentDeleteRequest desc)
		{
			LOG.info("Deleting environment '" + envId + "'...");

			if (desc.getEnvId() == null || desc.getEnvId().trim().isEmpty()) {
				return EnvironmentRepository.errorMessageResponse("Emil environment ID is null or empty");
			}

			if (!desc.getDeleteMetaData() && !desc.getDeleteImage())
				return EnvironmentRepository.successMessageResponse("nothing to be deleted");

			List<String> objectDependencies = classification.getEnvironmentDependencies(desc.getEnvId());
			if (objectDependencies != null && objectDependencies.size() > 0 && !desc.isForce()) {
				final JsonObject json = EnvironmentRepository.newJsonObject("2", objectDependencies.toString());
				return EnvironmentRepository.createResponse(Status.OK, json);
			}

			try {
				emilEnvRepo.delete(desc.getEnvId(), desc.getDeleteMetaData(), desc.getDeleteImage());
			}
			catch (BWFLAException error) {
				return EnvironmentRepository.internalErrorResponse(error);
			}

			imageProposer.refreshIndex();
			return EnvironmentRepository.successMessageResponse("deleting environment was successful!");
		}

		/** Export the specified environment. */
		@POST
		@Path("/{envId}/export")
		@Secured(roles={Role.RESTRICTED})
		@Produces(MediaType.APPLICATION_JSON)
		public TaskStateResponse export(@PathParam("envId") String envId, ExportRequest exportRequest)
		{
			LOG.info("Exporting environment '" + envId + "'...");

			if (exportPath == null || exportPath.isEmpty())
				return new TaskStateResponse(new BWFLAException("Emil export is not configured "));

			ExportEnvironmentTask.ExportEnvironmentRequest request = new ExportEnvironmentTask.ExportEnvironmentRequest();
			request.exportFilePath = exportPath;
			request.envId = exportRequest.getEnvId();
			request.envHelper = envdb;
			request.archive = exportRequest.getArchive();
			request.environmentRepository  = emilEnvRepo;
			request.userCtx = EnvironmentRepository.this.getUserContext().clone();
			return new TaskStateResponse(taskManager.submitTask(new ExportEnvironmentTask(request)));
		}

		/** List all object dependencies of an environment */
		@GET
		@Secured(roles={Role.PUBLIC})
		@Path("/{envId}/object-deps")
		@Produces(MediaType.APPLICATION_JSON)
		public List<String> getObjectDependencies(@PathParam("envId") String envId)
		{
			LOG.info("Listing object-deps for environment '" + envId + "'...");

			if (envId == null || envId.trim().isEmpty()) {
				return new ArrayList<>();
			}

			return classification.getEnvironmentDependencies(envId);
		}

		@Path("/{envId}/revisions")
		public Revisions revisions(@PathParam("envId") String envId)
		{
			return new Revisions(envId);
		}

		private EnvironmentDetails addEnvironmentDetails(EmilEnvironment emilenv) throws BWFLAException
		{
			final Environment env = imagearchive.api()
					.v2()
					.environments()
					.fetch(emilenv.getEnvId());

			MachineConfiguration machine = (env instanceof MachineConfiguration) ? (MachineConfiguration) env : null;
			List<EmilEnvironment> parents = emilEnvRepo.getParents(emilenv.getEnvId());
			return new EnvironmentDetails(emilenv, machine, parents, swHelper);
		}

		private EnvironmentDetails addEnvironmentDetailsNoThrow(EmilEnvironment emilenv)
		{
			try {
				return this.addEnvironmentDetails(emilenv);
			}
			catch (Exception error) {
				LOG.warning("Collecting environment's details failed! " + error.getMessage());
				return null;
			}
		}
	}


	public class DefaultEnvironments
	{
		/** List all default environments */
		@GET
		@Secured(roles={Role.RESTRICTED})
		@Produces(MediaType.APPLICATION_JSON)
		public Map<String, String> list()
		{
			LOG.info("Listing default environments...");
			return defaultEnvs.getDefaultEnvironments();
		}

		/** Get configured default environment for a specific operating system ID */
		@GET
		@Path("/{osId}")
		@Produces(MediaType.APPLICATION_JSON)
		public DefaultEnvironmentResponse get(@PathParam("osId") String osId)
		{
			LOG.info("Looking up default environment for OS '" + osId + "'...");

			DefaultEnvironmentResponse response = new DefaultEnvironmentResponse();
			response.setEnvId(defaultEnvs.getDefaultEnvironment(osId));
			return response;
		}

		/** Set default environment for a specific operating system ID */
		@PATCH
		@Path("/{osId}")
		@Secured(roles={Role.RESTRICTED})
		@Produces(MediaType.APPLICATION_JSON)
		public EmilResponseType set(@PathParam("osId") String osId, @QueryParam("envId") String envId)
		{
			LOG.info("Setting default environment for OS '" + osId + "'...");

			try {
				defaultEnvs.setDefaultEnvironment(osId,envId);
				return new EmilResponseType();
			}
			catch (BWFLAException error) {
				return new EmilResponseType(error);
			}
		}
	}


	public class Revisions
	{
		private final String envId;

		protected Revisions(String envId)
		{
			this.envId = envId;
		}

		/** Create a new revision  */
		@POST
		@Secured(roles={Role.RESTRICTED})
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.APPLICATION_JSON)
		public Response create()
		{
			LOG.info("Creating revision for environment '" + envId + "'...");

			EmilEnvironment emilEnv = emilEnvRepo.getEmilEnvironmentById(envId);
			if (emilEnv == null)
				return EnvironmentRepository.internalErrorResponse("not found: " + envId);  // TODO: throw NotFoundException!

			try {
				final Environment environment = imagearchive.api()
						.v2()
						.environments()
						.fetch(envId);

				final String id = imagearchive.api()
						.v2()
						.environments()
						.insert(environment);

				EmilEnvironment newEmilEnv = new EmilEnvironment(emilEnv);
				newEmilEnv.setEnvId(id);
				newEmilEnv.setTitle("[fork]: " + newEmilEnv.getTitle() + " " + newEmilEnv.getEnvId());
				newEmilEnv.setArchive("default");
				newEmilEnv.setParentEnvId(emilEnv.getParentEnvId());
				emilEnvRepo.save(newEmilEnv, true);

				final var message = "Forked environment " + envId + " as " + id;
				final var json = EmilRest.newJsonObjectBuilder("0", message)
						.add("envId", id)
						.build();

				return EmilRest.createResponse(Status.OK, json);
			}
			catch (BWFLAException error) {
				return EnvironmentRepository.internalErrorResponse(error);
			}
		}

		@POST
		@Path("/{revId}")
		@Secured(roles={Role.RESTRICTED})
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.APPLICATION_JSON)
		public synchronized Response revert(@PathParam("revId") String revId)
		{
			if (envId == null || revId == null)
				return EnvironmentRepository.errorMessageResponse("Invalid Request");  // TODO: throw BadRequestException!

			EmilEnvironment currentEnv = emilEnvRepo.getEmilEnvironmentById(envId);
			if (currentEnv == null)  // TODO: throw NotFoundException!
				return EnvironmentRepository.errorMessageResponse("No emil environment found with ID: " + envId);

			LOG.info("Reverting environment '" + envId + "' to revison '" + revId + "'...");

			final List<String> emilEnvsToDelete = new ArrayList<>();
			try {
				EmilEnvironment parentEnv = null;
				while ((parentEnv = emilEnvRepo.getEmilEnvironmentById(currentEnv.getParentEnvId())) != null) {
					if (currentEnv.getEnvId().equals(revId))
						break;

					emilEnvsToDelete.add(currentEnv.getEnvId());
					emilEnvRepo.save(currentEnv, false);
					currentEnv = parentEnv;
				}

				if (!currentEnv.getEnvId().equals(revId))
					return EnvironmentRepository.errorMessageResponse("Reverting to revision '" + revId + "' failed!");

				emilEnvRepo.save(currentEnv, false);
				for (String id : emilEnvsToDelete)
					emilEnvRepo.delete(id, true, true);

				return EnvironmentRepository.successMessageResponse("Environment reverted to revision '" + revId + "'");
			}
			catch (BWFLAException error) {
				return EnvironmentRepository.errorMessageResponse("No emil environment found with ID: " + currentEnv.getParentEnvId());
			}
		}
	}


	public class Templates
	{
		@GET
		@Secured(roles={Role.RESTRICTED})
		@Produces(MediaType.APPLICATION_JSON)
		/**
		 *
		 * for old-style template use ?compat=... get parameter
		 */
		public Response list(@QueryParam("compat") @DefaultValue("newStyle") String compat)
		{
			LOG.info("Listing environment templates...");
			try {
				final var templates = imagearchive.api()
						.v2()
						.templates()
						.fetch();

				if(compat.equals("newStyle")) {
					return Response.status(Status.OK)
							.entity(templates)
							.build();
				}
				else {
					try (templates) {
						final StringWriter output = new StringWriter();
						final JsonGenerator json = Json.createGenerator(output);
						json.writeStartObject();
						json.write("status", "0");
						json.writeStartArray("systems");

						final Consumer<MachineConfiguration> writer = (machine) -> {
							json.writeStartObject();
							json.write("id", machine.getId());
							json.write("label", machine.getDescription().getTitle());
							if (machine.getNativeConfig() != null)
								json.write("native_config", machine.getNativeConfig().getValue());
							else json.write("native_config", "");

							json.writeStartArray("properties");
							if (machine.getArch() != null && !machine.getArch().isEmpty()) {
								json.writeStartObject();
								json.write("name", "Architecture");
								json.write("value", machine.getArch());
								json.writeEnd();
							}

							final String emubean = (machine.getEmulator() != null) ? machine.getEmulator().getBean() : null;
							if (emubean != null && !emubean.isEmpty()) {
								json.writeStartObject();
								json.write("name", "EmulatorContainer");
								json.write("value", emubean);
								json.writeEnd();
							}

							json.writeEnd();
							json.writeEnd();
						};

						templates.stream()
								.forEach(writer);

						json.writeEnd();
						json.writeEnd();
						json.flush();
						json.close();

						return EnvironmentRepository.createResponse(Status.OK, output.toString());
					}
					catch (Throwable error) {
						return EnvironmentRepository.internalErrorResponse(error);
					}
				}
			}
			catch (Throwable error) {
				return EnvironmentRepository.internalErrorResponse(error);
			}
		}
	}


	public class Patches
	{
		/** List all available image-generalization patches. */
		@GET
		@Secured(roles={Role.RESTRICTED})
		@Produces(MediaType.APPLICATION_JSON)
		public Streamable<ImageGeneralizationPatchDescription> list() throws BWFLAException
		{
			LOG.info("Listing image-generalization patches...");
			return imageGeneralization.api()
					.v1()
					.patches()
					.list();
		}

		/** Try to apply a patch to the specified image. */
		@POST
		@Path("/{patchId}")
		@Secured(roles={Role.RESTRICTED})
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.APPLICATION_JSON)
		public ImageGeneralizationPatchResponse apply(@PathParam("patchId") String patchId, ImageGeneralizationPatchRequest request)
				throws BWFLAException
		{
			LOG.info("Applying image-generalization patch...");
			return imageGeneralization.api()
					.v1()
					.patches()
					.apply(patchId, request);
		}
	}


	public class Actions
	{
		/** Initialize internal database of environments. */
		@POST
		@Path("/prepare")
		@Secured(roles={Role.PUBLIC})
		@Produces(MediaType.APPLICATION_JSON)
		public Response prepare()
		{
			LOG.info("Preparing environment-repository...");
			try {
				ServerLifecycleHooks.instance()
						.started()
						.get();

				return EnvironmentRepository.successMessageResponse("Preparing environment-repository finished!");
			}
			catch (Throwable t) {
				return EnvironmentRepository.internalErrorResponse(t);
			}
		}

		/** Rebuild archive storage indexes */
		@POST
		@Path("/sync")
		@Secured(roles={Role.RESTRICTED})
		@Produces(MediaType.APPLICATION_JSON)
		public Response sync() throws BWFLAException
		{
			LOG.info("Updating archive indexes...");
			imagearchive.api()
					.v2()
					.storage()
					.indexes()
					.rebuild();

			emilEnvRepo.init();
			return Emil.successMessageResponse("Archive indexes updated!");
		}


		/** create new image */
		@POST
		@Path("/create-image")
		@Secured(roles = {Role.RESTRICTED})
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.APPLICATION_JSON)
		public TaskStateResponse createImage(ImageCreateRequest imageReq)
		{
			LOG.info("Create empty image ...");

			return new TaskStateResponse(taskManager.submitTask(new CreateEmptyImageTask(imageReq.getSize(), LOG)));
		}

		/** Import an image for new environment */
		@POST
		@Path("/import-image")
		@Secured(roles={Role.RESTRICTED})
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.APPLICATION_JSON)
		public TaskStateResponse importImage(ImportImageRequest imageReq)
		{
			LOG.info("Importing image ...");

			ImportImageTaskRequest request = new ImportImageTaskRequest();

			URL url;
			try {
				url = new URL(imageReq.getUrl());
				if(url.getProtocol().equalsIgnoreCase("file"))
					return new TaskStateResponse((new BWFLAException("invalid url format")));
			} catch (MalformedURLException me) {
				String filename = imageReq.getUrl();
				if (filename == null || filename.contains("/"))
					return new TaskStateResponse(new BWFLAException("filename must not be null/empty or contain '/' characters: " + filename));
				File image = new File("/eaas/import/", filename);
				if (!image.exists())
					return new TaskStateResponse(new BWFLAException("image : " + filename + " not found."));

				try {
					url = image.toURI().toURL();
				} catch (MalformedURLException e) {
					return new TaskStateResponse(new BWFLAException(e));
				}
			}

			request.url = url;
			request.destArchive = "default";
			request.environmentHelper = envdb;
			request.imagearchive = imagearchive;
			request.label = imageReq.getLabel();

			if(imageReq.getImageType() != null && imageReq.getImageType().equalsIgnoreCase(ImageType.ROMS.value()))
				request.type = ImageType.ROMS;
			else if(imageReq.getImageType() != null && imageReq.getImageType().equalsIgnoreCase(ImageType.RUNTIME.value()))
				request.type = ImageType.RUNTIME;
			else
				request.type = ImageType.USER;

			try {
				request.validate();
			} catch (BWFLAException e) {
				e.printStackTrace();
				return new TaskStateResponse(e);
			}

			return new TaskStateResponse(taskManager.submitTask(new ImportImageTask(request, LOG)));
		}

		/** Replicate an image from remote archives */
		@POST
		@Path("/replicate-image")
		@Secured(roles={Role.RESTRICTED})
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.APPLICATION_JSON)
		public ReplicateImagesResponse replicateImage(ReplicateImagesRequest replicateImagesRequest)
		{
			LOG.info("Replicating remote image...");

			if (replicateImagesRequest.getReplicateList() == null)
				return new ReplicateImagesResponse(new BWFLAException("no environments given"));

			ReplicateImagesResponse response = new ReplicateImagesResponse();
			List<String> taskList = new ArrayList<String>();

			ReplicateImageTask.ReplicateImageTaskRequest importRequest = new ReplicateImageTask.ReplicateImageTaskRequest();

			for (String envId : replicateImagesRequest.getReplicateList()) {
				EmilEnvironment emilEnvironment = emilEnvRepo.getEmilEnvironmentById(envId);
				if (emilEnvironment == null) {
					LOG.severe("Environment " + envId + " not found");
					continue;
				}
				try {
					importRequest.env = imagearchive.api()
							.v2()
							.environments()
							.fetch(envId);

					importRequest.repository = emilEnvRepo;
					importRequest.emilEnvironment = emilEnvironment;
				}
				catch (Exception error) {
					LOG.log(Level.WARNING, "Looking up environment failed!", error);
				}

				importRequest.environmentHelper = envdb;
				importRequest.imagearchive = imagearchive;
				importRequest.destArchive = replicateImagesRequest.getDestArchive();
				importRequest.imageProposer = imageProposer;
				importRequest.userctx = getUserContext().clone();

				try {
					importRequest.validate();
				}
				catch (BWFLAException error) {
					LOG.log(Level.WARNING, "Validating image replication request failed!", error);
					return new ReplicateImagesResponse(error);
				}

				taskList.add(taskManager.submitTask(new ReplicateImageTask(importRequest, LOG)));
			}

			response.setTaskList(taskList);
			return response;
		}

		@POST
		@Path("/delete-image")
		@Secured(roles={Role.PUBLIC})
		@Consumes(MediaType.APPLICATION_JSON)
		@Produces(MediaType.APPLICATION_JSON)
		public Response deleteImage(DeleteImageRequest request) throws BWFLAException
		{
			LOG.info("Deleting image '" + request.getImageId() + "'...");
			imagearchive.api()
					.v2()
					.metadata(MetaDataKindV2.IMAGES)
					.delete(request.getImageId());

			// envdb.deleteImage(request.getImageArchive(), request.getImageId(), ImageType.USER);
			return Response.status(Status.OK)
					.build();
		}
	}

	// helper
	private static void driveUpdateHelper(MachineConfiguration env, List<EnvironmentCreateRequest.DriveSetting> driveSettings, EmilObjectData objects) throws BWFLAException {
		if(driveSettings == null)
			return;

		for (EnvironmentCreateRequest.DriveSetting ds : driveSettings) {
			if (ds.getObjectId() != null && ds.getObjectArchive() != null) {
				FileCollection fc = objects.getFileCollection(ds.getObjectArchive(), ds.getObjectId());
				ObjectArchiveBinding binding = new ObjectArchiveBinding(objects.helper().getHost(), ds.getObjectArchive(), ds.getObjectId());
				if (EmulationEnvironmentHelper.addObjectArchiveBinding(env, binding, fc, ds.getDriveIndex()) < 0)
					throw new BadRequestException(Response
							.status(Response.Status.BAD_REQUEST)
							.entity(new ErrorInformation("could not insert object"))
							.build());
			} else if (ds.getImageId() != null && ds.getImageArchive() != null) {
				ImageArchiveBinding binding = new ImageArchiveBinding(ds.getImageArchive(),
						ds.getImageId(),
						ImageType.USER.value());
				binding.setId(ds.getImageId());
				if(env.getAbstractDataResource().stream().noneMatch(b -> binding.getId().equals(b.getId())))
					env.getAbstractDataResource().add(binding);
				EmulationEnvironmentHelper.setDrive(env, ds.getDrive(), ds.getDriveIndex());
				if (EmulationEnvironmentHelper.registerDrive(env, binding.getId(), null, ds.getDriveIndex()) < 0)
					throw new BadRequestException(Response
							.status(Response.Status.BAD_REQUEST)
							.entity(new ErrorInformation("could not insert image"))
							.build());
			} else {
				EmulationEnvironmentHelper.registerEmptyDrive(env, ds.getDriveIndex());
			}
		}

		// cleanup unused resources
		for (Iterator<AbstractDataResource> it = env.getAbstractDataResource().iterator(); it.hasNext();) {
			AbstractDataResource resource = it.next();
			String bindingId = resource.getId();
			if(bindingId == null || bindingId.isEmpty()) {
				LOG.severe("empty binding");
				continue;
			}

			if(bindingId.startsWith("rom-"))
				continue;

			if(bindingId.equalsIgnoreCase("checkpoint") || bindingId.equalsIgnoreCase("emucon-rootfs"))
				continue;

			if(EmulationEnvironmentHelper.getDriveId(env, bindingId) >= 0)
				continue;

			it.remove();
		}
	}

	private UserContext getUserContext() {
		return (authenticatedUser != null) ? authenticatedUser : new UserContext();
	}

	@Override
	public void register(@Observes MigrationRegistry migrations) throws Exception
	{
		migrations.register("remove-published-duplicates-from-legacy-image-archive-v1", this::removePublishedDuplicatesFromLegacyImageArchiveV1);
		migrations.register("rebase-legacy-images-v1", this::rebaseLegacyImagesV1);
		migrations.register("import-legacy-image-index", this::importLegacyImageIndex);
		migrations.register("import-legacy-emulator-index", this::importLegacyEmulatorIndex);
		migrations.register("import-legacy-image-archive-v1", this::importLegacyImageArchiveV1);
		migrations.register("import-legacy-emulator-archive-v1", this::importLegacyEmulatorArchiveV1);
		migrations.register("fix-checkpointed-environments-v1", this::fixCheckpointedEnvironmentsV1);
		migrations.register("fix-object-environments-v1", this::fixObjectEnvironmentsV1);
	}

	private void removePublishedDuplicatesFromLegacyImageArchiveV1(MigrationConfig mc) throws Exception
	{
		class LegacyImageArchive
		{
			private final String name;
			private final Map<String, java.nio.file.Path> images;
			private final Map<String, java.nio.file.Path> metadata;

			public LegacyImageArchive(String name)
			{
				this.name = name;
				this.images = new HashMap<>();
				this.metadata = new HashMap<>();
			}

			public boolean remove(String id, Map<String, java.nio.file.Path> entries) throws IOException
			{
				final var path = entries.remove(id);
				if (path == null)
					return false;

				final var result = Files.deleteIfExists(path);
				if (result)
					LOG.info("Removed duplicate at: " + path);

				return result;
			}
		}

		final var archives = new HashMap<String, LegacyImageArchive>();
		for (var iterator = new LegacyImageArchiveConfigIterator(); iterator.hasNext();) {
			final var config = iterator.next();
			final var name = config.get("name");
			if (name.contentEquals("emulators"))
				continue;

			final var basedir = Paths.get(config.get("basepath"));
			final var archive = new LegacyImageArchive(name);

			LOG.info("Listing entries in legacy image-archive (" + name + ")...");
			for (final var kind : LegacyImageArchiveUtils.ImageKind.values()) {
				LegacyImageArchiveUtils.list(basedir.resolve("meta-data"), kind, archive.metadata);
				LegacyImageArchiveUtils.list(basedir.resolve("images"), kind, archive.images);
			}

			archives.put(name, archive);
		}

		final var counter = ImportCounts.counter();

		final BiConsumer<String, java.nio.file.Path> metadataDuplicateRemover = (id, path) -> {
			for (final var archive : archives.values()) {
				try {
					if (archive.remove(id, archive.metadata))
						counter.increment(ImportCounts.IMPORTED);
				}
				catch (Exception error) {
					LOG.log(Level.WARNING, "Removing metadata duplicate of '" + id + "' failed!", error);
					counter.increment(ImportCounts.FAILED);
				}
			}
		};

		final BiConsumer<String, java.nio.file.Path> imageDuplicateRemover = (id, path) -> {
			for (final var archive : archives.values()) {
				try {
					if (archive.remove(id, archive.images))
						counter.increment(ImportCounts.IMPORTED);
				}
				catch (Exception error) {
					LOG.log(Level.WARNING, "Removing image duplicate of '" + id + "' failed!", error);
					counter.increment(ImportCounts.FAILED);
				}
			}
		};

		LOG.info("Removing published duplicates in legacy image-archive...");
		final var publicImageArchive = archives.remove("public");
		publicImageArchive.metadata.forEach(metadataDuplicateRemover);
		publicImageArchive.images.forEach(imageDuplicateRemover);

		final var numRemoved = counter.get(ImportCounts.IMPORTED);
		final var numFailed = counter.get(ImportCounts.FAILED);
		final var maxFailureRate = MigrationUtils.getFailureRate(mc);
		LOG.info("Removed " + numRemoved + " duplicate(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numRemoved + numFailed, numFailed, maxFailureRate))
			throw new BWFLAException("Removing published duplicates failed!");
	}

	private void rebaseLegacyImagesV1(MigrationConfig mc) throws Exception
	{
		final var images = new HashMap<String, java.nio.file.Path>();
		for (var iterator = new LegacyImageArchiveConfigIterator(); iterator.hasNext();) {
			final var config = iterator.next();
			final var name = config.get("name");
			final var basedir = Paths.get(config.get("basepath"), "images");
			LOG.info("Listing images in legacy image-archive (" + name + ")...");
			for (final var kind : LegacyImageArchiveUtils.ImageKind.values())
				LegacyImageArchiveUtils.list(basedir, kind, images);
		}

		// bfmap: image-id -> backing-file-id
		final var bfmap = new ConcurrentHashMap<String, String>();
		final var failedImageIds = new HashSet<String>();
		final var counter = ImportCounts.counter();

		final Consumer<String> rebaser = (id) -> {
			try {
				LegacyImageArchiveUtils.fixBackingFileRef(id, images, bfmap, LOG);
				counter.increment(ImportCounts.IMPORTED);
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Rebasing image '" + id + "' failed!", error);
				counter.increment(ImportCounts.FAILED);
				failedImageIds.add(id);
			}
		};

		LOG.info("Rebasing images in legacy image-archive...");
		final var imageids = images.keySet();
		ParallelProcessors.consumer(rebaser)
				.consume(imageids.iterator(), executor);

		final var numRebased = counter.get(ImportCounts.IMPORTED);
		final var numFailed = counter.get(ImportCounts.FAILED);
		final var maxFailureRate = MigrationUtils.getFailureRate(mc);
		LOG.info("Rebased " + numRebased + " image(s), failed " + numFailed);
		if (!failedImageIds.isEmpty())
			LOG.warning(LegacyImageArchiveUtils.summarize(bfmap, failedImageIds));

		if (!MigrationUtils.acceptable(numRebased + numFailed, numFailed, maxFailureRate))
			throw new BWFLAException("Rebasing legacy images failed!");
	}

	private void importLegacyImageIndex(MigrationConfig mc) throws BWFLAException
	{
		final var index = envdb.getImagesIndex();
		final var entries = index.getEntries();
		if (entries == null)
			return;

		int numImported = 0, numFailed = 0;

		LOG.info("Importing legacy image-index...");
		for (var entry : entries.getEntry()) {
			final var srcmd = entry.getValue();
			final var srcimg = srcmd.getImage();
			final var image = new ImageMetaData()
					.setId(srcimg.getId())
					.setFileSystemType(srcimg.getFstype())
					.setCategory(srcimg.getType())
					.setLabel(srcmd.getLabel());

			try {
				imagearchive.api()
						.v2()
						.metadata(MetaDataKindV2.IMAGES)
						.replace(image.id(), image, ImageArchiveMappers.OBJECT_TO_JSON_TREE);

				LOG.info("Imported metadata for image '" + image.id() + "'");
				envdb.deleteNameIndexesEntry(image.id(), null);
				++numImported;
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Importing metadata for image '" + image.id() + "' failed!", error);
				++numFailed;
			}
		}

		LOG.info("Imported metadata for " + numImported + " image(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numImported + numFailed, numFailed, MigrationUtils.getFailureRate(mc)))
			throw new BWFLAException("Importing legacy image-index failed!");
	}

	private void importLegacyEmulatorIndex(MigrationConfig mc) throws BWFLAException
	{
		final var index = envdb.getNameIndexes();
		final var entries = index.getEntries();
		if (entries == null)
			return;

		final var defaultEmulatorIds = new HashSet<String>();
		final var aliases = index.getAliases();
		if (aliases != null) {
			for (var entry : aliases.getEntry()) {
				final var value = entry.getValue();
				if ("latest".equals(value.getAlias())) {
					final var name = EmulatorSpec.stripLegacyNamePrefix(value.getName());
					defaultEmulatorIds.add(EmulatorMetaData.identifier(name, value.getVersion()));
				}
			}
		}

		int numImported = 0, numFailed = 0;

		LOG.info("Importing legacy emulator-index...");
		final var emuMetaHelper = new EmulatorMetaHelperV2(imagearchive, LOG);
		for (var entry : entries.getEntry()) {
			final var srcmd = entry.getValue();
			final var emulator = new EmulatorMetaData()
					.setName(EmulatorSpec.stripLegacyNamePrefix(srcmd.getName()))
					.setVersion(srcmd.getVersion())
					.setDigest(srcmd.getDigest());

			final var srcprov = srcmd.getProvenance();
			emulator.provenance()
					.setUrl(srcprov.getOciSourceUrl())
					.setTag(srcprov.getVersionTag())
					.setLayers(srcprov.getLayers());

			final var srcimg = srcmd.getImage();
			emulator.image()
					.setId(srcimg.getId())
					.setCategory(srcimg.getType())
					.setFileSystemType(srcimg.getFstype());

			if (defaultEmulatorIds.contains(emulator.id())) {
				emulator.tags()
						.add(EmulatorMetaData.DEFAULT_VERSION);
			}

			final var emuname = "'" + emulator.name() + " (" + emulator.version() + ")'";
			try {
				emuMetaHelper.insert(emulator);
				LOG.info("Imported metadata for emulator " + emuname);
				final var backend = DatabaseEnvironmentsAdapter.EMULATOR_DEFAULT_ARCHIVE;
				envdb.deleteNameIndexesEntry(backend, srcmd.getName(), srcmd.getVersion());
				++numImported;
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Importing metadata for emulator " + emuname + " failed!", error);
				++numFailed;
			}
		}

		LOG.info("Imported metadata for " + numImported + " emulator(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numImported + numFailed, numFailed, MigrationUtils.getFailureRate(mc)))
			throw new BWFLAException("Importing legacy emulator-index failed!");
	}

	private void importLegacyImageArchiveV1(MigrationConfig mc) throws Exception
	{
		for (var iterator = new LegacyImageArchiveConfigIterator(); iterator.hasNext();) {
			final var config = iterator.next();
			final var name = config.get("name");
			if (name.equals("emulators"))
				continue;

			final var basedir = Paths.get(config.get("basepath"));
			final var maxFailureRate = MigrationUtils.getFailureRate(mc);

			LOG.info("Importing legacy image-archive (" + name + ")...");
			this.importLegacyEnvironmentsV1(basedir, name, maxFailureRate);
			this.importLegacyImagesV1(basedir, name, maxFailureRate);
			this.importLegacyBlobsV1(basedir, name, maxFailureRate);
		}
	}

	private void importLegacyEnvironmentsV1(java.nio.file.Path basedir, String location, float maxFailureRate)
			throws Exception
	{
		final var kinds = new String[] {
				"base",
				"containers",
				"derivate",
				"object",
				"user",
		};

		basedir = basedir.resolve("meta-data");

		final var counter = ImportCounts.counter();
		for (final var kind : kinds)
			this.importLegacyEnvironmentsV1(basedir, location, kind, counter);

		final var numImported = counter.get(ImportCounts.IMPORTED);
		final var numFailed = counter.get(ImportCounts.FAILED);
		LOG.info("Imported " + numImported + " environment(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numImported + numFailed, numFailed, maxFailureRate))
			throw new BWFLAException("Importing legacy environments failed!");
	}

	private void importLegacyEnvironmentsV1(java.nio.file.Path basedir, String location,
											String kind, MultiCounter counter)
			throws Exception
	{
		final var srcdir = basedir.resolve(kind);
		if (!Files.exists(srcdir)) {
			LOG.info("No " + kind + "-environments found!");
			return;
		}

		final var options = new ReplaceOptionsV2()
				.setLocation(location);

		final var environments = imagearchive.api()
				.v2()
				.environments();

		final Consumer<java.nio.file.Path> importer = (file) -> {
			try {
				// simply import metadata from legacy archive...
				final var env = Environment.fromValue(Files.readString(file));
				List<AbstractDataResource> resources = null;
				if (env instanceof MachineConfiguration)
					resources = ((MachineConfiguration) env).getAbstractDataResource();
				else if (env instanceof ContainerConfiguration)
					resources = ((ContainerConfiguration) env).getDataResources();

				if (resources != null) {
					resources.forEach((resource) -> {
						if (resource instanceof ImageArchiveBinding) {
							final var binding = (ImageArchiveBinding) resource;
							binding.setBackendName(null);
							binding.setUrl(null);
						}
					});
				}

				environments.replace(env.getId(), env, options);
				counter.increment(ImportCounts.IMPORTED);
				LOG.info("Imported environment '" + env.getId() + "'");

				Files.delete(file);
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Importing environment '" + file.getFileName() + "' failed!", error);
				counter.increment(ImportCounts.FAILED);
			}
		};

		final Predicate<java.nio.file.Path> filter = (file) -> !Files.isDirectory(file);

		try (final var files = Files.list(srcdir)) {
			ParallelProcessors.consumer(filter, importer)
					.consume(files, executor);
		}
	}

	private void importLegacyImagesV1(java.nio.file.Path basedir, String location, float maxFailureRate)
			throws Exception
	{
		final var kinds = new String[] {
				"base",
				"containers",
				"derivate",
				"object",
				"user",
		};

		basedir = basedir.resolve("images");

		final var counter = ImportCounts.counter();
		for (final var kind : kinds)
			this.importLegacyImagesV1(basedir, location, kind, counter);

		final var numImported = counter.get(ImportCounts.IMPORTED);
		final var numFailed = counter.get(ImportCounts.FAILED);
		LOG.info("Imported " + numImported + " image(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numImported + numFailed, numFailed, maxFailureRate))
			throw new BWFLAException("Importing legacy images failed!");
	}

	private void importLegacyImagesV1(java.nio.file.Path basedir, String location,
									  String kind, MultiCounter counter)
			throws Exception
	{
		final var srcdir = basedir.resolve(kind);
		if (!Files.exists(srcdir)) {
			LOG.info("No " + kind + "-images found!");
			return;
		}

		final var options = new ReplaceOptionsV2()
				.setLocation(location);

		final var images = imagearchive.api()
				.v2()
				.images();

		final Consumer<java.nio.file.Path> importer = (file) -> {
			final var id = file.getFileName().toString();
			// first, check backing file reference
			try {
				final var info = new ImageInformation(file.toString(), LOG);
				if (info.hasBackingFile()) {
					final var backingFileUrl = info.getBackingFile();
					final var backingImageId = ImageInformation.getBackingImageId(backingFileUrl);
					final var backingFileFormat = info.getBackingFileFormat();
					assert backingFileUrl.equals(backingImageId);
					assert backingFileFormat != null;
				}
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Backing file reference for image '" + id + "' is invalid!", error);
				counter.increment(ImportCounts.FAILED);
				return;
			}

			// then, import (possibly rebased) image directly
			try (final var image = Files.newInputStream(file)) {
				images.replace(id, image, options);
				counter.increment(ImportCounts.IMPORTED);
				LOG.info("Imported image '" + id + "'");

				Files.delete(file);
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Importing image '" + id + "' failed!", error);
				counter.increment(ImportCounts.FAILED);
			}
		};

		final Predicate<java.nio.file.Path> filter = (file) -> !Files.isDirectory(file);

		try (final var files = Files.list(srcdir)) {
			ParallelProcessors.consumer(filter, importer)
					.consume(files, executor);
		}
	}

	private void importLegacyBlobsV1(java.nio.file.Path basedir, String location, float maxFailureRate)
			throws Exception
	{
		final var api = imagearchive.api()
				.v2();

		basedir = basedir.resolve("images");

		this.importLegacyBlobsV1(basedir, location, "rom", api.roms(), maxFailureRate);
		this.importLegacyBlobsV1(basedir, location, "checkpoint", api.checkpoints(), maxFailureRate);
	}

	private void importLegacyBlobsV1(java.nio.file.Path basedir, String location, String kind,
									 RemoteResourceRW<InputStream,?> api, float maxFailureRate)
			throws Exception
	{
		final var srcdir = basedir.resolve(kind + "s");
		if (!Files.exists(srcdir)) {
			LOG.info("No " + kind + "s found!");
			return;
		}

		final var options = new ReplaceOptionsV2()
				.setLocation(location);

		final var counter = ImportCounts.counter();

		final Consumer<java.nio.file.Path> importer = (file) -> {
			final var id = file.getFileName().toString();
			try (final var blob = Files.newInputStream(file)) {
				api.replace(id, blob, options);
				counter.increment(ImportCounts.IMPORTED);
				LOG.info("Imported " + kind + " '" + id + "'");

				Files.delete(file);
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Importing " + kind + " '" + id + "' failed!", error);
				counter.increment(ImportCounts.FAILED);
			}
		};

		final Predicate<java.nio.file.Path> filter = (file) -> !Files.isDirectory(file);

		try (final var files = Files.list(srcdir)) {
			ParallelProcessors.consumer(filter,importer)
					.consume(files, executor);
		}

		final var numImported = counter.get(ImportCounts.IMPORTED);
		final var numFailed = counter.get(ImportCounts.FAILED);
		LOG.info("Imported " + numImported + " " + kind + "(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numImported + numFailed, numFailed, maxFailureRate))
			throw new BWFLAException("Importing legacy " + kind + "s failed!");
	}

	private void importLegacyEmulatorArchiveV1(MigrationConfig mc) throws Exception
	{
		for (var iterator = new LegacyImageArchiveConfigIterator(); iterator.hasNext();) {
			final var config = iterator.next();
			final var name = config.get("name");
			if (!name.equals("emulators"))
				continue;

			final var maxFailureRate = MigrationUtils.getFailureRate(mc);
			final var basedir = Paths.get(config.get("basepath"));

			LOG.info("Importing legacy emulator-archive...");
			this.importLegacyEmulatorImagesV1(basedir, maxFailureRate);
			this.importLegacyEmulatorTemplatesV1(basedir, maxFailureRate);
		}
	}

	private void importLegacyEmulatorImagesV1(java.nio.file.Path basedir, float maxFailureRate) throws Exception
	{
		basedir = basedir.resolve("images");

		final var counter = ImportCounts.counter();
		this.importLegacyEmulatorImagesV1(basedir, "base", counter);

		final var numImported = counter.get(ImportCounts.IMPORTED);
		final var numFailed = counter.get(ImportCounts.FAILED);
		LOG.info("Imported " + numImported + " emulator-image(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numImported + numFailed, numFailed, maxFailureRate))
			throw new BWFLAException("Importing legacy emulator-images failed!");
	}

	private void importLegacyEmulatorImagesV1(java.nio.file.Path basedir, String kind, MultiCounter counter)
			throws Exception
	{
		final var srcdir = basedir.resolve(kind);
		if (!Files.exists(srcdir)) {
			LOG.info("No emulator-images (" + kind + ") found!");
			return;
		}

		final var images = imagearchive.api()
				.v2()
				.emulators();

		final Consumer<java.nio.file.Path> importer = (file) -> {
			final var id = file.getFileName().toString();
			// first, check backing file reference
			try {
				final var info = new ImageInformation(file.toString(), LOG);
				if (info.hasBackingFile()) {
					final var backingFileUrl = info.getBackingFile();
					final var backingImageId = ImageInformation.getBackingImageId(backingFileUrl);
					final var backingFileFormat = info.getBackingFileFormat();
					assert backingFileUrl.equals(backingImageId);
					assert backingFileFormat != null;
				}
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Backing file reference for emulator-image '" + id + "' is invalid!", error);
				counter.increment(ImportCounts.FAILED);
				return;
			}

			// then, import (possibly rebased) image directly
			try (final var image = Files.newInputStream(file)) {
				images.replace(id, image);
				counter.increment(ImportCounts.IMPORTED);
				LOG.info("Imported emulator-image '" + id + "'");

				Files.delete(file);
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Importing emulator-image '" + id + "' failed!", error);
				counter.increment(ImportCounts.FAILED);
			}
		};

		final Predicate<java.nio.file.Path> filter = (file) -> !Files.isDirectory(file);

		try (final var files = Files.list(srcdir)) {
			ParallelProcessors.consumer(filter, importer)
					.consume(files, executor);
		}
	}

	private void importLegacyEmulatorTemplatesV1(java.nio.file.Path basedir, float maxFailureRate) throws Exception
	{
		basedir = basedir.resolve("meta-data");

		final var counter = ImportCounts.counter();
		this.importLegacyEmulatorTemplatesV1(basedir, "template", counter);

		final var numImported = counter.get(ImportCounts.IMPORTED);
		final var numFailed = counter.get(ImportCounts.FAILED);
		LOG.info("Imported " + numImported + " emulator-template(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numImported + numFailed, numFailed, maxFailureRate))
			throw new BWFLAException("Importing legacy emulator-templates failed!");
	}

	private void importLegacyEmulatorTemplatesV1(java.nio.file.Path basedir, String kind, MultiCounter counter)
			throws Exception
	{
		final var srcdir = basedir.resolve(kind);
		if (!Files.exists(srcdir)) {
			LOG.info("No emulator-templates (" + kind + ") found!");
			return;
		}

		final var templates = imagearchive.api()
				.v2()
				.templates();

		final Consumer<java.nio.file.Path> importer = (file) -> {
			try {
				// simply import metadata from legacy archive...
				final var env = Environment.fromValue(Files.readString(file));
				if (!(env instanceof MachineConfigurationTemplate))
					return;

				final var template = (MachineConfigurationTemplate) env;
				final var resources = template.getAbstractDataResource();
				if (resources != null) {
					resources.forEach((resource) -> {
						if (resource instanceof ImageArchiveBinding) {
							final var binding = (ImageArchiveBinding) resource;
							binding.setBackendName(null);
							binding.setUrl(null);
						}
					});
				}

				templates.replace(template.getId(), template);
				counter.increment(ImportCounts.IMPORTED);
				LOG.info("Imported emulator-template '" + template.getId() + "'");

				Files.delete(file);
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Importing emulator-template '" + file.getFileName() + "' failed!", error);
				counter.increment(ImportCounts.FAILED);
			}
		};

		final Predicate<java.nio.file.Path> filter = (file) -> !Files.isDirectory(file);

		try (final var files = Files.list(srcdir)) {
			ParallelProcessors.consumer(filter, importer)
					.consume(files, executor);
		}
	}

	private void fixCheckpointedEnvironmentsV1(MigrationConfig mc) throws Exception
	{
		// NOTE: legacy code always just appended new checkpoint-binding to the list, without removing
		//       any existing checkpoint references. Hence, checkpointing an environment restored from
		//       a checkpoint resulted in all previous checkpoint references left over. Since the order
		//       in the list is stable between de/serializations of metadata, only each last checkpoint
		//       binding is valid and should be kept, but all others must be removed!

		final var counter = ImportCounts.counter();
		final var environments = imagearchive.api()
				.v2()
				.environments();

		final Consumer<Environment> fixer = (env) -> {
			try {
				final var machine = (MachineConfiguration) env;
				if (!machine.hasCheckpointBindingId())
					return;  // not checkpointed!

				final var checkpoints = new ArrayList<String>();
				final var checkpointBindingId = machine.getCheckpointBindingId();
				final var resources = machine.getAbstractDataResource();
				for (final var resource : resources) {
					if (checkpointBindingId.equals(resource.getId()))
						checkpoints.add(((ImageArchiveBinding) resource).getImageId());
				}

				if (checkpoints.size() == 1)
					return;  // old checkpoints not found!

				if (checkpoints.isEmpty())
					throw new IllegalStateException("Checkpoint's binding not found!");

				// ignore latest valid checkpoint
				checkpoints.remove(checkpoints.size() - 1);

				final Predicate<AbstractDataResource> isOldCheckpoint = (r) -> (r instanceof ImageArchiveBinding)
						&& checkpoints.contains(((ImageArchiveBinding) r).getImageId());

				// remove all old checkpoint-bindings
				resources.removeIf(isOldCheckpoint);
				environments.replace(machine.getId(), machine);
				counter.increment(ImportCounts.IMPORTED);

				final var message = "Removed " + checkpoints.size() + " old checkpoint(s) from environment '"
						+ env.getId() + "': " + String.join(", ", checkpoints);

				LOG.info(message);
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Removing old checkpoints from environment '" + env.getId() + "' failed!", error);
				counter.increment(ImportCounts.FAILED);
			}
		};

		final Predicate<Environment> filter = (env) -> (env instanceof MachineConfiguration);

		LOG.info("Fixing checkpointed environments...");
		try (final var envs = environments.fetch()) {
			ParallelProcessors.consumer(filter, fixer)
					.consume(envs.iterator(), executor);
		}

		final var numFixed = counter.get(ImportCounts.IMPORTED);
		final var numFailed = counter.get(ImportCounts.FAILED);
		final var maxFailureRate = MigrationUtils.getFailureRate(mc);
		LOG.info("Fixed " + numFixed + " checkpointed environment(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numFixed + numFailed, numFailed, maxFailureRate))
			throw new BWFLAException("Fixing checkpointed environments failed!");
	}

	private void fixObjectEnvironmentsV1(MigrationConfig mc) throws Exception
	{
		final var counter = ImportCounts.counter();
		final var machines = imagearchive.api()
				.v2()
				.machines();

		final var objectArchiveAddress = ConfigurationProvider.getConfiguration()
				.get("ws.objectarchive");

		final var objects = new ObjectArchiveHelper(objectArchiveAddress);
		final var replacements = Arrays.asList("", "-");

		// initialize connection
		objects.getArchives();

		final Consumer<MachineConfiguration> fixer = (machine) -> {
			try {
				final var resources = machine.getAbstractDataResource();
				final var updates = new ArrayList<String>();

				// check all object-archive bindings...
				for (final var resource : resources) {
					if (!(resource instanceof ObjectArchiveBinding))
						continue;

					final var binding = (ObjectArchiveBinding) resource;
					final var driveDataRefPrefix = "binding://" + binding.getId();
					final var object = objects.getObjectReference(binding.getArchive(), binding.getObjectId());

					// find corresponding object-reference in available drives...
					for (final var drive : machine.getDrive()) {
						final var driveDataRef = drive.getData();
						if (driveDataRef == null || driveDataRef.isEmpty())
							continue;

						if (!driveDataRef.startsWith(driveDataRefPrefix))
							continue;

						// legacy references contained filenames, but now file-ids have to be used instead
						if (driveDataRef.startsWith("FID-", driveDataRefPrefix.length() + 1))
							break;  // looks like a file-id is already used

						// NOTE: some legacy filenames can contain URL unsafe chars (e.g. " ").
						//       These are expected to be fixed by a related migration in object-archive,
						//       hence multiple filename variants have to be considered here too!
						final var filename = driveDataRef.substring(driveDataRefPrefix.length() + 1);
						final var isFilenameValid = !filename.contains(" ");
						var isFilenameFound = false;

						// find and replace a filename with a corresponding file-id...
						for (final var file : object.files) {
							final var url = file.getUrl();
							if (isFilenameValid) {
								if (url.endsWith(filename))
									isFilenameFound = true;
							}
							else {
								// check filename variants...
								for (final var replacement : replacements) {
									if (url.endsWith(filename.replace(" ", replacement))) {
										isFilenameFound = true;
										break;
									}
								}
							}

							if (isFilenameFound) {
								drive.setData(driveDataRefPrefix + "/" + file.getId());
								updates.add("Replaced object-reference: " + driveDataRef + " -> " + drive.getData());
								break;
							}
						}

						if (!isFilenameFound)
							throw new IllegalStateException("Referenced object not found: " + driveDataRef);
					}
				}

				if (updates.isEmpty())
					return;

				machines.replace(machine.getId(), machine);
				counter.increment(ImportCounts.IMPORTED);

				final var message = "Fixed " + updates.size() + " object-reference(s) in environment '"
						+ machine.getId() + "':\n    " + String.join("\n    ", updates);

				LOG.info(message);
			}
			catch (Exception error) {
				LOG.log(Level.WARNING, "Fixing object-references in environment '" + machine.getId() + "' failed!", error);
				counter.increment(ImportCounts.FAILED);
			}
		};

		LOG.info("Fixing object-environments...");
		try (final var envs = machines.fetch()) {
			ParallelProcessors.consumer(fixer)
					.consume(envs.iterator(), executor);
		}

		final var numFixed = counter.get(ImportCounts.IMPORTED);
		final var numFailed = counter.get(ImportCounts.FAILED);
		final var maxFailureRate = MigrationUtils.getFailureRate(mc);
		LOG.info("Fixed " + numFixed + " object-environment(s), failed " + numFailed);
		if (!MigrationUtils.acceptable(numFixed + numFailed, numFailed, maxFailureRate))
			throw new BWFLAException("Fixing object-environments failed!");
	}
}
