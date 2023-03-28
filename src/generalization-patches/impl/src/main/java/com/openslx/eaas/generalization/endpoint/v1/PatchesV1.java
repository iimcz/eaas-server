package com.openslx.eaas.generalization.endpoint.v1;


import com.openslx.eaas.common.databind.Streamable;
import com.openslx.eaas.generalization.ImageGeneralizationPatch;
import com.openslx.eaas.generalization.ImageGeneralizationPatchDescription;
import com.openslx.eaas.generalization.ImageGeneralizationPatchRequest;
import com.openslx.eaas.generalization.ImageGeneralizationPatchResponse;
import com.openslx.eaas.generalization.ImageGeneralizationPatches;
import com.openslx.eaas.generalization.api.v1.IPatchesV1;
import com.openslx.eaas.imagearchive.ImageArchiveClient;
import com.openslx.eaas.imagearchive.ImageArchiveMappers;
import com.openslx.eaas.imagearchive.api.v2.common.ReplaceOptionsV2;
import com.openslx.eaas.imagearchive.api.v2.databind.ImportRequestV2;
import com.openslx.eaas.imagearchive.api.v2.databind.ImportTargetV2;
import com.openslx.eaas.imagearchive.api.v2.databind.MetaDataKindV2;
import com.openslx.eaas.imagearchive.databind.ImageMetaData;
import com.openslx.eaas.resolver.DataResolvers;
import de.bwl.bwfla.common.exceptions.BWFLAException;
import de.bwl.bwfla.common.services.security.AuthenticatedUser;
import de.bwl.bwfla.common.services.security.UserContext;
import de.bwl.bwfla.emucomp.api.ImageArchiveBinding;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;


@ApplicationScoped
public class PatchesV1 implements IPatchesV1
{
	private Logger log;
	private ImageArchiveClient imagearchive;

	@Inject
	private ImageGeneralizationPatches patches;

	@Inject
	@AuthenticatedUser
	private UserContext userctx;


	@PostConstruct
	protected void initialize()
	{
		log = patches.logger();

		try {
			imagearchive = ImageArchiveClient.create();
		}
		catch (Exception error) {
			throw new RuntimeException(error);
		}
	}

	@PreDestroy
	protected void destroy()
	{
		try {
			if (imagearchive != null)
				imagearchive.close();
		}
		catch (Exception error) {
			log.log(Level.WARNING, "Closing image-archive client failed!", error);
		}
	}

	@Override
	public Streamable<ImageGeneralizationPatchDescription> list()
	{
		final Function<ImageGeneralizationPatch, ImageGeneralizationPatchDescription> mapper =
				(patch) -> new ImageGeneralizationPatchDescription(patch.getName(), patch.getDescription());

		return Streamable.of(patches.list(), mapper);
	}

	@Override
	public ImageGeneralizationPatchResponse apply(String patchId, ImageGeneralizationPatchRequest request)
			throws BWFLAException
	{
		final var origImage = imagearchive.api()
				.v2()
				.metadata(MetaDataKindV2.IMAGES)
				.fetch(request.getImageId(), ImageArchiveMappers.JSON_TREE_TO_IMAGE_METADATA);

		final String newImageId = this.createPatchedImage(request.getImageId(), patchId);

		final var newImage = new ImageMetaData()
				.setId(newImageId)
				.setFileSystemType(origImage.fileSystemType())
				.setLabel(origImage.label() + " (generalized)")
				.setCategory(request.getImageType());

		final var options = new ReplaceOptionsV2()
				.setLocation(request.getArchive());

		imagearchive.api()
				.v2()
				.metadata(MetaDataKindV2.IMAGES)
				.replace(newImageId, newImage, ImageArchiveMappers.OBJECT_TO_JSON_TREE, options);

		final var response = new ImageGeneralizationPatchResponse();
		response.setStatus("0");
		response.setImageId(newImageId);
		return response;
	}

	public String createPatchedImage(String imageId, String patchId) throws BWFLAException
	{
		if (patchId == null)
			throw new BadRequestException("Invalid image-generalization patch ID!");

		return this.createPatchedImage(imageId, patches.lookup(patchId));
	}


	protected String createPatchedImage(String bfid, ImageGeneralizationPatch patch)
			throws BWFLAException
	{
		if (bfid == null)
			throw new BadRequestException("Invalid image's ID!");

		final var binding = new ImageArchiveBinding();
		binding.setImageId(bfid);

		final var bfurl = DataResolvers.images()
				.resolve(binding, userctx);

		try {
			log.info("Preparing image '" + bfid + "' for patching with patch '" + patch.getName() + "'...");
			final var filename = java.util.UUID.randomUUID().toString();
			final var imgurl = patch.applyto(bfurl, filename, log);

			final var request = new ImportRequestV2();
			request.source()
					.setUrl(imgurl.toString());

			request.target()
					.setKind(ImportTargetV2.Kind.IMAGE);

			final var imgid = imagearchive.api()
					.v2()
					.imports()
					.await(request, 1L, TimeUnit.HOURS);

			log.info("Finished patching image '" + bfid + "', result stored as image '" + imgid + "'");
			return imgid;
		}
		catch (Exception error) {
			throw new BWFLAException(error);
		}
	}
}
