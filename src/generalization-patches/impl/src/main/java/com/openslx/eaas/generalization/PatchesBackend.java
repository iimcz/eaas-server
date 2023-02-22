package com.openslx.eaas.generalization;


import com.openslx.eaas.imagearchive.ImageArchiveClient;
import com.openslx.eaas.imagearchive.ImageArchiveMappers;
import com.openslx.eaas.imagearchive.api.v2.common.ReplaceOptionsV2;
import com.openslx.eaas.imagearchive.api.v2.databind.ImportRequestV2;
import com.openslx.eaas.imagearchive.api.v2.databind.ImportTargetV2;
import com.openslx.eaas.imagearchive.api.v2.databind.MetaDataKindV2;
import com.openslx.eaas.imagearchive.databind.ImageMetaData;
import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.common.services.security.Role;
import de.bwl.bwfla.common.services.security.Secured;
import org.apache.commons.lang3.StringUtils;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URL;
import java.util.logging.Logger;
import java.util.stream.Collectors;


@ApplicationScoped
@Path("/api/v1")
public class PatchesBackend
{
	private final Logger log = Logger.getLogger(PatchesBackend.class.getName());


	@Inject
	private ImageGeneralizationPatches patches;

	private ImageArchiveClient imagearchive;


	public PatchesBackend()
	{
		log.info("Initializing Patches Backend....");

		try {
			imagearchive = ImageArchiveClient.create();
		}
		catch (Exception error) {
			throw new RuntimeException(error);
		}
	}

	@Secured(roles = {Role.PUBLIC})
	@GET
	@Path("/patches")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getPatches()
	{
		var allPatches = patches.list()
				.stream()
				.map((entry) -> new ImageGeneralizationPatchDescription(entry.getName(), entry.getDescription()))
				.collect(Collectors.toList());

		return Response.ok(allPatches).build();
	}

	@POST
	@Path("/{patchId}")
	@Secured(roles = {Role.PUBLIC})
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public Response apply(@PathParam("patchId") String patchId, ImageGeneralizationPatchRequest request)
	{
		log.info("Applying image-generalization patch...");
		try {
			final var origImage = imagearchive.api()
					.v2()
					.metadata(MetaDataKindV2.IMAGES)
					.fetch(request.getImageId(), ImageArchiveMappers.JSON_TREE_TO_IMAGE_METADATA);

			final String newImageId = createPatchedImage(request.getImageId(), request.getImageType().value(), patchId);

			final var newImage = new ImageMetaData()
					.setId(newImageId)
					.setFileSystemType(origImage.fileSystemType())
					.setLabel(origImage.label() + " (generalized)")
					.setCategory(request.getImageType().value());

			final var options = new ReplaceOptionsV2()
					.setLocation(request.getArchive());

			imagearchive.api()
					.v2()
					.metadata(MetaDataKindV2.IMAGES)
					.replace(newImageId, newImage, ImageArchiveMappers.OBJECT_TO_JSON_TREE, options);

			final var response = new ImageGeneralizationPatchResponse();
			response.setStatus("0");
			response.setImageId(newImageId);
			return Response.ok()
					.entity(response)
					.build();

		}
		catch (BWFLAException e) {
			throw new RuntimeException(e);
		}

	}

	public String createPatchedImage(String imageId, String type, String patchId) throws BWFLAException
	{
		if (patchId == null)
			throw new BWFLAException("Invalid image-generalization patch ID!");

		return createPatchedImage(imageId, type, patches.lookup(patchId));
	}


	protected String createPatchedImage(String parentId, String type, ImageGeneralizationPatch patch)
			throws BWFLAException
	{
		if (parentId == null)
			throw new BWFLAException("Invalid image's ID!");

		//TODO type is ignore here, as the image is simply imported, is the field necessary?

		var resolved = imagearchive.api()
				.v2()
				.images()
				.resolve(parentId);

		var newBackingFile = StringUtils.substringBefore(resolved, "?"); //TODO why does this work but 403 with the presigned url?

		try {
			log.info("Preparing image '" + parentId + "' for patching with patch '" + patch.getName() + "'...");
			var newFileName = java.util.UUID.randomUUID().toString();
			URL urlToQcow = patch.applyto(newBackingFile, newFileName, log);

			final var request = new ImportRequestV2();
			request.source()
					.setUrl(String.valueOf(urlToQcow));

			request.target()
					.setKind(ImportTargetV2.Kind.IMAGE)
					.setName(newFileName);

			imagearchive.api()
					.v2()
					.imports()
					.insert(request);

			log.info("finished patching. new image id " + newFileName);
			return newFileName;
		}
		catch (BWFLAException error) {
			throw new BWFLAException(error);
		}

	}

}





