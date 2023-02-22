package com.openslx.eaas.generalization;

import org.apache.tamaya.ConfigurationProvider;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.logging.Logger;


public class GeneralizationPatchesClient
{

	private final WebTarget target;

	private static final Logger LOG = Logger.getLogger("PATCHES-CLIENT");


	public GeneralizationPatchesClient()
	{
		final String url = ConfigurationProvider.getConfiguration()
				.get("generalization.rest_url");

		target = ClientBuilder.newClient().target(url);
	}

	public Response getPatches()
	{
		return target.path("/patches").request().get();
	}

	public Response patchImage(String patchId, ImageGeneralizationPatchRequest request)
	{
		return target.path("/" + patchId).request().post(Entity.entity(request, MediaType.APPLICATION_JSON));
	}

}
